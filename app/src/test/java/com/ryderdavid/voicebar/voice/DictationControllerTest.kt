package com.ryderdavid.voicebar.voice

import kotlin.test.Test
import kotlin.test.assertEquals

class DictationControllerTest {
    private val events = mutableListOf<String>()
    private val ui = object : DictationController.Ui {
        override fun onListening() { events.add("listening") }
        override fun onLevel(level: Float) {}
        override fun onLiveText(text: String) { events.add("live:$text") }
        override fun onCleaning(raw: String) { events.add("cleaning:$raw") }
        override fun onResult(text: String) { events.add("result:$text") }
        override fun onIdle() { events.add("idle") }
        override fun onError(message: String) { events.add("error:$message") }
        override fun onNotCleaned(why: String) { events.add("notCleaned:$why") }
    }

    private class FakeAudio(var ok: Boolean = true) : AudioSource {
        var chunk: ((ByteArray) -> Unit)? = null
        var running = false
        override fun start(onChunk: (ByteArray) -> Unit, onLevel: (Float) -> Unit): Boolean {
            chunk = onChunk; running = ok; return ok
        }
        override fun stop() { running = false }
    }

    private class FakeTranscriber : Transcriber {
        lateinit var listener: Transcriber.Listener
        var sent = 0
        var cancelled = false
        var finalText: String? = null
        override fun start(listener: Transcriber.Listener) { this.listener = listener }
        override fun send(pcm: ByteArray) { sent++ }
        override fun finish(onDone: () -> Unit) { finalText?.let { listener.onCommitted(it) }; onDone() }
        override fun cancel() { cancelled = true }
        var commits = 0
        /** A mid-session commit: the current partial comes back as committed text. */
        var commitText: String? = null
        override fun commit() { commits++; commitText?.let { listener.onCommitted(it) } }
    }

    private val audio = FakeAudio()
    private val transcriber = FakeTranscriber()
    private var cleaner: Cleaner? = null
    private var key = true

    // everything runs inline: "main" and "background" are the test thread
    private val controller = DictationController(
        audio, { if (key) transcriber else null }, { cleaner }, ui,
        main = { it.run() }, background = { it.run() },
    )

    /** Records what each cleanup was given, and answers from a script. */
    private class Recorder(val answer: (String, String) -> String) : Cleaner {
        val calls = mutableListOf<Pair<String, String>>()
        override fun clean(raw: String) = clean("", raw)
        override fun clean(cleaned: String, raw: String): String { calls.add(cleaned to raw); return answer(cleaned, raw) }
    }

    @Test fun spokenFormattingReachesTheFieldWithOrWithoutCleanup() {
        // no cleanup: the phrases still become formatting
        controller.start()
        transcriber.listener.onPartial("watched title case the lord of the rings")
        assertEquals("live:watched The Lord of the Rings", events.last(), "shown as it's said")
        transcriber.finalText = "watched title case the lord of the rings end title case and said quote wow end quote"
        transcriber.listener.onPartial("")
        controller.stop(cancel = false)
        assertEquals("result:watched The Lord of the Rings and said \"wow\"", events.first { it.startsWith("result:") })
        // with cleanup: the model gets (and keeps) the tags; the field gets the formatting
        events.clear()
        val rec = Recorder { _, raw -> raw.replaceFirstChar { it.uppercase() } + "." }
        cleaner = rec
        controller.start()
        transcriber.finalText = "she said quote it's long end quote"
        controller.stop(cancel = false)
        assertEquals("she said <quote> it's long </quote>", rec.calls.single().second)
        assertEquals("result:She said \"it's long\".", events.first { it.startsWith("result:") })
    }

    @Test fun aPauseCleansWhatWasSaidAndKeepsListening() {
        val rec = Recorder { _, raw -> if (raw.startsWith("meet")) "Meet on Tuesday." else "?" }
        cleaner = rec
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on tuesday")
        transcriber.commitText = "meet on tuesday"
        controller.pause()
        assertEquals(1, transcriber.commits)
        assertEquals(listOf("" to "meet on tuesday"), rec.calls)
        assertEquals("live:Meet on Tuesday.", events.last())
        assertEquals(DictationController.State.LISTENING, controller.state)
    }

