package com.ryderdavid.voicebar.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.ryderdavid.voicebar.Prefs
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * "Only my voice": a voiceprint of the user, made on the phone from about half a minute of
 * reading, and a speaker model (3D-Speaker ERes2Net, 26 MB, downloaded when the setting is turned
 * on) that tells whether speech sounds like them. Speech that doesn't is replaced with silence
 * before it reaches speech recognition ([VoiceFilterTranscriber]). Nothing leaves the phone.
 */
object Voiceprint {
    const val MODEL_NAME = "3D-Speaker ERes2Net"
    private const val MODEL_FILE = "3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx"
    private const val BASE = "https://huggingface.co/csukuangfj/speaker-embedding-models/resolve/0743f301363dec56491a490f6d6cbc9d67f9a3bf/"
    val pack = ModelPack(MODEL_NAME, "speaker-eres2net", BASE, listOf(
        ModelPack.File(MODEL_FILE, 26_485_263, "c59158379255ad66e161679cca6af8d52d51e389e3224ab7d7a7baae295c2db5"),
    ))

    /** Strength 1..5 → how alike speech must sound to the voiceprint (cosine similarity). */
    val STRENGTH_RANGE = 1..5
    const val DEFAULT_STRENGTH = 3
    fun threshold(strength: Int): Float = 0.30f + 0.08f * (strength.coerceIn(STRENGTH_RANGE) - 1)

    /** Sentences to read for the voiceprint: varied sounds, about 30 seconds together. */
    val SENTENCES = listOf(
        "The quick brown fox jumps over the lazy dog, while a curious cat watches from the warm windowsill.",
        "On Thursday morning I'll call the pharmacy, pick up groceries, and send Jamie the photos from Saturday.",
        "Please remind me at half past six to water the garden and check whether the package arrived.",
        "Every voice is a little different: the pitch, the pace, the way we lean into vowels and soften consonants.",
    )
    /** Speech needed for a voiceprint (seconds of actual talking, pauses not counted). */
    const val ENROLL_SECONDS = 20

    fun enabled(context: Context) = Prefs.get(context).getBoolean(Prefs.VOICEPRINT, false)
    fun strength(context: Context) = Prefs.get(context).getInt(Prefs.VOICEPRINT_STRENGTH, DEFAULT_STRENGTH)

    /**
     * Turned on and downloaded: speech is filtered, against the voiceprint when one is recorded,
     * else (in a dictation started by hand) against the first voice heard ([VoiceGate]).
     */
    fun active(context: Context) = enabled(context) && pack.ready(context)

    // ---------------------------------------------------------------- the stored print

    private fun file(context: Context) = File(context.filesDir, "voiceprint.bin")

    fun has(context: Context) = file(context).exists()

    fun load(context: Context): FloatArray? = runCatching {
        DataInputStream(file(context).inputStream().buffered()).use { input ->
            val n = input.readInt()
            require(n in 1..4096)
            FloatArray(n) { input.readFloat() }
        }
    }.getOrNull()

    fun save(context: Context, print: FloatArray) {
        val tmp = File(context.filesDir, "voiceprint.bin.tmp")
        DataOutputStream(tmp.outputStream().buffered()).use { out ->
            out.writeInt(print.size); print.forEach { out.writeFloat(it) }
        }
        tmp.renameTo(file(context))
    }

    fun forget(context: Context) { file(context).delete() }

    // ---------------------------------------------------------------- the model, loaded once

    /** The speaker model (about 30 MB of memory), let go after a few idle minutes. */
    object Engine {
        private const val IDLE_MS = 3 * 60_000L
        private val lock = Any()
        @Volatile private var ex: SpeakerEmbeddingExtractor? = null
        private val main = Handler(Looper.getMainLooper())
        private val idle: Runnable = Runnable { release() }

        private fun get(context: Context): SpeakerEmbeddingExtractor = synchronized(lock) {
            main.removeCallbacks(idle)
            ex ?: SpeakerEmbeddingExtractor(null, SpeakerEmbeddingExtractorConfig(
                model = pack.file(context, MODEL_FILE).path, numThreads = 2, debug = false, provider = "cpu",
            )).also { ex = it }
        }

        /** The unit-length embedding of [samples] (16 kHz floats). */
        fun embed(context: Context, samples: FloatArray): FloatArray {
            val e = get(context)
            val s = e.createStream()
            try {
                s.acceptWaveform(samples, 16000)
                s.inputFinished()
                return normalize(e.compute(s))
            } finally { s.release() }
        }

