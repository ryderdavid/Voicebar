package com.ryderdavid.voicebar

import android.content.Context
import android.content.SharedPreferences

/** Settings keys and defaults, shared by the keyboard and its settings screen. */
object Prefs {
    /** The settings file's name on the phone (Backup refers to it by role, so it can change). */
    const val FILE = "voicebar"

    const val VOICE_LIVE = "voice_live"
    /** Start dictating as soon as a text box opens the keyboard. */
    const val AUTO_LISTEN = "auto_listen"
    /** How loud the user's own dictation usually is (0..1 mic level), learned: auto-listen starts only at about this level. */
    const val NEAR_LEVEL = "near_level"
    /** Auto-listen in messaging and email apps: preview in the pill, hold what ends by itself, stop after a minute. */
    const val CHAT_CARE = "auto_listen_chat_care"
    const val DEFAULT_CHAT_CARE = false
    /** When Send isn't labeled, the cleanup's model picks it out of the buttons beside the message box. */
    const val SEND_FINDER = "send_finder"
    /** Before a dictation is sent, the cleanup's model gives the whole message one last look ([SendCheck]). */
    const val SEND_CHECK = "send_check"
    const val DEFAULT_SEND_CHECK = true
    const val DEFAULT_SEND_FINDER = false
    /** The Send button the finder found for an app, by package ([SendFinder.spot]); dropped when it stops sending. */
    const val SEND_SPOT_PREFIX = "send_spot."
    const val DEFAULT_AUTO_LISTEN = false
    /** The old on/off switch for millennial mode; read once to carry it over to [MILLENNIAL_WHERE]. */
    const val MILLENNIAL = "millennial_mode"
    /** Where millennial mode applied before it had a check for each place: "off", "chats" or "everywhere". Read once to carry it over. */
    const val MILLENNIAL_WHERE = "millennial_where"
    const val MILLENNIAL_OFF = "off"
    const val MILLENNIAL_CHATS = "chats"
    const val MILLENNIAL_EVERYWHERE = "everywhere"
    /** Millennial mode's checks: search boxes, chats (messaging apps and message boxes), and everywhere (which covers both). */
    const val MILLENNIAL_IN_SEARCH = "millennial_in_search"
    const val MILLENNIAL_IN_CHATS = "millennial_in_chats"
    const val MILLENNIAL_IN_ALL = "millennial_in_all"
    /** "Voice bar, send" and friends: spoken commands while dictating. */
    const val VOICE_COMMANDS = "voice_commands"
    const val DEFAULT_VOICE_COMMANDS = true
    /** Learn names and terms from the user's hand corrections to dictated text. */
    const val LEARN = "learn_corrections"
    const val DEFAULT_LEARN = true
    /** Send the personal dictionary to the speech model as keyterms (ElevenLabs charges a little extra). */
    const val DICT_KEYTERMS = "dictionary_keyterms"
    const val DEFAULT_DICT_KEYTERMS = true
    /** Speech recognition: ElevenLabs Scribe (cloud, the default) or Parakeet on the phone. */
    const val SPEECH_ENGINE = "speech_engine"
    const val ENGINE_ELEVENLABS = "elevenlabs"
    const val ENGINE_PARAKEET = "parakeet"
    /** First-run tips on the bar ("Skip tips" turns them off; Settings › How to use it turns them back on). */
    const val CHILD_FILTER = "child_filter_on"
    const val CHILD_FILTER_STRENGTH = "child_filter_strength"
    const val VOICEPRINT = "voiceprint_on"
    const val VOICEPRINT_STRENGTH = "voiceprint_strength"

    const val TIPS = "tips_on"
    const val DEFAULT_TIPS = true
    /** Where the tips tour picks up (its length when finished). */
    const val TIPS_STEP = "tips_step"

    /** The welcome video has played (it plays once, the first time settings open). */
    const val WELCOME_SEEN = "welcome_seen"

    /** The Gboard button service has run at least once (so its being off now means it was switched off). */
    const val WAY_BACK_WAS_ON = "way_back_was_on"
    /** When the bar last said the Gboard button had been switched off (once a day at most). */
    const val WAY_BACK_WARNED_AT = "way_back_warned_at"

