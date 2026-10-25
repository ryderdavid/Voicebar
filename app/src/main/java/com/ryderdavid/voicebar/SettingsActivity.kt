package com.ryderdavid.voicebar

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.automirrored.rounded.KeyboardReturn
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material.icons.rounded.SpaceBar
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.Gamepad
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.OpenWith
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import com.ryderdavid.voicebar.voice.ClaudeCleanupRequest
import com.ryderdavid.voicebar.voice.Pricing
import com.ryderdavid.voicebar.voice.UsageLog
import com.ryderdavid.voicebar.voice.Secrets
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The app's settings: a home page of categories, each opening its own short page. */
class SettingsActivity : ComponentActivity() {

    /** Whether the keyboard is turned on and chosen, and the microphone allowed; re-read whenever we come back. */
    data class Setup(val enabled: Boolean = false, val current: Boolean = false, val mic: Boolean = false, val gboard: Boolean = false, val gboardBubble: Boolean = false,
                     /** Exempt from battery optimization (Samsung: "Unrestricted"): Android won't stop it in the background. */
                     val unrestricted: Boolean = false,
                     /** The old app (Bubble Keyboard's app ID, before 1.0) is still installed: offer to move its settings over. */
                     val oldApp: Boolean = false)

    private var setup by mutableStateOf(Setup())

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
        refreshSetup()
        val start = intent.getStringExtra(EXTRA_PAGE)?.let { runCatching { Page.valueOf(it) }.getOrNull() } ?: Page.HOME
        // first launch: the setup wizard, unless everything it does is done already (an update)
        val prefs = Prefs.get(this)
        val readyAlready = setup.enabled && setup.current && setup.mic && Secrets.has(this, Secrets.ELEVENLABS)
        if (readyAlready && !prefs.getBoolean(Prefs.SETUP_DONE, false)) prefs.edit().putBoolean(Prefs.SETUP_DONE, true).apply()
        // moving from the old app: the home page's moving card comes first (an import usually finishes setup too)
        val wizardFirst = intent.getStringExtra(EXTRA_PAGE) == null && !prefs.getBoolean(Prefs.SETUP_DONE, false) && !setup.oldApp
        setContent { VoicebarTheme { SettingsApp(setup, ::refreshSetup, start, wizardFirst) } }
        // the welcome video, once, before anything else (the wizard waits behind it)
        if (savedInstanceState == null && !WelcomeActivity.seen(this)) WelcomeActivity.open(this)
    }

    override fun onResume() {
        super.onResume()
        refreshSetup()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) refreshSetup() // the keyboard picker is a dialog, so onResume doesn't run after it
    }

    private fun refreshSetup() {
        val imm = getSystemService(android.view.inputmethod.InputMethodManager::class.java)
        val enabled = imm?.enabledInputMethodList?.any { it.packageName == packageName } == true
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        val mic = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val gboard = Prefs.gboardImeId(this) != null
        val oldApp = runCatching { packageManager.getPackageInfo(Migration.OLD_PACKAGE, 0); true }.getOrDefault(false)
        setup = Setup(enabled, current, mic, gboard, GboardButtonService.isEnabled(this), Background.unrestricted(this), oldApp)
    }

    companion object {
        /** Opens on a page other than home (a [Page] name). */
        const val EXTRA_PAGE = "page"
    }
}

enum class Page { HOME, LOOK, VOICE, CLEANUP, LANGUAGE, DICTIONARY, HELP, TRY, VOICEPRINT, LOGS, NOTICES }

@Composable
fun SettingsApp(setup: SettingsActivity.Setup, refresh: () -> Unit, start: Page = Page.HOME, wizardFirst: Boolean = false) {
    var page by rememberSaveable { mutableStateOf(start) }
    var wizard by rememberSaveable { mutableStateOf(wizardFirst) }
    if (wizard) {
        SetupWizardHost(setup, refresh) { tryIt -> wizard = false; page = if (tryIt) Page.TRY else Page.HOME }
        return
    }
    BackHandler(enabled = page != Page.HOME) { page = if (page == Page.VOICEPRINT) Page.VOICE else Page.HOME }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.HOME && !(initialState == Page.VOICEPRINT && targetState == Page.VOICE)
            (slideInHorizontally(tween(300)) { w -> if (forward) w / 5 else -w / 5 } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { w -> if (forward) -w / 5 else w / 5 } + fadeOut(tween(200)))
        },
        label = "page",
    ) { p ->
        val back = { page = Page.HOME }
        when (p) {
            Page.HOME -> HomePage(setup) { page = it }
            Page.LOOK -> LookPage(setup, back)
            Page.VOICE -> VoicePage(setup, refresh, back) { page = Page.VOICEPRINT }
            Page.VOICEPRINT -> VoiceprintPage { page = Page.VOICE }
            Page.CLEANUP -> CleanupPage(back) { page = Page.LOGS }
            Page.LOGS -> LogsPage(back)
            Page.NOTICES -> NoticesPage(back)
            Page.LANGUAGE -> LanguagePage(back)
            Page.DICTIONARY -> DictionaryPage(back)
            Page.HELP -> HelpPage(back) { wizard = true }
            Page.TRY -> TryPage(back)
        }
    }
}

/* ---------------------------------------------------------------- prefs */

@Composable
private fun rememberPrefs() = LocalContext.current.let { remember { Prefs.get(it) } }

@Composable
private fun intPref(key: String, default: Int, range: IntRange): Pair<Int, (Int) -> Unit> {
    val prefs = rememberPrefs()
    var v by remember { mutableIntStateOf(prefs.getInt(key, default).coerceIn(range)) }
    return v to { n: Int -> v = n; prefs.edit { putInt(key, n) } }
}

@Composable
private fun boolPref(key: String, default: Boolean): Pair<Boolean, (Boolean) -> Unit> {
    val prefs = rememberPrefs()
    var v by remember { mutableStateOf(prefs.getBoolean(key, default)) }
    return v to { b: Boolean -> v = b; prefs.edit { putBoolean(key, b) } }
}

/* ---------------------------------------------------------------- home */

