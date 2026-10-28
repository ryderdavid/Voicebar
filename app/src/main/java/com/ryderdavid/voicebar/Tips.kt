package com.ryderdavid.voicebar

import android.content.Context
import androidx.core.content.edit

/**
 * The first-run tour on the bar: the tips one after another, with Back and Next. It comes up when
 * the keyboard opens and picks up where it was left. Closing the keyboard or starting to talk just
 * puts it away for now; **Dismiss** asks whether to bring it back later or stop for good.
 */
object Tips {
    enum class Id(val anchor: String, val title: Int, val body: Int) {
        VOICE("voice", R.string.tip_voice_title, R.string.tip_voice),
        FINISH("voice", R.string.tip_finish_title, R.string.tip_finish),
        ABC("abc", R.string.tip_abc_title, R.string.tip_abc),
        CURSOR("cursor", R.string.tip_cursor_title, R.string.tip_cursor),
        MENU("more", R.string.tip_menu_title, R.string.tip_menu),
        OWN_KEYS("space|enter", R.string.tip_own_title, R.string.tip_own),
        BKSP("bksp", R.string.tip_bksp_title, R.string.tip_bksp),
        VOICEBAR("voice", R.string.tip_voicebar_title, R.string.tip_voicebar),
    }

    /** What's on the bubble: a step of the tour, or the "again later?" question after Dismiss. */
    sealed class Showing {
        data class Step(val index: Int) : Showing() { val id get() = Id.entries[index] }
        data class Ask(val index: Int) : Showing()
    }

    /** The bubble's buttons. */
    enum class Answer { BACK, NEXT, DISMISS, LATER, NEVER }

    /**
     * Where an answer leads from [now]: the next thing to show (null: put away), and the step to
     * resume from next time (null: tips off for good; [Id.entries].size: the tour is finished).
     */
    fun answer(now: Showing, a: Answer): Pair<Showing?, Int?> {
        val last = Id.entries.lastIndex
        return when (now) {
            is Showing.Step -> when (a) {
                Answer.BACK -> Showing.Step((now.index - 1).coerceAtLeast(0)).let { it to it.index }
                Answer.NEXT -> if (now.index >= last) null to Id.entries.size else Showing.Step(now.index + 1).let { it to it.index }
                Answer.DISMISS -> Showing.Ask(now.index) to now.index
                else -> now to now.index
            }
            is Showing.Ask -> when (a) {
                Answer.LATER -> null to now.index
                Answer.NEVER -> null to null
                else -> now to now.index
            }
        }
    }

    // ---------------------------------------------------------------- saved state

    fun on(context: Context) = Prefs.get(context).getBoolean(Prefs.TIPS, Prefs.DEFAULT_TIPS)

    /** The step to show next time, or null when the tour is finished or tips are off. */
    fun resume(context: Context): Int? =
        if (!on(context)) null else Prefs.get(context).getInt(Prefs.TIPS_STEP, 0).takeIf { it in Id.entries.indices }

    /** Saves [step] as the place to resume (see [answer]); null turns tips off. */
    fun save(context: Context, step: Int?) = Prefs.get(context).edit {
        if (step == null) putBoolean(Prefs.TIPS, false) else putInt(Prefs.TIPS_STEP, step)
    }

    /** Show the tour again from the start. */
    fun reset(context: Context) = Prefs.get(context).edit {
        putBoolean(Prefs.TIPS, true); putInt(Prefs.TIPS_STEP, 0)
    }
}
