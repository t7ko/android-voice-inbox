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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.CaptureState
import t7helpers.voiceinbox.ModelState
import t7helpers.voiceinbox.R
import t7helpers.voiceinbox.SpeechModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    target: Target,
    onRecord: () -> Unit,
    onPickTarget: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by Capture.state.collectAsState()
    val model by SpeechModel.state.collectAsState()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Inbox") },
                actions = {
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
            if (model != ModelState.Installed) ModelCard(model, onInstall = { SpeechModel.install(context) })
            TargetCard(target, onPickTarget)
            when (val s = state) {
                is CaptureState.Idle -> Idle(s, canRecord = model == ModelState.Installed, onRecord, onOpenAppInfo)
                is CaptureState.Recording -> Recording(s)
                is CaptureState.Editing -> Editing(s, canSave = target.name != null, onRecord)
                is CaptureState.Saving -> Busy("Saving…")
                is CaptureState.Saved -> Saved(s, onRecord)
            }
        }
    }
}

@Composable
private fun Idle(state: CaptureState.Idle, canRecord: Boolean, onRecord: () -> Unit, onOpenAppInfo: () -> Unit) {
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
    state.message?.let {
        Text(it, color = MaterialTheme.colorScheme.error)
        if (state.openAppInfo) OutlinedButton(onClick = onOpenAppInfo) { Text("Open App info") }
    }
}

@Composable
private fun Recording(state: CaptureState.Recording) {
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
    OutlinedTextField(
        value = state.text,
        onValueChange = Capture::edit,
        label = { Text("Task") },
        minLines = 3,
        modifier = Modifier.fillMaxWidth(),
    )
    state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Button(onClick = { Capture.save(context) }, enabled = canSave, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text("Save", style = MaterialTheme.typography.titleMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { Capture.discard(); onRecord() }) { Text("Record again") }
        TextButton(onClick = Capture::discard) { Text("Discard") }
    }
}

@Composable
private fun Busy(text: String) {
    CircularProgressIndicator(Modifier.padding(top = 32.dp).size(64.dp))
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun Saved(state: CaptureState.Saved, onRecord: () -> Unit) {
    Icon(
        Icons.Filled.CheckCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp).size(96.dp),
    )
    Text("Saved to ${state.fileName}", style = MaterialTheme.typography.titleLarge)
    Card(Modifier.fillMaxWidth()) {
        Text(state.line, Modifier.padding(16.dp), fontFamily = FontFamily.Monospace)
    }
    Button(onClick = { Capture.discard(); onRecord() }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
        Text("Record another", style = MaterialTheme.typography.titleMedium)
    }
    TextButton(onClick = Capture::discard) { Text("Done") }
}
