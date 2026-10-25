package com.ryderdavid.voicebar

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.inputmethod.EditorInfo
import androidx.core.graphics.ColorUtils
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Voicebar's own keyboard: a full QWERTY that pops up from ABC and takes over the bar's row too,
 * so its bottom row (?123, ⋮, comma, space, period, ↵) is where the thumbs already are. Pull the
 * handle down to put the bar back; ⋮ opens the bar's menu, and held, hands over to Gboard.
 * Suggestions across the top; shift (tap once, double-tap to lock) and the field's own
 * capitals; ?123 and =\< pages; hold a key for its accents or number and slide to one; slide on
 * space to move the cursor; hold ⌫ to keep deleting; a second thumb types the first thumb's key at
 * once. It lays itself out in the panel [DockView] gives it, draws itself and takes its touches;
 * what it types goes through [Host].
 */
class PopKeys(private val host: Host) {
    interface Host {
        fun dp(v: Float): Float
        fun post(r: Runnable, delayMs: Long)
        fun cancel(r: Runnable)
        fun invalidate()
        fun haptic(type: Int)
        /** Type [text] at the cursor (a character, or a space). */
        fun type(text: String)
        /** ⌫, ↵, and the cursor moves of a slide on space. */
        fun key(key: Bar.Key, repeat: Boolean)
        /** A suggestion was tapped: it replaces the word being typed. */
        fun suggest(word: String)
        /** Back to the voice bar (the handle pulled down). */
        fun close()
        /** The ⋮ key: the bar's menu, rising from [anchor] (the key, in bar coordinates). */
        fun menu(anchor: android.graphics.RectF)
        /** Hand the field over to Gboard. */
        fun gboard()
        /**
         * A swipe across the letters: its [points] and the a-to-z key centers ([keys], x, y pairs),
         * both in the [0, 1] frame of the letter rows. [caps]: 0 none, 1 a capital, 2 all capitals.
         */
        fun swipe(points: List<com.ryderdavid.voicebar.swipe.SwipeDecoder.Point>, keys: FloatArray, caps: Int) {}
    }

    enum class Page { LETTERS, NUMBERS, SYMBOLS }
    enum class Shift { OFF, ONCE, LOCK }
    enum class Kind { CHAR, SHIFT, BACK, ENTER, PAGE, SPACE, SPACER, MENU }

    class Key(val kind: Kind, val label: String, val sub: String = "", val flex: Float = 1f, val to: Page? = null) {
        val rect = RectF()
    }

    var page = Page.LETTERS
        private set
    var shift = Shift.OFF
        private set

    /** The field wants a capital here (the start of a sentence), as the service last said. */
    var autoCap = false
        set(v) { if (!v) autoOff = false; field = v; host.invalidate() }
    /** Shift was tapped to turn the field's capital off, until the field stops asking for one. */
    private var autoOff = false

    var suggestions: List<String> = emptyList()
        set(v) { field = v; host.invalidate() }

    /** Sliding across the letters types a word (swipe typing). */
    var swipeEnabled = true

    /** The field's action (EditorInfo.IME_ACTION_*), for ↵'s icon. */
    var enterAction = EditorInfo.IME_ACTION_UNSPECIFIED
        set(v) { field = v; host.invalidate() }

    /** How far the handle is pulled down: the panel follows the finger, this much lower. */
    var pull = 0f
        private set
    /** Let go short of closing: the panel eases back up. */
    private val settle = object : Runnable {
        override fun run() {
            pull *= 0.6f
            if (pull < host.dp(0.5f)) pull = 0f else host.post(this, 16L)
            host.invalidate()
        }
    }

    val upper get() = page == Page.LETTERS && (shift != Shift.OFF || (autoCap && !autoOff))

    /** Back to letters, no shift (a new field). */
    fun reset() {
        cancel()
        page = Page.LETTERS
        shift = Shift.OFF
        autoOff = false
        build()
    }

    // ---------------------------------------------------------------- layout

    private val card = RectF()
    private val handle = RectF()
    private val strip = RectF()
    private val slots = List(3) { RectF() }
    private var rows: List<List<Key>> = emptyList()