    @Test fun theNextCleanupGetsTheCleanedTextAndTheNewRawWordsMarkedApart() {
        val rec = Recorder { cleaned, raw -> if (cleaned.isEmpty()) "Meet on Tuesday." else "Meet on Wednesday." }
        cleaner = rec
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on tuesday")
        transcriber.commitText = "meet on tuesday"
        controller.pause()
        transcriber.listener.onPartial("no wait wednesday")
        assertEquals("live:Meet on Tuesday. no wait wednesday", events.last())
        transcriber.finalText = "no wait wednesday"
        controller.stop(cancel = false)
        assertEquals(listOf("" to "meet on tuesday", "Meet on Tuesday." to "no wait wednesday"), rec.calls)
        assertEquals(listOf("cleaning:Meet on Tuesday. no wait wednesday", "result:Meet on Wednesday.", "idle"), events.takeLast(3))
    }

    @Test fun nothingSaidAfterThePauseFinishesWithoutAnotherRequest() {
        val rec = Recorder { _, _ -> "Meet on Tuesday." }
        cleaner = rec
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on tuesday")
        transcriber.commitText = "meet on tuesday"
        controller.pause()
        controller.stop(cancel = false)
        assertEquals(1, rec.calls.size, "the pause's cleanup is the result")
        assertEquals(listOf("result:Meet on Tuesday.", "idle"), events.takeLast(2))
    }

    @Test fun aSecondPauseWithNothingNewDoesNothing() {
        val rec = Recorder { _, _ -> "Hi." }
        cleaner = rec
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("hi")
        transcriber.commitText = "hi"
        controller.pause()
        controller.pause()
        assertEquals(1, rec.calls.size)
    }

    @Test fun withoutCleanupAPauseDoesNothing() {
        cleaner = null
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("hello there")
        transcriber.commitText = "hello there"
        controller.pause()
        assertEquals(0, transcriber.commits)
        assertEquals("live:hello there", events.last())
    }

    @Test fun aCleanupThatFailsKeepsTheRawWords() {
        cleaner = Recorder { _, _ -> throw RuntimeException("offline") }
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on tuesday")
        transcriber.commitText = "meet on tuesday"
        controller.pause()
        transcriber.finalText = "and bring snacks"
        controller.stop(cancel = false)
        assertEquals(listOf("notCleaned:RuntimeException: offline", "result:meet on tuesday and bring snacks", "idle"), events.takeLast(3))
    }

    @Test fun newWordsAreHeardButAPauseCleanupIsNot() {
        var heard = 0
        val c = DictationController(
            audio, { transcriber }, { Recorder { _, _ -> "One two three four five." } },
            object : DictationController.Ui by ui { override fun onHeard() { heard++ } },
            main = { it.run() }, background = { it.run() },
        )
        c.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("one two")
        transcriber.listener.onPartial("one two three")
        assertEquals(2, heard)
        transcriber.commitText = "one two three"
        c.pause() // the committed text and the cleaned text don't count as new speech
        assertEquals(2, heard)
    }

    @Test fun liveTextThenCleanedResult() {
        cleaner = object : Cleaner { override fun clean(raw: String) = "Meet on Wednesday." }
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on")
        transcriber.listener.onPartial("meet on tuesday no wait")
        transcriber.finalText = "meet on tuesday no wait wednesday"
        controller.stop(cancel = false)
        assertEquals(1, transcriber.sent)
        assertEquals(
            listOf(
                "listening", "live:meet on", "live:meet on tuesday no wait",
                "live:meet on tuesday no wait wednesday",
                "cleaning:meet on tuesday no wait wednesday", "result:Meet on Wednesday.", "idle",
            ),
            events,
        )
        assertEquals(DictationController.State.IDLE, controller.state)
    }

    @Test fun aNewHoldWhilePolishingIsRefused() {
        var finishCleaning: Runnable? = null
        val c = DictationController(
            audio, { transcriber }, { object : Cleaner { override fun clean(raw: String) = "Done." } }, ui,
            main = { it.run() }, background = { finishCleaning = it },
        )
        c.start()
        transcriber.finalText = "first"
        c.stop(cancel = false)
        assertEquals(DictationController.State.CLEANING, c.state)
        assertEquals(false, c.start())
        transcriber.listener.onPartial("late") // ignored while polishing
        finishCleaning!!.run()
        assertEquals(listOf("result:Done.", "idle"), events.takeLast(2))
        assertEquals(0, events.count { it == "live:first late" })
    }

    @Test fun aBoundaryLimitsLiveTextAndCleanupToWhatFollows() {
        val cleaned = mutableListOf<String>()
        cleaner = object : Cleaner { override fun clean(raw: String): String { cleaned.add(raw); return raw.uppercase() } }
        controller.start()
        transcriber.listener.onPartial("first part")
        assertEquals(true, controller.markBoundary(2))
        transcriber.listener.onPartial("first part second part")
        assertEquals("live:second part", events.last())
        transcriber.finalText = "first part second part done"
        controller.stop(cancel = false)
        assertEquals(listOf("second part done"), cleaned)
        assertEquals("result:SECOND PART DONE", events[events.size - 2])
    }

