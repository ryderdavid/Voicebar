package com.ryderdavid.voicebar

/**
 * Which text box to focus again when the keyboard finds itself without one: the one with the hint
 * the last box had, else the one that still has focus, else the lowest on screen (a chat's message
 * box sits at the bottom). Password boxes never.
 */
object FieldPick {
    data class Box(val hint: String?, val focused: Boolean, val bottom: Int, val password: Boolean = false)

    /** The index of the box to focus in [boxes], or -1 when there's none. */
    fun choose(boxes: List<Box>, hint: String?): Int {
        val ok = boxes.withIndex().filter { !it.value.password }
        if (ok.isEmpty()) return -1
        hint?.let { h -> ok.firstOrNull { it.value.hint == h }?.let { return it.index } }
        ok.firstOrNull { it.value.focused }?.let { return it.index }
        return ok.maxBy { it.value.bottom }.index
    }
}
