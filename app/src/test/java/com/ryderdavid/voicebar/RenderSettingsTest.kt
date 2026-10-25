package com.ryderdavid.voicebar

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import kotlin.test.Test

/** Not a check: renders each settings page, light and dark, into app/build/previews/settings/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w400dp-h900dp-port-xxhdpi")
class RenderSettingsTest {

    @Test fun light() = renderAll("light")

    @Config(qualifiers = "+night")
    @Test fun dark() = renderAll("dark")

    /** The voice page whole (a tall screen), with some dictation logged so the cost rows show. */
    @Config(qualifiers = "w411dp-h1500dp")
    @Test fun voiceUsage() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val log = com.ryderdavid.voicebar.voice.UsageLog.of(ctx)
        log.clear()
        repeat(40) { log.addAudio(25_000); log.addClaude("claude-haiku-5-5", 420, 60) }
        val out = File("build/previews/settings").apply { mkdirs() }
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, Page.VOICE.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "voice-usage.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    /** Home with the old (pre-1.0) app still installed: the moving card, then setup and the rest. */
    @Config(qualifiers = "w411dp-h1700dp")
    @Test fun homeMoving() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        shadowOf(ctx.packageManager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = Migration.OLD_PACKAGE
            applicationInfo = android.content.pm.ApplicationInfo().apply { packageName = Migration.OLD_PACKAGE }
        })
        val out = File("build/previews/settings").apply { mkdirs() }
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, Page.HOME.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "home-moving.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    /** The Look page whole, to see the bar-layout options with their live previews. */
    @Config(qualifiers = "w454dp-h1400dp-port-440dpi")
    @Test fun lookTall() = renderLook("look-tall")

    @Config(qualifiers = "w454dp-h1400dp-port-night-440dpi")
    @Test fun lookTallDark() = renderLook("look-tall-dark")

    /** Millennial mode's checks: search boxes on, then everywhere on top (the other two checked and dimmed). */
    @Config(qualifiers = "w454dp-h900dp-port-440dpi")
    @Test fun millennialSearch() = renderPage("millennial-search", Page.LANGUAGE) { putBoolean(Prefs.MILLENNIAL_IN_SEARCH, true); putBoolean(Prefs.MILLENNIAL_IN_ALL, false) }

    @Config(qualifiers = "w454dp-h900dp-port-440dpi")
    @Test fun millennialEverywhere() = renderPage("millennial-everywhere", Page.LANGUAGE) { putBoolean(Prefs.MILLENNIAL_IN_SEARCH, true); putBoolean(Prefs.MILLENNIAL_IN_ALL, true) }

    private fun renderLook(name: String) = renderPage(name, Page.LOOK) { }

    private fun renderPage(name: String, page: Page, prefs: android.content.SharedPreferences.Editor.() -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear(); prefs() }
        val out = File("build/previews/settings").apply { mkdirs() }
        val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page.name)
        val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
        repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
        val root = controller.get().window.decorView
        val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private fun renderAll(mode: String) {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        Prefs.get(ctx).edit { clear() }
        val out = File("build/previews/settings").apply { mkdirs() }
        for (page in Page.entries) {
            val intent = Intent(ctx, SettingsActivity::class.java).putExtra(SettingsActivity.EXTRA_PAGE, page.name)
            val controller = Robolectric.buildActivity(SettingsActivity::class.java, intent).setup()
            repeat(10) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100)) }
            val root = controller.get().window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$mode-${page.ordinal}-${page.name.lowercase()}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            controller.pause().stop().destroy()
        }
        @Suppress("UNUSED_VARIABLE") val unused = RuntimeEnvironment.getApiLevel()
    }
}
