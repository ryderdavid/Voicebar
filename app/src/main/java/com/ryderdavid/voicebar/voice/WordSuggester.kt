package com.ryderdavid.voicebar.voice

import org.json.JSONObject

/**
 * Finishes the word being typed on Voicebar's keyboard: your own words first (the ones you type
 * most, and your personal dictionary), then English by how common it is (the bundled list,
 * most common first). Matches keep the case you started with: "Th" gives "The", "TH" gives "THE".
 */
class WordSuggester(
    /** English, most common first (assets/words_en.txt). */
    private val words: List<String>,
    /** Your personal dictionary's terms, read when suggesting. */
    private val extra: () -> Collection<String> = { emptyList() },
) {
    /** Words you've typed on the keyboard, and how often. */
    private val typed = HashMap<String, Int>()

    /** Up to [n] words that finish [prefix], best first; none for an empty prefix. */
    @Synchronized fun suggest(prefix: String, n: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        val out = LinkedHashMap<String, String>()
        fun offer(w: String) {
            val l = w.lowercase()
            if (l.length > p.length && l.startsWith(p) && l !in out) out[l] = w
        }
        typed.entries.sortedByDescending { it.value }.forEach { offer(it.key) }
        extra().forEach { offer(it) }
        for (w in words) { if (out.size >= n) break; offer(w) }
        return out.values.take(n).map { caseLike(prefix, it) }
    }

    /** A word typed on the keyboard (a word just finished by a space or punctuation). */
    @Synchronized fun learn(word: String) {
        if (word.length < 2 || word.any { !it.isLetter() && it != '\'' }) return
        // the dictionary's own capitals win ("I'm", names); otherwise remember it as typed
        val key = words.firstOrNull { it.equals(word, ignoreCase = true) } ?: if (word.all { it.isUpperCase() }) word.lowercase() else word
        typed[key] = (typed[key] ?: 0) + 1
        if (typed.size > MAX_TYPED) typed.entries.minByOrNull { it.value }?.let { typed.remove(it.key) }
    }

    @Synchronized fun save(): String = JSONObject(typed as Map<*, *>).toString()

    @Synchronized fun load(json: String?) {
        typed.clear()
        runCatching { val o = JSONObject(json ?: return); for (k in o.keys()) typed[k] = o.getInt(k) }
    }

    companion object {
        const val MAX_TYPED = 1000

        /** [word] in the case [prefix] was typed in: capitalized, all capitals, or as the word has it. */
        fun caseLike(prefix: String, word: String): String = when {
            prefix.length > 1 && prefix.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            prefix[0].isUpperCase() -> word.replaceFirstChar { it.uppercaseChar() }
            else -> word
        }

        /** The word being typed: the letters (and apostrophes) right before the cursor. */
        fun currentWord(before: CharSequence?): String {
            val s = before?.toString() ?: return ""
            var i = s.length
            while (i > 0 && (s[i - 1].isLetter() || s[i - 1] == '\'' || s[i - 1] == '’')) i--
            return s.substring(i)
        }

        /**
         * [typed] right after the [swiped] word (the cursor still at its end) starts a new word, as
         * the swipe itself would have: a space goes in first. Letters and digits only; punctuation
         * still follows the word directly.
         */
        fun spaceAfterSwipe(before: CharSequence?, swiped: String?, typed: String): Boolean =
            swiped != null && typed.length == 1 && typed[0].isLetterOrDigit() && currentWord(before) == swiped

        /**
         * How much ⌫ takes back right after a swipe, as Gboard does: all of what the swipe [added]
         * (the word, and the space it put before it) when the text before the cursor still ends
         * with it; 0 (one letter, as usual) when it doesn't.
         */
        fun swipeToDelete(before: CharSequence?, added: String?): Int =
            if (!added.isNullOrEmpty() && before != null && before.endsWith(added)) added.length else 0
    }
}