    const val SETUP_DONE = "setup_done"
    /** Hands-free mode ("voice bar, hands free"): control the phone by voice. */
    const val HANDS_FREE = "hands_free"
    const val DEFAULT_HANDS_FREE = true
    /** Listen for "voice bar, hands free" everywhere, not only while a keyboard is up. */
    const val HANDS_FREE_ANYWHERE = "hands_free_anywhere"
    const val DEFAULT_HANDS_FREE_ANYWHERE = false
    /** The Claude model that plans hands-free steps (default Haiku). */
    const val HANDS_FREE_MODEL = "hands_free_model"
    /** Read the screen's text when dictation starts, as context for recognition and cleanup. */
    const val SCREEN_CONTEXT = "screen_context"
    const val DEFAULT_SCREEN_CONTEXT = true
    /** Listen on the phone for "voice bar, listen" while a keyboard is up and idle (the bar, or over Gboard). */
    const val WAKE_WORD = "wake_word"
    const val DEFAULT_WAKE_WORD = true
    const val GBOARD_BUBBLE_X = "gboard_bubble_x"
    const val GBOARD_BUBBLE_GAP = "gboard_bubble_gap"
    /** How the way back from Gboard looks: a bar on top of Gboard, a button in its voice-key spot, or a floating bubble. */
    const val GBOARD_RETURN = "gboard_return"
    const val GBOARD_RETURN_BAR = "bar"
    const val GBOARD_RETURN_MIC_KEY = "mic_key"
    const val GBOARD_RETURN_BUBBLE = "bubble"
    const val DEFAULT_GBOARD_RETURN = GBOARD_RETURN_BAR
    /** The bar's style: "docked" across the bottom, or "floating" in a pill over the app that collapses to its record button. */
    const val BAR_STYLE = "bar_style"
    const val BAR_STYLE_DOCKED = "docked"
    const val BAR_STYLE_FLOATING = "floating"
    const val DEFAULT_BAR_STYLE = BAR_STYLE_DOCKED
    /** The floating bar stands upright against a side edge (a column of round keys) instead of lying across. */
    const val BAR_UPRIGHT = "bar_upright"
    /** The floating bar is collapsed to its record pill. */
    const val BAR_COLLAPSED = "bar_collapsed"
    /** Where the open floating pill was left (its center, as fractions, like [PILL_X]), unless it's parked at the bottom. */
    const val FLOAT_X = "float_x"
    const val FLOAT_Y = "float_y"
    /** The open floating pill sits at the bottom with the app above it (the default); dragged off, it floats over the app. */
    const val FLOAT_PARKED = "float_parked"
    /** Where the collapsed pill was left: its center, as fractions of the screen's width and of the height above the navigation area. */
    const val PILL_X = "pill_x"
    const val PILL_Y = "pill_y"
    /** What a tap on ABC does: "keys" (Voicebar's keyboard; hold for Gboard) or "gboard" (Gboard; hold for Voicebar's keyboard). */
    const val ABC_TAP = "abc_tap"
    const val ABC_TAP_KEYS = "keys"
    const val ABC_TAP_GBOARD = "gboard"
    const val DEFAULT_ABC_TAP = ABC_TAP_KEYS
    /** Sliding across the keyboard's letters types a word (FUTO Swipe). */
    const val SWIPE_TYPING = "swipe_typing"
    const val DEFAULT_SWIPE_TYPING = true
    /** The swipe models are being loaded (set before, cleared after): still set at the next start means the load crashed. */
    const val SWIPE_LOADING = "swipe_loading"
    /** The words typed on Voicebar's keyboard and how often (JSON), for its suggestions. */
    const val TYPED_WORDS = "typed_words"
    /** The seven-button bar's own key between ⋮ and Cursor: a [Bar.ExtraKey]'s pref ("space", "paste", "enter", "menu", …). */
    const val BAR_KEY = "bar_key"
    const val DEFAULT_BAR_KEY = "space"
    /** The seven-button bar's second own key, right of Voice (⌫ unless changed): same choices as [BAR_KEY]. */
    const val BAR_KEY_RIGHT = "bar_key_right"
    const val DEFAULT_BAR_KEY_RIGHT = "backspace"
    /** The floating pill's one own key, right of Voice (⌫ unless changed): same choices as [BAR_KEY]. */
    const val BAR_KEY_FLOAT = "bar_key_float"
    const val DEFAULT_BAR_KEY_FLOAT = "backspace"
    /** The punctuation palette's recent marks, newest first, one per line. */
    const val MARKS_RECENT = "marks_recent"
    /** The seven-button bar's far-right key (↵ unless changed): same choices as [BAR_KEY]. */
    const val BAR_KEY_ENTER = "bar_key_enter"
    const val DEFAULT_BAR_KEY_ENTER = "enter"
    /** The bar's colors: "classic" or "tiered" (one dark action; modes tonal; keys neutral). */
    /** On wide screens (tablets, unfolded phones): where the bar's buttons sit. */
    const val BAR_SIDE = "bar_side"
    const val BAR_SIDE_FULL = "full"
    const val BAR_SIDE_LEFT = "left"
    const val BAR_SIDE_CENTER = "center"
    const val BAR_SIDE_RIGHT = "right"
    const val DEFAULT_BAR_SIDE = BAR_SIDE_FULL

