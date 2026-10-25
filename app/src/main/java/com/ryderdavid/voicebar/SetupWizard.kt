package com.ryderdavid.voicebar

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.KeyboardReturn
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/*
 * The first-launch setup: one short page per thing Android needs from you (turn the keyboard on,
 * choose it, allow the microphone, the Gboard button's accessibility switch) and per API key, in
 * the settings' own look: a big title over groups of rounded rows, tinted icon circles, the
 * phone's wallpaper colors. Each page checks itself as you come back from the system screens, and
 * the optional ones can be skipped. The pages are stateless: [WizardState] in, [WizardActions] out.
 */

enum class WizardStep(val optional: Boolean = false) {
    WELCOME, KEYBOARDS, SWITCH, MIC, SPEECH, CLEANUP(optional = true), COMPANION(optional = true), DONE;

    /** The steps with a dot in the progress bar (not the welcome or the end). */
    val counted get() = this != WELCOME && this != DONE
}

enum class KeyCheck { IDLE, CHECKING, OK, BAD }

data class KeyField(val text: String = "", val saved: Boolean = false, val check: KeyCheck = KeyCheck.IDLE, val message: String? = null)

data class WizardState(
    val setup: SettingsActivity.Setup = SettingsActivity.Setup(),
    /** Gboard is installed at all (it may be installed but turned off). */
    val gboardInstalled: Boolean = true,
    val eleven: KeyField = KeyField(),
    val claude: KeyField = KeyField(),
    /** Installed from a file (a browser, a file manager): Android 13+ blocks accessibility until allowed. */
    val sideloaded: Boolean = false,
    val skipped: Set<WizardStep> = emptySet(),
    /** Parakeet (speech recognition on the phone): its download. */
    val parakeet: com.ryderdavid.voicebar.voice.ModelPack.State = com.ryderdavid.voicebar.voice.ModelPack.State.Idle,
) {
    fun done(step: WizardStep): Boolean = when (step) {
        WizardStep.WELCOME, WizardStep.DONE -> true
        WizardStep.KEYBOARDS -> setup.enabled && setup.gboard
        WizardStep.SWITCH -> setup.current
        WizardStep.MIC -> setup.mic
        WizardStep.SPEECH -> eleven.saved || parakeet == com.ryderdavid.voicebar.voice.ModelPack.State.Ready
        WizardStep.CLEANUP -> claude.saved
        WizardStep.COMPANION -> setup.gboardBubble
    }
}

/** What the pages can ask for. [Key] says which key a key action is about. */
interface WizardActions {
    enum class Key { ELEVENLABS, ANTHROPIC }

    fun next()
    fun back()
    fun skip()
    fun goTo(step: WizardStep)
    fun openKeyboardSettings()
    fun pickKeyboard()
    fun getGboard()
    fun askMic()
    fun openUrl(url: String)
    fun keyText(key: Key, text: String)
    fun pasteKey(key: Key)
    fun checkKey(key: Key)
    /** Speech recognition on the phone instead: choose Parakeet and download it. */
    fun downloadParakeet()
    fun openAccessibility()
    fun openAppInfo()
    /** Let Voicebar run in the background (battery optimization off). */
    fun allowBackground()
    fun tryIt()
    fun finish()
}

@Composable
fun SetupWizard(step: WizardStep, state: WizardState, actions: WizardActions) {
    Scaffold(
        containerColor = pageColor(),
        topBar = { WizardTop(step, actions) },
        bottomBar = { WizardBottom(step, state, actions) },
    ) { pad ->
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(260)) { if (forward) it / 5 else -it / 5 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(260)) { if (forward) -it / 5 else it / 5 } + fadeOut(tween(160)))
            },
            label = "wizard",
        ) { s ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 16.dp),
            ) {
                when (s) {
                    WizardStep.WELCOME -> welcome()
                    WizardStep.KEYBOARDS -> keyboards(state, actions)
                    WizardStep.SWITCH -> switchTo(state, actions)
                    WizardStep.MIC -> mic(state, actions)
                    WizardStep.SPEECH -> speech(state, actions)
                    WizardStep.CLEANUP -> cleanup(state, actions)
                    WizardStep.COMPANION -> companion(state, actions)
                    WizardStep.DONE -> allSet(state, actions)
                }
            }
        }
    }
}

