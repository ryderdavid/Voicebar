package com.ryderdavid.voicebar

import android.graphics.RectF
import android.view.inputmethod.EditorInfo
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class PopKeysTest {
    private val out = mutableListOf<String>()
    private val pending = mutableListOf<Runnable>()
    private var closed = false
    private val haptics = mutableListOf<Int>()
    private val swipes = mutableListOf<Triple<List<com.ryderdavid.voicebar.swipe.SwipeDecoder.Point>, FloatArray, Int>>()
    private lateinit var keys: PopKeys

    @Before fun setUp() {
        keys = PopKeys(object : PopKeys.Host {
            override fun dp(v: Float) = v
            override fun post(r: Runnable, delayMs: Long) { pending += r }
            override fun cancel(r: Runnable) { pending.remove(r) }
            override fun invalidate() = Unit
            override fun haptic(type: Int) { haptics += type }
            override fun type(text: String) { out += text }
            override fun key(key: Bar.Key, repeat: Boolean) { out += if (repeat) "$key+" else key.name }
            override fun suggest(word: String) { out += "suggest:$word" }
            override fun close() { closed = true }
            override fun menu(anchor: android.graphics.RectF) { out += "menu" }
            override fun gboard() { out += "gboard" }
            override fun swipe(points: List<com.ryderdavid.voicebar.swipe.SwipeDecoder.Point>, keys: FloatArray, caps: Int) { swipes += Triple(points, keys, caps) }
        })
        keys.layout(RectF(0f, -230f, 440f, 50f))
    }

    /** Fires what's waiting (a hold, a repeat), as time passing would. */
    private fun elapse() { val now = pending.toList(); pending.clear(); now.forEach { it.run() } }

    private fun center(r: RectF) = r.centerX() to r.centerY()
    private fun tap(label: String, id: Int = 0) {
        val (x, y) = center(assertNotNull(keys.testKey(label), "$label is on the keyboard"))
        keys.down(id, x, y); keys.up(id, x, y)
    }
    private fun tapKind(kind: PopKeys.Kind) {
        val (x, y) = center(keys.testKind(kind)!!)
        keys.down(0, x, y); keys.up(0, x, y)
    }

    @Test fun lettersTypeAndShiftCapitalizesOnce() {
        tap("h"); tap("i")
        tapKind(PopKeys.Kind.SHIFT)
        tap("o"); tap("k")
        assertEquals(listOf("h", "i", "O", "k"), out)
    }

    @Test fun doubleTapShiftLocksCaps() {
        tapKind(PopKeys.Kind.SHIFT); tapKind(PopKeys.Kind.SHIFT)
        assertEquals(PopKeys.Shift.LOCK, keys.shift)
        tap("o"); tap("k")
        assertEquals(listOf("O", "K"), out)
        android.os.SystemClock.sleep(PopKeys.DOUBLE_TAP_MS + 50) // a single tap, not a third of a double
        tapKind(PopKeys.Kind.SHIFT)
        tap("o")
        assertEquals("o", out.last())
    }

    @Test fun theFieldsCapitalComesAndTappingShiftTurnsItOff() {
        keys.autoCap = true
        tap("t")
        assertEquals("T", out.last())
        android.os.SystemClock.sleep(PopKeys.DOUBLE_TAP_MS + 50)
        tapKind(PopKeys.Kind.SHIFT)
        tap("t")
        assertEquals("t", out.last(), "shift turned the field's capital off")
        keys.autoCap = false
        keys.autoCap = true
        tap("t")
        assertEquals("T", out.last(), "a new sentence asks again")
    }

    @Test fun holdingALetterOffersItsNumberAndAccentsAndSlidingPicksOne() {
        val (x, y) = center(keys.testKey("e")!!)
        keys.down(0, x, y)
        elapse()
        val choices = assertNotNull(keys.testChoices(0))
        assertEquals("3èéêëēęė".length, choices.size)
        // let go where it opened: the first choice (the number)
        keys.up(0, x, y)
        assertEquals(listOf("3"), out)
        // again, sliding to é
        keys.down(1, x, y)
        elapse()
        val r = keys.testChoices(1)!![2]
        keys.move(1, r.centerX(), r.centerY())
        keys.up(1, r.centerX(), r.centerY())
        assertEquals(listOf("3", "é"), out)
    }

    @Test fun pagesForNumbersAndSymbols() {
        tap("?123")
        tap("4"); tap("$")
        tap("=\\<")
        tap("€")
        tap("ABC")
        tap("a")
        assertEquals(listOf("4", "$", "€", "a"), out)
    }

    @Test fun backspaceDeletesOnTheWayDownAndRepeatsWhileHeld() {
        val (x, y) = center(keys.testKind(PopKeys.Kind.BACK)!!)
        keys.down(0, x, y)
        assertEquals(listOf("DELETE"), out)
        elapse(); elapse(); elapse()
        keys.up(0, x, y)
        assertTrue(out.count { it == "DELETE+" } >= 2, "repeats: $out")
        val n = out.size
        elapse()
        assertEquals(n, out.size, "letting go stops it")
    }

    @Test fun slidingOnSpaceMovesTheCursorInsteadOfTypingASpace() {
        val (x, y) = center(keys.testKind(PopKeys.Kind.SPACE)!!)
        keys.down(0, x, y)
        keys.move(0, x + 40f, y)
        keys.up(0, x + 40f, y)
        assertTrue(out.isNotEmpty() && out.all { it == "RIGHT+" }, "$out")
        out.clear()
        keys.down(0, x, y); keys.up(0, x, y)
        assertEquals(listOf(" "), out)
    }

    @Test fun aSecondThumbTypesTheFirstThumbsKeyAtOnce() {
        val (ax, ay) = center(keys.testKey("a")!!)
        val (lx, ly) = center(keys.testKey("l")!!)
        keys.down(0, ax, ay)
        keys.down(1, lx, ly)
        assertEquals(listOf("a"), out, "a goes in as l comes down")
        keys.up(0, ax, ay)
        keys.up(1, lx, ly)
        assertEquals(listOf("a", "l"), out)
    }

    @Test fun theBestSuggestionSitsInTheMiddle() {
        keys.suggestions = listOf("the", "they", "them")
        val mid = keys.testSlot(1)
        keys.down(0, mid.centerX(), mid.centerY()); keys.up(0, mid.centerX(), mid.centerY())
        val left = keys.testSlot(0)
        keys.down(0, left.centerX(), left.centerY()); keys.up(0, left.centerX(), left.centerY())
        assertEquals(listOf("suggest:the", "suggest:they"), out)
    }

    @Test fun pullingTheHandleDownClosesItAndATapDoesNot() {
        val h = keys.testHandle
        keys.down(0, h.centerX(), h.centerY()); keys.up(0, h.centerX(), h.centerY())
        assertFalse(closed, "a tap on the handle leaves it open")
        assertEquals(1, haptics.size, "grabbing the handle ticks")
        // a short pull eases back
        keys.down(0, h.centerX(), h.centerY()); keys.move(0, h.centerX(), h.centerY() + 30f)
        assertEquals(30f, keys.pull)
        keys.up(0, h.centerX(), h.centerY() + 30f)
        assertFalse(closed)
        repeat(20) { elapse() }
        assertEquals(0f, keys.pull)
        // never above where it sits
        keys.down(0, h.centerX(), h.centerY()); keys.move(0, h.centerX(), h.centerY() - 40f)
        assertEquals(0f, keys.pull)
        keys.up(0, h.centerX(), h.centerY() - 40f)
        // a long one closes it, and other fingers wait while it's pulled
        keys.down(0, h.centerX(), h.centerY()); keys.move(0, h.centerX(), h.centerY() + 40f)
        val (x, y) = center(keys.testKey("h")!!)
        keys.down(1, x, y); keys.up(1, x, y)
        keys.move(0, h.centerX(), h.centerY() + 80f); keys.up(0, h.centerX(), h.centerY() + 80f)
        assertTrue(closed)
        assertEquals(emptyList(), out)
    }

    @Test fun aQuickFlickOfTheHandleClosesIt() {
        val h = keys.testHandle
        val t0 = android.os.SystemClock.uptimeMillis()
        keys.down(0, h.centerX(), h.centerY())
        keys.move(0, h.centerX(), h.centerY() + 10f, t0 + 20)
        keys.move(0, h.centerX(), h.centerY() + 30f, t0 + 40)
        keys.up(0, h.centerX(), h.centerY() + 30f, t0 + 45)
        assertTrue(closed)
    }

    @Test fun enterIsTheFieldsAction() {
        keys.enterAction = EditorInfo.IME_ACTION_SEND
        tapKind(PopKeys.Kind.ENTER)
        assertEquals(listOf("ENTER"), out)
    }

    @Test fun theMenuKeyFollowsPagesInTheBottomRowTapForTheMenuHoldForGboard() {
        val row = listOf(PopKeys.Kind.PAGE, PopKeys.Kind.MENU, PopKeys.Kind.SPACE, PopKeys.Kind.ENTER).map { keys.testKind(it)!! }
        assertTrue(row.zipWithNext().all { (a, b) -> a.right <= b.left }, "?123, ⋮, space, ↵ left to right")
        assertTrue(row.all { it.top == row[0].top }, "one row")
        tapKind(PopKeys.Kind.MENU)
        assertEquals(listOf("menu"), out)
        assertFalse(closed)
        val (x, y) = center(keys.testKind(PopKeys.Kind.MENU)!!)
        keys.down(0, x, y); elapse(); keys.up(0, x, y)
        assertEquals(listOf("menu", "gboard"), out)
    }

    /** A finger down on [word]'s first letter, through each of its letters, and up on the last. */
    private fun swipeThrough(word: String) {
        val pts = word.map { center(keys.testKey(it.toString())!!) }
        keys.down(0, pts[0].first, pts[0].second)
        var time = 1000L
        for ((a, b) in pts.zipWithNext()) for (i in 1..6) {
            time += 16
            keys.move(0, a.first + (b.first - a.first) * i / 6, a.second + (b.second - a.second) * i / 6, time)
        }
        keys.up(0, pts.last().first, pts.last().second, time + 16)
    }

    @Test fun slidingAcrossLettersIsASwipeInTheLetterRowsFrame() {
        swipeThrough("hello")
        assertEquals(emptyList(), out, "nothing typed key by key")
        val (points, keyCenters, caps) = swipes.single()
        assertEquals(0, caps)
        assertTrue(points.size > 20)
        assertTrue(points.all { it.x in -0.05f..1.05f && it.y in -0.05f..1.05f }, "in the letter rows' frame")
        // q's center is near the top left, m's in the bottom row; h at the middle row
        assertTrue(keyCenters[2 * ('q' - 'a')] < 0.1f && keyCenters[2 * ('q' - 'a') + 1] < 0.25f)
        assertTrue(keyCenters[2 * ('m' - 'a') + 1] > 0.75f)
        assertTrue(kotlin.math.abs(points.first().y - 0.5f) < 0.1f, "starts on h, in the middle row")
    }

    @Test fun aSwipeCarriesTheCapitalAndATapOrANeighborStaysATap() {
        tapKind(PopKeys.Kind.SHIFT)
        swipeThrough("then")
        assertEquals(1, swipes.single().third)
        assertEquals(PopKeys.Shift.OFF, keys.shift, "one capital, used")
        // a slide onto the next key is that key, not a swipe
        val (ax, ay) = center(keys.testKey("a")!!)
        val (sx, sy) = center(keys.testKey("s")!!)
        keys.down(0, ax, ay); keys.move(0, sx, sy); keys.up(0, sx, sy)
        assertEquals(listOf("s"), out)
        assertEquals(1, swipes.size)
        keys.swipeEnabled = false
        swipeThrough("hello")
        assertEquals(1, swipes.size, "swiping off: no swipe")
    }

    @Test fun theGapsBetweenKeysStillType() {
        val a = keys.testKey("a")!!
        val s = keys.testKey("s")!!
        val x = (a.right + s.left) / 2 - 0.5f
        keys.down(0, x, a.centerY()); keys.up(0, x, a.centerY())
        assertEquals(listOf("a"), out)
    }
}