    @Test fun noBoundaryOnceFinishing() {
        controller.start()
        transcriber.finalText = "words"
        cleaner = object : Cleaner { override fun clean(raw: String) = raw }
        assertEquals(true, controller.dictating)
        controller.stop(cancel = false)
        assertEquals(false, controller.markBoundary(1))
    }

    @Test fun cleanupFailureTypesTheRawText() {
        cleaner = object : Cleaner { override fun clean(raw: String): String = throw java.io.IOException("offline") }
        controller.start()
        transcriber.finalText = "hello there"
        controller.stop(cancel = false)
        assertEquals(listOf("notCleaned:IOException: offline", "result:hello there", "idle"), events.takeLast(3))
    }

    @Test fun aCleanerThatDeclinesSaysWhy() {
        cleaner = object : Cleaner {
            override fun clean(raw: String): String? = null
            override val problem: String get() = "Claude answered 529: Overloaded"
        }
        controller.start()
        transcriber.finalText = "hello there"
        controller.stop(cancel = false)
        assertEquals(listOf("notCleaned:Claude answered 529: Overloaded", "result:hello there", "idle"), events.takeLast(3))
    }

    @Test fun withoutCleanupTheRawTextIsTyped() {
        controller.start()
        transcriber.listener.onCommitted("first part")
        transcriber.finalText = "second part"
        controller.stop(cancel = false)
        assertEquals(listOf("result:first part second part", "idle"), events.takeLast(2))
    }

    @Test fun cancelThrowsItAway() {
        controller.start()
        transcriber.listener.onPartial("never mind")
        controller.stop(cancel = true)
        assertEquals(true, transcriber.cancelled)
        assertEquals(false, audio.running)
        assertEquals("idle", events.last())
        assertEquals(0, events.count { it.startsWith("result") })
        transcriber.listener.onCommitted("late") // a late message after cancel is ignored
        assertEquals("idle", events.last())
    }

    @Test fun silenceTypesNothing() {
        controller.start()
        controller.stop(cancel = false)
        assertEquals(listOf("listening", "idle"), events)
    }

    @Test fun missingKeyExplains() {
        key = false
        controller.start()
        assertEquals(listOf("error:Set up speech recognition in Voicebar settings: an ElevenLabs key, or Parakeet on the phone"), events)
        assertEquals(DictationController.State.IDLE, controller.state)
    }

    @Test fun micFailureCancelsTheStream() {
        audio.ok = false
        controller.start()
        assertEquals(true, transcriber.cancelled)
        assertEquals(listOf("error:Couldn't open the microphone"), events)
    }

    @Test fun streamErrorStopsListening() {
        controller.start()
        transcriber.listener.onError("ElevenLabs quota used up")
        assertEquals("error:ElevenLabs quota used up", events.last())
        assertEquals(DictationController.State.IDLE, controller.state)
        assertEquals(false, audio.running)
    }

    @Test fun aSpokenCommandIsCutFromTheEndBeforeFinishing() {
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("running late, voice bar, send")
        controller.cutLast(3)
        assertEquals("live:running late,", events.last())
        transcriber.finalText = "running late, voice bar, send."
        controller.stop(cancel = false)
        assertEquals(listOf("result:running late", "idle"), events.takeLast(2))
    }

    @Test fun aCommandAloneTypesNothing() {
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("voice bar done")
        controller.cutLast(3)
        transcriber.finalText = "Voice bar done."
        controller.stop(cancel = false)
        assertEquals("idle", events.last())
        assertEquals(0, events.count { it.startsWith("result:") })
    }

    @Test fun cleanUpByVoiceCutsTheCommandThenPolishesAndKeepsListening() {
        val rec = Recorder { _, raw -> if (raw == "meet on tuesday") "Meet on Tuesday." else "?" }
        cleaner = rec
        controller.start()
        audio.chunk!!(ByteArray(10))
        transcriber.listener.onPartial("meet on tuesday voice bar clean up")
        controller.cutLast(4)
        transcriber.commitText = "meet on tuesday voice bar clean up"
        controller.pause()
        assertEquals(listOf("" to "meet on tuesday"), rec.calls)
        assertEquals("live:Meet on Tuesday.", events.last())
        transcriber.listener.onPartial("and bring snacks")
        assertEquals("live:Meet on Tuesday. and bring snacks", events.last())
    }
}
