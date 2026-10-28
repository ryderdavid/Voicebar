package com.ryderdavid.voicebar.voice

import android.content.Context
import java.util.concurrent.Executors

/**
 * Keeps only the user's voice: audio goes through in half-second blocks, and each block with
 * speech in it is checked against the voiceprint, using the last second of speech; a block
 * that doesn't sound like the user is replaced with silence. Recognition hears the user and
 * quiet, never the other voice. This costs one block of delay (half a second), and at the start
 * of speech, when there isn't enough to judge yet, up to one more.
 *
 * Voices talking over each other at the same moment can't be pulled apart this way; it handles
 * turns (someone else speaking between your sentences, a TV, a conversation nearby).
 *
 * With no voiceprint ([print] null), the first voice is the one kept: the first [LEARN_SECONDS] of
 * speech at least as loud as [near] (someone talking at the phone, as the user does) become the
 * reference. Until then, speech that close goes through and quieter speech (a TV, a room away) is
 * silenced, since whoever started the dictation is the one holding the phone.
 * Single-threaded: call everything from one thread.
 */
class VoiceGate(
    private val speech: SpeechTest,
    private val embed: (FloatArray) -> FloatArray,
    print: FloatArray?,
    private val threshold: Float,
    private val emit: (ByteArray) -> Unit,
    /** Each judgement: the similarity (null when there was too little speech to tell) and the verdict. */
    private val onVerdict: ((Float?, Boolean) -> Unit)? = null,
    /** Learning the first voice: how loud (0..1, as [MicRecorder.level]) speech must be to count as it. */
    private val near: Float = 0f,
    /** The first voice is learned. */
    private val onLearned: (() -> Unit)? = null,
    private val sampleRate: Int = 16000,
    blockMs: Int = 500,
) {
    private var print: FloatArray? = print
    private val learnSamples = (sampleRate * LEARN_SECONDS).toInt()
    private val learning = FloatArrayBuilder()
    private var blockNear = 0
    private val blockSamples = sampleRate * blockMs / 1000
    private val windowSamples = sampleRate              // judged on the last second of speech
    private val judgeSamples = sampleRate / 2           // wait for half a second of speech if we can
    private val leastSamples = sampleRate / 4           // under a quarter second: too little to tell
    private val resetSilence = sampleRate / 2           // a pause this long may be a new speaker
    private val maxHeld = 2
    /** Once it's you, a little leeway, so a soft word mid-sentence isn't dropped. */
    private val leeway = 0.05f

    private class Block(val pcm: ByteArray, val speech: Int, val near: Int)

    private val vadQueue = FloatArrayBuilder()
    private var blockPcm = java.io.ByteArrayOutputStream()
    private var blockFilled = 0
    private var blockSpeech = 0
    private val recent = ArrayDeque<FloatArray>()
    private var recentSamples = 0
    private val held = ArrayList<Block>()
    private var silenceRun = 0
    private var lastMe = false

    fun accept(pcm: ByteArray) {
        val f = pcmToFloats(pcm)
        var off = 0
        while (off < f.size) {
            val n = minOf(f.size - off, blockSamples - blockFilled)
            vadQueue.add(f.copyOfRange(off, off + n))
            blockPcm.write(pcm, off * 2, n * 2)
            blockFilled += n; off += n
            runVad()
            if (blockFilled == blockSamples) closeBlock()
        }
    }

    /** Decides everything held, now (a commit or the end). */
    fun flush() {
        if (blockFilled > 0) closeBlock(final = true)
        if (held.isNotEmpty()) decide()
    }

    private fun runVad() {
        while (vadQueue.size >= SpeechTest.WINDOW) {
            val w = vadQueue.take(SpeechTest.WINDOW)
            if (!speech.isSpeech(w)) {
                silenceRun += w.size
                if (silenceRun >= resetSilence) { recent.clear(); recentSamples = 0 }
            } else {
                silenceRun = 0
                blockSpeech += w.size
                if (print == null && MicRecorder.level(w) >= near) {
                    blockNear += w.size
                    learning.add(w)
                    if (learning.size >= learnSamples) learn()
                }
                recent.addLast(w); recentSamples += w.size
                while (recentSamples - recent.first().size >= windowSamples) recentSamples -= recent.removeFirst().size
            }
        }
    }

    /** The first voice heard close up becomes the one to keep. */
    private fun learn() {
        val all = learning.take(learning.size)
        print = runCatching { embed(all) }.getOrNull() ?: return
        onLearned?.invoke()
    }

    private fun closeBlock(final: Boolean = false) {
        val b = Block(blockPcm.toByteArray(), blockSpeech, blockNear)
        blockPcm = java.io.ByteArrayOutputStream(); blockFilled = 0; blockSpeech = 0; blockNear = 0
        if (b.speech == 0 && held.isEmpty()) { emit(b.pcm); return }
        held += b
        // speech just began: hold on for a little more to judge it by, unless it's already over
        if (!final && b.speech > 0 && recentSamples < judgeSamples && held.size < maxHeld) return
        decide()
    }

    private fun decide() {
        val ref = print
        if (ref == null) {
            // no voice to compare with yet: speech at the phone is whoever started this, anything quieter isn't
            for (b in held) {
                val me = b.speech == 0 || b.near > 0
                onVerdict?.invoke(null, me)
                emit(if (me) b.pcm else ByteArray(b.pcm.size))
            }
            held.clear()
            return
        }
        val score: Float?
        val me: Boolean
        if (recentSamples < leastSamples) { score = null; me = true } // too little to tell: let it through
        else {
            val all = FloatArray(recentSamples).also { var i = 0; for (r in recent) { r.copyInto(it, i); i += r.size } }
            score = runCatching { Voiceprint.similarity(embed(all), ref) }.getOrNull()
            me = score == null || score >= threshold - (if (lastMe) leeway else 0f)
        }
        lastMe = me
        onVerdict?.invoke(score, me)
        for (b in held) emit(if (me) b.pcm else ByteArray(b.pcm.size))
        held.clear()
    }

    companion object {
        /** Speech at the phone taken as the first voice before others are judged against it. */
        const val LEARN_SECONDS = 1.5f
        /** A voice learned from a second and a half is a rougher likeness than a voiceprint: this much leeway. */
        const val FIRST_VOICE_EASE = 0.04f
    }
}

