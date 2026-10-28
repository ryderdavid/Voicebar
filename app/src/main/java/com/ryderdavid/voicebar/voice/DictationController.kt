package com.ryderdavid.voicebar.voice

import android.os.Handler
import android.os.Looper

/**
 * Hold-to-talk dictation: microphone -> streaming transcriber (live text) -> optional cleanup
 * model on the whole utterance -> one block of text to type.
 *
 * Cleanup runs in two stages. At a pause ([pause], from the silence countdown) what's been said so
 * far is committed and cleaned while listening goes on; the cleaned text replaces the raw words.
 * Later cleanups send the cleaned text and the raw words since, marked apart, so the model has the
 * whole context and spoken corrections that span the pause ("...on Tuesday. No, make that
 * Wednesday") still resolve. If nothing new was said after the last cleanup, finishing types the
 * cleaned text without another request.
 *
 * All [Ui] calls happen on the main thread.
 */
class DictationController(
    private val audio: AudioSource,
    /** A fresh transcriber per utterance, or null when no API key is set. */
    private val transcriberFactory: () -> Transcriber?,
    /** The cleanup model, or null to type the raw transcript. */
    private val cleanerFactory: () -> Cleaner?,
    private val ui: Ui,
    private val main: (Runnable) -> Unit = { Handler(Looper.getMainLooper()).post(it) },
    private val background: (Runnable) -> Unit = { Thread(it, "bubble-cleanup").start() },
) {
    interface Ui {
        fun onListening()
        fun onLevel(level: Float)
        fun onLiveText(text: String)
        fun onCleaning(raw: String)
        fun onResult(text: String)
        fun onIdle()
        fun onError(message: String)
        /** New words were recognized (speech, not noise): the silence countdown starts over. */
        fun onHeard() {}
        /** Just before [onResult]: the whole dictation as it was heard, before any cleanup. */
        fun onHeardAll(raw: String) {}
        /** The cleanup failed, so the words went in as spoken: [why] in a few words. */
        fun onNotCleaned(why: String) {}
        /** Spoken formatting still open as the words come in ("title case" said, not yet ended). */
        fun onFormatting(open: Set<SpokenFormat.Kind>) {}
        /** The recognizer is connected (or loaded) and hearing: the silence countdown starts from here. */
        fun onRecognizerReady() {}
    }

    enum class State { IDLE, LISTENING, FINISHING, CLEANING }

    var state = State.IDLE
        private set

    private var session = 0
    private var transcriber: Transcriber? = null
    private val committed = ArrayList<String>()
    private var partial = ""

    private fun fullText() = (committed + partial).filter { it.isNotBlank() }.joinToString(" ") { it.trim() }.trim()

    /** Words already typed for good (before a key press mid-dictation); what follows starts fresh. */
    private var skipWords = 0
    private fun words(s: String) = s.split(WHITESPACE).filter { it.isNotEmpty() }

    /** Text cleaned at a pause, and how many heard words (after [skipWords]) it stands for. */
    private var cleaned = ""
    private var cleanedWords = 0
    /** A pause asked for a commit; the cleanup runs when the committed text arrives. */
    private var pauseWaiting = false
    private var pauseCleaning = false
    private var pauseCleaner: Cleaner? = null
    /** Bumped when a boundary or a new dictation makes an in-flight pause cleanup stale. */
    private var generation = 0
    private var heardCount = 0

    /** Heard words (by position) taken out of the text: spoken commands. */
    private val cut = HashSet<Int>()

    private fun heard() = words(fullText()).withIndex().filter { it.index >= skipWords && it.index !in cut }.map { it.value }

    /**
     * Takes the last [n] heard words out of the text (a spoken command), and shows what's left.
     * False when not listening.
     */
    fun cutLast(n: Int): Boolean {
        if (state != State.LISTENING) return false
        val all = words(fullText()).indices.filter { it >= skipWords && it !in cut }
        all.takeLast(n).forEach { cut.add(it) }
        ui.onLiveText(liveText())
        return true
    }
    private fun rawTail() = heard().drop(cleanedWords).joinToString(" ")
    /** What's been said, with spoken formatting as tags ([SpokenFormat]): cleaned text keeps its tags until the end. */
    private fun taggedText(): String = listOf(cleaned, SpokenFormat.mark(rawTail())).filter { it.isNotBlank() }.joinToString(" ")
    /** What the field shows as you talk: the formatting as said so far. */
    private fun liveText(): String {
        val tagged = taggedText()
        ui.onFormatting(SpokenFormat.open(tagged))
        return SpokenFormat.render(tagged, final = false)
    }

    private fun noteHeard() {
        val n = words(fullText()).size
        if (n > heardCount) ui.onHeard()
        heardCount = n
    }

    /**
     * [typedWords] more words are typed for good (the user pressed a key, or the draft ended):
     * from here, live text and the cleanup cover only what follows them. False once finishing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun markBoundary(typedWords: Int): Boolean {
        if (state != State.LISTENING) return false
        // everything heard so far is in the field now, cleaned or not
        skipWords = words(fullText()).size
        cleaned = ""
        cleanedWords = 0
        pauseWaiting = false
        generation++
        return true
    }

    /**
     * A pause while listening: commit what's been said and clean it now, keeping the microphone
     * open. Does nothing without cleanup, or when there's nothing new since the last cleanup.
     */
    fun pause() {
        if (state != State.LISTENING || pauseWaiting || pauseCleaning) return
        if (heard().size <= cleanedWords && partial.isBlank()) return
        // no cleanup (off, or no key): leave the transcript alone, and don't commit for nothing
        pauseCleaner = cleanerFactory() ?: return
        if (partial.isNotBlank()) {
            pauseWaiting = true
            transcriber?.commit()
            // the commit's text arrives through onCommitted, which runs the cleanup
        } else cleanAtPause()
    }

    private fun cleanAtPause() {
        pauseWaiting = false
        if (state != State.LISTENING) return
        val all = heard()
        val upTo = all.size
        val raw = SpokenFormat.mark(joined(cleaned, all.drop(cleanedWords).joinToString(" ")))
        if (raw.isBlank()) return
        val cleaner = pauseCleaner ?: return
        val id = session
        val gen = generation
        val before = cleaned
        pauseCleaning = true
        background(Runnable {
            val t0 = System.currentTimeMillis()
            com.ryderdavid.voicebar.AppLog.log("cleanup", "at a pause, start with ${cleaner.service}: " + com.ryderdavid.voicebar.AppLog.excerpt(raw))
            val attempt = runCatching { cleaner.cleanAtPause(before, raw) }
            val result = attempt.getOrNull()?.takeIf { it.isNotBlank() }
            val why = if (result != null) null
                else attempt.exceptionOrNull()?.let { cleanupFailure(it, cleaner.service) } ?: cleaner.problem ?: "no reply from ${cleaner.service}"
            com.ryderdavid.voicebar.AppLog.log("cleanup", "at a pause, " + (if (why == null) "ok" else "failed ($why)") + " in ${System.currentTimeMillis() - t0} ms" +
                (result?.let { ": " + com.ryderdavid.voicebar.AppLog.excerpt(it) } ?: ""))
            main(Runnable {
                if (id != session) return@Runnable
                pauseCleaning = false
                if (result != null && (gen != generation || state != State.LISTENING))
                    com.ryderdavid.voicebar.AppLog.log("cleanup", "at a pause, not used: the dictation had moved on")
                if (gen != generation || result == null || state != State.LISTENING) return@Runnable
                cleaned = result
                cleanedWords = upTo
                ui.onLiveText(liveText())
            })
        })
    }

    /** Starts listening; false when it couldn't (busy finishing the last one, no key, no mic). */
    fun start(): Boolean {
        if (state != State.IDLE) return false
        val t = transcriberFactory() ?: return false.also { ui.onError("Set up speech recognition in Voicebar settings: an ElevenLabs key, or Parakeet on the phone") }
        val id = ++session
        committed.clear()
        partial = ""
        cut.clear()
        skipWords = 0
        cleaned = ""
        cleanedWords = 0
        pauseWaiting = false
        pauseCleaning = false
        heardCount = 0
        generation++
        transcriber = t
        t.start(object : Transcriber.Listener {
            override fun onPartial(text: String) = main(Runnable {
                if (id != session || (state != State.LISTENING && state != State.FINISHING)) return@Runnable
                partial = text
                noteHeard()
                ui.onLiveText(liveText())
            })

            override fun onCommitted(text: String) = main(Runnable {
                if (id != session || (state != State.LISTENING && state != State.FINISHING)) return@Runnable
                committed.add(text)
                partial = ""
                noteHeard()
                ui.onLiveText(liveText())
                if (pauseWaiting && state == State.LISTENING) cleanAtPause()
            })

            override fun onReady() = main(Runnable { if (id == session && state == State.LISTENING) ui.onRecognizerReady() })

            override fun onError(message: String) = main(Runnable {
                if (id != session || state == State.IDLE) return@Runnable
                audio.stop()
                transcriber?.cancel()
                state = State.IDLE
                ui.onError(message)
            })
        })
        val ok = audio.start(
            onChunk = { pcm -> if (id == session) t.send(pcm) },
            onLevel = { level -> main(Runnable { if (id == session && state == State.LISTENING) ui.onLevel(level) }) },
        )
        if (!ok) {
            t.cancel()
            ui.onError("Couldn't open the microphone")
            return false
        }
        state = State.LISTENING
        ui.onListening()
        return true
    }

    /** The finger lifted: finish and type the text, or throw it away when [cancel]. */
    fun stop(cancel: Boolean) {
        if (state != State.LISTENING) return
        audio.stop()
        val t = transcriber ?: return
        if (cancel) {
            session++
            t.cancel()
            state = State.IDLE
            ui.onIdle()
            return
        }
        val id = session
        state = State.FINISHING
        t.finish {
            main(Runnable {
                if (id != session || state != State.FINISHING) return@Runnable
                val before = cleaned
                // a spoken command cut from the end can leave the comma that led into it
                val raw = SpokenFormat.mark(joined(before, rawTail().trimEnd { it.isWhitespace() || it in LEAD_IN }))
                val all = listOf(before, raw).filter { it.isNotBlank() }.joinToString(" ")
                if (all.isBlank()) {
                    state = State.IDLE
                    ui.onIdle()
                    return@Runnable
                }
                // cleaned at the last pause and nothing said since: that's the result already
                if (raw.isBlank()) {
                    deliver(before)
                    return@Runnable
                }
                val cleaner = cleanerFactory()
                if (cleaner == null) {
                    com.ryderdavid.voicebar.AppLog.log("dictation", "no cleanup (off or no key): " + com.ryderdavid.voicebar.AppLog.excerpt(all))
                    deliver(all)
                    return@Runnable
                }
                state = State.CLEANING
                ui.onCleaning(SpokenFormat.render(all, final = false))
                background(Runnable {
                    // any failure or timeout falls back to what we have (cleaned part + raw words); nothing is lost
                    val t0 = System.currentTimeMillis()
                    com.ryderdavid.voicebar.AppLog.log("cleanup", "start with ${cleaner.service}: " + com.ryderdavid.voicebar.AppLog.excerpt(all))
                    val attempt = runCatching { cleaner.clean(before, raw) }
                    val cleanedText = attempt.getOrNull()?.takeIf { it.isNotBlank() }
                    val why = if (cleanedText != null) null
                        else attempt.exceptionOrNull()?.let { cleanupFailure(it, cleaner.service) } ?: cleaner.problem ?: "no reply from ${cleaner.service}"
                    com.ryderdavid.voicebar.AppLog.log("cleanup", (if (why == null) "ok" else "failed ($why)") + " in ${System.currentTimeMillis() - t0} ms" +
                        (cleanedText?.let { ": " + com.ryderdavid.voicebar.AppLog.excerpt(it) } ?: ""))
                    main(Runnable {
                        if (id == session && state == State.CLEANING) {
                            // first, so a send waiting on this dictation knows not to go
                            why?.let(ui::onNotCleaned)
                            deliver(cleanedText ?: all)
                        }
                    })
                })
            })
        }
    }

    private fun deliver(tagged: String) {
        runCatching { ui.onHeardAll(heard().joinToString(" ")) }
        state = State.IDLE
        ui.onFormatting(emptySet())
        // the spoken formatting, made real: quotes and parentheses that never closed go back to words
        ui.onResult(SpokenFormat.render(tagged, final = true))
        ui.onIdle()
    }

    val dictating get() = state != State.IDLE

    /** Drops anything in flight, e.g. when the text field goes away. */
    fun abort() {
        if (state == State.IDLE) return
        session++
        audio.stop()
        transcriber?.cancel()
        state = State.IDLE
        ui.onIdle()
    }

    internal companion object {
        val WHITESPACE = Regex("\\s+")
        const val LEAD_IN = ",;:-–—"

        /**
         * What's been said since [cleaned], ready to follow it: the recognizer capitalizes the first
         * word after every pause, which means nothing (the speaker paused to think), so it goes to
         * the cleanup in lowercase and the cleanup capitalizes it if it starts a sentence or is a
         * name. "I" (and "I'm"…) and acronyms keep theirs.
         */
        fun joined(cleaned: String, raw: String): String {
            if (cleaned.isBlank()) return raw
            val start = raw.indexOfFirst { !it.isWhitespace() }
            if (start < 0) return raw
            val word = raw.drop(start).takeWhile { it.isLetter() || it == '\'' || it == '’' }
            if (word.isEmpty() || !word[0].isUpperCase()) return raw
            if (word == "I" || word.startsWith("I'") || word.startsWith("I’")) return raw
            if (word.drop(1).any { it.isUpperCase() }) return raw
            return raw.substring(0, start) + raw[start].lowercaseChar() + raw.substring(start + 1)
        }
    }
}
