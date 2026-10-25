package t7helpers.voiceinbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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
            Text(
                "Audio and text stay on the phone. The network is used only to download the speech model.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