@Composable
private fun HomePage(setup: SettingsActivity.Setup, open: (Page) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val voiceSummary = when {
        !setup.mic -> stringResource(R.string.home_voice_need_mic)
        !Secrets.has(context, Secrets.ELEVENLABS) -> stringResource(R.string.home_voice_need_key)
        else -> stringResource(R.string.home_voice_ready)
    }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val ok = runCatching { context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(Backup.export(context).toByteArray()) } }.isSuccess
        Toast.makeText(context, if (ok) R.string.backup_exported else R.string.backup_export_failed, Toast.LENGTH_LONG).show()
    }
    val importFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val s = Backup.import(context, text)
            Prefs.get(context).edit().putBoolean(Migration.IMPORTED, true).apply()
            context.getString(R.string.backup_imported, s.words, context.getString(if (s.voiceprint) R.string.backup_with_voiceprint else R.string.backup_no_voiceprint))
        }.getOrElse { it.message ?: context.getString(R.string.backup_import_failed) }
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
    SettingsPage(stringResource(R.string.app_name), onBack = null) {
        if (setup.oldApp) item {
            MoveCard(onImport = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            Spacer(Modifier.height(16.dp))
        }
        item {
            if (!setup.enabled || !setup.current || !setup.gboard || (!setup.gboardBubble && android.os.Build.VERSION.SDK_INT >= 30)) SetupCard(setup) else HeroCard(onTry = { open(Page.TRY) })
            Spacer(Modifier.height(24.dp))
        }
        group(rows = listOf(
            { NavRow(stringResource(R.string.page_look), stringResource(R.string.page_look_summary), Icons.Rounded.Palette, cs.primaryContainer, cs.onPrimaryContainer) { open(Page.LOOK) } },
            { NavRow(stringResource(R.string.page_voice), voiceSummary, Icons.Rounded.Mic, cs.tertiaryContainer, cs.onTertiaryContainer) { open(Page.VOICE) } },
            { NavRow(stringResource(R.string.page_cleanup), cleanupSummary(context), Icons.Rounded.AutoAwesome, cs.primaryContainer, cs.onPrimaryContainer) { open(Page.CLEANUP) } },
            { NavRow(stringResource(R.string.page_language), stringResource(R.string.page_language_summary), Icons.Rounded.TextFields, cs.primaryContainer, cs.onPrimaryContainer) { open(Page.LANGUAGE) } },
            { NavRow(stringResource(R.string.page_dictionary), stringResource(R.string.page_dictionary_summary, com.ryderdavid.voicebar.voice.Dictionary.of(LocalContext.current).all().size), Icons.Rounded.Spellcheck, cs.secondaryContainer, cs.onSecondaryContainer) { open(Page.DICTIONARY) } },
        ))
        group(rows = listOf(
            { NavRow(stringResource(R.string.page_help), stringResource(R.string.page_help_summary), Icons.Rounded.Lightbulb, cs.surfaceContainerHighest, cs.onSurfaceVariant) { open(Page.HELP) } },
            { NavRow(stringResource(R.string.page_try), stringResource(R.string.page_try_summary), Icons.Rounded.Keyboard, cs.surfaceContainerHighest, cs.onSurfaceVariant) { open(Page.TRY) } },
            { NavRow(stringResource(R.string.page_logs), stringResource(R.string.page_logs_summary), Icons.Rounded.Article, cs.surfaceContainerHighest, cs.onSurfaceVariant) { open(Page.LOGS) } },
            { NavRow(stringResource(R.string.page_notices), stringResource(R.string.page_notices_summary), Icons.Rounded.Info, cs.surfaceContainerHighest, cs.onSurfaceVariant) { open(Page.NOTICES) } },
        ))
        group(R.string.backup_group, listOf(
            {
                SettingRow(stringResource(R.string.backup_export), stringResource(R.string.backup_export_summary),
                    leading = { RowIcon(Icons.Rounded.Upload, cs.secondaryContainer, cs.onSecondaryContainer) },
                    onClick = { exportFile.launch(Backup.fileName()) })
            },
            {
                SettingRow(stringResource(R.string.backup_import), stringResource(R.string.backup_import_summary),
                    leading = { RowIcon(Icons.Rounded.Download, cs.secondaryContainer, cs.onSecondaryContainer) },
                    onClick = { importFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) })
            },
        ))
        item {
            Text(
                stringResource(R.string.version, BuildConfigCompat.versionName(context)),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Shown until the keyboard is turned on and chosen: two steps, each with its button. */
@Composable
private fun SetupCard(setup: SettingsActivity.Setup) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = cs.primaryContainer, contentColor = cs.onPrimaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium)
            SetupStep(1, stringResource(R.string.setup_enable), setup.enabled, stringResource(R.string.setup_enable_button)) {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            SetupStep(2, stringResource(R.string.setup_switch), setup.current, stringResource(R.string.setup_switch_button), enabled = setup.enabled) {
                context.getSystemService(android.view.inputmethod.InputMethodManager::class.java)?.showInputMethodPicker()
            }
            SetupStep(3, stringResource(R.string.setup_gboard), setup.gboard, stringResource(R.string.setup_enable_button)) {
                context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
            if (android.os.Build.VERSION.SDK_INT >= 30) SetupStep(4, stringResource(R.string.setup_way_back), setup.gboardBubble, stringResource(R.string.setup_enable_button)) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }
}

/** Moving from the old app: export there, import here, uninstall it. Shown while the old app is installed. */
@Composable
private fun MoveCard(onImport: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val imported = Prefs.get(context).getBoolean(Migration.IMPORTED, false)
    Surface(shape = RoundedCornerShape(28.dp), color = cs.tertiaryContainer, contentColor = cs.onTertiaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.move_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.move_body), style = MaterialTheme.typography.bodyMedium)
            SetupStep(1, stringResource(R.string.move_export), imported, stringResource(R.string.move_export_button)) {
                context.packageManager.getLaunchIntentForPackage(Migration.OLD_PACKAGE)?.let { context.startActivity(it) }
            }
            SetupStep(2, stringResource(R.string.move_import), imported, stringResource(R.string.move_import_button), onClick = onImport)
            SetupStep(3, stringResource(R.string.move_uninstall), false, stringResource(R.string.move_uninstall_button)) {
                context.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:" + Migration.OLD_PACKAGE)))
            }
        }
    }
}

@Composable
private fun SetupStep(n: Int, title: String, done: Boolean, action: String, enabled: Boolean = true, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(32.dp).then(Modifier.padding(0.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary, modifier = Modifier.size(28.dp))
            else Surface(shape = CircleShape, color = cs.primary, contentColor = cs.onPrimary, modifier = Modifier.size(26.dp)) {
                Box(contentAlignment = Alignment.Center) { Text("$n", style = MaterialTheme.typography.labelLarge) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!done) Button(onClick = onClick, enabled = enabled) { Text(action) }
    }
}

/** Once set up: the bar itself and a way to try it. */
@Composable
private fun HeroCard(onTry: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(28.dp), color = rowColor()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.hero_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.hero_body), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { ctx -> DockView(ctx, null).apply { showAsPreview() } },
                    update = { it.showAsPreview() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            FilledTonalButton(onClick = onTry) { Text(stringResource(R.string.page_try)) }
        }
    }
}

