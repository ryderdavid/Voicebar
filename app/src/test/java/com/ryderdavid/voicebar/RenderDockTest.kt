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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test

/** Not a check: renders the docked bar's states under a fake app into app/build/previews/dock/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h860dp-port-xxhdpi")
class RenderDockTest {
    @Test fun light() = renderAll("light")

    @Config(qualifiers = "+night")
    @Test fun dark() = renderAll("dark")

    private fun renderAll(mode: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val dock = DockView(activity, null)
        var down = 0L
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
            override fun shouldCapitalize() = false
            override fun onTalkStart() = true
            override fun onFixStart() = true
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = activity.resources.displayMetrics.density
        dock.testSetBottomInset((24 * d).toInt())
        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun relayout() {
            idle(300)
            dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight)
            dock.settle()
        }
        fun touch(action: Int, p: PointF) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
            dock.dispatchTouchEvent(e); e.recycle()
        }
        fun tap(a: DockView.Act) { val r = dock.testItem(a)!!; val p = PointF(r.centerX(), r.centerY()); touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(30) }
        val out = File("build/previews/dock").apply { mkdirs() }
        val pal = Palette(activity)
        val night = mode == "dark"
        fun shotImpl(name: String, lay: Boolean) {
            if (lay) relayout()
            val w = dock.width
            val appH = (300 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; color = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt() }
            val bubble = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt() }
            c.drawRoundRect(16 * d, 130 * d, 280 * d, 180 * d, 20 * d, 20 * d, bubble)
            c.drawText("Still on for the trailhead at 6:40?", 30 * d, 160 * d, p)
            c.drawRoundRect(16 * d, appH - 60 * d, w - 16 * d, appH - 12 * d, 24 * d, 24 * d, bubble)
            c.drawText("Message", 34 * d, appH - 30 * d, p.apply { alpha = 150 })
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c)
            // the gesture pill the system draws over our surface
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = pal.onBloomKey; alpha = 140 }
            val h = dock.height.toFloat()
            c.drawRoundRect(w / 2f - 54 * d, h - 14 * d, w / 2f + 54 * d, h - 10 * d, 2 * d, 2 * d, pill)
            c.restore()
            File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun shotRaw(name: String) = shotImpl(name, false)
        fun shot(name: String) = shotImpl(name, true)
        fun hold(a: DockView.Act) { val r = dock.testItem(a)!!; touch(MotionEvent.ACTION_DOWN, PointF(r.centerX(), r.centerY())); idle(400) }
        fun release(a: DockView.Act) { val r = dock.testItem(a)!!; touch(MotionEvent.ACTION_UP, PointF(r.centerX(), r.centerY())); idle(30) }
        dock.settle()
        shot("01-idle")
        // the morph, frame by frame: Voice stretching out as the rest shrink away
        tap(DockView.Act.MIC)
        dock.showListening()
        repeat(16) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f)[i % 6]) }
        for (f in 1..3) { dock.testFrame(); shotRaw("02-morph-$f"); idle(40); dock.testFrame() }
        dock.settle()
        // the recognizer not connected yet: the pill says so until it is
        shot("03a-connecting")
        dock.showReady()
        shot("03-listening")
        repeat(80) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.6f)[i % 7]) }
        idle(7_000)
        dock.settle()
        shot("04-draining")
        dock.heardSpeech()
        dock.voiceInField = false
        dock.showLiveText("Running ten minutes late, start without me")
        dock.settle()
        shot("05-listening-text")
        // "title case" said: the badge on the recording pill
        dock.showTitleCase(true)
        shot("05b-title-case")
        dock.showTitleCase(false)
        tap(DockView.Act.MIC)
        dock.showCleaning("raw")
        dock.talkEnded()
        dock.settle()
        shot("06-polishing")
        dock.hideVoice()
        dock.settle()
        // once there's been a dictation, an own key set to "Fix last dictation" fixes it
        dock.dictations = listOf(DictationHistory.Entry(1, "so give it one extra broad look", "So give it one extra Broad look."))
        dock.testStartFix()
        // an own key set to "Fix last dictation": listening for what to fix (never typed)
        dock.showListening()
        repeat(40) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f, 0.6f)[i % 7]) }
        shot("06d-fix-listening")
        dock.showLiveText("broad should be lowercase")
        shot("06e-fix-heard")
        tap(DockView.Act.MIC)
        dock.showCleaning("So give it one extra Broad look.")
        dock.talkEnded()
        shot("06f-fixing")
        dock.hideVoice()
        dock.settle()
        // ⌫ held: its choices; let go, and a tap elsewhere closes them
        hold(DockView.Act.BACKSPACE)
        dock.settle()
        shot("07-hold-backspace")
        run {
            val b = dock.testItem(DockView.Act.BACKSPACE)!!
            touch(MotionEvent.ACTION_UP, PointF(b.centerX(), b.centerY())); idle(30)
            touch(MotionEvent.ACTION_DOWN, PointF(4f, 4f)); touch(MotionEvent.ACTION_UP, PointF(4f, 4f)); idle(30)
        }
        dock.canPaste = true
        tap(DockView.Act.CURSOR)
        dock.settle()
        shot("10-trackpad")
        run {
            val pad = dock.testItem(DockView.Act.PAD)!!
            touch(MotionEvent.ACTION_DOWN, PointF(pad.left + dock.resources.displayMetrics.density * 28f, pad.centerY()))
            shot("10b-trackpad-tap-left")
            touch(MotionEvent.ACTION_UP, PointF(pad.left + dock.resources.displayMetrics.density * 28f, pad.centerY())); idle(30)
        }
        tap(DockView.Act.CURSOR)
        dock.settle()
        dock.canPaste = true
        dock.clipPreview = "Meet at the north entrance of the station at noon"
        tap(DockView.Act.MORE)
        dock.settle()
        shot("11-menu")
        run {
            dock.dictations = listOf(
                DictationHistory.Entry(3, "why is create the only option for or sorry why is podcasts the only option for create", "Why is Podcasts the only option for Create?"),
                DictationHistory.Entry(2, "um tell shauna I'll bring the tent", "Tell Siona I'll bring the tent."),
                DictationHistory.Entry(1, "remind me to pick up milk and eggs", "Remind me to pick up milk and eggs."),
            )
            val item = dock.testMenuAt(DockView.MenuItem.DICTATIONS)!!
            touch(MotionEvent.ACTION_DOWN, item); touch(MotionEvent.ACTION_UP, item); idle(130)
            shot("11c-menu-morphing")
            idle(300)
            dock.settle()
            shot("11d-menu-dictations")
            val row = dock.testMenuAt(DockView.DictRow(2))!!
            touch(MotionEvent.ACTION_DOWN, row); idle(600); touch(MotionEvent.ACTION_UP, row); idle(30)
            dock.settle()
            shot("11g-menu-dictation-held")
            touch(MotionEvent.ACTION_DOWN, row); touch(MotionEvent.ACTION_UP, row); idle(30)
            dock.recleaning = 2
            idle(1400 + 600 - (android.os.SystemClock.uptimeMillis() % 1400))
            shot("11e-menu-dictation-cleaning-again")
            dock.recleaning = null
            dock.showRecleaned(2)
            shot("11f-menu-dictation-copied")
        }
        dock.reset()
        dock.settle()
        dock.showMessage("Add your ElevenLabs key in settings")
        dock.settle()
        shot("12-message")
        // Voicebar's keyboard: letters with suggestions and a key held down, a held letter's accents, ?123, a capital due
        dock.hideVoice()
        dock.settle()
        tap(DockView.Act.KEYBOARD)
        dock.settle()
        dock.keySuggestions = listOf("running", "run", "runs")
        dock.enterAction = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
        fun keyAt(label: String) = dock.testKeys.testKey(label)!!.let { PointF(it.centerX(), it.centerY() + dock.testBarTop) }
        touch(MotionEvent.ACTION_DOWN, keyAt("n")); idle(60)
        shotRaw("13-keys")
        touch(MotionEvent.ACTION_UP, keyAt("n")); idle(30)
        touch(MotionEvent.ACTION_DOWN, keyAt("e")); idle(450)
        shotRaw("13b-keys-accents")
        touch(MotionEvent.ACTION_UP, keyAt("e")); idle(30)
        touch(MotionEvent.ACTION_DOWN, keyAt("?123")); touch(MotionEvent.ACTION_UP, keyAt("?123")); idle(30)
        dock.keySuggestions = emptyList()
        shotRaw("13c-keys-numbers")
        touch(MotionEvent.ACTION_DOWN, keyAt("ABC")); touch(MotionEvent.ACTION_UP, keyAt("ABC")); idle(30)
        dock.keyAutoCap = true
        dock.enterAction = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        shotRaw("13d-keys-capital")
        // a swipe through "hello", caught mid-word: the trail behind the finger, no key previews
        dock.keyAutoCap = false
        run {
            val path = "hello".map { keyAt(it.toString()) }
            touch(MotionEvent.ACTION_DOWN, path[0])
            for ((a, b) in path.zipWithNext().take(3)) for (i in 1..8) {
                touch(MotionEvent.ACTION_MOVE, PointF(a.x + (b.x - a.x) * i / 8, a.y + (b.y - a.y) * i / 8)); idle(8)
            }
            shotRaw("13e-keys-swiping")
            touch(MotionEvent.ACTION_UP, path.last()); idle(30)
        }
    }
}
