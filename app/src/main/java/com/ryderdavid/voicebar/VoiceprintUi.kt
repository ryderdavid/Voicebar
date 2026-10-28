package com.ryderdavid.voicebar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.ryderdavid.voicebar.voice.Enrollment
import com.ryderdavid.voicebar.voice.MicRecorder
import com.ryderdavid.voicebar.voice.ModelPack
import com.ryderdavid.voicebar.voice.SileroSpeechTest
import com.ryderdavid.voicebar.voice.VoiceGate
import com.ryderdavid.voicebar.voice.Voiceprint
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** A model download as Compose state. */
@Composable
fun rememberPack(pack: ModelPack): ModelPack.State {
    var s by remember { mutableStateOf(pack.state) }
    val context = LocalContext.current
    DisposableEffect(pack) {
        val stop = pack.observe { s = it }
        if (s == ModelPack.State.Idle && pack.ready(context)) s = ModelPack.State.Ready
        onDispose { stop() }
    }
    return s
}

@Composable
fun strengthName(n: Int) = stringResource(when (n) {
    1 -> R.string.vp_strength_1; 2 -> R.string.vp_strength_2; 4 -> R.string.vp_strength_4; 5 -> R.string.vp_strength_5
    else -> R.string.vp_strength_3
})

/** The "Only my voice" group on the Voice page: the switch, the voiceprint, and its strength. */
@Composable
fun VoiceprintRows(openPage: () -> Unit): List<@Composable () -> Unit> {
    val context = LocalContext.current
    val prefs = remember { Prefs.get(context) }
    var on by remember { mutableStateOf(Voiceprint.enabled(context)) }
    var strength by remember { mutableStateOf(Voiceprint.strength(context)) }
    val has = remember(on) { Voiceprint.has(context) }
    val cs = MaterialTheme.colorScheme
    val names = Voiceprint.STRENGTH_RANGE.map { strengthName(it) }
    var kids by remember { mutableStateOf(com.ryderdavid.voicebar.voice.ChildVoices.enabled(context)) }
    var kidStrength by remember { mutableStateOf(com.ryderdavid.voicebar.voice.ChildVoices.strength(context)) }
    return listOf(
        {
            SwitchRow(stringResource(R.string.kids_switch), stringResource(R.string.kids_switch_summary), kids) { v ->
                kids = v; prefs.edit { putBoolean(Prefs.CHILD_FILTER, v) }
            }
        },
        {
            SliderRow(stringResource(R.string.kids_strength), stringResource(R.string.kids_strength_summary), kidStrength,
                com.ryderdavid.voicebar.voice.ChildFilter.STRENGTH_RANGE, { names[it - 1] }, enabled = kids, stepped = true) { n ->
                kidStrength = n; prefs.edit { putInt(Prefs.CHILD_FILTER_STRENGTH, n) }
            }
        },
        {
            SwitchRow(stringResource(R.string.vp_switch),
                stringResource(if (on && !has) R.string.vp_first_voice else R.string.vp_switch_summary), on) { v ->
                on = v; prefs.edit { putBoolean(Prefs.VOICEPRINT, v) }
                // no voiceprint needed: without one, the first voice at the phone is the one kept
                if (v) Voiceprint.pack.download(context)
            }
        },
        {
            NavRow(stringResource(R.string.vp_print), stringResource(if (has) R.string.vp_print_saved else R.string.vp_print_none),
                Icons.Rounded.Fingerprint, cs.secondaryContainer, cs.onSecondaryContainer, openPage)
        },
        {
            SliderRow(stringResource(R.string.vp_strength), stringResource(R.string.vp_strength_summary), strength, Voiceprint.STRENGTH_RANGE,
                { names[it - Voiceprint.STRENGTH_RANGE.first] }, enabled = on, stepped = true) { n -> strength = n; prefs.edit { putInt(Prefs.VOICEPRINT_STRENGTH, n) } }
        },
    )
}

