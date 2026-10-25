package com.ryderdavid.voicebar

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import com.ryderdavid.voicebar.handsfree.HandsFreeSession
import com.ryderdavid.voicebar.voice.CorrectionWatch
import com.ryderdavid.voicebar.voice.HandsFreeStart
import com.ryderdavid.voicebar.voice.ScreenContext
import com.ryderdavid.voicebar.voice.WakeWord
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.animation.PathInterpolator
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * ABC on the voice bar hands the field to Gboard. While Gboard is
 * up, this service draws a way back to Bubble Keyboard on top of it, in one of three styles
 * (Settings ▸ Look ▸ Way back from Gboard):
 *
 *  - **Bar** ([Prefs.GBOARD_RETURN_BAR]): a full-width strip fused to Gboard's top edge in Gboard's
 *    own background color (sampled from the screen), rounded top corners, no shadow, with a tonal
 *    "Tap to return" pill. It reads as a taller Gboard.
 *  - **Voice key** ([Prefs.GBOARD_RETURN_MIC_KEY]): a mic button in the spot of Gboard's voice key.
 *    With Gboard's voice key on, it covers that key and takes its taps; with the voice key turned off
 *    in Gboard, it sits at the right end of Gboard's top row, mirroring the ⊞ button, whether the row
 *    shows the toolbar or suggestions. If Gboard's layout can't be read, the floating bubble stands in.
 *  - **Bubble** ([Prefs.GBOARD_RETURN_BUBBLE]): the draggable mic bubble floating above Gboard.
 *
 * Tapping any of them switches back (Android 11+, via the accessibility keyboard controller).
 * It reads only Gboard's window: its position, its key layout, and its background color.
 */
class GboardButtonService : AccessibilityService() {

    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val imeBounds = Rect()

    private var bubble: MicBubble? = null
    private var bar: ReturnBar? = null
    private var slot: MicSlot? = null

    /** Gboard's background, read off the screen; null until the first sample (or if it fails). */
    private var gboardSurface: Int? = null
    private var sampledFor = Rect()
    private var lastSample = 0L

