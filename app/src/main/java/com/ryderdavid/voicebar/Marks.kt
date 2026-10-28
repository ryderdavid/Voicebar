package com.ryderdavid.voicebar

/**
 * The punctuation palette's marks (an own key set to Punctuation opens it): three pages of
 * twelve, what holding a mark offers, the recent row, and how a mark goes in at the cursor.
 */
object Marks {
    val PAGES = listOf(
        listOf(".", ",", "?", "!", "'", "\"", ":", ";", "-", "…", "/", "@"),
        listOf("(", ")", "#", "&", "*", "$", "%", "+", "=", "_", "~", "^"),
        listOf("<", ">", "[", "]", "{", "}", "|", "\\", "`", "°", "•", "§"),
    )

    /** Held, these offer more of their kind: curly quotes, dashes, other currencies. */
    val VARIANTS = mapOf(
        "'" to listOf("’", "‘"),
        "\"" to listOf("“", "”"),
        "-" to listOf("–", "—"),
        "$" to listOf("€", "£", "¥", "¢"),
    )

    const val RECENT = 6

    /** The recent row before there's enough history: common marks that are awkward to say. */
    private val STARTERS = listOf("?", "!", "@", "&", "#", "/")

    /** Marks that close up to the word before them. */
    private const val ATTACH = ".,?!:;…)]}”’"

    /** The recent row: the last marks used, newest first, topped up with [STARTERS]. */
    fun recent(saved: String?): List<String> =
        (saved.orEmpty().split('\n').filter { it.isNotEmpty() } + STARTERS).distinct().take(RECENT)

    /** What's saved after [mark] is used: it moves to the front. */
    fun used(saved: String?, mark: String): String =
        (listOf(mark) + saved.orEmpty().split('\n').filter { it.isNotEmpty() && it != mark }).take(RECENT).joinToString("\n")

    /**
     * How [mark] goes in after [before] (the text before the cursor): how many characters to take
     * back first, and what to type. A mark that closes up to a word, after the space a dictation
     * left, takes that space's place and keeps it after itself: "there " and ! give "there! ".
     */
    fun insertion(before: CharSequence?, mark: String): Pair<Int, String> {
        val b = before?.toString().orEmpty()
        if (mark.length == 1 && mark[0] in ATTACH && b.length >= 2 && b.last() == ' ' && !b[b.length - 2].isWhitespace())
            return 1 to "$mark "
        return 0 to mark
    }
}