/**
 * Wraps a recognizer (ElevenLabs or Parakeet) with the voice filters that are on: children's
 * voices taken out ([ChildFilter]), and voices that aren't the user's ([VoiceGate]). If the speaker
 * model can't load, that part passes audio through unchanged.
 */
class VoiceFilterTranscriber(private val context: Context, private val inner: Transcriber, private val firstVoice: Boolean = false) : Transcriber {
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-voicefilter").apply { isDaemon = true } }
    private var gate: VoiceGate? = null
    private var vad: SileroSpeechTest? = null
    private var child: ChildFilterPcm? = null
    /** For the log: stretches the voiceprint judged, and how many it silenced. */
    private var judged = 0
    private var silenced = 0

    override fun start(listener: Transcriber.Listener) {
        inner.start(listener)
        exec {
            if (ChildVoices.enabled(context)) child = ChildFilterPcm(ChildFilter.childMin(ChildVoices.strength(context)))
            if (!Voiceprint.active(context)) return@exec
            val print = Voiceprint.load(context)
            if (print == null && !firstVoice) {
                com.ryderdavid.voicebar.AppLog.log("filter", "only my voice: no voiceprint saved and not started by hand, passing everything")
                return@exec
            }
            try {
                val t0 = android.os.SystemClock.uptimeMillis()
                Voiceprint.Engine.embed(context, FloatArray(8000)) // loads the model now, not on the first word
                val v = SileroSpeechTest(context).also { vad = it }
                // no voiceprint: the first voice at the phone is the one kept, judged with a little leeway
                val threshold = Voiceprint.threshold(Voiceprint.strength(context)) - if (print == null) VoiceGate.FIRST_VOICE_EASE else 0f
                val near = AutoListenGuard.nearThreshold(com.ryderdavid.voicebar.Prefs.get(context)
                    .getFloat(com.ryderdavid.voicebar.Prefs.NEAR_LEVEL, AutoListenGuard.DEFAULT_TYPICAL))
                if (print == null) com.ryderdavid.voicebar.AppLog.log("filter", "only my voice: no voiceprint, keeping the first voice at the phone (level ${"%.2f".format(near)}+)")
                // the speaker model is let go after a few idle minutes, so the first dictation in a
                // while loads it again: audio waits here meanwhile, and the log says how long for
                com.ryderdavid.voicebar.AppLog.log("filter", "only my voice: ready in ${android.os.SystemClock.uptimeMillis() - t0} ms")
                gate = VoiceGate(v, { Voiceprint.Engine.embed(context, it) }, print, threshold, emit = inner::send,
                    near = near,
                    onLearned = { com.ryderdavid.voicebar.AppLog.log("filter", "first voice learned: other voices are judged against it now") },
                    onVerdict = { score, me ->
                        judged++
                        if (!me) silenced++
                        val how = if (score == null) "too little speech to judge, or the first voice not learned yet and this " +
                            (if (me) "at the phone" else "quieter than talking at the phone")
                            else "similarity ${"%.2f".format(score)}, needs ${"%.2f".format(threshold)}"
                        // the first judgement either way (a cold model getting the first stretch
                        // wrong would silence the start of the dictation), then only what it silences
                        if (judged == 1) com.ryderdavid.voicebar.AppLog.log("filter",
                            "first stretch ${if (me) "kept" else "silenced"} ($how)")
                        else if (!me) com.ryderdavid.voicebar.AppLog.log("filter", "silenced a stretch: not your voice ($how)")
                    })
            } catch (t: Throwable) {
                logW("load", t)
            }
        }
    }