    init { build() }

    private fun chars(s: String, subs: String? = null) = s.mapIndexed { i, c -> Key(Kind.CHAR, c.toString(), subs?.getOrNull(i)?.toString() ?: "") }

    private fun build() {
        val back = Key(Kind.BACK, "⌫", flex = 1.5f)
        val enter = Key(Kind.ENTER, "↵", flex = 1.5f)
        val space = Key(Kind.SPACE, " ", flex = 4f)
        val menu = Key(Kind.MENU, "menu", flex = 1.2f)
        rows = when (page) {
            Page.LETTERS -> listOf(
                chars("qwertyuiop", "1234567890"),
                listOf(Key(Kind.SPACER, "", flex = 0.5f)) + chars("asdfghjkl") + Key(Kind.SPACER, "", flex = 0.5f),
                listOf(Key(Kind.SHIFT, "⇧", flex = 1.5f)) + chars("zxcvbnm") + back,
                listOf(Key(Kind.PAGE, "?123", flex = 1.3f, to = Page.NUMBERS), menu, Key(Kind.CHAR, ","), space, Key(Kind.CHAR, "."), enter),
            )
            Page.NUMBERS -> listOf(
                chars("1234567890"),
                chars("@#\$_&-+()/"),
                listOf(Key(Kind.PAGE, "=\\<", flex = 1.5f, to = Page.SYMBOLS)) + chars("*\"':;!?") + back,
                listOf(Key(Kind.PAGE, "ABC", flex = 1.3f, to = Page.LETTERS), menu, Key(Kind.CHAR, ","), space, Key(Kind.CHAR, "."), enter),
            )
            Page.SYMBOLS -> listOf(
                chars("~`|•√π÷×¶∆"),
                chars("£¢€¥^°={}\\"),
                listOf(Key(Kind.PAGE, "?123", flex = 1.5f, to = Page.NUMBERS)) + chars("%©®™✓[]") + back,
                listOf(Key(Kind.PAGE, "ABC", flex = 1.3f, to = Page.LETTERS), menu, Key(Kind.CHAR, "<"), space, Key(Kind.CHAR, ">"), enter),
            )
        }
        if (!card.isEmpty) layout(card)
    }

    /** Lays the keys out in [panel], the open panel's rectangle (in the bar's coordinates). */
    fun layout(panel: RectF) {
        card.set(panel)
        val side = host.dp(6f)
        val gap = host.dp(KEY_GAP_DP)
        val rowGap = host.dp(ROW_GAP_DP)
        handle.set(card.left, card.top, card.right, card.top + host.dp(HANDLE_DP))
        strip.set(card.left + side, handle.bottom, card.right - side, handle.bottom + host.dp(STRIP_DP))
        val sw = strip.width() / 3
        for (i in 0..2) slots[i].set(strip.left + i * sw, strip.top, strip.left + (i + 1) * sw, strip.bottom)
        val top = strip.bottom + host.dp(4f)
        val bottom = card.bottom - host.dp(8f)
        val kh = (bottom - top - (rows.size - 1) * rowGap) / rows.size
        val w = card.width() - 2 * side
        for ((r, row) in rows.withIndex()) {
            val flex = row.sumOf { it.flex.toDouble() }.toFloat()
            val unit = (w - (row.size - 1) * gap) / flex
            var x = card.left + side
            val y = top + r * (kh + rowGap)
            for (k in row) {
                k.rect.set(x, y, x + unit * k.flex, y + kh)
                x += unit * k.flex + gap
            }
        }
    }

    // ---------------------------------------------------------------- touch

