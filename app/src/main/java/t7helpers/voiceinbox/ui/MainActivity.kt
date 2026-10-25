package t7helpers.voiceinbox.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.Settings
import t7helpers.voiceinbox.SpeechModel
import t7helpers.voiceinbox.TargetFile

private enum class Screen { CAPTURE, HISTORY, SETTINGS }

/** The Markdown file as the screens show it. */
data class Target(val name: String?, val accessible: Boolean, val problem: String? = null)

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.CAPTURE)
    private var target by mutableStateOf(Target(null, false))

    private val targetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) target = readTarget(problem = TargetFile.choose(this, uri))
    }

    private val microphoneRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Capture.start(this) else Capture.refuse("Voice Inbox needs the microphone. Allow it in App info.", openAppInfo = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Capture.init(this)
        SpeechModel.refresh(this)
        SpeechModel.preload(this)
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let { name -> Screen.entries.find { it.name == name } }
            ?: Screen.CAPTURE

        setContent {
            VoiceInboxTheme {
                BackHandler(enabled = screen != Screen.CAPTURE) { screen = Screen.CAPTURE }
                when (screen) {
                    Screen.CAPTURE -> CaptureScreen(
                        target,
                        onRecord = ::record,
                        onPickTarget = ::pickTarget,
                        onOpenAppInfo = ::openAppInfo,
                        onOpenHistory = { screen = Screen.HISTORY },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.HISTORY -> HistoryScreen(onOpen = ::openCapture, onBack = { screen = Screen.CAPTURE })
                    Screen.SETTINGS -> SettingsScreen(target, onPickTarget = ::pickTarget, onBack = { screen = Screen.CAPTURE })
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        target = readTarget(target.problem)
    }

    override fun onStop() {
        super.onStop()
        // No background recording: a recording ends when the app leaves the screen. Its
        // audio and text are kept, and so is edited text.
        if (!isChangingConfigurations) Capture.onHidden()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_SCREEN, screen.name)
    }

    private fun readTarget(problem: String?): Target {
        val uri = Settings.targetUri(this) ?: return Target(null, false, problem)
        return Target(Settings.targetName(this), TargetFile.hasAccess(this, uri), problem)
    }

    private fun record() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Capture.start(this)
        } else {
            microphoneRequest.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun pickTarget() {
        // Markdown files often have no MIME type of their own, so any file can be picked.
        targetPicker.launch(arrayOf("*/*"))
    }

    private fun openCapture(id: String) {
        if (Capture.open(id)) {
            screen = Screen.CAPTURE
        } else {
            Toast.makeText(this, "Finish the current capture first.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppInfo() {
        startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
    }

    private companion object {
        const val KEY_SCREEN = "screen"
    }
}
