package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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

/**
 * Not a check: the bar at rest and while dictating, both layouts, on the cover screen: Cursor and
 * ABC give way to the recording pill and every other key stays put; polishing; ⋮ with Paste; the ⌫
 * tray while dictating; and the morph from rest to recording frame by frame (for a GIF).
 * Writes app/build/previews/layout/<mode>-<layout>-<state>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderRecordLayoutTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun light() = render("light")

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = render("dark")

    private fun render(mode: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear() }
        val dock = DockView(activity, null)
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
            override fun onTalkStart() = true
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = activity.resources.displayMetrics.density
        dock.testSetBottomInset((24 * d).toInt())
        val looper = shadowOf(Looper.getMainLooper())
        var li = 0
        val levels = listOf(0.2f, 0.6f, 0.9f, 0.4f, 0.7f, 0.3f)
        fun idle(ms: Long) { var left = ms; while (left > 0) { val st = minOf(40L, left); looper.idleFor(Duration.ofMillis(st)); dock.showLevel(levels[li++ % levels.size]); left -= st } }
        fun relayout() {
            idle(300); dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
        }
        dock.canPaste = true
        dock.clipPreview = "Meet at the Camelback trailhead, 6:40 sharp — bring headlamps"
        dock.voiceInField = true
        relayout()
        val night = mode == "dark"
        val out = File("build/previews/layout").apply { mkdirs() }
        val short = "seven"
        fun shot(name: String, settle: Boolean = true) {
            if (settle) dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight)
            val w = dock.width
            val appH = (16 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c); c.restore()
            File(out, "$mode-$short-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        var down = 0L
        fun ev(action: Int, x: Float, y: Float) {
            val t = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) down = t
            MotionEvent.obtain(down, t, action, x, y, 0).also { dock.dispatchTouchEvent(it); it.recycle() }
        }
        fun tap(a: DockView.Act) { val r = dock.testItem(a)!!; ev(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()); ev(MotionEvent.ACTION_UP, r.centerX(), r.centerY()) }
        shot("1-rest")
        // the morph, frame by frame
        tap(DockView.Act.MIC)
        dock.showListening(); dock.heardSpeech()
        for (i in 0..14) { looper.idleFor(Duration.ofMillis(40)); dock.showLevel(levels[li++ % levels.size]); shot("morph-%02d".format(i), settle = false) }
        idle(4500); dock.heardSpeech(); idle(200)
        shot("2-recording")
        dock.heardSpeech()
        tap(DockView.Act.MORE); relayout(); dock.heardSpeech()
        shot("4-menu")
        tap(DockView.Act.MORE); relayout(); dock.heardSpeech()
        tap(DockView.Act.MIC)
        dock.showCleaning("raw"); idle(500)
        shot("5-polishing")
    }
}
