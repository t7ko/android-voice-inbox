package t7helpers.voiceinbox.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.CaptureState
import t7helpers.voiceinbox.CaptureStatus
import t7helpers.voiceinbox.ModelState
import t7helpers.voiceinbox.R
import t7helpers.voiceinbox.SpeechModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    target: Target,
    language: String,
    onLanguage: (String) -> Unit,
    onRecord: () -> Unit,
    onPickTarget: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by Capture.state.collectAsState()
    val models by SpeechModel.states.collectAsState()
    val model = models[language] ?: ModelState.Missing
    val records by Capture.records.collectAsState()
    val unsaved = records.count { it.status == CaptureStatus.READY || it.status == CaptureStatus.FAILED }
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Inbox") },
                actions = {
                    IconButton(onClick = onOpenHistory) { Icon(Icons.AutoMirrored.Filled.List, contentDescription = "History") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (model != ModelState.Installed) {
                val info = SpeechModel.info(language)
                ModelCard(info, model, onInstall = { SpeechModel.install(context, info) })
            }
            TargetCard(target, onPickTarget)
            when (val s = state) {
                is CaptureState.Idle -> Idle(
                    s,
                    canRecord = model == ModelState.Installed,
                    language,
                    unsaved,
                    onLanguage,
                    onRecord,
                    onOpenAppInfo,
                    onOpenHistory,
                )
                is CaptureState.Recording -> Recording(s)
                is CaptureState.Editing -> Editing(s, canSave = target.name != null, onRecord)
                is CaptureState.Saving -> Busy("Saving…")
                is CaptureState.Saved -> Saved(s, onRecord)
            }
        }
    }
}

@Composable
private fun Idle(
    state: CaptureState.Idle,
    canRecord: Boolean,
    language: String,
    unsaved: Int,
    onLanguage: (String) -> Unit,
    onRecord: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Box(Modifier.padding(top = 32.dp)) {
        Button(
            onClick = onRecord,
            enabled = canRecord,
            shape = CircleShape,
            modifier = Modifier.size(160.dp),
        ) {
            Icon(painterResource(R.drawable.ic_mic), contentDescription = "Record", modifier = Modifier.size(72.dp))
        }
    }
    Text(if (canRecord) "Tap to record" else "Download the speech model first", style = MaterialTheme.typography.titleMedium)
    LanguageChips(language, onLanguage)
    state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        if (state.openAppInfo) OutlinedButton(onClick = onOpenAppInfo) { Text("Open App info") }
    }
    if (unsaved > 0) {
        OutlinedButton(onClick = onOpenHistory) {
            Text(if (unsaved == 1) "1 capture not saved" else "$unsaved captures not saved")
        }
    }
}

@Composable
private fun Recording(state: CaptureState.Recording) {
    // A screen that times out would end the recording (no background recording).
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .size(20.dp)
                .background(if (state.stopping) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error, CircleShape),
        )
        Text(formatDuration(state.elapsedMs), style = MaterialTheme.typography.displayMedium)
    }
    LinearProgressIndicator(progress = { if (state.stopping) 0f else state.level }, modifier = Modifier.fillMaxWidth())
    Text(
        when {
            state.stopping -> "Recognizing…"
            !state.recognizerReady -> "Recording. Loading the speech model…"
            state.text.isEmpty() -> "Listening…"
            else -> "Speech detected"
        },
        style = MaterialTheme.typography.titleMedium,
    )
    state.autoStopMs?.let {
        if (!state.stopping) {
            Text(
                "Stops after ${formatSeconds(it)} of silence",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Card(Modifier.fillMaxWidth().heightIn(min = 96.dp)) {
        Text(state.text, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge)
    }
    if (state.stopping) {
        CircularProgressIndicator(Modifier.size(96.dp))
    } else {
        Button(
            onClick = Capture::stop,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.size(120.dp).semantics { contentDescription = "Stop" },
        ) {
            Box(Modifier.size(36.dp).background(MaterialTheme.colorScheme.onError, RoundedCornerShape(4.dp)))
        }
        Text("Stop", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Editing(state: CaptureState.Editing, canSave: Boolean, onRecord: () -> Unit) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.text,
        onValueChange = Capture::edit,
        label = { Text("Task") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (state.needsDecision) {
        Button(onClick = { Capture.save(context, force = true) }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Save anyway", style = MaterialTheme.typography.titleMedium)
        }
        OutlinedButton(onClick = { Capture.markSaved(context) }, modifier = Modifier.fillMaxWidth()) { Text("It is in the file: mark as saved") }
    } else {
        Button(onClick = { Capture.save(context) }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
            Text("Save", style = MaterialTheme.typography.titleMedium)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRecord) { Text("Record again") }
        TextButton(onClick = Capture::close) { Text("Later") }
        TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
    }
    Text(
        "Record again and Later keep this capture in History.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this capture?") },
            text = { Text("It was not saved to the file. Its text and audio will be gone.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    Capture.delete(state.id)
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator(Modifier.padding(top = 32.dp).size(64.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Saved(state: CaptureState.Saved, onRecord: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    LaunchedEffect(state.id) { haptic.performHapticFeedback(HapticFeedbackType.Confirm) }
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp).size(96.dp),
    )
    Text("Saved to ${state.fileName}", style = MaterialTheme.typography.titleLarge)
    if (state.closeApp) Text("Going back to the home screen…", style = MaterialTheme.typography.bodyMedium)
    Card(Modifier.fillMaxWidth()) {
        Text(state.line, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace)
    }
    Button(onClick = onRecord, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text("Record another", style = MaterialTheme.typography.titleMedium)
    }
    TextButton(onClick = Capture::close) { Text("Done") }
}
