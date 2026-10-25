package com.ryderdavid.voicebar

import android.app.Application
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.ryderdavid.voicebar.voice.Dictionary
import com.ryderdavid.voicebar.voice.Secrets
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackupTest {
    private val ctx = ApplicationProvider.getApplicationContext<Application>()

    @Test fun settingsDictionaryAndVoiceprintTravelButKeysDoNot() {
        Prefs.get(ctx).edit { putBoolean(Prefs.AUTO_LISTEN, true); putInt(Prefs.SILENCE_TIMEOUT, 7); putFloat(Prefs.NEAR_LEVEL, 0.62f); putString(Prefs.BAR_KEY, "paste") }
        Secrets.put(ctx, Secrets.ANTHROPIC, "sk-ant-secret")
        File(ctx.filesDir, "dictionary.json").writeText("""[{"term":"Siona","heard":["shauna"],"vocabulary":true,"count":3,"lastSeen":1,"learned":true}]""")
        File(ctx.filesDir, "voiceprint.bin").writeBytes(byteArrayOf(1, 2, 3, 4))
        val file = Backup.export(ctx)
        assertFalse(file.contains("sk-ant-secret"), "API keys never leave")

        // a fresh install
        Prefs.get(ctx).edit { clear() }
        File(ctx.filesDir, "dictionary.json").delete(); File(ctx.filesDir, "voiceprint.bin").delete()
        Dictionary.of(ctx).reload()

        val s = Backup.import(ctx, file)
        assertTrue(Prefs.get(ctx).getBoolean(Prefs.AUTO_LISTEN, false))
        assertEquals(7, Prefs.get(ctx).getInt(Prefs.SILENCE_TIMEOUT, 0))
        assertEquals(0.62f, Prefs.get(ctx).getFloat(Prefs.NEAR_LEVEL, 0f))
        assertEquals("paste", Prefs.get(ctx).getString(Prefs.BAR_KEY, null))
        assertEquals(1, s.words)
        assertTrue(Dictionary.of(ctx).all().any { it.term == "Siona" })
        assertTrue(s.voiceprint && File(ctx.filesDir, "voiceprint.bin").readBytes().contentEquals(byteArrayOf(1, 2, 3, 4)))
    }

    @Test fun anythingElseIsRefusedPlainly() {
        assertFailsWith<IllegalArgumentException> { Backup.import(ctx, "hello") }
        assertFailsWith<IllegalArgumentException> { Backup.import(ctx, """{"format":"something-else"}""") }
    }
}