    private val refresh = Runnable { update() }
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.GBOARD_RETURN || key == Prefs.WAKE_WORD) main.post { hideAll(); update() }
        if (key == Prefs.HANDS_FREE || key == Prefs.HANDS_FREE_ANYWHERE || key == Prefs.WAKE_WORD) main.post { listenAnywhere() }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        running = this
        // remembered, so the keyboard can tell when Android has switched it off since
        Prefs.get(this).edit().putBoolean(Prefs.WAY_BACK_WAS_ON, true).apply()
        wm = getSystemService(WindowManager::class.java)
        Prefs.get(this).registerOnSharedPreferenceChangeListener(prefsListener)
        HandsFreeStart.handler = { startHandsFree() }
        HandsFreeStart.stopper = { handsFree?.stop() }
        update()
        listenAnywhere()
    }

    /* ---------------------------------------------------------------- hands-free */

    private var handsFree: HandsFreeSession? = null

    /** Starts hands-free mode (from "voice bar, hands free", the tile, or the keyboard). */
    fun startHandsFree(): Boolean {
        if (!Prefs.get(this).getBoolean(Prefs.HANDS_FREE, Prefs.DEFAULT_HANDS_FREE)) return false
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) return false
        if (handsFree?.isActive == true) return true
        HandsFreeStart.active = true
        handsFree = HandsFreeSession(this) {
            handsFree = null
            HandsFreeStart.active = false
        }.also { it.start() }
        return true
    }

    private val anywhereListener = object : WakeWord.Listener {
        override fun onWake() {}
        override fun onListen() {}
        override fun onHide() {}
        override fun onWakeGone() {}
        override fun onHandsFree() { startHandsFree() }
    }

    /** "Listen for it everywhere": the wake word runs whenever nothing else wants it. */
    private fun listenAnywhere() {
        val p = Prefs.get(this)
        val on = p.getBoolean(Prefs.HANDS_FREE, Prefs.DEFAULT_HANDS_FREE) &&
            p.getBoolean(Prefs.HANDS_FREE_ANYWHERE, Prefs.DEFAULT_HANDS_FREE_ANYWHERE) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        WakeWord.want(this, ANYWHERE_OWNER, if (on) anywhereListener else null, onlyIfAlone = true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        handsFree?.let { hf ->
            when (event?.eventType) {
                AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> hf.onScreenChanged()
            }
        }
        when (event?.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> schedule(0)
            // the user editing what Voicebar typed (in Gboard): pass the field's text along to learn from
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> if (CorrectionWatch.pending != null && !event.isPassword) {
                val text = runCatching { event.source?.text?.toString() }.getOrNull() ?: event.text.joinToString("")
                CorrectionWatch.seen(event.packageName?.toString(), text)
            }
            // Gboard's rows change (suggestions, symbols, emoji): follow its keys, a little debounced
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (event.packageName == Prefs.GBOARD_PACKAGE && style() == Prefs.GBOARD_RETURN_MIC_KEY) schedule(80)
        }
    }

    override fun onInterrupt() {}

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // dark mode, wallpaper colors, rotation: rebuild with fresh colors and a fresh sample
        hideAll()
        gboardSurface = null
        sampledFor.setEmpty()
        schedule(0)
    }

    override fun onDestroy() {
        if (running === this) running = null
        handsFree?.stop()
        HandsFreeStart.handler = null
        HandsFreeStart.stopper = null
        WakeWord.want(this, ANYWHERE_OWNER, null)
        Prefs.get(this).unregisterOnSharedPreferenceChangeListener(prefsListener)
        main.removeCallbacksAndMessages(null)
        hideAll()
        super.onDestroy()
    }

    private fun schedule(delay: Long) {
        main.removeCallbacks(refresh)
        main.postDelayed(refresh, delay)
    }

    private fun style(): String =
        Prefs.get(this).getString(Prefs.GBOARD_RETURN, Prefs.DEFAULT_GBOARD_RETURN) ?: Prefs.DEFAULT_GBOARD_RETURN

    private fun gboardIsCurrent(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("${Prefs.GBOARD_PACKAGE}/") == true

    /** Show the chosen way back when the setting is on and Gboard's window is on screen; otherwise hide. */
    private fun update() {
        val on = Build.VERSION.SDK_INT >= 30 &&
            gboardIsCurrent()
        val ime = if (on) runCatching { windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrNull() else null
        if (ime == null) { hideAll(); return }
        ime.getBoundsInScreen(imeBounds)
        if (imeBounds.height() <= 0) { hideAll(); return }
        // with a physical keyboard, Gboard often shows only its small floating toolbar (which keeps
        // changing size): nothing to sit on, so nothing to add. Its full keyboard still gets the button.
        if (HardKeyboard.attached(resources.configuration) && toolbarOnly(imeBounds)) { hideAll(); return }

        when (style()) {
            Prefs.GBOARD_RETURN_BAR -> {
                hideBubble(); hideSlot()
                sampleSurface()
                showBar()
            }
            Prefs.GBOARD_RETURN_MIC_KEY -> {
                hideBubble(); hideBar()
                sampleSurface()
                val spot = runCatching { ime.root }.getOrNull()?.let { micKeySpot(it) }
                if (spot != null) { showSlot(spot) } else { hideSlot(); showBubble(); placeBubble() }
            }
            else -> {
                hideBar(); hideSlot()
                showBubble(); placeBubble()
            }
        }
        watch(true)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Gboard's floating toolbar rather than its keyboard: a short row or a narrow column. */
    private fun toolbarOnly(b: Rect): Boolean =
        b.height() < dp(TOOLBAR_MAX_DP) || b.width() < dp(TOOLBAR_MAX_DP)

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
    }

    private fun move(v: View, x: Int, y: Int, w: Int, h: Int) {
        val p = v.layoutParams as? WindowManager.LayoutParams ?: return
        if (p.x == x && p.y == y && p.width == w && p.height == h) return
        p.x = x; p.y = y; p.width = w; p.height = h
        runCatching { wm?.updateViewLayout(v, p) }
    }

    private fun add(v: View, x: Int, y: Int, w: Int, h: Int): Boolean {
        val p = overlayParams(w, h).apply { this.x = x; this.y = y }
        return runCatching { wm?.addView(v, p) }.isSuccess
    }

    private fun remove(v: View?) {
        if (v != null) runCatching { wm?.removeViewImmediate(v) }
    }

    private fun hideAll() { hideBubble(); hideBar(); hideSlot(); watch(false) }

    /* ---------------------------------------------------------------- "voice bar, listen" over Gboard */

    private var waking = false
        set(v) { field = v; bar?.waking = v; slot?.waking = v; bubble?.waking = v }

    private val wakeListener = object : WakeWord.Listener {
        override fun onWake() { waking = true }
        override fun onListen() { waking = false; switchBack() }
        // Back closes whatever keyboard is up
        override fun onHide() { waking = false; performGlobalAction(GLOBAL_ACTION_BACK) }
        override fun onWakeGone() { waking = false }
        override fun onHandsFree() { waking = false; startHandsFree() }
    }

    /** While the way-back button is up, listen (on the phone) for "voice bar, listen". */
    private fun watch(on: Boolean) {
        val want = on && Prefs.get(this).getBoolean(Prefs.WAKE_WORD, Prefs.DEFAULT_WAKE_WORD) &&
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!want) waking = false
        WakeWord.want(this, WAKE_OWNER, if (want) wakeListener else null)
    }

    private fun switchBack() {
        if (Build.VERSION.SDK_INT < 30) return
        val id = ComponentName(this, VoicebarService::class.java).flattenToShortString()
        // Bubble Keyboard starts listening as soon as it opens
        GboardReturn.voiceUntil = SystemClock.uptimeMillis() + 3000
        val ok = runCatching { softKeyboardController.switchToInputMethod(id) }.getOrDefault(false)
        if (ok) hideAll()
    }

    /**
     * The text field the keyboard was meant for, focused again: a tap on it, which also brings back
     * its connection to the keyboard. In [pkg]'s windows only: the field with [hint] if there is one,
     * else the focused one, else the lowest on screen (a chat's message box). Never a password field.
     * False when there's none to focus. Blocking IPC, but short.
     */
    fun refocusField(pkg: String?, hint: String?): Boolean = runCatching {
        val fields = ArrayList<AccessibilityNodeInfo>()
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            val p = r.packageName?.toString()
            if (p == packageName || (pkg != null && p != pkg)) continue
            flatten(r, cap = 2000).filterTo(fields) { it.isEditable && it.isVisibleToUser && !it.isPassword }
        }
        val bounds = Rect()
        val i = FieldPick.choose(fields.map { it.getBoundsInScreen(bounds); FieldPick.Box(it.hintText?.toString(), it.isFocused, bounds.bottom) }, hint)
        if (i < 0) return@runCatching false
        val target = fields[i]
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        target.performAction(AccessibilityNodeInfo.ACTION_CLICK) || target.isFocused
    }.getOrDefault(false)

    /** The focused text field's text, or null (none, or a password). */
    fun focusedText(): String? = runCatching {
        val n = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        if (n.isPassword) null else n.text?.toString()
    }.getOrNull()

    /**
     * The text on screen in the app being typed into (every app window: split screen counts), in
     * reading order, for dictation context. Never the keyboards, the status bar or overlays, and
     * never password fields. Blocking IPC: call it off the main thread.
     */
    fun screenText(): ScreenContext.Snapshot? = runCatching {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val pieces = ArrayList<ScreenContext.Piece>()
        val r = Rect()
        var budget = SCREEN_NODES
        fun walk(n: AccessibilityNodeInfo) {
            if (budget-- <= 0 || !n.isVisibleToUser) return
            if (!n.isPassword) {
                val text = n.text?.toString()?.trim().orEmpty()
                val label = n.contentDescription?.toString()?.trim().orEmpty()
                val said = text.ifEmpty { label.takeIf { it.length in 2..40 } ?: "" }
                if (said.isNotEmpty() && (!n.isEditable || n.isFocused)) {
                    n.getBoundsInScreen(r)
                    pieces += ScreenContext.Piece(said, r.top, r.left, field = n.isEditable)
                }
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let(::walk)
        }
        // only the app being typed into: other windows (a chat bubble's app behind it, split
        // screen, Voicebar's own settings) would feed the recognizer someone else's words
        val roots = apps.mapNotNull { w -> w.root?.let { w to it } }
            .filter { (_, root) -> root.packageName?.toString() != packageName }
        val target = roots.firstOrNull { (w, _) -> w.isFocused } ?: roots.firstOrNull { (_, root) -> root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) != null }
        (target?.let { listOf(it) } ?: emptyList()).forEach { walk(it.second) }
        val app = target?.second?.packageName?.toString()?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
        }.orEmpty()
        ScreenContext.build(pieces, app)
    }.getOrNull()

    /* ---------------------------------------------------------------- send */

    /**
     * Presses the app's own Send button, for fields that don't offer a Send action to keyboards
     * (most chat apps' multi-line message boxes): a clickable control labeled or named "send",
     * the one closest to the focused text field. False when there's none (yet).
     *
     * The search starts at the text field and widens through its parents, so the composer row is
     * looked at first: a long conversation above it can run to more nodes than a whole-window
     * search would ever reach. And it looks in the window holding the text field, which isn't
     * always the "active" one (a chat bubble or pop-up over another app).
     */
    fun clickSend(): Boolean {
        val (root, focus) = typingWindow() ?: return false
        val anchor = Rect().also { r -> focus?.getBoundsInScreen(r) }
        val seen = HashSet<AccessibilityNodeInfo>()
        var scope: AccessibilityNodeInfo? = focus?.parent
        repeat(6) {
            val sc = scope ?: return@repeat
            pressNearest(sendButtons(flatten(sc, cap = 400), seen), anchor)?.let { return it }
            scope = sc.parent
        }
        return pressNearest(sendButtons(flatten(root, cap = 4000), seen), anchor) ?: false
    }

    /** The app window with the focused text field in it (and that field), or the active window. */
    private fun typingWindow(): Pair<AccessibilityNodeInfo, AccessibilityNodeInfo?>? = runCatching {
        for (w in windows) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            val r = w.root ?: continue
            if (r.packageName?.toString() == packageName) continue
            val f = r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (f != null) return@runCatching r to f
        }
        val r = rootInActiveWindow ?: return@runCatching null
        r.refresh()
        r to r.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
    }.getOrNull()

    private fun sendButtons(nodes: List<AccessibilityNodeInfo>, seen: MutableSet<AccessibilityNodeInfo>): List<AccessibilityNodeInfo> =
        nodes.filter { n ->
            if (!seen.add(n) || !n.isVisibleToUser || !n.isEnabled) return@filter false
            val label = listOfNotNull(n.contentDescription, n.text).joinToString(" ")
            val id = n.viewIdResourceName?.substringAfter(":id/") ?: ""
            (SEND_LABEL.containsMatchIn(label) && label.length <= 40) || SEND_ID.containsMatchIn(id)
        }.mapNotNull { n -> clickableSelfOrParent(n)?.takeIf { it.isEnabled } }.distinct()

    /** Clicks the candidate nearest [anchor]; null when there are none. */
    private fun pressNearest(candidates: List<AccessibilityNodeInfo>, anchor: Rect): Boolean? {
        if (candidates.isEmpty()) return null
        val b = Rect()
        val best = candidates.minBy { n ->
            n.getBoundsInScreen(b)
            if (anchor.isEmpty) 0.0 else Math.hypot((b.exactCenterX() - anchor.exactCenterX()).toDouble(), (b.exactCenterY() - anchor.exactCenterY()).toDouble())
        }
        return best.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun clickableSelfOrParent(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var cur: AccessibilityNodeInfo? = n
        repeat(4) {
            val c = cur ?: return null
            if (c.isClickable) return c
            cur = c.parent
        }
        return null
    }

    /** Whether a button labeled or named "send" is there now, without pressing it. */
    fun hasLabeledSend(): Boolean {
        val (root, focus) = typingWindow() ?: return false
        val scope = focus?.parent?.parent?.parent ?: root
        return sendButtons(flatten(scope, cap = 600), HashSet()).isNotEmpty()
    }

    /**
     * The composer row for [SendFinder]: the message box and the clickable controls to its right,
     * with a numbered picture of the row when [shot] (Android 11+). Blocking: not on the main
     * thread (the screenshot comes back on it).
     */
    fun sendRow(shot: Boolean): SendFinder.Row? {
        val (root, focus) = typingWindow() ?: return null
        focus ?: return null
        val fr = Rect().also { focus.getBoundsInScreen(it) }
        if (fr.isEmpty) return null
        val field = SendFinder.Box(fr.left, fr.top, fr.right, fr.bottom)
        val slack = dp(24f).toInt()
        val b = Rect()
        // only the message box's row: a long conversation above it is never walked (it could use up the
        // walk before the composer at the bottom is reached)
        val found = mutableListOf<SendFinder.Button>()
        var walked = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (walked++ > ROW_NODES) return
            n.getBoundsInScreen(b)
            if (b.bottom < fr.top - slack || b.top > fr.bottom + slack) return
            if (n.isClickable && n.isVisibleToUser && n.isEnabled && n != focus && !n.isEditable &&
                // the composer itself, around the box, isn't a button
                !b.contains(fr.centerX(), fr.centerY())) {
                found += SendFinder.Button(SendFinder.Box(b.left, b.top, b.right, b.bottom), listOfNotNull(n.contentDescription, n.text).joinToString(" ").trim(),
                    n.viewIdResourceName?.substringAfter(":id/") ?: "", n.className?.toString() ?: "")
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        val buttons = SendFinder.candidates(field, found, slack)
        val app = root.packageName?.toString() ?: ""
        AppLog.log("send", "finder: box at ${fr.toShortString()}, ${found.size} buttons on its row, ${buttons.size} to its right: " +
            buttons.joinToString(" · ") { listOf(it.kind.substringAfterLast('.'), it.id, it.label).filter { s -> s.isNotBlank() }.joinToString(" ") + " @" + it.box.cx })
        val jpeg = if (shot && buttons.isNotEmpty()) rowPicture(field, buttons) else null
        return SendFinder.Row(app, field, buttons, jpeg)
    }

    /** The composer row cut from a screenshot, each button outlined and numbered, as base64 JPEG. API 30+. */
    private fun rowPicture(field: SendFinder.Box, buttons: List<SendFinder.Button>): String? {
        if (Build.VERSION.SDK_INT < 30) return null
        val latch = java.util.concurrent.CountDownLatch(1)
        var screen: Bitmap? = null
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        screen = hw?.copy(Bitmap.Config.ARGB_8888, true)
                        hw?.recycle()
                        result.hardwareBuffer.close()
                    }
                    latch.countDown()
                }
                override fun onFailure(errorCode: Int) { AppLog.log("send", "finder screenshot failed: $errorCode"); latch.countDown() }
            })
        }.onFailure { latch.countDown() }
        latch.await(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
        val full = screen ?: return null
        return runCatching {
            val pad = dp(12f).toInt()
            val top = (minOf(field.top, buttons.minOf { it.box.top }) - pad).coerceIn(0, full.height - 1)
            val bottom = (maxOf(field.bottom, buttons.maxOf { it.box.bottom }) + pad).coerceIn(top + 1, full.height)
            val left = (field.left - pad).coerceIn(0, full.width - 1)
            val row = Bitmap.createBitmap(full, left, top, full.width - left, bottom - top)
            val c = Canvas(row)
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f); color = MARK }
            val badge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MARK }
            val num = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = dp(11f); typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
            buttons.forEachIndexed { i, btn ->
                val r = RectF((btn.box.left - left).toFloat(), (btn.box.top - top).toFloat(), (btn.box.right - left).toFloat(), (btn.box.bottom - top).toFloat())
                c.drawRect(r, line)
                val br = dp(8f)
                c.drawCircle(r.left + br, r.top + br, br, badge)
                c.drawText("${i + 1}", r.left + br, r.top + br - (num.ascent() + num.descent()) / 2, num)
            }
            val scale = min(1f, ROW_WIDTH / row.width.toFloat())
            val small = if (scale < 1f) Bitmap.createScaledBitmap(row, (row.width * scale).toInt(), (row.height * scale).toInt().coerceAtLeast(1), true) else row
            val out = java.io.ByteArrayOutputStream()
            small.compress(Bitmap.CompressFormat.JPEG, 80, out)
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        }.onFailure { AppLog.log("send", "finder picture failed: ${it.javaClass.simpleName}") }.getOrNull().also { full.recycle() }
    }

    /**
     * Presses [button] as it is now: the clickable control with its view id there, else the
     * smallest clickable one under its middle (an app may have swapped a mic for Send since).
     */
    fun pressButton(button: SendFinder.Button): Boolean {
        val (root, _) = typingWindow() ?: return false
        val b = Rect()
        val under = flatten(root, cap = 4000).filter { n ->
            if (!n.isClickable || !n.isVisibleToUser || !n.isEnabled) return@filter false
            n.getBoundsInScreen(b)
            b.contains(button.box.cx, button.box.cy)
        }
        val byId = button.id.takeIf { it.isNotBlank() }?.let { id -> under.firstOrNull { it.viewIdResourceName?.substringAfter(":id/") == id } }
        val target = byId ?: under.minByOrNull { n -> n.getBoundsInScreen(b); b.width().toLong() * b.height() } ?: return false
        return target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    /* ---------------------------------------------------------------- bar */

    private fun showBar() {
        val h = dp(BAR_DP).toInt()
        // 1 px into Gboard, so there's no seam between the bar and the keyboard
        val x = imeBounds.left
        val y = imeBounds.top - h + 1
        val w = imeBounds.width()
        val v = bar
        if (v != null) {
            v.surface = gboardSurface
            move(v, x, y, w, h)
            return
        }
        val nv = ReturnBar(this, getString(R.string.gboard_return_label)) { switchBack() }
        nv.surface = gboardSurface
        if (!add(nv, x, y, w, h)) return
        bar = nv
        // rise into place with Material's emphasized-decelerate curve
        nv.alpha = 0f
        nv.translationY = dp(10f)
        nv.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(EMPHASIZED_DECELERATE).start()
    }

    private fun hideBar() { remove(bar); bar = null }

    /* ---------------------------------------------------------------- voice key */

    /**
     * Where Gboard's voice key is, or would be. Gboard labels its keys for screen readers, so the
     * voice key is found by its description. With the voice key turned off, the spot is the empty
     * space at the right end of the top toolbar row; null if that row is full.
     */
    private fun micKeySpot(root: AccessibilityNodeInfo): Rect? {
        val nodes = flatten(root)
        val b = Rect()

        // 1. Gboard's own voice key showing: cover it exactly.
        nodes.firstOrNull { it.isVisibleToUser && VOICE_KEY.containsMatchIn(it.contentDescription ?: "") }?.let {
            it.getBoundsInScreen(b)
            if (!b.isEmpty) return Rect(b)
        }
        // 2. Otherwise the mirror of Gboard's ⊞ button (the row's first key) at the right end, when
        // nothing's there. 3. Failing that, the right end, stepping aside for round buttons. It stays put whether the row shows the toolbar or suggestions (a suggestion
        // in the middle must not pull it inward). Key size and edge margin are learned from the
        // toolbar's evenly sized icons and remembered for when suggestions take the row.
        val band = imeBounds.top + dp(72f)
        val row = nodes.mapNotNull { n ->
            if (!n.isVisibleToUser || !n.isClickable) return@mapNotNull null
            n.getBoundsInScreen(b)
            if (b.height() in 1..dp(64f).toInt() && b.top < band && b.width() < imeBounds.width() / 2) Rect(b) else null
        }
        if (row.isEmpty()) return null
        val rowTop = row.minOf { it.top }
        val sameRow = row.filter { it.top <= rowTop + dp(8f) }.sortedBy { it.left }
        val rowBottom = sameRow.maxOf { it.bottom }
        val widths = sameRow.map { it.width() }.sorted()
        val toolbar = sameRow.size >= 3 && widths.last() - widths.first() <= dp(6f)
        if (toolbar && imeBounds.width() != learnedFor) {
            learnedFor = imeBounds.width()
            learnedKey = widths[widths.size / 2]
            learnedEdge = sameRow.first().left - imeBounds.left
        }
        sameRow.firstOrNull()?.takeIf { toolbar || learnedFor == imeBounds.width() }?.let { g ->
            val mirror = Rect(imeBounds.right - (g.right - imeBounds.left), g.top, imeBounds.right - (g.left - imeBounds.left), g.bottom)
            if (mirror.left > g.right && sameRow.none { Rect.intersects(it, mirror) }) return mirror
        }
        val key = if (learnedFor == imeBounds.width()) learnedKey else min(rowBottom - rowTop, dp(48f).toInt())
        val edge = if (learnedFor == imeBounds.width()) learnedEdge else dp(8f).toInt()
        val cy = (rowTop + rowBottom) / 2
        val size = min(key, rowBottom - rowTop)
        return voiceSlot(sameRow, imeBounds, key, size, edge, cy, dp(4f).toInt())
    }

    /** The toolbar's icon width and left margin, for the keyboard width they were measured at. */
    private var learnedFor = -1
    private var learnedKey = 0
    private var learnedEdge = 0

    private fun showSlot(r: Rect) {
        val v = slot
        if (v != null) {
            v.surface = gboardSurface
            move(v, r.left, r.top, r.width(), r.height())
            return
        }
        val nv = MicSlot(this) { switchBack() }
        nv.surface = gboardSurface
        if (add(nv, r.left, r.top, r.width(), r.height())) slot = nv
    }

    private fun hideSlot() { remove(slot); slot = null }

    private fun flatten(root: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo> = mutableListOf(), cap: Int = 600): List<AccessibilityNodeInfo> {
        if (out.size > cap) return out
        out += root
        for (i in 0 until root.childCount) root.getChild(i)?.let { flatten(it, out, cap) }
        return out
    }

    /* ---------------------------------------------------------------- Gboard's color */

    /**
     * Read Gboard's background off the screen: the most common color along a line just inside its
     * top edge (the most common, so a toolbar icon under one sample point doesn't win). Done once per
     * keyboard position; the platform allows about three screenshots a second.
     */
    private fun sampleSurface() {
        if (Build.VERSION.SDK_INT < 30) return
        if (gboardSurface != null && sampledFor == imeBounds) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSample < 400) { schedule(420); return }
        lastSample = now
        val at = Rect(imeBounds)
        runCatching {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val buffer = result.hardwareBuffer
                    val hw = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    val bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                    hw?.recycle()
                    buffer.close()
                    if (bmp == null) return
                    val y = (at.top + dp(3f).toInt()).coerceIn(0, bmp.height - 1)
                    val samples = (1..12).map { i ->
                        bmp.getPixel((at.left + at.width() * i / 13).coerceIn(0, bmp.width - 1), y) or 0xFF000000.toInt()
                    }
                    bmp.recycle()
                    val color = samples.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: return
                    sampledFor = at
                    if (color != gboardSurface) {
                        gboardSurface = color
                        schedule(0)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    sampledFor = at  // keep the theme's surface color rather than retrying forever
                }
            })
        }
    }

    /* ---------------------------------------------------------------- floating bubble */

    private val bubbleSize get() = dp(BUBBLE_DP).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble() {
        if (bubble != null) return
        val v = MicBubble(this)
        v.setOnTouchListener(DragOrTap())
        if (add(v, 0, 0, bubbleSize, bubbleSize)) bubble = v
    }

    private fun hideBubble() { remove(bubble); bubble = null }

    /** Where the bubble sits: its saved x, and its saved gap above the keyboard's top edge. */
    private fun placeBubble() {
        val v = bubble ?: return
        val prefs = Prefs.get(this)
        val screenW = resources.displayMetrics.widthPixels
        val size = bubbleSize
        val x = prefs.getInt(Prefs.GBOARD_BUBBLE_X, -1).let { if (it < 0) screenW - size - dp(12f).toInt() else it }
        val gap = prefs.getInt(Prefs.GBOARD_BUBBLE_GAP, dp(12f).toInt())
        move(v, x.coerceIn(0, (screenW - size).coerceAtLeast(0)), (imeBounds.top - gap - size).coerceAtLeast(0), size, size)
    }

    /** A tap switches back; a drag moves the bubble and remembers where it was left. */
    private inner class DragOrTap : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val p = v.layoutParams as? WindowManager.LayoutParams ?: return false
            val size = bubbleSize
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = p.x; startY = p.y; dragging = false
                    (v as MicBubble).down = true; v.invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > ViewConfiguration.get(v.context).scaledTouchSlop) dragging = true
                    if (dragging) {
                        p.x = (startX + dx).toInt().coerceIn(0, resources.displayMetrics.widthPixels - size)
                        p.y = (startY + dy).toInt().coerceIn(0, (imeBounds.top - size).coerceAtLeast(0))
                        runCatching { wm?.updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    (v as MicBubble).down = false; v.invalidate()
                    if (dragging) {
                        Prefs.get(this@GboardButtonService).edit {
                            putInt(Prefs.GBOARD_BUBBLE_X, p.x)
                            putInt(Prefs.GBOARD_BUBBLE_GAP, (imeBounds.top - p.y - size).coerceAtLeast(0))
                        }
                    } else if (abs(e.rawX - downX) < dp(24f)) {
                        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        switchBack()
                    }
                }
                MotionEvent.ACTION_CANCEL -> { (v as MicBubble).down = false; v.invalidate() }
            }
            return true
        }
    }

    /* ---------------------------------------------------------------- views */

    /** Shared press handling for the bar and the voice-key button: a tonal press state and a tap. */
    private abstract class Pressable(context: Context, private val onTap: () -> Unit) : View(context) {
        var down = false
            private set
        val pal = Palette(context)
        val d = resources.displayMetrics.density
        /** "Voice bar" was just heard: purple, with a breathing glow. */
        var waking = false
            set(v) { if (field != v) { field = v; invalidate() } }
        /** How much the glow shows right now, 0..1 (and keeps the animation going while waking). */
        fun breathe(): Float {
            if (!waking) return 0f
            postInvalidateOnAnimation()
            return 0.65f + 0.35f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() / 260.0).toFloat()
        }

        init {
            isClickable = true
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onTap()
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
            }
            return super.onTouchEvent(event)
        }
    }

    /** The bar: Gboard's surface with rounded top corners and a tonal "Tap to return" pill. */
    private class ReturnBar(context: Context, private val label: String, onTap: () -> Unit) : Pressable(context, onTap) {
        var surface: Int? = null
            set(v) { if (field != v) { field = v; invalidate() } }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)  // M3 label large
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            letterSpacing = 0.00714f
        }
        private val sheet = Path()
        private val r = RectF()

        init { contentDescription = label }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val c = 20f * d
            sheet.reset()
            sheet.addRoundRect(0f, 0f, w, h + c, floatArrayOf(c, c, c, c, 0f, 0f, 0f, 0f), Path.Direction.CW)
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            fill.color = surface ?: pal.disc
            canvas.drawPath(sheet, fill)
            canvas.restore()

            // the pill: mic + label, centered
            val icon = 18f * d
            val gap = 8f * d
            val padH = 16f * d
            val tw = text.measureText(label)
            val pw = padH + icon + gap + tw + padH
            val ph = 32f * d
            val left = (w - pw) / 2f
            val top = (h - ph) / 2f
            r.set(left, top, left + pw, top + ph)
            val glow = breathe()
            if (waking) for (i in 1..5) {
                fill.color = ColorUtils.setAlphaComponent(pal.command, (255 * glow * 0.22f * (1f - i / 6f)).toInt())
                canvas.drawRoundRect(r.left - i * 1.2f * d, r.top - i * 1.2f * d, r.right + i * 1.2f * d, r.bottom + i * 1.2f * d,
                    ph / 2f + i * 1.2f * d, ph / 2f + i * 1.2f * d, fill)
            }
            val bg = if (waking) pal.command else pal.diagonal
            val fg = if (waking) pal.onCommand else pal.onDiagonal
            fill.color = if (down) ColorUtils.blendARGB(bg, fg, 0.12f) else bg
            canvas.drawRoundRect(r, ph / 2f, ph / 2f, fill)
            drawMic(canvas, left + padH + icon / 2f, h / 2f, icon / 2f * 1.1f, fg)
            text.color = fg
            canvas.drawText(if (waking) context.getString(R.string.dock_command_wait) else label,
                left + padH + icon + gap, h / 2f - (text.ascent() + text.descent()) / 2f, text)
        }
    }

    /**
     * The voice-key button: Gboard's surface covering the whole key (so Gboard's own mic can't show
     * through), with Bubble Keyboard's mic on a primary disc in the middle.
     */
    private class MicSlot(context: Context, onTap: () -> Unit) : Pressable(context, onTap) {
        var surface: Int? = null
            set(v) { if (field != v) { field = v; invalidate() } }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        init { contentDescription = context.getString(R.string.gboard_return_label) }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            fill.color = surface ?: pal.disc
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fill)
            val rad = min(min(width, height) / 2f - 2f * d, 18f * d)
            val glow = breathe()
            if (waking) for (i in 1..5) {
                fill.color = ColorUtils.setAlphaComponent(pal.command, (255 * glow * 0.25f * (1f - i / 6f)).toInt())
                canvas.drawCircle(cx, cy, rad + i * 1.2f * d, fill)
            }
            val bg = if (waking) pal.command else pal.pressed
            fill.color = if (down) ColorUtils.blendARGB(bg, Color.BLACK, 0.15f) else bg
            canvas.drawCircle(cx, cy, rad, fill)
            drawMic(canvas, cx, cy, rad * 0.62f, if (waking) pal.onCommand else pal.onPressed)
        }
    }

    /** The floating bubble: the voice pill's primary color, a mic, and a soft shadow. */
    private class MicBubble(context: Context) : View(context) {
        var down = false
        var waking = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val pal = Palette(context)
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

        init { contentDescription = context.getString(R.string.gboard_return_label) }

        override fun onDraw(canvas: Canvas) {
            val d = resources.displayMetrics.density
            val cx = width / 2f
            val cy = height / 2f
            val rad = width / 2f - (if (waking) 7 else 3) * d
            if (waking) {
                // a breathing purple glow where the shadow was
                val glow = 0.65f + 0.35f * kotlin.math.sin(android.os.SystemClock.uptimeMillis() / 260.0).toFloat()
                for (i in 1..6) {
                    fill.color = ColorUtils.setAlphaComponent(pal.command, (255 * glow * 0.26f * (1f - i / 7f)).toInt())
                    canvas.drawCircle(cx, cy, rad + i * 1.1f * d, fill)
                }
                postInvalidateOnAnimation()
            } else for (i in 3 downTo 1) {
                fill.color = ColorUtils.setAlphaComponent(Color.BLACK, 14 + 6 * (3 - i))
                canvas.drawCircle(cx, cy + 1.5f * d, rad + i * 0.8f * d, fill)
            }
            val bg = if (waking) pal.command else pal.pressed
            fill.color = if (down) ColorUtils.blendARGB(bg, Color.BLACK, 0.15f) else bg
            canvas.drawCircle(cx, cy, rad, fill)
            drawMic(canvas, cx, cy, rad * 0.9f, if (waking) pal.onCommand else pal.onPressed)
        }
    }

    companion object {
        /** Gboard's keyboard is taller than this; its hardware-keyboard toolbar isn't. */
        const val TOOLBAR_MAX_DP = 140f
        const val BUBBLE_DP = 52f
        private const val WAKE_OWNER = "gboard"
        private const val ANYWHERE_OWNER = "anywhere"
        const val BAR_DP = 44f
        /** Nodes read at most for the screen's text (a long chat has many). */
        private const val SCREEN_NODES = 1500

        /** How Gboard names its voice key for screen readers ("Voice typing", "Use voice typing", …). */
        private val VOICE_KEY = Regex("""voice|microphone|\bmic\b|dictat""", RegexOption.IGNORE_CASE)
        private val EMPHASIZED_DECELERATE = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)

        private val micPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val micRect = RectF()

        /** The voice pill's mic, centered on (cx, cy), fitting a circle of radius [half]. */
        fun drawMic(canvas: Canvas, cx: Float, cy: Float, half: Float, color: Int) {
            val u = half / 12f
            micPaint.color = color
            micPaint.style = Paint.Style.FILL
            micRect.set(cx - 3.2f * u, cy - 9f * u, cx + 3.2f * u, cy + 3f * u)
            canvas.drawRoundRect(micRect, 3.2f * u, 3.2f * u, micPaint)
            micPaint.style = Paint.Style.STROKE
            micPaint.strokeCap = Paint.Cap.ROUND
            micPaint.strokeWidth = 2f * u
            micRect.set(cx - 6.5f * u, cy - 5f * u, cx + 6.5f * u, cy + 6f * u)
            canvas.drawArc(micRect, 0f, 180f, false, micPaint)
            canvas.drawLine(cx, cy + 6f * u, cx, cy + 9.5f * u, micPaint)
        }

        /**
         * The spot for the voice button with Gboard's voice key off: the right end of the top row,
         * [edge] in from the keyboard's edge. It never covers another round button (Gboard's AI
         * writing key and the like appear there): if one is in the way, the spot slides to its left,
         * as many times as needed. Wide items (suggestions) may be covered. Null if it runs out of room.
         */
        internal fun voiceSlot(row: List<Rect>, ime: Rect, key: Int, size: Int, edge: Int, cy: Int, gap: Int): Rect? {
            val r = Rect(ime.right - edge - key, cy - size / 2, ime.right - edge, cy + size / 2)
            fun round(b: Rect) = b.height() > 0 && kotlin.math.abs(b.width() - b.height()) <= b.height() * 0.35f
            repeat(4) {
                val hit = row.filter { round(it) && Rect.intersects(it, r) }.minByOrNull { it.left } ?: return r
                r.offsetTo(hit.left - gap - key, r.top)
            }
            return if (r.left > ime.left + edge && row.none { round(it) && Rect.intersects(it, r) }) r else null
        }

        /** The connected service, to press an app's Send button for the keyboard. */
        @Volatile var running: GboardButtonService? = null
            private set
        private val SEND_LABEL = Regex("""^\s*send\b|\bsend (message|sms|text|now|email|reply)\b|^\s*submit\s*$""", RegexOption.IGNORE_CASE)
        /** The send finder's outlines and numbers: a magenta no app's composer is likely to use. */
        private const val MARK = 0xFFE5007E.toInt()
        /** The most elements the send finder walks looking for the composer row. */
        private const val ROW_NODES = 3000
        /** The send finder's picture is scaled down to this width at most. */
        private const val ROW_WIDTH = 720f
        private val SEND_ID = Regex("""(^|_)send(_|btn|button|$)""", RegexOption.IGNORE_CASE)

        /** Whether the user has turned this service on in Accessibility settings. */
        fun isEnabled(context: Context): Boolean {
            val me = ComponentName(context, GboardButtonService::class.java)
            val list = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return list.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

/** Hand-off from the way-back button to the keyboard: if the keyboard opens before [voiceUntil], it starts listening. */
internal object GboardReturn {
    @Volatile var voiceUntil = 0L
}
