package com.ryderdavid.voicebar

/**
 * What the service drives on screen: the voice bar ([DockView]), which reports touches
 * through [Bar.Listener].
 */
interface KeyboardSurface {
    var hasSelection: Boolean
    var canPaste: Boolean
    /** The start of what's on the clipboard, for the paste button while dictating; null when nothing. */
    var clipPreview: String?
    /** The last few dictations, newest first, for the ⋮ menu. */
    var dictations: List<DictationHistory.Entry>
    /** Over Voicebar's keyboard: words that finish the one being typed, best first. */
    var keySuggestions: List<String>
    /** The field wants a capital at the cursor (the start of a sentence). */
    var keyAutoCap: Boolean
    /** The field's action (EditorInfo.IME_ACTION_*), for the keyboard's ↵. */
    var enterAction: Int
    /** The dictation (by its time) being cleaned again from the ⋮ menu, or null. */
    var recleaning: Long?
    /** "Title case" was said and not yet ended: the recording pill says so. */
    fun showTitleCase(on: Boolean) {}
    /** That dictation's new cleanup is on the clipboard: its card says so for a moment. */
    fun showRecleaned(time: Long) {}
    var voiceInField: Boolean
    val voice: Bar.Voice

    /** Listening has started: the pill says "Connecting…" until [showReady]. */
    fun showListening()
    /** The recognizer is connected (or loaded) and hearing: "Connecting…" gives way to listening. */
    fun showReady() {}
    fun showLevel(level: Float)
    fun showLiveText(text: String)
    fun showCleaning(text: String)
    /**
     * "Voice bar" was heard: the pill turns purple and shows what's said after it ([said], maybe
     * empty), bold once it's a command ([understood]). Null puts the pill back.
     */
    fun showCommand(said: String?, understood: Boolean = false)
    /** A short note in the voice pill; with [onTap], tapping it runs that (and it stays up longer). */
    fun showMessage(message: String, onTap: (() -> Unit)? = null)
    /** Good news (something learned), in the calm primary tone rather than the warning one. */
    fun showNote(message: String, onTap: (() -> Unit)? = null) = showMessage(message, onTap)
    fun hideVoice()
    fun heardSpeech()
    fun talkEnded()
    /** Start dictating as if the mic were tapped (used when coming back from Gboard). */
    fun startVoice()
    /** End the dictation as the silence timeout would (no polish forced). */
    fun finishVoice() {}
    /** Finished words waiting for a tap to go in ([onTap] types them); stays up a while. */
    fun showHeld(message: String, onTap: () -> Unit) = showMessage(message, onTap)
    fun reset()
    fun reloadSettings()
}
