package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * Not a check: the upright floating bar on a phone-sized screen over a stand-in chat: at rest,
 * recording, the mic pulled up (send) and down (cancel), its menu, the cursor pad, a note, and collapsed.
 * Writes app/build/previews/upright/<mode>-<state>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderUprightTest {
    @Config(qualifiers = "w412dp-h915dp-port-xhdpi")
    @Test fun light() = render("light")

    @Config(qualifiers = "w412dp-h915dp-port-night-xhdpi")
    @Test fun dark() = render("dark")

    private fun render(mode: String) {
        val night = mode == "dark"
        val out = File("build/previews/upright").apply { mkdirs() }
        run {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            Prefs.get(activity).edit { clear(); putString(Prefs.BAR_STYLE, Prefs.BAR_STYLE_FLOATING); putBoolean(Prefs.BAR_UPRIGHT, true); putFloat(Prefs.FLOAT_X, 1f); putFloat(Prefs.FLOAT_Y, 0.5f); putFloat(Prefs.PILL_X, 1f); putFloat(Prefs.PILL_Y, 0.55f) }
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
            val levels = listOf(0.2f, 0.6f, 0.9f, 0.4f, 0.7f, 0.3f)
            var li = 0
            fun idle(ms: Long) { var left = ms; while (left > 0) { val st = minOf(40L, left); looper.idleFor(Duration.ofMillis(st)); dock.showLevel(levels[li++ % levels.size]); left -= st } }
            fun relayout() {
                idle(300); dock.settle()
                dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
                dock.layout(0, 0, dock.width, dock.measuredHeight); dock.settle()
            }
            var down = 0L
            fun ev(action: Int, x: Float, y: Float) {
                val t = SystemClock.uptimeMillis()
                if (action == MotionEvent.ACTION_DOWN) down = t
                MotionEvent.obtain(down, t, action, x, y, 0).also { dock.dispatchTouchEvent(it); it.recycle() }
            }
            fun tap(a: DockView.Act) { val r = dock.testItem(a)!!; ev(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()); ev(MotionEvent.ACTION_UP, r.centerX(), r.centerY()) }
            relayout()
            // a whole phone screen: a stand-in chat above, the keyboard's window at the bottom
            fun shot(name: String) {
                dock.settle()
                dock.measure(View.MeasureSpec.makeMeasureSpec(dock.width, View.MeasureSpec.EXACTLY), 0)
                dock.layout(0, 0, dock.width, dock.measuredHeight)
                val w = dock.width
                val h = activity.resources.displayMetrics.heightPixels
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                c.drawColor(if (night) 0xFF111318.toInt() else 0xFFF9F9FF.toInt())
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15 * d; color = if (night) 0xFFE2E2E9.toInt() else 0xFF191C20.toInt() }
                // the app's own layout ends where the keyboard's content starts (all of it, collapsed)
                val appBottom = dock.floatingInsets()?.first?.let { (h - dock.height + it).toFloat() } ?: (h - dock.height + dock.floatRoom()).toFloat()
                p.color = if (night) 0xFF1D2024.toInt() else 0xFFEDEDF4.toInt()
                c.drawRect(0f, 0f, w.toFloat(), 64 * d, p)
                c.drawText("Jamie", 20 * d, 40 * d, text)
                val inB = if (night) 0xFF33353A.toInt() else 0xFFE2E2E9.toInt()
                val outB = if (night) 0xFFAAC7FF.toInt() else 0xFF415F91.toInt()
                fun bubble(y: Float, s: String, out: Boolean) {
                    val tw = text.measureText(s) + 28 * d
                    val r = if (out) RectF(w - 12 * d - tw, y, w - 12 * d, y + 40 * d) else RectF(12 * d, y, 12 * d + tw, y + 40 * d)
                    p.color = if (out) outB else inB
                    c.drawRoundRect(r, 18 * d, 18 * d, p)
                    val col = text.color
                    if (out) text.color = if (night) 0xFF0A305F.toInt() else 0xFFFFFFFF.toInt()
                    c.drawText(s, r.left + 14 * d, r.centerY() + 5 * d, text)
                    text.color = col
                }
                bubble(appBottom - 170 * d, "Hike tomorrow? When should we meet?", false)
                bubble(appBottom - 120 * d, "Let me check the trail first", true)
                p.color = inB
                c.drawRoundRect(RectF(12 * d, appBottom - 62 * d, w - 64 * d, appBottom - 16 * d), 23 * d, 23 * d, p)
                text.color = if (night) 0xFF8E9099.toInt() else 0xFF74777F.toInt()
                c.drawText("Message", 30 * d, appBottom - 34 * d, text)
                p.color = outB
                c.drawCircle(w - 36 * d, appBottom - 39 * d, 23 * d, p)
                c.save(); c.translate(0f, (h - dock.height).toFloat()); dock.draw(c); c.restore()
                File(out, "$mode-$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
            fun drag(a: DockView.Act, dy: Float) { val r = dock.testItem(a)!!; ev(MotionEvent.ACTION_DOWN, r.centerX(), r.centerY()); ev(MotionEvent.ACTION_MOVE, r.centerX(), r.centerY() + dy / 2); ev(MotionEvent.ACTION_MOVE, r.centerX(), r.centerY() + dy) }
            shot("1-rest")
            tap(DockView.Act.MIC); dock.showListening(); dock.heardSpeech(); idle(1500); dock.heardSpeech(); relayout(); dock.heardSpeech()
            shot("2-recording")
            drag(DockView.Act.MIC, -30 * d); idle(40); shot("3-pull-up-halfway")
            ev(MotionEvent.ACTION_MOVE, dock.testItem(DockView.Act.MIC)!!.centerX(), dock.testItem(DockView.Act.MIC)!!.centerY() - 70 * d); idle(40); shot("4-pull-up-send")
            ev(MotionEvent.ACTION_CANCEL, 0f, 0f); idle(400)
            drag(DockView.Act.MIC, 70 * d); idle(40); shot("5-pull-down-cancel")
            ev(MotionEvent.ACTION_CANCEL, 0f, 0f); idle(400)
            tap(DockView.Act.MIC); dock.showCleaning("raw"); idle(500); shot("6-polishing")
            dock.hideVoice(); idle(400); relayout()
            dock.canPaste = true
            dock.clipPreview = "Meet at the Camelback trailhead, 6:40 sharp"
            tap(DockView.Act.MORE); relayout(); shot("7-menu")
            tap(DockView.Act.MORE); relayout()
            tap(DockView.Act.CURSOR); relayout(); shot("8-cursor-pad")
            tap(DockView.Act.CURSOR); relayout()
            dock.showNote("Nothing in the box to fix yet", null); relayout(); shot("9-note")
            dock.hideVoice(); idle(400); relayout()
            tap(DockView.Act.MORE); relayout()
            dock.testMenuAt(DockView.MenuItem.COLLAPSE)!!.let { ev(MotionEvent.ACTION_DOWN, it.x, it.y); ev(MotionEvent.ACTION_UP, it.x, it.y) }
            relayout(); shot("10-collapsed")
        }
    }
}
