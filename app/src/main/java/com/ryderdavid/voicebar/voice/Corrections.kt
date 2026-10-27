package com.ryderdavid.voicebar.voice

import kotlin.math.max
import kotlin.math.min

/**
 * Finds the user's hand corrections to text Voicebar typed: [inserted] is what we typed, [field]
 * is the field's text later. The inserted words are located in the field, the two are aligned
 * word by word, and each small substitution (a word or a short phrase swapped for another that
 * sounds alike) is a candidate correction, with a few words of context. Rewrites, added or
 * deleted words, number changes and case-only edits are left out. Pure, so it can be tested.
 */
object Corrections {
    data class Change(val heard: String, val fixed: String, val before: String, val after: String)

    private const val MAX_PHRASE = 3
    private const val CONTEXT = 3

    private fun norm(w: String) = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }
    private fun words(s: String) = s.split(Regex("\\s+")).filter { it.isNotBlank() }

    /** The inserted words, where they ended up in the field, and the two aligned word by word. */
    private class Aligned(val a: List<String>, val w: List<String>, val m: List<Pair<Int, Int>>)

    private fun align(inserted: String, field: String): Aligned? {
        val a = words(inserted)
        val f = words(field)
        if (a.isEmpty() || f.isEmpty()) return null
        val an = a.map(::norm)
        val fn = f.map(::norm)
        // where the inserted words ended up: the span of the field the alignment matches
        val m0 = lcs(an, fn)
        if (m0.size < max(1, a.size / 2)) return null // mostly gone or rewritten: nothing to learn
        val firstA = m0.first().first
        val lastA = m0.last().first
        val lo = (m0.first().second - firstA).coerceAtLeast(0)
        val hi = (m0.last().second + (a.size - 1 - lastA)).coerceAtMost(f.size - 1)
        val w = f.subList(lo, hi + 1)
        val m = lcs(an, fn.subList(lo, hi + 1))
        // a rewrite, not a correction
        if (m.size < a.size * 0.6) return null
        return Aligned(a, w, m)
    }

    fun find(inserted: String, field: String): List<Change> {
        val al = align(inserted, field) ?: return emptyList()
        val a = al.a
        val w = al.w
        val m = al.m
        val out = mutableListOf<Change>()
        var pa = 0
        var pw = 0
        for ((ia, iw) in m + listOf(a.size to w.size)) {
            val heard = a.subList(pa, ia)
            val fixed = w.subList(pw, iw)
            if (heard.isNotEmpty() && fixed.isNotEmpty() && heard.size <= MAX_PHRASE && fixed.size <= MAX_PHRASE) {
                val h = heard.joinToString(" ") { it.trim { c -> !c.isLetterOrDigit() } }
                val x = fixed.joinToString(" ") { it.trim { c -> !c.isLetterOrDigit() } }
                if (worthLearning(h, x)) {
                    val before = w.subList((pw - CONTEXT).coerceAtLeast(0), pw).joinToString(" ")
                    val after = w.subList(iw, min(w.size, iw + CONTEXT)).joinToString(" ")
                    out += Change(h, x, before, after)
                }
            }
            pa = ia + 1
            pw = iw + 1
        }
        return out
    }

    /**
     * The words the user only re-capitalized ("LOL" → "lol", "iphone" → "iPhone"), as (typed,
     * fixed): how they like the word written, wherever it comes up. Not a first letter alone ("The"
     * → "the"): that's where the word fell in a sentence, not how it's spelled.
     */
    fun casings(inserted: String, field: String): List<Pair<String, String>> {
        val al = align(inserted, field) ?: return emptyList()
        fun bare(s: String) = s.trim { c -> !c.isLetterOrDigit() }
        return al.m.map { (i, j) -> bare(al.a[i]) to bare(al.w[j]) }
            .filter { (h, x) -> casingWorthLearning(h, x) }
            .distinctBy { it.second }
    }

    fun casingWorthLearning(typed: String, fixed: String): Boolean =
        typed != fixed && typed.equals(fixed, ignoreCase = true) && fixed.length >= 2 &&
            fixed.all { it.isLetter() || it == '\'' } && typed.drop(1) != fixed.drop(1)

    /** A swap that could be a mishearing: different words (not just case), no numbers, sounding alike. */
    fun worthLearning(heard: String, fixed: String): Boolean {
        if (heard.equals(fixed, ignoreCase = true)) return false // case only
        if (heard.any { it.isDigit() } || fixed.any { it.isDigit() }) return false
        val h = heard.lowercase().filter { it.isLetter() }
        val x = fixed.lowercase().filter { it.isLetter() }
        if (h.isEmpty() || x.length < 2) return false
        if (h == x) return true // the same letters, joined or split differently ("camel back" → "Camelback")
        return similarity(h, x) >= 0.6 || skeleton(h) == skeleton(x)
    }

    /** 1 for the same letters, 0 for nothing in common (edit distance over the longer length). */
    fun similarity(a: String, b: String): Double {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return 1.0 - d[a.length][b.length].toDouble() / max(a.length, b.length)
    }

    /** The consonant outline, a rough "sounds like" key: "shona" and "siona" both give "sn". */
    fun skeleton(s: String): String {
        val t = s.replace("ph", "f").replace("ck", "k").replace("sh", "s").replace("ch", "k").replace("c", "k").replace("z", "s")
        val c = t.filter { it !in "aeiouyhw" }
        return c.fold(StringBuilder()) { sb, ch -> if (sb.isEmpty() || sb.last() != ch) sb.append(ch) else sb }.toString()
    }

    /** Longest common subsequence of two word lists, as matched index pairs in order. */
    private fun lcs(a: List<String>, b: List<String>): List<Pair<Int, Int>> {
        val n = a.size
        val m = b.size
        val t = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0)
            t[i][j] = if (a[i] == b[j] && a[i].isNotEmpty()) t[i + 1][j + 1] + 1 else max(t[i + 1][j], t[i][j + 1])
        val out = mutableListOf<Pair<Int, Int>>()
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] && a[i].isNotEmpty() -> { out += i to j; i++; j++ }
                t[i + 1][j] >= t[i][j + 1] -> i++
                else -> j++
            }
        }
        return out
    }
}