/* ---------------------------------------------------------------- look */

@Composable
private fun LookPage(setup: SettingsActivity.Setup, onBack: () -> Unit) {
    val prefs = rememberPrefs()
    var barKey by remember { mutableStateOf(prefs.getString(Prefs.BAR_KEY, Prefs.DEFAULT_BAR_KEY) ?: Prefs.DEFAULT_BAR_KEY) }
    var abcTap by remember { mutableStateOf(prefs.getString(Prefs.ABC_TAP, Prefs.DEFAULT_ABC_TAP) ?: Prefs.DEFAULT_ABC_TAP) }
    var swipe by remember { mutableStateOf(prefs.getBoolean(Prefs.SWIPE_TYPING, Prefs.DEFAULT_SWIPE_TYPING)) }
    val setAbcTap = { v: String -> abcTap = v; prefs.edit { putString(Prefs.ABC_TAP, v) } }
    val setBarKey = { v: String -> barKey = v; prefs.edit { putString(Prefs.BAR_KEY, v) } }
    var barKeyRight by remember { mutableStateOf(prefs.getString(Prefs.BAR_KEY_RIGHT, Prefs.DEFAULT_BAR_KEY_RIGHT) ?: Prefs.DEFAULT_BAR_KEY_RIGHT) }
    val setBarKeyRight = { v: String -> barKeyRight = v; prefs.edit { putString(Prefs.BAR_KEY_RIGHT, v) } }
    var barKeyMenu by remember { mutableStateOf(prefs.getString(Prefs.BAR_KEY_MENU, Prefs.DEFAULT_BAR_KEY_MENU) ?: Prefs.DEFAULT_BAR_KEY_MENU) }
    val setBarKeyMenu = { v: String -> barKeyMenu = v; prefs.edit { putString(Prefs.BAR_KEY_MENU, v) } }
    var barKeyEnter by remember { mutableStateOf(prefs.getString(Prefs.BAR_KEY_ENTER, Prefs.DEFAULT_BAR_KEY_ENTER) ?: Prefs.DEFAULT_BAR_KEY_ENTER) }
    val setBarKeyEnter = { v: String -> barKeyEnter = v; prefs.edit { putString(Prefs.BAR_KEY_ENTER, v) } }
    var gboardReturn by remember { mutableStateOf(prefs.getString(Prefs.GBOARD_RETURN, Prefs.DEFAULT_GBOARD_RETURN) ?: Prefs.DEFAULT_GBOARD_RETURN) }
    val setGboardReturn = { v: String -> gboardReturn = v; prefs.edit { putString(Prefs.GBOARD_RETURN, v) } }
    val context = LocalContext.current
    SettingsPage(stringResource(R.string.page_look), onBack) {
        // the bar's four own keys, either side of Cursor · ABC · Voice: each can be anything
        run {
            group(R.string.bar_key_group, listOf<@Composable () -> Unit>(
                {
                    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        androidx.compose.ui.viewinterop.AndroidView(
                            factory = { ctx -> DockView(ctx, null).apply { showAsPreview(key = barKey, rightKey = barKeyRight, menuKey = barKeyMenu, enterKey = barKeyEnter) } },
                            update = { it.showAsPreview(key = barKey, rightKey = barKeyRight, menuKey = barKeyMenu, enterKey = barKeyEnter) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                { OwnKeyRow(stringResource(R.string.bar_key_slot_menu), Bar.ExtraKey.of(barKeyMenu, Bar.ExtraKey.MENU), setBarKeyMenu) },
                { OwnKeyRow(stringResource(R.string.bar_key_slot_left), Bar.ExtraKey.of(barKey), setBarKey) },
                { OwnKeyRow(stringResource(R.string.bar_key_slot_right), Bar.ExtraKey.of(barKeyRight, Bar.ExtraKey.BACKSPACE), setBarKeyRight) },
                { OwnKeyRow(stringResource(R.string.bar_key_slot_enter), Bar.ExtraKey.of(barKeyEnter, Bar.ExtraKey.ENTER), setBarKeyEnter) },
            ))
            item { Footnote(stringResource(R.string.bar_key_right_footnote)) }
        }
        // ABC: Voicebar's own pop-up keyboard, or Gboard; a hold gives the other
        group(R.string.abc_group, listOf(
            { StyleRow(stringResource(R.string.abc_tap_keys), stringResource(R.string.abc_tap_keys_summary), abcTap != Prefs.ABC_TAP_GBOARD) { setAbcTap(Prefs.ABC_TAP_KEYS) } },
            { StyleRow(stringResource(R.string.abc_tap_gboard), stringResource(R.string.abc_tap_gboard_summary), abcTap == Prefs.ABC_TAP_GBOARD) { setAbcTap(Prefs.ABC_TAP_GBOARD) } },
            // FUTO's model license asks for "Powered by FUTO Swipe" where people can see it: here, and in the notices
            { SwitchRow(stringResource(R.string.swipe_typing), stringResource(R.string.swipe_typing_summary), swipe) { swipe = it; prefs.edit { putBoolean(Prefs.SWIPE_TYPING, it) } } },
        ))
        group(R.string.bar_side_group, listOf({ BarSideRow() }))
        // typing happens in Gboard: whether it's ready, and the button that brings Voicebar back
        group(R.string.gboard_group, listOf(
            {
                SettingRow(
                    stringResource(R.string.gboard_row),
                    stringResource(if (setup.gboard) R.string.gboard_ready else R.string.gboard_missing),
                    onClick = { context.startActivity(android.content.Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            },
            {
                val old = android.os.Build.VERSION.SDK_INT < 30
                SettingRow(
                    stringResource(R.string.gboard_bubble_row),
                    stringResource(when { old -> R.string.gboard_bubble_old_android; setup.gboardBubble -> R.string.gboard_bubble_on; else -> R.string.gboard_bubble_off }),
                    enabled = !old,
                    onClick = { context.startActivity(android.content.Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) },
                )
            },
            {
                SettingRow(
                    stringResource(R.string.bg_row),
                    stringResource(if (setup.unrestricted) R.string.bg_on else R.string.bg_off),
                    onClick = { Background.ask(context) },
                )
            },
        ))
        group(R.string.gboard_return_group, listOf(
            { StyleRow(stringResource(R.string.gboard_return_bar), stringResource(R.string.gboard_return_bar_summary), gboardReturn == Prefs.GBOARD_RETURN_BAR) { setGboardReturn(Prefs.GBOARD_RETURN_BAR) } },
            { StyleRow(stringResource(R.string.gboard_return_mic_key), stringResource(R.string.gboard_return_mic_key_summary), gboardReturn == Prefs.GBOARD_RETURN_MIC_KEY) { setGboardReturn(Prefs.GBOARD_RETURN_MIC_KEY) } },
            { StyleRow(stringResource(R.string.gboard_return_bubble), stringResource(R.string.gboard_return_bubble_summary), gboardReturn == Prefs.GBOARD_RETURN_BUBBLE) { setGboardReturn(Prefs.GBOARD_RETURN_BUBBLE) } },
        ))
    }
}


/** What the seven-button bar's own keys can be, with their names and descriptions. */
/** One of the seven-button bar's own keys: what it is now, and a dropdown of everything it can be. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OwnKeyRow(slot: String, current: Bar.ExtraKey, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val labels = OWN_KEY_CHOICES.toMap()
    androidx.compose.material3.ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        Box(Modifier.menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)) {
            SettingRow(
                slot, stringResource(labels.getValue(current).first),
                leading = { RowIcon(ownKeyIcon(current), cs.secondaryContainer, cs.onSecondaryContainer) },
                trailing = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            )
        }
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for ((key, text) in OWN_KEY_CHOICES) {
                androidx.compose.material3.DropdownMenuItem(
                    leadingIcon = { Icon(ownKeyIcon(key), null, tint = cs.onSurfaceVariant) },
                    text = {
                        Column {
                            Text(stringResource(text.first), style = MaterialTheme.typography.bodyLarge)
                            Text(stringResource(text.second), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (key == current) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary) },
                    onClick = { open = false; onPick(key.pref) },
                )
            }
        }
    }
}

private fun ownKeyIcon(key: Bar.ExtraKey) = when (key) {
    Bar.ExtraKey.SPACE -> Icons.Rounded.SpaceBar
    Bar.ExtraKey.BACKSPACE -> Icons.AutoMirrored.Rounded.Backspace
    Bar.ExtraKey.ENTER -> Icons.AutoMirrored.Rounded.KeyboardReturn
    Bar.ExtraKey.PASTE -> Icons.Rounded.ContentPaste
    Bar.ExtraKey.UNDO -> Icons.AutoMirrored.Rounded.Undo
    Bar.ExtraKey.SELECT_ALL -> Icons.Rounded.SelectAll
    Bar.ExtraKey.LAST_DICTATION -> Icons.Rounded.History
    Bar.ExtraKey.FIX_LAST -> Icons.Rounded.AutoFixHigh
    Bar.ExtraKey.MENU -> Icons.Rounded.MoreVert
}

private val OWN_KEY_CHOICES = listOf(
    Bar.ExtraKey.SPACE to (R.string.bar_key_space to R.string.bar_key_space_summary),
    Bar.ExtraKey.BACKSPACE to (R.string.bar_key_backspace to R.string.bar_key_backspace_summary),
    Bar.ExtraKey.ENTER to (R.string.bar_key_enter to R.string.bar_key_enter_summary),
    Bar.ExtraKey.PASTE to (R.string.bar_key_paste to R.string.bar_key_paste_summary),
    Bar.ExtraKey.UNDO to (R.string.bar_key_undo to R.string.bar_key_undo_summary),
    Bar.ExtraKey.SELECT_ALL to (R.string.bar_key_select_all to R.string.bar_key_select_all_summary),
    Bar.ExtraKey.LAST_DICTATION to (R.string.bar_key_last to R.string.bar_key_last_summary),
    Bar.ExtraKey.FIX_LAST to (R.string.dock_fix_offer to R.string.bar_key_fix_summary),
    Bar.ExtraKey.MENU to (R.string.bar_key_menu to R.string.bar_key_menu_summary),
)

@Composable
private fun CheckRow(title: String, summary: String, checked: Boolean, enabled: Boolean = true, onToggle: () -> Unit) =
    SettingRow(
        title, summary,
        enabled = enabled,
        leading = { androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = null, enabled = enabled) },
        onClick = onToggle,
        role = androidx.compose.ui.semantics.Role.Checkbox,
    )

@Composable
private fun StyleRow(title: String, summary: String, selected: Boolean, onSelect: () -> Unit) =
    SettingRow(
        title, summary,
        leading = { androidx.compose.material3.RadioButton(selected = selected, onClick = onSelect) },
        onClick = onSelect,
        role = androidx.compose.ui.semantics.Role.RadioButton,
    )

/** The folded bubble and the open ring side by side, at their real sizes. */
/* ---------------------------------------------------------------- voice */

private val usageWindows = listOf(R.string.usage_day to 24, R.string.usage_week to 24 * 7, R.string.usage_month to 24 * 30)

private fun fmtRate(v: Double): String = if (v >= 1 && v == Math.floor(v)) "%.0f".format(java.util.Locale.US, v) else "%.2f".format(java.util.Locale.US, v)

@Composable
private fun usageSummary(t: UsageLog.Totals): String {
    if (t.audioMs == 0L && t.inputTokens + t.outputTokens == 0L) return stringResource(R.string.usage_none)
    val parts = mutableListOf(
        stringResource(R.string.usage_scribe, UsageLog.formatUsd(t.scribeUsd), UsageLog.formatAudio(t.audioMs)),
        stringResource(R.string.usage_claude, UsageLog.formatUsd(t.claudeUsd), UsageLog.formatTokens(t.inputTokens + t.outputTokens)),
    )
    if (t.unpricedTokens > 0) parts += stringResource(R.string.usage_unpriced, UsageLog.formatTokens(t.unpricedTokens))
    return parts.joinToString(" · ")
}

@Composable
private fun VoicePage(setup: SettingsActivity.Setup, refresh: () -> Unit, onBack: () -> Unit, openVoiceprint: () -> Unit = {}) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    val prefs = rememberPrefs()
    val cs = MaterialTheme.colorScheme
    val (live, setLive) = boolPref(Prefs.VOICE_LIVE, Prefs.DEFAULT_VOICE_LIVE)
    val (autoListen, setAutoListen) = boolPref(Prefs.AUTO_LISTEN, Prefs.DEFAULT_AUTO_LISTEN)
    val (chatCare, setChatCare) = boolPref(Prefs.CHAT_CARE, Prefs.DEFAULT_CHAT_CARE)
    val (cleanup, setCleanup) = boolPref(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)
    val (screenContext, setScreenContext) = boolPref(Prefs.SCREEN_CONTEXT, Prefs.DEFAULT_SCREEN_CONTEXT)
    val (commands, setCommands) = boolPref(Prefs.VOICE_COMMANDS, Prefs.DEFAULT_VOICE_COMMANDS)
    val (wake, setWake) = boolPref(Prefs.WAKE_WORD, Prefs.DEFAULT_WAKE_WORD)
    val (handsFree, setHandsFree) = boolPref(Prefs.HANDS_FREE, Prefs.DEFAULT_HANDS_FREE)
    val (anywhere, setAnywhere) = boolPref(Prefs.HANDS_FREE_ANYWHERE, Prefs.DEFAULT_HANDS_FREE_ANYWHERE)
    val (silence, setSilence) = intPref(Prefs.SILENCE_TIMEOUT, Prefs.DEFAULT_SILENCE_TIMEOUT, Prefs.SILENCE_TIMEOUT_RANGE)
    var keysVersion by remember { mutableIntStateOf(0) } // re-reads the keys' saved state after a dialog
    var editing by remember { mutableStateOf<String?>(null) }
    var editingModel by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(prefs.getString(Prefs.CLEANUP_MODEL, null) ?: ClaudeCleanupRequest.DEFAULT_MODEL) }

    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh()
        // after a permanent "don't allow" the dialog no longer shows: send the user to the app's page
        if (!granted && activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == false) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
        }
    }

    @Suppress("UNUSED_EXPRESSION") keysVersion
    var usageVersion by remember { mutableIntStateOf(0) }
    val usage = remember(usageVersion) { UsageLog.of(context) }
    val hasEleven = Secrets.has(context, Secrets.ELEVENLABS)
    val hasClaude = Secrets.has(context, Secrets.ANTHROPIC)
    val hasOpenAI = Secrets.has(context, Secrets.OPENAI)
    val engineRows = EngineRows()
    val voiceprintRows = VoiceprintRows(openVoiceprint)

    SettingsPage(stringResource(R.string.page_voice), onBack) {
        group(rows = listOf({
            SettingRow(
                stringResource(R.string.voice_mic_title),
                stringResource(if (setup.mic) R.string.voice_mic_done else R.string.voice_mic_needed),
                leading = { RowIcon(Icons.Rounded.Mic, cs.tertiaryContainer, cs.onTertiaryContainer) },
                trailing = {
                    if (setup.mic) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary)
                    else FilledTonalButton(onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) }) { Text(stringResource(R.string.voice_mic_allow)) }
                },
            )
        }))
        group(R.string.group_engine, engineRows)
        group(R.string.group_speech, listOf<@Composable () -> Unit>(
            {
                SettingRow(
                    stringResource(R.string.voice_elevenlabs_key),
                    stringResource(if (hasEleven) R.string.voice_key_saved_summary else R.string.voice_elevenlabs_summary),
                    leading = { RowIcon(Icons.Rounded.Key, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    trailing = { KeyBadge(hasEleven) },
                    onClick = { editing = Secrets.ELEVENLABS },
                )
            },
            { SwitchRow(stringResource(R.string.auto_listen), stringResource(R.string.auto_listen_summary), autoListen, onChange = setAutoListen) },
            { SwitchRow(stringResource(R.string.chat_care), stringResource(R.string.chat_care_summary), chatCare, enabled = autoListen, onChange = setChatCare) },
            { SwitchRow(stringResource(R.string.voice_live), stringResource(R.string.voice_live_summary), live, onChange = setLive) },
            { SwitchRow(stringResource(R.string.screen_context), stringResource(R.string.screen_context_summary), screenContext, onChange = setScreenContext) },
            { SliderRow(stringResource(R.string.silence_timeout), stringResource(R.string.silence_timeout_summary), silence, Prefs.SILENCE_TIMEOUT_RANGE, { "$it s" }, onCommit = setSilence) },
        ))
        group(R.string.group_voiceprint, voiceprintRows)
        group(R.string.group_commands, listOf(
            { SwitchRow(stringResource(R.string.voice_commands), stringResource(R.string.voice_commands_summary), commands, onChange = setCommands) },
            { SwitchRow(stringResource(R.string.wake_word), stringResource(R.string.wake_word_summary, com.ryderdavid.voicebar.voice.WakeWord.status), wake, onChange = setWake) },
            {
                SettingRow(
                    stringResource(R.string.voice_commands_list_title), stringResource(R.string.voice_commands_list), enabled = commands,
                    leading = { RowIcon(Icons.Rounded.RecordVoiceOver, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                )
            },
        ))
        group(R.string.group_hands_free, listOf(
            { SwitchRow(stringResource(R.string.hands_free), stringResource(R.string.hands_free_summary), handsFree, onChange = setHandsFree) },
            { SwitchRow(stringResource(R.string.hands_free_anywhere), stringResource(R.string.hands_free_anywhere_summary), anywhere, enabled = handsFree && wake, onChange = setAnywhere) },
            {
                SettingRow(
                    stringResource(R.string.hands_free_commands_title), stringResource(R.string.hands_free_commands), enabled = handsFree,
                    leading = { RowIcon(Icons.Rounded.TouchApp, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                )
            },
        ))
        group(R.string.group_usage, usageWindows.map<Pair<Int, Int>, @Composable () -> Unit> { (label, hours) ->
            {
                val t = usage.last(hours)
                SettingRow(
                    stringResource(label),
                    usageSummary(t),
                    leading = { RowIcon(Icons.Rounded.Payments, cs.secondaryContainer, cs.onSecondaryContainer) },
                    trailing = { Text(UsageLog.formatUsd(t.totalUsd), style = MaterialTheme.typography.titleMedium, color = cs.onSurface) },
                )
            }
        } + listOf<@Composable () -> Unit>({
            SettingRow(
                stringResource(R.string.usage_clear),
                null,
                leading = { RowIcon(Icons.Rounded.DeleteSweep, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                onClick = { usage.clear(); usageVersion++ },
            )
        }))
        item {
            Footnote(stringResource(
                R.string.usage_footnote,
                Pricing.SCRIBE_REALTIME_PER_HOUR,
                model,
                Pricing.claude(model)?.let { "$" + fmtRate(it.input) + " in / $" + fmtRate(it.output) + " out per million tokens" }
                    ?: stringResource(R.string.usage_unknown_price),
            ))
        }
        item { Footnote(stringResource(R.string.voice_summary)) }
    }

    editing?.let { name ->
        KeyDialog(
            title = stringResource(when (name) { Secrets.ELEVENLABS -> R.string.voice_elevenlabs_key; Secrets.OPENAI -> R.string.voice_openai_key; else -> R.string.voice_anthropic_key }),
            help = stringResource(when (name) { Secrets.ELEVENLABS -> R.string.voice_elevenlabs_help; Secrets.OPENAI -> R.string.voice_openai_help; else -> R.string.voice_anthropic_help }),
            has = Secrets.has(context, name),
            onSave = { v ->
                if (!Secrets.put(context, name, v)) Toast.makeText(context, R.string.voice_key_failed, Toast.LENGTH_LONG).show()
                editing = null; keysVersion++
            },
            onRemove = { Secrets.put(context, name, null); editing = null; keysVersion++ },
            onDismiss = { editing = null },
        )
    }
}

/** One line for the home page: what cleanup is doing. */
@Composable
private fun cleanupSummary(context: android.content.Context): String {
    val p = Prefs.get(context)
    return when {
        !p.getBoolean(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP) -> stringResource(R.string.cleanup_summary_off)
        CleanupModels.resolve(context) == null -> stringResource(R.string.cleanup_summary_need_key)
        else -> CleanupModels.label(context, CleanupModels.resolve(context)!!.model)
    }
}

/**
 * Everything about cleanup in one place: on or off, the model (a dropdown of the ones that make
 * sense), the Claude key, the optional OpenAI key and the auto-listen check it unlocks, and what's
 * gone wrong lately.
 */
@Composable
private fun CleanupPage(onBack: () -> Unit, openLogs: () -> Unit = {}) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    val cs = MaterialTheme.colorScheme
    val (cleanup, setCleanup) = boolPref(Prefs.CLEANUP, Prefs.DEFAULT_CLEANUP)
    val (sendFinder, setSendFinder) = boolPref(Prefs.SEND_FINDER, Prefs.DEFAULT_SEND_FINDER)
    val (sendCheck, setSendCheck) = boolPref(Prefs.SEND_CHECK, Prefs.DEFAULT_SEND_CHECK)
    var keysVersion by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var model by remember { mutableStateOf(CleanupModels.chosen(context)) }
    var problem by remember { mutableStateOf(CleanupProblem.last(context)) }
    @Suppress("UNUSED_EXPRESSION") keysVersion
    val hasClaude = Secrets.has(context, Secrets.ANTHROPIC)
    val hasOpenAI = Secrets.has(context, Secrets.OPENAI)

    SettingsPage(stringResource(R.string.page_cleanup), onBack) {
        group(null, listOf<@Composable () -> Unit>(
            { SwitchRow(stringResource(R.string.voice_cleanup), stringResource(R.string.voice_cleanup_summary), cleanup, onChange = setCleanup) },
            {
                SettingRow(
                    stringResource(R.string.voice_anthropic_key),
                    stringResource(if (hasClaude) R.string.voice_key_saved_summary else R.string.voice_anthropic_summary),
                    enabled = cleanup,
                    leading = { RowIcon(Icons.Rounded.Key, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    trailing = { KeyBadge(hasClaude) },
                    onClick = { editing = Secrets.ANTHROPIC },
                )
            },
            {
                SettingRow(
                    stringResource(R.string.voice_openai_key),
                    stringResource(if (hasOpenAI) R.string.voice_key_saved_summary else R.string.voice_openai_summary),
                    enabled = cleanup,
                    leading = { RowIcon(Icons.Rounded.Key, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    trailing = { if (hasOpenAI) KeyBadge(true) },
                    onClick = { editing = Secrets.OPENAI },
                )
            },
            {
                ModelDropdownRow(model, enabled = cleanup, keysVersion = keysVersion) { id ->
                    model = id
                    prefs.edit { putString(Prefs.CLEANUP_MODEL, id) }
                }
            },
        ))
        group(R.string.send_finder_group, listOf<@Composable () -> Unit>(
            {
                val running = CleanupModels.resolve(context)?.model
                CheckRow(
                    stringResource(R.string.send_check),
                    if (running == null) stringResource(R.string.send_finder_needs_key) else stringResource(R.string.send_check_summary, CleanupModels.label(context, running)),
                    sendCheck && running != null, enabled = running != null,
                ) { setSendCheck(!sendCheck) }
            },
            {
                // the cleanup's own model does it: Haiku or Luna, whichever key there is
                val running = CleanupModels.resolve(context)?.model
                CheckRow(
                    stringResource(R.string.send_finder),
                    if (running == null) stringResource(R.string.send_finder_needs_key) else stringResource(R.string.send_finder_summary, CleanupModels.label(context, running)),
                    sendFinder && running != null, enabled = running != null,
                ) { setSendFinder(!sendFinder) }
            },
        ))
        problem?.let { why ->
            group(R.string.cleanup_problem_group, listOf(
                {
                    SettingRow(
                        stringResource(R.string.cleanup_problem_title), why + "\n" + stringResource(R.string.cleanup_problem_see_log),
                        leading = { RowIcon(Icons.Rounded.Lightbulb, cs.errorContainer, cs.onErrorContainer) },
                        trailing = { TextButton(onClick = { CleanupProblem.clear(context); problem = null }) { Text(stringResource(R.string.crash_clear)) } },
                        onClick = openLogs,
                    )
                },
            ))
        }
    }

    editing?.let { name ->
        KeyDialog(
            title = stringResource(if (name == Secrets.OPENAI) R.string.voice_openai_key else R.string.voice_anthropic_key),
            help = stringResource(if (name == Secrets.OPENAI) R.string.voice_openai_help else R.string.voice_anthropic_help),
            has = Secrets.has(context, name),
            onSave = { v ->
                if (!Secrets.put(context, name, v)) Toast.makeText(context, R.string.voice_key_failed, Toast.LENGTH_LONG).show()
                editing = null; keysVersion++
            },
            onRemove = { Secrets.put(context, name, null); editing = null; keysVersion++ },
            onDismiss = { editing = null },
        )
    }
}

/** The cleanup model, a dropdown of the two: Claude Haiku (Claude key) and GPT-6 Luna (OpenAI key). */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdownRow(current: String, enabled: Boolean, keysVersion: Int, onPick: (String) -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    @Suppress("UNUSED_EXPRESSION") keysVersion
    // what will really run: with only one key, that key's model, whatever was picked
    val running = CleanupModels.resolve(context)?.model ?: current
    androidx.compose.material3.ExposedDropdownMenuBox(expanded = open && enabled, onExpandedChange = { if (enabled) open = it }) {
        Box(Modifier.menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled)) {
            SettingRow(
                stringResource(R.string.voice_model), CleanupModels.label(context, running), enabled = enabled,
                leading = { RowIcon(Icons.Rounded.AutoAwesome, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                trailing = { androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            )
        }
        ExposedDropdownMenu(expanded = open && enabled, onDismissRequest = { open = false }) {
            for (id in CleanupModels.IDS) {
                val ok = CleanupModels.available(context, id)
                androidx.compose.material3.DropdownMenuItem(
                    enabled = ok,
                    text = {
                        Column {
                            Text(CleanupModels.label(context, id), style = MaterialTheme.typography.bodyLarge)
                            Text(if (ok) CleanupModels.detail(context, id) else stringResource(if (id == CleanupModels.LUNA) R.string.model_needs_openai else R.string.model_needs_claude),
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (id == running) Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary) },
                    onClick = { open = false; onPick(id) },
                )
            }
        }
    }
}

@Composable
private fun KeyBadge(saved: Boolean) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(8.dp), color = if (saved) cs.secondaryContainer else cs.errorContainer) {
        Text(
            stringResource(if (saved) R.string.voice_key_saved else R.string.voice_key_missing),
            style = MaterialTheme.typography.labelMedium,
            color = if (saved) cs.onSecondaryContainer else cs.onErrorContainer,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Paste a key: it's stored encrypted and never shown again; a saved one can be replaced or removed. */
@Composable
private fun KeyDialog(title: String, help: String, has: Boolean, onSave: (String) -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.Key, null) },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(help, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(if (has) R.string.voice_key_replace_hint else R.string.voice_key_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.voice_key_save)) }
        },
        dismissButton = {
            Row {
                if (has) TextButton(onClick = onRemove) { Text(stringResource(R.string.voice_key_remove), color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

@Composable
private fun ModelDialog(current: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.AutoAwesome, null) },
        title = { Text(stringResource(R.string.voice_model)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.voice_model_summary), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(stringResource(R.string.voice_key_save)) } },
        dismissButton = {
            Row {
                TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.voice_model_default)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/* ---------------------------------------------------------------- language */

@Composable
private fun LanguagePage(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = rememberPrefs()
    var m by remember { mutableStateOf(Prefs.millennial(context)) }
    val set = { v: Prefs.MillennialPlaces -> m = v; Prefs.setMillennial(context, v) }
    SettingsPage(stringResource(R.string.page_language), onBack) {
        // checks, not a choice: search boxes and chats each on their own; everywhere covers both (shown
        // checked and dimmed underneath it, keeping their own setting for when it's unchecked); nowhere clears all
        group(R.string.millennial_mode, listOf(
            { CheckRow(stringResource(R.string.millennial_search), stringResource(R.string.millennial_search_summary), m.search || m.everywhere, enabled = !m.everywhere) { set(m.copy(search = !m.search)) } },
            { CheckRow(stringResource(R.string.millennial_chats), stringResource(R.string.millennial_chats_summary), m.chats || m.everywhere, enabled = !m.everywhere) { set(m.copy(chats = !m.chats)) } },
            { CheckRow(stringResource(R.string.millennial_everywhere), stringResource(R.string.millennial_everywhere_summary), m.everywhere) { set(m.copy(everywhere = !m.everywhere)) } },
            { CheckRow(stringResource(R.string.millennial_off), stringResource(R.string.millennial_off_summary), m.nowhere) { if (!m.nowhere) set(Prefs.MillennialPlaces()) } },
        ))
        item { Footnote(stringResource(R.string.millennial_mode_summary)) }
    }
}

/* ---------------------------------------------------------------- dictionary */

@Composable
private fun DictionaryPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val dict = remember { com.ryderdavid.voicebar.voice.Dictionary.of(context) }
    var version by remember { mutableIntStateOf(0) }
    val entries = remember(version) { dict.all() }
    val (learn, setLearn) = boolPref(Prefs.LEARN, Prefs.DEFAULT_LEARN)
    val (keyterms, setKeyterms) = boolPref(Prefs.DICT_KEYTERMS, Prefs.DEFAULT_DICT_KEYTERMS)
    var adding by remember { mutableStateOf(false) }
    SettingsPage(stringResource(R.string.page_dictionary), onBack) {
        group(rows = listOf(
            { SwitchRow(stringResource(R.string.dict_learn), stringResource(R.string.dict_learn_summary), learn, onChange = setLearn) },
            { SwitchRow(stringResource(R.string.dict_keyterms), stringResource(R.string.dict_keyterms_summary), keyterms, onChange = setKeyterms) },
        ))
        group(R.string.dict_words, listOf<@Composable () -> Unit>({
            SettingRow(
                stringResource(R.string.dict_add), null,
                leading = { RowIcon(Icons.Rounded.Add, cs.primaryContainer, cs.onPrimaryContainer) },
                onClick = { adding = true },
            )
        }) + entries.map { e ->
            @Composable {
                val parts = mutableListOf<String>()
                if (e.heard.isNotEmpty()) parts += stringResource(R.string.dict_heard_as, e.heard.joinToString(", ") { "“$it”" })
                parts += stringResource(if (e.learned) R.string.dict_learned else R.string.dict_added)
                if (e.count > 1) parts += "${e.count}×"
                SettingRow(
                    e.term, parts.joinToString(" · "),
                    leading = { RowIcon(if (e.learned) Icons.Rounded.AutoAwesome else Icons.Rounded.Spellcheck, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    trailing = {
                        androidx.compose.material3.IconButton(onClick = { dict.remove(e.term); version++ }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.dict_remove), tint = cs.onSurfaceVariant)
                        }
                    },
                )
            }
        })
        item { Footnote(stringResource(R.string.dict_footnote)) }
    }
    if (adding) {
        var term by remember { mutableStateOf("") }
        var heard by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            icon = { Icon(Icons.Rounded.Spellcheck, null) },
            title = { Text(stringResource(R.string.dict_add)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(value = term, onValueChange = { term = it }, singleLine = true,
                        label = { Text(stringResource(R.string.dict_add_term)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = heard, onValueChange = { heard = it }, singleLine = true,
                        label = { Text(stringResource(R.string.dict_add_heard)) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = term.isNotBlank(), onClick = {
                    dict.add(term, heard.ifBlank { null }, vocabulary = true, learned = false)
                    adding = false; version++
                }) { Text(stringResource(R.string.voice_key_save)) }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/* ---------------------------------------------------------------- help */

@Composable
private fun HelpPage(onBack: () -> Unit, onSetup: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val (tipsOn, setTipsOn) = boolPref(Prefs.TIPS, Prefs.DEFAULT_TIPS)
    var tipsReset by remember { mutableStateOf(false) }
    var crash by remember { mutableStateOf(CrashLog.last(context)) }
    val entries = listOf(
        Triple(Icons.Rounded.GraphicEq, R.string.help_talk_title, R.string.help_talk),
        Triple(Icons.Rounded.TextFields, R.string.help_format_title, R.string.help_format),
        Triple(Icons.Rounded.Keyboard, R.string.help_type_title, R.string.help_type),
        Triple(Icons.Rounded.TouchApp, R.string.help_cursor_title, R.string.help_cursor),
        Triple(Icons.AutoMirrored.Rounded.Backspace, R.string.help_edit_title, R.string.help_edit),
        Triple(Icons.Rounded.Apps, R.string.help_menu_title, R.string.help_menu),
    )
    SettingsPage(stringResource(R.string.page_help), onBack) {
        group(rows = entries.map { (icon, title, body) ->
            @Composable {
                SettingRow(stringResource(title), stringResource(body), leading = { RowIcon(icon, cs.secondaryContainer, cs.onSecondaryContainer) })
            }
        })
        group(rows = listOf(
            { NavRow(stringResource(R.string.welcome_row), stringResource(R.string.welcome_row_summary), Icons.Rounded.PlayCircle, cs.tertiaryContainer, cs.onTertiaryContainer) { WelcomeActivity.open(context) } },
            { SwitchRow(stringResource(R.string.tips_show), stringResource(R.string.tips_show_summary), tipsOn, onChange = setTipsOn) },
            {
                SettingRow(
                    stringResource(R.string.tips_again), stringResource(if (tipsReset) R.string.tips_again_done else R.string.tips_again_summary),
                    leading = { RowIcon(Icons.Rounded.Lightbulb, cs.tertiaryContainer, cs.onTertiaryContainer) },
                    onClick = { Tips.reset(context); setTipsOn(true); tipsReset = true },
                )
            },
            { NavRow(stringResource(R.string.wiz_again), stringResource(R.string.wiz_again_summary), Icons.Rounded.Settings, cs.primaryContainer, cs.onPrimaryContainer, onSetup) },
        ))
        crash?.let { report ->
            group(R.string.crash_group, listOf(
                {
                    SettingRow(
                        stringResource(R.string.crash_title),
                        report.lineSequence().drop(1).firstOrNull()?.substringBefore(" · thread") + "\n" +
                            (report.lineSequence().drop(3).firstOrNull()?.take(120) ?: ""),
                        leading = { RowIcon(Icons.Rounded.Cancel, cs.errorContainer, cs.onErrorContainer) },
                        trailing = { TextButton(onClick = { CrashLog.clear(context); crash = null }) { Text(stringResource(R.string.crash_clear)) } },
                        onClick = {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                ?.setPrimaryClip(android.content.ClipData.newPlainText("Voicebar crash", report))
                            Toast.makeText(context, R.string.crash_copied, Toast.LENGTH_SHORT).show()
                        },
                    )
                },
            ))
            item { Footnote(stringResource(R.string.crash_footnote)) }
        }
    }
}

/* ---------------------------------------------------------------- logs */

/**
 * The real log: Voicebar's own events and Android's log for the app (logcat, this process), the
 * last five minutes of each, to read here and copy whole into a bug report.
 */
@Composable
private fun LogsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var report by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    // Android's log: warnings and errors unless asked for everything (the rest is window chatter)
    var everything by rememberSaveable { mutableStateOf(false) }
    val header = "Voicebar ${BuildConfigCompat.versionName(context)} · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MODEL}"
    androidx.compose.runtime.LaunchedEffect(version, everything) {
        report = null
        report = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            // the last crash too: the events and Android's log only cover the process running now
            AppLog.report(header, everything) + (CrashLog.last(context)?.let { "\n\n== Last crash ==\n$it" } ?: "")
        }
    }
    SettingsPage(stringResource(R.string.page_logs), onBack) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                FilledTonalButton(onClick = {
                    val text = report ?: return@FilledTonalButton
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("Voicebar log", text))
                    Toast.makeText(context, R.string.log_copied, Toast.LENGTH_SHORT).show()
                }, enabled = report != null) { Text(stringResource(R.string.log_copy)) }
                TextButton(onClick = { version++ }) { Text(stringResource(R.string.log_refresh)) }
                TextButton(onClick = { AppLog.clear(); version++ }) { Text(stringResource(R.string.crash_clear)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
                androidx.compose.material3.FilterChip(selected = !everything, onClick = { everything = false },
                    label = { Text(stringResource(R.string.log_level_warnings)) })
                androidx.compose.material3.FilterChip(selected = everything, onClick = { everything = true },
                    label = { Text(stringResource(R.string.log_level_everything)) })
            }
        }
        item {
            Surface(shape = RoundedCornerShape(16.dp), color = cs.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        report ?: stringResource(R.string.log_loading),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = cs.onSurface,
                        modifier = Modifier.padding(12.dp).horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                    )
                }
            }
        }
        item { Footnote(stringResource(R.string.log_footnote)) }
    }
}

/* ---------------------------------------------------------------- open-source notices */

/** What Voicebar is built with that others made: each one's maker and license; tap one for the license's text. */
@Composable
private fun NoticesPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Notices.Notice?>(null) }
    SettingsPage(stringResource(R.string.page_notices), onBack) {
        group(rows = Notices.all.map { n ->
            @Composable {
                SettingRow(n.name, "${n.role}\n${n.copyright} · ${n.license}", onClick = { shown = n })
            }
        })
        item { Footnote(stringResource(R.string.notices_footnote)) }
    }
    shown?.let { n ->
        val text = remember(n) {
            n.licenseAsset?.let { a -> runCatching { context.assets.open("licenses/$a").bufferedReader().readText() }.getOrNull() }
        }
        AlertDialog(
            onDismissRequest = { shown = null },
            title = { Text(n.name) },
            text = {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) {
                    Text("${n.copyright}\n${n.license}\n${n.source}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    if (text != null) Text(text, style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace))
                    else n.licenseUrl?.let { Text(stringResource(R.string.notices_license_at, it), style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    shown = null
                    runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(n.licenseUrl ?: n.source))) }
                }) { Text(stringResource(R.string.notices_open)) }
            },
            dismissButton = { TextButton(onClick = { shown = null }) { Text(stringResource(R.string.notices_close)) } },
        )
    }
}

/* ---------------------------------------------------------------- try */

@Composable
private fun TryPage(onBack: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    SettingsPage(stringResource(R.string.page_try), onBack) {
        item {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.try_hint)) },
                minLines = 5,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.try_tip), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)
        }
    }
}

/** The app's version name, for the footer. */
private object BuildConfigCompat {
    fun versionName(context: android.content.Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: ""
}
