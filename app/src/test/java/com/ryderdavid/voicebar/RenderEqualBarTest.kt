package com.ryderdavid.voicebar

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
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
 * Not a check: the docked bar at rest, today's three pills vs seven equal pills, on a whole Fold 8
 * screen (cover 1248×1972, inner 1828×2448, at 440 dpi) under a fake messaging app.
 * Writes app/build/previews/equal/<screen>-<layout>.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RenderEqualBarTest {
    @Config(qualifiers = "w454dp-h717dp-port-440dpi")
    @Test fun coverPortrait() = render("1-cover-portrait")

    @Config(qualifiers = "w665dp-h890dp-port-440dpi")
    @Test fun innerPortrait() = render("2-inner-portrait")

    @Config(qualifiers = "w890dp-h665dp-land-440dpi")
    @Test fun innerLandscape() = render("3-inner-landscape")

    private fun render(screen: String) {
        for (layout in listOf("equal")) {
            val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            Prefs.get(activity).edit { clear() }
            val dock = DockView(activity, null)
            dock.listener = object : Bar.Listener {
                override fun onKey(key: Bar.Key, repeat: Boolean) {}
                override fun onSwitchKeyboard() {}
                override fun shouldCapitalize() = false
                override fun onTalkStart() = true
            }
            dock.reloadSettings()
            activity.setContentView(dock, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val dm = activity.resources.displayMetrics
            val d = dm.density
            dock.testSetBottomInset((24 * d).toInt())
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
            dock.settle()
            dock.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY), 0)
            dock.layout(0, 0, dm.widthPixels, dock.measuredHeight)
            dock.settle()

            val w = dm.widthPixels
            val h = dm.heightPixels
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            val ink = 0xFF191C20.toInt()
            c.drawColor(0xFFF9F9FF.toInt())
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 13 * d }
            // status bar
            c.drawText("16:25", 16 * d, 24 * d, p)
            c.drawText("71%", w - 44 * d, 24 * d, p)
            // app bar
            p.textSize = 20 * d
            c.drawText("Allison", 56 * d, 72 * d, p)
            val bubbleIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE2E2E9.toInt() }
            val bubbleOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFD7E3F8.toInt() }
            c.drawCircle(28 * d, 65 * d, 14 * d, bubbleIn)
            // conversation, anchored above the composer
            val appBottom = h - dock.height
            p.textSize = 15 * d
            val msgs = listOf(
                false to "Still on for the trailhead at 6:40?",
                true to "Yes! Bringing the thermos",
                false to "Perfect. Ezra's coming too",
                true to "Running ten minutes late",
            )
            var y = appBottom - 76 * d
            for ((mine, m) in msgs.reversed()) {
                val tw = p.measureText(m) + 28 * d
                val l = if (mine) w - 16 * d - tw else 16 * d
                c.drawRoundRect(l, y - 40 * d, l + tw, y, 20 * d, 20 * d, if (mine) bubbleOut else bubbleIn)
                c.drawText(m, l + 14 * d, y - 14 * d, p)
                y -= 52 * d
            }
            // composer
            c.drawRoundRect(16 * d, appBottom - 60 * d, w - 16 * d, appBottom - 12 * d, 24 * d, 24 * d, bubbleIn)
            c.drawText("Message", 34 * d, appBottom - 30 * d, p.apply { alpha = 150 })
            // the keyboard
            c.save(); c.translate(0f, appBottom.toFloat()); dock.draw(c)
            val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; alpha = 140 }
            c.drawRoundRect(w / 2f - 54 * d, dock.height - 14 * d, w / 2f + 54 * d, dock.height - 10 * d, 2 * d, 2 * d, pill)
            c.restore()

            val out = File("build/previews/equal").apply { mkdirs() }
            File(out, "$screen-$layout.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