    /** One finger on the keyboard. */
    private inner class Touch(val id: Int, var key: Key?, val x0: Float, val y0: Float) {
        /** Its key went in already (a second thumb came down, or ⌫ acts on the way down). */
        var done = false
        var sliding = false
        var slideX = 0f
        var onHandle = false
        /** The handle's last point and how fast it was moving down (px per ms), for a flick. */
        var lastY = y0
        var lastAt = android.os.SystemClock.uptimeMillis()
        var speed = 0f
        var slot = -1
        /** Held: the choices above the key, which one's under the finger. */
        var choices: List<String>? = null
        val choiceRects = mutableListOf<RectF>()
        var hot = -1
        val hold = Runnable { held(this) }
        /** Where the finger has been, in the bar's coordinates and at what time, for a swipe. */
        val path = mutableListOf(Triple(x0, y0, android.os.SystemClock.uptimeMillis()))
        /** The finger slid off its letter: a swipe, typed as a whole word when it lifts. */
        var swiping = false
        val repeat = object : Runnable {
            override fun run() { host.key(Bar.Key.DELETE, true); host.post(this, REPEAT_MS) }
        }
    }

    private val touches = LinkedHashMap<Int, Touch>()
    private var lastShiftTap = Long.MIN_VALUE / 2

    /** Whether ([x], [y]) is on the keyboard's panel (so its touches are ours). */
    fun contains(x: Float, y: Float) = card.contains(x, y)

    private fun keyAt(x: Float, y: Float): Key? {
        val all = rows.flatten().filter { it.kind != Kind.SPACER }
        // inside a key, else the nearest one (the gaps between keys still type)
        all.firstOrNull { it.rect.contains(x, y) }?.let { return it }
        if (y < (rows.firstOrNull()?.firstOrNull()?.rect?.top ?: 0f) - host.dp(4f)) return null
        return all.minByOrNull { d2(it.rect, x, y) }?.takeIf { d2(it.rect, x, y) < host.dp(14f) * host.dp(14f) }
    }

    private fun d2(r: RectF, x: Float, y: Float): Float {
        val dx = max(0f, max(r.left - x, x - r.right))
        val dy = max(0f, max(r.top - y, y - r.bottom))
        return dx * dx + dy * dy
    }