/* ---------------------------------------------------------------- the page */

sealed class Rec {
    data object Idle : Rec()
    data class Listening(val seconds: Float) : Rec()
    data object Working : Rec()
    data class Done(val noisy: Boolean) : Rec()
    data object Short : Rec()
    data class Problem(val message: Int) : Rec()
}

/** Checking the voiceprint live: the last similarity (null: too little speech), verdict and threshold. */
data class Check(val score: Float? = null, val me: Boolean? = null, val threshold: Float = 0.46f)

data class VoiceprintState(
    val model: ModelPack.State = ModelPack.State.Idle,
    val hasPrint: Boolean = false,
    val rec: Rec = Rec.Idle,
    val check: Check? = null,
)

interface VoiceprintActions {
    fun download()
    fun record()
    fun stopRecording()
    fun check()
    fun stopCheck()
    fun forget()
}

@Composable
fun VoiceprintPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val model = rememberPack(Voiceprint.pack)
    var has by remember { mutableStateOf(Voiceprint.has(context)) }
    var rec by remember { mutableStateOf<Rec>(Rec.Idle) }
    var check by remember { mutableStateOf<Check?>(null) }
    val host = remember { Host(context.applicationContext) }
    DisposableEffect(Unit) { onDispose { host.stopAll() } }
    val micOk = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val actions = remember {
        object : VoiceprintActions {
            override fun download() = Voiceprint.pack.download(context)
            override fun record() {
                if (!micOk) { rec = Rec.Problem(R.string.vp_need_mic); return }
                host.stopCheck(); check = null
                host.record(onProgress = { rec = Rec.Listening(it) }, onWorking = { rec = Rec.Working }) { result ->
                    rec = result; has = Voiceprint.has(context)
                }
            }
            override fun stopRecording() = host.stopRecording()
            override fun check() {
                if (!micOk) { rec = Rec.Problem(R.string.vp_need_mic); return }
                val th = Voiceprint.threshold(Voiceprint.strength(context))
                check = Check(threshold = th)
                host.check(th) { s, me -> check = Check(s, if (s == null) null else me, th) }
            }
            override fun stopCheck() { host.stopCheck(); check = null }
            override fun forget() { host.stopCheck(); check = null; Voiceprint.forget(context); has = false; rec = Rec.Idle }
        }
    }
    VoiceprintScreen(VoiceprintState(model, has, rec, check), actions, onBack)
}

/** Microphone work for the page, off the main thread. */
private class Host(private val context: android.content.Context) {
    private val main = Handler(Looper.getMainLooper())
    private var work: ExecutorService? = null
    private var mic: MicRecorder? = null
    @Volatile private var finishing: (() -> Unit)? = null

    private fun worker() = work ?: Executors.newSingleThreadExecutor().also { work = it }

    fun record(onProgress: (Float) -> Unit, onWorking: () -> Unit, onDone: (Rec) -> Unit) {
        stopAll()
        val w = worker()
        var speech: SileroSpeechTest? = null
        var en: Enrollment? = null
        w.execute {
            speech = SileroSpeechTest(context)
            en = Enrollment(speech!!, embed = { Voiceprint.Engine.embed(context, it) })
        }
        var done = false
        val finish = {
            w.execute {
                if (done) return@execute
                done = true
                mic?.stop(); mic = null
                main.post(onWorking)
                val e = en
                val result = runCatching { if (e == null || e.seconds < 8) null else e.result() }.getOrNull()
                speech?.close()
                Voiceprint.Engine.done()
                val out = if (result == null) Rec.Short else {
                    Voiceprint.save(context, result.first)
                    Rec.Done(noisy = result.second < 0.6f)
                }
                main.post { onDone(out) }
            }
        }
        finishing = finish
        mic = MicRecorder().also { m ->
            val ok = m.start(onChunk = { pcm ->
                w.execute {
                    val e = en ?: return@execute
                    if (done) return@execute
                    e.accept(com.ryderdavid.voicebar.voice.pcmToFloats(pcm))
                    val s = e.seconds
                    main.post { if (!done) onProgress(s) }
                    if (e.enough) finish()
                }
            }, onLevel = {})
            if (!ok) { main.post { onDone(Rec.Problem(R.string.vp_need_mic)) }; done = true }
        }
        onProgress(0f)
    }

