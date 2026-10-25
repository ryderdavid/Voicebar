package com.ryderdavid.voicebar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import androidx.annotation.VisibleForTesting
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ryderdavid.voicebar.voice.UsageLog
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The docked style: a one-row bar under the app, in the navigation area's surface color.
 *
 * At rest it holds ⋮, three equal pills in the middle (Cursor, Voice, ABC), then ⌫ and ↵. Each
 * pill grows into its tool: Voice stretches across the bar (✕ and ✓ grow in at the ends), ABC
 * swells up into the keyboard and lands as the space bar, Cursor swells up into the trackpad and
 * becomes Done. Every control springs between layouts; ones that leave shrink to nothing where
 * they stand, ones that arrive grow out of nothing where they land.
 *
 * Hold ↵ and ⌫ turns into space (slide onto it); hold ⌫ and ↵ turns into delete-all.
 */
class DockView(context: Context, attrs: AttributeSet?) : View(context, attrs), KeyboardSurface {

    var listener: Bar.Listener? = null

    enum class Drawer { NONE, PAD, KEYS }
    var drawer = Drawer.NONE
        private set

    /** What a control does. */
    enum class Act {
        MORE, CURSOR, MIC, KEYBOARD, BACKSPACE, ENTER, SPACE_ALT, CLEAR_ALL, DISCARD, FINISH,
        CHIP_SELECT_ALL, CHIP_CUT, CHIP_COPY, CHIP_PASTE, CHIP_DELETE, PAD,
        /** While dictating: throw the dictation away and paste the clipboard instead. */
        TALK_PASTE,
        PANEL,
        /** The floating pill's grip (⠿): drag it to move the pill. */
        GRIP,
    }

    /** A control where it should be now: [id] ties it to its animation across layouts. */
    private class Item(val id: String, val act: Act, val rect: RectF, val alpha: Float = 1f, val corner: Float = -1f, val ch: Char = ' ')

    /** Where a control is drawn right now, springing toward its [Item]. */
    private class Anim(val id: String, var act: Act) {
        val v = FloatArray(6) // left, top, right, bottom, corner, alpha
        val vel = FloatArray(6)
        val target = FloatArray(6)
        var present = true
        /** The alpha it stands at when shown: 1, or [GHOST] for a control that's there but not pressable. */
        var full = 1f
        val rect = RectF()
    }

    private var palette = Palette(context)
    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    // ---------------------------------------------------------------- the service's side

    override var hasSelection = false
        set(v) { if (field != v) { field = v; invalidate() } }
    override var canPaste = false
        set(v) { if (field != v) { field = v; invalidate() } }
    override var clipPreview: String? = null
        set(v) { if (field != v) { field = v; invalidate() } }
    override var dictations: List<DictationHistory.Entry> = emptyList()
        set(v) { field = v; if (menuHeld != null && v.none { it.time == menuHeld }) menuHeld = null; invalidate() }
    override var voiceInField = false
        set(v) { field = v; invalidate() }
    override var voice = Bar.Voice.OFF
        private set
    private var voiceText = ""
    /** Listening (a dictation, or what to fix): the screen stays on, so a long one isn't cut off by the screen dimming or locking. */
    private var talking = false
        set(v) { field = v; keepScreenOn = v }
    private var talkStartedAt = 0L
    private var silenceMs = Prefs.DEFAULT_SILENCE_TIMEOUT * 1000L
    private var silenceDeadline = 0L
    private val levels = FloatArray(WAVE_HISTORY)
    private var levelPeak = 0.08f

    private val hideVoiceRunnable = Runnable { hideVoice() }
    /** This quiet stretch has already asked for the pause cleanup. */
    private var quietSent = false
    private val silenceRunnable = object : Runnable {
        override fun run() {
            if (!talking || silenceDeadline == 0L) return
            val now = SystemClock.uptimeMillis()
            val left = silenceDeadline - now
            val quietAt = silenceDeadline - silenceMs + Bar.quietMs(silenceMs)
            if (!quietSent && now >= quietAt) { quietSent = true; listener?.onQuiet() }
            if (left > 0) { postDelayed(this, if (quietSent) left else minOf(left, quietAt - now)); return }
            endTalk(cancel = false, polish = false)
        }
    }

    override fun showListening() {
        removeCallbacks(hideVoiceRunnable)
        connecting = true
        voice = Bar.Voice.LISTENING
        voiceText = ""
        commandSaid = null
        levels.fill(0f)
        levelPeak = 0.08f
        invalidate()
    }

    override fun showLevel(level: Float) {
        val raw = level.coerceIn(0f, 1f)
        levelPeak = max(raw, levelPeak * 0.985f).coerceAtLeast(0.08f)
        System.arraycopy(levels, 1, levels, 0, levels.size - 1)
        levels[levels.size - 1] = (raw / levelPeak).coerceIn(0f, 1f)
        invalidate()
    }

    override fun showLiveText(text: String) { voiceText = text; invalidate() }

    /** What's been said after "voice bar", while the pill is purple; null when it isn't. */
    private var commandSaid: String? = null
    private var commandUnderstood = false
    private var commandAt = 0L

    override fun showCommand(said: String?, understood: Boolean) {
        val wantsRoom = said != null
        if (wantsRoom != floatHint) { floatHint = wantsRoom; requestLayout() }
        if (said != null && commandSaid == null) {
            commandAt = SystemClock.uptimeMillis()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        if (said == null && commandSaid != null) commandAt = SystemClock.uptimeMillis()
        if (understood && !commandUnderstood) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        commandSaid = said
        commandUnderstood = said != null && understood
        invalidate()
    }

    @VisibleForTesting internal val testCommand get() = commandSaid

    override fun showCleaning(text: String) {
        voice = Bar.Voice.CLEANING
        voiceText = text
        invalidate()
    }

    /** What a tap on the message does, if anything. */
    private var messageAction: (() -> Unit)? = null

    /** The message showing is good news (a note), not a warning. */
    private var messageGood = false

    override fun showNote(message: String, onTap: (() -> Unit)?) {
        showMessage(message, onTap)
        messageGood = true
        invalidate()
    }

    override fun showMessage(message: String, onTap: (() -> Unit)?) {
        messageGood = false
        removeCallbacks(hideVoiceRunnable)
        voice = Bar.Voice.MESSAGE
        voiceText = message
        messageAction = onTap
        postDelayed(hideVoiceRunnable, if (onTap != null) MESSAGE_ACTION_MS else MESSAGE_MS)
        invalidate()
    }

    override fun hideVoice() {
        messageAction = null
        removeCallbacks(hideVoiceRunnable)
        voice = Bar.Voice.OFF
        voiceText = ""
        invalidate()
    }

    /** When speech was last heard (or dictation started): the hint flashes after 3 s of quiet. */
    private var lastHeardAt = 0L

    /**
     * The recognizer isn't connected yet (ElevenLabs) or loaded (Parakeet): the mic is recording and
     * the audio is held for it, and the pill says so. Ends at [showReady], or at the first words.
     */
    private var connecting = false

    override fun showReady() {
        if (!connecting) return
        connecting = false
        invalidate()
    }

    @VisibleForTesting internal val testConnecting get() = connecting

    override fun heardSpeech() {
        // words are coming back, so it's hearing, whatever said so first
        connecting = false
        lastHeardAt = SystemClock.uptimeMillis()
        if (talking && silenceDeadline != 0L) {
            silenceDeadline = SystemClock.uptimeMillis() + silenceMs
            quietSent = false
        }
    }

    override fun talkEnded() {
        fixMode = false
        commandSaid = null
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        invalidate()
    }

    override fun reset() {
        if (padActive) endPad()
        if (talking) endTalk(cancel = true, polish = false)
        removeCallbacks(repeatRunnable)
        if (swiping) swipeEnd(commit = false)
        pressed = null
        closePicker()
        closeMenu()
        titleCaseOn = false
        resumeAfterDrawer = false // leaving the field: no listening to come back to
        setDrawer(Drawer.NONE)
        keys.reset()
        invalidate()
    }

    /** Seven equal pills at rest instead of three wide ones between ⋮ and ⌫. */
    /**
     * "Tiered" colors: one dark (primary) action at a time; modes tonal; keys neutral like the
     * keyboard's own keys; ↵ primary-container; ⋮ bare; an open mode (Done, ⋮) tertiary.
     */
    private var tiered = false
    /** A settings-screen preview: drawn at rest, ignores touches and insets. */
    private var preview = false
    /** On a wide screen, the buttons kept together at one side (or the middle) instead of spread across. */
    private var barSide = Prefs.DEFAULT_BAR_SIDE

    /** Turn this view into a still, untouchable picture of the bar at rest in [layout]. */
    private var previewColors: String? = null
    /**
     * What each of the seven-button bar's own keys does, by its slot: ⋮'s at the far left, the one
     * between ⋮ and Cursor, ⌫'s right of Voice and ↵'s at the far right (the right two stay put while
     * dictating). Any of them can be any [Bar.ExtraKey].
     */
    private val own = HashMap<Act, Bar.ExtraKey>()
    /** Own keys to show instead of the saved ones, in a preview (by slot, as prefs). */
    private var previewKeys: Map<Act, String?> = emptyMap()
    private var previewFloatKey: String? = null

    /** The bar's style ([Prefs.BAR_STYLE]): floating draws the keys in a pill clear of the screen's edges. */
    private var floating = false
    /** The floating bar collapsed to its record pill, floating over the app wherever it was left. */
    private var collapsed = false
    /** The collapsed pill's center: fractions of the width and of the height above the navigation area. */
    private var pillFx = DEFAULT_PILL_FX
    private var pillFy = DEFAULT_PILL_FY
    /** The open floating pill: where it was dragged (center fractions), unless it's parked at the bottom. */
    private var floatFx = 0.5f
    private var floatFy = 1f
    private var parked = true
    /** The floating bar stands upright against a side edge ([Prefs.BAR_UPRIGHT]). */
    private var upright = false
    private var previewStyle: String? = null

    fun showAsPreview(colors: String? = null, key: String? = null, rightKey: String? = null,
                      enterKey: String? = null, style: String? = null, floatKey: String? = null) {
        preview = true
        previewColors = colors
        previewStyle = style
        previewKeys = mapOf(Act.SPACE_ALT to key, Act.BACKSPACE to rightKey, Act.ENTER to enterKey)
        previewFloatKey = floatKey
        insetOverride = 0
        bottomInset = 0
        isClickable = false
        reloadSettings()
    }

    override fun reloadSettings() {
        palette = Palette(context)
        tiered = (previewColors ?: Prefs.get(context).getString(Prefs.BAR_COLORS, Prefs.DEFAULT_BAR_COLORS)) == Prefs.BAR_COLORS_TIERED
        abcTapGboard = Prefs.get(context).getString(Prefs.ABC_TAP, Prefs.DEFAULT_ABC_TAP) == Prefs.ABC_TAP_GBOARD
        keys.swipeEnabled = Prefs.get(context).getBoolean(Prefs.SWIPE_TYPING, Prefs.DEFAULT_SWIPE_TYPING)
        barSide = Prefs.get(context).getString(Prefs.BAR_SIDE, Prefs.DEFAULT_BAR_SIDE) ?: Prefs.DEFAULT_BAR_SIDE
        val prefs = Prefs.get(context)
        floating = (previewStyle ?: prefs.getString(Prefs.BAR_STYLE, Prefs.DEFAULT_BAR_STYLE)) == Prefs.BAR_STYLE_FLOATING
        collapsed = floating && !preview && prefs.getBoolean(Prefs.BAR_COLLAPSED, false)
        pillFx = prefs.getFloat(Prefs.PILL_X, DEFAULT_PILL_FX).coerceIn(0f, 1f)
        pillFy = prefs.getFloat(Prefs.PILL_Y, DEFAULT_PILL_FY).coerceIn(0f, 1f)
        floatFx = prefs.getFloat(Prefs.FLOAT_X, 0.5f).coerceIn(0f, 1f)
        floatFy = prefs.getFloat(Prefs.FLOAT_Y, 1f).coerceIn(0f, 1f)
        parked = prefs.getBoolean(Prefs.FLOAT_PARKED, true)
        upright = prefs.getBoolean(Prefs.BAR_UPRIGHT, false)
        own.clear()
        for ((act, slot) in ownSlots()) {
            val shown = if (floating) previewFloatKey else previewKeys[act]
            own[act] = Bar.ExtraKey.of(shown ?: prefs.getString(slot.first, slot.second.pref), slot.second)
        }
        requestLayout()
        silenceMs = Prefs.get(context).getInt(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT)
            .coerceIn(Prefs.SILENCE_TIMEOUT_RANGE) * 1000L
        invalidate()
    }

    // ---------------------------------------------------------------- talking

    override fun startVoice() = startTalk()

    override fun finishVoice() { if (talking) endTalk(cancel = false, polish = false) }

    override fun showHeld(message: String, onTap: () -> Unit) {
        showNote(message, onTap)
        removeCallbacks(hideVoiceRunnable)
        postDelayed(hideVoiceRunnable, HELD_MS)
    }

    private fun startTalk(fix: Boolean = false) {
        if (talking) return
        if ((if (fix) listener?.onFixStart() else listener?.onTalkStart()) == true) {
            onUsed?.invoke("voice")
            fixMode = fix
            // listening again by hand: no pause left to resume, nor a keyboard to go back to
            resumeAfterDrawer = false
                removeCallbacks(resumeRunnable)
            // the keyboard has the bar's row: it gives it back to the recording pill
            if (drawer == Drawer.KEYS) setDrawer(Drawer.NONE)
            talking = true
            talkStartedAt = SystemClock.uptimeMillis()
            lastHeardAt = talkStartedAt
            silenceDeadline = talkStartedAt + silenceMs
            quietSent = false
            removeCallbacks(silenceRunnable)
            postDelayed(silenceRunnable, Bar.quietMs(silenceMs))
            if (drawer == Drawer.PAD) setDrawer(Drawer.NONE)
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY)
        }
        invalidate()
    }

    /** ✓ (and a tap on the pill) always polishes; the silence timeout follows the setting. */
    private fun endTalk(cancel: Boolean, polish: Boolean) {
        fixMode = false
        commandSaid = null
        silenceDeadline = 0L
        removeCallbacks(silenceRunnable)
        talking = false
        onUsed?.invoke("finish")
        if (cancel) listener?.onTalkEnd(true) else listener?.onTalkFinish(polish)
        invalidate()
    }

    @VisibleForTesting internal val testTalking get() = talking

    // ---------------------------------------------------------------- geometry

    private var bottomInset = 0
    private var insetOverride = -1
    private val barH get() = dp(BAR_DP)
    private val drawerPx get() = dp(if (roomFor == Drawer.KEYS) KEYS_DP else DRAWER_DP)
    /** The panel the drawer's room is sized for: the one open, or the one closing. */
    private var roomFor = Drawer.NONE
    /** The drawer's room stays while a closing panel shrinks back into its pill. */
    private var drawerRoom = false
    private val barTop get() = when {
        freeFloat -> floatTop()
        // a settings preview of the floating pill: in the middle of its room
        preview && inPill -> (height - barH) / 2
        else -> height - bottomInset - barH
    }

    /**
     * The open floating pill's top, in view coordinates: parked at the bottom (the app above it), or
     * wherever it was dragged; low enough for an open panel to fit above it.
     */
    private fun floatTop(): Float {
        if (vert) return columnTop()
        val areaH = (height - bottomInset).toFloat()
        val bottom = areaH - barH
        val m = dp(FLOAT_MARGIN_DP)
        var t = if (parked) bottom else (floatFy * areaH - barH / 2).coerceIn(m, bottom)
        if (drawer != Drawer.NONE || drawerRoom) t = max(t, drawerPx + m).coerceAtMost(bottom)
        return t
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateInset(WindowInsetsCompat.toWindowInsetsCompat(insets, this))
        return super.onApplyWindowInsets(insets)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateInset()
        requestApplyInsets()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        // the keyboard window is reused between fields; re-read the insets each time it comes back
        if (visibility == VISIBLE) { updateInset(); requestApplyInsets() }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        updateInset(); requestApplyInsets()
    }

