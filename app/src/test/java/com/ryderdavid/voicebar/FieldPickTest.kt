package com.ryderdavid.voicebar

import kotlin.test.Test
import kotlin.test.assertEquals

class FieldPickTest {
    private fun box(hint: String? = null, focused: Boolean = false, bottom: Int = 0, password: Boolean = false) = FieldPick.Box(hint, focused, bottom, password)

    @Test fun theBoxItWasLastInComesFirst() {
        val boxes = listOf(box("Search", focused = true, bottom = 200), box("Message", bottom = 2000), box("Subject", bottom = 900))
        assertEquals(1, FieldPick.choose(boxes, "Message"))
    }

    @Test fun elseTheFocusedOneElseTheLowest() {
        val boxes = listOf(box("Search", bottom = 200), box(null, focused = true, bottom = 900), box("Message", bottom = 2000))
        assertEquals(1, FieldPick.choose(boxes, "Gone"))
        assertEquals(1, FieldPick.choose(listOf(box("Search", bottom = 200), box("Message", bottom = 2000)), null), "a chat's message box sits at the bottom")
    }

    @Test fun neverAPasswordAndNothingWhenThereAreNone() {
        assertEquals(-1, FieldPick.choose(emptyList(), "Message"))
        assertEquals(-1, FieldPick.choose(listOf(box("Password", password = true, bottom = 2000)), "Password"))
        assertEquals(0, FieldPick.choose(listOf(box("Email", bottom = 800), box("Password", focused = true, bottom = 1000, password = true)), null))
    }
}