    fun down(id: Int, x: Float, y: Float) {
        // the handle being pulled has the panel to itself
        if (touches.values.any { it.onHandle }) return
        // rolling thumbs: the first key goes in now, as the next comes down
        for (t in touches.values) if (!t.done && !t.swiping && t.choices == null && t.key?.kind == Kind.CHAR) { host.cancel(t.hold); commit(t.key!!); t.done = true }
        val t = Touch(id, null, x, y)
        touches[id] = t
        when {
            handle.contains(x, y) -> {
                t.onHandle = true
                host.cancel(settle)
                // a light tick: the handle's in hand
                host.haptic(if (android.os.Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.GESTURE_START else HapticFeedbackConstants.CLOCK_TICK)
            }
            strip.contains(x, y) -> t.slot = slots.indexOfFirst { it.contains(x, y) }
            else -> {
                val k = keyAt(x, y) ?: return
                t.key = k
                host.haptic(HapticFeedbackConstants.KEYBOARD_TAP)
                when (k.kind) {
                    Kind.CHAR -> if (choicesFor(k).isNotEmpty()) host.post(t.hold, HOLD_MS)
                    Kind.BACK -> { host.key(Bar.Key.DELETE, false); t.done = true; host.post(t.repeat, REPEAT_START_MS) }
                    Kind.MENU -> host.post(t.hold, HOLD_MS)
                    else -> Unit
                }
            }
        }
        host.invalidate()
    }

    fun move(id: Int, x: Float, y: Float, time: Long = android.os.SystemClock.uptimeMillis()) {
        val t = touches[id] ?: return
        if (t.onHandle) { pullTo(t, y, time); return }
        val k = t.key ?: return
        if (k.kind == Kind.CHAR) t.path += Triple(x, y, time)
        // a letter's finger sliding past its neighbor (rolling onto the next key is still a tap on it),
        // alone on the letters: a swipe
        if (!t.swiping && swipeEnabled && k.kind == Kind.CHAR && page == Page.LETTERS && !t.done && t.choices == null && touches.size == 1 &&
            k.label[0] in 'a'..'z' && kotlin.math.hypot(x - t.x0, y - t.y0) > k.rect.width() * SWIPE_START_KEYS) {
            t.swiping = true
            host.cancel(t.hold)
            host.invalidate()
        }
        if (t.swiping) { host.invalidate(); return }
        t.choices?.let {
            val hot = t.choiceRects.indexOfFirst { r -> x >= r.left && x < r.right }
                .let { i -> if (i >= 0) i else if (x < t.choiceRects.first().left) 0 else t.choiceRects.lastIndex }
            if (hot != t.hot) { t.hot = hot; host.haptic(HapticFeedbackConstants.CLOCK_TICK); host.invalidate() }
            return
        }
        if (k.kind == Kind.SPACE) {
            if (!t.sliding && abs(x - t.x0) > host.dp(SLIDE_START_DP)) { t.sliding = true; t.slideX = t.x0 }
            if (t.sliding) {
                val step = host.dp(SLIDE_STEP_DP)
                while (x - t.slideX >= step) { t.slideX += step; host.key(Bar.Key.RIGHT, true) }
                while (t.slideX - x >= step) { t.slideX -= step; host.key(Bar.Key.LEFT, true) }
            }
            return
        }
        // a letter slid onto its neighbor: that one goes in instead
        if (k.kind == Kind.CHAR && !t.done) {
            val now = keyAt(x, y)
            if (now != null && now !== k && now.kind == Kind.CHAR) {
                host.cancel(t.hold)
                t.key = now
                if (choicesFor(now).isNotEmpty()) host.post(t.hold, HOLD_MS)
                host.invalidate()
            }
        }
    }

    fun up(id: Int, x: Float, y: Float, time: Long = android.os.SystemClock.uptimeMillis()) {
        val t = touches.remove(id) ?: return
        host.cancel(t.hold)
        host.cancel(t.repeat)
        when {
            t.swiping -> { t.path += Triple(x, y, time); sendSwipe(t) }
            t.onHandle -> {
                pull = max(0f, y - t.y0)
                val flicked = pull > host.dp(PULL_FLICK_MIN_DP) && t.speed > host.dp(PULL_FLICK_DP_PER_MS)
                if (pull > host.dp(PULL_CLOSE_DP) || flicked) host.close() else host.post(settle, 16L)
            }
            t.slot >= 0 -> suggestions.getOrNull(slotWord(t.slot))?.let { if (slots[t.slot].contains(x, y)) host.suggest(it) }
            t.choices != null -> t.choices?.getOrNull(t.hot)?.let { typeChar(it) }
            t.done || t.sliding -> Unit
            else -> t.key?.let { commit(it) }
        }
        host.invalidate()
    }

    fun cancel() {
        for (t in touches.values) { host.cancel(t.hold); host.cancel(t.repeat) }
        touches.clear()
        host.cancel(settle)
        pull = 0f
        host.invalidate()
    }

    /** The handle pulled to [y]: the panel follows the finger down (not up past where it sits). */
    private fun pullTo(t: Touch, y: Float, time: Long) {
        if (time > t.lastAt) { t.speed = (y - t.lastY) / (time - t.lastAt); t.lastY = y; t.lastAt = time }
        pull = max(0f, y - t.y0)
        host.invalidate()
    }

    /** The letter rows' frame (the swipe models' [0, 1] square), and a swipe sent in it. */
    private fun sendSwipe(t: Touch) {
        val letters = rows.take(3).flatten().filter { it.kind == Kind.CHAR && it.label[0] in 'a'..'z' }
        if (letters.size != 26) return
        val l = letters.minOf { it.rect.left }; val r = letters.maxOf { it.rect.right }
        val top = letters.minOf { it.rect.top }; val bottom = letters.maxOf { it.rect.bottom }
        val w = max(1f, r - l); val h = max(1f, bottom - top)
        val keys = FloatArray(52)
        for (k in letters) { val i = k.label[0] - 'a'; keys[2 * i] = (k.rect.centerX() - l) / w; keys[2 * i + 1] = (k.rect.centerY() - top) / h }
        val pts = t.path.map { (x, y, ms) -> com.ryderdavid.voicebar.swipe.SwipeDecoder.Point((x - l) / w, (y - top) / h, ms) }
        val caps = when { shift == Shift.LOCK -> 2; upper -> 1; else -> 0 }
        if (shift == Shift.ONCE) shift = Shift.OFF
        host.swipe(pts, keys, caps)
    }

    private fun held(t: Touch) {
        val k = t.key ?: return
        if (t.done || touches[t.id] !== t) return
        // ⋮ held: Gboard
        if (k.kind == Kind.MENU) { t.done = true; host.haptic(HapticFeedbackConstants.LONG_PRESS); host.gboard(); return }
        val choices = choicesFor(k)
        if (choices.isEmpty()) return
        t.choices = choices
        t.hot = 0
        host.haptic(HapticFeedbackConstants.LONG_PRESS)
        // a row of choices above the key, kept on the panel
        val w = host.dp(CHOICE_W_DP)
        val h = k.rect.height()
        val total = w * choices.size
        var left = k.rect.centerX() - w / 2
        if (left + total > card.right - host.dp(6f)) left = card.right - host.dp(6f) - total
        left = max(card.left + host.dp(6f), left)
        val top = k.rect.top - host.dp(6f) - h
        t.choiceRects.clear()
        for (i in choices.indices) t.choiceRects += RectF(left + i * w, top, left + (i + 1) * w, top + h)
        host.invalidate()
    }

    private fun choicesFor(k: Key): List<String> {
        if (k.kind != Kind.CHAR) return emptyList()
        val base = k.label.lowercase()
        val s = k.sub + (ACCENTS[base] ?: "")
        return (if (upper) s.uppercase() else s).map { it.toString() }
    }

    private fun commit(k: Key) {
        when (k.kind) {
            Kind.CHAR -> typeChar(if (upper) k.label.uppercase() else k.label)
            Kind.SPACE -> host.type(" ")
            Kind.ENTER -> host.key(Bar.Key.ENTER, false)
            Kind.PAGE -> { page = k.to ?: Page.LETTERS; build() }
            Kind.SHIFT -> tapShift()
            Kind.MENU -> host.menu(android.graphics.RectF(k.rect))
            Kind.BACK, Kind.SPACER -> Unit
        }
    }

    private fun typeChar(s: String) {
        host.type(s)
        if (shift == Shift.ONCE) shift = Shift.OFF
    }

    private fun tapShift() {
        val now = android.os.SystemClock.uptimeMillis()
        val wasUpper = upper
        shift = when {
            now - lastShiftTap < DOUBLE_TAP_MS -> Shift.LOCK
            shift == Shift.LOCK || shift == Shift.ONCE -> Shift.OFF
            wasUpper -> { autoOff = true; Shift.OFF } // the field's own capital, turned off
            else -> Shift.ONCE
        }
        lastShiftTap = now
    }

    /** The suggestion in strip slot [i]: the best in the middle, the next two either side. */
    private fun slotWord(i: Int) = when (i) { 0 -> 1; 1 -> 0; else -> 2 }

    // ---------------------------------------------------------------- drawing

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT }
    private val path = Path()

