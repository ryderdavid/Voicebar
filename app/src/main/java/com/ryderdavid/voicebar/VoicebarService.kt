package com.ryderdavid.voicebar

import android.Manifest
import com.ryderdavid.voicebar.voice.ScreenContext
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.os.SystemClock
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.ryderdavid.voicebar.voice.ChatApps
import com.ryderdavid.voicebar.voice.ClaudeCleaner
import com.ryderdavid.voicebar.voice.CorrectionWatch
import com.ryderdavid.voicebar.voice.Corrections
import com.ryderdavid.voicebar.voice.Dictionary
import com.ryderdavid.voicebar.voice.LearnerRequest
import com.ryderdavid.voicebar.voice.ClaudeCleanupRequest
import com.ryderdavid.voicebar.voice.DictationController
import com.ryderdavid.voicebar.voice.ElevenLabsTranscriber
import com.ryderdavid.voicebar.voice.Transcriber
import com.ryderdavid.voicebar.voice.LiveField
import com.ryderdavid.voicebar.voice.LiveTyper
import com.ryderdavid.voicebar.voice.MicRecorder
import com.ryderdavid.voicebar.voice.Infill
import com.ryderdavid.voicebar.voice.Millennial
import com.ryderdavid.voicebar.voice.Secrets
import com.ryderdavid.voicebar.voice.VoiceCommands
import com.ryderdavid.voicebar.voice.WakeWord
import com.ryderdavid.voicebar.voice.UsageLog
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The input method: Voicebar's one-row voice bar ([DockView]), docked like any keyboard.
 * Typing is handed to Gboard (ABC); the bar is for dictation, the cursor pad and editing.
 */
class VoicebarService : InputMethodService(), Bar.Listener {

    /** The voice bar. */
    private var bar: KeyboardSurface? = null

    private val http by lazy {
        OkHttpClient.Builder()
            // a dead route fails over to the next quickly (OkHttp tries them one at a time)
            .connectTimeout(3, TimeUnit.SECONDS)
            // the cleanup call is short; never leave the user waiting long for it
            .callTimeout(8, TimeUnit.SECONDS)
            .pingInterval(15, TimeUnit.SECONDS)
            .build()
    }

    private val usage by lazy { UsageLog.of(this) }

    private fun pickTranscriber(): Transcriber? = run {
                // on the phone when Parakeet is chosen and downloaded (the screen is still read, for the cleanup)
                if (com.ryderdavid.voicebar.voice.Parakeet.inUse(this)) { readScreen(); com.ryderdavid.voicebar.voice.ParakeetTranscriber(this) }
                else Secrets.get(this, Secrets.ELEVENLABS)?.let {
                    val useTerms = Prefs.get(this).getBoolean(Prefs.DICT_KEYTERMS, Prefs.DEFAULT_DICT_KEYTERMS)
                    val terms = if (useTerms) Dictionary.of(this).keyterms() else emptyList()
                    // the screen is read for the cleanup only: given to the recognizer (as names to listen
                    // for or as words "just said") it made it hear things that were never said and keep
                    // rewriting its live text. The recognizer gets the dictionary and, at most, the last
                    // sentence you typed in this box.
                    readScreen()
                    val lead = aroundCursor?.before?.let { ScreenContext.leadIn(it) }
                    ElevenLabsTranscriber(it, http, onAudio = usage::addAudio, keyterms = terms,
                        context = if (lead == null) null else ({ ElevenLabsTranscriber.Context(terms, lead) }))
                }
    }

    private val dictation: DictationController by lazy {
        DictationController(
            audio = MicRecorder(),
            // started by hand, whoever's talking at the phone first is taken as the user (Only my voice without a voiceprint)
            transcriberFactory = { pickTranscriber()?.let { com.ryderdavid.voicebar.voice.VoiceFilterTranscriber.wrap(this, it, firstVoice = !autoStartPending) } },
            cleanerFactory = {
                val polish = forcePolish
                forcePolish = false
                newCleaner(polish, forField = true)
            },
            ui = object : DictationController.Ui {
                override fun onListening() {
                    micChunks = 0
                    micPeak = 0f
                    dismissedWords = 0
                    // a new dictation starts empty: the last one's words must never be typed again
                    lastLiveText = ""
                    restorePeriodIfFollowingUp()
                    if (liveTyping) liveTyper.begin()
                    bar?.showListening()
                }
                override fun onLevel(level: Float) {
                    bar?.showLevel(level)
                    // the first 2 s (20 chunks), for the log: did the microphone hear anything at all?
                    if (micChunks < MIC_CHECK_CHUNKS) {
                        micPeak = maxOf(micPeak, level)
                        if (++micChunks == MIC_CHECK_CHUNKS) AppLog.log("mic", "first 2 s: peak level ${"%.2f".format(micPeak)}" +
                            if (micPeak <= 0f) " (nothing above -50 dBFS: the mic may be muted or taken)" else "")
                    }
                    if (!guard.auto) { if (manualLevels.size < 4000) manualLevels += level }
                    // talked at the phone: what was heard so far goes into the field now
                    else if (guard.level(level, SystemClock.uptimeMillis())) {
                        AppLog.log("guard", "engaged (talking at the phone): " + AppLog.excerpt(lastLiveText))
                        // the words the pill was keeping go into the field now; the pill goes back to the waveform
                        bar?.voiceInField = guard.typesLive(liveTyping)
                        if (liveTyping && lastLiveText.isNotBlank()) liveTyper.live(livePending(lastLiveText)?.before ?: lastLiveText)
                    }
                }
                // only a newly recognized word counts as speech for the silence countdown, never mere
                // noise (the controller counts raw words, so a pause cleanup changing the text doesn't count)
                override fun onHeard() { bar?.heardSpeech() }
                // a slow first connection (or Parakeet loading) mustn't use up the silence countdown:
                // it starts once the recognizer can actually hear
                override fun onRecognizerReady() { bar?.heardSpeech(); bar?.showReady() }
                override fun onLiveText(text: String) {
                    // "voice bar …": what follows goes in the pill, not the field
                    lastLiveText = text
                    val pending = livePending(text)
                    pendingCommand = pending
                    val shown = pending?.before ?: text
                    if (guard.typesLive(liveTyping)) liveTyper.live(shown) else bar?.showLiveText(shown)
                    bar?.showCommand(pending?.said, pending?.command != null)
                    watchCommands(text)
                }
                override fun onCleaning(raw: String) {
                    if (liveTyping) liveTyper.live(raw) // the draft holds the raw words while they're polished
                    bar?.showCleaning(raw)
                }
                override fun onHeardAll(raw: String) { heardAll = raw }
                override fun onResult(text: String) {
                    delivered = true
                    // every dictation goes in the history, as heard and as cleaned, wherever it ends up
                    val app = currentInputEditorInfo?.packageName?.let { pkg ->
                        runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
                    }.orEmpty()
                    DictationHistory.of(this@VoicebarService).add(heardAll ?: text, Dictionary.of(this@VoicebarService).applySure(text).trim(), app)
                    heardAll = null
                    bar?.dictations = DictationHistory.of(this@VoicebarService).all()
                    AppLog.log("result", "heard " + AppLog.excerpt(heardAll ?: text) + " → " + AppLog.excerpt(text) +
                        " toClipboard=$toClipboard field=${hasTextField()} hold=${guard.hold(endedBySilence)} live=$liveTyping")
                    if (!hasTextField()) {
                        // nowhere to type it right now: try to get back to the text box, and only then
                        // put the words on the clipboard, to paste wherever they're wanted
                        val words = Dictionary.of(this@VoicebarService).applySure(text).trim()
                        val toBox = { toClipboard = false; deliver(text) }
                        val toClip = {
                            sendAfterTalk = false
                            if (words.isNotEmpty() && copyDictation(words)) bar?.showMessage(getString(R.string.dictation_copied))
                        }
                        if (askForField()) awaitField(REGAIN_FINISH_MS, toBox, toClip) else toClip()
                        return
                    }
                    deliver(text)
                }

                /** The finished dictation into the text box (there is one). */
                private fun deliver(text: String) {
                    // fitted to its spot when it goes into the middle of text: capitals, the closing mark, leftover words
                    val fit = Infill.fit(Dictionary.of(this@VoicebarService).applySure(text), aroundCursor, fieldCleaner?.seam ?: Infill.Seam())
                    if (fit.notes.isNotEmpty()) AppLog.log("infill", fit.notes.joinToString("; "))
                    // millennial mode is for the end of a message, not a fix in the middle of one
                    val final = if (aroundCursor?.inside == true) fit.text else finishMessage(fit.text)
                    if (guard.hold(endedBySilence)) {
                        // auto-started in a chat or email app and it ended by itself: it waits for a tap
                        sendAfterTalk = false
                        if (liveTyping) liveTyper.cancel()
                        heldShown = true
                        bar?.showHeld(getString(R.string.held_tap_to_add, final)) {
                            heldShown = false
                            typeDictation(final)
                            removeLeftovers(final, fit)
                            if (aroundCursor?.inside == true) spaceAfterInsert(final)
                        }
                        return
                    }
                    // watch for hand corrections to it (never in password fields)
                    if (learnOn && fieldTakesLiveText(currentInputEditorInfo))
                        CorrectionWatch.start(final, currentInputEditorInfo?.packageName, fieldKey)
                    lastDelivered = final
                    lastDeliveredAt = SystemClock.uptimeMillis()
                    if (liveTyping) liveTyper.result(final) else typeDictation(final)
                    removeLeftovers(final, fit)
                    if (aroundCursor?.inside == true) spaceAfterInsert(final)
                    rememberStripped(final)
                }
                override fun onFormatting(open: Set<com.ryderdavid.voicebar.voice.SpokenFormat.Kind>) {
                    bar?.showTitleCase(com.ryderdavid.voicebar.voice.SpokenFormat.Kind.TITLE in open)
                }
                override fun onNotCleaned(why: String) {
                    CleanupProblem.save(this@VoicebarService, why)
                    // swiped to send: an uncleaned message isn't sent; it's typed for a look first
                    val held = sendAfterTalk
                    if (held) { sendAfterTalk = false; AppLog.log("send", "not sent: the cleanup failed ($why)") }
                    bar?.showMessage(getString(if (held) R.string.not_cleaned_not_sent else R.string.not_cleaned, why))
                }
                override fun onIdle() {
                    (bar as? View)?.post { updateWake() }
                    if (!guard.auto && manualLevels.isNotEmpty()) {
                        val p = Prefs.get(this@VoicebarService)
                        val old = p.getFloat(Prefs.NEAR_LEVEL, com.ryderdavid.voicebar.voice.AutoListenGuard.DEFAULT_TYPICAL)
                        p.edit().putFloat(Prefs.NEAR_LEVEL, com.ryderdavid.voicebar.voice.AutoListenGuard.learnTypical(old, manualLevels)).apply()
                        manualLevels.clear()
                    }
                    if (sendAfterTalk) {
                        sendAfterTalk = false
                        // after the text is in and the app has seen it (its Send may enable only then)
                        (bar as? View)?.postDelayed({ send() }, SEND_DELAY_MS)
                    }
                    if (hideAfterTalk) {
                        hideAfterTalk = false
                        (bar as? View)?.postDelayed({ requestHideSelf(0) }, SEND_DELAY_MS)
                    }
                    if (gboardAfterTalk) {
                        gboardAfterTalk = false
                        // after the text is in: let the field settle, then hand over
                        (bar as? View)?.post { onSwitchToGboard() }
                    }
                    if (liveTyping) liveTyper.end()
                    if (!delivered) takeBackRestoredPeriod()
                    bar?.talkEnded()
                    if (bar?.voice != Bar.Voice.MESSAGE) bar?.hideVoice()
                }
                override fun onError(message: String) {
                    AppLog.log("error", message)
                    hideAfterTalk = false
                    (bar as? View)?.post { updateWake() }
                    gboardAfterTalk = false
                    sendAfterTalk = false
                    if (liveTyping) liveTyper.end() // keep what was typed so far
                    takeBackRestoredPeriod()
                    bar?.talkEnded()
                    bar?.showMessage(message)
                }
            },
        )
    }

    // ---------------------------------------------------------------- millennial mode

    /** The end of the last message we typed without its final period, to recognize a follow-up. */
    private var strippedTail: String? = null
    /** This dictation put the previous message's period back (taken back if nothing gets typed). */
    private var restoredPeriod = false
    /** This dictation typed its result. */
    private var delivered = false

