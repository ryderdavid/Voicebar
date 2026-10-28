package com.ryderdavid.voicebar

/** What the voice bar ([DockView]) and the service ([VoicebarService]) share. */
object Bar {
    enum class Key { UP, DOWN, LEFT, RIGHT, SPACE, DELETE, ENTER, UNDO }

    /**
     * The seven-button bar's own key, between ⋮ and Cursor (⌫ and ↵ always end the bar): space
     * (slide it to move the cursor), paste, undo, select all, or the last dictation typed again.
     */
    /** What one of the seven-button bar's own keys does. In picker order, bottom up: the typing keys nearest the thumb. */
    enum class ExtraKey(val pref: String) {
        SPACE("space"), BACKSPACE("backspace"), ENTER("enter"), PUNCTUATION("punctuation"), PASTE("paste"), UNDO("undo"), SELECT_ALL("select_all"),
        LAST_DICTATION("last_dictation"), FIX_LAST("fix_last"), STYLE("style");
        companion object {
            fun of(pref: String?, default: ExtraKey = SPACE) = entries.firstOrNull { it.pref == pref } ?: default
        }
    }

    enum class EditAction(val glyph: Icons.Glyph) { CUT(Icons.Glyph.CUT), COPY(Icons.Glyph.COPY), PASTE(Icons.Glyph.PASTE), DELETE(Icons.Glyph.BACKSPACE), SELECT_ALL(Icons.Glyph.SELECT_ALL) }

    enum class Voice { OFF, LISTENING, CLEANING, MESSAGE }

    /** When a quiet stretch triggers the pause cleanup: halfway to the silence timeout, at most 5 s in. */
    fun quietMs(silenceMs: Long) = minOf(5_000L, silenceMs / 2)

    /** The bar's touches, reported to the service. */
    /**
     * A recent dictation in the ⋮ menu: paste it as typed, as heard (before cleanup), delete it, or
     * clean what was heard again and copy that (a tap on its card).
     */
    enum class DictationAction { PASTE_TYPED, PASTE_HEARD, DELETE, RECLEAN }

    interface Listener {
        fun onKey(key: Key, repeat: Boolean)
        /** A recent dictation (by its time) in the ⋮ menu. */
        fun onDictation(action: DictationAction, time: Long) {}
        /** The last-dictation key: type the most recent dictation again, as it was typed. */
        fun onRepeatDictation() {}
        /** Voicebar's keyboard typed [text] (a character or a space). */
        fun onText(text: String) {}
        /** A mark from the punctuation palette, to go in at the cursor (see [Marks.insertion]). */
        fun onMark(mark: String) = onText(mark)
        /** A suggestion over Voicebar's keyboard was tapped: [word] replaces the word being typed. */
        fun onSuggestion(word: String) {}
        /** Voicebar's keyboard opened or closed (suggestions and capitals are kept up to date while it's open). */
        fun onKeysShown(open: Boolean) {}
        /**
         * A swipe across the keyboard's letters, to type as a word: its points and the a-to-z key
         * centers in the [0, 1] frame of the letter rows; [caps] 0 none, 1 a capital, 2 all capitals.
         */
        fun onSwipe(points: List<com.ryderdavid.voicebar.swipe.SwipeDecoder.Point>, keys: FloatArray, caps: Int) {}
        /** Show the system keyboard picker. */
        fun onSwitchKeyboard()
        fun onOpenSettings() {}
        /** ABC: hand the field to Gboard for typing. */
        fun onSwitchToGboard() {}
        /** A swipe up on the purple "voice bar" pill: not a command after all; the words stay text. */
        fun onDismissCommand() {}
        /** A swipe right on the voice pill while dictating: finish (polished), then send. */
        fun onSendAfterTalk() {}
        /** ABC while dictating: finish (polished), then hand the field to Gboard. */
        fun onTypeAfterTalk() = onSwitchToGboard()
        /** Dictation finished (not thrown away); [polish] runs Claude's cleanup even when it's off. */
        fun onTalkFinish(polish: Boolean) = onTalkEnd(false)
        /** Clear the whole field. */
        fun onDeleteAll() {}
        /** Whether the next letter should be capitalized. */
        fun shouldCapitalize(): Boolean = false
        /** Start dictation; false if it couldn't start. */
        fun onTalkStart(): Boolean = false
        /**
         * Listen for what to fix in the last dictation (Voice held, "Fix last dictation"): what's said
         * is instructions for the cleanup, never typed. False when it couldn't start.
         */
        fun onFixStart(): Boolean = false
        /** Cut, copy, paste or delete on the current selection. */
        fun onEdit(action: EditAction) {}
        /** Dictation should end: type it, or throw it out when [cancel]. */
        fun onTalkEnd(cancel: Boolean) {}
        /** Dictation has been quiet for a while: polish what's been said so far and keep listening. */
        fun onQuiet() {}
        /** The cursor pad: a drag started. */
        fun onStickStart() {}
        /** Move [cols] characters and [lines] lines; while [selecting] it extends the selection. */
        fun onStickStep(cols: Int, lines: Int, selecting: Boolean) {}
        fun onStickTap() {}
        /** A hold on the pad: a selection starts at the cursor. */
        fun onStickSelect() {}
        fun onStickEnd() {}
    }
}