    private fun alpha(c: Int, a: Float) = ColorUtils.setAlphaComponent(c, (Color.alpha(c) * a.coerceIn(0f, 1f)).toInt())

    /** The panel behind the keys: a step off the bar's surface either way, so it reads as a card. */
    fun cardColor(pal: Palette) = if (pal.dark) ColorUtils.blendARGB(pal.disc, Color.WHITE, 0.05f) else pal.cardinal

    fun draw(canvas: Canvas, pal: Palette, al: Float) {
        if (card.isEmpty) return
        // the handle: pull it down to close
        fill.color = alpha(pal.onCardinal, al * 0.45f)
        val hw = host.dp(18f)
        canvas.drawRoundRect(card.centerX() - hw, handle.centerY() - host.dp(2f), card.centerX() + hw, handle.centerY() + host.dp(2f), host.dp(2f), host.dp(2f), fill)
        drawStrip(canvas, pal, al)
        val pressedKeys = touches.values.filter { !it.done && it.choices == null && !it.swiping }.mapNotNull { it.key }.toSet()
        for (row in rows) for (k in row) if (k.kind != Kind.SPACER) drawKey(canvas, pal, al, k, k in pressedKeys)
        // what's under each finger, larger, above it (letters only)
        for (t in touches.values) {
            val k = t.key ?: continue
            if (t.swiping) { drawTrail(canvas, pal, al, t); continue }
            if (t.choices != null) drawChoices(canvas, pal, al, t)
            else if (!t.done && k.kind == Kind.CHAR) drawPreview(canvas, pal, al, k)
        }
    }