    /** Millennial mode, for this field: everywhere, or where it's checked (search boxes, chats). */
    private val millennial get() = Prefs.millennial(this).let { m ->
        m.everywhere || (m.chats && ChatApps.isChat(currentInputEditorInfo)) || (m.search && ChatApps.isSearch(currentInputEditorInfo))
    }

    // ---------------------------------------------------------------- learning from hand corrections

    private val learnOn get() = Prefs.get(this).getBoolean(Prefs.LEARN, Prefs.DEFAULT_LEARN)

    /** A "Learned …" note waiting for the bar to show again (the correction was made in Gboard). */
    private var learnedNote: Pair<String, () -> Unit>? = null

    /** The current field's text, through the keyboard if it's active, else what accessibility sees. */
    /** The screen's text for the dictation under way, being read in the background. */
    private var screenNow: java.util.concurrent.Future<ScreenContext.Snapshot?>? = null

    /**
     * Starts reading the screen for a dictation that's starting: the app's text through the Gboard
     * companion (accessibility) when it's on, otherwise just the text box. Null when it's off.
     */
    private fun readScreen(): java.util.concurrent.Future<ScreenContext.Snapshot?>? {
        screenNow = null
        if (!Prefs.get(this).getBoolean(Prefs.SCREEN_CONTEXT, Prefs.DEFAULT_SCREEN_CONTEXT)) return null
        val companion = GboardButtonService.running
        // without the companion, the field is all there is (read here: the connection is ours)
        val field = if (companion == null) readField()?.trim() else null
        val task = java.util.concurrent.FutureTask {
            companion?.screenText() ?: field?.takeIf { it.isNotEmpty() }?.let { ScreenContext.build(listOf(ScreenContext.Piece(it, 0, 0, field = true))) }
        }
        Thread(task, "voicebar-screen").apply { isDaemon = true }.start()
        return task.also { screenNow = it }
    }

    private var speechWarmedAt = 0L
    /** This keyboard connection's "not listening by itself" note has been shown. */
    private var keyboardNoted = false
    private val heldProbe by lazy { HeldProbe(this) }
    /** The microphone's first moments in this dictation: how many chunks, and the loudest. */
    private var micChunks = 0
    private var micPeak = 0f

