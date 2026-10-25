package t7helpers.voiceinbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.Settings
import t7helpers.voiceinbox.SpeechModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(target: Target, onPickTarget: () -> Unit, onBack: () -> Unit) {
    val model by SpeechModel.state.collectAsState()
    val context = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TargetCard(target, onPickTarget)
            Text(
                "Each capture is added at the end of this file as one task line: - [ ] text",
                style = MaterialTheme.typography.bodyMedium,
            )
            ModelCard(model, onInstall = { SpeechModel.install(context) })
            AudioRetention()
            Text(
                "Audio and text stay on the phone. The network is used only to download the speech model.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** How long saved captures keep their audio. */
@Composable
private fun AudioRetention() {
    val context = LocalContext.current
    var days by remember { mutableIntStateOf(Settings.audioRetentionDays(context)) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Audio of saved captures", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (choice in Settings.AUDIO_RETENTION_CHOICES) {
                    FilterChip(
                        selected = days == choice,
                        onClick = {
                            days = choice
                            Settings.setAudioRetentionDays(context, choice)
                            Capture.deleteOldAudio(context)
                        },
                        label = {
                            Text(
                                when (choice) {
                                    Settings.KEEP_AUDIO -> "Keep"
                                    1 -> "1 day"
                                    else -> "$choice days"
                                },
                            )
                        },
                    )
                }
            }
            Text(
                "Deleted that long after saving. Captures not saved yet always keep their audio.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