/* ---------------------------------------------------------------- frame */

@Composable
private fun WizardTop(step: WizardStep, actions: WizardActions) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().background(pageColor()).statusBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(48.dp)) {
            if (step != WizardStep.WELCOME) {
                FilledTonalIconButton(
                    onClick = actions::back,
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = rowColor()),
                ) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back)) }
            }
            Spacer(Modifier.weight(1f))
            if (step.optional) TextButton(onClick = actions::skip) { Text(stringResource(R.string.wiz_skip)) }
        }
        if (step.counted) {
            val steps = WizardStep.entries.filter { it.counted }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (s in steps) {
                    Box(
                        Modifier.weight(1f).height(4.dp).clip(CircleShape)
                            .background(if (s.ordinal <= step.ordinal) cs.primary else cs.surfaceContainerHighest),
                    )
                }
            }
            Text(
                stringResource(R.string.wiz_step_of, steps.indexOf(step) + 1, steps.size),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun WizardBottom(step: WizardStep, state: WizardState, actions: WizardActions) {
    val done = state.done(step)
    // the page's own action until it's done, then Next
    val (label, onClick, enabled) = when {
        step == WizardStep.WELCOME -> Triple(stringResource(R.string.wiz_start), actions::next, true)
        step == WizardStep.DONE -> Triple(stringResource(R.string.wiz_try), actions::tryIt, true)
        done -> Triple(stringResource(R.string.wiz_next), actions::next, true)
        step == WizardStep.KEYBOARDS ->
            if (!state.gboardInstalled) Triple(stringResource(R.string.wiz_get_gboard), actions::getGboard, true)
            else Triple(stringResource(R.string.wiz_open_keyboards), actions::openKeyboardSettings, true)
        step == WizardStep.SWITCH -> Triple(stringResource(R.string.wiz_choose), actions::pickKeyboard, state.setup.enabled)
        step == WizardStep.MIC -> Triple(stringResource(R.string.voice_mic_allow), actions::askMic, true)
        step == WizardStep.SPEECH -> Triple(stringResource(R.string.wiz_check_key), { actions.checkKey(WizardActions.Key.ELEVENLABS) },
            state.eleven.text.isNotBlank() && state.eleven.check != KeyCheck.CHECKING)
        step == WizardStep.CLEANUP -> Triple(stringResource(R.string.wiz_check_key), { actions.checkKey(WizardActions.Key.ANTHROPIC) },
            state.claude.text.isNotBlank() && state.claude.check != KeyCheck.CHECKING)
        step == WizardStep.COMPANION -> Triple(stringResource(R.string.wiz_open_accessibility), actions::openAccessibility, true)
        else -> Triple(stringResource(R.string.wiz_next), actions::next, true)
    }
    Column(Modifier.fillMaxWidth().background(pageColor()).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
        // a quiet way past a required step that's already been tried (Android's screens can be stubborn)
        val later = when {
            step == WizardStep.DONE -> stringResource(R.string.wiz_finish)
            !done && step != WizardStep.WELCOME && !step.optional -> stringResource(R.string.wiz_later)
            else -> null
        }
        if (later != null) TextButton(
            onClick = if (step == WizardStep.DONE) actions::finish else actions::next,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        ) { Text(later) }
    }
}

/** The page's head: a big tinted icon, the title, a sentence or two. */
private fun LazyListScope.hero(icon: ImageVector, title: Int, body: Int, tone: @Composable () -> Pair<Color, Color>) {
    item {
        val (bg, fg) = tone()
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 12.dp, bottom = 24.dp)) {
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(28.dp)).background(bg), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(36.dp))
            }
            Spacer(Modifier.height(20.dp))
            Text(stringResource(title), style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A requirement and whether it's met: a check, or what's still needed. */
@Composable
private fun StatusRow(icon: ImageVector, title: String, done: Boolean, todo: String, doneText: String? = null) {
    val cs = MaterialTheme.colorScheme
    SettingRow(
        title, if (done) doneText ?: stringResource(R.string.wiz_done) else todo,
        leading = { RowIcon(icon, if (done) cs.primaryContainer else cs.surfaceContainerHighest, if (done) cs.onPrimaryContainer else cs.onSurfaceVariant) },
        trailing = {
            if (done) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
            else Surface(shape = RoundedCornerShape(8.dp), color = cs.surfaceContainerHighest) {
                Text(stringResource(R.string.wiz_needed), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        },
    )
}

/** A plain explaining row with an icon. */
@Composable
private fun InfoRow(icon: ImageVector, title: String, body: String) {
    val cs = MaterialTheme.colorScheme
    SettingRow(title, body, leading = { RowIcon(icon, cs.surfaceContainerHigh, cs.onSurfaceVariant) })
}

/* ---------------------------------------------------------------- pages */

private fun LazyListScope.welcome() {
    item {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 28.dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.GraphicEq, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.wiz_welcome_title), style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.wiz_welcome_body), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        }
    }
    item {
        // the bar itself, as it'll sit under your apps
        Surface(shape = RoundedCornerShape(28.dp), color = rowColor(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> DockView(ctx, null).apply { showAsPreview() } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    group(R.string.wiz_welcome_list, listOf(
        { InfoRow(Icons.Rounded.Keyboard, stringResource(R.string.wiz_w_keyboards), stringResource(R.string.wiz_w_keyboards_body)) },
        { InfoRow(Icons.Rounded.Mic, stringResource(R.string.wiz_w_mic), stringResource(R.string.wiz_w_mic_body)) },
        { InfoRow(Icons.Rounded.Key, stringResource(R.string.wiz_w_keys), stringResource(R.string.wiz_w_keys_body)) },
        { InfoRow(Icons.Rounded.AccessibilityNew, stringResource(R.string.wiz_w_companion), stringResource(R.string.wiz_w_companion_body)) },
    ))
    item { Footnote(stringResource(R.string.wiz_welcome_foot)) }
}

private fun LazyListScope.keyboards(state: WizardState, actions: WizardActions) {
    hero(Icons.Rounded.Keyboard, R.string.wiz_kb_title, R.string.wiz_kb_body) { MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer }
    group(null, listOf(
        { StatusRow(Icons.Rounded.GraphicEq, stringResource(R.string.wiz_kb_voicebar), state.setup.enabled, stringResource(R.string.wiz_kb_voicebar_todo), stringResource(R.string.wiz_on)) },
        {
            if (!state.gboardInstalled) SettingRow(
                stringResource(R.string.wiz_kb_gboard), stringResource(R.string.wiz_kb_gboard_missing),
                leading = { RowIcon(Icons.Rounded.Keyboard, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer) },
                trailing = { FilledTonalButton(onClick = actions::getGboard) { Text(stringResource(R.string.wiz_get)) } },
            ) else StatusRow(Icons.Rounded.Keyboard, stringResource(R.string.wiz_kb_gboard), state.setup.gboard, stringResource(R.string.wiz_kb_gboard_todo), stringResource(R.string.wiz_on))
        },
    ))
    item { Footnote(stringResource(R.string.wiz_kb_warning)) }
}

private fun LazyListScope.switchTo(state: WizardState, actions: WizardActions) {
    hero(Icons.Rounded.SwapHoriz, R.string.wiz_sw_title, R.string.wiz_sw_body) { MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer }
    group(null, listOf(
        { StatusRow(Icons.Rounded.GraphicEq, stringResource(R.string.wiz_sw_status), state.setup.current, stringResource(R.string.wiz_sw_todo), stringResource(R.string.wiz_sw_done)) },
        { InfoRow(Icons.Rounded.SwapHoriz, stringResource(R.string.wiz_sw_tip), stringResource(R.string.wiz_sw_tip_body)) },
    ))
}

private fun LazyListScope.mic(state: WizardState, actions: WizardActions) {
    hero(Icons.Rounded.Mic, R.string.wiz_mic_title, R.string.wiz_mic_body) { MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer }
    group(null, listOf(
        { StatusRow(Icons.Rounded.Mic, stringResource(R.string.voice_mic_title), state.setup.mic, stringResource(R.string.wiz_mic_todo), stringResource(R.string.wiz_allowed)) },
        { InfoRow(Icons.Rounded.RecordVoiceOver, stringResource(R.string.wiz_mic_when), stringResource(R.string.wiz_mic_when_body)) },
        { InfoRow(Icons.Rounded.Lock, stringResource(R.string.wiz_mic_wake), stringResource(R.string.wiz_mic_wake_body)) },
    ))
}

/** A page on the service's site that the key page links to: what it's for, and how to use it. */
data class KeyLink(val icon: ImageVector, val title: Int, val body: Int, val url: String)

private fun LazyListScope.keyPage(
    field: KeyField, key: WizardActions.Key, actions: WizardActions,
    links: List<KeyLink>, hint: Int, foot: Int,
) {
    group(null, links.map { link ->
        @Composable {
            val cs = MaterialTheme.colorScheme
            SettingRow(
                stringResource(link.title), stringResource(link.body),
                leading = { RowIcon(link.icon, cs.primaryContainer, cs.onPrimaryContainer) },
                trailing = { Icon(Icons.AutoMirrored.Rounded.OpenInNew, stringResource(R.string.wiz_opens_browser), tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp)) },
                onClick = { actions.openUrl(link.url) },
            )
        }
    } + listOf<@Composable () -> Unit>({ KeyInput(field, hint, { actions.keyText(key, it) }, { actions.pasteKey(key) }) }))
    item { Footnote(stringResource(foot)) }
}

/** Parakeet on the phone, as an alternative to a key: download state and its button. */
@Composable
private fun ParakeetRow(dl: com.ryderdavid.voicebar.voice.ModelPack.State, actions: WizardActions) {
    val cs = MaterialTheme.colorScheme
    val mb = com.ryderdavid.voicebar.voice.Parakeet.TOTAL / 1_000_000
    Column {
        SettingRow(
            stringResource(R.string.engine_parakeet),
            when (dl) {
                com.ryderdavid.voicebar.voice.ModelPack.State.Ready -> stringResource(R.string.wiz_sp_local_ready)
                is com.ryderdavid.voicebar.voice.ModelPack.State.Downloading -> stringResource(R.string.engine_parakeet_downloading, dl.done / 1_000_000, dl.total / 1_000_000)
                com.ryderdavid.voicebar.voice.ModelPack.State.Checking -> stringResource(R.string.engine_parakeet_checking)
                is com.ryderdavid.voicebar.voice.ModelPack.State.Failed -> dl.why
                com.ryderdavid.voicebar.voice.ModelPack.State.Idle -> stringResource(R.string.engine_parakeet_summary, mb)
            },
            leading = { RowIcon(Icons.Rounded.Smartphone, cs.primaryContainer, cs.onPrimaryContainer) },
            trailing = {
                when (dl) {
                    com.ryderdavid.voicebar.voice.ModelPack.State.Ready -> Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    is com.ryderdavid.voicebar.voice.ModelPack.State.Downloading, com.ryderdavid.voicebar.voice.ModelPack.State.Checking ->
                        CircularProgressIndicator(
                            progress = { if (dl is com.ryderdavid.voicebar.voice.ModelPack.State.Downloading) (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) else 1f },
                            modifier = Modifier.size(28.dp), strokeWidth = 3.dp,
                        )
                    else -> FilledTonalButton(onClick = actions::downloadParakeet) {
                        Text(stringResource(if (dl is com.ryderdavid.voicebar.voice.ModelPack.State.Failed) R.string.engine_retry else R.string.engine_download))
                    }
                }
            },
        )
    }
}

@Composable
private fun KeyInput(field: KeyField, hint: Int, onText: (String) -> Unit, onPaste: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var shown by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        OutlinedTextField(
            value = field.text,
            onValueChange = onText,
            singleLine = true,
            label = { Text(stringResource(R.string.wiz_key_label)) },
            placeholder = { Text(stringResource(hint), fontFamily = FontFamily.Monospace) },
            visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            shape = RoundedCornerShape(16.dp),
            isError = field.check == KeyCheck.BAD,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = cs.surfaceContainerLow, focusedContainerColor = cs.surfaceContainerLow),
            trailingIcon = {
                Row {
                    if (field.text.isNotEmpty()) IconButton(onClick = { shown = !shown }) {
                        Icon(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, stringResource(R.string.wiz_show_key))
                    }
                    IconButton(onClick = onPaste) { Icon(Icons.Rounded.ContentPaste, stringResource(R.string.wiz_paste)) }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp)) {
            when (field.check) {
                KeyCheck.CHECKING -> {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.wiz_key_checking), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                }
                KeyCheck.OK -> {
                    Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(field.message ?: stringResource(R.string.wiz_key_ok), style = MaterialTheme.typography.bodyMedium, color = cs.primary)
                }
                KeyCheck.BAD -> {
                    Icon(Icons.Rounded.ErrorOutline, null, tint = cs.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(field.message ?: stringResource(R.string.wiz_key_bad), style = MaterialTheme.typography.bodyMedium, color = cs.error)
                }
                KeyCheck.IDLE -> Text(
                    stringResource(if (field.saved) R.string.wiz_key_saved else R.string.wiz_key_private),
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

private fun LazyListScope.speech(state: WizardState, actions: WizardActions) {
    hero(Icons.Rounded.GraphicEq, R.string.wiz_sp_title, R.string.wiz_sp_body) { MaterialTheme.colorScheme.tertiaryContainer to MaterialTheme.colorScheme.onTertiaryContainer }
    keyPage(state.eleven, WizardActions.Key.ELEVENLABS, actions, listOf(
        KeyLink(Icons.Rounded.PersonAdd, R.string.wiz_sp_signup, R.string.wiz_sp_signup_body, ELEVENLABS_SIGNUP_URL),
        KeyLink(Icons.Rounded.Key, R.string.wiz_sp_get, R.string.wiz_sp_steps, ELEVENLABS_KEYS_URL),
    ), R.string.wiz_sp_hint, R.string.wiz_sp_foot)
    group(R.string.wiz_sp_local_header, listOf({ ParakeetRow(state.parakeet, actions) }))
    item { Footnote(stringResource(R.string.wiz_sp_local_foot)) }
}

private fun LazyListScope.cleanup(state: WizardState, actions: WizardActions) {
    hero(Icons.Rounded.AutoAwesome, R.string.wiz_cl_title, R.string.wiz_cl_body) { MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer }
    item {
        // what cleanup does, in one example
        val cs = MaterialTheme.colorScheme
        Surface(shape = RoundedCornerShape(28.dp), color = rowColor(), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.wiz_cl_said), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text(stringResource(R.string.wiz_cl_raw), style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = cs.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.wiz_cl_typed), style = MaterialTheme.typography.labelMedium, color = cs.primary)
                Text(stringResource(R.string.wiz_cl_clean), style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    keyPage(state.claude, WizardActions.Key.ANTHROPIC, actions, listOf(
        KeyLink(Icons.Rounded.PersonAdd, R.string.wiz_cl_signup, R.string.wiz_cl_signup_body, ANTHROPIC_SIGNUP_URL),
        KeyLink(Icons.Rounded.Payments, R.string.wiz_cl_credits, R.string.wiz_cl_credits_body, ANTHROPIC_BILLING_URL),
        KeyLink(Icons.Rounded.Key, R.string.wiz_cl_get, R.string.wiz_cl_steps, ANTHROPIC_KEYS_URL),
    ), R.string.wiz_cl_hint, R.string.wiz_cl_foot)
}

private fun LazyListScope.companion(state: WizardState, actions: WizardActions) {
    hero(Icons.Rounded.AccessibilityNew, R.string.wiz_co_title, R.string.wiz_co_body) { MaterialTheme.colorScheme.secondaryContainer to MaterialTheme.colorScheme.onSecondaryContainer }
    group(null, listOf(
        { StatusRow(Icons.Rounded.AccessibilityNew, stringResource(R.string.wiz_co_status), state.setup.gboardBubble, stringResource(R.string.wiz_co_todo), stringResource(R.string.wiz_on)) },
        {
            // so Android doesn't stop it (and switch the button off) to save battery
            val cs = MaterialTheme.colorScheme
            val done = state.setup.unrestricted
            SettingRow(
                stringResource(R.string.wiz_co_bg), stringResource(if (done) R.string.wiz_co_bg_done else R.string.wiz_co_bg_todo),
                leading = { RowIcon(Icons.Rounded.BatteryChargingFull, if (done) cs.primaryContainer else cs.surfaceContainerHighest, if (done) cs.onPrimaryContainer else cs.onSurfaceVariant) },
                trailing = {
                    if (done) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    else FilledTonalButton(onClick = actions::allowBackground) { Text(stringResource(R.string.wiz_co_bg_allow)) }
                },
                onClick = if (done) null else actions::allowBackground,
            )
        },
    ))
    if (state.sideloaded && !state.setup.gboardBubble) item {
        // Android 13+: an app installed from a file can't get accessibility until you allow it
        val cs = MaterialTheme.colorScheme
        Surface(shape = RoundedCornerShape(28.dp), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.wiz_co_restricted_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.wiz_co_restricted_body), style = MaterialTheme.typography.bodyMedium)
                RestrictedStep(1, stringResource(R.string.wiz_co_r1), null)
                RestrictedStep(2, stringResource(R.string.wiz_co_r2), Icons.Rounded.MoreVert)
                RestrictedStep(3, stringResource(R.string.wiz_co_r3), null)
                FilledTonalButton(onClick = actions::openAppInfo) { Text(stringResource(R.string.wiz_co_app_info)) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    group(R.string.wiz_co_gets, listOf(
        { InfoRow(Icons.Rounded.KeyboardReturn, stringResource(R.string.wiz_co_back), stringResource(R.string.wiz_co_back_body)) },
        { InfoRow(Icons.Rounded.TouchApp, stringResource(R.string.wiz_co_hands), stringResource(R.string.wiz_co_hands_body)) },
        { InfoRow(Icons.Rounded.Spellcheck, stringResource(R.string.wiz_co_names), stringResource(R.string.wiz_co_names_body)) },
    ))
    item { Footnote(stringResource(R.string.wiz_co_foot)) }
}

@Composable
private fun RestrictedStep(n: Int, text: String, icon: ImageVector?) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = CircleShape, color = cs.onTertiaryContainer, contentColor = cs.tertiaryContainer, modifier = Modifier.size(24.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelMedium) }
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (icon != null) Icon(icon, null, modifier = Modifier.size(20.dp))
    }
}

private fun LazyListScope.allSet(state: WizardState, actions: WizardActions) {
    item {
        val cs = MaterialTheme.colorScheme
        Column(Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 24.dp, bottom = 24.dp)) {
            Box(Modifier.size(88.dp).clip(RoundedCornerShape(28.dp)).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.CheckCircle, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.wiz_done_title), style = MaterialTheme.typography.displaySmall, color = cs.onSurface)
            Spacer(Modifier.height(10.dp))
            Text(stringResource(R.string.wiz_done_body), style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
        }
    }
    val rows: List<Triple<WizardStep, ImageVector, Int>> = listOf(
        Triple(WizardStep.KEYBOARDS, Icons.Rounded.Keyboard, R.string.wiz_sum_keyboards),
        Triple(WizardStep.SWITCH, Icons.Rounded.SwapHoriz, R.string.wiz_sum_switch),
        Triple(WizardStep.MIC, Icons.Rounded.Mic, R.string.wiz_sum_mic),
        Triple(WizardStep.SPEECH, Icons.Rounded.GraphicEq, R.string.wiz_sum_speech),
        Triple(WizardStep.CLEANUP, Icons.Rounded.AutoAwesome, R.string.wiz_sum_cleanup),
        Triple(WizardStep.COMPANION, Icons.Rounded.AccessibilityNew, R.string.wiz_sum_companion),
    )
    group(null, rows.map { (step, icon, title) ->
        @Composable {
            val cs = MaterialTheme.colorScheme
            val done = state.done(step)
            SettingRow(
                stringResource(title),
                stringResource(when { done -> R.string.wiz_done; step.optional -> R.string.wiz_skipped_later; else -> R.string.wiz_not_yet }),
                leading = { RowIcon(icon, if (done) cs.primaryContainer else cs.surfaceContainerHighest, if (done) cs.onPrimaryContainer else cs.onSurfaceVariant) },
                trailing = {
                    if (done) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    else TextButton(onClick = { actions.goTo(step) }) { Text(stringResource(R.string.wiz_set_up)) }
                },
            )
        }
    })
    item { Footnote(stringResource(R.string.wiz_done_foot)) }
}

const val ELEVENLABS_SIGNUP_URL = "https://elevenlabs.io/app/sign-up"
/** Developers › API Keys in the ElevenLabs app. */
const val ELEVENLABS_KEYS_URL = "https://elevenlabs.io/app/developers/api-keys"
/** The Claude Console (API accounts are separate from Claude.ai plans): sign in or sign up. */
const val ANTHROPIC_SIGNUP_URL = "https://platform.claude.com/"
const val ANTHROPIC_BILLING_URL = "https://platform.claude.com/settings/billing"
const val ANTHROPIC_KEYS_URL = "https://platform.claude.com/settings/keys"

/* ---------------------------------------------------------------- the live wizard */

/** Installed from a downloaded or local file (not a store): Android 13+ restricts accessibility for it. */
fun isSideloaded(context: android.content.Context): Boolean {
    if (android.os.Build.VERSION.SDK_INT < 33) return false
    val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull() ?: return false
    return source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE ||
        source == android.content.pm.PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE
}

fun gboardInstalled(context: android.content.Context): Boolean =
    runCatching { context.packageManager.getPackageInfo(Prefs.GBOARD_PACKAGE, 0) }.isSuccess

/**
 * The wizard with its state: steps, keys being checked, and the system screens each page opens.
 * [setup] is re-read by the activity whenever you come back to it, so pages tick themselves off.
 * [onExit] is told whether to open Try it.
 */
@Composable
fun SetupWizardHost(setup: SettingsActivity.Setup, refresh: () -> Unit, onExit: (tryIt: Boolean) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = context as? androidx.activity.ComponentActivity
    val main = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    var step by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(WizardStep.WELCOME) }
    var eleven by remember { mutableStateOf(KeyField(saved = com.ryderdavid.voicebar.voice.Secrets.has(context, com.ryderdavid.voicebar.voice.Secrets.ELEVENLABS))) }
    var claude by remember { mutableStateOf(KeyField(saved = com.ryderdavid.voicebar.voice.Secrets.has(context, com.ryderdavid.voicebar.voice.Secrets.ANTHROPIC))) }
    var skipped by remember { mutableStateOf(emptySet<WizardStep>()) }
    val sideloaded = remember { isSideloaded(context) }
    val hasGboard = remember(setup) { gboardInstalled(context) }
    val parakeet = rememberParakeet()

    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        // after a permanent "don't allow" the dialog no longer shows: send them to the app's page
        if (!granted && activity?.shouldShowRequestPermissionRationale(android.Manifest.permission.RECORD_AUDIO) == false) {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)))
        }
    }

    fun finish(tryIt: Boolean) {
        Prefs.get(context).edit().putBoolean(Prefs.SETUP_DONE, true).apply()
        onExit(tryIt)
    }

    fun open(intent: android.content.Intent) {
        runCatching { context.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun setStep(s: WizardStep) { step = s }
    val actions = remember {
        object : WizardActions {
            override fun next() { step = WizardStep.entries.getOrElse(step.ordinal + 1) { WizardStep.DONE } }
            override fun back() { if (step != WizardStep.WELCOME) step = WizardStep.entries[step.ordinal - 1] }
            override fun skip() { skipped = skipped + step; next() }
            override fun goTo(step: WizardStep) = setStep(step)
            override fun openKeyboardSettings() = open(android.content.Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            override fun pickKeyboard() {
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
            }
            override fun getGboard() {
                val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=${Prefs.GBOARD_PACKAGE}"))
                if (runCatching { context.startActivity(market.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isFailure)
                    openUrl("https://play.google.com/store/apps/details?id=${Prefs.GBOARD_PACKAGE}")
            }
            override fun askMic() = askMic.launch(android.Manifest.permission.RECORD_AUDIO)
            override fun openUrl(url: String) = open(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
            override fun keyText(key: WizardActions.Key, text: String) {
                setField(key, KeyField(text = text.trim(), saved = field(key).saved))
            }
            override fun pasteKey(key: WizardActions.Key) {
                val clip = context.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) keyText(key, text)
            }
            private fun field(key: WizardActions.Key) = when (key) { WizardActions.Key.ELEVENLABS -> eleven; WizardActions.Key.ANTHROPIC -> claude }
            private fun setField(key: WizardActions.Key, f: KeyField) { when (key) { WizardActions.Key.ELEVENLABS -> eleven = f; WizardActions.Key.ANTHROPIC -> claude = f } }
            override fun checkKey(key: WizardActions.Key) {
                val text = field(key).text.trim()
                if (text.isEmpty()) return
                setField(key, field(key).copy(check = KeyCheck.CHECKING, message = null))
                Thread {
                    val r = when (key) {
                        WizardActions.Key.ELEVENLABS -> com.ryderdavid.voicebar.voice.KeyChecks.elevenLabs(text)
                        WizardActions.Key.ANTHROPIC -> com.ryderdavid.voicebar.voice.KeyChecks.anthropic(text)
                    }
                    main.post {
                        when (r) {
                            com.ryderdavid.voicebar.voice.KeyChecks.Result.Ok -> {
                                val name = when (key) {
                                    WizardActions.Key.ELEVENLABS -> com.ryderdavid.voicebar.voice.Secrets.ELEVENLABS
                                    WizardActions.Key.ANTHROPIC -> com.ryderdavid.voicebar.voice.Secrets.ANTHROPIC
                                }
                                val saved = com.ryderdavid.voicebar.voice.Secrets.put(context, name, text)
                                setField(key, field(key).copy(saved = saved, check = if (saved) KeyCheck.OK else KeyCheck.BAD,
                                    message = if (saved) null else context.getString(R.string.wiz_key_not_saved)))
                            }
                            is com.ryderdavid.voicebar.voice.KeyChecks.Result.Rejected -> setField(key, field(key).copy(check = KeyCheck.BAD, message = r.why))
                            is com.ryderdavid.voicebar.voice.KeyChecks.Result.Unreachable -> setField(key, field(key).copy(check = KeyCheck.BAD, message = r.why))
                        }
                    }
                }.apply { isDaemon = true }.start()
            }
            override fun downloadParakeet() {
                Prefs.get(context).edit().putString(Prefs.SPEECH_ENGINE, Prefs.ENGINE_PARAKEET).apply()
                com.ryderdavid.voicebar.voice.Parakeet.download(context)
            }
            override fun openAccessibility() = open(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
            override fun openAppInfo() = open(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.fromParts("package", context.packageName, null)))
            override fun allowBackground() = Background.ask(context)
            override fun tryIt() = finish(true)
            override fun finish() = finish(false)
        }
    }
    androidx.activity.compose.BackHandler(enabled = step != WizardStep.WELCOME) { actions.back() }
    SetupWizard(step, WizardState(setup = setup, gboardInstalled = hasGboard, eleven = eleven, claude = claude, sideloaded = sideloaded, skipped = skipped, parakeet = parakeet), actions)
}
