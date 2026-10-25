package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test

/**
 * Films the real bar for the product video: each scene in video/src/timeline.json is played out
 * against a live DockView, frame by frame at 30 fps, keyed to the narration's words. Writes a
 * transparent PNG per frame (the bar, its panels and pickers, bottom-aligned on the phone screen)
 * to video/public/bar/<scene>/, and meta.json with what the composition needs around it: where the
 * app's content ends (the text box sits on it), the text box's contents, fingertips and sent
 * messages. Run with -Prender (and -Pscene=<id>,<id> for some scenes); the video's README has the steps.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderVideoTest {
    class Word(val w: String, val start: Double)
    class Scene(val id: String, val lead: Double, val frames: Int, val words: List<Word>)

    /** 412 dp wide at 365 dpi: 940 px, the phone screen's width in the video. */
    @Config(qualifiers = "w412dp-h915dp-port-notnight-365dpi")
    @Test fun frames() {
        val timeline = JSONArray(File("../video/src/timeline.json").readText())
        val only = System.getProperty("scene")?.ifBlank { null }?.split(",")
        for (i in 0 until timeline.length()) {
            val o = timeline.getJSONObject(i)
            if (only != null && o.getString("id") !in only) continue
            val ws = o.getJSONArray("words")
            val words = (0 until ws.length()).map { ws.getJSONObject(it).let { w -> Word(w.getString("w"), w.getDouble("start")) } }
            Shoot(Scene(o.getString("id"), o.getDouble("lead"), o.getInt("frames"), words)).run()
        }
    }

    private class Finger(val at: Int, val hold: Int, val path: MutableList<Triple<Int, Float, Float>>)
    private class Field(val text: String, val draft: Boolean)

    private inner class Shoot(val s: Scene) {
        val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val d = activity.resources.displayMetrics.density
        val w = activity.resources.displayMetrics.widthPixels
        val h = (583 * d).toInt() // the phone screen in the video: 940 × 1330 px
        val dock = DockView(activity, null)
        var f = 0
        var field = Field("", false)
        val fields = ArrayList<Field>()
        val insets = ArrayList<Int>()
        val fingers = ArrayList<Finger>()
        val sent = ArrayList<Pair<Int, String>>()
        val todo = HashMap<Int, MutableList<() -> Unit>>()
        var talkingLevels = false
        var down = 0L
        val out = File("../video/public/bar/${s.id}").apply { deleteRecursively(); mkdirs() }

        /** The frame the narration reaches the first word starting with [word] (after word index [after]). */
        fun cue(word: String, after: Int = 0): Int {
            val i = s.words.withIndex().indexOfFirst { (k, x) -> k >= after && x.w.lowercase().replace(Regex("[^a-z']"), "").startsWith(word.lowercase()) }
            return ((s.lead + (if (i >= 0) s.words[i].start else 0.0)) * 30).toInt()
        }

        fun at(frame: Int, action: () -> Unit) { todo.getOrPut(frame) { ArrayList() } += action }

        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

        /** View coordinates to the frame's (the dock sits at the frame's bottom). */
        fun toFrame(p: PointF) = PointF(p.x, p.y + (h - dock.height))

        fun touch(action: Int, p: PointF) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
            dock.dispatchTouchEvent(e); e.recycle()
        }

        fun item(act: DockView.Act) = dock.testItem(act)!!.let { PointF(it.centerX(), it.centerY()) }
        fun key(label: String) = dock.testKeys.testKey(label)!!.let { PointF(it.centerX(), it.centerY() + dock.testBarTop) }

        /** A visible tap at [frame] on what [where] says then. */
        fun tap(frame: Int, show: Boolean = true, where: () -> PointF) {
            at(frame) {
                val p = where()
                if (show) fingers += Finger(frame, 4, mutableListOf(Triple(frame, toFrame(p).x, toFrame(p).y)))
                touch(MotionEvent.ACTION_DOWN, p)
            }
            at(frame + 2) { touch(MotionEvent.ACTION_UP, where()) }
        }

        /** A press at [frame] on [from], moving through [points] (each at its frame), lifted at [upAt]. */
        fun gesture(frame: Int, from: () -> PointF, points: List<Pair<Int, () -> PointF>>, upAt: Int) {
            var finger: Finger? = null
            at(frame) {
                val p = from()
                finger = Finger(frame, upAt - frame, mutableListOf(Triple(frame, toFrame(p).x, toFrame(p).y))).also { fingers += it }
                touch(MotionEvent.ACTION_DOWN, p)
            }
            for ((fr, pt) in points) at(fr) { val p = pt(); finger?.path?.add(Triple(fr, toFrame(p).x, toFrame(p).y)); touch(MotionEvent.ACTION_MOVE, p) }
            at(upAt) { touch(MotionEvent.ACTION_UP, points.lastOrNull()?.second?.invoke() ?: from()) }
        }

        /**
         * A press at [frame] on [from] that glides to each waypoint by its frame, moving every frame in
         * between (so what's under the finger always matches where it's drawn), lifted at [upAt].
         */
        fun glide(frame: Int, from: () -> PointF, waypoints: List<Pair<Int, () -> PointF>>, upAt: Int) {
            val steps = ArrayList<Pair<Int, () -> PointF>>()
            var prevFrame = frame
            var prev: () -> PointF = from
            for ((wf, wp) in waypoints) {
                val a = prev; val f0 = prevFrame; val n = (wf - f0).coerceAtLeast(1)
                for (k in 1..n) steps += f0 + k to { val p = a(); val q = wp(); val t = k / n.toFloat(); PointF(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t) }
                prev = wp; prevFrame = f0 + n
            }
            gesture(frame, from, steps, upAt.coerceAtLeast(prevFrame + 1))
        }

        /** A straight drag from [from] by ([dx], [dy]) px over [frames] frames. */
        fun drag(frame: Int, frames: Int, from: () -> PointF, dx: Float, dy: Float) {
            val start = lazy { from() }
            gesture(frame, { start.value }, (1..frames).map { i -> frame + i to { val t = i / frames.toFloat(); PointF(start.value.x + dx * t, start.value.y + dy * t) } }, frame + frames + 1)
        }

        /** Listening, as the service shows it: the pill, connected a moment later. */
        fun listening(frame: Int) {
            at(frame) { dock.showListening(); talkingLevels = true }
            at(frame + 5) { dock.showReady() }
        }

        /** Already dictating when the scene opens (no morph, no finger). */
        fun alreadyTalking(text: String) {
            touch(MotionEvent.ACTION_DOWN, item(DockView.Act.MIC)); touch(MotionEvent.ACTION_UP, item(DockView.Act.MIC))
            idle(30); dock.showListening(); dock.showReady(); talkingLevels = true
            relayout(); dock.settle()
            field = Field(text, true)
        }

        /** The service's polish after a dictation ends: the sweep, then the text in for good. */
        fun polish(frame: Int, raw: String, final: String) {
            at(frame) { talkingLevels = false; dock.talkEnded(); dock.showCleaning(raw) }
            at(frame + 18) { dock.hideVoice(); field = Field(final, false) }
        }

        fun relayout() {
            dock.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED)
            if (dock.measuredHeight != dock.height || dock.isLayoutRequested) dock.layout(0, 0, w, dock.measuredHeight)
        }

        fun run() {
            Prefs.get(activity).edit {
                clear()
                putString(Prefs.BAR_KEY, "paste")
                putString(Prefs.BAR_KEY_RIGHT, "backspace")
            }
            dock.listener = object : Bar.Listener {
                override fun onKey(key: Bar.Key, repeat: Boolean) {}
                override fun onSwitchKeyboard() {}
                override fun onTalkStart() = true
                override fun onFixStart() = true
            }
            dock.testSetBottomInset((24 * d).toInt())
            dock.reloadSettings()
            activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            idle(50); relayout(); dock.settle()
            script()
            var clock = 0.0
            val pal = Palette(activity)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.onBloomKey; alpha = 140 }
            for (frame in 0 until s.frames) {
                f = frame
                todo[frame]?.forEach { it() }
                if (talkingLevels && frame % 3 == 0) dock.showLevel((0.3 + 0.55 * abs(sin(frame * 0.41) * sin(frame * 0.13 + 1))).toFloat())
                // words keep coming while it's being talked to: the silence countdown never runs down
                if (talkingLevels && frame % 15 == 0) dock.heardSpeech()
                clock += 1000.0 / 30
                val ms = clock.toLong(); clock -= ms
                idle(ms)
                relayout()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.save(); c.translate(0f, (h - dock.height).toFloat()); dock.draw(c); c.restore()
                // the gesture pill the system draws over the keyboard
                c.drawRoundRect(w / 2f - 54 * d, h - 14 * d, w / 2f + 54 * d, h - 10 * d, 2 * d, 2 * d, pill)
                File(out, "%04d.png".format(frame)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bmp.recycle()
                fields += field
                insets += dock.height - dock.floatRoom()
            }
            val meta = JSONObject()
                .put("frames", s.frames).put("w", w).put("h", h)
                .put("inset", JSONArray(insets))
            // the text box: its distinct states, and which one each frame shows
            val states = fields.distinctBy { it.text to it.draft }
            meta.put("fieldStates", JSONArray(states.map { JSONObject().put("t", it.text).put("d", it.draft) }))
            meta.put("field", JSONArray(fields.map { fl -> states.indexOfFirst { it.text == fl.text && it.draft == fl.draft } }))
            meta.put("fingers", JSONArray(fingers.map { fg ->
                JSONObject().put("at", fg.at).put("hold", fg.hold).put("path", JSONArray(fg.path.map { (fr, x, y) -> JSONArray(listOf(fr, x.toDouble(), y.toDouble())) }))
            }))
            meta.put("sent", JSONArray(sent.map { (fr, t) -> JSONObject().put("at", fr).put("text", t) }))
            File(out, "meta.json").writeText(meta.toString())
        }

        val raw = "so um let's meet at the trailhead at like nine"
        val clean = "So let's meet at the trailhead at nine."

        fun typedRaw(from: Int, to: Int, text: String, prefix: String = "") {
            val ws = text.split(" ")
            for (i in ws.indices) at(from + (to - from) * i / ws.size) { field = Field(prefix + ws.take(i + 1).joinToString(" "), true) }
        }

        fun script() {
            when (s.id) {
                "intro" -> {
                    // a fingertip comes to rest by Voice as the narration reaches "thumb"
                    at(cue("thumb")) { val p = toFrame(item(DockView.Act.MIC)); fingers += Finger(f, 14, mutableListOf(Triple(f, p.x, p.y))) }
                }
                "talk" -> {
                    tap(cue("tap")) { item(DockView.Act.MIC) }
                    listening(cue("tap") + 3)
                    typedRaw(cue("start"), s.frames - 6, raw)
                }
                "tidy" -> {
                    alreadyTalking(raw)
                    at(cue("tidied")) { field = Field(clean, true) }
                }
                "format" -> {
                    alreadyTalking("So let's meet at the trailhead at nine, then watch")
                    val base = field.text
                    at(cue("title")) { dock.showTitleCase(true) }
                    at(cue("lord")) { field = Field("$base the lord", true) }
                    at(cue("rings")) { field = Field("$base the lord of the rings", true) }
                    at(cue("end") + 6) { dock.showTitleCase(false); field = Field("$base The Lord of the Rings", true) }
                }
                "finish" -> {
                    alreadyTalking(clean.dropLast(1))
                    at(cue("tap")) { val p = toFrame(item(DockView.Act.MIC)); fingers += Finger(f, 10, mutableListOf(Triple(f, p.x, p.y))) }
                    drag(cue("swipe"), 12, { item(DockView.Act.MIC) }, dock.testSwipeMark() + 40 * d, 0f)
                    polish(cue("swipe") + 15, clean.dropLast(1), clean)
                    at(cue("read") + 4) { field = Field("", false); sent += f to clean }
                }
                "keyboard" -> {
                    field = Field("See you ", false)
                    tap(cue("abc")) { item(DockView.Act.KEYBOARD) }
                    val t0 = cue("suggestions")
                    "tomo".forEachIndexed { i, ch ->
                        tap(t0 + i * 6) { key(ch.toString()) }
                        at(t0 + i * 6 + 1) {
                            field = Field("See you " + "tomo".take(i + 1), false)
                            // the best word first: it takes the middle of the strip
                            dock.keySuggestions = if (i < 1) listOf("to", "the", "today") else listOf("tomorrow", "tom", "tomorrow's")
                        }
                    }
                    tap(cue("go")) { dock.testKeys.testSlot(1).let { PointF(it.centerX(), it.centerY() + dock.testBarTop) } }
                    at(cue("go") + 1) { field = Field("See you tomorrow ", false); dock.keySuggestions = emptyList() }
                    drag(cue("pull"), 10, { dock.testKeys.testHandle.let { PointF(it.centerX(), it.centerY() + dock.testBarTop) } }, 0f, 150 * d)
                }
                "swipe" -> {
                    field = Field("Sounds ", false)
                    touch(MotionEvent.ACTION_DOWN, item(DockView.Act.KEYBOARD)); touch(MotionEvent.ACTION_UP, item(DockView.Act.KEYBOARD))
                    idle(30); relayout(); dock.settle()
                    val t0 = cue("slide")
                    val letters = "great"
                    gesture(t0, { key("g") }, (1 until letters.length).flatMap { i ->
                        // a curve from letter to letter, a few points each
                        (1..4).map { k -> t0 + (i - 1) * 5 + k to { val a = key(letters[i - 1].toString()); val b = key(letters[i].toString()); val t = k / 4f; PointF(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t - 10 * d * sin(t * Math.PI).toFloat()) } }
                    }, t0 + (letters.length - 1) * 5 + 2)
                    at(t0 + (letters.length - 1) * 5 + 3) { field = Field("Sounds great", false); dock.keySuggestions = listOf("great", "greet", "treat") }
                }
                "pause" -> {
                    alreadyTalking("So let's meet at the trailhead at nine")
                    tap(cue("tap")) { item(DockView.Act.CURSOR) }
                    polish(cue("tap") + 3, field.text, "So let's meet at the trailhead at nine.")
                    at(cue("tap") + 3) { talkingLevels = false }
                    // a fix on the trackpad: nine becomes ten
                    at(cue("fix") + 8) { field = Field("So let's meet at the trailhead at ten.", false) }
                    tap(cue("picks")) { item(DockView.Act.CURSOR) }
                    listening(cue("picks") + 4)
                    at(cue("picks") + 6) { field = Field("So let's meet at the trailhead at ten. ", true) }
                }
                "keys" -> {
                    field = Field("", false)
                    val t0 = cue("hold")
                    val rows = listOf("space" to Bar.ExtraKey.SPACE, "backspace" to Bar.ExtraKey.BACKSPACE, "paste" to Bar.ExtraKey.PASTE,
                        "undo" to Bar.ExtraKey.UNDO, "fix" to Bar.ExtraKey.FIX_LAST)
                    // held until the column opens, then from choice to choice as each is named
                    val open = t0 + 14
                    glide(t0, { item(DockView.Act.SPACE_ALT) },
                        listOf<Pair<Int, () -> PointF>>(open to { item(DockView.Act.SPACE_ALT) }) +
                            rows.map { (word, key) -> maxOf(cue(word), open + 4) to { dock.testPickerRow(key)!! } }, cue("fix") + 12)
                }
                "fix" -> {
                    Prefs.get(activity).edit { putString(Prefs.BAR_KEY, "fix_last") }
                    dock.reloadSettings(); relayout(); dock.settle()
                    dock.dictations = listOf(DictationHistory.Entry(1, "lets give it one extra broad look", "Let's give it one extra Broad look."))
                    field = Field("Let's give it one extra Broad look.", false)
                    tap(cue("fix")) { item(DockView.Act.SPACE_ALT) }
                    listening(cue("fix") + 3)
                    val said = "broad should be lowercase".split(" ")
                    val from = cue("broad"); val to = cue("lowercase") + 6
                    for (i in said.indices) at(from + (to - from) * i / said.size) { dock.showLiveText(said.take(i + 1).joinToString(" ")) }
                    tap(cue("fixed") - 4) { item(DockView.Act.MIC) }
                    polish(cue("fixed") - 1, "Let's give it one extra Broad look.", "Let's give it one extra broad look.")
                }
                "commands" -> {
                    alreadyTalking("See you at nine")
                    at(cue("voice")) { dock.showCommand("", false) }
                    at(cue("send")) { dock.showCommand("send", true) }
                    at(cue("send") + 14) { dock.showCommand(null) }
                    polish(cue("send") + 14, "See you at nine", "See you at nine.")
                    at(cue("send") + 34) { field = Field("", false); sent += f to "See you at nine." }
                }
                "outro" -> {}
            }
        }
    }
}