    /**
     * The keyboard opened: reach ElevenLabs now (a free request), so the phone's radio is awake and
     * the address and secure connection are sorted before Voice is tapped. After a while idle, the
     * first connection can stall on a dead route; this takes that wait (and OkHttp remembers the
     * route that failed), not the dictation. At most once a minute.
     */
    private fun warmSpeechService() {
        if (com.ryderdavid.voicebar.voice.Parakeet.inUse(this) || !Secrets.has(this, Secrets.ELEVENLABS)) return
        val now = SystemClock.uptimeMillis()
        if (now - speechWarmedAt < SPEECH_WARM_EVERY_MS) return
        speechWarmedAt = now
        val request = okhttp3.Request.Builder().url("https://api.elevenlabs.io/").head().build()
        http.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                response.close()
                val ms = SystemClock.uptimeMillis() - now
                if (ms > SPEECH_WARM_SLOW_MS) AppLog.log("speech", "warm-up reached ElevenLabs in $ms ms (slow network)")
            }
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                AppLog.log("speech", "warm-up couldn't reach ElevenLabs after ${SystemClock.uptimeMillis() - now} ms: ${e.javaClass.simpleName}")
            }
        })
    }

    private fun readField(): String? =
        runCatching { currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = 5000 }, 0)?.text?.toString() }.getOrNull()
            ?: GboardButtonService.running?.focusedText()

    /** Compares the field with what was typed; corrections go to Claude to judge, in the background. */
    private fun checkCorrections(p: CorrectionWatch.Pending, field: String): Boolean {
        // re-capitalized words need no judging: how the user writes them, kept once it's happened twice
        val casings = Corrections.casings(p.text, field).take(3)
        casings.forEach { (typed, fixed) -> learnedCasing(typed, fixed) }
        val changes = Corrections.find(p.text, field).take(3)
        if (changes.isEmpty()) return casings.isNotEmpty()
        val key = Secrets.get(this, Secrets.ANTHROPIC)?.takeIf { it.isNotBlank() } ?: return true
        // learning from fixes is Claude's job (Haiku), whichever model does cleanup
        val model = ClaudeCleanupRequest.DEFAULT_MODEL
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            for (c in changes) {
                val r = LearnerRequest.judge(c, key, model, http, usage::addClaude) ?: continue
                if (r.verdict == LearnerRequest.Verdict.NONE) continue
                main.post { learned(r) }
            }
        }, "voicebar-learn").start()
        return true
    }

    private fun learned(r: LearnerRequest.Result) {
        val dict = Dictionary.of(this)
        val before = dict.find(r.term)
        val heard = if (r.verdict == LearnerRequest.Verdict.VOCABULARY) null else r.heard
        dict.add(r.term, heard, vocabulary = r.verdict != LearnerRequest.Verdict.REPLACEMENT)
        val note = getString(R.string.learned_note, r.term)
        val undo = { dict.restore(r.term, before) }
        if (inputShown && !dictation.dictating) bar?.showNote(note, undo) else learnedNote = note to undo
    }

    /** [typed] was re-capitalized to [fixed] by hand: counted, and from the second time on, kept. */
    private fun learnedCasing(typed: String, fixed: String) {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post {
            val dict = Dictionary.of(this)
            val before = dict.find(fixed)
            val e = dict.add(fixed, typed, vocabulary = before?.vocabulary ?: false)
            AppLog.log("learn", "“$typed” written “$fixed” (${e.count}×)")
            // a note once, when it starts being kept
            fun kept(x: Dictionary.Entry) = Dictionary.keepsCase(x) && (!x.learned || x.count >= 2)
            if (!kept(e) || (before != null && kept(before))) return@post
            val note = getString(R.string.learned_casing_note, fixed)
            val undo = { dict.restore(fixed, before) }
            if (inputShown && !dictation.dictating) bar?.showNote(note, undo) else learnedNote = note to undo
        }
    }

    private fun showLearnedNote() {
        val (note, undo) = learnedNote ?: return
        learnedNote = null
        bar?.showNote(note, undo)
    }

    // ---------------------------------------------------------------- "voice bar, listen" while idle

    /** The bar is up (between onStartInputView and onFinishInputView). */
    private var inputShown = false

    private val wakeListener = object : WakeWord.Listener {
        override fun onWake() { if (!dictation.dictating) bar?.showCommand("", false) }
        override fun onListen() {
            if (dictation.dictating) return
            bar?.showCommand(null)
            bar?.startVoice()
        }
        override fun onHide() {
            if (dictation.dictating) return
            bar?.showCommand(null)
            requestHideSelf(0)
        }
        override fun onWakeGone() { if (!dictation.dictating) bar?.showCommand(null) }
        override fun onHandsFree() {
            if (dictation.dictating) return
            bar?.showCommand(null)
            // the screen is yours to command: put the keyboard away
            if (com.ryderdavid.voicebar.voice.HandsFreeStart.request()) requestHideSelf(0)
            else bar?.showMessage(getString(R.string.hf_needs_service))
        }
    }

    /** Watch for "voice bar" while the bar is up and nobody's dictating. */
    private fun updateWake() {
        val want = inputShown && !dictation.dictating &&
            Prefs.get(this).getBoolean(Prefs.WAKE_WORD, Prefs.DEFAULT_WAKE_WORD) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        WakeWord.want(this, WAKE_OWNER, if (want) wakeListener else null)
    }

    override fun onCreate() {
        super.onCreate()
        // the clipboard history is gone (1.7): what an earlier version kept goes too
        runCatching { deleteSharedPreferences("clip_history") }
        CorrectionWatch.onCheck = { p, field -> learnOn && checkCorrections(p, field) }
    }

    override fun onDestroy() {
        CorrectionWatch.onCheck = null
        WakeWord.want(this, WAKE_OWNER, null)
        super.onDestroy()
    }

    // ---------------------------------------------------------------- spoken commands

    private val commandHandler = android.os.Handler(android.os.Looper.getMainLooper())
    /** The live text when it last ended with a command. */
    private var commandText = ""
    private val commandRunnable = Runnable { runSpokenCommand() }

    /** "Voice bar" heard near the end of the live text, and what's followed it. */
    private var pendingCommand: VoiceCommands.Pending? = null

    /** The live text as last heard (with any "voice bar …" still in it). */
    private var lastLiveText = ""
    /**
     * "Voice bar" waved off with a swipe up: how many words it was from "voice" to the end then.
     * That one stays plain text as it grows; a later "voice bar" (fewer words from the end) counts.
     */
    private var dismissedWords = 0

    private fun livePending(text: String): VoiceCommands.Pending? {
        if (!commandsOn || dictation.state != DictationController.State.LISTENING) return null
        val p = VoiceCommands.pending(text) ?: run { dismissedWords = 0; return null }
        if (dismissedWords > 0) { if (p.words >= dismissedWords) return null else dismissedWords = 0 }
        return p
    }

    override fun onDismissCommand() {
        if (!dictation.dictating) {
            // at rest: just settle back down
            WakeWord.dismiss()
            bar?.showCommand(null)
            return
        }
        val p = pendingCommand ?: return
        dismissedWords = p.words
        pendingCommand = null
        commandHandler.removeCallbacks(commandRunnable)
        // the words go back where they were said
        if (liveTyping) liveTyper.live(lastLiveText) else bar?.showLiveText(lastLiveText)
        bar?.showCommand(null)
    }

    /** Finishing some other way with "voice bar …" half said: those words don't get typed. */
    private fun dropPendingCommand() {
        val p = pendingCommand ?: return
        pendingCommand = null
        dictation.cutLast(p.words)
    }

    private val commandsOn get() = Prefs.get(this).getBoolean(Prefs.VOICE_COMMANDS, Prefs.DEFAULT_VOICE_COMMANDS)

    /**
     * New live text: when it ends with "voice bar" and a command, wait a moment; if nothing more is
     * said (the text is unchanged), the command runs and its words come out of the text.
     */
    private fun watchCommands(text: String) {
        commandHandler.removeCallbacks(commandRunnable)
        if (dictation.state != DictationController.State.LISTENING || !commandsOn) return
        commandText = text
        if (commandAt(text) != null) commandHandler.postDelayed(commandRunnable, COMMAND_HOLD_MS)
    }

    /** The command [text] ends with, unless it's the "voice bar" that was waved off. */
    private fun commandAt(text: String): VoiceCommands.Heard? {
        val h = VoiceCommands.atEnd(text) ?: return null
        return if (dismissedWords > 0 && h.words >= dismissedWords) null else h
    }

    private fun runSpokenCommand() {
        if (dictation.state != DictationController.State.LISTENING) return
        val heard = commandAt(commandText) ?: return
        (bar as? View)?.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        if (!dictation.cutLast(heard.words)) return
        tipUsed("voicebar")
        when (heard.command) {
            VoiceCommands.Command.CLEANUP -> {
                // polish what's been said so far and keep listening
                forcePolish = true
                dictation.pause()
                return
            }
            VoiceCommands.Command.CANCEL -> {
                bar?.talkEnded()
                onTalkEnd(cancel = true)
                return
            }
            VoiceCommands.Command.SEND -> sendAfterTalk = true
            VoiceCommands.Command.KEYBOARD -> gboardAfterTalk = true
            // what's been said is typed (polished), then the keyboard goes away
            VoiceCommands.Command.HIDE -> hideAfterTalk = true
            VoiceCommands.Command.DONE -> Unit
        }
        bar?.talkEnded()
        forcePolish = true // like tapping the pill: always polished
        dictation.stop(cancel = false)
    }

    /** The finished text, minus its final period when millennial mode applies here. */
    private fun finishMessage(text: String): String = if (millennial) Millennial.strip(text).first else text

    private fun rememberStripped(final: String) {
        strippedTail = if (millennial && final.isNotEmpty() && final.last().isLetterOrDigit()) Millennial.tail(final) else null
    }

    /**
     * Dictating more right after a message we left without its period: it was the end of a
     * sentence after all, so the period goes back before the new words.
     */
    private fun restorePeriodIfFollowingUp() {
        delivered = false
        restoredPeriod = false
        val tail = strippedTail ?: return
        strippedTail = null
        val ic = currentInputConnection ?: return
        if (!millennial || !Millennial.needsPeriodBack(tail, ic.getTextBeforeCursor(tail.length, 0))) return
        ic.commitText(".", 1)
        restoredPeriod = true
    }

    /** The follow-up was thrown away or heard nothing: the message ends without its period again. */
    private fun takeBackRestoredPeriod() {
        if (!restoredPeriod) return
        restoredPeriod = false
        val ic = currentInputConnection ?: return
        if (ic.getTextBeforeCursor(1, 0)?.toString() == ".") {
            ic.deleteSurroundingText(1, 0)
            strippedTail = ic.getTextBeforeCursor(Millennial.TAIL_LENGTH, 0)?.toString()
        }
    }

    /** Words recognized so far in this dictation, so the silence countdown resets only on new ones. */

    /** This dictation types into the field as you speak (rather than previewing above the bubble). */
    private var liveTyping = false

    private val liveField = object : LiveField {
        override fun setComposing(text: String) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.setComposingText(text, 1) }
        override fun finishComposing() { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.finishComposingText() }
        override fun commit(text: String) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.commitText(text, 1) }
        override fun textBefore(n: Int): CharSequence? = currentInputConnection?.getTextBeforeCursor(n, 0)
        override fun deleteBefore(n: Int) { currentInputConnection?.deleteSurroundingText(n, 0) }
        override fun textAfter(n: Int): CharSequence? = currentInputConnection?.getTextAfterCursor(n, 0)
        override fun deleteAfter(n: Int) { lastOwnEditAt = SystemClock.uptimeMillis(); currentInputConnection?.deleteSurroundingText(0, n) }
        override fun setSelection(start: Int, end: Int) { currentInputConnection?.setSelection(start, end) }
        override fun beginBatch() { currentInputConnection?.beginBatchEdit() }
        override fun endBatch() { currentInputConnection?.endBatchEdit() }
    }

    private val liveTyper: LiveTyper by lazy {
        // the app ended our draft mid-speech: what's typed stays, the next words start a new draft
        // the user took over the field mid-dictation (moved the cursor, selected text): stop listening
        // and finish in place, so the dictation never fights them for the cursor
        LiveTyper({ if (currentInputConnection != null) liveField else null }, onUserEdit = { userTookOver() })
    }

    /**
     * Live typing needs a real text editor: not a password (spoken passwords shouldn't appear as
     * you say them) and not a raw-key field like a terminal, which can't hold a draft.
     */
    private fun fieldTakesLiveText(info: EditorInfo?): Boolean {
        val type = info?.inputType ?: return false
        if (type == InputType.TYPE_NULL) return false
        val cls = type and InputType.TYPE_MASK_CLASS
        val variation = type and InputType.TYPE_MASK_VARIATION
        if (cls == InputType.TYPE_CLASS_TEXT && (variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)) return false
        if (cls == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) return false
        return true
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        selStart = newSelStart
        selEnd = newSelEnd
        // the cursor went somewhere other than the end of the swiped word: ⌫ is one letter again
        if (swipeEnd >= 0 && (newSelStart != swipeEnd || newSelEnd != swipeEnd)) swipeAdded = null
        if (stick.active && stick.onSelection(newSelStart, newSelEnd)) return // the joystick's own marker
        bar?.hasSelection = newSelStart >= 0 && newSelEnd >= 0 && newSelStart != newSelEnd
        // the cursor back at the very start mid-dictation: the app may have sent the message itself
        if (newSelStart == 0 && newSelEnd == 0 && sentByApp()) return
        if (liveTyping) liveTyper.onSelection(newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        updateKeys()
    }

    // ---------------------------------------------------------------- Voicebar's keyboard

    /** The keyboard is up: its suggestions and capitals follow the cursor. */
    private var keysOpen = false

    private val suggester by lazy {
        // "word<TAB>frequency", most common first
        val words = runCatching { assets.open("words_en.txt").bufferedReader().readLines().mapNotNull { l -> l.substringBefore('\t').takeIf { it.isNotBlank() } } }.getOrDefault(emptyList())
        com.ryderdavid.voicebar.voice.WordSuggester(words, extra = { Dictionary.of(this).all().take(300).map { it.term } }).also {
            it.load(Prefs.get(this).getString(Prefs.TYPED_WORDS, null))
        }
    }

    /** The last fix made on a space ("i" to "I"): ⌫ right after puts back what was typed. */
    private var lastFix: Pair<String, String>? = null
    /** A suggestion just went in with a space after it: punctuation next takes the space's place. */
    private var suggestionSpace = false
    private var lastSpaceAt = 0L

    override fun onKeysShown(open: Boolean) {
        keysOpen = open
        if (open) {
            updateKeys()
            // the swipe models take a moment to load: now, so the first swipe doesn't wait
            if (Prefs.get(this).getBoolean(Prefs.SWIPE_TYPING, Prefs.DEFAULT_SWIPE_TYPING)) swipeWork.execute { swiper }
        } else Prefs.get(this).edit().putString(Prefs.TYPED_WORDS, suggester.save()).apply()
    }

    // ---------------------------------------------------------------- swipe typing (FUTO Swipe)

    /** Swipes are decoded one at a time, off the main thread. */
    private val swipeWork = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-swipe").apply { isDaemon = true } }

    /** The models and the dictionary's tree; null when they couldn't load. Touched only on [swipeWork]. */
    private val swiper: Pair<com.ryderdavid.voicebar.swipe.SwipeModels, com.ryderdavid.voicebar.swipe.SwipeDecoder.Lexicon>? by lazy {
        val prefs = Prefs.get(this)
        // a native crash can't be caught: if the last load never finished, it took the keyboard down
        // with it, so swipe typing goes off rather than crash the keyboard every time it opens
        if (prefs.getBoolean(Prefs.SWIPE_LOADING, false)) {
            prefs.edit().putBoolean(Prefs.SWIPE_LOADING, false).putBoolean(Prefs.SWIPE_TYPING, false).commit()
            AppLog.log("swipe", "the last load crashed the keyboard: swipe typing turned off")
            (bar as? android.view.View)?.post { bar?.reloadSettings(); bar?.showMessage(getString(R.string.swipe_crashed)) }
            return@lazy null
        }
        prefs.edit().putBoolean(Prefs.SWIPE_LOADING, true).commit()
        runCatching {
            val t0 = SystemClock.uptimeMillis()
            val lexicon = com.ryderdavid.voicebar.swipe.SwipeDecoder.Lexicon.parse(assets.open("words_en.txt").bufferedReader().lineSequence())
            Dictionary.of(this).all().forEach { lexicon.add(it.term, PERSONAL_WORD_FREQ) }
            val models = com.ryderdavid.voicebar.swipe.SwipeModels.load(this)
            AppLog.log("swipe", "ready in ${SystemClock.uptimeMillis() - t0} ms (${lexicon.nodes} dictionary nodes)")
            models to lexicon
        }.onFailure { AppLog.log("swipe", "couldn't load", it) }.getOrNull()
            .also { prefs.edit().putBoolean(Prefs.SWIPE_LOADING, false).apply() }
    }

    /** The word a swipe just typed, and the other words it might have been (shown over the keyboard). */
    private var lastSwipe: Pair<String, List<String>>? = null

    /**
     * What the last swipe put in (its space and the word) while nothing else has happened since:
     * ⌫ takes all of it back at once, as in Gboard. Anything else (a key, a suggestion, the cursor
     * moving) and ⌫ deletes letter by letter again.
     */
    private var swipeAdded: String? = null
    /** Where the cursor was left after [swipeAdded], when known (-1 otherwise). */
    private var swipeEnd = -1

    override fun onSwipe(points: List<com.ryderdavid.voicebar.swipe.SwipeDecoder.Point>, keys: FloatArray, caps: Int) {
        swipeWork.execute {
            val s = swiper
            val t0 = SystemClock.uptimeMillis()
            val words = s?.let { (models, lexicon) -> runCatching { models.decode(points, keys, lexicon, 4) }.onFailure { AppLog.log("swipe", "decode failed", it) }.getOrNull() }
                .orEmpty().map { it.word }
            val ms = SystemClock.uptimeMillis() - t0
            (bar as? android.view.View)?.post {
                if (s == null) { bar?.showMessage(getString(R.string.swipe_unavailable)); return@post }
                AppLog.log("swipe", "${points.size} points, ${ms} ms: " + words.joinToString(" · ").ifEmpty { "nothing" })
                val best = words.firstOrNull() ?: return@post
                fun cased(w: String) = when (caps) { 2 -> w.uppercase(); 1 -> w.replaceFirstChar { it.uppercaseChar() }; else -> w }
                val ic = currentInputConnection ?: return@post
                ownEdit()
                keyDuringDictation()
                // a space before it, unless it starts the field, a line, or follows a space or an opening bracket or quote
                val before = ic.getTextBeforeCursor(1, 0)
                val sep = if (before.isNullOrEmpty() || before.last().isWhitespace() || before.last() in "([{\"“‘") "" else " "
                val word = cased(best)
                val atCursor = if (selStart >= 0 && selStart == selEnd) selStart else -1
                ic.commitText(sep + word, 1)
                swipeAdded = sep + word
                swipeEnd = if (atCursor >= 0) atCursor + (sep + word).length else -1
                suggester.learn(best)
                lastFix = null
                suggestionSpace = false
                lastSwipe = word to words.drop(1).map { cased(it) }
                updateKeys()
            }
        }
    }

    /** Suggestions for the word at the cursor, and whether a capital is due there. */
    private fun updateKeys() {
        if (!keysOpen) return
        val b = bar ?: return
        val before = currentInputConnection?.getTextBeforeCursor(48, 0)
        b.keyAutoCap = shouldCapitalize()
        val word = com.ryderdavid.voicebar.voice.WordSuggester.currentWord(before)
        // right after a swipe: the other words it might have been
        val swiped = lastSwipe?.takeIf { it.first == word }
        if (swiped == null) lastSwipe = null
        b.keySuggestions = swiped?.second ?: suggester.suggest(word)
    }

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        ownEdit()
        keyDuringDictation()
        val before = ic.getTextBeforeCursor(48, 0)?.toString().orEmpty()
        val now = SystemClock.uptimeMillis()
        lastFix = null
        swipeAdded = null
        val afterSuggestion = suggestionSpace
        suggestionSpace = false
        // a letter straight after a swiped word is the next word: a space between them
        if (com.ryderdavid.voicebar.voice.WordSuggester.spaceAfterSwipe(before, lastSwipe?.first, text)) {
            lastSwipe = null
            ic.commitText(" $text", 1)
            return
        }
        when {
            text == " " -> {
                // two spaces after a word: a period and a space
                if (now - lastSpaceAt < DOUBLE_SPACE_MS && before.length >= 2 && before.endsWith(" ") && before[before.length - 2].isLetterOrDigit()) {
                    lastSpaceAt = 0L
                    ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText(". ", 1); ic.endBatchEdit()
                    return
                }
                lastSpaceAt = now
                val word = com.ryderdavid.voicebar.voice.WordSuggester.currentWord(before)
                suggester.learn(word)
                val fixed = KeyFixes.fix(word)
                if (fixed != null) {
                    ic.beginBatchEdit(); ic.deleteSurroundingText(word.length, 0); ic.commitText("$fixed ", 1); ic.endBatchEdit()
                    lastFix = word to fixed
                    return
                }
            }
            // a suggestion's space: the punctuation goes right after the word
            text.length == 1 && text[0] in ".,?!;:" && afterSuggestion && before.endsWith(" ") -> {
                ic.beginBatchEdit(); ic.deleteSurroundingText(1, 0); ic.commitText("$text ", 1); ic.endBatchEdit()
                return
            }
            text.length == 1 && text[0] in ".,?!;:" -> suggester.learn(com.ryderdavid.voicebar.voice.WordSuggester.currentWord(before))
        }
        ic.commitText(text, 1)
    }

    override fun onMark(mark: String) {
        val ic = currentInputConnection ?: return
        ownEdit()
        keyDuringDictation()
        swipeAdded = null
        lastSwipe = null
        lastFix = null
        suggestionSpace = false
        val (back, typed) = Marks.insertion(ic.getTextBeforeCursor(8, 0), mark)
        ic.beginBatchEdit()
        if (back > 0) ic.deleteSurroundingText(back, 0)
        ic.commitText(typed, 1)
        ic.endBatchEdit()
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        ownEdit()
        keyDuringDictation()
        val typed = com.ryderdavid.voicebar.voice.WordSuggester.currentWord(ic.getTextBeforeCursor(48, 0))
        swipeAdded = null
        ic.beginBatchEdit()
        if (typed.isNotEmpty()) ic.deleteSurroundingText(typed.length, 0)
        ic.commitText("$word ", 1)
        ic.endBatchEdit()
        suggester.learn(word)
        suggestionSpace = true
        lastFix = null
    }

    /** ⌫ right after a swipe: the whole word goes, and the space the swipe put before it. */
    private fun deleteSwipe(): Boolean {
        val added = swipeAdded ?: return false
        swipeAdded = null
        if (selStart >= 0 && selStart != selEnd) return false // a selection: ⌫ deletes that, as always
        val ic = currentInputConnection ?: return false
        val n = com.ryderdavid.voicebar.voice.WordSuggester.swipeToDelete(ic.getTextBeforeCursor(added.length, 0), added)
        if (n == 0) return false
        ownEdit()
        keyDuringDictation()
        ic.deleteSurroundingText(n, 0)
        lastFix = null
        suggestionSpace = false
        lastSwipe = null
        AppLog.log("swipe", "⌫ took back the swiped word")
        return true
    }

    /** ⌫ right after a fix on a space: back to what was typed, without the space. */
    private fun undoFix(): Boolean {
        val (typed, fixed) = lastFix ?: return false
        lastFix = null
        val ic = currentInputConnection ?: return false
        if (ic.getTextBeforeCursor(fixed.length + 1, 0)?.toString() != "$fixed ") return false
        ownEdit()
        ic.beginBatchEdit(); ic.deleteSurroundingText(fixed.length + 1, 0); ic.commitText(typed, 1); ic.endBatchEdit()
        return true
    }

    /** The field's selection as last reported. */
    private var selStart = -1
    private var selEnd = -1

    /**
     * The cursor joystick's side of things. An IME can't draw inside the app's text, so the
     * target marker is shown as a one-character selection at the target (the field highlights
     * it); a tap collapses it to a cursor there. Left and right move the target by characters
     * directly; up and down ask the app to move a cursor by a line (so wrapped lines work) and
     * read where it landed. Selecting keeps the anchor at the cursor and moves the other end.
     */
    private inner class Stick {
        var active = false
        /** Where the blinking cursor is (or the selection's fixed end), and where the stick is aimed. */
        var cursor = 0
        var target = 0
        /** The one-character highlight standing in for the marker, while it's up. */
        var markStart = -1
        var markEnd = -1
        var selecting = false
        var anchor = 0
        /** A real selection that was up when aiming began: it comes back if the aim isn't taken. */
        private var keptStart = -1
        private var keptEnd = -1

        /** Puts back what aiming covered up: the selection that was there, or the cursor. */
        private fun restore() {
            if (keptStart >= 0) setSel(keptStart, keptEnd) else setSel(cursor, cursor)
            keptStart = -1; keptEnd = -1
        }
        /** A line move is out with the app; the next collapsed cursor report is where it landed. */
        var awaitingLine = false
        private var awaitingSince = 0L
        /** Where the line move started from: reports of the cursor still there aren't its answer. */
        private var lineFrom = -1
        private var length = 0

        /** A line move that never answered (the first or last line): give up on it. */
        private fun lineTimedOut(): Boolean {
            if (awaitingLine && SystemClock.uptimeMillis() - awaitingSince > LINE_MOVE_TIMEOUT_MS) {
                awaitingLine = false
                if (!selecting) mark()
            }
            return !awaitingLine
        }

        fun start() {
            active = true
            selecting = false
            awaitingLine = false
            cursor = if (selStart >= 0) selEnd else 0
            target = cursor
            clearMark()
            refreshLength()
        }

        fun end() {
            if (!active) return
            // aimed but never tapped: the cursor (or selection) stays where it was
            if (markStart >= 0 || awaitingLine) restore()
            active = false
            clearMark()
        }

        private fun refreshLength() {
            val et = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
            length = if (et?.text != null) et.startOffset + et.text.length else Int.MAX_VALUE / 2
        }

        private fun clearMark() { markStart = -1; markEnd = -1 }

        private fun setSel(a: Int, b: Int) {
            lastOwnEditAt = SystemClock.uptimeMillis()
            currentInputConnection?.setSelection(a, b)
        }

        /** Moves the blinking cursor to the target, between characters, as it's aimed. */
        private fun mark() {
            keptStart = -1; keptEnd = -1
            clearMark()
            cursor = target
            setSel(target, target)
        }

        fun step(cols: Int, lines: Int) {
            if (selecting) { stepSelecting(cols, lines); return }
            if (!lineTimedOut()) return
            if (lines != 0) {
                // at the very start or end an arrow would move focus out of the field instead
                if ((lines < 0 && target == 0) || (lines > 0 && target >= length)) return
                if (markStart < 0 && keptStart < 0 && selStart != selEnd && selStart >= 0) { keptStart = selStart; keptEnd = selEnd }
                awaitingLine = true
                awaitingSince = SystemClock.uptimeMillis()
                lineFrom = target
                // let the app move a real cursor a line, so wrapping is respected
                clearMark()
                setSel(target, target)
                repeat(kotlin.math.abs(lines)) { sendDownUpKeyEvents(if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
                return
            }
            target = (target + cols).coerceIn(0, length)
            mark()
        }

        private fun stepSelecting(cols: Int, lines: Int) {
            if (!lineTimedOut()) return
            if (lines != 0) {
                if ((lines < 0 && target == 0) || (lines > 0 && target >= length)) return
                awaitingLine = true
                awaitingSince = SystemClock.uptimeMillis()
                lineFrom = target
                val code = if (lines > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                val ic = currentInputConnection ?: return
                lastOwnEditAt = SystemClock.uptimeMillis()
                val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
                repeat(kotlin.math.abs(lines)) {
                    val now = SystemClock.uptimeMillis()
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, meta))
                    ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, meta))
                }
                return
            }
            target = (target + cols).coerceIn(0, length)
            setSel(anchor, target)
        }

        /** Takes the marker down, putting the cursor back where it was. */
        fun dropMark() {
            if (markStart < 0) return
            clearMark()
            target = cursor
            restore()
        }

        /** The knob was held still: select from the cursor. */
        fun select() {
            keptStart = -1; keptEnd = -1
            selecting = true
            awaitingLine = false
            anchor = cursor
            target = cursor
            clearMark()
            setSel(cursor, cursor)
        }

        /** A tap: the cursor jumps to the target. */
        fun tap() {
            keptStart = -1; keptEnd = -1
            awaitingLine = false
            selecting = false
            cursor = target
            clearMark()
            setSel(cursor, cursor)
        }

        /**
         * A selection report while the joystick is up. True when it's our own marker (so the
         * keyboard shouldn't treat it as the user's selection).
         */
        /**
         * The end of a reported selection that the stick is moving. Apps report selections
         * normalized (start <= end), so a selection grown leftward of the anchor comes back with the
         * moving end first: taking "end" there snapped the target back to the anchor every step, and
         * a leftward drag just rattled one or two characters from it.
         */
        private fun movingEnd(start: Int, end: Int): Int = stickMovingEnd(anchor, target, start, end)

        fun onSelection(start: Int, end: Int): Boolean {
            if (awaitingLine) {
                if (selecting) {
                    val moved = movingEnd(start, end)
                    if (moved == lineFrom) return false
                    awaitingLine = false
                    target = moved
                    return false // a real selection, grown a line
                }
                if (start != end || start == lineFrom) return true // not the line move's answer yet
                awaitingLine = false
                target = start
                mark()
                return true
            }
            if (start == markStart && end == markEnd && markStart >= 0) return true
            if (!selecting && start == end && markStart < 0) {
                // the cursor moved some other way (an edit button, the app): follow it
                cursor = start
                target = start
            }
            if (selecting) target = movingEnd(start, end)
            refreshLength()
            return false
        }
    }
    private val stick = Stick()

    override fun onStickStart() {
        stick.start()
    }

    override fun onStickStep(cols: Int, lines: Int, selecting: Boolean) = stick.step(cols, lines)
    override fun onStickTap() = stick.tap()
    override fun onStickSelect() = stick.select()
    override fun onStickEnd() = stick.end()

    /** When we last typed or edited, so our own cursor moves aren't mistaken for the user's taps. */
    private var lastOwnEditAt = 0L

    private fun ownEdit() {
        lastOwnEditAt = SystemClock.uptimeMillis()
    }

    /** Whether the clipboard holds something pasteable as text, from its description only. */
    private fun clipHasText(): Boolean {
        val clip = getSystemService(ClipboardManager::class.java) ?: return false
        if (!clip.hasPrimaryClip()) return false
        val desc = clip.primaryClipDescription ?: return true
        return desc.mimeTypeCount == 0 || (0 until desc.mimeTypeCount).any {
            desc.getMimeType(it).startsWith("text/") || desc.getMimeType(it) == android.content.ClipDescription.MIMETYPE_TEXT_URILIST
        }
    }

    private fun refreshCanPaste() {
        val can = runCatching { clipHasText() }.getOrDefault(false)
        bar?.canPaste = can
        bar?.clipPreview = if (can) runCatching { clipStart() }.getOrNull() else null
        bar?.dictations = DictationHistory.of(this).all()
    }


    /** The dictation just finished, as heard (before cleanup). */
    private var heardAll: String? = null

    override fun onDictation(action: Bar.DictationAction, time: Long) {
        AppLog.log("menu", "dictation $action")
        val h = DictationHistory.of(this)
        val e = h.all().firstOrNull { it.time == time } ?: return
        when (action) {
            Bar.DictationAction.PASTE_TYPED, Bar.DictationAction.PASTE_HEARD -> {
                val t = if (action == Bar.DictationAction.PASTE_HEARD) e.heard else e.typed.ifBlank { e.heard }
                ownEdit()
                keyDuringDictation()
                typeDictation(t)
            }
            Bar.DictationAction.DELETE -> h.remove(time)
            Bar.DictationAction.RECLEAN -> reclean(e)
        }
        bar?.dictations = h.all()
    }

    override fun onRepeatDictation() {
        val last = DictationHistory.of(this).all().firstOrNull()
        AppLog.log("menu", "last dictation key: " + (last?.let { AppLog.excerpt(it.typed.ifBlank { it.heard }) } ?: "none yet"))
        last?.let { onDictation(Bar.DictationAction.PASTE_TYPED, it.time) }
    }

    /** The first few dozen characters on the clipboard, for the paste button while dictating. */
    private fun clipStart(): String? {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        return clip.getItemAt(0).coerceToText(this)?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.take(80)?.ifBlank { null }
    }

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { refreshCanPaste() }

    /**
     * The app's own Send was pressed mid-dictation: the message went, with the words typed live in
     * it, and the box is empty. What's been said is dropped and listening stops; otherwise the
     * words would be typed straight back in (some apps never report our draft, so its ending isn't
     * noticed otherwise). Also for a box wiped by hand: the words are gone either way.
     */
    private fun sentByApp(): Boolean {
        if (!dictation.dictating || !liveTyping || liveTyper.typedWords() == 0) return false
        // unreadable isn't empty
        if ((readField() ?: return false).isNotBlank()) return false
        AppLog.log("dictation", "the box emptied mid-dictation (sent by the app): stopped, dropped " + AppLog.excerpt(lastLiveText))
        sendAfterTalk = false
        hideAfterTalk = false
        gboardAfterTalk = false
        dropPendingCommand()
        liveTyper.end()
        dictation.abort()
        lastLiveText = ""
        return true
    }

    private fun userTookOver() {
        if (sentByApp()) return
        if (dictation.state != DictationController.State.LISTENING) return
        bar?.talkEnded()
        dictation.stop(cancel = false)
    }

    /** A key typed mid-dictation: what's spoken so far stays as typed, and later words follow the key. */
    private fun keyDuringDictation() {
        if (dictation.dictating) guard.touch()
        if (!liveTyping || !dictation.dictating) return
        liveTyper.interrupt(newSegment = dictation.markBoundary(liveTyper.typedWords()))
    }

    // ---------------------------------------------------------------- first-run tips

    /** What the tips bubble shows now, if anything. */
    private var tipShowing: Tips.Showing? = null
    /** The tour already came up since the keyboard opened. */
    private var tipShownThisOpen = false

    /** When the keyboard opens: the tour, from where it was left, if it isn't finished (and tips are on). */
    private fun maybeTip() {
        if (!inputShown || tipShowing != null || tipShownThisOpen || dictation.dictating) return
        val step = Tips.resume(this) ?: return
        tipShownThisOpen = true
        showTip(Tips.Showing.Step(step))
    }

    private fun showTip(s: Tips.Showing?) {
        tipShowing = s
        val dock = bar as? DockView ?: return
        val of = Tips.Id.entries.size
        dock.tip = when (s) {
            null -> null
            is Tips.Showing.Step -> DockView.Tip(s.id.anchor, getString(s.id.title), getString(s.id.body), s.index + 1, of)
            is Tips.Showing.Ask -> DockView.Tip(Tips.Id.entries[s.index].anchor, getString(R.string.tip_ask_title), getString(R.string.tip_ask), s.index + 1, of, ask = true)
        }
    }

    /** Puts the bubble away for now (the tour resumes there next time). */
    private fun hideTip() = showTip(null)

    /** The bar reports a feature used: starting to talk puts the tour away for now. */
    private fun tipUsed(key: String) {
        if (key == "voice" && tipShowing != null) hideTip()
    }

    private fun tipAnswered(a: Tips.Answer) {
        val now = tipShowing ?: return
        val (next, resume) = Tips.answer(now, a)
        Tips.save(this, resume)
        showTip(next)
    }

    override fun onCreateInputView(): View {
        val view = DockView(this, null).apply {
            listener = this@VoicebarService
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            onUsed = { key -> tipUsed(key) }
            onTipAnswer = { a -> tipAnswered(a) }
        }
        bar = view as KeyboardSurface
        return view
    }

    override fun setInputView(view: View) {
        super.setInputView(view)
        dockWindow(view)
    }

    /** Ends any dictation now, leaving whatever it typed (the field is going away or changing). */
    private fun stopDictationKeepingText() {
        if (liveTyping) liveTyper.end()
        dictation.abort()
    }

    private var fieldKey: String? = null
    /** The last real text box's app and hint, to focus it again when Voice is tapped without one. */
    private var lastField: Pair<String, String?>? = null
    /** Voice was tapped with no text box: the box was asked back, and this is the deadline to start anyway. */
    private var regainUntil = 0L

    /**
     * No text box to type into: ask the app (through the accessibility service) to focus its text box
     * again, the last one Voicebar was in if it's on screen. True when it was asked.
     */
    private fun askForField(): Boolean {
        val pkg = currentInputEditorInfo?.packageName ?: lastField?.first
        val hint = lastField?.takeIf { it.first == pkg }?.second
        val asked = GboardButtonService.running?.refocusField(pkg, hint) == true
        AppLog.log("field", "no text box to type into (in $pkg): " +
            if (asked) "asked the app to focus its text box again" else "nothing to focus (no text box on screen, or the accessibility service is off)")
        return asked
    }

    /** Waits (up to [ms]) for a text box to come back, then [then]; [otherwise] if none does. */
    private fun awaitField(ms: Long, then: () -> Unit, otherwise: () -> Unit) {
        val until = SystemClock.uptimeMillis() + ms
        sendHandler.post(object : Runnable {
            override fun run() = when {
                hasTextField() -> { AppLog.log("field", "back in the text box"); then() }
                SystemClock.uptimeMillis() >= until -> { AppLog.log("field", "no text box came back"); otherwise() }
                else -> { sendHandler.postDelayed(this, 80); Unit }
            }
        })
    }

    /**
     * Some apps (browsers especially) move between fields by restarting input on the same view,
     * without finishing the input view: a dictation still finishing must not land in the new field.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        val key = attribute?.let { "${it.packageName}/${it.fieldId}/${it.fieldName}" }
        if (key != fieldKey) {
            // left the field: one last look at what was typed there (as accessibility last saw it)
            if (CorrectionWatch.pending != null) CorrectionWatch.finish(null)
            stopDictationKeepingText()
            strippedTail = null
        }
        fieldKey = key
        // the last real text box: what to get back to when the keyboard finds itself without one
        if (attribute != null && attribute.inputType != InputType.TYPE_NULL && attribute.packageName != null)
            lastField = attribute.packageName to attribute.hintText?.toString()
        // switched here from Gboard's Voicebar button: make sure the bar actually shows. With a
        // physical keyboard attached, Android switches keyboards but doesn't re-show the new one,
        // which would leave the field with no keyboard at all
        if (SystemClock.uptimeMillis() < GboardReturn.voiceUntil && android.os.Build.VERSION.SDK_INT >= 28) {
            window?.window?.decorView?.post { if (!isInputViewShown) runCatching { requestShowSelf(0) } }
        }
    }

    /**
     * Show the bar even with a physical keyboard attached (Android hides on-screen keyboards then,
     * unless "Show on-screen keyboard" is on): it's one row, and voice is still a tap away.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        refreshCanPaste()
        bar?.hasSelection = info != null && info.initialSelStart >= 0 && info.initialSelStart != info.initialSelEnd
        // the keyboard's ↵ shows what this field's Enter does (send, search, go…), unless Enter is a new line here
        bar?.enterAction = if (info == null || (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) EditorInfo.IME_ACTION_UNSPECIFIED
            else info.imeOptions and EditorInfo.IME_MASK_ACTION
        lastFix = null
        suggestionSpace = false
        swipeAdded = null
        if (!restarting) {
            stopDictationKeepingText()
            bar?.reloadSettings()
            bar?.reset()
        }
        getSystemService(ClipboardManager::class.java)?.let { it.removePrimaryClipChangedListener(clipListener); it.addPrimaryClipChangedListener(clipListener) }
        // just switched back from Gboard with the return button, or "listen as soon as a text box
        // opens" is on (and this is a new field, not a password): go straight to listening
        val fromGboard = SystemClock.uptimeMillis() < GboardReturn.voiceUntil
        // …but not with a physical keyboard attached: you're about to type, so the bar just opens
        val config = resources.configuration
        val keyboard = HardKeyboard.attached(config)
        val skip = com.ryderdavid.voicebar.voice.AutoListenGuard.skipReason(
            Prefs.get(this).getBoolean(Prefs.AUTO_LISTEN, Prefs.DEFAULT_AUTO_LISTEN), restarting, fieldTakesLiveText(info), keyboard)
        val autoListen = skip == null
        inputShown = true
        AppLog.log("field", "opened ${info?.packageName} type=0x${Integer.toHexString(info?.inputType ?: 0)} restarting=$restarting auto=$autoListen fromGboard=$fromGboard" +
            (skip?.let { " (not listening by itself: $it" + (if (keyboard) ", keyboard=${config.keyboard} hidden=${config.hardKeyboardHidden}" else "") + ")" } ?: ""))
        if (!keyboard) keyboardNoted = false
        // a keyboard connected: lying still (at a desk with it) you're about to type, but in your hand
        // (walking around) you're not, so listen after all; a quiet stand-down says why, once per connection
        if (skip == com.ryderdavid.voicebar.voice.AutoListenGuard.SKIP_KEYBOARD && !fromGboard) {
            val field = fieldKey
            heldProbe.check { held, motion, sensor ->
                AppLog.log("field", "keyboard connected; phone ${when (held) { true -> "in hand"; false -> "still"; null -> "unknown" }} ($sensor motion ${"%.4f".format(motion)})")
                if (!inputShown || fieldKey != field || dictation.dictating) return@check
                if (held == true) {
                    autoStartPending = true
                    bar?.startVoice()
                    autoStartPending = false
                } else if (!keyboardNoted) {
                    keyboardNoted = true
                    bar?.showNote(getString(R.string.auto_listen_keyboard_note))
                }
            }
        }
        // Parakeet takes a moment to load: start now, so it's ready when you talk
        com.ryderdavid.voicebar.voice.Parakeet.Engine.warm(this)
        warmSpeechService()
        // back in the field (from Gboard, say): see whether what we typed was corrected
        if (CorrectionWatch.pending?.field == fieldKey) CorrectionWatch.check(readField())
        (bar as? android.view.View)?.post { showLearnedNote() }
        if (fromGboard || autoListen) {
            GboardReturn.voiceUntil = 0L
            // the field opening started it, not a tap: the auto-listen safeguards apply
            val auto = autoListen && !fromGboard
            (bar as? android.view.View)?.post {
                autoStartPending = auto
                bar?.startVoice()
                autoStartPending = false
                if (!dictation.dictating) AppLog.log("field", "auto-listen didn't start (no mic permission, or the last dictation still finishing)")
            }
        }
        // after any auto-start has had its turn (dictating, it stays off)
        (bar as? android.view.View)?.post { updateWake() }
        if (!restarting) {
            tipShownThisOpen = false
            hideTip()
            (bar as? android.view.View)?.postDelayed({ maybeTip() }, 450)
            (bar as? android.view.View)?.postDelayed({ if (inputShown && !dictation.dictating) warnIfWayBackSwitchedOff() }, 700)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputShown = false
        // held words were for this message box: leaving it throws them away
        if (heldShown) { heldShown = false; bar?.hideVoice() }
        hideTip()
        WakeWord.want(this, WAKE_OWNER, null)
        getSystemService(ClipboardManager::class.java)?.removePrimaryClipChangedListener(clipListener)
        // keep what's been typed, before the view's reset would throw out a dictation in progress
        stopDictationKeepingText()
        bar?.reset()
        super.onFinishInputView(finishingInput)
    }

    /**
     * A floating hint above the bar (DockView.floatRoom) grows the window, but the app stays
     * where it was and touches there go to the app: content and visible insets start at the
     * bar's surface, and only the bar is touchable.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val v = bar as? DockView ?: return
        // floating: the app keeps the whole screen (or, the pill parked at the bottom, everything above it),
        // and only the pill and what's open from it take touches
        v.floatingInsets()?.takeIf { v.isShown }?.let { (content, rects) ->
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            outInsets.contentTopInsets = content + loc[1]
            outInsets.visibleTopInsets = content + loc[1]
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
            outInsets.touchableRegion.setEmpty()
            for (r in rects) outInsets.touchableRegion.op(android.graphics.Rect(r.left.toInt() + loc[0], r.top.toInt() + loc[1], r.right.toInt() + loc[0], r.bottom.toInt() + loc[1]),
                android.graphics.Region.Op.UNION)
            return
        }
        val room = v.floatRoom()
        if (room <= 0 || !v.isShown) return
        outInsets.contentTopInsets += room
        outInsets.visibleTopInsets += room
        // the punctuation palette up: the whole window takes touches, so a tap away only closes it
        if (v.catchesEveryTouch()) { outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_FRAME; return }
        val h = window.window?.decorView?.height ?: return
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, outInsets.contentTopInsets, v.width, h)
        // a tip's bubble, or the own key's column of choices, takes taps; the rest of the room stays the app's
        (v.pickerRect() ?: v.tipRect())?.let { r ->
            val loc = IntArray(2).also { v.getLocationInWindow(it) }
            outInsets.touchableRegion.op(android.graphics.Rect(r.left.toInt() + loc[0], r.top.toInt() + loc[1], r.right.toInt() + loc[0], r.bottom.toInt() + loc[1]),
                android.graphics.Region.Op.UNION)
        }
    }

    /** Never an extract (fullscreen) editor: the bar sits under the app. */
    override fun onEvaluateFullscreenMode() = false

    /**
     * The docked bar is an ordinary bottom keyboard: the window wraps it, and it draws its own
     * surface under the navigation area. On Android 15+ the system's ⌄/switcher band is hidden so
     * the bar sits right on the gesture area (back still closes the keyboard; switching lives in ⋮).
     */
    private fun dockWindow(view: View) {
        window.window?.let { w ->
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            if (android.os.Build.VERSION.SDK_INT >= 29) w.isNavigationBarContrastEnforced = false
            val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            androidx.core.view.WindowCompat.getInsetsController(w, view).isAppearanceLightNavigationBars = !night
            val lp = w.attributes
            if (lp.height != ViewGroup.LayoutParams.WRAP_CONTENT) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                w.attributes = lp
            }
            if (android.os.Build.VERSION.SDK_INT >= 35) {
                runCatching { w.insetsController?.hide(android.view.WindowInsets.Type.captionBar()) }
            }
        }
        val inputArea = window.window?.findViewById<View>(android.R.id.inputArea)
        inputArea?.layoutParams?.let {
            it.height = ViewGroup.LayoutParams.WRAP_CONTENT
            inputArea.layoutParams = it
        }
        view.requestApplyInsets()
    }

    private companion object {
        /** How long recognition and cleanup wait for the screen's text before going without it. */
        const val SCREEN_WAIT_MS = 400L
        /** Cursor moves this soon after our own typing are ours, not the user's. */
        /** How long the joystick waits for the app to report a line move before giving up on it. */
        const val LINE_MOVE_TIMEOUT_MS = 300L
    }

    // ---------------------------------------------------------------- Bar.Listener

    override fun onKey(key: Bar.Key, repeat: Boolean) {
        if (key == Bar.Key.DELETE && !repeat && deleteSwipe()) return
        swipeAdded = null
        if (key == Bar.Key.DELETE && !repeat && keysOpen && undoFix()) return
        if (key != Bar.Key.LEFT && key != Bar.Key.RIGHT) { lastFix = null; suggestionSpace = false }
        ownEdit()
        // ↵ while dictating is a line break, never the field's Send/Search action
        val lineBreak = key == Bar.Key.ENTER && dictation.dictating
        keyDuringDictation()
        if (lineBreak) { currentInputConnection?.commitText("\n", 1); return }
        when (key) {
            Bar.Key.UP -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_UP)
            Bar.Key.DOWN -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_DOWN)
            // at either end of the text a d-pad arrow moves focus to another view, which closes the
            // keyboard mid-repeat; stop at the end instead
            Bar.Key.LEFT -> if (canMove(before = true)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
            Bar.Key.RIGHT -> if (canMove(before = false)) sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            Bar.Key.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            Bar.Key.SPACE -> currentInputConnection?.commitText(" ", 1)
            // Ctrl+Z: the undo every text field, web page and Compose field understands
            Bar.Key.UNDO -> currentInputConnection?.let { ic ->
                val t = SystemClock.uptimeMillis()
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
                ic.sendKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_Z, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON))
            }
            // runs the field's action (search, send, next...) when it has one, else a newline
            Bar.Key.ENTER -> sendKeyChar('\n')
        }
    }

    /** Whether the cursor has room to move left ([before]) or right, or a selection to collapse. */
    private fun canMove(before: Boolean): Boolean {
        val ic = currentInputConnection ?: return false
        if (!ic.getSelectedText(0).isNullOrEmpty()) return true
        val next = if (before) ic.getTextBeforeCursor(1, 0) else ic.getTextAfterCursor(1, 0)
        return next == null || next.isNotEmpty() // null: the app won't say, so let the key through
    }

    /** Cut, copy and paste are done by the app itself, so the keyboard never reads the clipboard. */
    override fun onEdit(action: Bar.EditAction) {
        ownEdit()
        // an aimed-at marker is only a highlight: edits act on the real cursor or selection
        if (stick.active) stick.dropMark()

        keyDuringDictation()
        val ic = currentInputConnection ?: return
        when (action) {
            Bar.EditAction.CUT -> ic.performContextMenuAction(android.R.id.cut)
            Bar.EditAction.COPY -> ic.performContextMenuAction(android.R.id.copy)
            Bar.EditAction.PASTE -> ic.performContextMenuAction(android.R.id.paste)
            Bar.EditAction.SELECT_ALL -> ic.performContextMenuAction(android.R.id.selectAll)
            // a backspace with text selected deletes the selection, like any keyboard's
            Bar.EditAction.DELETE -> sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
    }

    override fun onOpenSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** ABC: hands the field to Gboard; the way-back button over it (GboardButtonService) brings us back. */
    /** When the "way back is off" warning was shown: ABC again soon after switches anyway. */
    private var wayBackWarnedAt = 0L
    /** ABC was tapped mid-dictation: switch to Gboard once the dictation has been typed. */
    private var gboardAfterTalk = false

    /** "Voice bar, close" mid-dictation: put the keyboard away once the text is in. */
    private var hideAfterTalk = false

    /** A long press on the voice pill: send once the dictation has been typed. */
    private var sendAfterTalk = false

    override fun onSendAfterTalk() {
        dropPendingCommand()
        guard.touch()
        startSendFinder()
        if (dictation.dictating) sendAfterTalk = true else (bar as? View)?.postDelayed({ send() }, SEND_DELAY_MS)
    }

    /**
     * Sends the message: the field's own Send (or Go/Search) action when it offers one to
     * keyboards; otherwise the app's Send button, pressed through the accessibility service.
     */
    private fun send() {
        val ic = currentInputConnection
        val choice = CleanupModels.resolve(this)
        val text = runCatching { ic?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = SEND_CHECK_MAX }, 0) }
            .getOrNull()?.takeIf { it.startOffset == 0 }?.text?.toString()
        if (ic == null || choice == null || text == null || text.length >= SEND_CHECK_MAX || text.trim().split(Regex("\\s+")).size < 2 ||
            !Prefs.get(this).getBoolean(Prefs.SEND_CHECK, Prefs.DEFAULT_SEND_CHECK)) { sendNow(); return }
        // one last look at the whole message first; whatever happens, it's sent after
        checkWork.execute {
            val t0 = SystemClock.uptimeMillis()
            val checked = runCatching { SendCheck.check(text, choice, checkHttp, usage::addClaude) }.getOrNull()
            sendHandler.post {
                AppLog.log("send", "check: " + (if (checked == null) "as it was" else "fixed: " + AppLog.excerpt(checked)) + " in ${SystemClock.uptimeMillis() - t0} ms")
                // only if the field still holds what was looked at (nothing typed meanwhile)
                if (checked != null && readField() == text) currentInputConnection?.let { now ->
                    ownEdit()
                    now.beginBatchEdit(); now.setSelection(0, text.length); now.commitText(checked, 1); now.endBatchEdit()
                }
                sendNow()
            }
        }
    }

    private val checkWork = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-check").apply { isDaemon = true } }
    private val checkHttp by lazy { http.newBuilder().callTimeout(SEND_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS).build() }

    private fun sendNow() {
        // what's about to be sent, in case Send can't be found: it's kept and copied, never lost
        // (the last dictation only if it was just now: never an old message brought back)
        messageBeforeSend = readField()?.takeIf { it.isNotBlank() }
            ?: lastDelivered?.takeIf { SystemClock.uptimeMillis() - lastDeliveredAt < 15_000L }
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        val opts = info?.imeOptions ?: 0
        val action = opts and EditorInfo.IME_MASK_ACTION
        val noEnterAction = (opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (ic != null && !noEnterAction && action in SEND_ACTIONS && ic.performEditorAction(action)) { AppLog.log("send", "editor action $action"); messageBeforeSend = null; dropSendFinder(); return }
        pressSend(SEND_TRIES)
    }

    /**
     * The app's own Send button. Many apps only show or enable it once they've caught up with the
     * new text (a mic button turning into Send), which can take longer than the moment we wait
     * after typing: so look again a few times before saying it isn't there.
     */
    private fun pressSend(triesLeft: Int) {
        if (GboardButtonService.running?.clickSend() == true) { AppLog.log("send", "pressed the app's Send"); messageBeforeSend = null; lastDelivered = null; dropSendFinder(); return }
        if (triesLeft > 1 && GboardButtonService.running != null) {
            (bar as? View)?.postDelayed({ pressSend(triesLeft - 1) }, SEND_RETRY_MS) ?: sendFailed()
            return
        }
        if (!guessSend()) sendFailed()
    }

    // ---------------------------------------------------------------- the send finder

    private val sendHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sendWork = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "voicebar-send").apply { isDaemon = true } }
    private val finderHttp by lazy { http.newBuilder().callTimeout(SEND_FINDER_WAIT_MS, TimeUnit.MILLISECONDS).build() }
    /** The send finder's answer for the send under way, asked for when it was swiped (alongside the cleanup). */
    private var sendGuess: java.util.concurrent.Future<SendFinder.Pick?>? = null

    private fun sendSpot(app: String) = Prefs.get(this).getString(Prefs.SEND_SPOT_PREFIX + app, null)
    private fun rememberSpot(app: String, spot: String) { Prefs.get(this).edit().putString(Prefs.SEND_SPOT_PREFIX + app, spot).apply() }
    private fun forgetSpot(app: String) { Prefs.get(this).edit().remove(Prefs.SEND_SPOT_PREFIX + app).apply() }

    private fun dropSendFinder() { sendGuess?.cancel(true); sendGuess = null }

    /**
     * Swiped to send: with the setting on, the cleanup's model is asked now, while the cleanup runs,
     * which of the buttons beside the message box is Send, in case the usual search finds none.
     * Not asked when the field sends by itself, Send is labeled, or this app's Send is remembered.
     */
    private fun startSendFinder(late: Boolean = false) {
        dropSendFinder()
        if (!Prefs.get(this).getBoolean(Prefs.SEND_FINDER, Prefs.DEFAULT_SEND_FINDER)) return
        val svc = GboardButtonService.running ?: return
        val choice = CleanupModels.resolve(this) ?: return
        val info = currentInputEditorInfo ?: return
        val app = info.packageName ?: return
        if (sendSpot(app) != null) return
        // asked late, the usual search has already come up empty: whatever the field or a label says
        if (!late) {
            val opts = info.imeOptions
            if ((opts and EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0 && (opts and EditorInfo.IME_MASK_ACTION) in SEND_ACTIONS) return
        }
        AppLog.log("send", if (late) "finder asked now (not when sending began)" else "finder asked alongside the cleanup")
        sendGuess = sendWork.submit<SendFinder.Pick?> {
            if (!late && svc.hasLabeledSend()) return@submit null
            val row = svc.sendRow(shot = true) ?: return@submit null
            if (row.buttons.isEmpty()) { AppLog.log("send", "finder: no buttons beside the message box"); return@submit null }
            AppLog.log("send", "finder asking ${choice.model} about ${row.buttons.size} buttons" + if (row.jpegBase64 == null) " (no picture)" else "")
            SendFinder.ask(row, choice, finderHttp, usage::addClaude)?.let { SendFinder.Pick(it, row.buttons) }
        }
    }

    /**
     * The usual search found no Send: this app's remembered one, or the finder's pick (waited for a
     * moment if it isn't back yet). False when there's neither, for [sendFailed].
     */
    private fun guessSend(): Boolean {
        val svc = GboardButtonService.running ?: return false
        val app = currentInputEditorInfo?.packageName ?: return false
        sendSpot(app)?.let { spot ->
            val button = svc.sendRow(shot = false)?.let { SendFinder.at(spot, it.buttons) }
            if (button != null && svc.pressButton(button)) { AppLog.log("send", "pressed the remembered Send ($spot)"); confirmSent(app, null); return true }
            // not there any more (the app changed): forget it, and look afresh
            AppLog.log("send", "remembered Send ($spot) not found: forgotten")
            forgetSpot(app)
        }
        // not asked yet (a spoken "send", say), or it saw a labeled Send that wouldn't press: ask now
        if (sendGuess?.let { it.isDone && runCatching { it.get() }.getOrNull() == null } != false) startSendFinder(late = true)
        val f = sendGuess ?: return false
        awaitGuess(f, app, SystemClock.uptimeMillis() + SEND_FINDER_WAIT_MS)
        return true
    }

    private fun awaitGuess(f: java.util.concurrent.Future<SendFinder.Pick?>, app: String, deadline: Long) {
        if (sendGuess !== f) return
        if (!f.isDone) {
            if (SystemClock.uptimeMillis() < deadline) sendHandler.postDelayed({ awaitGuess(f, app, deadline) }, SEND_FINDER_POLL_MS)
            else { AppLog.log("send", "finder too slow"); dropSendFinder(); sendFailed() }
            return
        }
        sendGuess = null
        val pick = runCatching { f.get() }.getOrNull()
        val svc = GboardButtonService.running
        if (pick == null || svc == null || !svc.pressButton(pick.button)) { AppLog.log("send", "finder found no Send"); sendFailed(); return }
        val spot = SendFinder.spot(pick.button, pick.among)
        AppLog.log("send", "pressed the finder's pick ($spot)")
        confirmSent(app, spot)
    }

    /**
     * A guessed Send was pressed: it worked if the message has left the box. Then a new pick is
     * remembered for the app; if not, the app's memory is dropped (its layout may have changed)
     * and the message is kept, as for any Send not found.
     */
    private fun confirmSent(app: String, newSpot: String?) {
        val before = messageBeforeSend?.trim()?.takeIf { it.isNotEmpty() }
        sendHandler.postDelayed({
            val sent = before == null || !readField().orEmpty().contains(before)
            if (sent) {
                if (newSpot != null && before != null) { rememberSpot(app, newSpot); AppLog.log("send", "remembered Send for $app ($newSpot)") }
                messageBeforeSend = null
                lastDelivered = null
            } else {
                AppLog.log("send", "the pressed button didn't send: forgot $app's Send")
                forgetSpot(app)
                sendFailed()
            }
        }, SEND_CONFIRM_MS)
    }

    /** The message box's text when Send was asked for (or the last dictation's), for [sendFailed]. */
    private var messageBeforeSend: String? = null
    /** The last dictation typed into a field, and when. */
    private var lastDelivered: String? = null
    private var lastDeliveredAt = 0L

    /**
     * Send isn't there: whatever was in the message stays in the box (put back if it went
     * missing) and goes on the clipboard too, so it's never lost.
     */
    private fun sendFailed() {
        AppLog.log("send", "Send not found; service=${GboardButtonService.running != null} kept=" + AppLog.excerpt(messageBeforeSend))
        val text = messageBeforeSend?.takeIf { it.isNotBlank() }
        messageBeforeSend = null
        var copied = false
        if (text != null) {
            val now = readField().orEmpty()
            if (!now.contains(text.trim())) typeDictation(text)
            copied = copyDictation(text)
        }
        // the Gboard button's service is what presses an app's own Send: off, say so, with a tap to fix it
        if (GboardButtonService.running == null) { bar?.showMessage(getString(R.string.send_needs_service)) { openAccessibility() }; return }
        bar?.showMessage(getString(if (copied) R.string.send_not_found_kept else R.string.send_not_found))
    }

    private fun openAccessibility() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Android sometimes switches the Gboard button's service off by itself (after the app's
     * process crashes or is killed a few times in a row, or a battery saver steps in). When it was
     * on before and isn't now, say so on the bar, at most once a day, with a tap to fix it.
     */
    private fun warnIfWayBackSwitchedOff() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        val p = Prefs.get(this)
        if (!p.getBoolean(Prefs.WAY_BACK_WAS_ON, false) || GboardButtonService.running != null) return
        val now = System.currentTimeMillis()
        if (now - p.getLong(Prefs.WAY_BACK_WARNED_AT, 0) < 24 * 3600_000L) return
        p.edit().putLong(Prefs.WAY_BACK_WARNED_AT, now).apply()
        val msg = if (GboardButtonService.isEnabled(this)) R.string.way_back_stopped else R.string.way_back_switched_off
        bar?.showMessage(getString(msg)) { openAccessibility() }
    }

    override fun onTypeAfterTalk() {
        dropPendingCommand()
        guard.touch()
        if (dictation.dictating) gboardAfterTalk = true else onSwitchToGboard()
    }

    override fun onSwitchToGboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        val id = Prefs.gboardImeId(this)
        if (id == null) { bar?.showMessage(getString(R.string.gboard_not_enabled)); return }
        // without the way-back service there'd be no button on Gboard to come back with: say so first
        val now = SystemClock.uptimeMillis()
        if (android.os.Build.VERSION.SDK_INT >= 30 && !GboardButtonService.isEnabled(this) && now - wayBackWarnedAt > WAY_BACK_WARNING_MS) {
            wayBackWarnedAt = now
            bar?.showMessage(getString(R.string.way_back_off)) {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return
        }
        wayBackWarnedAt = 0L
        val ok = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 28) switchInputMethod(id)
            else @Suppress("DEPRECATION") window.window?.attributes?.token?.let { imm.setInputMethod(it, id) }
        }.isSuccess
        if (!ok) bar?.showMessage(getString(R.string.gboard_not_enabled))
    }

    override fun onSwitchKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java) ?: return
        imm.showInputMethodPicker()
    }

    override fun shouldCapitalize(): Boolean {
        val ic = currentInputConnection ?: return false
        val inputType = currentInputEditorInfo?.inputType ?: return false
        return ic.getCursorCapsMode(inputType) != 0
    }

    override fun onTalkStart(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bar?.showMessage(getString(R.string.voice_need_mic))
            return false
        }
        if (dictation.dictating) return false // still finishing the last one
        // no text box (the app hasn't connected its box to the keyboard, or it went): get back to it
        // first, then start listening into it; if it doesn't come back, listen anyway (to the clipboard)
        val now = SystemClock.uptimeMillis()
        if (!hasTextField() && now >= regainUntil + 2_000L && askForField()) {
            regainUntil = now + REGAIN_MS
            awaitField(REGAIN_MS, then = { bar?.startVoice() }, otherwise = { bar?.startVoice() })
            return false
        }
        // dictating by hand: hands-free mode lets go of the microphone
        if (com.ryderdavid.voicebar.voice.HandsFreeStart.active) com.ryderdavid.voicebar.voice.HandsFreeStart.stop()
        // a new dictation: the last one's corrections, if any, are in by now
        if (CorrectionWatch.pending?.field == fieldKey) CorrectionWatch.check(readField())
        // the wake-word watcher lets go of the mic for the real dictation
        WakeWord.want(this, WAKE_OWNER, null)
        bar?.showCommand(null)
        refreshCanPaste() // the paste button beside the voice pill shows what's on the clipboard
        forcePolish = false
        ownEdit()
        toClipboard = !hasTextField()
        lastLiveText = ""
        val info = currentInputEditorInfo
        val chatOrEmail = com.ryderdavid.voicebar.voice.ChatApps.isChat(info) ||
            info?.packageName in com.ryderdavid.voicebar.voice.AutoListenGuard.EMAIL_PACKAGES
        guard.start(autoStartPending, chatOrEmail, Prefs.get(this).getFloat(Prefs.NEAR_LEVEL, com.ryderdavid.voicebar.voice.AutoListenGuard.DEFAULT_TYPICAL),
            careful = Prefs.get(this).getBoolean(Prefs.CHAT_CARE, Prefs.DEFAULT_CHAT_CARE))
        manualLevels.clear()
        // an auto-started dictation in a chat or email app previews in the pill: nothing reaches the
        // message box until it's clearly yours (see onResult)
        liveTyping = !toClipboard && !guard.careful && Prefs.get(this).getBoolean(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE) &&
            fieldTakesLiveText(currentInputEditorInfo)
        AppLog.log("dictation", "start auto=${guard.auto} chatOrEmail=$chatOrEmail live=$liveTyping toClipboard=$toClipboard in ${info?.packageName}")
        (bar as? View)?.let { v ->
            v.removeCallbacks(guardWindow); v.removeCallbacks(guardCap)
            if (guard.auto) v.postDelayed(guardWindow, com.ryderdavid.voicebar.voice.AutoListenGuard.START_WINDOW_MS)
            if (guard.careful) v.postDelayed(guardCap, com.ryderdavid.voicebar.voice.AutoListenGuard.CAP_MS)
        }
        // nothing reaches the field until the guard engages, so until then the pill keeps the words
        bar?.voiceInField = guard.typesLive(liveTyping)
        aroundCursor = readAround()
        fieldCleaner = null
        return dictation.start()
    }

    /** This dictation began with no text field to type into: it goes on the clipboard instead. */
    private var toClipboard = false

    // ---------------------------------------------------------------- fixing what's in the box

    /** Everything in the box when "Fix last dictation" started listening: what it fixes, hand edits and all. */
    private var fixTarget: String? = null
    /** The fix request is out. */
    private var fixRunning = false
    private val fixHttp by lazy { http.newBuilder().callTimeout(15, TimeUnit.SECONDS).build() }

    /** Listens for what to fix: shown in the pill, never typed, never cleaned. */
    private val fixer: DictationController by lazy {
        DictationController(
            audio = MicRecorder(),
            transcriberFactory = { pickTranscriber()?.let { com.ryderdavid.voicebar.voice.VoiceFilterTranscriber.wrap(this, it, firstVoice = true) } },
            cleanerFactory = { null },
            ui = object : DictationController.Ui {
                override fun onListening() { bar?.showListening() }
                override fun onRecognizerReady() { bar?.heardSpeech(); bar?.showReady() }
                override fun onLevel(level: Float) { bar?.showLevel(level) }
                override fun onHeard() { bar?.heardSpeech() }
                override fun onLiveText(text: String) { bar?.showLiveText(text) }
                override fun onCleaning(raw: String) {}
                override fun onResult(text: String) { runFix(text) }
                override fun onIdle() {
                    (bar as? View)?.post { updateWake() }
                    bar?.talkEnded()
                    // nothing said: the cleanup just takes another, closer look
                    if (fixTarget != null && !fixRunning) runFix("")
                    if (!fixRunning) bar?.hideVoice()
                }
                override fun onError(message: String) {
                    AppLog.log("error", "fix: $message")
                    fixTarget = null
                    (bar as? View)?.post { updateWake() }
                    bar?.talkEnded()
                    bar?.showMessage(message)
                }
            },
        )
    }

    override fun onFixStart(): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            bar?.showMessage(getString(R.string.voice_need_mic))
            return false
        }
        if (dictation.dictating || fixer.dictating || fixRunning) return false
        // the whole box as it is now, not the last dictation as it was typed: edits by hand are fine
        val ext = if (hasTextField() && fieldTakesLiveText(currentInputEditorInfo)) boxExtract() else null
        val box = ext?.text?.toString()
        when {
            ext == null || box == null -> { bar?.showNote(getString(R.string.fix_no_box)); return false }
            ext.startOffset != 0 || box.length > FIX_MAX -> { bar?.showNote(getString(R.string.fix_too_long)); return false }
            box.isBlank() -> { bar?.showNote(getString(R.string.fix_nothing_yet)); return false }
        }
        if (CleanupModels.resolve(this) == null) { bar?.showMessage(getString(R.string.dictation_reclean_no_key)); return false }
        if (com.ryderdavid.voicebar.voice.HandsFreeStart.active) com.ryderdavid.voicebar.voice.HandsFreeStart.stop()
        WakeWord.want(this, WAKE_OWNER, null)
        bar?.showCommand(null)
        bar?.voiceInField = false
        fixTarget = box
        AppLog.log("fix", "listening for what to fix in: " + AppLog.excerpt(box))
        return fixer.start().also { if (!it) fixTarget = null }
    }

    /** The box's text, fixed with what the user said was wrong ([instructions]; blank for "look again"). */
    private fun runFix(instructions: String) {
        val inBox = fixTarget ?: return
        val choice = CleanupModels.resolve(this) ?: run { fixTarget = null; bar?.showMessage(getString(R.string.dictation_reclean_no_key)); return }
        fixRunning = true
        bar?.showCleaning(inBox)
        AppLog.log("fix", "with ${choice.model}: " + if (instructions.isBlank()) "no instructions, look again" else "“" + AppLog.excerpt(instructions) + "”")
        val dictionary = Dictionary.of(this).promptSection()
        checkWork.execute {
            val t0 = SystemClock.uptimeMillis()
            // the box's own leading and trailing space (a trailing space before the next word, a final newline) is kept
            val fixed = runCatching { DictationFix.fix(inBox.trim(), instructions, choice, fixHttp, usage::addClaude, dictionary) }.getOrNull()
                ?.let { inBox.takeWhile(Char::isWhitespace) + it + inBox.takeLastWhile(Char::isWhitespace).takeIf { _ -> inBox.isNotBlank() }.orEmpty() }
            sendHandler.post {
                fixRunning = false
                fixTarget = null
                bar?.hideVoice()
                AppLog.log("fix", (fixed?.let { "→ " + AppLog.excerpt(it) } ?: "failed") + " in ${SystemClock.uptimeMillis() - t0} ms")
                when {
                    fixed == null -> bar?.showMessage(getString(R.string.fix_failed, choice.model))
                    fixed == inBox -> bar?.showMessage(getString(R.string.fix_unchanged))
                    replaceBox(inBox, fixed) -> Unit
                    // the box changed (or went) while the fix was out: it's on the clipboard instead
                    copyDictation(fixed) -> bar?.showMessage(getString(R.string.fix_not_in_box))
                }
            }
        }
    }

    /**
     * The box's text, asked for up to one character more than [FIX_MAX]: a longer box comes back
     * longer than that, or only around the cursor (not from its start). Null when it can't be read.
     */
    private fun boxExtract() = runCatching {
        currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest().apply { hintMaxChars = FIX_MAX + 1 }, 0)
    }.getOrNull()

    /** The box's text, still [old], becomes [new]; false when the box has changed since (or is gone). */
    private fun replaceBox(old: String, new: String): Boolean {
        val ic = currentInputConnection ?: return false
        if (boxExtract()?.takeIf { it.startOffset == 0 }?.text?.toString() != old) return false
        ownEdit()
        ic.beginBatchEdit(); ic.setSelection(0, old.length); ic.commitText(new, 1); ic.endBatchEdit()
        return true
    }

    /** The next dictation is being started by a field opening (auto-listen), not a tap. */
    private var autoStartPending = false
    private val guard = com.ryderdavid.voicebar.voice.AutoListenGuard()
    /** Levels of a dictation started by hand, to learn how loud the user talks at the phone. */
    private val manualLevels = ArrayList<Float>()
    /** The guard is stopping this dictation itself (not the user's ✕). */
    private var guardStopping = false
    /** Nobody talked at the phone in the start window: stop, quietly, with nothing typed. */
    private val guardWindow = Runnable {
        if (!dictation.dictating || !guard.expired()) return@Runnable
        AppLog.log("guard", "start window passed with nobody talking at the phone: stopped" +
            " (loudest ${"%.2f".format(guard.peak)}, needs ${"%.2f".format(guard.nearLevel)})" +
            if (lastLiveText.isBlank()) "" else ", though it had heard " + AppLog.excerpt(lastLiveText))
        guardStopping = true
        bar?.reset()
        guardStopping = false
        bar?.hideVoice()
    }
    /** Held words are showing in the pill: they belong to this field and go when it does. */
    private var heldShown = false
    /** An untouched auto-started dictation in a chat or email app has run a minute: end it (its text is held). */
    private val guardCap = Runnable { if (dictation.dictating && guard.capped()) bar?.finishVoice() }

    /** A text field is focused and connected (not a raw-key view or nothing at all). */
    private fun hasTextField(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return currentInputConnection != null && info.inputType != InputType.TYPE_NULL
    }

    /** Puts dictated [text] on the clipboard; false if the system wouldn't take it. */
    private fun copyDictation(text: String): Boolean = runCatching {
        val cm = getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(android.content.ClipData.newPlainText(getString(R.string.dictation_clip_label), text))
        true
    }.getOrDefault(false)

    /** ✓ on the docked bar: always polish, whatever the cleanup setting says. */
    private var forcePolish = false

    /**
     * Claude Haiku or GPT-6 Luna: the one chosen, or whichever has a key; null when cleanup is off
     * (unless [polish] forces it) or there's no key. [forField]: it's for the field being typed in, so
     * it gets the screen's text and the text around the cursor; a retry from the ⋮ menu gets neither.
     */
    private fun newCleaner(polish: Boolean, forField: Boolean): com.ryderdavid.voicebar.voice.Cleaner? {
        val prefs = Prefs.get(this)
        val choice = CleanupModels.resolve(this)
        AppLog.log("cleanup", "model ${choice?.model ?: "none (no key)"} cleanupOn=${prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)} forced=$polish")
        if ((!polish && !prefs.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)) || choice == null) return null
        val screen = { if (!forField) "" else runCatching { screenNow?.get(SCREEN_WAIT_MS, TimeUnit.MILLISECONDS) }.getOrNull()?.prompt ?: "" }
        val around = { if (forField) aroundCursor else null }
        val cleaner = if (choice.provider == CleanupModels.Provider.OPENAI) com.ryderdavid.voicebar.voice.OpenAICleaner(
            // a little more room than Claude gets: a slow first reply shouldn't lose the cleanup
            choice.key, choice.model, http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build(),
            dictionary = { Dictionary.of(this).promptSection() },
            screen = screen,
            children = { com.ryderdavid.voicebar.voice.ChildVoices.enabled(this) },
            around = around,
            onUsage = usage::addClaude,
        )
        else ClaudeCleaner(
            choice.key, choice.model, http,
            dictionary = { Dictionary.of(this).promptSection() },
            screen = screen,
            children = { com.ryderdavid.voicebar.voice.ChildVoices.enabled(this) },
            around = around,
            onUsage = usage::addClaude,
        )
        if (forField) fieldCleaner = cleaner
        return cleaner
    }

    /**
     * A dictation tapped in the ⋮ menu: what was said goes to the cleanup model again, and the new
     * cleanup goes on the clipboard (and becomes that dictation's typed text). For a bad cleanup.
     */
    private fun reclean(e: DictationHistory.Entry) {
        val b = bar ?: return
        if (b.recleaning != null) return
        val raw = e.heard.ifBlank { e.typed }
        val cleaner = newCleaner(polish = true, forField = false)
            ?: return b.showMessage(getString(R.string.dictation_reclean_no_key))
        b.recleaning = e.time
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val t0 = System.currentTimeMillis()
            AppLog.log("cleanup", "again from the menu, start with ${cleaner.service}: " + AppLog.excerpt(raw))
            // the spoken formatting ("title case … end title case") the same way as when it was said
            val attempt = runCatching { cleaner.clean(com.ryderdavid.voicebar.voice.SpokenFormat.mark(raw)) }
            val result = attempt.getOrNull()?.takeIf { it.isNotBlank() }?.let { com.ryderdavid.voicebar.voice.SpokenFormat.render(it) }
            val why = if (result != null) null
                else attempt.exceptionOrNull()?.let { com.ryderdavid.voicebar.voice.cleanupFailure(it, cleaner.service) } ?: cleaner.problem ?: "no reply from ${cleaner.service}"
            AppLog.log("cleanup", "again from the menu, " + (if (why == null) "ok" else "failed ($why)") + " in ${System.currentTimeMillis() - t0} ms" +
                (result?.let { ": " + AppLog.excerpt(it) } ?: ""))
            main.post {
                val bar = bar
                bar?.recleaning = null
                if (result == null) { bar?.showMessage(getString(R.string.dictation_reclean_failed, why)); return@post }
                if (!copyDictation(result)) { bar?.showMessage(getString(R.string.dictation_reclean_failed, "the clipboard wouldn't take it")); return@post }
                DictationHistory.of(this).retype(e.time, result)
                bar?.dictations = DictationHistory.of(this).all()
                bar?.showRecleaned(e.time)
            }
        }, "voicebar-reclean").start()
    }
    /** This dictation ended by itself (silence or the cap), not by the user. */
    private var endedBySilence = false

    override fun onTalkFinish(polish: Boolean) {
        if (fixer.dictating) { fixer.stop(false); return }
        dropPendingCommand()
        // ✓ or the pill polishes: the user ended it, so it's theirs; the silence timeout and the cap don't
        if (polish) guard.touch()
        endedBySilence = !polish
        forcePolish = polish
        onTalkEnd(false)
    }

    override fun onDeleteAll() {
        val ic = currentInputConnection ?: return
        ownEdit()
        keyDuringDictation()
        ic.beginBatchEdit()
        val before = ic.getTextBeforeCursor(100_000, 0)?.length ?: 0
        val after = ic.getTextAfterCursor(100_000, 0)?.length ?: 0
        val sel = ic.getSelectedText(0)?.length ?: 0
        if (sel > 0) ic.commitText("", 1)
        if (before > 0 || after > 0) ic.deleteSurroundingText(before, after)
        ic.endBatchEdit()
    }

    /** A pause in hands-free dictation: polish what's been said so far, keep listening. */
    override fun onQuiet() { if (!fixer.dictating) dictation.pause() }

    override fun onTalkEnd(cancel: Boolean) {
        if (fixer.dictating) {
            // what to fix, thrown away: nothing is fixed
            if (cancel) { AppLog.log("fix", "cancelled"); fixTarget = null }
            fixer.stop(cancel)
            return
        }
        if (cancel && !guardStopping) guard.touch()
        (bar as? View)?.let { it.removeCallbacks(guardWindow); it.removeCallbacks(guardCap) }
        if (cancel && liveTyping) liveTyper.cancel()
        if (cancel) takeBackRestoredPeriod()
        dictation.stop(cancel)
    }

    /**
     * The field's text either side of the cursor as this dictation began (never a password), so the
     * cleanup fits the new words into that spot: capitals, punctuation, the right sound-alike.
     */
    @Volatile private var aroundCursor: com.ryderdavid.voicebar.voice.Around? = null

    private fun readAround(): com.ryderdavid.voicebar.voice.Around? {
        if (!fieldTakesLiveText(currentInputEditorInfo)) return null
        val ic = currentInputConnection ?: return null
        return runCatching {
            com.ryderdavid.voicebar.voice.Around.of(
                ic.getTextBeforeCursor(com.ryderdavid.voicebar.voice.Around.BEFORE_CHARS, 0),
                ic.getTextAfterCursor(com.ryderdavid.voicebar.voice.Around.AFTER_CHARS, 0),
                ic.getSelectedText(0))
        }.getOrNull()
    }

    /**
     * The cleaner this dictation's text came from (the last one made for the field), to read the
     * leftover words its reply named; null without cleanup.
     */
    @Volatile private var fieldCleaner: com.ryderdavid.voicebar.voice.Cleaner? = null

    /** Takes out the leftover words [fit] found at either side of [typed], now right before the cursor. */
    private fun removeLeftovers(typed: String, fit: Infill.Fit) {
        if (fit.removeBefore.isEmpty() && fit.removeAfter.isEmpty()) return
        ownEdit()
        val done = Infill.removeLeftovers(liveField, typed, fit)
        AppLog.log("infill", if (done) "took leftovers out of the field" else "leftovers weren't where expected: left alone")
    }

    /** Dictated into the middle of text: a space between the new words and a word right after them. */
    private fun spaceAfterInsert(text: String) {
        val ic = currentInputConnection ?: return
        if (text.isEmpty() || text.last().isWhitespace()) return
        val next = ic.getTextAfterCursor(1, 0)?.firstOrNull() ?: return
        if (next.isLetterOrDigit()) { ownEdit(); ic.commitText(" ", 0) } // the cursor stays before the space
    }

    /** Types the dictated text, with a space before it when it would otherwise run into a word. */
    private fun typeDictation(text: String) {
        ownEdit()
        val ic = currentInputConnection ?: return
        val before = ic.getTextBeforeCursor(1, 0)
        val sep = if (before.isNullOrEmpty() || before.last().isWhitespace()) "" else " "
        ic.commitText(sep + text, 1)
    }
}

