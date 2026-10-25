package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import android.os.Looper
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
 * Not a check: the seven-button bar at rest with each choice of its own key, classic and tiered
 * colors, light and dark, stacked on a Fold cover screen's width.
 * Writes app/build/previews/extra-key/<theme>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderExtraKeyTest {
    @Config(qualifiers = "w454dp-h717dp-port-notnight-440dpi")
    @Test fun light() = render("light", 0xFFF9F9FF.toInt(), 0xFF191C20.toInt())

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun dark() = render("dark", 0xFF111318.toInt(), 0xFFE2E2E9.toInt())

    @Config(qualifiers = "w454dp-h717dp-port-notnight-440dpi")
    @Test fun pickerLight() = renderPicker("picker-light", 0xFFF9F9FF.toInt())

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun pickerDark() = renderPicker("picker-dark", 0xFF111318.toInt())

    /** The key right of Voice (⌫ by default) held: the same choices, lined up with its right edge. */
    @Config(qualifiers = "w454dp-h717dp-port-notnight-440dpi")
    @Test fun rightPickerLight() = renderPicker("right-picker-light", 0xFFF9F9FF.toInt(), DockView.Act.BACKSPACE)

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun rightPickerDark() = renderPicker("right-picker-dark", 0xFF111318.toInt(), DockView.Act.BACKSPACE)

    @Config(qualifiers = "w454dp-h717dp-port-notnight-440dpi")
    @Test fun listeningLight() = renderListening("listening-light", 0xFFF9F9FF.toInt())

    @Config(qualifiers = "w454dp-h717dp-port-night-440dpi")
    @Test fun listeningDark() = renderListening("listening-dark", 0xFF111318.toInt())

    /** The seven-button bar at rest, partway into dictation (Cursor and ABC sliding left), and dictating. */
    private fun renderListening(name: String, bg: Int) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear(); putString(Prefs.BAR_KEY, "paste") }
        val dock = DockView(activity, null)
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
            override fun onTalkStart() = true
        }
        dock.testSetBottomInset(0)
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = activity.resources.displayMetrics.widthPixels
        val d = activity.resources.displayMetrics.density
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        dock.settle()
        dock.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), 0)
        dock.layout(0, 0, w, dock.measuredHeight)
        dock.settle()
        val h = dock.height
        val frames = mutableListOf<Bitmap>()
        fun grab() = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b -> Canvas(b).apply { drawColor(bg); dock.draw(this) }; frames += b }
        grab()
        val r = dock.testItem(DockView.Act.MIC)!!
        val down = SystemClock.uptimeMillis()
        for (a in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), a, r.centerX(), r.centerY(), 0); dock.dispatchTouchEvent(e); e.recycle()
        }
        dock.showListening()
        repeat(12) { i -> dock.showLevel(listOf(0.2f, 0.6f, 0.9f, 0.4f)[i % 4]) }
        dock.testFrame(); shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(90)); dock.testFrame()
        grab()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        dock.settle()
        grab()
        val gap = (10 * d).toInt()
        val out = Bitmap.createBitmap(w, frames.size * (h + gap), Bitmap.Config.ARGB_8888)
        Canvas(out).apply { drawColor(bg); frames.forEachIndexed { i, b -> drawBitmap(b, 0f, (i * (h + gap)).toFloat(), null) } }
        File(File("build/previews/extra-key").apply { mkdirs() }, "$name.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The own key held, then the thumb slid up onto Undo. */
    private fun renderPicker(name: String, bg: Int, act: DockView.Act = DockView.Act.SPACE_ALT) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        Prefs.get(activity).edit { clear(); putString(Prefs.BAR_KEY, "paste") }
        val dock = DockView(activity, null)
        dock.listener = object : Bar.Listener {
            override fun onKey(key: Bar.Key, repeat: Boolean) {}
            override fun onSwitchKeyboard() {}
        }
        dock.testSetBottomInset(0)
        dock.reloadSettings()
        activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val w = activity.resources.displayMetrics.widthPixels
        fun lay() {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, w, dock.measuredHeight)
            dock.settle()
        }
        lay()
        val r = dock.testItem(act)!!
        val down = SystemClock.uptimeMillis()
        fun touch(a: Int, p: PointF) { val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), a, p.x, p.y, 0); dock.dispatchTouchEvent(e); e.recycle() }
        touch(MotionEvent.ACTION_DOWN, PointF(r.centerX(), r.centerY()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        lay()
        touch(MotionEvent.ACTION_MOVE, dock.testPickerRow(Bar.ExtraKey.UNDO)!!)
        lay()
        val bmp = Bitmap.createBitmap(w, dock.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        dock.draw(c)
        val out = File("build/previews/extra-key").apply { mkdirs() }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun render(theme: String, bg: Int, ink: Int) {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val d = activity.resources.displayMetrics.density
        val w = activity.resources.displayMetrics.widthPixels
        val rows = Bar.ExtraKey.entries.flatMap { k -> listOf(Prefs.BAR_COLORS_CLASSIC, Prefs.BAR_COLORS_TIERED).map { k to it } }
        val bars = rows.map { (key, colors) ->
            Prefs.get(activity).edit { clear() }
            val dock = DockView(activity, null)
            dock.showAsPreview(colors, key.pref)
            activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, w, dock.measuredHeight)
            dock.settle()
            Triple("${key.name.lowercase().replace('_', ' ')} · $colors", dock, dock.measuredHeight)
        }
        val label = 22 * d
        val h = bars.sumOf { it.third + label.toInt() }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(bg)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 13 * d }
        var y = 0f
        for ((name, dock, bh) in bars) {
            c.drawText(name, 12 * d, y + 16 * d, p)
            y += label
            c.save(); c.translate(0f, y); dock.draw(c); c.restore()
            y += bh
        }
        val out = File("build/previews/extra-key").apply { mkdirs() }
        File(out, "$theme.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
