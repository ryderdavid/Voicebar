package com.ryderdavid.voicebar

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MarksTest {
    @Test fun aClosingMarkTakesTheSpaceADictationLeftAndKeepsItAfter() {
        assertEquals(1 to "! ", Marks.insertion("see you there ", "!"))
        assertEquals(1 to ", ", Marks.insertion("Well ", ","))
        assertEquals(1 to ") ", Marks.insertion("(the north one ", ")"))
    }

    @Test fun otherwiseAMarkGoesInAsItIs() {
        assertEquals(0 to "!", Marks.insertion("there", "!"), "no space to move")
        assertEquals(0 to "?", Marks.insertion("", "?"))
        assertEquals(0 to "?", Marks.insertion("Done.  ", "?"), "two spaces: not a dictation's one")
        assertEquals(0 to "@", Marks.insertion("email me ", "@"), "@ doesn't close up to the word")
        assertEquals(0 to "(", Marks.insertion("call me ", "("))
        assertEquals(0 to "“", Marks.insertion("she said ", "“"))
        assertEquals(0 to ".", Marks.insertion(null, "."))
    }

    @Test fun theRecentRowIsTheLastSixUsedNewestFirstToppedUpWithStarters() {
        assertEquals(listOf("?", "!", "@", "&", "#", "/"), Marks.recent(null))
        var saved: String? = null
        for (m in listOf("$", ";", "$", "%")) saved = Marks.used(saved, m)
        assertEquals(listOf("%", "$", ";", "?", "!", "@"), Marks.recent(saved))
        for (m in listOf("a", "b", "c", "d", "e", "f", "g")) saved = Marks.used(saved, m)
        assertEquals(listOf("g", "f", "e", "d", "c", "b"), Marks.recent(saved))
    }

    @Test fun threePagesOfTwelveAndEveryVariantSitsOnAPage() {
        assertEquals(3, Marks.PAGES.size)
        Marks.PAGES.forEach { assertEquals(12, it.size) }
        assertEquals(36, Marks.PAGES.flatten().distinct().size, "no mark twice")
        Marks.VARIANTS.keys.forEach { k -> assertTrue(Marks.PAGES.any { k in it }, "$k is on a page") }
    }
}
