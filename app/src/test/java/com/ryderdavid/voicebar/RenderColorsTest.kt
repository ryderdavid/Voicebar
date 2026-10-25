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

/**
 * Not a check: the bar's states in the classic and tiered color schemes, both layouts, on the
 * Fold 8 cover screen. Writes app/build/previews/colors/<mode>-<colors>-<layout>-<state>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderColorsTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun light() = renderAll("light")

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = renderAll("dark")

    private fun renderAll(mode: String) {
        for (colors in listOf(Prefs.BAR_COLORS_CLASSIC, Prefs.BAR_COLORS_TIERED))
            for (layout in listOf("equal"))
                render(mode, colors, layout)
    }

    private fun render(mode: String, colors: String, layout: String) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear(); putString(Prefs.BAR_COLORS, colors) }
        val dock = DockView(activity, null)
        var down = 0L
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
            override fun shouldCapitalize() = false
            override fun onTalkStart() = true
        }
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val d = activity.resources.displayMetrics.density
        dock.testSetBottomInset((24 * d).toInt())
        fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        fun relayout() {
            idle(300); dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
        }
        fun touch(action: Int, p: PointF) {
            if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, p.x, p.y, 0)
            dock.dispatchTouchEvent(e); e.recycle()
        }
        fun tap(a: DockView.Act) { val r = dock.testItem(a)!!; val p = PointF(r.centerX(), r.centerY()); touch(MotionEvent.ACTION_DOWN, p); touch(MotionEvent.ACTION_UP, p); idle(30) }
        val night = mode == "dark"
        val out = File("build/previews/colors").apply { mkdirs() }
        fun shot(state: String) {
            relayout()
            val w = dock.width
            val appH = (70 * d).toInt()
            val bmp = Bitmap.createBitmap(w, appH + dock.height, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; color = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt() }
            val field = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt() }
            c.drawRoundRect(16 * d, appH - 60 * d, w - 16 * d, appH - 12 * d, 24 * d, 24 * d, field)
            c.drawText("Message", 34 * d, appH - 30 * d, p.apply { alpha = 150 })
            c.save(); c.translate(0f, appH.toFloat()); dock.draw(c)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt(); alpha = 140 }
            c.drawRoundRect(w / 2f - 54 * d, dock.height - 14 * d, w / 2f + 54 * d, dock.height - 10 * d, 2 * d, 2 * d, pill)
            c.restore()
            File(out, "$mode-$colors-$layout-$state.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        dock.settle()
        shot("1-rest")
        tap(DockView.Act.MIC)
        dock.showListening()
        repeat(40) { i -> dock.showLevel(listOf(0.1f, 0.4f, 0.8f, 0.5f, 0.9f, 0.3f)[i % 6]) }
        dock.heardSpeech()
        dock.voiceInField = false
        dock.showLiveText("Running ten minutes late, start without me")
        shot("2-recording")
        tap(DockView.Act.MIC)
        dock.talkEnded()
        dock.hideVoice()
        relayout()
        tap(DockView.Act.CURSOR)
        shot("3-cursor")
        tap(DockView.Act.CURSOR)
        relayout()
        tap(DockView.Act.MORE)
        shot("4-menu")
        tap(DockView.Act.MORE)
    }
}
