package t7helpers.voiceinbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.ModelState
import t7helpers.voiceinbox.Settings
import t7helpers.voiceinbox.SpeechModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    target: Target,
    language: String,
    onLanguage: (String) -> Unit,
    onPickTarget: () -> Unit,
    onBack: () -> Unit,
) {
    val models by SpeechModel.states.collectAsState()
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
            Section("Language") {
                LanguageChips(language, onLanguage)
                Text(
                    "Each model knows one language: words of the other come out as similar-sounding words.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            for (info in SpeechModel.ALL) {
                ModelCard(info, models[info.language] ?: ModelState.Missing, onInstall = { SpeechModel.install(context, info) })
            }
            RecordingSettings()
            AutoSave()
            AudioRetention()
            Text(
                "Audio and text stay on the phone. The network is used only to download speech models.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

/** The whole row toggles, not only the switch. */
@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** Chips for a setting with a few choices. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choices(choices: List<Int>, selected: Int, label: (Int) -> String, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (choice in choices) {
            FilterChip(selected = choice == selected, onClick = { onSelect(choice) }, label = { Text(label(choice)) })
        }
    }
}

/** Stopping on silence and the longest recording. */
@Composable
private fun RecordingSettings() {
    val context = LocalContext.current
    var autoStop by remember { mutableStateOf(Settings.autoStop(context)) }
    var silenceMs by remember { mutableIntStateOf(Settings.silenceMs(context)) }
    var maxSeconds by remember { mutableIntStateOf(Settings.maxSeconds(context)) }
    Section("Recording") {
        SwitchRow("Stop when I stop speaking", autoStop) {
            autoStop = it
            Settings.setAutoStop(context, it)
        }
        if (autoStop) {
            Text("After this much silence:", style = MaterialTheme.typography.bodyMedium)
            Choices(Settings.SILENCE_CHOICES, silenceMs, { formatSeconds(it.toLong()) }) {
                silenceMs = it
                Settings.setSilenceMs(context, it)
            }
            Text(
                "Pauses between words do not count. With nothing said, it stops after 10 s.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text("Longest recording:", style = MaterialTheme.typography.bodyMedium)
        Choices(Settings.MAX_SECONDS_CHOICES, maxSeconds, { if (it < 60) "$it s" else "${it / 60} min" }) {
            maxSeconds = it
            Settings.setMaxSeconds(context, it)
        }
    }
}

@Composable
private fun AutoSave() {
    val context = LocalContext.current
    var autoSave by remember { mutableStateOf(Settings.autoSave(context)) }
    Section("Saving") {
        SwitchRow("Save without tapping Save", autoSave) {
            autoSave = it
            Settings.setAutoSave(context, it)
        }
        Text(
            "Recognized text goes into the file right after recording. Captures with a problem, " +
                "or with nothing recognized, still wait for you.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** How long saved captures keep their audio. */
@Composable
private fun AudioRetention() {
    val context = LocalContext.current
    var days by remember { mutableIntStateOf(Settings.audioRetentionDays(context)) }
    Section("Audio of saved captures") {
        Choices(Settings.AUDIO_RETENTION_CHOICES, days, {
            when (it) {
                Settings.KEEP_AUDIO -> "Keep"
                1 -> "1 day"
                else -> "$it days"
            }
        }) {
            days = it
            Settings.setAudioRetentionDays(context, it)
            Capture.deleteOldAudio(context)
        }
        Text(
            "Deleted that long after saving. Captures not saved yet always keep their audio.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
