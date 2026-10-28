package com.ryderdavid.voicebar.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Something that captures speech as 16 kHz, 16-bit, mono little-endian PCM. */
interface AudioSource {
    /** [onChunk] gets ~100 ms of PCM; [onLevel] gets a 0..1 loudness for the UI. Both on a background thread. */
    fun start(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit): Boolean
    fun stop()
}

/** The phone's microphone via AudioRecord, read on its own thread. */
class MicRecorder : AudioSource {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission") // the caller checks RECORD_AUDIO first
    override fun start(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuffer, CHUNK_BYTES * 4)
            )
        }.getOrNull() ?: return false
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        val started = runCatching { record.startRecording() }.isSuccess &&
            record.recordingState == AudioRecord.RECORDSTATE_RECORDING
        if (!started) { // e.g. another app holds the microphone
            record.release()
            return false
        }
        running = true
        thread = Thread({
            val buffer = ByteArray(CHUNK_BYTES)
            try {
                while (running) {
                    var filled = 0
                    while (filled < buffer.size && running) {
                        val n = record.read(buffer, filled, buffer.size - filled)
                        if (n < 0) { running = false; break } // the recorder failed; stop rather than spin
                        if (n == 0) break
                        filled += n
                    }
                    if (filled > 0) {
                        val chunk = buffer.copyOf(filled)
                        onLevel(level(chunk))
                        onChunk(chunk)
                    }
                }
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "bubble-mic").apply { start() }
        return true
    }

    override fun stop() {
        running = false
        thread?.join(500)
        thread = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10 // 100 ms

        /** Loudness of samples in -1..1, on the same 0..1 scale as [level]. */
        fun level(samples: FloatArray): Float {
            if (samples.isEmpty()) return 0f
            var sum = 0.0
            for (x in samples) sum += x.toDouble() * x
            val db = 20 * log10(sqrt(sum / samples.size).coerceAtLeast(1e-6))
            return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        }

        /** Loudness of a PCM chunk mapped to 0..1 (about -50 dBFS to -10 dBFS). */
        fun level(pcm: ByteArray): Float {
            var sum = 0.0
            var i = 0
            while (i + 1 < pcm.size) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xff)).toShort().toDouble()
                sum += s * s
                i += 2
            }
            val samples = pcm.size / 2
            if (samples == 0) return 0f
            val rms = sqrt(sum / samples) / 32768.0
            val db = 20 * log10(rms.coerceAtLeast(1e-6))
            return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
        }
    }
}