    private fun drawStrip(canvas: Canvas, pal: Palette, al: Float) {
        if (suggestions.isEmpty()) return
        label.textSize = host.dp(16f)
        for (i in 0..2) {
            val word = suggestions.getOrNull(slotWord(i)) ?: continue
            val r = slots[i]
            val best = i == 1
            label.typeface = if (best) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            label.color = alpha(if (best) pal.onBloomKey else pal.onCardinal, al)
            var shown = word
            while (shown.length > 1 && label.measureText(shown) > r.width() - host.dp(12f)) shown = shown.dropLast(1)
            if (shown != word) shown = shown.dropLast(1) + "…"
            canvas.drawText(shown, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
            if (i > 0) {
                fill.color = alpha(pal.outline, al)
                canvas.drawRect(r.left, r.top + host.dp(10f), r.left + max(1f, host.dp(1f)), r.bottom - host.dp(10f), fill)
            }
        }
        label.typeface = Typeface.DEFAULT
    }

    private fun drawKey(canvas: Canvas, pal: Palette, al: Float, k: Key, down: Boolean) {
        val r = k.rect
        val fn = k.kind != Kind.CHAR && k.kind != Kind.SPACE
        var bg = when {
            k.kind == Kind.ENTER -> pal.pressed
            fn -> pal.diagonal
            else -> pal.bloomKey
        }
        val fg = when {
            k.kind == Kind.ENTER -> pal.onPressed
            fn -> pal.onDiagonal
            else -> pal.onBloomKey
        }
        if (down) bg = ColorUtils.blendARGB(bg, fg, 0.14f)
        // the bottom corners (?123 or ABC, and ↵) are the bar's own pills, rounding with the card's
        // corners; the rest are traditional keys
        val corner = k.kind == Kind.ENTER || k === rows.lastOrNull()?.firstOrNull()
        val rad = if (corner) r.height() / 2 else host.dp(KEY_RADIUS_DP)
        // a hairline of shade under each key
        fill.color = alpha(Color.BLACK, al * (if (pal.dark) 0.30f else 0.08f))
        canvas.drawRoundRect(r.left, r.top + host.dp(1f), r.right, r.bottom + host.dp(1f), rad, rad, fill)
        fill.color = alpha(bg, al)
        canvas.drawRoundRect(r, rad, rad, fill)
        val c = alpha(fg, al)
        when (k.kind) {
            Kind.CHAR -> {
                val s = if (upper) k.label.uppercase() else k.label
                label.textSize = host.dp(if (s.length > 1) 15f else 22f)
                label.color = c
                canvas.drawText(s, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
                if (k.sub.isNotEmpty()) {
                    label.textSize = host.dp(10f)
                    label.color = alpha(fg, al * 0.6f)
                    canvas.drawText(k.sub, r.right - host.dp(7f), r.top + host.dp(12f), label)
                }
            }
            Kind.PAGE -> {
                label.textSize = host.dp(15f)
                label.color = c
                label.typeface = Typeface.DEFAULT_BOLD
                canvas.drawText(k.label, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
                label.typeface = Typeface.DEFAULT
            }
            Kind.SPACE -> Unit
            Kind.SHIFT -> drawShift(canvas, r.centerX(), r.centerY(), c)
            Kind.BACK -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.BACKSPACE, r.centerX(), r.centerY(), host.dp(24f), fill) }
            Kind.ENTER -> drawEnter(canvas, r.centerX(), r.centerY(), c)
            Kind.MENU -> { fill.color = c; for (i in -1..1) canvas.drawCircle(r.centerX(), r.centerY() + i * host.dp(6f), host.dp(2f), fill) }
            Kind.SPACER -> Unit
        }
    }

    /** The swipe's trail: the last stretch of the path, fading and thinning toward its start. */
    private fun drawTrail(canvas: Canvas, pal: Palette, al: Float, t: Touch) {
        val pts = t.path.takeLast(TRAIL_POINTS)
        if (pts.size < 2) return
        // solid colors blended toward the card, not transparency: overlapping joints would show as dots
        val card = cardColor(pal)
        for (i in 1 until pts.size) {
            val f = i.toFloat() / (pts.size - 1)
            stroke.strokeWidth = host.dp(2f + 5f * f)
            stroke.color = alpha(ColorUtils.blendARGB(card, pal.pressed, 0.2f + 0.65f * f), al)
            canvas.drawLine(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second, stroke)
        }
    }

    private fun drawShift(canvas: Canvas, cx: Float, cy: Float, c: Int) {
        val s = host.dp(1f)
        path.reset()
        path.moveTo(cx, cy - 8 * s); path.lineTo(cx - 8 * s, cy + 0.5f * s); path.lineTo(cx - 3.5f * s, cy + 0.5f * s)
        path.lineTo(cx - 3.5f * s, cy + 7 * s); path.lineTo(cx + 3.5f * s, cy + 7 * s); path.lineTo(cx + 3.5f * s, cy + 0.5f * s)
        path.lineTo(cx + 8 * s, cy + 0.5f * s); path.close()
        if (upper) { fill.color = c; canvas.drawPath(path, fill) }
        stroke.color = c
        stroke.strokeWidth = 1.8f * s
        canvas.drawPath(path, stroke)
        if (shift == Shift.LOCK) { stroke.strokeWidth = 2f * s; canvas.drawLine(cx - 4.5f * s, cy + 10.5f * s, cx + 4.5f * s, cy + 10.5f * s, stroke) }
    }

    private fun drawEnter(canvas: Canvas, cx: Float, cy: Float, c: Int) {
        val s = host.dp(1f)
        stroke.color = c
        stroke.strokeWidth = 2f * s
        path.reset()
        when (enterAction) {
            EditorInfo.IME_ACTION_SEND -> { fill.color = c; Icons.draw(canvas, Icons.Glyph.SEND, cx, cy, 22 * s, fill); return }
            EditorInfo.IME_ACTION_SEARCH -> {
                canvas.drawCircle(cx - 1.5f * s, cy - 1.5f * s, 5.5f * s, stroke)
                canvas.drawLine(cx + 2.5f * s, cy + 2.5f * s, cx + 7f * s, cy + 7f * s, stroke)
                return
            }
            EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_NEXT -> {
                path.moveTo(cx - 7 * s, cy); path.lineTo(cx + 7 * s, cy); path.moveTo(cx + 2 * s, cy - 5 * s); path.lineTo(cx + 7 * s, cy); path.lineTo(cx + 2 * s, cy + 5 * s)
            }
            EditorInfo.IME_ACTION_DONE -> { path.moveTo(cx - 7 * s, cy); path.lineTo(cx - 2 * s, cy + 5 * s); path.lineTo(cx + 7 * s, cy - 5 * s) }
            else -> { path.moveTo(cx + 7 * s, cy - 7 * s); path.lineTo(cx + 7 * s, cy + 1 * s); path.lineTo(cx - 6 * s, cy + 1 * s); path.moveTo(cx - 2 * s, cy - 3 * s); path.lineTo(cx - 6 * s, cy + 1 * s); path.lineTo(cx - 2 * s, cy + 5 * s) }
        }
        canvas.drawPath(path, stroke)
    }

    private fun shadow(canvas: Canvas, r: RectF, rad: Float, al: Float) {
        for (i in 1..4) {
            fill.color = Color.argb((14 * al).toInt(), 0, 0, 0)
            val o = host.dp(i.toFloat())
            canvas.drawRoundRect(r.left - o, r.top - o + host.dp(2f), r.right + o, r.bottom + o + host.dp(2f), rad + o, rad + o, fill)
        }
    }

    private fun drawPreview(canvas: Canvas, pal: Palette, al: Float, k: Key) {
        val r = RectF(k.rect.left - host.dp(6f), k.rect.top - host.dp(6f) - host.dp(PREVIEW_DP), k.rect.right + host.dp(6f), k.rect.top - host.dp(6f))
        if (r.left < card.left) r.offset(card.left - r.left, 0f)
        if (r.right > card.right) r.offset(card.right - r.right, 0f)
        val rad = host.dp(14f)
        shadow(canvas, r, rad, al)
        fill.color = alpha(pal.bloomKey, al)
        canvas.drawRoundRect(r, rad, rad, fill)
        label.textSize = host.dp(30f)
        label.color = alpha(pal.onBloomKey, al)
        val s = if (upper) k.label.uppercase() else k.label
        canvas.drawText(s, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
    }

    private fun drawChoices(canvas: Canvas, pal: Palette, al: Float, t: Touch) {
        val first = t.choiceRects.firstOrNull() ?: return
        val box = RectF(first.left, first.top, t.choiceRects.last().right, first.bottom).apply { inset(-host.dp(5f), -host.dp(5f)) }
        val rad = host.dp(16f)
        shadow(canvas, box, rad, al)
        fill.color = alpha(pal.bloomKey, al)
        canvas.drawRoundRect(box, rad, rad, fill)
        label.textSize = host.dp(22f)
        for ((i, ch) in (t.choices ?: return).withIndex()) {
            val r = t.choiceRects[i]
            if (i == t.hot) {
                fill.color = alpha(pal.pressed, al)
                canvas.drawRoundRect(r, host.dp(10f), host.dp(10f), fill)
            }
            label.color = alpha(if (i == t.hot) pal.onPressed else pal.onBloomKey, al)
            canvas.drawText(ch, r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
        }
    }

    @androidx.annotation.VisibleForTesting internal fun testKey(label: String): RectF? =
        rows.flatten().firstOrNull { it.label == label && it.kind != Kind.SPACER }?.rect
    @androidx.annotation.VisibleForTesting internal fun testKind(kind: Kind): RectF? = rows.flatten().firstOrNull { it.kind == kind }?.rect
    @androidx.annotation.VisibleForTesting internal fun testSlot(i: Int): RectF = slots[i]
    @androidx.annotation.VisibleForTesting internal val testHandle get() = RectF(handle)
    @androidx.annotation.VisibleForTesting internal fun testChoices(id: Int): List<RectF>? = touches[id]?.choiceRects?.takeIf { touches[id]?.choices != null }

    companion object {
        const val HANDLE_DP = 18f
        /** The handle pulled this far down and let go closes the keyboard... */
        const val PULL_CLOSE_DP = 56f
        /** ...or a flick down: this far, at least this fast. */
        const val PULL_FLICK_MIN_DP = 16f
        const val PULL_FLICK_DP_PER_MS = 0.5f
        const val STRIP_DP = 40f
        const val KEY_GAP_DP = 5f
        const val ROW_GAP_DP = 8f
        const val KEY_RADIUS_DP = 12f
        const val PREVIEW_DP = 56f
        const val CHOICE_W_DP = 36f
        const val HOLD_MS = 380L
        const val REPEAT_START_MS = 400L
        const val REPEAT_MS = 50L
        const val DOUBLE_TAP_MS = 300L
        const val SLIDE_START_DP = 12f
        const val SLIDE_STEP_DP = 9f
        /** A finger this many key widths from where it came down, on the letters, is swiping. */
        const val SWIPE_START_KEYS = 1.4f
        /** How much of a swipe's path its trail shows. */
        const val TRAIL_POINTS = 40

        /** Held keys: the number on the top row's keys first, then the accents. */
        val ACCENTS = mapOf(
            "a" to "àáâäæãåā", "c" to "çćč", "e" to "èéêëēęė", "i" to "îïíīįì", "n" to "ñń", "o" to "ôöòóœøōõ",
            "s" to "ßśš", "u" to "ûüùúū", "y" to "ÿý", "z" to "žźż", "l" to "ł", "d" to "ð", "g" to "ğ",
            "." to "?!,'\"-:;", "," to ";:'\"", "-" to "–—_", "?" to "¿", "!" to "¡", "$" to "€£¥¢", "'" to "‘’", "\"" to "“”«»",
        )
    }
}
