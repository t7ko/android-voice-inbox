package t7helpers.voiceinbox.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.ModelInfo
import t7helpers.voiceinbox.ModelState
import t7helpers.voiceinbox.SpeechModel
import java.util.Locale

/** Where captures go, with a button to pick another file. */
@Composable
fun TargetCard(target: Target, onPickTarget: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Saving to", style = MaterialTheme.typography.labelMedium)
                Text(target.name ?: "No file chosen", style = MaterialTheme.typography.titleMedium)
                val problem = target.problem ?: if (target.name != null && !target.accessible) {
                    "The app lost access to this file. Choose it again."
                } else {
                    null
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (target.name == null) {
                Button(onClick = onPickTarget) { Text("Choose") }
            } else {
                OutlinedButton(onClick = onPickTarget) { Text("Change") }
            }
        }
    }
}

/** A speech model's state, with Download while it is missing. */
@Composable
fun ModelCard(info: ModelInfo, state: ModelState, onInstall: () -> Unit) {
    val colors = if (state == ModelState.Installed) CardDefaults.cardColors() else {
        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    }
    Card(Modifier.fillMaxWidth(), colors = colors) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Speech model: ${info.languageName}", style = MaterialTheme.typography.titleMedium)
            when (state) {
                ModelState.Missing -> {
                    Text(
                        "Recognition needs a ${megabytes(info.zipSize)} MB model, downloaded once from " +
                            "alphacephei.com. After that the app works offline.",
                    )
                    Button(onClick = onInstall) { Text("Download") }
                }
                is ModelState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.done.toFloat() / state.total },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Downloading: ${megabytes(state.done)} of ${megabytes(state.total)} MB")
                }
                ModelState.Unpacking -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Unpacking…")
                }
                ModelState.Installed -> Text("${info.name}, installed. Works offline.")
                is ModelState.Failed -> {
                    Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onInstall) { Text("Try again") }
                }
            }
        }
    }
}

private fun megabytes(bytes: Long) = (bytes + 500_000) / 1_000_000

/** 1500 ms as `1.5 s`, 2000 as `2 s`. */
fun formatSeconds(ms: Long): String =
    if (ms % 1000 == 0L) "${ms / 1000} s" else String.format(Locale.ROOT, "%.1f s", ms / 1000.0)

/** 75 seconds as `1:15`. */
fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** Chips to pick the language of new captures. */
@Composable
fun LanguageChips(language: String, onLanguage: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (info in SpeechModel.ALL) {
            FilterChip(
                selected = info.language == language,
                onClick = { onLanguage(info.language) },
                label = { Text(info.languageName) },
            )
        }
    }
}