    /** Children's voices out first (instant), then anyone who isn't the user (the voiceprint), then the recognizer. */
    override fun send(pcm: ByteArray) = exec {
        val clean = child?.process(pcm) ?: pcm
        if (clean.isEmpty()) return@exec
        gate?.accept(clean) ?: inner.send(clean)
    }

    override fun commit() = exec { drainChild(); gate?.flush(); inner.commit() }

    private fun drainChild() {
        val tail = child?.flush() ?: return
        gate?.accept(tail) ?: inner.send(tail)
    }

    override fun finish(onDone: () -> Unit) {
        exec { drainChild(); gate?.flush(); close(); inner.finish(onDone) }
        work.shutdown()
    }

    override fun cancel() {
        inner.cancel()
        exec { close() }
        work.shutdown()
    }

    private fun close() {
        val parts = listOfNotNull(
            child?.let { "children's voices silenced ${"%.1f".format(it.filter.silencedFrames * ChildFilter.HOP / 16000f)} s" },
            gate?.let { "only my voice silenced $silenced of $judged stretches" },
        )
        if (parts.isNotEmpty()) com.ryderdavid.voicebar.AppLog.log("filter", parts.joinToString("; "))
        vad?.close(); vad = null; gate = null; child = null
        Voiceprint.Engine.done()
    }

    private fun exec(block: () -> Unit) {
        if (work.isShutdown) return
        runCatching { work.execute { runCatching(block).onFailure { logW("work", it) } } }
    }

    companion object {
        /** [t] filtered when "Ignore children's voices" is on, or "Only my voice" is on and set up. */
        fun wrap(context: Context, t: Transcriber, firstVoice: Boolean = false): Transcriber {
            val children = ChildVoices.enabled(context)
            val mine = Voiceprint.active(context) && (firstVoice || Voiceprint.has(context))
            com.ryderdavid.voicebar.AppLog.log("filter", "recognizer ${if (t is ParakeetTranscriber) "Parakeet" else "ElevenLabs"}; " +
                "children's voices ${if (children) "on (strength ${ChildVoices.strength(context)})" else "off"}; " +
                "only my voice ${if (mine) "on (strength ${Voiceprint.strength(context)})" else "off"}")
            return if (children || mine) VoiceFilterTranscriber(context.applicationContext, t, firstVoice) else t
        }
    }
}