        fun done() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS) }

        fun release(): Unit = synchronized(lock) {
            main.removeCallbacks(idle)
            ex?.release()
            ex = null
        }
    }

    // ---------------------------------------------------------------- math

    fun normalize(v: FloatArray): FloatArray {
        var n = 0.0
        for (x in v) n += x * x
        val len = sqrt(n).toFloat()
        return if (len == 0f) v else FloatArray(v.size) { v[it] / len }
    }

    /** Cosine similarity of two unit vectors. */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** The voiceprint from several embeddings: their normalized mean. */
    fun average(embeddings: List<FloatArray>): FloatArray {
        val out = FloatArray(embeddings.first().size)
        for (e in embeddings) for (i in out.indices) out[i] += e[i]
        return normalize(out)
    }

    /** Cuts speech into windows of [seconds] (the last, if long enough, included) for embedding. */
    fun windows(speech: FloatArray, seconds: Float = 1.5f, sampleRate: Int = 16000): List<FloatArray> {
        val n = (seconds * sampleRate).toInt()
        val out = ArrayList<FloatArray>()
        var i = 0
        while (i + n <= speech.size) { out += speech.copyOfRange(i, i + n); i += n }
        if (speech.size - i >= n / 2) out += speech.copyOfRange(i, speech.size)
        return out
    }
}

/** Is this 32 ms of audio speech? (Silero, via sherpa-onnx; stateful, so feed it in order.) */
fun interface SpeechTest {
    fun isSpeech(window: FloatArray): Boolean
    /** Size of the windows it wants. */
    companion object { const val WINDOW = 512 }
}

/** Silero VAD's per-window speech probability. */
class SileroSpeechTest(context: Context, private val threshold: Float = 0.5f) : SpeechTest, AutoCloseable {
    private val vad = Vad(context.assets, VadModelConfig(
        sileroVadModelConfig = SileroVadModelConfig(model = "vad/silero_vad.onnx", threshold = threshold, windowSize = SpeechTest.WINDOW),
        sampleRate = 16000, numThreads = 1,
    ))
    override fun isSpeech(window: FloatArray) = vad.compute(window) >= threshold
    override fun close() = vad.release()
}

/**
 * Gathers a voiceprint while the user reads aloud: keeps only the speech, and when there's
 * enough, embeds it in 1.5-second windows and averages them. Call from one thread.
 */
class Enrollment(private val speech: SpeechTest, private val embed: (FloatArray) -> FloatArray, private val sampleRate: Int = 16000) {
    private val kept = ArrayList<FloatArray>()
    private var keptSamples = 0
    private val pending = FloatArrayBuilder()

    /** Seconds of speech so far. */
    val seconds get() = keptSamples.toFloat() / sampleRate
    val enough get() = seconds >= Voiceprint.ENROLL_SECONDS

    fun accept(samples: FloatArray) {
        pending.add(samples)
        while (pending.size >= SpeechTest.WINDOW) {
            val w = pending.take(SpeechTest.WINDOW)
            if (speech.isSpeech(w)) { kept += w; keptSamples += w.size }
        }
    }

    /** The voiceprint and how consistent the recording was (mean similarity of windows to it), or null. */
    fun result(): Pair<FloatArray, Float>? {
        val all = FloatArray(keptSamples).also { var i = 0; for (k in kept) { k.copyInto(it, i); i += k.size } }
        val embs = Voiceprint.windows(all).map(embed)
        if (embs.size < 3) return null
        val print = Voiceprint.average(embs)
        return print to embs.map { Voiceprint.similarity(it, print) }.average().toFloat()
    }
}

/** A growable float queue (append at the end, take from the front). */
internal class FloatArrayBuilder {
    private var buf = FloatArray(4096)
    private var start = 0
    private var end = 0
    val size get() = end - start

    fun add(a: FloatArray) {
        if (end + a.size > buf.size) {
            val live = size
            val nb = if (live + a.size > buf.size / 2) FloatArray(maxOf(buf.size * 2, live + a.size)) else buf
            buf.copyInto(nb, 0, start, end)
            buf = nb; start = 0; end = live
        }
        a.copyInto(buf, end); end += a.size
    }

    fun take(n: Int): FloatArray { val out = buf.copyOfRange(start, start + n); start += n; return out }
    fun takeAll(): FloatArray = take(size)
}

fun pcmToFloats(pcm: ByteArray): FloatArray {
    val f = FloatArray(pcm.size / 2)
    for (i in f.indices) f[i] = ((pcm[2 * i + 1].toInt() shl 8) or (pcm[2 * i].toInt() and 0xff)).toShort() / 32768f
    return f
}

private const val TAG = "VoicebarVoiceprint"
internal fun logW(msg: String, t: Throwable? = null) { Log.w(TAG, msg, t); com.ryderdavid.voicebar.AppLog.log("warn", msg, t) }
