package t7helpers.voiceinbox.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.CaptureRecord
import t7helpers.voiceinbox.CaptureStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** All captures, newest first, with what can be done with each. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(onOpen: (String) -> Unit, onBack: () -> Unit) {
    val records by Capture.records.collectAsState()
    val playing by AudioPlayer.playing.collectAsState()
    val context = LocalContext.current
    var deleting by remember { mutableStateOf<CaptureRecord?>(null) }
    DisposableEffect(Unit) { onDispose { AudioPlayer.stop() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("History") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("No captures yet.") }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(records, key = { it.id }) { record ->
                    HistoryItem(
                        record,
                        isPlaying = playing == record.id,
                        onOpen = { onOpen(record.id) },
                        onCopy = { copy(context, record.text) },
                        onPlay = {
                            if (playing == record.id) {
                                AudioPlayer.stop()
                            } else if (!AudioPlayer.play(record.id, Capture.audioFile(record.id))) {
                                Toast.makeText(context, "Cannot play this audio.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onDelete = { deleting = record },
                    )
                }
            }
        }
    }

    deleting?.let { record ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this capture?") },
            text = {
                Text(
                    if (record.isSaved) {
                        "Its text and audio are deleted from the app. The task stays in ${record.savedTo}."
                    } else {
                        "It was not saved to the file. Its text and audio will be gone."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (playing == record.id) AudioPlayer.stop()
                    Capture.delete(record.id)
                    deleting = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HistoryItem(
    record: CaptureRecord,
    isPlaying: Boolean,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
) {
    val busy = record.status == CaptureStatus.RECORDING || record.status == CaptureStatus.TRANSCRIBING ||
        record.status == CaptureStatus.SAVING
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${TIME_FORMAT.format(Instant.ofEpochMilli(record.createdAt))} · ${formatDuration(record.durationMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f),
                )
                StatusLabel(record.status)
            }
            Text(record.text.ifEmpty { "No text" }, style = MaterialTheme.typography.bodyLarge)
            if (record.isSaved) {
                Text("Saved to ${record.savedTo}", style = MaterialTheme.typography.bodySmall)
            } else {
                // Red for failures; notes such as "Recovered" are not errors.
                val color = if (record.status == CaptureStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                record.message?.let { Text(it, color = color, style = MaterialTheme.typography.bodySmall) }
            }
            Row {
                if (record.status == CaptureStatus.READY || record.status == CaptureStatus.FAILED) {
                    TextButton(onClick = onOpen) { Text("Open") }
                }
                if (record.text.isNotEmpty()) TextButton(onClick = onCopy) { Text("Copy") }
                if (record.hasAudio && !busy) TextButton(onClick = onPlay) { Text(if (isPlaying) "Stop" else "Play") }
                if (!busy) TextButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun StatusLabel(status: CaptureStatus) {
    val colors = MaterialTheme.colorScheme
    val (text, background, foreground) = when (status) {
        CaptureStatus.SAVED -> Triple("Saved", colors.primaryContainer, colors.onPrimaryContainer)
        CaptureStatus.READY -> Triple("Not saved", colors.tertiaryContainer, colors.onTertiaryContainer)
        CaptureStatus.FAILED -> Triple("Failed", colors.errorContainer, colors.onErrorContainer)
        CaptureStatus.RECORDING -> Triple("Recording", colors.surfaceVariant, colors.onSurfaceVariant)
        CaptureStatus.TRANSCRIBING -> Triple("Recognizing", colors.surfaceVariant, colors.onSurfaceVariant)
        CaptureStatus.SAVING -> Triple("Saving", colors.surfaceVariant, colors.onSurfaceVariant)
    }
    Surface(color = background, contentColor = foreground, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelMedium)
    }
}

private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm").withZone(ZoneId.systemDefault())

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Voice Inbox", text))
    // Android 13 and later confirm copying themselves.
    if (Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
}