    /**
     * How far the bar must sit above the bottom so the system's navigation (gesture handle, or
     * the back/home/recents and ⌄/switcher buttons) never draws on top of it. Insets are read
     * ignoring visibility, because while the window is being shown or the caption band is
     * hidden they can briefly report 0; and when they still say 0 in portrait, the system's own
     * navigation bar height is the floor.
     */
    private fun updateInset(given: WindowInsetsCompat? = null) {
        if (insetOverride >= 0) return
        val root = given ?: ViewCompat.getRootWindowInsets(this)
        fun bottom(type: Int): Int {
            root ?: return 0
            val shown = root.getInsets(type).bottom
            val any = if (type == WindowInsetsCompat.Type.captionBar()) 0
                else runCatching { root.getInsetsIgnoringVisibility(type).bottom }.getOrDefault(0)
            return maxOf(shown, any)
        }
        val nav = bottom(WindowInsetsCompat.Type.navigationBars())
        val mode = navMode()
        var v = maxOf(
            nav,
            bottom(WindowInsetsCompat.Type.captionBar()),
            bottom(WindowInsetsCompat.Type.tappableElement()),
            // before Android 15 the keyboard can't hide the system's ⌄/switcher band in gesture
            // navigation, so the bar sits above it (see BubbleView.imeNavFrameHeight)
            if (nav > 0 && Build.VERSION.SDK_INT < 35 && mode == NAV_GESTURE) sysDimen("navigation_bar_frame_height") else 0,
        )
        val c = resources.configuration
        val navAtBottom = c.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT || c.smallestScreenWidthDp >= 600
        // gesture navigation (nothing tappable down there): on Android 15+ the keyboard hides the
        // system's ⌄/switcher band, so the bar only needs to clear the gesture handle. The insets
        // and the platform dimens still report a full button-bar's height on some phones (One UI),
        // which left a tall empty band under the bar; keep just the handle's room.
        val tappable = runCatching { root?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.tappableElement())?.bottom }.getOrNull() ?: 0
        val gestures = mode == NAV_GESTURE || (nav > 0 && tappable == 0)
        if (Build.VERSION.SDK_INT >= 35 && gestures && navAtBottom) {
            v = nav.coerceIn(dp(GESTURE_ROOM_MIN_DP).toInt(), dp(GESTURE_ROOM_MAX_DP).toInt())
        } else if (navAtBottom) v = maxOf(v, navFloor(mode))
        if (v != bottomInset) { bottomInset = v; requestLayout(); invalidate() }
    }

    /** The smallest the bottom gap can honestly be for this navigation mode. */
    private fun navFloor(mode: Int): Int = when (mode) {
        NAV_GESTURE -> if (Build.VERSION.SDK_INT < 35) sysDimen("navigation_bar_frame_height")
            else sysDimen("navigation_bar_gesture_height").takeIf { it > 0 } ?: sysDimen("navigation_bar_height")
        else -> sysDimen("navigation_bar_height")
    }

    private fun navMode(): Int = runCatching {
        android.provider.Settings.Secure.getInt(context.contentResolver, "navigation_mode", 0)
    }.getOrDefault(0)

    private fun sysDimen(name: String): Int {
        @Suppress("DiscouragedApi")
        val id = resources.getIdentifier(name, "dimen", "android")
        return if (id != 0) runCatching { resources.getDimensionPixelSize(id) }.getOrDefault(0) else 0
    }

    @VisibleForTesting fun testSetBottomInset(px: Int) { insetOverride = px; bottomInset = px; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (collapsed || freeFloat) {
            // the whole screen, see-through: the pill can be anywhere, and the service passes every touch
            // but the pill's (and its panels') to the app
            val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) resources.displayMetrics.heightPixels
                else MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            return
        }
        val room = drawer != Drawer.NONE || drawerRoom
        // a settings preview of the floating pill gets room above and below, for its edge and shadow
        val previewRoom = if (preview && inPill) 2 * dp(PREVIEW_ROOM_DP) else 0f
        setMeasuredDimension(w, ((if (room) drawerPx else 0f) + floatRoom() + barH + bottomInset + previewRoom).toInt())
    }

    /**
     * Room at the top of the view that's see-through and floats over the app (the window grows
     * by it, but the service keeps the app where it was and lets touches through): the "swipe up"
     * hint above the purple pill. 0 when there's none (or a drawer has the room).
     */
    fun floatRoom(): Int = when {
        collapsed -> max(0, height - bottomInset - barH.toInt())
        // the list may reach above an open panel (the keyboard's ⋮): only what it adds is see-through
        menuList -> max(0, menuNeed() - if (drawer != Drawer.NONE || drawerRoom) drawerPx.toInt() else 0)
        picker -> pickerRoom().toInt()
        drawer != Drawer.NONE || drawerRoom -> 0
        tip != null -> tipRoom
        floatHint -> dp(FLOAT_DP).toInt()
        else -> 0
    }

    // ---------------------------------------------------------------- first-run tips

    /**
     * A first-run tip: a bubble floating over the app, pointing down at one of the bar's controls
     * ([anchor], an animation id like "voice"): step [n] of [of] of the tour, with Dismiss, Back and
     * Next (Done on the last), or, when [ask], the "again later?" question with No thanks and Later.
     */
    data class Tip(val anchor: String, val title: String, val body: String, val n: Int, val of: Int, val ask: Boolean = false)

    var tip: Tip? = null
        set(v) { if (field != v) { field = v; tipAt = SystemClock.uptimeMillis(); tipRoom = measureTipRoom(); requestLayout(); invalidate() } }
    private var tipAt = 0L
    private var tipRoom = 0
    /** Where the bubble's buttons are, in view coordinates, for taps. */
    private val tipButtons = ArrayList<Pair<RectF, Tips.Answer>>()
    /** A button on the bubble was tapped. */
    var onTipAnswer: ((Tips.Answer) -> Unit)? = null
    /** A feature was used ("voice", "finish", "abc", "cursor", "bksp"): its tip is no longer needed. */
    var onUsed: ((String) -> Unit)? = null

    /** The tip's bubble in view coordinates (fully shown), for the window's touchable area; null without one. */
    fun tipRect(): RectF? {
        if (collapsed || vert) return null // nothing on the bar to point at
        val t = tip ?: return null
        val a = anims[t.anchor]?.rect ?: return null
        val w = tipWidth()
        val h = tipHeight(t)
        val left = (a.centerX() - w / 2).coerceIn(dp(8f), width - w - dp(8f))
        val bottom = barTop - dp(14f)
        return RectF(left, bottom - h, left + w, bottom)
    }

    private val tipTitle = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(15f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val tipBody = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13.5f) }
    private val tipButton = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(13.5f); typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }

    private fun tipWidth() = min(width.toFloat().takeIf { it > 0 } ?: resources.displayMetrics.widthPixels.toFloat(), dp(340f) + dp(16f)) - dp(16f)

    private fun tipLayout(t: Tip): android.text.StaticLayout {
        val inner = (tipWidth() - dp(32f)).toInt().coerceAtLeast(1)
        return android.text.StaticLayout.Builder.obtain(t.body, 0, t.body.length, tipBody, inner).setLineSpacing(dp(2f), 1f).build()
    }

    /** The bubble's height plus its tail and a margin: the floating room it needs. */
    private fun tipHeight(t: Tip) = dp(16f) + dp(20f) + dp(6f) + tipLayout(t).height + dp(14f) + dp(36f) + dp(12f)

    private fun measureTipRoom(): Int = tip?.let { (tipHeight(it) + dp(18f)).toInt() } ?: 0

    private fun drawTip(canvas: Canvas, now: Long) {
        val t = tip ?: return
        val a = anims[t.anchor]?.rect ?: return
        val pal = palette
        val e = ((now - tipAt) / 280f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        val w = tipWidth()
        val h = tipHeight(t)
        val ax = a.centerX()
        val left = (ax - w / 2).coerceIn(dp(8f), width - w - dp(8f))
        val bottom = barTop - dp(14f) + dp(10f) * (1f - e)
        val box = RectF(left, bottom - h, left + w, bottom)
        val r = dp(22f)
        // the control it's about: a soft ring that breathes
        val pulse = 0.5f + 0.5f * sin(now / 320.0).toFloat()
        val ring = RectF(a).apply { offset(0f, barTop); inset(-dp(3f) - dp(2f) * pulse, -dp(3f) - dp(2f) * pulse) }
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2.5f); color = alpha(pal.command, e * (0.55f + 0.45f * pulse)) }
        val rr = min(ring.height(), ring.width()) / 2
        canvas.drawRoundRect(ring, rr, rr, st)
        // shadow, bubble, and a tail down to the control
        for (i in 1..5) {
            fill.color = Color.argb((12 * e).toInt(), 0, 0, 0)
            canvas.drawRoundRect(box.left - i * dp(1f), box.top - i * dp(1f) + dp(2f), box.right + i * dp(1f), box.bottom + i * dp(1f) + dp(2f), r + i * dp(1f), r + i * dp(1f), fill)
        }
        fill.color = alpha(pal.command, e)
        canvas.drawRoundRect(box, r, r, fill)
        val tx = ax.coerceIn(box.left + dp(18f), box.right - dp(18f))
        val tail = android.graphics.Path().apply {
            moveTo(tx - dp(9f), box.bottom - dp(1f)); lineTo(tx, box.bottom + dp(9f)); lineTo(tx + dp(9f), box.bottom - dp(1f)); close()
        }
        canvas.drawPath(tail, fill)
        // title, with "1 of 6" on the right
        val on = pal.onCommand
        val pad = dp(16f)
        tipTitle.color = alpha(on, e)
        canvas.drawText(t.title, box.left + pad, box.top + pad - tipTitle.ascent(), tipTitle)
        tipBody.color = alpha(on, e * 0.8f)
        if (!t.ask) {
            tipBody.textAlign = Paint.Align.RIGHT
            canvas.drawText("${t.n} of ${t.of}", box.right - pad, box.top + pad - tipTitle.ascent(), tipBody)
            tipBody.textAlign = Paint.Align.LEFT
        }
        tipBody.color = alpha(on, e * 0.92f)
        canvas.save()
        canvas.translate(box.left + pad, box.top + pad + dp(20f) + dp(6f))
        tipLayout(t).draw(canvas)
        canvas.restore()
        // buttons: Dismiss / No thanks on the left (text); Back (text) and Next / Done / Later (filled) on the right
        tipButtons.clear()
        val by = box.bottom - dp(12f) - dp(36f)
        tipButton.textAlign = Paint.Align.CENTER
        val mid = by + dp(18f) - (tipButton.ascent() + tipButton.descent()) / 2
        val main = context.getString(when { t.ask -> R.string.tip_later; t.n >= t.of -> R.string.tip_done; else -> R.string.tip_next })
        val mw = tipButton.measureText(main) + dp(32f)
        val mainRect = RectF(box.right - pad + dp(4f) - mw, by, box.right - pad + dp(4f), by + dp(36f))
        fill.color = alpha(on, e)
        canvas.drawRoundRect(mainRect, dp(18f), dp(18f), fill)
        tipButton.color = alpha(pal.command, e)
        canvas.drawText(main, mainRect.centerX(), mid, tipButton)
        tipButtons += mainRect to (if (t.ask) Tips.Answer.LATER else Tips.Answer.NEXT)
        tipButton.color = alpha(on, e * 0.85f)
        if (!t.ask && t.n > 1) {
            val back = context.getString(R.string.tip_back)
            val bw = tipButton.measureText(back) + dp(24f)
            val backRect = RectF(mainRect.left - dp(4f) - bw, by, mainRect.left - dp(4f), by + dp(36f))
            canvas.drawText(back, backRect.centerX(), mid, tipButton)
            tipButtons += backRect to Tips.Answer.BACK
        }
        val leftLabel = context.getString(if (t.ask) R.string.tip_never else R.string.tip_dismiss)
        val lw = tipButton.measureText(leftLabel) + dp(24f)
        val leftRect = RectF(box.left + pad - dp(12f), by, box.left + pad - dp(12f) + lw, by + dp(36f))
        tipButton.color = alpha(on, e * 0.7f)
        canvas.drawText(leftLabel, leftRect.centerX(), mid, tipButton)
        tipButtons += leftRect to (if (t.ask) Tips.Answer.NEVER else Tips.Answer.DISMISS)
        tipButton.textAlign = Paint.Align.LEFT
        postInvalidateOnAnimation()
    }

    /** A tap in the floating room while a tip shows: its buttons, or nothing. */
    private fun tipTap(x: Float, y: Float): Boolean {
        if (tip == null || y >= barTop) return false
        val pad = dp(6f)
        // nearest first, so neighbouring buttons' padding doesn't steal a tap
        tipButtons.firstOrNull { (r, _) -> r.contains(x, y) }?.let { onTipAnswer?.invoke(it.second); return true }
        tipButtons.firstOrNull { (r, _) -> RectF(r).apply { inset(-pad, -pad) }.contains(x, y) }?.let { onTipAnswer?.invoke(it.second) }
        return true
    }

    /** The "swipe up" hint wants room above the bar. */
    private var floatHint = false

    private fun setDrawer(to: Drawer) {
        if (collapsed && to != Drawer.NONE) setCollapsed(false)
        // the menu opened from the keyboard's ⋮ closes back to the keyboard
        val d = to
        if (drawer == d) return
        val opening = drawer == Drawer.NONE
        val was = drawer
        drawer = d
        if (was == Drawer.KEYS) { keys.cancel(); listener?.onKeysShown(false) }
        if (d == Drawer.NONE && resumeAfterDrawer) { resumeRunnable.tries = 0; removeCallbacks(resumeRunnable); post(resumeRunnable) }
        if (d == Drawer.KEYS) listener?.onKeysShown(true)
        if (d != Drawer.NONE) {
            // the keyboard needs more room than the pad or the menu
            val resized = roomFor != d && (roomFor == Drawer.KEYS || d == Drawer.KEYS)
            roomFor = d
            if (opening && !drawerRoom) { drawerRoom = true; requestLayout() } else if (resized) requestLayout()
        }
        invalidate()
    }

    // ---------------------------------------------------------------- layout (y is relative to the bar's top)

    private val items = mutableListOf<Item>()
    /** Where the recording pill started (after the paste button, if any), so polishing stays put. */
    private var talkLeft = 0f
    /** The paste button's width while talking (0 when there wasn't one), so polishing keeps it, ghosted. */
    private var talkPasteW = 0f
    private val drawerItems = mutableListOf<Item>()

    private fun rect(l: Float, t: Float, w: Float, h: Float) = RectF(l, t, l + w, t + h)

    /**
     * The part of the bar the buttons use, as (left, width): all of it, or on a wide screen with a
     * side chosen, a phone-width stretch at the left, middle or right, within reach of a thumb.
     */
    internal fun buttonSpan(full: Float, side: String = barSide): Pair<Float, Float> {
        if (side == Prefs.BAR_SIDE_FULL || full < dp(WIDE_DP)) return 0f to full
        val w = dp(SPAN_DP)
        return when (side) {
            Prefs.BAR_SIDE_LEFT -> 0f
            Prefs.BAR_SIDE_RIGHT -> full - w
            else -> (full - w) / 2
        } to w
    }

    private fun layoutAll() {
        items.clear()
        drawerItems.clear()
        if (width <= 0) return
        if (vert) { layoutUpright(); return }
        if (collapsed) { layoutCollapsed(); return }
        // floating: a pill just big enough for its five keys, at the docked keys' size, in the middle
        // (the keyboard still opens full width)
        val (x0, w) = if (inPill) pillSpan() else buttonSpan(width.toFloat())
        try { layoutSpan(w) } finally {
            if (x0 != 0f) (items + drawerItems).map { it.rect }.distinctBy { System.identityHashCode(it) }.forEach { it.offset(x0, 0f) }
        }
        // the keyboard's keys, in its open panel (after the offset, so they sit where it is drawn)
        if (drawer == Drawer.KEYS) items.firstOrNull { it.id == "keysPanel" }?.let { keys.layout(it.rect) }
    }

    /** The floating bar, open: its keys sit in a pill (not while the keyboard, which opens full width, is up). */
    private val inPill get() = floating && !collapsed && drawer != Drawer.KEYS
    /** The open floating pill on the keyboard (not a settings preview): it can be anywhere on the screen. */
    private val freeFloat get() = inPill && !preview
    /** Upright: the floating bar (open or collapsed) as a column against a side edge; not while the keyboard's up. */
    private val vert get() = upright && floating && !preview && drawer != Drawer.KEYS

    // ---------------------------------------------------------------- upright

    private val ukey get() = dp(UPRIGHT_KEY_DP)
    private val ugap get() = dp(6f)
    private val upad get() = dp(6f)
    private val ugrip get() = dp(20f)
    private val colW get() = ukey + 2 * upad
    /** The column's height: ⠿ and five keys open, ⠿ ☰ and Voice collapsed. */
    private fun columnH() = 2 * upad + ugrip + ugap + if (collapsed) 2 * ukey + ugap else 5 * ukey + 4 * ugap
    /** On the right half of the screen (or the left): its menus and panels open toward the middle. */
    private fun columnRight() = columnLeft() + colW / 2 >= width / 2f
    /** Where it was left across the screen (its center, as a fraction), kept on screen; at an edge, against it. */
    private fun columnLeft(): Float {
        val m = dp(FLOAT_MARGIN_DP)
        val cx = (if (collapsed) pillFx else floatFx) * width
        return (cx - colW / 2).coerceIn(m, max(m, width - m - colW))
    }
    /** The column's top, in view coordinates: where it was dragged, kept on screen. */
    private fun columnTop(): Float {
        val areaH = (height - bottomInset).toFloat()
        val m = dp(FLOAT_MARGIN_DP)
        val h = columnH()
        return ((if (collapsed) pillFy else floatFy) * areaH - h / 2).coerceIn(m, max(m, areaH - m - h))
    }

    /**
     * Upright: ⠿ · ☰ · Cursor · ABC · Voice · own key in a column (collapsed, ⠿ · ☰ · Voice), round
     * keys, nothing sliding while dictating; notes, and a "voice bar" command, beside Voice.
     */
    private fun layoutUpright() {
        val top = columnTop()
        val cl = columnLeft() + upad
        var y = top - barTop + upad
        val k = if (voice == Bar.Voice.CLEANING) GHOST else 1f
        items.add(Item("grip", Act.GRIP, rect(cl, y, ukey, ugrip))); y += ugrip + ugap
        items.add(Item("more", Act.MORE, rect(cl, y, ukey, ukey), if (talking) 1f else k)); y += ukey + ugap
        val voiceAt: RectF
        if (collapsed) {
            voiceAt = rect(cl, y, ukey, ukey)
        } else {
            items.add(Item("cursor", Act.CURSOR, rect(cl, y, ukey, ukey), k)); y += ukey + ugap
            items.add(Item("abc", Act.KEYBOARD, rect(cl, y, ukey, ukey), k)); y += ukey + ugap
            voiceAt = rect(cl, y, ukey, ukey); y += ukey + ugap
            items.add(Item("enter", Act.ENTER, rect(cl, y, ukey, ukey), k))
        }
        items.add(Item("voice", Act.MIC, voiceAt))
        // a note, or the "voice bar" command: beside Voice, toward the middle, with room for the words
        if (voice == Bar.Voice.MESSAGE || commandSaid != null) {
            val nw = min(dp(UPRIGHT_NOTE_DP), width - colW - 3 * dp(FLOAT_MARGIN_DP))
            val nx = if (columnRight()) voiceAt.left - upad - dp(8f) - nw else voiceAt.right + upad + dp(8f)
            items.add(Item("note", Act.MIC, rect(nx, voiceAt.top, nw, ukey)))
        }
        if (collapsed) return
        // the cursor pad: beside the column, its top level with the column's, kept on screen
        val padOpen = drawer == Drawer.PAD
        val cursor = items.first { it.id == "cursor" }.rect
        if (padOpen) {
            val pw = min(dp(UPRIGHT_PANEL_DP), width - colW - 3 * dp(FLOAT_MARGIN_DP))
            val px = if (columnRight()) columnLeft() - dp(8f) - pw else columnLeft() + colW + dp(8f)
            val groupH = drawerPx - dp(2f)
            val areaH = (height - bottomInset).toFloat()
            val gt = (top - barTop).coerceAtMost(areaH - dp(FLOAT_MARGIN_DP) - barTop - groupH)
            val n = drawerItems.size
            layoutPad(pw, -drawerPx)
            val dy = gt + drawerPx
            for (i in n until drawerItems.size) drawerItems[i].rect.offset(px, dy)
            items.add(0, Item("padBack", Act.PANEL, RectF(px, gt, px + pw, gt + groupH), 1f, dp(PANEL_CORNER_DP)))
            items.add(0, Item("padPanel", Act.PANEL, RectF(px + dp(10f), gt + dp(54f), px + pw - dp(10f), gt + groupH - dp(4f)), 1f, dp(PANEL_CORNER_DP)))
        } else {
            items.add(0, Item("padBack", Act.PANEL, RectF(cursor), 0f, ukey / 2))
            items.add(0, Item("padPanel", Act.PANEL, RectF(cursor), 0f, ukey / 2))
        }
    }

    /** Turned upright, or back on its side; remembered. */
    private fun setUpright(v: Boolean) {
        if (preview || upright == v) return
        upright = v
        // upright, it starts against the right edge, part-way up; on its side again, centered and parked
        if (v) { floatFx = 1f; if (parked || floatFy > 0.8f) floatFy = 0.55f } else { floatFx = 0.5f }
        Prefs.get(context).edit().putBoolean(Prefs.BAR_UPRIGHT, v).putFloat(Prefs.FLOAT_X, floatFx).putFloat(Prefs.FLOAT_Y, floatFy).apply()
        if (drawer != Drawer.NONE) setDrawer(Drawer.NONE)
        closePicker()
        closeMenu()
        anims.clear()
        requestLayout()
        invalidate()
    }

    @VisibleForTesting internal val testUpright get() = vert

    /**
     * Floating (not while the keyboard, which docks, is up): where the app's content ends, in view
     * coordinates (the view's height when the app keeps the whole screen), and what takes touches.
     * Null when docked.
     */
    fun floatingInsets(): Pair<Int, List<RectF>>? {
        if (preview || !(collapsed || freeFloat) || width <= 0) return null
        layoutAll()
        val touch = ArrayList<RectF>()
        if (collapsed) collapsedPill()?.let { touch += it }
        else {
            touch += pillRect().apply { offset(0f, barTop) }
            for (id in listOf("padPanel", "padBack")) items.firstOrNull { it.id == id && it.alpha > 0f }?.let { touch += RectF(it.rect).apply { offset(0f, barTop) } }
        }
        if (menuList) touch += menuBox().apply { offset(0f, barTop) }
        if (picker) pickerBox()?.let { touch += it.apply { offset(0f, barTop) } }
        if (!collapsed) tipRect()?.let { touch += it }
        items.firstOrNull { it.id == "note" }?.let { touch += RectF(it.rect).apply { offset(0f, barTop) } }
        // parked at the bottom, the app sits above the pill (the strip just above it is see-through, for its edge)
        val content = if (!collapsed && parked && !vert) (barTop - dp(FLOAT_EDGE_DP)).toInt() else height
        return content to touch
    }

    /** Where the floating pill is across the bar, and how wide: five keys the size of the docked bar's seven. */
    private fun pillSpan(): Pair<Float, Float> {
        val (x0, w) = buttonSpan(width.toFloat())
        val pad = dp(6f)
        val gap = dp(5f)
        val ew = (w - 2 * pad - 6 * gap) / 7
        val pw = dp(GRIP_DP) + gap + 5 * ew + 4 * gap + 2 * pad
        if (!freeFloat) return x0 + (w - pw) / 2 to pw
        // where it was dragged, kept on screen
        val m = dp(FLOAT_MARGIN_DP)
        val cx = (floatFx * width).coerceIn(m + pw / 2, width - m - pw / 2)
        return cx - pw / 2 to pw
    }

    /** The floating pill's outline, in bar coordinates (upright, the column). */
    private fun pillRect(): RectF {
        if (vert) return RectF(columnLeft(), columnTop() - barTop, columnLeft() + colW, columnTop() - barTop + columnH())
        val (x0, w) = pillSpan()
        return RectF(x0, 0f, x0 + w, barH)
    }

    /**
     * Collapsed: just the record pill (☰ and Voice), wherever it was left. Dictating, or showing a
     * note, it grows into the recording pill toward the middle of the screen.
     */
    private fun layoutCollapsed() {
        val pad = dp(6f)
        val gap = dp(5f)
        val b = dp(40f)
        val m = dp(FLOAT_MARGIN_DP)
        val w = width.toFloat()
        val areaH = (height - bottomInset).toFloat()
        val ph = b + 2 * pad
        val grip = dp(GRIP_DP)
        val small = pad + grip + gap + b + gap + dp(COLLAPSED_MIC_DP) + pad
        val wide = talking || voice != Bar.Voice.OFF || commandSaid != null
        val pw = if (wide) min(w - 2 * m, dp(COLLAPSED_WIDE_DP)) else small
        val cx = (pillFx * w).coerceIn(m + small / 2, w - m - small / 2)
        val top = (pillFy * areaH - ph / 2).coerceIn(m, areaH - m - ph)
        var left = if (cx < w / 2) cx - small / 2 else cx + small / 2 - pw
        left = left.coerceIn(m, w - m - pw)
        val y = top - barTop + pad
        if (wide) {
            items.add(Item("grip", Act.GRIP, rect(left + pad, y, grip, b), 0f))
            items.add(Item("more", Act.MORE, rect(left + pad, y, b, b), 0f))
            items.add(Item("voice", Act.MIC, rect(left + pad, y, pw - 2 * pad, b)))
        } else {
            items.add(Item("grip", Act.GRIP, rect(left + pad, y, grip, b)))
            items.add(Item("more", Act.MORE, rect(left + pad + grip + gap, y, b, b)))
            items.add(Item("voice", Act.MIC, rect(left + pad + grip + gap + b + gap, y, dp(COLLAPSED_MIC_DP), b)))
        }
    }

    /** The collapsed pill on screen (view coordinates), for the touchable region; null when it isn't collapsed. */
    fun collapsedPill(): RectF? {
        if (!collapsed || width <= 0) return null
        layoutAll()
        val r = items.filter { (it.act == Act.MIC || it.act == Act.MORE || it.act == Act.GRIP) && it.alpha > 0f && it.id != "note" }.map { it.rect }.reduceOrNull { a, c -> RectF(a).apply { union(c) } } ?: return null
        val pad = dp(6f)
        return RectF(r.left - pad, r.top - pad + barTop, r.right + pad, r.bottom + pad + barTop)
    }

    /** Collapses the floating bar to its record pill, or opens it again; remembered. */
    private fun setCollapsed(v: Boolean) {
        if (!floating || collapsed == v) return
        if (drawer != Drawer.NONE) setDrawer(Drawer.NONE)
        closePicker()
        closeMenu()
        collapsed = v
        if (!preview) Prefs.get(context).edit().putBoolean(Prefs.BAR_COLLAPSED, v).apply()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        anims.clear() // a different shape altogether: no morph from one to the other
        requestLayout()
        invalidate()
    }

    @VisibleForTesting internal val testCollapsed get() = collapsed
    @VisibleForTesting internal fun testCollapsedPill() = collapsedPill()

    private fun layoutSpan(w: Float) {
        val pad = dp(6f)
        val gap = dp(5f)
        val b = dp(40f)
        val y = (barH - b) / 2
        // docked: seven equal keys, ☰ · own key · Cursor ABC Voice · own key · own key; floating: five,
        // ☰ · Cursor ABC Voice · own key (the own key in ↵'s slot, so it stays put while dictating)
        val n = if (inPill) 5 else 7
        // floating, the grip (⠿) comes first: drag it to move the pill
        val g = if (inPill) dp(GRIP_DP) + gap else 0f
        if (inPill && !(drawer == Drawer.KEYS && !talking)) items.add(Item("grip", Act.GRIP, rect(pad, y, dp(GRIP_DP), b)))
        val ew = (w - 2 * pad - g - (n - 1) * gap) / n
        fun slot(i: Int) = rect(pad + g + i * (ew + gap), y, ew, b)
        val c0 = if (inPill) 1 else 2 // where Cursor sits
        val more = slot(0)
        val cursorPill = slot(c0)
        val abcPill = slot(c0 + 1)
        val voicePill = slot(c0 + 2)
        val busyVoice = voice == Bar.Voice.CLEANING || voice == Bar.Voice.MESSAGE
        fun rightKeys(k: Float = 1f) {
            if (!inPill) items.add(Item("bksp", Act.BACKSPACE, slot(5), k))
            items.add(Item("enter", Act.ENTER, slot(n - 1), k))
        }

        when {
            // the keyboard is up and has the bar's row: the bar's own keys (and a dictation's polishing,
            // which goes on into the field) give way until it's pulled down
            drawer == Drawer.KEYS && !talking -> Unit
            talking || voice == Bar.Voice.CLEANING -> {
                // dictating: Cursor and ABC slide left into the first places, pushing ☰ (and the own key
                // after it) off the bar's left edge, and Voice becomes the recording pill over the rest;
                // the keys right of Voice stay where they were. Tapping Cursor or ABC pauses the
                // dictation for the pad or the keyboard. Polishing keeps the places, the keys ghosted.
                val k = if (talking) 1f else GHOST
                val out = c0 * (ew + gap)
                items.add(Item("more", Act.MORE, RectF(more).apply { offset(-out, 0f) }, 0f))
                if (!inPill) items.add(Item("space", Act.SPACE_ALT, slot(1).apply { offset(-out, 0f) }, 0f))
                val talkAbc = slot(1)
                items.add(Item("cursor", Act.CURSOR, slot(0), k))
                items.add(Item("abc", Act.KEYBOARD, talkAbc, k))
                items.add(Item("voice", Act.MIC, RectF(talkAbc.right + gap, y, voicePill.right, y + b)))
                rightKeys(k)
            }
            busyVoice -> items.add(Item("voice", Act.MIC, RectF(pad, y, w - pad, y + b)))
            !talking && voice == Bar.Voice.OFF && commandSaid != null -> {
                // "voice bar" heard at rest: the Voice pill grows over Cursor and ABC, so there's
                // room to say what it's waiting for
                items.add(Item("more", Act.MORE, more))
                if (!inPill) items.add(Item("space", Act.SPACE_ALT, slot(1)))
                items.add(Item("voice", Act.MIC, RectF(cursorPill.left, y, voicePill.right, y + b)))
                rightKeys()
            }
            else -> {
                items.add(Item("more", Act.MORE, more))
                if (!inPill) items.add(Item("space", Act.SPACE_ALT, slot(1)))
                items.add(Item("cursor", Act.CURSOR, cursorPill))
                items.add(Item("abc", Act.KEYBOARD, abcPill))
                items.add(Item("voice", Act.MIC, voicePill))
                rightKeys()
            }
        }

        // the panels: each rests, invisible, on the control it grows out of
        val top = -drawerPx
        val padOpen = drawer == Drawer.PAD
        items.add(0, Item("padPanel", Act.PANEL, if (padOpen) RectF(dp(10f), top + dp(54f), w - dp(10f), -dp(6f)) else RectF(cursorPill), if (padOpen) 1f else 0f, if (padOpen) dp(PANEL_CORNER_DP) else b / 2))
        // the keyboard takes the bar's row too: its bottom row sits where the thumbs already are
        val keysOpen = drawer == Drawer.KEYS
        items.add(0, Item("keysPanel", Act.PANEL, if (keysOpen) RectF(dp(6f), top + dp(4f), w - dp(6f), barH - dp(2f)) else RectF(abcPill), if (keysOpen) 1f else 0f, if (keysOpen) dp(PANEL_CORNER_DP) else b / 2))

        when (drawer) {
            Drawer.PAD -> layoutPad(w, top)
            Drawer.KEYS, Drawer.NONE -> Unit
        }
    }

    private fun layoutPad(w: Float, top: Float) {
        val side = dp(10f)
        val ch = dp(36f)
        val gap = dp(8f)
        val chips = listOf(Act.CHIP_SELECT_ALL, Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE)
        val cw = (w - 2 * side - (chips.size - 1) * gap) / chips.size
        var x = side
        for (a in chips) {
            drawerItems.add(Item(a.name, a, rect(x, top + dp(10f), cw, ch)))
            x += cw + gap
        }
        drawerItems.add(Item("pad", Act.PAD, RectF(side, top + dp(54f), w - side, -dp(6f))))
    }


    @VisibleForTesting internal fun testItem(act: Act, ch: Char = ' '): RectF? {
        layoutAll()
        val it = (drawerItems + items).firstOrNull { it.act == act && it.alpha > 0f && true } ?: return null
        return RectF(it.rect).apply { offset(0f, barTop) }
    }
    @VisibleForTesting internal fun testHas(act: Act): Boolean = testItem(act) != null
    /** Where control [id] is drawn right now (mid-animation included), in view coordinates. */
    @VisibleForTesting internal fun testAnimRect(id: String): RectF? = anims[id]?.rect?.let { RectF(it).apply { offset(0f, barTop) } }
    @VisibleForTesting internal fun testFrame() { layoutAll(); step(SystemClock.uptimeMillis()) }

    // ---------------------------------------------------------------- motion

    private val anims = LinkedHashMap<String, Anim>()
    private var lastFrame = 0L

    /** Springs every control toward its place; true while anything is still moving. */
    private fun step(now: Long): Boolean {
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1000f).coerceIn(0f, 1f / 30f)
        lastFrame = now
        val seen = HashSet<String>()
        for (it in items) {
            seen.add(it.id)
            val a = anims.getOrPut(it.id) {
                // a newcomer grows out of nothing where it lands (a panel, out of its pill)
                Anim(it.id, it.act).also { n ->
                    n.v[0] = it.rect.left; n.v[1] = it.rect.top; n.v[2] = it.rect.right; n.v[3] = it.rect.bottom
                    n.v[4] = if (it.corner >= 0f) it.corner else it.rect.height() / 2
                    n.v[5] = if (it.act == Act.PANEL) it.alpha else 0f
                }
            }
            a.act = it.act
            a.present = true
            a.target[0] = it.rect.left; a.target[1] = it.rect.top; a.target[2] = it.rect.right; a.target[3] = it.rect.bottom
            a.target[4] = if (it.corner >= 0f) it.corner else it.rect.height() / 2
            a.target[5] = it.alpha
            if (it.alpha > 0f) a.full = it.alpha
        }
        for ((id, a) in anims) if (id !in seen) { a.present = false; a.target[5] = 0f }
        var moving = false
        val k = SPRING_K
        val c = 2f * SPRING_ZETA * sqrt(k)
        for (a in anims.values) {
            for (i in 0 until 6) {
                if (dt == 0f) continue
                val damping = if (i == 5) 2f * sqrt(k) else c // alpha never overshoots
                a.vel[i] += (k * (a.target[i] - a.v[i]) - damping * a.vel[i]) * dt
                a.v[i] += a.vel[i] * dt
                if (abs(a.target[i] - a.v[i]) > 0.5f || abs(a.vel[i]) > 1f) moving = true else { a.v[i] = a.target[i]; a.vel[i] = 0f }
            }
            a.v[5] = a.v[5].coerceIn(0f, 1f)
            a.rect.set(a.v[0], a.v[1], a.v[2], a.v[3])
        }
        anims.entries.removeAll { !it.value.present && it.value.v[5] <= 0.01f && it.value.target[5] == 0f }
        // the drawer's room goes once nothing is left in it
        if (drawer == Drawer.NONE && drawerRoom) {
            val busy = anims.values.any { it.rect.top < -dp(2f) && it.v[5] > 0.02f }
            if (!busy) { drawerRoom = false; requestLayout() }
        }
        return moving
    }

    /** Jump straight to the end of every move (tests, and the first frame). */
    @VisibleForTesting internal fun settle() {
        layoutAll()
        lastFrame = 0L
        step(SystemClock.uptimeMillis())
        for (a in anims.values) { System.arraycopy(a.target, 0, a.v, 0, 6); a.vel.fill(0f); a.rect.set(a.v[0], a.v[1], a.v[2], a.v[3]) }
        anims.entries.removeAll { !it.value.present }
        if (drawer == Drawer.NONE && drawerRoom) { drawerRoom = false; requestLayout() }
    }

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val p = pressed ?: return
            if (!deletes(p.act)) return
            if (repeatHalted) return
            consumed = true // the release isn't one more delete
            onUsed?.invoke("bksp")
            listener?.onKey(Bar.Key.DELETE, true)
            postDelayed(this, REPEAT_MS)
        }
    }
    private var repeatHalted = false

    /** When ⌫ was last tapped: pressing it again soon after and holding deletes repeatedly. */
    private var lastBkspTap = 0L

    // ---------------------------------------------------------------- Voicebar's keyboard

    /** A tap on ABC opens Gboard instead (and a hold, Voicebar's keyboard). */
    private var abcTapGboard = false

    private val keys: PopKeys = PopKeys(object : PopKeys.Host {
        override fun dp(v: Float) = this@DockView.dp(v)
        override fun post(r: Runnable, delayMs: Long) { postDelayed(r, delayMs) }
        override fun cancel(r: Runnable) { removeCallbacks(r) }
        override fun invalidate() = this@DockView.invalidate()
        override fun haptic(type: Int) { performHapticFeedback(type) }
        override fun type(text: String) { listener?.onText(text) }
        override fun key(key: Bar.Key, repeat: Boolean) { listener?.onKey(key, repeat) }
        override fun suggest(word: String) { listener?.onSuggestion(word) }
        override fun close() {
            // let go of where it was pulled to, so it shrinks back into ABC from there
            anims["keysPanel"]?.let { a -> val d = keys.pull; a.v[1] += d; a.v[3] += d; a.rect.offset(0f, d) }
            setDrawer(Drawer.NONE)
        }
        override fun menu(anchor: RectF) = openMenu(anchor)
        override fun gboard() { resumeAfterDrawer = false; setDrawer(Drawer.NONE); onUsed?.invoke("abc"); listener?.onSwitchToGboard() }
        override fun swipe(points: List<com.ryderdavid.voicebar.swipe.SwipeDecoder.Point>, keys: FloatArray, caps: Int) { listener?.onSwipe(points, keys, caps) }
    })

    override var keySuggestions: List<String>
        get() = keys.suggestions
        set(v) { keys.suggestions = v }
    override var keyAutoCap: Boolean
        get() = keys.autoCap
        set(v) { keys.autoCap = v }
    override var enterAction: Int
        get() = keys.enterAction
        set(v) { keys.enterAction = v }

    /** Voice held at rest: straight into fixing what's in the box, by voice. */
    private val micHoldRunnable = Runnable {
        val p = pressed ?: return@Runnable
        if (p.act != Act.MIC || sliding || talking || voice != Bar.Voice.OFF || drawer != Drawer.NONE) return@Runnable
        consumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startFix()
    }

    private val abcHoldRunnable = Runnable {
        val p = pressed ?: return@Runnable
        if (p.act != Act.KEYBOARD) return@Runnable
        consumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        abc(hold = true)
    }

    /**
     * ABC: Voicebar's keyboard pops up (or goes away), or Gboard takes over the field; which one a
     * tap gives and which a hold is the ABC setting. Dictating, what's been said is finished first,
     * unless listening only just started: then it was probably incidental, and is thrown away.
     */
    private fun abc(hold: Boolean) {
        val l = listener
        if (talking && SystemClock.uptimeMillis() - talkStartedAt < EARLY_KEYS_MS) {
            AppLog.log("bar", "keyboard within ${EARLY_KEYS_MS / 1000} s of listening: dictation dropped")
            endTalk(cancel = true, polish = false)
            onUsed?.invoke("abc")
            resumeAfterDrawer = false
            if (hold != abcTapGboard) { setDrawer(Drawer.NONE); l?.onSwitchToGboard() } else setDrawer(Drawer.KEYS)
            return
        }
        if (hold != abcTapGboard) {
            // typing is Gboard's: ABC hands the field over
            if (voice == Bar.Voice.CLEANING && !talking) l?.onTypeAfterTalk() else if (talking) {
                endTalk(cancel = false, polish = true)
                l?.onTypeAfterTalk()
            } else { onUsed?.invoke("abc"); resumeAfterDrawer = false; setDrawer(Drawer.NONE); l?.onSwitchToGboard() }
            return
        }
        onUsed?.invoke("abc")
        if (talking) { pauseFor(Drawer.KEYS); return }
        setDrawer(if (drawer == Drawer.KEYS) Drawer.NONE else Drawer.KEYS)
    }

    /**
     * Dictation paused for the cursor pad or the keyboard: what's been said is finished (polished)
     * so it's real text to work on, and listening starts again where the cursor is once that panel
     * closes. Leaving the field, or switching to Gboard, doesn't resume it.
     */
    private var resumeAfterDrawer = false
        set(v) { field = v; invalidate() }

    private fun pauseFor(d: Drawer) {
        onUsed?.invoke(if (d == Drawer.PAD) "cursor" else "abc")
        // what to fix isn't a dictation to come back to: it's dropped, and the panel opens
        if (fixMode) { endTalk(cancel = true, polish = false); setDrawer(d); return }
        endTalk(cancel = false, polish = true)
        setDrawer(d)
        resumeAfterDrawer = true
    }

    /** The panel a pause opened has closed: listen again, once the polish has gone in. */
    private val resumeRunnable = object : Runnable {
        var tries = 0
        override fun run() {
            if (!resumeAfterDrawer || drawer != Drawer.NONE) return
            if (voice == Bar.Voice.CLEANING && tries++ < RESUME_TRIES) { postDelayed(this, RESUME_POLL_MS); return }
            resumeAfterDrawer = false
            tries = 0
            startTalk()
        }
    }

    /** The keyboard's touches (every finger, so two thumbs can roll); false when it isn't open or wasn't touched. */
    private var keysGesture = false

    private fun keysTouch(e: MotionEvent): Boolean {
        val oy = barTop
        if (e.actionMasked == MotionEvent.ACTION_DOWN) keysGesture = drawer == Drawer.KEYS && keys.contains(e.x, e.y - oy)
        if (!keysGesture) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> e.actionIndex.let { keys.down(e.getPointerId(it), e.getX(it), e.getY(it) - oy) }
            // every point the touchscreen reported since the last frame, for swipes
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                for (h in 0 until e.historySize) keys.move(e.getPointerId(i), e.getHistoricalX(i, h), e.getHistoricalY(i, h) - oy, e.getHistoricalEventTime(h))
                keys.move(e.getPointerId(i), e.getX(i), e.getY(i) - oy, e.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> e.actionIndex.let { keys.up(e.getPointerId(it), e.getX(it), e.getY(it) - oy, e.eventTime) }
            MotionEvent.ACTION_UP -> { keys.up(e.getPointerId(0), e.x, e.y - oy, e.eventTime); keysGesture = false }
            MotionEvent.ACTION_CANCEL -> { keys.cancel(); keysGesture = false }
        }
        return true
    }

    @VisibleForTesting internal val testKeys get() = keys
    @VisibleForTesting internal val testBarTop get() = barTop

    // ---------------------------------------------------------------- the own key's choices

    /**
     * The seven-button bar's own key held, or swiped up from: every choice in a column above it,
     * space nearest the thumb. Slide onto one and let go, or let go first and tap one; that one
     * becomes the key (nothing runs: press the key to use it). Anywhere else closes it.
     */
    private var picker = false
    private var pickerAt = 0L
    /** The choice under the finger, if any. */
    private var pickerHot: Bar.ExtraKey? = null
    /** A press that started on the open column (the tap after letting go). */
    private var pickerTap = false
    /** Which own key the open column chooses for, by its slot ([ownSlots]). */
    private var pickerFor = Act.SPACE_ALT
    private val pickerHoldRunnable = Runnable {
        val p = pressed ?: return@Runnable
        if (keyOf(p.act) == null || sliding || picker) return@Runnable
        openPicker(p.act)
    }

    /** The own keys by slot: docked, the key after ☰ and the two right of Voice; floating, the one right of Voice. */
    private fun ownSlots() = if (floating) FLOAT_SLOTS else DOCKED_SLOTS

    /** What an own key does (null for ☰, Cursor, ABC and Voice). */
    private fun keyOf(act: Act): Bar.ExtraKey? = own[act]

    /** A press on this deletes: an own key set to Backspace (whose double-tap-and-hold keeps deleting). */
    private fun deletes(act: Act) = keyOf(act) == Bar.ExtraKey.BACKSPACE

    /** This key opens the ⋮ menu: whichever own keys are set to it. */
    private fun opensMenu(act: Act) = act == Act.MORE


    /**
     * An own key pressed: set to Backspace, a second press right after a tap deletes and keeps
     * deleting while held; otherwise a hold opens its choices.
     */
    private fun ownKeyDown(act: Act) {
        val now = SystemClock.uptimeMillis()
        if (keyOf(act) == Bar.ExtraKey.BACKSPACE && lastBkspTap > 0L && now - lastBkspTap < DOUBLE_TAP_MS) {
            lastBkspTap = 0L
            consumed = true
            listener?.onKey(Bar.Key.DELETE, false)
            postDelayed(repeatRunnable, BKSP_REPEAT_START_MS)
            return
        }
        postDelayed(pickerHoldRunnable, PICKER_HOLD_MS)
    }

    private fun openPicker(act: Act = Act.SPACE_ALT) {
        removeCallbacks(pickerHoldRunnable)
        pickerFor = act
        picker = true
        pickerAt = SystemClock.uptimeMillis()
        pickerHot = null
        consumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        requestLayout() // the window grows by the column's room
        invalidate()
    }

    private fun closePicker() {
        if (!picker) return
        removeCallbacks(pickerHoldRunnable)
        picker = false
        pickerHot = null
        pickerTap = false
        requestLayout()
        invalidate()
    }

    private fun pick(key: Bar.ExtraKey) {
        closePicker()
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val slot = ownSlots().getValue(pickerFor)
        if (own[pickerFor] != key) {
            own[pickerFor] = key
            if (!preview) Prefs.get(context).edit().putString(slot.first, key.pref).apply()
            AppLog.log("bar", "${slot.first} is now ${key.pref}")
            requestLayout() // the menu opens out of whichever key now opens it
        }
        // just set: switching keys never commits to the action; a press uses it
    }

    /** What the own key does when tapped (or picked). Mid-dictation it acts at the cursor like any key. */
    private fun pressExtra(key: Bar.ExtraKey) {
        val l = listener
        when (key) {
            Bar.ExtraKey.SPACE -> l?.onKey(Bar.Key.SPACE, false)
            Bar.ExtraKey.PASTE -> l?.onEdit(Bar.EditAction.PASTE)
            Bar.ExtraKey.UNDO -> l?.onKey(Bar.Key.UNDO, false)
            Bar.ExtraKey.SELECT_ALL -> l?.onEdit(Bar.EditAction.SELECT_ALL)
            Bar.ExtraKey.LAST_DICTATION -> l?.onRepeatDictation()
            // a tap: pressing it again right after and holding keeps deleting (see ownKeyDown)
            Bar.ExtraKey.BACKSPACE -> { l?.onKey(Bar.Key.DELETE, false); lastBkspTap = SystemClock.uptimeMillis() }
            Bar.ExtraKey.FIX_LAST -> startFix()
            // mid-dictation a line break, never Send (as ↵ always is)
            Bar.ExtraKey.ENTER -> l?.onKey(Bar.Key.ENTER, false)
            Bar.ExtraKey.STYLE -> setStyle(!floating)
        }
    }

    private val pickerRowH get() = dp(48f)
    private val pickerPad get() = dp(6f)
    private fun pickerRoom() = Bar.ExtraKey.entries.size * pickerRowH + 2 * pickerPad + dp(PICKER_GAP_DP) + dp(8f)

    /**
     * The column, in bar coordinates (it sits above the bar, so its y's are negative): lined up with
     * its key's left edge, or for the two keys right of Voice, its right edge.
     */
    private fun pickerBox(): RectF? {
        val right = pickerFor == Act.BACKSPACE || pickerFor == Act.ENTER
        val id = when (pickerFor) { Act.MORE -> "more"; Act.BACKSPACE -> "bksp"; Act.ENTER -> "enter"; else -> "space" }
        val key = anims[id]?.rect ?: rectOf(id) ?: return null
        val w = min(dp(PICKER_W_DP), width - 2 * dp(8f))
        val left = (if (right) key.right - w else key.left).coerceIn(dp(8f), width - w - dp(8f))
        val h = Bar.ExtraKey.entries.size * pickerRowH + 2 * pickerPad
        if (vert) return besideColumn(key, w, h)
        val bottom = -dp(PICKER_GAP_DP)
        // the floating pill near the top: below it instead
        if (freeFloat && barTop + bottom - h < dp(8f)) return RectF(left, barH + dp(PICKER_GAP_DP), left + w, barH + dp(PICKER_GAP_DP) + h)
        return RectF(left, bottom - h, left + w, bottom)
    }

    /**
     * A box [w] by [h] beside the upright column, toward the middle of the screen, its top level
     * with [key] where it fits (bar coordinates).
     */
    private fun besideColumn(key: RectF, w: Float, h: Float): RectF {
        val m = dp(8f)
        val left = if (columnRight()) columnLeft() - m - w else columnLeft() + colW + m
        val areaH = (height - bottomInset).toFloat()
        val top = key.top.coerceIn(-barTop + m, max(-barTop + m, areaH - m - barTop - h))
        return RectF(left, top, left + w, top + h)
    }

    /** Row [i] of the column, counted up from the bottom. */
    private fun pickerRow(box: RectF, i: Int) =
        RectF(box.left + pickerPad, box.bottom - pickerPad - (i + 1) * pickerRowH, box.right - pickerPad, box.bottom - pickerPad - i * pickerRowH)

    /** The choice at ([x], [y]) in bar coordinates: by row, with room either side for a drifting thumb. */
    private fun pickerAtPoint(x: Float, y: Float): Bar.ExtraKey? {
        val box = pickerBox() ?: return null
        if (x < box.left - dp(48f) || x > box.right + dp(48f)) return null
        return Bar.ExtraKey.entries.withIndex().firstOrNull { (i, _) -> pickerRow(box, i).let { y >= it.top && y < it.bottom } }?.value
    }

    private fun pickerHover(x: Float, y: Float) {
        val hot = pickerAtPoint(x, y)
        if (hot != pickerHot) {
            pickerHot = hot
            if (hot != null) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            invalidate()
        }
    }

    /** Touches while the column is up and no press of the key is under way: a tap on a choice, or anywhere to close. */
    private fun pickerTouch(e: MotionEvent): Boolean {
        if (pressed != null && !pickerTap) return false // the press that opened it: onMove and onUp handle it
        val x = e.x
        val y = e.y - barTop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pickerTap = pickerAtPoint(x, y) != null
                if (!pickerTap) { closePicker(); pressed = null; consumed = true; return true }
                pressed = null
                pickerHover(x, y)
            }
            MotionEvent.ACTION_MOVE -> if (pickerTap) pickerHover(x, y)
            MotionEvent.ACTION_UP -> if (pickerTap) {
                pickerTap = false
                pickerAtPoint(x, y)?.let { pick(it) } ?: closePicker()
            }
            MotionEvent.ACTION_CANCEL -> { pickerTap = false; pickerHot = null; invalidate() }
        }
        return true
    }

    /** Where the column is on screen (view coordinates), for the touchable region; null when it's down. */
    fun pickerRect(): RectF? = when {
        menuList -> menuBox().apply { offset(0f, barTop) }
        picker -> pickerBox()?.apply { offset(0f, barTop) }
        else -> null
    }

    @VisibleForTesting internal val testPicker get() = picker
    @VisibleForTesting internal fun testPickerRow(key: Bar.ExtraKey): PointF? =
        pickerBox()?.let { pickerRow(it, key.ordinal) }?.let { PointF(it.centerX(), it.centerY() + barTop) }

    private fun drawPicker(canvas: Canvas, now: Long) {
        val box = pickerBox() ?: return
        val pal = palette
        // rises into place
        val e = ((now - pickerAt) / 180f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        if (e < 1f) postInvalidateOnAnimation()
        canvas.save()
        canvas.translate(0f, dp(10f) * (1f - e))
        val r = pickerRowH / 2 + pickerPad
        for (i in 1..5) {
            fill.color = Color.argb((12 * e).toInt(), 0, 0, 0)
            canvas.drawRoundRect(box.left - i * dp(1f), box.top - i * dp(1f) + dp(2f), box.right + i * dp(1f), box.bottom + i * dp(1f) + dp(2f), r + i * dp(1f), r + i * dp(1f), fill)
        }
        fill.color = alpha(pal.cardinal, e)
        canvas.drawRoundRect(box, r, r, fill)
        text.textSize = dp(15f)
        text.textAlign = Paint.Align.LEFT
        for ((i, key) in Bar.ExtraKey.entries.withIndex()) {
            val row = pickerRow(box, i)
            val hot = key == pickerHot
            val current = key == own[pickerFor]
            if (hot || current) {
                fill.color = alpha(if (hot) pal.pressed else pal.diagonal, e)
                canvas.drawRoundRect(row, row.height() / 2, row.height() / 2, fill)
            }
            val c = alpha(when { hot -> pal.onPressed; current -> pal.onDiagonal; else -> pal.onCardinal }, e)
            drawExtraGlyph(canvas, key, row.left + dp(28f), row.centerY(), dp(22f), c)
            text.color = c
            canvas.drawText(context.getString(PICKER_LABELS.getValue(key)), row.left + dp(54f), row.centerY() - (text.ascent() + text.descent()) / 2, text)
        }
        text.textAlign = Paint.Align.CENTER
        canvas.restore()
    }


    // ---------------------------------------------------------------- the menu (☰)

    /** What the menu offers below its row of edit keys, top to bottom. */
    enum class MenuItem { PASTE, DICTATIONS, COLLAPSE, EXPAND, UPRIGHT, SIDEWAYS, DOCK, FLOAT, SWITCH, SETTINGS }
    /** The menu's row of edit keys. */
    enum class MenuEdit { UNDO, CUT, COPY, PASTE, SELECT_ALL }
    /** The menu's pages: its items, or the recent dictations (that row morphs the list into them). */
    enum class MenuPage { MAIN, DICTATIONS }
    /** On the dictations page: its ‹ back, one dictation (by time), or one of a held dictation's options. */
    object MenuBack
    data class DictRow(val time: Long)
    data class DictOpt(val time: Long, val action: Bar.DictationAction)

    /** The menu is up: a list rising from ☰ (or the keyboard's ⋮), like an own key's choices. */
    private var menuList = false
    private var menuAt = 0L
    /** The row or edit key under the finger. */
    private var menuHot: Any? = null
    /** A press that started on the list. */
    private var menuTap = false
    /** The key it rises from, in bar coordinates. */
    private var menuAnchor = RectF()
    private var menuPage = MenuPage.MAIN
    /** The page it's morphing from, and when that began. */
    private var menuFrom = MenuPage.MAIN
    private var menuPageAt = 0L
    /** The dictation held for its options (its time). */
    private var menuHeld: Long? = null
    /** The hold on a dictation has fired: letting go doesn't paste it. */
    private var menuHoldFired = false
    private val menuHoldRunnable = Runnable {
        val k = menuHot as? DictRow ?: return@Runnable
        menuHeld = k.time
        menuHoldFired = true
        menuHot = null
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
    }

    private fun menuItems(): List<MenuItem> = buildList {
        add(MenuItem.PASTE)
        add(MenuItem.DICTATIONS)
        if (collapsed) add(MenuItem.EXPAND) else if (floating) add(MenuItem.COLLAPSE)
        if (floating) add(if (upright) MenuItem.SIDEWAYS else MenuItem.UPRIGHT)
        add(if (floating) MenuItem.DOCK else MenuItem.FLOAT)
        add(MenuItem.SWITCH)
        add(MenuItem.SETTINGS)
    }

    /** The dictations the menu shows: the newest few. */
    private fun menuDictations() = dictations.take(MENU_DICTATIONS)

    /** Where a separator goes: after these. */
    private fun menuBreakAfter(it: MenuItem) = it == MenuItem.DICTATIONS || it == MenuItem.DOCK || it == MenuItem.FLOAT

    private val menuRowH get() = dp(48f)
    private val menuEditH get() = dp(54f)
    private val menuSepH get() = dp(9f)
    private val dictRowH get() = dp(52f)
    private val dictOptH get() = dp(40f)
    private val dictOptGap get() = dp(10f)

    private fun menuHeight(page: MenuPage = menuPage): Float = when (page) {
        MenuPage.MAIN -> {
            val items = menuItems()
            2 * pickerPad + menuEditH + menuSepH + items.size * menuRowH + items.count { menuBreakAfter(it) } * menuSepH
        }
        MenuPage.DICTATIONS -> 2 * pickerPad + menuRowH + menuSepH + max(1, menuDictations().size) * dictRowH +
            (if (menuHeld != null) dictOptH + 2 * dictOptGap else 0f)
    }

    /**
     * The list's box in bar coordinates, for [page]: above its key, lined up with the key's left edge;
     * when the pill is too near the top for that, below it. Above, it grows upward from a fixed bottom.
     */
    private fun menuBox(page: MenuPage = menuPage): RectF {
        val w = min(dp(MENU_W_DP), width - 2 * dp(8f))
        val h = menuHeight(page)
        val left = menuAnchor.left.coerceIn(dp(8f), width - w - dp(8f))
        if (vert) return besideColumn(menuAnchor, w, h)
        val above = menuAnchor.top - dp(PICKER_GAP_DP) - h
        val top = if ((collapsed || freeFloat) && above < -barTop + dp(8f)) menuAnchor.bottom + dp(PICKER_GAP_DP) else above
        return RectF(left, top, left + w, top + h)
    }

    /** How far the morph between pages has got (1: done), eased. */
    private fun menuMorph(now: Long): Float {
        val t = ((now - menuPageAt) / MENU_MORPH_MS.toFloat()).coerceIn(0f, 1f)
        return 1f - (1f - t) * (1f - t) * (1f - t)
    }

    /** The card as drawn: mid-morph, its height between the two pages', from the edge that stays put. */
    private fun menuDrawnBox(now: Long): RectF {
        val to = menuBox()
        val m = menuMorph(now)
        if (m >= 1f) return to
        val h = menuHeight(menuFrom) + (to.height() - menuHeight(menuFrom)) * m
        val below = to.top >= menuAnchor.top - 1f
        return if (below) RectF(to.left, to.top, to.right, to.top + h) else RectF(to.left, to.bottom - h, to.right, to.bottom)
    }

    /** Every tappable place on [page], laid out in [box]. */
    private fun menuTargets(box: RectF, page: MenuPage = menuPage): List<Pair<Any, RectF>> {
        val out = ArrayList<Pair<Any, RectF>>()
        var y = box.top + pickerPad
        when (page) {
            MenuPage.MAIN -> {
                val cw = (box.width() - 2 * pickerPad) / MenuEdit.entries.size
                for ((i, e) in MenuEdit.entries.withIndex()) out += e to RectF(box.left + pickerPad + i * cw, y, box.left + pickerPad + (i + 1) * cw, y + menuEditH)
                y += menuEditH + menuSepH
                for (it in menuItems()) {
                    out += it to RectF(box.left + pickerPad, y, box.right - pickerPad, y + menuRowH)
                    y += menuRowH + if (menuBreakAfter(it)) menuSepH else 0f
                }
            }
            MenuPage.DICTATIONS -> {
                out += MenuBack to RectF(box.left + pickerPad, y, box.right - pickerPad, y + menuRowH)
                y += menuRowH + menuSepH
                for (d in menuDictations()) {
                    out += DictRow(d.time) to RectF(box.left + pickerPad, y, box.right - pickerPad, y + dictRowH)
                    y += dictRowH
                    if (d.time == menuHeld) {
                        // its options, in a row of their own, with room above and below
                        y += dictOptGap
                        val opts = listOf(Bar.DictationAction.PASTE_HEARD, Bar.DictationAction.RECLEAN, Bar.DictationAction.DELETE)
                        val g = dp(6f)
                        val ow = (box.width() - 2 * pickerPad - 2 * dp(4f) - (opts.size - 1) * g) / opts.size
                        for ((i, a) in opts.withIndex()) {
                            val l = box.left + pickerPad + dp(4f) + i * (ow + g)
                            out += DictOpt(d.time, a) to RectF(l, y, l + ow, y + dictOptH)
                        }
                        y += dictOptH + dictOptGap
                    }
                }
            }
        }
        return out
    }

    /** The room the list needs above the bar's top edge (0 when it fits below it). */
    private fun menuNeed(): Int = max(0f, -menuBox().top + dp(8f)).toInt()

    private fun menuAt(x: Float, y: Float): Any? = menuTargets(menuBox()).firstOrNull { it.second.contains(x, y) }?.first

    private fun menuEnabled(k: Any) = when (k) {
        MenuEdit.CUT, MenuEdit.COPY -> hasSelection
        MenuEdit.PASTE, MenuItem.PASTE -> canPaste
        is DictOpt -> k.action != Bar.DictationAction.RECLEAN || recleaning == null
        else -> true
    }

    private fun openMenu(anchor: RectF) {
        closePicker()
        menuAnchor = RectF(anchor)
        menuList = true
        menuAt = SystemClock.uptimeMillis()
        menuHot = null
        menuPage = MenuPage.MAIN
        menuFrom = MenuPage.MAIN
        menuPageAt = 0L
        menuHeld = null
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        requestLayout() // the window grows by the list's room
        invalidate()
    }

    private fun closeMenu() {
        if (!menuList) return
        removeCallbacks(menuHoldRunnable)
        menuList = false
        menuHot = null
        menuTap = false
        menuHeld = null
        requestLayout()
        invalidate()
    }

    /** ☰ tapped: the list, or closed again. */
    private fun toggleMenu() {
        if (menuList) { closeMenu(); return }
        openMenu(items.firstOrNull { it.act == Act.MORE }?.rect ?: return)
    }

    /** The list morphs into [page]: the card resizes in place while the rows slide across. */
    private fun menuGoTo(page: MenuPage) {
        if (page == menuPage) return
        menuFrom = menuPage
        menuPage = page
        menuPageAt = SystemClock.uptimeMillis()
        menuHeld = null
        requestLayout()
        invalidate()
    }

    private fun runMenu(k: Any) {
        if (!menuEnabled(k)) return
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        val l = listener
        when (k) {
            // the edit keys leave the list up, for select all then copy
            MenuEdit.UNDO -> l?.onKey(Bar.Key.UNDO, false)
            MenuEdit.CUT -> l?.onEdit(Bar.EditAction.CUT)
            MenuEdit.COPY -> l?.onEdit(Bar.EditAction.COPY)
            MenuEdit.PASTE -> l?.onEdit(Bar.EditAction.PASTE)
            MenuEdit.SELECT_ALL -> l?.onEdit(Bar.EditAction.SELECT_ALL)
            MenuItem.DICTATIONS -> menuGoTo(MenuPage.DICTATIONS)
            MenuBack -> menuGoTo(MenuPage.MAIN)
            is MenuItem -> {
                closeMenu()
                when (k) {
                    MenuItem.PASTE -> l?.onEdit(Bar.EditAction.PASTE)
                    MenuItem.COLLAPSE -> setCollapsed(true)
                    MenuItem.EXPAND -> setCollapsed(false)
                    MenuItem.UPRIGHT -> setUpright(true)
                    MenuItem.SIDEWAYS -> setUpright(false)
                    MenuItem.DOCK -> setStyle(false)
                    MenuItem.FLOAT -> setStyle(true)
                    MenuItem.SWITCH -> { setDrawer(Drawer.NONE); l?.onSwitchKeyboard() }
                    MenuItem.SETTINGS -> { setDrawer(Drawer.NONE); l?.onOpenSettings() }
                    MenuItem.DICTATIONS -> Unit
                }
            }
            // a tap pastes it as typed; with another one's options open, it just closes them
            is DictRow -> if (menuHeld != null) { menuHeld = null; requestLayout() } else {
                closeMenu()
                l?.onDictation(Bar.DictationAction.PASTE_TYPED, k.time)
            }
            is DictOpt -> when (k.action) {
                Bar.DictationAction.PASTE_HEARD -> { closeMenu(); l?.onDictation(k.action, k.time) }
                // cleaned again and copied, with the list left up to watch it happen
                Bar.DictationAction.RECLEAN -> { menuHeld = null; requestLayout(); l?.onDictation(k.action, k.time) }
                else -> { menuHeld = null; requestLayout(); l?.onDictation(k.action, k.time) }
            }
        }
        invalidate()
    }

    /** Touches while the list is up: a tap (or a hold, on a dictation) on a row or key, or anywhere else to close it. */
    private fun menuTouch(e: MotionEvent): Boolean {
        val x = e.x
        val y = e.y - barTop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val k = menuAt(x, y)
                menuTap = k != null
                menuHoldFired = false
                if (!menuTap) { closeMenu(); pressed = null; consumed = true; return true }
                menuHot = k
                if (k is DictRow && menuHeld == null) postDelayed(menuHoldRunnable, CLIP_HOLD_MS)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (menuTap && !menuHoldFired) {
                val k = menuAt(x, y)
                if (k != menuHot) { removeCallbacks(menuHoldRunnable); menuHot = k; invalidate() }
            }
            MotionEvent.ACTION_UP -> if (menuTap) {
                removeCallbacks(menuHoldRunnable)
                menuTap = false
                val k = menuAt(x, y)
                menuHot = null
                if (menuHoldFired) { menuHoldFired = false; requestLayout(); invalidate() }
                else if (k != null) runMenu(k) else invalidate()
            }
            MotionEvent.ACTION_CANCEL -> { removeCallbacks(menuHoldRunnable); menuTap = false; menuHot = null; invalidate() }
        }
        return true
    }

    /** Docked, or floating; remembered (docking also forgets that it was collapsed). */
    private fun setStyle(float: Boolean) {
        if (preview || floating == float) return
        Prefs.get(context).edit().putString(Prefs.BAR_STYLE, if (float) Prefs.BAR_STYLE_FLOATING else Prefs.BAR_STYLE_DOCKED)
            .apply { if (!float) putBoolean(Prefs.BAR_COLLAPSED, false) }.apply()
        if (drawer != Drawer.NONE) setDrawer(Drawer.NONE)
        closePicker()
        AppLog.log("bar", if (float) "floating" else "docked")
        reloadSettings()
        anims.clear()
        requestLayout()
        invalidate()
    }

    @VisibleForTesting internal val testMenu get() = menuList
    @VisibleForTesting internal val testMenuPage get() = menuPage
    @VisibleForTesting internal val testMenuHeld get() = menuHeld
    @VisibleForTesting internal fun testMenuAt(k: Any): PointF? =
        if (!menuList) null else menuTargets(menuBox()).firstOrNull { it.first == k }?.second?.let { PointF(it.centerX(), it.centerY() + barTop) }
    @VisibleForTesting internal fun testMenuItems() = menuItems()

    /** A divider between groups: a line that shows on the list's own color, whatever the palette. */
    private fun menuDivider(canvas: Canvas, box: RectF, y: Float, al: Float) {
        fill.color = alpha(ColorUtils.blendARGB(palette.cardinal, palette.onCardinal, 0.22f), al)
        canvas.drawRect(box.left + dp(18f), y - dp(0.5f), box.right - dp(18f), y + dp(0.5f), fill)
    }

    private fun drawMenu(canvas: Canvas, now: Long) {
        val pal = palette
        val box = menuDrawnBox(now)
        val e = ((now - menuAt) / 180f).coerceIn(0f, 1f).let { 1f - (1f - it) * (1f - it) }
        val m = menuMorph(now)
        if (e < 1f || m < 1f) postInvalidateOnAnimation()
        canvas.save()
        canvas.translate(0f, dp(10f) * (1f - e) * if (box.top > menuAnchor.top) -1f else 1f)
        val r = dp(24f)
        for (i in 1..5) {
            fill.color = Color.argb((12 * e).toInt(), 0, 0, 0)
            canvas.drawRoundRect(box.left - i * dp(1f), box.top - i * dp(1f) + dp(2f), box.right + i * dp(1f), box.bottom + i * dp(1f) + dp(2f), r + i * dp(1f), r + i * dp(1f), fill)
        }
        fill.color = alpha(pal.cardinal, e)
        canvas.drawRoundRect(box, r, r, fill)
        // the pages, inside the card: mid-morph the old one slides out left and the new one in from the right
        canvas.save()
        menuClip.reset(); menuClip.addRoundRect(box, r, r, android.graphics.Path.Direction.CW)
        canvas.clipPath(menuClip)
        val slide = dp(28f)
        val forward = menuPage == MenuPage.DICTATIONS
        if (m < 1f) drawMenuPage(canvas, menuFrom, e * (1f - m), (if (forward) -slide else slide) * m)
        drawMenuPage(canvas, menuPage, e * m, (if (forward) slide else -slide) * (1f - m))
        canvas.restore()
        text.textAlign = Paint.Align.CENTER
        canvas.restore()
    }
    private val menuClip = android.graphics.Path()

    /** One page of the list, laid out in its own box, faded to [al] and moved across by [dx]. */
    private fun drawMenuPage(canvas: Canvas, page: MenuPage, al: Float, dx: Float) {
        if (al <= 0.01f) return
        val pal = palette
        val box = menuBox(page)
        canvas.save()
        canvas.translate(dx, 0f)
        text.textAlign = Paint.Align.LEFT
        val targets = menuTargets(box, page)
        for ((k, row) in targets) {
            val on = menuEnabled(k)
            val hot = k == menuHot && on
            when (k) {
                is MenuEdit -> {
                    val cr = min(row.width(), row.height()) / 2 - dp(3f)
                    fill.color = alpha(if (hot) pal.pressed else if (on) pal.diagonal else Color.TRANSPARENT, al)
                    if (Color.alpha(fill.color) > 0) canvas.drawCircle(row.centerX(), row.centerY(), cr, fill)
                    fill.color = alpha(if (hot) pal.onPressed else if (on) pal.onDiagonal else pal.onCardinal, al * if (on) 1f else 0.38f)
                    val g = when (k) { MenuEdit.UNDO -> Icons.Glyph.UNDO; MenuEdit.CUT -> Icons.Glyph.CUT; MenuEdit.COPY -> Icons.Glyph.COPY; MenuEdit.PASTE -> Icons.Glyph.PASTE; MenuEdit.SELECT_ALL -> Icons.Glyph.SELECT_ALL }
                    Icons.draw(canvas, g, row.centerX(), row.centerY(), dp(21f), fill)
                }
                is MenuItem -> drawMenuItem(canvas, box, k, row, hot, on, al)
                MenuBack -> {
                    // ‹ and the page's title
                    val c = alpha(pal.onCardinal, al)
                    fill.color = alpha(if (hot) pal.pressed else pal.diagonal, al)
                    canvas.drawCircle(row.left + dp(24f), row.centerY(), dp(18f), fill)
                    strokePath(canvas, row.left + dp(24f), row.centerY(), dp(18f), alpha(if (hot) pal.onPressed else pal.onDiagonal, al)) { mv, ln -> mv(14f, 6f); ln(8f, 12f); ln(14f, 18f) }
                    text.textSize = dp(15f)
                    text.color = c
                    canvas.drawText(context.getString(R.string.menu_dictations), row.left + dp(52f), row.centerY() - (text.ascent() + text.descent()) / 2, text)
                    menuDivider(canvas, box, row.bottom + menuSepH / 2, al)
                }
                is DictRow -> drawDictRow(canvas, k, row, hot, al)
                is DictOpt -> {
                    val del = k.action == Bar.DictationAction.DELETE
                    val bg = if (hot) pal.pressed else if (del) pal.discard else pal.diagonal
                    val fg = if (hot) pal.onPressed else if (del) pal.onDiscard else pal.onDiagonal
                    fill.color = alpha(bg, al * if (on) 1f else 0.38f)
                    canvas.drawRoundRect(row, row.height() / 2, row.height() / 2, fill)
                    val label = context.getString(when (k.action) {
                        Bar.DictationAction.PASTE_HEARD -> R.string.dictations_as_said
                        Bar.DictationAction.RECLEAN -> R.string.dictations_clean_up
                        else -> R.string.clips_delete
                    })
                    clipPaint.textSize = dp(13f)
                    clipPaint.color = alpha(fg, al)
                    // one line, always (labels only, so the three match)
                    val shown = android.text.TextUtils.ellipsize(label, clipPaint, row.width() - dp(12f), android.text.TextUtils.TruncateAt.END).toString()
                    val x = row.centerX() - clipPaint.measureText(shown) / 2
                    canvas.drawText(shown, x, row.centerY() - (clipPaint.ascent() + clipPaint.descent()) / 2, clipPaint)
                }
            }
        }
        if (page == MenuPage.MAIN) {
            // under the edit keys
            targets.firstOrNull { it.first is MenuItem }?.second?.let { first -> menuDivider(canvas, box, first.top - menuSepH / 2, al) }
        } else if (menuDictations().isEmpty()) {
            text.textSize = dp(14f)
            text.color = alpha(pal.onCardinal, al * 0.8f)
            val y0 = box.top + pickerPad + menuRowH + menuSepH
            canvas.drawText(context.getString(R.string.dictations_none), box.left + pickerPad + dp(16f), y0 + dictRowH / 2 - (text.ascent() + text.descent()) / 2, text)
        }
        canvas.restore()
    }

    private fun drawMenuItem(canvas: Canvas, box: RectF, item: MenuItem, row: RectF, hot: Boolean, on: Boolean, al: Float) {
        val pal = palette
        if (hot) { fill.color = alpha(pal.pressed, al); canvas.drawRoundRect(row, row.height() / 2, row.height() / 2, fill) }
        val c = alpha(if (hot) pal.onPressed else pal.onCardinal, al * if (on) 1f else 0.38f)
        val ix = row.left + dp(24f)
        fill.color = c
        when (item) {
            MenuItem.PASTE -> Icons.draw(canvas, Icons.Glyph.PASTE, ix, row.centerY(), dp(21f), fill)
            MenuItem.DICTATIONS -> Icons.draw(canvas, Icons.Glyph.HISTORY, ix, row.centerY(), dp(21f), fill)
            MenuItem.COLLAPSE -> drawCollapseGlyph(canvas, ix, row.centerY(), dp(22f), c)
            MenuItem.EXPAND -> drawExpandGlyph(canvas, ix, row.centerY(), dp(22f), c)
            // the bar's outline, the way it'll stand
            MenuItem.UPRIGHT -> strokePath(canvas, ix, row.centerY(), dp(22f), c) { m, l -> m(9f, 3f); l(15f, 3f); l(15f, 21f); l(9f, 21f); l(9f, 3f) }
            MenuItem.SIDEWAYS -> strokePath(canvas, ix, row.centerY(), dp(22f), c) { m, l -> m(3f, 9f); l(21f, 9f); l(21f, 15f); l(3f, 15f); l(3f, 9f) }
            MenuItem.DOCK -> drawDockGlyph(canvas, ix, row.centerY(), dp(22f), c)
            MenuItem.FLOAT -> drawFloatGlyph(canvas, ix, row.centerY(), dp(22f), c)
            MenuItem.SWITCH -> Icons.draw(canvas, Icons.Glyph.SWITCH_KEYBOARD, ix, row.centerY(), dp(21f), fill)
            MenuItem.SETTINGS -> Icons.draw(canvas, Icons.Glyph.SETTINGS, ix, row.centerY(), dp(21f), fill)
        }
        val label = context.getString(MENU_LABELS.getValue(item))
        text.color = c
        text.textSize = dp(15f)
        val tx = row.left + dp(52f)
        val preview = if (item == MenuItem.PASTE) clipPreview?.replace(Regex("\\s+"), " ")?.trim()?.ifBlank { null } else null
        if (preview != null) {
            canvas.drawText(label, tx, row.centerY() - dp(3f), text)
            clipPaint.color = alpha(if (hot) pal.onPressed else pal.onCardinal, al * 0.72f)
            clipPaint.textSize = dp(12f)
            canvas.drawText(android.text.TextUtils.ellipsize("“$preview”", clipPaint, row.right - dp(12f) - tx, android.text.TextUtils.TruncateAt.END).toString(), tx, row.centerY() + dp(14f), clipPaint)
        } else canvas.drawText(label, tx, row.centerY() - (text.ascent() + text.descent()) / 2, text)
        if (item == MenuItem.DICTATIONS) strokePath(canvas, row.right - dp(20f), row.centerY(), dp(18f), c) { m, l -> m(9f, 6f); l(15f, 12f); l(9f, 18f) }
        if (menuBreakAfter(item)) menuDivider(canvas, box, row.bottom + menuSepH / 2, al)
    }

    /** One dictation: a line of what was typed, cut off with an ellipsis, and how long ago. */
    private fun drawDictRow(canvas: Canvas, k: DictRow, row: RectF, hot: Boolean, al: Float) {
        val pal = palette
        val d = dictations.firstOrNull { it.time == k.time } ?: return
        val held = menuHeld == k.time
        val dim = menuHeld != null && !held
        val lit = hot || held
        if (lit) { fill.color = alpha(pal.pressed, al); canvas.drawRoundRect(row, row.height() / 2, row.height() / 2, fill) }
        val c = alpha(if (lit) pal.onPressed else pal.onCardinal, al * if (dim) 0.38f else 1f)
        val now = SystemClock.uptimeMillis()
        val copied = d.time == recleaned && now < recleanedUntil
        val ago = if (copied) context.getString(R.string.dictations_copied) else ago(d.time)
        clipPaint.textSize = dp(12f)
        clipPaint.color = alpha(if (lit) pal.onPressed else pal.onCardinal, al * (if (dim) 0.38f else 0.72f))
        val aw = clipPaint.measureText(ago)
        canvas.drawText(ago, row.right - dp(16f) - aw, row.centerY() - (clipPaint.ascent() + clipPaint.descent()) / 2, clipPaint)
        text.textSize = dp(14.5f)
        text.color = c
        val words = d.typed.ifBlank { d.heard }.replace(Regex("\\s+"), " ").trim()
        val tx = row.left + dp(16f)
        val shown = android.text.TextUtils.ellipsize(words, android.text.TextPaint(text), row.right - dp(16f) - aw - dp(12f) - tx, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, tx, row.centerY() - (text.ascent() + text.descent()) / 2, text)
        if (d.time == recleaning) {
            // being cleaned again: the recording pill's polishing glow, sweeping across the row
            canvas.save()
            pulseClip.reset(); pulseClip.addRoundRect(row, row.height() / 2, row.height() / 2, android.graphics.Path.Direction.CW)
            canvas.clipPath(pulseClip)
            val p = ((now % PULSE_MS) / PULSE_MS.toFloat()).let { it * it * (3f - 2f * it) }
            val sw = row.width() * 0.5f
            val sx = row.left - sw + (row.width() + sw) * p
            val glow = alpha(pal.pressed, al * 0.6f)
            pulsePaint.shader = android.graphics.LinearGradient(sx, 0f, sx + sw, 0f,
                intArrayOf(glow and 0x00ffffff, glow, glow and 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(sx, row.top, sx + sw, row.bottom, pulsePaint)
            canvas.restore()
            postInvalidateOnAnimation()
        }
    }

    /** How long ago [time] (wall-clock ms) was, briefly: now, 12m, 3h, Yesterday, or the date. */
    private fun ago(time: Long): String {
        val mins = (System.currentTimeMillis() - time) / 60_000L
        return when {
            mins < 1 -> context.getString(R.string.ago_now)
            mins < 60 -> context.getString(R.string.ago_minutes, mins)
            mins < 24 * 60 -> context.getString(R.string.ago_hours, mins / 60)
            mins < 48 * 60 -> context.getString(R.string.ago_yesterday)
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(time))
        }
    }

    /** Collapse: two arrows pointing in; expand: pointing out. */
    private fun drawCollapseGlyph(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) =
        strokePath(canvas, x, y, size, c) { m, l -> m(19f, 5f); l(14f, 10f); m(14f, 6f); l(14f, 10f); l(18f, 10f); m(5f, 19f); l(10f, 14f); m(6f, 14f); l(10f, 14f); l(10f, 18f) }
    private fun drawExpandGlyph(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) =
        strokePath(canvas, x, y, size, c) { m, l -> m(14f, 10f); l(19f, 5f); m(15f, 5f); l(19f, 5f); l(19f, 9f); m(10f, 14f); l(5f, 19f); m(5f, 15f); l(5f, 19f); l(9f, 19f) }
    /** Docked: a screen with the bar across its bottom. */
    private fun drawDockGlyph(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) {
        strokePath(canvas, x, y, size, c) { m, l -> m(5f, 4f); l(19f, 4f); l(19f, 20f); l(5f, 20f); l(5f, 4f) }
        val u = size / 24f
        fill.color = c
        canvas.drawRect(x - 7f * u, y + 3f * u, x + 7f * u, y + 8f * u, fill)
    }
    /** Floating: a screen with a pill floating in it. */
    private fun drawFloatGlyph(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) {
        strokePath(canvas, x, y, size, c) { m, l -> m(5f, 4f); l(19f, 4f); l(19f, 20f); l(5f, 20f); l(5f, 4f) }
        val u = size / 24f
        fill.color = c
        canvas.drawRoundRect(x - 4f * u, y - 2f * u, x + 4f * u, y + 2f * u, 2f * u, 2f * u, fill)
    }

    // ---------------------------------------------------------------- fixing the last dictation

    /**
     * Listening for what to fix in the box (an own key set to "Fix last dictation", or Voice held): what's
     * said tells the cleanup's model what's wrong (it's never typed), and the box's text is fixed with that.
     */
    private var fixMode = false
    @VisibleForTesting internal val testFixMode get() = fixMode
    @VisibleForTesting internal fun testStartFix() = startFix()

    /**
     * "Fix last dictation" (an own key, or Voice held): listen for what to fix in the box, when nothing
     * else is going on. The service says so if there's no box, or nothing in it.
     */
    private fun startFix() {
        if (talking || voice != Bar.Voice.OFF) showNote(context.getString(R.string.fix_finish_first)) else startTalk(fix = true)
    }

    // ---------------------------------------------------------------- the trackpad

    private var padActive = false
    private var padSelecting = false
    private var padMoved = false
    private var padX = 0f
    private var padY = 0f
    private var padAccX = 0f
    private var padAccY = 0f
    private var padPointer = 0
    private val padHoldRunnable = Runnable {
        if (!padActive || padMoved) return@Runnable
        padSelecting = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onStickSelect()
        invalidate()
    }

    /** The arrow target a tap landed on (one step that way), or null: the rest of the pad. */
    private var padArrow: IntArray? = null

    /**
     * Faint arrow targets on the trackpad's edges: (dx, dy) and where each is drawn. A tap on one
     * moves the cursor one step that way; a drag that starts on one is still a trackpad drag.
     */
    private fun padArrows(r: RectF): List<Pair<IntArray, RectF>> {
        val cx = r.centerX()
        val i = dp(8f); val long = dp(64f)
        // left and right are wide, reaching in under the hint toward the middle; up and down
        // keep the middle column between them
        val col = long / 2 + dp(6f)
        return listOf(
            intArrayOf(-1, 0) to RectF(r.left + i, r.top + i, cx - col, r.bottom - i),
            intArrayOf(1, 0) to RectF(cx + col, r.top + i, r.right - i, r.bottom - i),
            intArrayOf(0, -1) to RectF(cx - long / 2, r.top + i, cx + long / 2, r.top + i + dp(30f)),
            intArrayOf(0, 1) to RectF(cx - long / 2, r.bottom - i - dp(30f), cx + long / 2, r.bottom - i),
        )
    }

    /** The arrow under a finger at (x, y), with some slack around what's drawn. */
    private fun padArrowAt(r: RectF, x: Float, y: Float): IntArray? {
        if (!r.contains(x, y)) return null
        val a = padArrows(r)
        return when {
            x < a[0].second.right -> a[0].first
            x > a[1].second.left -> a[1].first
            y < r.centerY() -> a[2].first // the middle column: its top half is up, its bottom half down
            else -> a[3].first
        }
    }

    private fun padDown(x: Float, y: Float) {
        padArrow = drawerItems.firstOrNull { it.act == Act.PAD }?.let { padArrowAt(it.rect, x, y) }
        padActive = true
        padSelecting = false
        padMoved = false
        padX = x; padY = y; padAccX = 0f; padAccY = 0f
        listener?.onStickStart()
        postDelayed(padHoldRunnable, PAD_HOLD_MS)
        invalidate()
    }

    private fun padMove(x: Float, y: Float) {
        padAccX += x - padX
        padAccY += y - padY
        padX = x; padY = y
        if (!padMoved && (abs(padAccX) > dp(4f) || abs(padAccY) > dp(4f))) {
            padMoved = true
            removeCallbacks(padHoldRunnable)
        }
        val cs = dp(PAD_COL_DP)
        val ls = dp(PAD_LINE_DP)
        val cols = (padAccX / cs).toInt()
        val lines = (padAccY / ls).toInt()
        if (cols != 0 || lines != 0) {
            padAccX -= cols * cs
            padAccY -= lines * ls
            listener?.onStickStep(cols, lines, padSelecting)
        }
        invalidate()
    }

    /** A different finger drives the trackpad from here; where it is now is its starting point. */
    private fun padHandOff(id: Int, x: Float, y: Float) {
        padPointer = id
        padX = x
        padY = y
        padMoved = true // more than one finger is never a tap
        removeCallbacks(padHoldRunnable)
        invalidate()
    }

    private fun padUp() {
        removeCallbacks(padHoldRunnable)
        val arrow = padArrow
        // a tap on an arrow: one step that way
        if (arrow != null && !padMoved && !padSelecting) listener?.onStickStep(arrow[0], arrow[1], false)
        if (padMoved && !padSelecting) listener?.onStickTap()
        endPad()
    }

    private fun endPad() {
        removeCallbacks(padHoldRunnable)
        padArrow = null
        padActive = false
        padSelecting = false
        listener?.onStickEnd()
        invalidate()
    }

    // ---------------------------------------------------------------- recent dictations (from the menu)

    override var recleaning: Long? = null
        set(v) { field = v; invalidate() }
    /** The dictation whose new cleanup was just copied, and until when its card says so. */
    private var recleaned: Long? = null
    private var recleanedUntil = 0L

    override fun showRecleaned(time: Long) {
        recleaned = time
        recleanedUntil = SystemClock.uptimeMillis() + RECLEANED_MS
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        invalidate()
        postDelayed({ invalidate() }, RECLEANED_MS + 20)
    }

    // ---------------------------------------------------------------- touch

    private var pressed: Item? = null
    /** The collapsed pill is being dragged; where its center was when the drag began. */
    private var dragging = false
    private var dragFromX = 0f
    private var dragFromY = 0f
    /** Where the drag began, in view coordinates (the open pill moves its own bar coordinates as it goes). */
    private var dragDownY = 0f
    private var downX = 0f
    private var downY = 0f
    private var sliding = false
    private var slideX = 0f
    /** The press already did its thing (a hold); its release does nothing more. */
    private var consumed = false

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (preview) return false
        if (menuList && menuTouch(e)) return true
        if (picker && pickerTouch(e)) return true
        if (keysTouch(e)) return true
        if (e.actionMasked == MotionEvent.ACTION_DOWN && tip != null && e.y < barTop) return true
        if (e.actionMasked == MotionEvent.ACTION_UP && tip != null && e.y < barTop && pressed == null) return tipTap(e.x, e.y)
        val oy = barTop
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { padPointer = e.getPointerId(0); onDown(e.x, e.y - oy) }
            MotionEvent.ACTION_MOVE -> {
                if (pressed?.act == Act.PAD) {
                    val i = e.findPointerIndex(padPointer)
                    if (i >= 0) padMove(e.getX(i), e.getY(i) - oy)
                } else onMove(e.x, e.y - oy)
            }
            // on the trackpad another finger takes over the dragging while the first stays down,
            // so a selection can run longer than one sweep of the thumb
            MotionEvent.ACTION_POINTER_DOWN -> if (pressed?.act == Act.PAD) {
                val i = e.actionIndex
                padHandOff(e.getPointerId(i), e.getX(i), e.getY(i) - oy)
            }
            MotionEvent.ACTION_POINTER_UP -> if (pressed?.act == Act.PAD && e.getPointerId(e.actionIndex) == padPointer) {
                val keep = (0 until e.pointerCount).firstOrNull { it != e.actionIndex }
                if (keep != null) padHandOff(e.getPointerId(keep), e.getX(keep), e.getY(keep) - oy)
            }
            MotionEvent.ACTION_UP -> onUp(e.x, e.y - oy)
            MotionEvent.ACTION_CANCEL -> onCancel()
        }
        return true
    }

    private fun hit(x: Float, y: Float): Item? {
        layoutAll()
        val slop = dp(4f)
        val all = drawerItems + items.filter { it.act != Act.PANEL && it.alpha != GHOST }
        return all.firstOrNull { it.act == Act.PAD && it.rect.contains(x, y) }
            ?: all.minByOrNull { d2(it.rect, x, y) }?.takeIf { d2(it.rect, x, y) <= slop * slop }
    }

    private fun d2(r: RectF, x: Float, y: Float): Float {
        val dx = max(0f, max(r.left - x, x - r.right))
        val dy = max(0f, max(r.top - y, y - r.bottom))
        return dx * dx + dy * dy
    }

    private fun rectOf(id: String): RectF? { layoutAll(); return items.firstOrNull { it.id == id }?.rect }

    private fun onDown(x: Float, y: Float) {
        downX = x; downY = y
        sliding = false
        consumed = false
        repeatHalted = false
        val it = hit(x, y)
        pressed = it
        if (it == null) return
        dragging = false
        // a drag starts from where the pill is drawn (kept on screen), not where it was asked to be
        dragDownY = y + barTop
        if (collapsed) collapsedPill()?.let { dragFromX = it.centerX(); dragFromY = it.centerY() }
        else if (freeFloat) pillRect().let { dragFromX = it.centerX(); dragFromY = it.centerY() + barTop }
        if (vert) { dragFromX = columnLeft() + colW / 2; dragFromY = columnTop() + columnH() / 2 }
        when (it.act) {
            Act.PAD -> { padDown(x, y); return }
            Act.KEYBOARD -> postDelayed(abcHoldRunnable, ABC_HOLD_MS)
            Act.MIC -> if (!talking && voice == Bar.Voice.OFF && commandSaid == null && drawer == Drawer.NONE) postDelayed(micHoldRunnable, MIC_HOLD_MS)
            // the own keys: a hold opens their choices (with a panel open, a Backspace one still keeps deleting)
            Act.BACKSPACE, Act.SPACE_ALT, Act.ENTER -> if (drawer == Drawer.NONE) ownKeyDown(it.act) else if (deletes(it.act)) {
                val now = SystemClock.uptimeMillis()
                if (lastBkspTap > 0L && now - lastBkspTap < DOUBLE_TAP_MS) {
                    // the second press of a double: delete now and keep deleting while it's held
                    lastBkspTap = 0L
                    consumed = true
                    listener?.onKey(Bar.Key.DELETE, false)
                    postDelayed(repeatRunnable, BKSP_REPEAT_START_MS)
                }
            }
            else -> Unit
        }
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        invalidate()
    }

    // ---------------------------------------------------------------- swiping the voice pill

    /** The voice pill is being dragged sideways while dictating: right sends, left cancels. */
    private var swiping = false
    private var swipeDx = 0f
    /** -1 past the cancel mark, 1 past the send mark, else 0. */
    private var swipeArmed = 0
    /** A let-go short of either mark springs back from here. */
    private var springFrom = 0f
    private var springAt = 0L

    private fun swipeMark(r: RectF) = min(dp(SWIPE_MARK_DP), r.width() * 0.4f)

    /** How far the pill is drawn off center right now. */
    private fun swipeOffset(now: Long): Float {
        if (swiping) return swipeDx
        if (springFrom == 0f) return 0f
        val t = ((now - springAt) / SPRING_MS.toFloat()).coerceIn(0f, 1f)
        if (t >= 1f) { springFrom = 0f; return 0f }
        val e = 1f - (1f - t) * (1f - t) * (1f - t)
        return springFrom * (1f - e)
    }

    private fun swipeMove(x: Float, r: RectF) {
        val lim = r.width() * 0.6f
        swipeDx = (x - downX).coerceIn(-lim, lim)
        val mark = swipeMark(r)
        val armed = when { swipeDx >= mark -> 1; swipeDx <= -mark -> -1; else -> 0 }
        if (armed != swipeArmed) {
            swipeArmed = armed
            performHapticFeedback(if (armed != 0) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
        invalidate()
    }

    /** How far the upright mic can stretch: one more key, and the gap. */
    private val stretchMax get() = ukey + ugap

    /** Upright: the mic's stretch follows the finger, up (negative) or down, at most [stretchMax]; armed at full stretch. */
    private fun swipeMoveUp(y: Float) {
        swipeDx = (y - downY).coerceIn(-stretchMax, stretchMax)
        val armed = when { swipeDx <= -stretchMax * 0.98f -> 1; swipeDx >= stretchMax * 0.98f -> -1; else -> 0 }
        if (armed != swipeArmed) {
            swipeArmed = armed
            performHapticFeedback(if (armed != 0) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.TEXT_HANDLE_MOVE)
        }
        invalidate()
    }

    /** Let go: past a mark, its action; short of it, the pill springs back. */
    private fun swipeEnd(commit: Boolean) {
        val armed = if (commit) swipeArmed else 0
        swiping = false
        swipeArmed = 0
        if (armed == 0 || !talking) {
            springFrom = swipeDx; springAt = SystemClock.uptimeMillis()
        } else {
            springFrom = 0f
            performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
            if (armed > 0) {
                // telling the cleanup what to fix: nothing to send, it just finishes
                val send = !fixMode
                endTalk(cancel = false, polish = true)
                if (send) listener?.onSendAfterTalk()
            } else endTalk(cancel = true, polish = false)
        }
        swipeDx = 0f
        invalidate()
    }

    @VisibleForTesting internal fun testSwipeMark() = swipeMark(rectOf("voice")!!)

    private fun onMove(x: Float, y: Float) {
        val p = pressed ?: return
        if (picker) { pickerHover(x, y); return }
        // the collapsed pill at rest follows a finger that moves off it (a press that stays put is a tap);
        // the open floating pill follows its grip
        val drags = (collapsed && !talking && voice == Bar.Voice.OFF && (p.act == Act.MIC || p.act == Act.MORE || p.act == Act.GRIP)) ||
            (freeFloat && p.act == Act.GRIP && drawer == Drawer.NONE) || (vert && !collapsed && p.act == Act.GRIP && drawer == Drawer.NONE)
        if (drags) {
            val vy = y + barTop // in view coordinates, before the pill moves
            if (!dragging && hypot(x - downX, vy - dragDownY) > dp(DRAG_SLOP_DP)) {
                dragging = true
                consumed = true
                removeCallbacks(micHoldRunnable)
                closeMenu()
                if (!collapsed && !vert) parked = false
            }
            if (dragging) {
                val fx = ((dragFromX + x - downX) / width).coerceIn(0f, 1f)
                val fy = ((dragFromY + vy - dragDownY) / max(1, height - bottomInset)).coerceIn(0f, 1f)
                if (collapsed) { pillFx = fx; pillFy = fy } else { floatFx = fx; floatFy = fy }
                settle()
                invalidate()
            }
            return
        }
        // a swipe up from either of the bar's own keys opens its choices at once
        if (keyOf(p.act) != null && drawer == Drawer.NONE && !sliding &&
            downY - y > dp(SWIPE_UP_DP) && downY - y > abs(x - downX)) { removeCallbacks(repeatRunnable); openPicker(p.act); pickerHover(x, y); return }
        // the purple "voice bar" pill swiped up: not a command after all
        if (p.act == Act.MIC && commandSaid != null && !swiping && !sliding &&
            downY - y > dp(SWIPE_UP_DP) && downY - y > abs(x - downX)) {
            consumed = true
            pressed = null
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            listener?.onDismissCommand()
            invalidate()
            return
        }
        // upright, the round mic stretches instead: up to send, down to cancel
        if (p.act == Act.MIC && talking && vert && p.id == "voice") {
            if (!swiping && abs(y - downY) > dp(SLIDE_START_DP)) swiping = true
            if (swiping) swipeMoveUp(y)
            return
        }
        // dictating: the voice pill slides sideways, right to send and left to cancel
        if (p.act == Act.MIC && talking) {
            if (!swiping && abs(x - downX) > dp(SLIDE_START_DP)) swiping = true
            if (swiping) swipeMove(x, p.rect)
            return
        }
        // a sideways slide on the space pill, or on the idle Voice pill, moves the cursor
        val spaceKey = keyOf(p.act) == Bar.ExtraKey.SPACE
        if ((spaceKey || (p.act == Act.MIC && !talking)) && !sliding && abs(x - downX) > dp(SLIDE_START_DP)) {
            sliding = true
            slideX = downX
            removeCallbacks(pickerHoldRunnable) // moving the cursor, not choosing
            removeCallbacks(micHoldRunnable)
        }
        if (sliding) {
            val step = dp(SLIDE_STEP_DP)
            while (x - slideX >= step) { slideX += step; listener?.onKey(Bar.Key.RIGHT, true) }
            while (slideX - x >= step) { slideX -= step; listener?.onKey(Bar.Key.LEFT, true) }
            invalidate()
            return
        }
        if (d2(p.rect, x, y) > dp(16f) * dp(16f)) {
            removeCallbacks(repeatRunnable)
            removeCallbacks(micHoldRunnable)
            pressed = null
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        val p = pressed
        pressed = null
        removeCallbacks(repeatRunnable)
        removeCallbacks(pickerHoldRunnable)
        removeCallbacks(abcHoldRunnable)
        removeCallbacks(micHoldRunnable)
        if (dragging) {
            // dropped: kept on screen (as it's drawn), remembered, and the touchable region follows
            dragging = false
            val areaH = max(1, height - bottomInset)
            if (vert) {
                // upright: where it was dropped, or, dropped near a side edge, against it (with a tick)
                val m = dp(FLOAT_MARGIN_DP)
                val left = columnLeft()
                val fx = when {
                    left - m < dp(SNAP_DP) -> 0f
                    width - m - colW - left < dp(SNAP_DP) -> 1f
                    else -> ((left + colW / 2) / width).coerceIn(0f, 1f)
                }
                if (fx == 0f || fx == 1f) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val cy = (columnTop() + columnH() / 2) / areaH
                if (collapsed) { pillFx = fx; pillFy = cy } else { floatFx = fx; floatFy = cy }
                if (!preview) Prefs.get(context).edit().apply {
                    if (collapsed) putFloat(Prefs.PILL_X, pillFx).putFloat(Prefs.PILL_Y, pillFy) else putFloat(Prefs.FLOAT_X, floatFx).putFloat(Prefs.FLOAT_Y, floatFy)
                }.apply()
                settle()
            } else if (collapsed) {
                collapsedPill()?.let { r ->
                    pillFx = (r.centerX() / width).coerceIn(0f, 1f)
                    pillFy = (r.centerY() / areaH).coerceIn(0f, 1f)
                }
                if (!preview) Prefs.get(context).edit().putFloat(Prefs.PILL_X, pillFx).putFloat(Prefs.PILL_Y, pillFy).apply()
            } else {
                // dropped near the bottom: parked there again, the app above it
                val r = pillRect().apply { offset(0f, barTop) }
                floatFx = (r.centerX() / width).coerceIn(0f, 1f)
                floatFy = (r.centerY() / areaH).coerceIn(0f, 1f)
                if (r.bottom > areaH - dp(PARK_DP)) { parked = true; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) }
                if (!preview) Prefs.get(context).edit().putFloat(Prefs.FLOAT_X, floatFx).putFloat(Prefs.FLOAT_Y, floatFy).putBoolean(Prefs.FLOAT_PARKED, parked).apply()
                settle()
            }
            requestLayout()
            invalidate()
            return
        }
        if (picker) {
            // let go on a choice: that's it; anywhere else, the column stays up for a tap
            pickerHot?.let { pick(it) } ?: invalidate()
            return
        }
        if (swiping) { swipeEnd(commit = true); return }
        if (p == null) { invalidate(); return }
        if (p.act == Act.PAD) { padUp(); return }
        if (consumed || sliding) { sliding = false; invalidate(); return }
        act(p)
        invalidate()
    }

    private fun onCancel() {
        removeCallbacks(repeatRunnable)
        removeCallbacks(pickerHoldRunnable)
        removeCallbacks(abcHoldRunnable)
        removeCallbacks(micHoldRunnable)
        pickerTap = false
        if (swiping) swipeEnd(commit = false)
        if (padActive) endPad()
        pressed = null
        sliding = false
        invalidate()
    }

    private fun act(p: Item) {
        val l = listener
        when (p.act) {
            Act.MIC -> when {
                talking -> endTalk(cancel = false, polish = true)
                voice == Bar.Voice.CLEANING -> Unit
                voice == Bar.Voice.MESSAGE && messageAction != null -> { val a = messageAction; hideVoice(); a?.invoke() }
                else -> startTalk()
            }
            Act.FINISH -> if (talking) endTalk(cancel = false, polish = true)
            Act.DISCARD -> if (talking) endTalk(cancel = true, polish = false)
            Act.TALK_PASTE -> if (talking) {
                // the clipboard instead of what's been said: drop the dictation, then paste
                endTalk(cancel = true, polish = false)
                l?.onEdit(Bar.EditAction.PASTE)
            }
            Act.CHIP_PASTE -> if (canPaste) l?.onEdit(Bar.EditAction.PASTE)
            Act.CHIP_SELECT_ALL -> l?.onEdit(Bar.EditAction.SELECT_ALL)
            Act.CHIP_CUT -> if (hasSelection) l?.onEdit(Bar.EditAction.CUT)
            Act.CHIP_COPY -> if (hasSelection) l?.onEdit(Bar.EditAction.COPY)
            Act.CHIP_DELETE -> l?.onEdit(Bar.EditAction.DELETE)
            Act.CURSOR -> if (talking) pauseFor(Drawer.PAD) else { onUsed?.invoke("cursor"); setDrawer(if (drawer == Drawer.PAD) Drawer.NONE else Drawer.PAD) }
            Act.KEYBOARD -> abc(hold = false)
            Act.MORE -> toggleMenu()
            Act.ENTER -> keyOf(Act.ENTER)?.let { pressExtra(it) } ?: l?.onKey(Bar.Key.ENTER, false)
            // a tap: on release, so a hold can open the tray (or, on the seven-button bar, its choices)
            Act.BACKSPACE -> keyOf(Act.BACKSPACE)?.let { pressExtra(it) } ?: run { listener?.onKey(Bar.Key.DELETE, false); lastBkspTap = SystemClock.uptimeMillis() }
            // the equal layout's own key (in the ⌫ tray, space is handled on release instead); mid-dictation
            // it acts at the cursor like any key, so a paste there keeps what's been said
            Act.SPACE_ALT -> pressExtra(keyOf(Act.SPACE_ALT) ?: Bar.ExtraKey.SPACE)
            Act.PAD, Act.PANEL, Act.CLEAR_ALL, Act.GRIP -> Unit
        }
    }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(2.2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val icon = Path()
    private val tmp = RectF()

    private val night get() = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
        android.content.res.Configuration.UI_MODE_NIGHT_YES
    /** Letter keys: white on a light panel; in the dark, a step lighter than the panel so they read. */

    private fun alpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (Color.alpha(c) * a.coerceIn(0f, 1f)).toInt())

    override fun onDraw(canvas: Canvas) {
        val pal = palette
        val now = SystemClock.uptimeMillis()
        layoutAll()
        if (anims.isEmpty()) settle()
        val moving = step(now)
        when {
            // collapsed: nothing but the record pill, which follows its keys as they grow and shrink
            collapsed && vert -> drawPillSurface(canvas, RectF(columnLeft(), columnTop(), columnLeft() + colW, columnTop() + columnH()))
            collapsed -> listOfNotNull(anims["voice"], anims["more"]?.takeIf { it.v[5] > 0.05f }, anims["grip"]?.takeIf { it.v[5] > 0.05f }).map { it.rect }
                .reduceOrNull { a, c -> RectF(a).apply { union(c) } }?.let { drawPillSurface(canvas, RectF(it).apply { inset(-dp(6f), -dp(6f)); offset(0f, barTop) }) }
            // floating: the bar's own surface, as a pill clear of the edges; the panels draw their own
            inPill -> drawPillSurface(canvas, pillRect().apply { offset(0f, barTop) })
            else -> {
                // the surface starts below any floating room (that stays see-through, over the app)
                val surfTop = floatRoom().toFloat()
                fill.color = pal.disc
                canvas.drawRect(0f, surfTop, width.toFloat(), height.toFloat(), fill)
                // a hairline where the app ends and the keyboard begins
                fill.color = pal.outline
                canvas.drawRect(0f, surfTop, width.toFloat(), surfTop + max(1f, dp(1f)), fill)
            }
        }
        if (commandSaid != null && !collapsed && !vert) drawWaveOffHint(canvas, now)
        else if (tip != null && drawer == Drawer.NONE && !drawerRoom && !picker && !collapsed) drawTip(canvas, now)
        canvas.save()
        canvas.translate(0f, barTop)
        // panels first, then what's inside them, then the bar; with the keyboard up, the bar's controls
        // still fading out go under it instead
        val keysOver = drawer == Drawer.KEYS
        for ((id, a) in anims) if (a.act == Act.PANEL && !(keysOver && id == "keysPanel")) drawPanel(canvas, id, a)
        if (keysOver) {
            drawControls(canvas, now)
            anims["keysPanel"]?.let { drawPanel(canvas, "keysPanel", it) }
        }
        val open = when (drawer) {
            Drawer.PAD -> anims["padPanel"]
            Drawer.KEYS -> anims["keysPanel"]
            Drawer.NONE -> null
        }
        val p = open?.let {
            val full = it.target[3] - it.target[1]
            ((it.rect.height() - dp(40f)) / max(1f, full - dp(40f))).coerceIn(0f, 1f)
        } ?: 0f
        val contentAlpha = ((p - 0.55f) / 0.45f).coerceIn(0f, 1f)
        if (contentAlpha > 0f) for (it in drawerItems) drawDrawerItem(canvas, it, contentAlpha)
        if (contentAlpha > 0f && drawer == Drawer.KEYS) {
            canvas.save()
            canvas.translate(0f, keys.pull)
            keys.draw(canvas, pal, contentAlpha)
            canvas.restore()
        }
        if (!keysOver) drawControls(canvas, now)
        if (picker) drawPicker(canvas, now)
        if (menuList) drawMenu(canvas, now)
        canvas.restore()
        if (moving) postInvalidateOnAnimation()
    }

    /** The bar's controls, the leaving ones under the ones that stay or arrive. */
    private fun drawControls(canvas: Canvas, now: Long) {
        for ((_, a) in anims) if (a.act != Act.PANEL && !a.present) drawControl(canvas, a, now)
        for ((_, a) in anims) if (a.act != Act.PANEL && a.present) drawControl(canvas, a, now)
    }

    private fun drawPanel(canvas: Canvas, id: String, a: Anim) {
        val al = a.v[5]
        if (al <= 0.01f) return
        val pal = palette
        fill.color = when (id) {
            "padPanel" -> alpha(pal.bloomKey, al)
            // upright, the pad's card beside the column (its chips sit on it)
            "padBack" -> alpha(pal.disc, al)
            "keysPanel" -> alpha(keys.cardColor(pal), al)
            // the menu: tiles stand out against a solid surface (the faint panel lost them in dark mode)
            // solid surface container, like the settings page behind its rows
            else -> alpha(pal.cardinal, al * 0.55f)
        }
        // the keyboard follows its handle down
        val dy = if (id == "keysPanel") keys.pull else 0f
        canvas.save()
        canvas.translate(0f, dy)
        canvas.drawRoundRect(a.rect, a.v[4], a.v[4], fill)
        canvas.restore()
    }

    /** One bar control, at its animated place; leaving or arriving ones are scaled by their alpha. */
    private fun drawControl(canvas: Canvas, a: Anim, now: Long) {
        val al = a.v[5]
        if (al <= 0.01f) return
        val r = a.rect
        canvas.save()
        // ghosted controls keep their full size; only coming and going scales them
        val s = 0.35f + 0.65f * (al / a.full).coerceAtMost(1f)
        canvas.scale(s, s, r.centerX(), r.centerY())
        val down = pressed?.id == a.id
        when (a.act) {
            Act.MIC -> if (vert && a.id == "voice") drawUprightMic(canvas, r, al, now, down)
                else { drawVoicePill(canvas, r, a.v[4], al, now, down); if (talking && titleCaseOn) drawTitleCaseBadge(canvas, r, al) }
            Act.CURSOR -> drawPill(canvas, r, a.v[4], al, down, Act.CURSOR)
            Act.KEYBOARD -> drawPill(canvas, r, a.v[4], al, down || drawer == Drawer.KEYS, Act.KEYBOARD) // lit while its keyboard is up
            Act.TALK_PASTE -> drawClipPill(canvas, r, a.v[4], al, down)
            else -> drawRound(canvas, a.act, r, a.v[4], al, down)
        }
        canvas.restore()
    }

    /**
     * The floating bar's (or collapsed pill's) surface: a soft Material shadow under it (elevation 2:
     * faint layers, widening and a little lower), the bar's color, and a hairline drawn just inside
     * its edge so nothing clips it.
     */
    private fun drawPillSurface(canvas: Canvas, r: RectF) {
        val rr = min(r.width(), r.height()) / 2
        val a = if (palette.dark) 14 else 7
        for (i in 1..6) {
            val g = i * dp(0.7f)
            val dy = dp(1f) + i * dp(0.35f)
            fill.color = Color.argb(a, 0, 0, 0)
            canvas.drawRoundRect(r.left - g, r.top - g + dy, r.right + g, r.bottom + g + dy, rr + g, rr + g, fill)
        }
        fill.color = palette.disc
        canvas.drawRoundRect(r, rr, rr, fill)
        val sw = max(1f, dp(1f))
        stroke.color = palette.outline
        stroke.strokeWidth = sw
        canvas.drawRoundRect(r.left + sw / 2, r.top + sw / 2, r.right - sw / 2, r.bottom - sw / 2, rr - sw / 2, rr - sw / 2, stroke)
    }

    private fun drawRound(canvas: Canvas, act: Act, r: RectF, corner: Float, al: Float, down: Boolean) {
        val pal = palette
        val bg = when {
            // tiered: keeping is the strong action (primary); throwing away is tonal red, so it never outshouts ✓
            tiered && act == Act.DISCARD -> if (down) ColorUtils.blendARGB(pal.discard, pal.onDiscard, 0.14f) else pal.discard
            act == Act.DISCARD -> if (down) ColorUtils.blendARGB(pal.error, Color.BLACK, 0.18f) else pal.error
            act == Act.FINISH -> if (down) ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.18f) else pal.pressed
            tiered && opensMenu(act) && menuList -> pal.letters
            tiered && down && (act == Act.BACKSPACE || act == Act.SPACE_ALT || act == Act.ENTER) -> ColorUtils.blendARGB(pal.cardinal, pal.onCardinal, 0.14f)
            tiered && (act == Act.BACKSPACE || act == Act.SPACE_ALT || act == Act.ENTER) -> pal.cardinal
            tiered && act == Act.MORE -> if (down) pal.cardinal else Color.TRANSPARENT
            down -> pal.pressed
            act == Act.CLEAR_ALL -> pal.discard
            opensMenu(act) && menuList -> pal.pressed
            // the own keys: secondary, whatever they're set to
            act in TONAL || act == Act.ENTER -> pal.diagonal
            drawer == Drawer.NONE && (act == Act.MORE || act == Act.SPACE_ALT) -> pal.diagonal
            else -> Color.TRANSPARENT
        }
        val fg = when {
            tiered && act == Act.DISCARD -> pal.onDiscard
            act == Act.DISCARD -> pal.onError
            act == Act.FINISH -> pal.onPressed
            tiered && opensMenu(act) && menuList -> pal.onLetters
            tiered && (act == Act.BACKSPACE || act == Act.SPACE_ALT || act == Act.ENTER || act == Act.MORE) -> pal.onCardinal
            down -> pal.onPressed
            act == Act.CLEAR_ALL -> pal.onDiscard
            opensMenu(act) && menuList -> pal.onPressed
            act in TONAL || act == Act.ENTER -> pal.onDiagonal
            drawer == Drawer.NONE && (act == Act.MORE || act == Act.SPACE_ALT) -> pal.onDiagonal
            else -> pal.onCardinal
        }
        if (Color.alpha(bg) > 0) {
            fill.color = alpha(bg, al)
            canvas.drawRoundRect(r, corner, corner, fill)
        }
        drawGlyph(canvas, act, r.centerX(), r.centerY(), dp(22f), alpha(fg, al))
    }

    /** While dictating: a tonal pill with the paste glyph and the start of the clipboard, ellipsized. */
    private val clipPaint = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.LEFT }
    private fun clipText() = (clipPreview ?: "").replace(Regex("\\s+"), " ").trim()

    /** How wide the paste pill would need to be to show the whole clip preview on one line. */
    private fun clipPillContentWidth(): Float {
        clipPaint.textSize = dp(13f)
        return dp(12f) + dp(18f) + dp(6f) + clipPaint.measureText(clipText()) + dp(12f)
    }

    private fun drawClipPill(canvas: Canvas, r: RectF, corner: Float, al: Float, down: Boolean) {
        val pal = palette
        fill.color = alpha(if (down) ColorUtils.blendARGB(pal.diagonal, pal.onDiagonal, 0.14f) else pal.diagonal, al)
        canvas.drawRoundRect(r, corner, corner, fill)
        val c = alpha(pal.onDiagonal, al)
        // one line: the paste glyph, then the start of the clipboard, ellipsized to fit
        val iw = dp(18f)
        val x0 = r.left + dp(12f)
        fill.color = c
        Icons.draw(canvas, Icons.Glyph.PASTE, x0 + iw / 2, r.centerY(), iw, fill)
        clipPaint.textSize = dp(13f)
        clipPaint.color = c
        val tx = x0 + iw + dp(6f)
        val shown = android.text.TextUtils.ellipsize(clipText(), clipPaint, r.right - dp(12f) - tx, android.text.TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, tx, r.centerY() - (clipPaint.ascent() + clipPaint.descent()) / 2, clipPaint)
    }

    /** Cursor and ABC at rest: tonal pills with an icon and, when there's room, their name. */
    private fun drawPill(canvas: Canvas, r: RectF, corner: Float, al: Float, down: Boolean, act: Act) {
        val pal = palette
        val done = act == Act.CURSOR && drawer == Drawer.PAD
        // with equal pills, Cursor and ABC take Voice's dark fill: the three modes stand apart from the keys
        // while dictating they step back to the secondary tone, so the recording pill keeps the weight
        val modeDark = drawer == Drawer.NONE && !tiered && !talking && voice != Bar.Voice.CLEANING
        val bg = when {
            tiered && done -> pal.letters
            tiered && down -> ColorUtils.blendARGB(pal.diagonal, pal.onDiagonal, 0.14f)
            down && modeDark -> ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.18f)
            down || done || modeDark -> pal.pressed
            else -> pal.diagonal
        }
        val fg = when {
            tiered && done -> pal.onLetters
            tiered -> pal.onDiagonal
            down || done || modeDark -> pal.onPressed
            else -> pal.onDiagonal
        }
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, corner, corner, fill)
        val label = context.getString(when {
            done -> R.string.dock_done
            act == Act.CURSOR -> R.string.dock_cursor
            else -> R.string.dock_abc
        })
        val c = alpha(fg, al)
        text.textSize = dp(14f)
        val iw = dp(20f)
        val showLabel = r.width() >= iw + dp(6f) + text.measureText(label) + dp(14f)
        val total = if (showLabel) iw + dp(6f) + text.measureText(label) else iw
        val x0 = r.centerX() - total / 2
        val glyph = if (done) Act.FINISH else act
        drawGlyph(canvas, glyph, x0 + iw / 2, r.centerY(), iw, c)
        if (showLabel) {
            text.color = c
            text.textAlign = Paint.Align.LEFT
            canvas.drawText(label, x0 + iw + dp(6f), r.centerY() - (text.ascent() + text.descent()) / 2, text)
            text.textAlign = Paint.Align.CENTER
        }
    }

    /** "Title case" said and not yet ended: a small badge on the recording pill's top edge says so. */
    private var titleCaseOn = false

    override fun showTitleCase(on: Boolean) {
        if (on == titleCaseOn) return
        titleCaseOn = on
        invalidate()
    }

    private fun drawTitleCaseBadge(canvas: Canvas, r: RectF, al: Float) {
        val pal = palette
        val label = context.getString(R.string.dock_title_case)
        text.textSize = dp(10f)
        val w = text.measureText(label) + dp(14f)
        val h = dp(13f)
        val box = RectF(r.centerX() - w / 2, r.top - h / 2, r.centerX() + w / 2, r.top + h / 2)
        fill.color = alpha(pal.command, al)
        canvas.drawRoundRect(box, h / 2, h / 2, fill)
        text.color = alpha(pal.onCommand, al)
        canvas.drawText(label, box.centerX(), box.centerY() - (text.ascent() + text.descent()) / 2, text)
        text.textSize = dp(14f)
    }

    private fun drawVoicePill(canvas: Canvas, r: RectF, rad: Float, al: Float, now: Long, down: Boolean) {
        val sw = swipeOffset(now)
        if (sw == 0f) { drawVoicePillBody(canvas, r, rad, al, now, down && !swiping); return }
        // sliding: the pill squeezes away from the finger's side, uncovering where it's headed;
        // past the mark it says what letting go will do
        drawSwipeTrack(canvas, r, rad, al, sw)
        swipeBody.set(r)
        if (sw > 0f) swipeBody.left += sw else swipeBody.right += sw
        val armed = swiping && abs(sw) >= swipeMark(r)
        swipeNote = if (!armed) null else context.getString(if (sw > 0f) R.string.dock_let_go_send else R.string.dock_let_go_cancel)
        drawVoicePillBody(canvas, swipeBody, rad, al, now, false)
        swipeNote = null
        if (!swiping) postInvalidateOnAnimation()
    }

    private val swipeBody = RectF()

    /**
     * Upright Voice: round, a mic at rest; dictating, a small waveform pulsing with the voice (it
     * doesn't scroll), stretched up or down while it's pulled, the end it's pulled toward showing
     * what letting go does (send, or cancel); polishing, the polishing glow.
     */
    private fun drawUprightMic(canvas: Canvas, r: RectF, al: Float, now: Long, down: Boolean) {
        val pal = palette
        val sw = if (talking) swipeOffset(now) else 0f
        val body = RectF(r).apply { if (sw < 0f) top += sw else bottom += sw }
        val a = (abs(sw) / stretchMax).coerceIn(0f, 1f)
        val armed = a >= 0.98f
        val bg = when {
            voice == Bar.Voice.CLEANING -> pal.center
            talking && armed && sw > 0f -> pal.discard
            talking && armed -> ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.22f)
            talking && fixMode -> pal.letters
            down -> ColorUtils.blendARGB(pal.pressed, Color.BLACK, 0.18f)
            else -> pal.pressed
        }
        val fg = when {
            voice == Bar.Voice.CLEANING -> pal.onCenter
            talking && armed && sw > 0f -> pal.onDiscard
            talking && fixMode && !armed -> pal.onLetters
            else -> pal.onPressed
        }
        fill.color = alpha(bg, al)
        val rr = body.width() / 2
        canvas.drawRoundRect(body, rr, rr, fill)
        when {
            voice == Bar.Voice.CLEANING -> {
                canvas.save()
                pulseClip.reset(); pulseClip.addRoundRect(body, rr, rr, android.graphics.Path.Direction.CW)
                canvas.clipPath(pulseClip)
                val p = ((now % PULSE_MS) / PULSE_MS.toFloat()).let { it * it * (3f - 2f * it) }
                val sh = body.height() * 0.8f
                val sy = body.top - sh + (body.height() + sh) * p
                val glow = alpha(pal.pressed, al * 0.6f)
                pulsePaint.shader = android.graphics.LinearGradient(0f, sy, 0f, sy + sh,
                    intArrayOf(glow and 0x00ffffff, glow, glow and 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(body.left, sy, body.right, sy + sh, pulsePaint)
                canvas.restore()
                postInvalidateOnAnimation()
            }
            talking -> {
                // five bars, tallest in the middle, all following the latest level
                val lvl = (levels.takeLast(3).average().toFloat()).coerceIn(0f, 1f)
                val shape = floatArrayOf(0.5f, 0.8f, 1f, 0.8f, 0.5f)
                val bw = dp(3f)
                val bg2 = dp(2.5f)
                val total = 5 * bw + 4 * bg2
                fill.color = alpha(fg, al * (1f - a))
                for (i in 0 until 5) {
                    val wob = 0.75f + 0.25f * sin(now / 90.0 + i).toFloat()
                    val h = dp(5f) + dp(13f) * lvl * shape[i] * wob
                    val x = r.centerX() - total / 2 + i * (bw + bg2)
                    canvas.drawRoundRect(x, body.centerY() - h / 2, x + bw, body.centerY() + h / 2, bw / 2, bw / 2, fill)
                }
                if (a > 0.02f) {
                    fill.color = alpha(fg, al * a)
                    if (sw < 0f) Icons.draw(canvas, Icons.Glyph.SEND, r.centerX(), body.top + ukey / 2, dp(20f), fill)
                    else Icons.draw(canvas, Icons.Glyph.DISCARD, r.centerX(), body.bottom - ukey / 2, dp(20f), fill)
                }
                postInvalidateOnAnimation()
            }
            else -> drawMic(canvas, r.centerX(), r.centerY(), dp(22f), alpha(fg, al))
        }
    }

    /** Which gesture hint to draw: 0 the text, 1 arrow-tag chips, 2 end handles (the one in use). */
    @VisibleForTesting internal var hintStyle = 2

    private val hintPath = android.graphics.Path()
    private val tagPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { pathEffect = android.graphics.CornerPathEffect(0f) }

    /**
     * The gesture hint with some shape to it: "Tap to finish" in the middle, and Cancel and Send
     * as chips at the ends that point the way to swipe (1), or as round handles at the pill's ends (2).
     */
    /** Whether the shaped hint has room for "Tap to finish" between its ends. */
    private fun gestureHintFits(r: RectF): Boolean {
        text.textSize = dp(13.5f)
        val mid = text.measureText(context.getString(R.string.dock_tap_to_finish))
        text.textSize = dp(14f)
        val ends = if (hintStyle == 1) 2 * (dp(14f) + dp(9f) + dp(15f) + dp(11f)) else 2 * (r.height() - dp(8f) + dp(28f))
        return ends + mid + dp(16f) <= r.width() - dp(12f)
    }

    private fun drawGestureHint(canvas: Canvas, r: RectF, hint: Float, al: Float, fg: Int, withMid: Boolean) {
        val pal = palette
        val cy = r.centerY()
        val a = al * min(1f, hint * 1.3f)
        val slide = dp(14f) * (1f - hint)
        val mid = context.getString(R.string.dock_tap_to_finish)
        val cancel = context.getString(R.string.dock_swipe_cancel)
        val send = context.getString(R.string.dock_swipe_send)
        text.textAlign = Paint.Align.CENTER
        if (hintStyle == 1) {
            val h = dp(28f)
            val point = dp(14f)
            val pad = dp(9f)
            val g = dp(15f)
            text.textSize = dp(12.5f)
            text.isFakeBoldText = true
            val midW = run { val t = text.textSize; text.textSize = dp(13.5f); text.isFakeBoldText = false; val w = text.measureText(mid); text.textSize = t; text.isFakeBoldText = true; w }
            fun chipW(label: String?) = point + pad + g + (if (label != null) dp(5f) + text.measureText(label) else 0f) + pad + dp(2f)
            val inset = dp(6f)
            var labels = true
            if (chipW(cancel) + chipW(send) + midW + dp(24f) > r.width() - 2 * inset) labels = false
            val showMid = withMid && chipW(null) * 2 + midW + dp(20f) <= r.width() - 2 * inset
            // left: points left, error container; right: points right, primary container
            fun tag(x0: Float, w: Float, left: Boolean, bg: Int, on: Int, glyph: Icons.Glyph, label: String?) {
                val top = cy - h / 2; val bot = cy + h / 2; val x1 = x0 + w
                hintPath.reset()
                if (left) {
                    hintPath.moveTo(x0, cy)
                    hintPath.lineTo(x0 + point, top)
                    hintPath.lineTo(x1 - h / 2, top)
                    hintPath.arcTo(x1 - h, top, x1, bot, -90f, 180f, false)
                    hintPath.lineTo(x0 + point, bot)
                } else {
                    hintPath.moveTo(x1, cy)
                    hintPath.lineTo(x1 - point, bot)
                    hintPath.lineTo(x0 + h / 2, bot)
                    hintPath.arcTo(x0, top, x0 + h, bot, 90f, 180f, false)
                    hintPath.lineTo(x1 - point, top)
                }
                hintPath.close()
                tagPaint.pathEffect = android.graphics.CornerPathEffect(dp(3f))
                tagPaint.color = alpha(bg, a)
                canvas.drawPath(hintPath, tagPaint)
                fill.color = alpha(on, a)
                text.color = alpha(on, a)
                val base = cy - (text.ascent() + text.descent()) / 2
                if (left) {
                    var x = x0 + point + pad - dp(2f)
                    Icons.draw(canvas, glyph, x + g / 2, cy, g, fill); x += g
                    if (label != null) { text.textAlign = Paint.Align.LEFT; canvas.drawText(label, x + dp(5f), base, text) }
                } else {
                    var x = x1 - point - pad + dp(2f)
                    Icons.draw(canvas, glyph, x - g / 2, cy, g, fill); x -= g
                    if (label != null) { text.textAlign = Paint.Align.RIGHT; canvas.drawText(label, x - dp(5f), base, text) }
                }
                text.textAlign = Paint.Align.CENTER
            }
            val lw = chipW(if (labels) cancel else null)
            val rw = chipW(if (labels) send else null)
            tag(r.left + inset - slide, lw, true, pal.discard, pal.onDiscard, Icons.Glyph.DISCARD, if (labels) cancel else null)
            tag(r.right - inset - rw + slide, rw, false, pal.center, pal.onCenter, Icons.Glyph.SEND, if (labels) send else null)
            text.isFakeBoldText = false
            if (showMid) {
                text.textSize = dp(13.5f)
                text.color = alpha(fg, a)
                val c = (r.left + inset + lw + r.right - inset - rw) / 2
                canvas.drawText(mid, c, cy - (text.ascent() + text.descent()) / 2, text)
            }
        } else {
            // round handles tucked into the pill's ends, with chevrons pointing out toward them
            val d = r.height() - dp(8f)
            val g = dp(17f)
            val lc = r.left + dp(4f) + d / 2 - slide
            val rc = r.right - dp(4f) - d / 2 + slide
            fill.color = alpha(pal.discard, a); canvas.drawCircle(lc, cy, d / 2, fill)
            fill.color = alpha(pal.onDiscard, a); Icons.draw(canvas, Icons.Glyph.DISCARD, lc, cy, g, fill)
            fill.color = alpha(pal.center, a); canvas.drawCircle(rc, cy, d / 2, fill)
            fill.color = alpha(pal.onCenter, a); Icons.draw(canvas, Icons.Glyph.SEND, rc + dp(1f), cy, g, fill)
            // chevrons: ‹‹ beside the left handle, ›› beside the right, fading toward the middle
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
            val ch = dp(5f)
            for (i in 0..1) {
                stroke.color = alpha(fg, a * (if (i == 0) 0.85f else 0.45f))
                val lx = lc + d / 2 + dp(9f) + i * dp(8f)
                hintPath.reset(); hintPath.moveTo(lx + ch * 0.6f, cy - ch); hintPath.lineTo(lx, cy); hintPath.lineTo(lx + ch * 0.6f, cy + ch)
                canvas.drawPath(hintPath, stroke)
                val rx = rc - d / 2 - dp(9f) - i * dp(8f)
                hintPath.reset(); hintPath.moveTo(rx - ch * 0.6f, cy - ch); hintPath.lineTo(rx, cy); hintPath.lineTo(rx - ch * 0.6f, cy + ch)
                canvas.drawPath(hintPath, stroke)
            }
            text.textSize = dp(13.5f)
            val room = (rc - d / 2 - dp(28f)) - (lc + d / 2 + dp(28f))
            if (withMid && text.measureText(mid) <= room) {
                text.color = alpha(fg, a)
                canvas.drawText(mid, r.centerX(), cy - (text.ascent() + text.descent()) / 2, text)
            }
        }
        text.textSize = dp(14f)
    }
    /** Drawn in the pill instead of the waveform while a swipe is past its mark. */
    private var swipeNote: String? = null

    private fun drawSwipeTrack(canvas: Canvas, r: RectF, rad: Float, al: Float, sw: Float) {
        val pal = palette
        val send = sw > 0f
        val p = (abs(sw) / swipeMark(r)).coerceIn(0f, 1f)
        val armed = p >= 1f
        val (bg, fg) = when {
            send && armed -> pal.onCenter to pal.center
            send -> pal.center to pal.onCenter
            armed -> pal.error to pal.onError
            else -> pal.discard to pal.onDiscard
        }
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, rad, rad, fill)
        // the icon (and the label, once there's room) in the space the pill has left
        val gap = abs(sw)
        val label = context.getString(if (send) R.string.dock_swipe_send else R.string.dock_swipe_cancel)
        text.textSize = dp(14f)
        text.isFakeBoldText = armed
        val iw = dp(18f)
        val total = iw + dp(6f) + text.measureText(label)
        val a = al * ((gap - dp(12f)) / dp(36f)).coerceIn(0f, 1f)
        if (a > 0.01f) {
            fill.color = alpha(fg, a)
            text.color = alpha(fg, a)
            val cy = r.centerY()
            val base = cy - (text.ascent() + text.descent()) / 2
            val s = 0.8f + 0.2f * p
            val withLabel = gap >= total + dp(28f)
            val glyph = if (send) Icons.Glyph.SEND else Icons.Glyph.DISCARD
            // the uncovered gap: from the pill's edge to where the squeezed pill now starts
            val g0 = if (send) r.left else r.right - gap
            val gc = g0 + gap / 2
            canvas.save(); canvas.scale(s, s, gc, cy)
            if (withLabel) {
                val x0 = gc - total / 2
                Icons.draw(canvas, glyph, x0 + iw / 2, cy, iw, fill)
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, x0 + iw + dp(6f), base, text)
            } else Icons.draw(canvas, glyph, gc, cy, iw, fill)
            canvas.restore()
            text.textAlign = Paint.Align.CENTER
        }
        text.isFakeBoldText = false
    }

    private val pulseClip = android.graphics.Path()
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private fun drawVoicePillBody(canvas: Canvas, r: RectF, rad: Float, al: Float, now: Long, down: Boolean) {
        val pal = palette
        val cleaning = voice == Bar.Voice.CLEANING && !talking
        val message = voice == Bar.Voice.MESSAGE && !talking
        val small = false
        // "voice bar" heard: Material You purple, fading in (and back out when it's let go)
        val cmdT = ((SystemClock.uptimeMillis() - commandAt) / COMMAND_FADE_MS.toFloat()).coerceIn(0f, 1f)
        val cmd = if (!talking && voice != Bar.Voice.OFF) 0f else if (commandSaid != null) cmdT else 1f - cmdT
        if (cmd in 0.001f..0.999f) postInvalidateOnAnimation()
        val bg0 = when {
            down -> pal.onCenter
            talking && fixMode -> pal.letters
            cleaning -> pal.center
            message -> if (messageGood) pal.center else pal.discard
            small -> pal.cardinal
            else -> pal.pressed
        }
        val bg = if (cmd > 0f) ColorUtils.blendARGB(bg0, pal.command, cmd) else bg0
        val fg0 = when {
            down -> pal.center
            talking && fixMode -> pal.onLetters
            cleaning -> pal.onCenter
            message -> if (messageGood) pal.onCenter else pal.onDiscard
            small -> pal.onCardinal
            else -> pal.onPressed
        }
        val fg = if (cmd > 0f) ColorUtils.blendARGB(fg0, pal.onCommand, cmd) else fg0
        if (cmd > 0f) {
            // a soft glow, breathing while it listens for the command
            val breathe = 0.65f + 0.35f * sin(now / 260.0).toFloat()
            for (i in 1..5) {
                fill.color = alpha(pal.command, al * cmd * breathe * 0.2f * (1f - i / 6f))
                tmp.set(r); tmp.inset(-i * dp(1.1f), -i * dp(1.1f))
                canvas.drawRoundRect(tmp, rad + i * dp(1.1f), rad + i * dp(1.1f), fill)
            }
            postInvalidateOnAnimation()
        }
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, rad, rad, fill)
        val cx = r.centerX()
        val cy = r.centerY()
        text.color = alpha(fg, al)
        text.textSize = dp(14f)
        val base = cy - (text.ascent() + text.descent()) / 2
        when {
            cleaning -> {
                // a soft glow sweeping left to right inside the pill, under the label
                canvas.save()
                pulseClip.reset(); pulseClip.addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW)
                canvas.clipPath(pulseClip)
                val p = ((now % PULSE_MS) / PULSE_MS.toFloat()).let { it * it * (3f - 2f * it) }
                val sw = r.width() * 0.45f
                val sx = r.left - sw + (r.width() + sw) * p
                val glow = alpha(fg, al * 0.22f)
                pulsePaint.shader = android.graphics.LinearGradient(sx, 0f, sx + sw, 0f,
                    intArrayOf(glow and 0x00ffffff, glow, glow and 0x00ffffff), null, android.graphics.Shader.TileMode.CLAMP)
                canvas.drawRect(sx, r.top, sx + sw, r.bottom, pulsePaint)
                canvas.restore()
                canvas.drawText(context.getString(R.string.dock_polishing), cx, base, text)
                postInvalidateOnAnimation()
            }
            message -> {
                var t = voiceText
                while (t.isNotEmpty() && text.measureText(t) > r.width() - dp(24f)) t = t.dropLast(1)
                canvas.drawText(t, cx, base, text)
            }
            talking && swipeNote != null -> {
                val note = swipeNote.orEmpty()
                text.isFakeBoldText = true
                val fit = r.width() - dp(20f)
                if (text.measureText(note) > fit) text.textSize = dp(14f) * fit / text.measureText(note)
                canvas.drawText(note, cx, cy - (text.ascent() + text.descent()) / 2, text)
                text.isFakeBoldText = false
                text.textSize = dp(14f)
            }
            talking && commandSaid != null -> {
                // what's being said after "voice bar", instead of the waveform and timer
                val said = commandSaid.orEmpty()
                val label = if (said.isBlank()) context.getString(R.string.dock_command_wait)
                else context.getString(R.string.dock_command_said, said)
                text.isFakeBoldText = commandUnderstood
                val fit = r.width() - dp(64f)
                if (text.measureText(label) > fit) text.textSize = dp(14f) * fit / text.measureText(label)
                canvas.drawText(label, cx, cy - (text.ascent() + text.descent()) / 2, text)
                text.isFakeBoldText = false
                text.textSize = dp(14f)
                drawUpChevron(canvas, r.right - dp(22f), cy, alpha(fg, al * 0.75f))
            }
            talking -> {
                canvas.save()
                canvas.clipRect(r)
                val showText = !voiceInField && voiceText.isNotBlank()
                // quiet for a while: the pill's color drains away right to left until it stops
                val drain = silenceDrain(now)
                if (drain < 1f) {
                    fill.color = alpha(pal.center, al)
                    canvas.drawRoundRect(r, rad, rad, fill)
                    canvas.save()
                    canvas.clipRect(r.left, r.top, r.left + r.width() * drain, r.bottom)
                    fill.color = alpha(bg, al)
                    canvas.drawRoundRect(r, rad, rad, fill)
                    canvas.restore()
                }
                text.textAlign = Paint.Align.RIGHT
                val right = r.right - dp(16f)
                val label = elapsed(now)
                val labelW = text.measureText(label)
                // "Tap to finish recording": shown at the start, then morphing in from the waveform
                // every few seconds (only when the words go into the field, so nothing is hidden)
                // the whole "← Cancel · Tap to finish · Send →" when it fits; otherwise "Tap to
                // finish" and "← Cancel · Send →" take turns
                text.textSize = dp(14f)
                val full = context.getString(R.string.dock_hint_full)
                val fits = text.measureText(full) <= r.width() - dp(28f)
                // shaped hints: everything at once when it fits, otherwise "Tap to finish", then the shapes
                val shapesFit = !fixMode && hintStyle != 0 && gestureHintFits(r)
                val (hintIdx, hintP) = if (showText) 0 to 0f else hintAt(now, if ((hintStyle == 0 && fits) || shapesFit) 1 else 2)
                // still connecting: that's what the pill says, the whole time, over the waveform
                val shapes = !connecting && !fixMode && hintStyle != 0 && (shapesFit || hintIdx == 1)
                val hint = if (connecting && !showText) 1f else hintP
                val hintText = when {
                    connecting -> context.getString(R.string.dock_connecting)
                    fixMode -> context.getString(R.string.dock_fix_hint)
                    hintStyle != 0 -> context.getString(R.string.dock_tap_to_finish)
                    fits -> full
                    hintIdx == 0 -> context.getString(R.string.dock_tap_to_finish)
                    else -> context.getString(R.string.dock_swipe_hint)
                }
                // the waveform, centered in the pill and clear of the timer on the right
                val bw = dp(3f)
                val bg2 = dp(3f)
                val half = min(cx - (r.left + dp(16f)), (right - labelW - dp(12f)) - cx)
                val wx0 = if (showText) r.left + dp(16f) else cx - half
                val wx1 = if (showText) right - labelW - dp(12f) else cx + half
                text.textSize = dp(14f)
                // the hint gets the whole pill (the timer steps aside); shrink it if it still won't fit
                val fitW = r.width() - dp(28f)
                if (text.measureText(hintText) > fitW) text.textSize = dp(14f) * fitW / text.measureText(hintText)
                val tw = text.measureText(hintText)
                // the text is unveiled from the middle outward; bars inside the opening give way
                val open = if (shapes) r.width() / 2 * hint else tw / 2 * hint + dp(10f) * hint
                if (wx1 > wx0) {
                    val bars = ((wx1 - wx0 + bg2) / (bw + bg2)).toInt().coerceIn(1, levels.size)
                    val off = ((wx1 - wx0) - (bars * bw + (bars - 1) * bg2)) / 2
                    for (i in 0 until bars) {
                        val lv = levels[levels.size - bars + i]
                        val idle = 0.12f + 0.06f * sin((now / 140.0 + i).toFloat())
                        val bx = wx0 + off + i * (bw + bg2)
                        val d = abs(bx + bw / 2 - cx)
                        // inside the opening a bar fades out; outside, bars settle down as the text arrives
                        val keep = ((d - open) / dp(14f)).coerceIn(0f, 1f).let { if (hint > 0f) it else 1f }
                        val settle = 1f - 0.65f * hint
                        val h = max(dp(3f), dp(22f) * max(lv, idle) * settle)
                        tmp.set(bx, cy - h / 2, bx + bw, cy + h / 2)
                        val drained = drain < 1f && bx > r.left + r.width() * drain
                        val a = al * keep * (if (showText) 0.18f else 1f) * (1f - 0.35f * hint)
                        if (a <= 0.01f) continue
                        fill.color = alpha(if (drained) pal.onCenter else fg, a)
                        canvas.drawRoundRect(tmp, bw / 2, bw / 2, fill)
                    }
                }
                if (hint > 0f && shapes) {
                    drawGestureHint(canvas, r, hint, al, fg, withMid = shapesFit)
                } else if (hint > 0f) {
                    canvas.save()
                    canvas.clipRect(cx - open, r.top, cx + open, r.bottom)
                    text.textAlign = Paint.Align.CENTER
                    text.color = alpha(if (drain < 1f && cx > r.left + r.width() * drain) pal.onCenter else fg, al * min(1f, hint * 1.4f))
                    // a slight rise and grow as it lands
                    val s = 0.94f + 0.06f * hint
                    canvas.scale(s, s, cx, cy)
                    canvas.drawText(hintText, cx, cy - (text.ascent() + text.descent()) / 2 + dp(5f) * (1f - hint), text)
                    canvas.restore()
                }
                text.textSize = dp(14f)
                text.textAlign = Paint.Align.RIGHT
                text.color = alpha(if (drain < 1f && right > r.left + r.width() * drain) pal.onCenter else fg, al * (1f - hint))
                canvas.drawText(label, right, base, text)
                if (showText) {
                    val room = right - labelW - dp(10f) - wx0
                    text.textAlign = Paint.Align.LEFT
                    text.color = alpha(fg, al)
                    var t = voiceText
                    while (t.isNotEmpty() && text.measureText(t) > room) t = t.substring(1)
                    canvas.drawText(t, wx0, base, text)
                }
                text.textAlign = Paint.Align.CENTER
                canvas.restore()
                postInvalidateOnAnimation()
            }
            commandSaid != null -> {
                // idle, and "voice bar" just heard: waiting for "listen"
                val full = context.getString(R.string.dock_wake_listen)
                val label = if (text.measureText(full) <= r.width() - dp(64f)) full else context.getString(R.string.dock_command_wait)
                val fit = r.width() - dp(56f)
                drawUpChevron(canvas, r.right - dp(22f), cy, alpha(fg, al * 0.75f))
                if (text.measureText(label) > fit) text.textSize = dp(14f) * fit / text.measureText(label)
                canvas.drawText(label, cx, base, text)
                text.textSize = dp(14f)
            }
            else -> {
                val label = if (small) "" else context.getString(if (resumeAfterDrawer) R.string.dock_paused else R.string.dock_voice)
                val iw = dp(20f)
                val showLabel = label.isNotEmpty() && r.width() >= iw + dp(6f) + text.measureText(label) + dp(14f)
                val total = if (showLabel) iw + dp(6f) + text.measureText(label) else iw
                val x0 = cx - total / 2
                drawMic(canvas, x0 + iw / 2, cy, iw, alpha(fg, al))
                if (showLabel) {
                    text.textAlign = Paint.Align.LEFT
                    canvas.drawText(label, x0 + iw + dp(6f), base, text)
                    text.textAlign = Paint.Align.CENTER
                }
            }
        }
    }

    /**
     * Floating just above the bar, over the app: a purple chip pointing up at the swipe, so it's
     * clear the purple pill can be waved off ("keep as words" while dictating).
     */
    private fun drawWaveOffHint(canvas: Canvas, now: Long) {
        val v = anims["voice"]?.rect ?: return
        val pal = palette
        val t = ((now - commandAt) / 260f).coerceIn(0f, 1f)
        val e = 1f - (1f - t) * (1f - t)
        val label = context.getString(if (talking) R.string.dock_waveoff_talking else R.string.dock_waveoff_rest)
        text.textSize = dp(13f)
        text.isFakeBoldText = true
        val iw = dp(14f)
        val cw = dp(14f) + iw + dp(6f) + text.measureText(label) + dp(16f)
        val ch = dp(30f)
        val cx = (v.centerX()).coerceIn(cw / 2 + dp(8f), width - cw / 2 - dp(8f))
        val bottom = barTop - dp(8f) + dp(10f) * (1f - e)
        tmp.set(cx - cw / 2, bottom - ch, cx + cw / 2, bottom)
        // a soft shadow, then the chip
        for (i in 1..4) {
            fill.color = Color.argb((14 * e).toInt(), 0, 0, 0)
            canvas.drawRoundRect(tmp.left - i * dp(1f), tmp.top - i * dp(1f) + dp(1.5f), tmp.right + i * dp(1f), tmp.bottom + i * dp(1f) + dp(1.5f), ch / 2 + i * dp(1f), ch / 2 + i * dp(1f), fill)
        }
        fill.color = alpha(pal.command, e)
        canvas.drawRoundRect(tmp, ch / 2, ch / 2, fill)
        // a little tail pointing down at the pill
        val tail = android.graphics.Path().apply {
            moveTo(cx - dp(6f), tmp.bottom - dp(1f)); lineTo(cx, tmp.bottom + dp(5f)); lineTo(cx + dp(6f), tmp.bottom - dp(1f)); close()
        }
        canvas.drawPath(tail, fill)
        // the arrow nudges upward, over and over
        val bob = dp(2.5f) * (0.5f + 0.5f * sin(now / 180.0).toFloat())
        val ax = tmp.left + dp(14f) + iw / 2
        drawUpChevron(canvas, ax, tmp.centerY() + dp(1f) - bob, alpha(pal.onCommand, e))
        text.color = alpha(pal.onCommand, e)
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(label, ax + iw / 2 + dp(6f), tmp.centerY() - (text.ascent() + text.descent()) / 2, text)
        text.textAlign = Paint.Align.CENTER
        text.isFakeBoldText = false
        text.textSize = dp(14f)
        postInvalidateOnAnimation()
    }

    /** A small ^ at the purple pill's end: swipe up to wave "voice bar" off. */
    private fun drawUpChevron(canvas: Canvas, x: Float, cy: Float, c: Int) {
        val st = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = c }
        val h = dp(4f); val w = dp(6f)
        for (i in 0..1) {
            val y = cy + dp(1f) - i * dp(6f) + dp(2f)
            st.alpha = (android.graphics.Color.alpha(c) * (if (i == 0) 1f else 0.55f)).toInt()
            canvas.drawLine(x - w, y + h / 2, x, y - h / 2, st)
            canvas.drawLine(x, y - h / 2, x + w, y + h / 2, st)
        }
    }

    /** A soft drop shadow under a rounded rectangle (stacked translucent outlines). */
    private fun drawShadowRect(canvas: Canvas, r: RectF, rad: Float) {
        for (i in 1..4) {
            fill.color = Color.argb(10, 0, 0, 0)
            tmp.set(r); tmp.inset(-i * dp(1.2f), -i * dp(1.2f)); tmp.offset(0f, dp(1.5f))
            canvas.drawRoundRect(tmp, rad + i * dp(1.2f), rad + i * dp(1.2f), fill)
        }
    }

    /**
     * 1 while speech is recent; after [DRAIN_AFTER_MS] of quiet it falls to 0 at the silence
     * timeout, so the pill can show the time left as color draining away.
     */
    private fun silenceDrain(now: Long): Float {
        if (!talking || silenceDeadline == 0L) return 1f
        val start = min(DRAIN_AFTER_MS, silenceMs / 2)
        val left = (silenceDeadline - now).toFloat()
        val span = (silenceMs - start).toFloat()
        return (left / span).coerceIn(0f, 1f)
    }

    @VisibleForTesting internal fun testDrain() = silenceDrain(SystemClock.uptimeMillis())

    /**
     * The hint over the waveform: which text (0 = the first, 1 = the second when they alternate)
     * and how far it has morphed in, 0..1. It's shown as dictation starts, and again after
     * [HINT_QUIET_MS] without speech, every [HINT_EVERY_MS] while the quiet lasts. Each showing
     * morphs in, holds and morphs out; with two texts they play one after the other.
     */
    private fun hintAt(now: Long, texts: Int): Pair<Int, Float> {
        if (!talking) return 0 to 0f
        fun ease(x: Float) = x.coerceIn(0f, 1f).let { it * it * (3f - 2f * it) }
        val one = HINT_MORPH_MS * 2 + HINT_HOLD_MS
        fun play(o: Long): Pair<Int, Float> {
            if (o < 0 || o >= one * texts) return 0 to 0f
            val k = (o / one).toInt()
            val p = o % one
            return k to when {
                p < HINT_MORPH_MS -> ease(p.toFloat() / HINT_MORPH_MS)
                p < HINT_MORPH_MS + HINT_HOLD_MS -> 1f
                else -> 1f - ease((p - HINT_MORPH_MS - HINT_HOLD_MS).toFloat() / HINT_MORPH_MS)
            }
        }
        // at the start it's already in place, then plays out
        val start = play(now - talkStartedAt + HINT_MORPH_MS)
        if (start.second > 0f) return start
        val quiet = now - lastHeardAt - HINT_QUIET_MS
        if (quiet < 0) return 0 to 0f
        return play(quiet % HINT_EVERY_MS)
    }
    private fun hintAmount(now: Long) = hintAt(now, 1).second
    @VisibleForTesting internal fun testHint(now: Long) = hintAmount(now)

    private fun elapsed(now: Long): String {
        val s = ((now - talkStartedAt) / 1000).coerceAtLeast(0)
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun drawDrawerItem(canvas: Canvas, it: Item, al: Float) {
        val pal = palette
        val r = it.rect
        val down = pressed === it || (pressed?.id == it.id)
        when (it.act) {
            Act.CHIP_SELECT_ALL, Act.CHIP_CUT, Act.CHIP_COPY, Act.CHIP_PASTE, Act.CHIP_DELETE -> {
                val enabled = when (it.act) {
                    Act.CHIP_CUT, Act.CHIP_COPY -> hasSelection
                    Act.CHIP_PASTE -> canPaste
                    else -> true
                }
                val a = al * if (enabled) 1f else DISABLED_ALPHA
                fill.color = alpha(if (down) pal.pressed else pal.diagonal, a)
                canvas.drawRoundRect(r, r.height() / 2, r.height() / 2, fill)
                val fg = alpha(if (down) pal.onPressed else pal.onDiagonal, a)
                val g = when (it.act) { Act.CHIP_SELECT_ALL -> Icons.Glyph.SELECT_ALL; Act.CHIP_CUT -> Icons.Glyph.CUT; Act.CHIP_COPY -> Icons.Glyph.COPY; Act.CHIP_PASTE -> Icons.Glyph.PASTE; else -> Icons.Glyph.BACKSPACE }
                text.textSize = dp(13f)
                text.color = fg
                val iw = dp(18f)
                // "Select all" when it fits, else "All"; any label that still won't fit is left off
                val label = context.getString(when (it.act) {
                    Act.CHIP_SELECT_ALL -> if (iw + dp(6f) + text.measureText(context.getString(R.string.dock_select_all)) <= r.width() - dp(16f)) R.string.dock_select_all else R.string.dock_all
                    Act.CHIP_CUT -> R.string.dock_cut; Act.CHIP_COPY -> R.string.dock_copy; Act.CHIP_PASTE -> R.string.dock_paste; else -> R.string.dock_delete
                }).let { l -> if (iw + dp(6f) + text.measureText(l) <= r.width() - dp(12f)) l else "" }
                val total = if (label.isEmpty()) iw else iw + dp(6f) + text.measureText(label)
                val x0 = r.centerX() - total / 2
                fill.color = fg
                Icons.draw(canvas, g, x0 + iw / 2, r.centerY(), iw, fill)
                text.textAlign = Paint.Align.LEFT
                canvas.drawText(label, x0 + iw + dp(6f), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                text.textAlign = Paint.Align.CENTER
            }
            Act.PAD -> {
                text.textSize = dp(13f)
                text.color = alpha(pal.onCardinal, 0.8f * al)
                val msg = context.getString(if (padSelecting) R.string.dock_pad_selecting_more else R.string.dock_pad_hint)
                val arrows = padArrows(r)
                if (!padSelecting) for ((d, a) in arrows) {
                    val hot = padActive && !padMoved && padArrow?.contentEquals(d) == true
                    val side = d[0] != 0
                    // very faint, under the hint: the pad still reads as a pad, the targets as somewhere to tap
                    fill.color = alpha(if (hot) pal.pressed else pal.onCardinal, (if (hot) 0.22f else if (side) 0.03f else 0.045f) * al)
                    canvas.drawRoundRect(a, dp(20f), dp(20f), fill)
                    arrowPaint.color = alpha(if (hot) pal.pressed else pal.onCardinal, (if (hot) 1f else 0.28f) * al)
                    val c = dp(5f)
                    // the side chevrons sit out by the edges, clear of the hint
                    val ax = when { d[0] < 0 -> a.left + dp(20f); d[0] > 0 -> a.right - dp(20f); else -> a.centerX() }
                    val ay = a.centerY()
                    val path = android.graphics.Path()
                    when {
                        d[0] < 0 -> { path.moveTo(ax + c * 0.6f, ay - c); path.lineTo(ax - c * 0.6f, ay); path.lineTo(ax + c * 0.6f, ay + c) }
                        d[0] > 0 -> { path.moveTo(ax - c * 0.6f, ay - c); path.lineTo(ax + c * 0.6f, ay); path.lineTo(ax - c * 0.6f, ay + c) }
                        d[1] < 0 -> { path.moveTo(ax - c, ay + c * 0.6f); path.lineTo(ax, ay - c * 0.6f); path.lineTo(ax + c, ay + c * 0.6f) }
                        else -> { path.moveTo(ax - c, ay - c * 0.6f); path.lineTo(ax, ay + c * 0.6f); path.lineTo(ax + c, ay - c * 0.6f) }
                    }
                    canvas.drawPath(path, arrowPaint)
                }
                // the hint on top, between the side chevrons
                val room = arrows[1].second.right - arrows[0].second.left - dp(80f)
                val shown = android.text.TextUtils.ellipsize(msg, android.text.TextPaint(text), room, android.text.TextUtils.TruncateAt.END).toString()
                canvas.drawText(shown, r.centerX(), r.centerY() - (text.ascent() + text.descent()) / 2, text)
                if (padActive) {
                    val px = padX.coerceIn(r.left, r.right)
                    val py = padY.coerceIn(r.top, r.bottom)
                    fill.color = alpha(pal.pressed, 0.22f * al)
                    canvas.drawCircle(px, py, dp(26f), fill)
                    fill.color = alpha(pal.pressed, al)
                    canvas.drawCircle(px, py, dp(6f), fill)
                }
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- glyphs (24-unit grid, 2-unit strokes)

    private fun drawGlyph(canvas: Canvas, act: Act, x: Float, y: Float, size: Float, c: Int) {
        fill.color = c
        when (act) {
            Act.BACKSPACE -> keyOf(Act.BACKSPACE)?.let { drawExtraGlyph(canvas, it, x, y, size, c) } ?: Icons.draw(canvas, Icons.Glyph.BACKSPACE, x, y, size, fill)
            Act.CURSOR -> Icons.draw(canvas, Icons.Glyph.TEXT_CURSOR, x, y, size, fill)
            Act.DISCARD -> Icons.draw(canvas, Icons.Glyph.DISCARD, x, y, size, fill)
            Act.KEYBOARD -> drawKeyboard(canvas, x, y, size, c)
            // ⠿: two columns of three dots
            Act.GRIP -> if (vert) { for (cx in -1..1) for (cy in listOf(-1, 1)) canvas.drawCircle(x + cx * size * 0.24f, y + cy * size * 0.14f, size * 0.075f, fill) }
                else for (cx in listOf(-1, 1)) for (cy in -1..1) canvas.drawCircle(x + cx * size * 0.14f, y + cy * size * 0.24f, size * 0.075f, fill)
            // ☰: the menu
            Act.MORE -> { stroke.color = c; stroke.strokeWidth = size * 0.085f; for (i in -1..1) canvas.drawLine(x - size * 0.3f, y + i * size * 0.24f, x + size * 0.3f, y + i * size * 0.24f, stroke) }
            Act.FINISH -> strokePath(canvas, x, y, size, c) { m, l -> m(5f, 12.5f); l(9.5f, 17f); l(19f, 7.5f) }
            Act.ENTER -> drawExtraGlyph(canvas, keyOf(Act.ENTER) ?: Bar.ExtraKey.ENTER, x, y, size, c)
            Act.SPACE_ALT -> drawExtraGlyph(canvas, keyOf(Act.SPACE_ALT) ?: Bar.ExtraKey.SPACE, x, y, size, c)
            Act.CLEAR_ALL -> {
                // a backspace with a bar to its left: everything, back to the start
                Icons.draw(canvas, Icons.Glyph.BACKSPACE, x + size * 0.08f, y, size * 0.86f, fill)
                stroke.color = c
                stroke.strokeWidth = size * 0.09f
                canvas.drawLine(x - size * 0.46f, y - size * 0.3f, x - size * 0.46f, y + size * 0.3f, stroke)
            }
            Act.MIC -> drawMic(canvas, x, y, size, c)
            else -> Unit
        }
    }

    private inline fun strokePath(canvas: Canvas, x: Float, y: Float, size: Float, c: Int, build: ((Float, Float) -> Unit, (Float, Float) -> Unit) -> Unit) {
        val u = size / 24f
        icon.reset()
        build({ a, b -> icon.moveTo(x + (a - 12f) * u, y + (b - 12f) * u) }, { a, b -> icon.lineTo(x + (a - 12f) * u, y + (b - 12f) * u) })
        stroke.color = c
        stroke.strokeWidth = 2f * u
        canvas.drawPath(icon, stroke)
    }

    private fun drawMic(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) {
        val u = size / 24f
        stroke.color = c
        stroke.strokeWidth = 2f * u
        tmp.set(x - 3.2f * u, y - 9f * u, x + 3.2f * u, y + 3f * u)
        fill.color = c
        canvas.drawRoundRect(tmp, 3.2f * u, 3.2f * u, fill)
        tmp.set(x - 6.5f * u, y - 5f * u, x + 6.5f * u, y + 6f * u)
        canvas.drawArc(tmp, 0f, 180f, false, stroke)
        canvas.drawLine(x, y + 6f * u, x, y + 9.5f * u, stroke)
    }

    private fun drawKeyboard(canvas: Canvas, x: Float, y: Float, size: Float, c: Int) {
        val w = size * 0.42f
        val h = size * 0.28f
        stroke.color = c
        stroke.strokeWidth = size * 0.08f
        tmp.set(x - w, y - h, x + w, y + h)
        canvas.drawRoundRect(tmp, size * 0.08f, size * 0.08f, stroke)
        fill.color = c
        val k = size * 0.055f
        for (row in 0 until 2) for (col in 0 until 4) canvas.drawCircle(x - w * 0.6f + col * w * 0.4f, y - h * 0.45f + row * h * 0.5f, k, fill)
        canvas.drawLine(x - w * 0.4f, y + h * 0.5f, x + w * 0.4f, y + h * 0.5f, stroke)
    }

    private fun drawExtraGlyph(canvas: Canvas, key: Bar.ExtraKey, x: Float, y: Float, size: Float, c: Int) {
        fill.color = c
        when (key) {
            Bar.ExtraKey.SPACE -> drawSpace(canvas, x, y, size * 0.42f, c)
            Bar.ExtraKey.PASTE -> Icons.draw(canvas, Icons.Glyph.PASTE, x, y, size, fill)
            Bar.ExtraKey.UNDO -> Icons.draw(canvas, Icons.Glyph.UNDO, x, y, size, fill)
            Bar.ExtraKey.SELECT_ALL -> Icons.draw(canvas, Icons.Glyph.SELECT_ALL, x, y, size, fill)
            Bar.ExtraKey.LAST_DICTATION -> Icons.draw(canvas, Icons.Glyph.HISTORY, x, y, size, fill)
            Bar.ExtraKey.BACKSPACE -> Icons.draw(canvas, Icons.Glyph.BACKSPACE, x, y, size, fill)
            Bar.ExtraKey.FIX_LAST -> Icons.draw(canvas, Icons.Glyph.AUTO_FIX, x, y, size, fill)
            Bar.ExtraKey.ENTER -> strokePath(canvas, x, y, size, c) { m, l -> m(19f, 5f); l(19f, 13f); l(6f, 13f); m(10f, 9f); l(6f, 13f); l(10f, 17f) }
            Bar.ExtraKey.STYLE -> if (floating) drawDockGlyph(canvas, x, y, size, c) else drawFloatGlyph(canvas, x, y, size, c)
        }
    }

    private fun drawSpace(canvas: Canvas, x: Float, y: Float, half: Float, c: Int) {
        stroke.color = c
        stroke.strokeWidth = dp(2f)
        icon.reset()
        icon.moveTo(x - half, y - dp(3f)); icon.lineTo(x - half, y + dp(3f)); icon.lineTo(x + half, y + dp(3f)); icon.lineTo(x + half, y - dp(3f))
        canvas.drawPath(icon, stroke)
    }

    private fun drawSlideArrows(canvas: Canvas, x: Float, y: Float, s: Float, c: Int) {
        stroke.color = c
        stroke.strokeWidth = s * 0.5f
        icon.reset()
        icon.moveTo(x - s * 1.4f, y - s); icon.lineTo(x - s * 2.4f, y); icon.lineTo(x - s * 1.4f, y + s)
        icon.moveTo(x + s * 1.4f, y - s); icon.lineTo(x + s * 2.4f, y); icon.lineTo(x + s * 1.4f, y + s)
        canvas.drawPath(icon, stroke)
    }

    private companion object {
        const val BAR_DP = 52f
        /** The floating pill's gap to the screen's edges. */
        const val FLOAT_MARGIN_DP = 8f
        /** The menu list's width (less on a narrow screen). */
        const val MENU_W_DP = 264f
        /** The menu morphing between its items and the recent dictations. */
        const val MENU_MORPH_MS = 260L
        /** How many recent dictations the menu shows. */
        const val MENU_DICTATIONS = 5
        /** Voice in the collapsed pill. */
        const val COLLAPSED_MIC_DP = 64f
        /** The collapsed pill grown into the recording pill (at most; less on a narrow screen). */
        const val COLLAPSED_WIDE_DP = 300f
        /** Where the collapsed pill starts: on the right, above where most apps put their message box and Send. */
        const val DEFAULT_PILL_FX = 0.82f
        const val DEFAULT_PILL_FY = 0.74f
        /** Above and below the floating pill in a settings preview. */
        const val PREVIEW_ROOM_DP = 14f
        /** The upright column dropped this close to a side edge snaps against it. */
        const val SNAP_DP = 48f
        /** The upright column's round keys. */
        const val UPRIGHT_KEY_DP = 44f
        /** A note beside the upright column, at most. */
        const val UPRIGHT_NOTE_DP = 260f
        /** The cursor pad beside the upright column, at most. */
        const val UPRIGHT_PANEL_DP = 340f
        /** The pills' grip (⠿). */
        const val GRIP_DP = 18f
        /** The see-through strip above the parked pill, for its edge and shadow. */
        const val FLOAT_EDGE_DP = 6f
        /** The open pill dropped this close to the bottom parks there. */
        const val PARK_DP = 64f
        /** A press on the collapsed pill that moves this far is a drag, not a tap. */
        const val DRAG_SLOP_DP = 10f
        /** Wider than this, the buttons can keep to one side; and how wide they are then. */
        const val WIDE_DP = 600f
        const val SPAN_DP = 440f
        const val PULSE_MS = 1400L
        const val DRAWER_DP = 220f
        /** Material 3 shape scale: panels are extra large (28), what sits inside them large (16): 28 less their 12 inset. */
        const val PANEL_CORNER_DP = 28f
        /** Holding a recent dictation in the menu this long opens its options. */
        const val CLIP_HOLD_MS = 450L
        /** How long a dictation card says "Copied" after its new cleanup goes on the clipboard. */
        const val RECLEANED_MS = 1500L
        const val NAV_GESTURE = 2
        const val WAVE_HISTORY = 96
        const val DRAIN_AFTER_MS = 5000L
        const val MESSAGE_MS = 2600L
        /** Held words (auto-listen in a chat) wait this long for a tap. */
        const val HELD_MS = 60_000L
        /** How faint a control is when it's still there but can't be pressed (Material's disabled 38%). */
        const val GHOST = 0.38f
        /** The "Tap to finish recording" hint: shown first, then back every 5 s. */
        const val HINT_QUIET_MS = 3000L
        const val HINT_EVERY_MS = 10_000L
        const val HINT_MORPH_MS = 650L
        const val HINT_HOLD_MS = 1100L
        /** Holding the voice pill this long while dictating sends. */
        /** How far the voice pill must slide to send (right) or cancel (left), at most. */
        const val SWIPE_MARK_DP = 84f
        const val SPRING_MS = 260L
        const val COMMAND_FADE_MS = 220L
        /** The see-through room above the bar for the floating "swipe up" hint. */
        const val FLOAT_DP = 50f
        /** With gesture navigation, the room kept under the bar for the handle. */
        const val GESTURE_ROOM_MIN_DP = 16f
        const val GESTURE_ROOM_MAX_DP = 22f
        /** How far up the purple pill must be swiped to wave "voice bar" off. */
        const val SWIPE_UP_DP = 22f
        const val MESSAGE_ACTION_MS = 6000L
        const val DOUBLE_TAP_MS = 350L
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        /** Holding ABC this long gives the other keyboard (Gboard, or Voicebar's). */
        const val ABC_HOLD_MS = 450L
        /** After a pause for the pad or keyboard: how often, and how many times, to check the polish is in before listening again. */
        const val RESUME_POLL_MS = 120L
        const val RESUME_TRIES = 60
        /** The keyboard's room above the bar: the handle, suggestions and three rows (the fourth is in the bar's row). */
        const val KEYS_DP = 234f
        /** Holding the seven-button bar's own key this long opens its choices. */
        const val PICKER_HOLD_MS = 350L
        /** Holding Voice this long, at rest, starts a fix by voice. */
        const val MIC_HOLD_MS = 400L
        const val PICKER_W_DP = 210f
        /** Between the bar's top edge and the bottom of the choices' column. */
        const val PICKER_GAP_DP = 10f
        /** The keyboard opened this soon after listening started: what was heard is dropped, not cleaned or kept. */
        const val EARLY_KEYS_MS = 4_000L
        val PICKER_LABELS = mapOf(
            Bar.ExtraKey.SPACE to R.string.bar_key_space, Bar.ExtraKey.PASTE to R.string.bar_key_paste,
            Bar.ExtraKey.UNDO to R.string.bar_key_undo, Bar.ExtraKey.SELECT_ALL to R.string.bar_key_select_all,
            Bar.ExtraKey.LAST_DICTATION to R.string.bar_key_last, Bar.ExtraKey.BACKSPACE to R.string.bar_key_backspace,
            Bar.ExtraKey.FIX_LAST to R.string.dock_fix_offer, Bar.ExtraKey.ENTER to R.string.bar_key_enter,
            Bar.ExtraKey.STYLE to R.string.bar_key_style,
        )
        val MENU_LABELS = mapOf(
            MenuItem.PASTE to R.string.bar_key_paste, MenuItem.DICTATIONS to R.string.menu_dictations,
            MenuItem.COLLAPSE to R.string.menu_collapse, MenuItem.EXPAND to R.string.menu_expand,
            MenuItem.UPRIGHT to R.string.menu_upright, MenuItem.SIDEWAYS to R.string.menu_sideways,
            MenuItem.DOCK to R.string.menu_dock, MenuItem.FLOAT to R.string.menu_float,
            MenuItem.SWITCH to R.string.dock_switch, MenuItem.SETTINGS to R.string.dock_settings,
        )
        /** The docked bar's own keys: each slot's pref and what it is unless changed (☰ is always ☰). */
        val DOCKED_SLOTS = mapOf(
            Act.SPACE_ALT to (Prefs.BAR_KEY to Bar.ExtraKey.SPACE),
            Act.BACKSPACE to (Prefs.BAR_KEY_RIGHT to Bar.ExtraKey.BACKSPACE),
            Act.ENTER to (Prefs.BAR_KEY_ENTER to Bar.ExtraKey.ENTER),
        )
        /** The floating pill's one own key, in ↵'s slot (so it stays put while dictating). */
        val FLOAT_SLOTS = mapOf(Act.ENTER to (Prefs.BAR_KEY_FLOAT to Bar.ExtraKey.BACKSPACE))
        const val BKSP_REPEAT_START_MS = 250L
        const val PAD_HOLD_MS = 350L
        const val PAD_COL_DP = 8f
        const val PAD_LINE_DP = 22f
        const val SLIDE_START_DP = 12f
        const val SLIDE_STEP_DP = 9f
        const val DISABLED_ALPHA = 0.38f
        /** One spring for every move: a slight overshoot, settled in about a third of a second. */
        const val SPRING_K = 420f
        const val SPRING_ZETA = 0.72f
        val TONAL = setOf(Act.DISCARD, Act.FINISH, Act.BACKSPACE)
        const val NUM_LEFT = "-+*"
    }
}