    const val BAR_COLORS = "bar_colors"
    const val BAR_COLORS_CLASSIC = "classic"
    const val BAR_COLORS_TIERED = "tiered"
    const val DEFAULT_BAR_COLORS = BAR_COLORS_CLASSIC
    const val GBOARD_PACKAGE = "com.google.android.inputmethod.latin"
    const val SILENCE_TIMEOUT = "silence_timeout_s"
    const val CLEANUP = "voice_cleanup"
    const val CLEANUP_MODEL = "voice_cleanup_model"

    const val DEFAULT_CLEANUP = true
    const val DEFAULT_VOICE_LIVE = true
    const val DEFAULT_MILLENNIAL = false
    const val DEFAULT_SILENCE_TIMEOUT = 10 // seconds of quiet before hands-free dictation finishes

    val SILENCE_TIMEOUT_RANGE = 3..60

    /**
     * Gboard's input-method id if it's turned on as a keyboard, else null. Asks the input-method
     * service first, then falls back to the system's enabled-keyboards setting, which isn't filtered
     * by Android's package-visibility rules.
     */
    fun gboardImeId(context: Context): String? {
        val imm = context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        imm?.enabledInputMethodList?.firstOrNull { it.packageName == GBOARD_PACKAGE }?.id?.let { return it }
        val enabled = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ENABLED_INPUT_METHODS)
            ?: return null
        return enabled.split(':').map { it.substringBefore(';') }.firstOrNull { it.startsWith("$GBOARD_PACKAGE/") }
    }

    /** Where millennial mode applies. Nowhere is all three off. */
    data class MillennialPlaces(val search: Boolean = false, val chats: Boolean = false, val everywhere: Boolean = false) {
        val nowhere get() = !search && !chats && !everywhere
    }

    /** Millennial mode's checks; before there were checks, the one choice (or the older switch's "on", as chats) carries over. */
    fun millennial(context: Context): MillennialPlaces {
        val p = get(context)
        if (p.contains(MILLENNIAL_IN_ALL)) return MillennialPlaces(
            p.getBoolean(MILLENNIAL_IN_SEARCH, false), p.getBoolean(MILLENNIAL_IN_CHATS, false), p.getBoolean(MILLENNIAL_IN_ALL, false))
        val old = p.getString(MILLENNIAL_WHERE, null) ?: if (p.getBoolean(MILLENNIAL, DEFAULT_MILLENNIAL)) MILLENNIAL_CHATS else MILLENNIAL_OFF
        return MillennialPlaces(chats = old == MILLENNIAL_CHATS, everywhere = old == MILLENNIAL_EVERYWHERE)
    }

    fun setMillennial(context: Context, m: MillennialPlaces) = get(context).edit()
        .putBoolean(MILLENNIAL_IN_SEARCH, m.search).putBoolean(MILLENNIAL_IN_CHATS, m.chats).putBoolean(MILLENNIAL_IN_ALL, m.everywhere).apply()

    fun get(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