/** The end of a normalized selection report [start]..[end] that isn't [anchor] (see Stick.movingEnd). */
internal fun stickMovingEnd(anchor: Int, target: Int, start: Int, end: Int): Int = when (anchor) {
    start -> end
    end -> start
    else -> if (kotlin.math.abs(start - target) < kotlin.math.abs(end - target)) start else end
}

/** Your personal dictionary's words, in the swipe dictionary: as common as a fairly common word (of 255). */
private const val PERSONAL_WORD_FREQ = 160

/** Two taps on space this close together type a period. */
private const val DOUBLE_SPACE_MS = 450L

/** How long after the way-back warning a second ABC tap switches anyway. */
private const val WAY_BACK_WARNING_MS = 6_000L

/** The keyboard's claim on the wake-word watcher (the Gboard button has its own). */
private const val WAKE_OWNER = "bar"

/** How long a spoken command must stay the last thing said before it runs. */
private const val COMMAND_HOLD_MS = 700L

/** Looking for the app's Send button: this many times, this far apart (about 1.5 s in all). */
private const val SEND_TRIES = 8
private const val SEND_RETRY_MS = 200L

/** After the dictation lands, a moment for the app to see the text before Send is pressed. */
private const val SEND_DELAY_MS = 200L
/** Reaching ElevenLabs ahead of a dictation: at most this often, and logged when slower than this. */
private const val SPEECH_WARM_EVERY_MS = 60_000L
private const val MIC_CHECK_CHUNKS = 20
private const val SPEECH_WARM_SLOW_MS = 1_500L
/** How long the send finder may still take once the usual search has given up, checked this often. */
private const val SEND_FINDER_WAIT_MS = 4_000L
/** The last look before sending: messages up to this long, and no longer than this wait. */
private const val SEND_CHECK_MAX = 5_000
/** Voice tapped with no text box: how long to wait for the box to come back before listening anyway. */
private const val REGAIN_MS = 1_000L
/** A dictation finished with no text box: how long to wait for it before the clipboard. */
private const val REGAIN_FINISH_MS = 1_500L
/** The most text a fix by voice sends: a long email, not a document. */
private const val FIX_MAX = 8_000
private const val SEND_CHECK_TIMEOUT_MS = 4_000L
private const val SEND_FINDER_POLL_MS = 120L
/** After pressing a guessed Send, how long the app gets to empty the message box. */
private const val SEND_CONFIRM_MS = 900L
/** Field actions that mean "send this": the field's own Send, Go or Search. */
private val SEND_ACTIONS = setOf(EditorInfo.IME_ACTION_SEND, EditorInfo.IME_ACTION_GO, EditorInfo.IME_ACTION_SEARCH)