    fun stopRecording() { finishing?.invoke(); finishing = null }

    private var checkMic: MicRecorder? = null
    private var checkVad: SileroSpeechTest? = null

    fun check(threshold: Float, onVerdict: (Float?, Boolean) -> Unit) {
        stopCheck()
        val print = Voiceprint.load(context) ?: return
        val w = worker()
        var gate: VoiceGate? = null
        w.execute {
            runCatching {
                val v = SileroSpeechTest(context).also { checkVad = it }
                gate = VoiceGate(v, { Voiceprint.Engine.embed(context, it) }, print, threshold, emit = {},
                    onVerdict = { s, me -> main.post { onVerdict(s, me) } })
            }
        }
        checkMic = MicRecorder().also { it.start(onChunk = { pcm -> w.execute { gate?.accept(pcm) } }, onLevel = {}) }
    }

    fun stopCheck() {
        checkMic?.stop(); checkMic = null
        work?.execute { checkVad?.close(); checkVad = null; Voiceprint.Engine.done() }
    }

    fun stopAll() {
        stopCheck()
        if (mic != null) { mic?.stop(); mic = null }
        finishing = null
    }
}

@Composable
fun VoiceprintScreen(state: VoiceprintState, actions: VoiceprintActions, onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ready = state.model == ModelPack.State.Ready
    val mb = (Voiceprint.pack.total / 1_000_000).toInt()
    SettingsPage(stringResource(R.string.vp_page), onBack) {
        group(rows = listOf({
            Column {
                SettingRow(
                    stringResource(R.string.vp_model),
                    when (val m = state.model) {
                        ModelPack.State.Ready -> stringResource(R.string.vp_model_ready)
                        is ModelPack.State.Downloading -> stringResource(R.string.engine_parakeet_downloading, (m.done / 1_000_000).toInt(), (m.total / 1_000_000).toInt())
                        ModelPack.State.Checking -> stringResource(R.string.engine_parakeet_checking)
                        is ModelPack.State.Failed -> m.why
                        ModelPack.State.Idle -> stringResource(R.string.vp_model_summary, Voiceprint.MODEL_NAME, mb)
                    },
                    leading = { RowIcon(Icons.Rounded.GraphicEq, cs.primaryContainer, cs.onPrimaryContainer) },
                    trailing = {
                        when (state.model) {
                            ModelPack.State.Ready -> Icon(Icons.Rounded.CheckCircle, null, tint = cs.primary)
                            is ModelPack.State.Downloading, ModelPack.State.Checking -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                            else -> FilledTonalButton(onClick = actions::download) {
                                Text(stringResource(if (state.model is ModelPack.State.Failed) R.string.engine_retry else R.string.engine_download))
                            }
                        }
                    },
                )
                (state.model as? ModelPack.State.Downloading)?.let { d ->
                    LinearProgressIndicator(progress = { (d.done.toFloat() / d.total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(start = 72.dp, end = 16.dp))
                    Spacer(Modifier.height(14.dp))
                }
            }
        }))
        group(rows = listOf({ RecordCard(state, ready, actions) }))
        if (state.hasPrint) group(rows = listOf(
            { CheckCard(state.check, ready, actions) },
            {
                SettingRow(stringResource(R.string.vp_forget), null,
                    leading = { RowIcon(Icons.Rounded.DeleteSweep, cs.surfaceContainerHighest, cs.onSurfaceVariant) },
                    onClick = actions::forget)
            },
        ))
        item { Footnote(stringResource(R.string.vp_footnote)) }
    }
}

@Composable
private fun RecordCard(state: VoiceprintState, ready: Boolean, actions: VoiceprintActions) {
    val cs = MaterialTheme.colorScheme
    val rec = state.rec
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.Fingerprint, cs.secondaryContainer, cs.onSecondaryContainer)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.vp_record_title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vp_record_help), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        val listening = rec is Rec.Listening
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Voiceprint.SENTENCES.forEach {
                Text("“$it”", style = MaterialTheme.typography.bodyLarge, color = if (listening) cs.onSurface else cs.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(18.dp))
        when (rec) {
            is Rec.Listening -> {
                LinearProgressIndicator(progress = { (rec.seconds / Voiceprint.ENROLL_SECONDS).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Status(stringResource(R.string.vp_record_progress, rec.seconds.toInt(), Voiceprint.ENROLL_SECONDS), cs.primary)
            }
            Rec.Working -> { LinearProgressIndicator(Modifier.fillMaxWidth()); Spacer(Modifier.height(8.dp)); Status(stringResource(R.string.vp_record_working), cs.primary) }
            is Rec.Done -> Status(stringResource(if (rec.noisy) R.string.vp_record_noisy else R.string.vp_record_done), if (rec.noisy) cs.tertiary else cs.primary)
            Rec.Short -> Status(stringResource(R.string.vp_record_short), cs.error)
            is Rec.Problem -> Status(stringResource(rec.message), cs.error)
            Rec.Idle -> if (!ready) Status(stringResource(R.string.vp_need_model), cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        if (listening) FilledTonalButton(onClick = actions::stopRecording) {
            Icon(Icons.Rounded.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.vp_record_stop))
        } else Button(onClick = actions::record, enabled = ready && rec != Rec.Working) {
            Icon(Icons.Rounded.Mic, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(stringResource(if (state.hasPrint) R.string.vp_record_again else R.string.vp_record_start))
        }
    }
}

@Composable
private fun Status(text: String, color: androidx.compose.ui.graphics.Color) =
    Text(text, style = MaterialTheme.typography.bodyMedium, color = color)

@Composable
private fun CheckCard(check: Check?, ready: Boolean, actions: VoiceprintActions) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowIcon(Icons.Rounded.Hearing, cs.tertiaryContainer, cs.onTertiaryContainer)
            Spacer(Modifier.width(16.dp))
            Text(stringResource(R.string.vp_check_title), style = MaterialTheme.typography.titleMedium, color = cs.onSurface)
        }
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.vp_check_help), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
        if (check != null) {
            Spacer(Modifier.height(18.dp))
            val good = check.me == true
            val fill = if (check.me == null) cs.outlineVariant else if (good) cs.primary else cs.error
            val track = cs.surfaceContainerHighest
            val tick = cs.onSurface
            // similarity 0.0 .. 1.0 across the bar; the line is where it starts counting as you
            Canvas(Modifier.fillMaxWidth().height(14.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(track, cornerRadius = r)
                val f = ((check.score ?: 0f).coerceIn(0f, 1f))
                if (f > 0f) drawRoundRect(fill, size = Size(size.width * f, size.height), cornerRadius = r)
                val x = size.width * check.threshold
                drawLine(tick, Offset(x, -4f), Offset(x, size.height + 4f), strokeWidth = 3.dp.toPx())
            }
            Spacer(Modifier.height(8.dp))
            Status(
                when (check.me) { null -> stringResource(R.string.vp_check_listening); true -> stringResource(R.string.vp_check_me); false -> stringResource(R.string.vp_check_other) },
                if (check.me == null) cs.onSurfaceVariant else if (good) cs.primary else cs.error,
            )
        }
        Spacer(Modifier.height(12.dp))
        if (check != null) TextButton(onClick = actions::stopCheck) { Text(stringResource(R.string.vp_record_stop)) }
        else FilledTonalButton(onClick = actions::check, enabled = ready) { Text(stringResource(R.string.vp_check_start)) }
    }
}
