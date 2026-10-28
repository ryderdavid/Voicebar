package com.ryderdavid.voicebar.voice

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceGateTest {
    private val rate = 16000

    /** "Speech" is anything louder than a whisper. */
    private val loud = SpeechTest { w -> w.map { it * it }.average() > 0.002 }

    /** A pretend speaker model: low tones are one voice, high tones another. */
    private val byPitch: (FloatArray) -> FloatArray = { s ->
        var crossings = 0
        for (i in 1 until s.size) if ((s[i - 1] < 0) != (s[i] < 0)) crossings++
        val hz = crossings / 2f / (s.size.toFloat() / rate)
        if (hz < 350) floatArrayOf(1f, 0f) else floatArrayOf(0f, 1f)
    }
    private val me = floatArrayOf(1f, 0f)

    private fun tone(hz: Int, seconds: Float, amp: Float = 0.3f) = ByteArray((seconds * rate).toInt() * 2).also { b ->
        for (i in 0 until b.size / 2) {
            val v = (amp * sin(2 * PI * hz * i / rate) * 32767).toInt().toShort()
            b[2 * i] = (v.toInt() and 0xff).toByte(); b[2 * i + 1] = (v.toInt() shr 8).toByte()
        }
    }
    private fun quiet(seconds: Float) = ByteArray((seconds * rate).toInt() * 2)

    private class Out { val bytes = java.io.ByteArrayOutputStream(); fun all() = bytes.toByteArray() }

    private fun run(vararg parts: ByteArray, chunk: Int = 3200, verdicts: MutableList<Pair<Float?, Boolean>>? = null): ByteArray {
        val out = Out()
        val g = VoiceGate(loud, byPitch, me, threshold = 0.5f, emit = { out.bytes.write(it) }, onVerdict = { s, m -> verdicts?.add(s to m) })
        val all = parts.fold(ByteArray(0)) { a, b -> a + b }
        var i = 0
        while (i < all.size) { val n = minOf(chunk, all.size - i); g.accept(all.copyOfRange(i, i + n)); i += n }
        g.flush()
        return out.all()
    }

    private fun energy(b: ByteArray, fromS: Float, toS: Float): Double {
        val f = pcmToFloats(b)
        val a = (fromS * rate).toInt(); val z = minOf((toS * rate).toInt(), f.size)
        return (a until z).sumOf { abs(f[it]).toDouble() } / (z - a).coerceAtLeast(1)
    }

    @Test fun myVoicePassesUnchanged() {
        val input = tone(200, 3f)
        assertTrue(run(input).contentEquals(input))
    }

    @Test fun anotherVoiceIsSilenced() {
        val out = run(tone(600, 3f))
        assertEquals(3 * rate * 2, out.size)
        assertTrue(out.all { it == 0.toByte() })
    }

    @Test fun turnsAreSeparated() {
        val out = run(tone(200, 2f), quiet(1f), tone(600, 2f), quiet(0.5f), tone(200, 1.5f))
        assertEquals((7f * rate).toInt() * 2, out.size)
        assertTrue(energy(out, 0f, 2f) > 0.1, "my first sentence")
        assertTrue(energy(out, 3f, 5f) < 0.001, "the other voice")
        assertTrue(energy(out, 5.5f, 7f) > 0.1, "me again")
    }

    @Test fun aShortWordStillGetsThrough() {
        // "yes": too short to judge by, so it isn't dropped
        val out = run(quiet(1f), tone(200, 0.2f), quiet(1f))
        assertTrue(energy(out, 1f, 1.2f) > 0.1)
    }

    @Test fun silenceIsNotHeldBack() {
        val out = Out()
        val g = VoiceGate(loud, byPitch, me, 0.5f, emit = { out.bytes.write(it) })
        g.accept(quiet(0.5f))
        assertEquals(rate, out.all().size)
    }

    @Test fun speechIsHeldAtMostABlockOrTwo() {
        val out = Out()
        val g = VoiceGate(loud, byPitch, me, 0.5f, emit = { out.bytes.write(it) })
        g.accept(tone(200, 1.0f))
        assertTrue(out.all().size >= rate * 2, "a second in, at least the first half-second is out")
    }

    /** No voiceprint: the first voice at the phone (louder than [near]) is the one kept. */
    private fun runFirst(vararg parts: ByteArray, learned: MutableList<Boolean>? = null): ByteArray {
        val out = Out()
        val g = VoiceGate(loud, byPitch, null, threshold = 0.5f, emit = { out.bytes.write(it) }, near = 0.7f, onLearned = { learned?.add(true) })
        val all = parts.fold(ByteArray(0)) { x, y -> x + y }
        var i = 0
        while (i < all.size) { val n = minOf(3200, all.size - i); g.accept(all.copyOfRange(i, i + n)); i += n }
        g.flush()
        return out.all()
    }

    @Test fun withoutAVoiceprintTheFirstVoiceIsKeptAndOthersSilenced() {
        val learned = mutableListOf<Boolean>()
        val out = runFirst(tone(200, 2f), quiet(1f), tone(600, 2f), quiet(0.5f), tone(200, 1.5f), learned = learned)
        assertEquals(listOf(true), learned, "learned once, from the first speech")
        assertTrue(energy(out, 0f, 2f) > 0.1, "the first voice, from its first word")
        assertTrue(energy(out, 3f, 5f) < 0.001, "another voice")
        assertTrue(energy(out, 5.5f, 7f) > 0.1, "the first voice again")
    }

    @Test fun itsWhoeverSpeaksFirstNotAFixedVoice() {
        val out = runFirst(tone(600, 2f), quiet(1f), tone(200, 2f))
        assertTrue(energy(out, 0f, 2f) > 0.1, "the high voice started it")
        assertTrue(energy(out, 3f, 5f) < 0.001, "so the low one is the other voice")
    }

    @Test fun aDistantVoiceBeforeYouSpeakIsntTakenAsYours() {
        // a TV across the room, then you at the phone, then the TV again (now louder)
        val out = runFirst(tone(600, 1.5f, amp = 0.08f), quiet(0.5f), tone(200, 2f), quiet(0.5f), tone(600, 1.5f))
        assertTrue(energy(out, 0f, 1.5f) < 0.001, "too quiet to be whoever started this")
        assertTrue(energy(out, 2f, 4f) > 0.1, "you")
        assertTrue(energy(out, 4.5f, 6f) < 0.001, "the TV isn't you, however loud")
    }

    @Test fun strengthMapsToThresholds() {
        assertEquals(0.30f, Voiceprint.threshold(1), 1e-4f)
        assertEquals(0.46f, Voiceprint.threshold(3), 1e-4f)
        assertEquals(0.62f, Voiceprint.threshold(5), 1e-4f)
        assertEquals(0.62f, Voiceprint.threshold(9), 1e-4f)
    }

    @Test fun enrollmentAveragesSpeechOnly() {
        val e = Enrollment(loud, byPitch)
        e.accept(pcmToFloats(quiet(2f)))
        e.accept(pcmToFloats(tone(200, 6f)))
        assertEquals(6f, e.seconds, 0.05f)
        val (print, consistency) = e.result()!!
        assertTrue(print.contentEquals(floatArrayOf(1f, 0f)))
        assertEquals(1f, consistency, 1e-4f)
    }

    @Test fun windowsCoverTheSpeech() {
        assertEquals(3, Voiceprint.windows(FloatArray(rate * 4)).size) // 1.5 + 1.5 + 1.0
        assertEquals(2, Voiceprint.windows(FloatArray(rate * 3 + 100)).size) // a 0.006 s tail is dropped
    }
}
