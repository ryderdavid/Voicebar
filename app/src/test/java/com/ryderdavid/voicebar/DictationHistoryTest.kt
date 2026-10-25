package com.ryderdavid.voicebar

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DictationHistoryTest {
    @Test fun keepsTheLastTenNewestFirstWithBothVersions() {
        var saved: String? = null
        val store = object : DictationHistory.Store { override fun read() = saved; override fun write(json: String) { saved = json } }
        val h = DictationHistory(store)
        for (i in 1..12) h.add("um so number $i", "Number $i.", "Messages", now = i.toLong())
        val all = h.all()
        assertEquals(10, all.size)
        assertEquals("Number 12.", all.first().typed)
        assertEquals("um so number 12", all.first().heard)
        h.remove(12)
        assertEquals("Number 11.", DictationHistory(store).all().first().typed)
    }
}
