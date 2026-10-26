package t7helpers.voiceinbox.ui

import android.Manifest
import android.content.Context
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import t7helpers.voiceinbox.Capture
import t7helpers.voiceinbox.CaptureState
import t7helpers.voiceinbox.Settings
import t7helpers.voiceinbox.SpeechModel
import t7helpers.voiceinbox.TargetFile

private enum class Screen { CAPTURE, HISTORY, SETTINGS }

/** The Markdown file as the screens show it. */
data class Target(val name: String?, val accessible: Boolean, val problem: String? = null)

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.CAPTURE)
    private var target by mutableStateOf(Target(null, false))
    private var language by mutableStateOf("")

    /** Set by the Record icon; recording starts once the activity is in front. */
    private var recordOnResume = false

    /** Whether the recording being started, perhaps after the permission prompt, came from the Record icon. */
    private var recordFromIcon = false

    private val targetPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) target = readTarget(problem = TargetFile.choose(this, uri))
    }

    private val microphoneRequest = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) Capture.start(this, recordFromIcon) else Capture.refuse("Voice Inbox needs the microphone. Allow it in App info.", openAppInfo = true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Capture.init(this)
        SpeechModel.refresh(this)
        language = Settings.language(this)
        SpeechModel.preload(this, language)
        screen = savedInstanceState?.getString(KEY_SCREEN)?.let { name -> Screen.entries.find { it.name == name } }
            ?: Screen.CAPTURE
        // Not again after a rotation, which recreates the activity with the same intent.
        if (savedInstanceState == null) handle(intent)

        setContent {
            val state by Capture.state.collectAsState()
            LaunchedEffect(state) {
                val saved = state as? CaptureState.Saved
                if (saved != null && saved.closeApp) {
                    delay(CLOSE_DELAY_MS)
                    if (Capture.state.value == saved) {
                        Capture.close()
                        moveTaskToBack(true)
                    }
                }
            }
            VoiceInboxTheme {
                BackHandler(enabled = screen != Screen.CAPTURE) { screen = Screen.CAPTURE }
                when (screen) {
                    Screen.CAPTURE -> CaptureScreen(
                        target,
                        language,
                        onLanguage = ::chooseLanguage,
                        onRecord = { record(fromIcon = false) },
                        onPickTarget = ::pickTarget,
                        onOpenAppInfo = ::openAppInfo,
                        onOpenHistory = { screen = Screen.HISTORY },
                        onOpenSettings = { screen = Screen.SETTINGS },
                    )
                    Screen.HISTORY -> HistoryScreen(onOpen = ::openCapture, onBack = { screen = Screen.CAPTURE })
                    Screen.SETTINGS -> SettingsScreen(
                        target,
                        language,
                        onLanguage = ::chooseLanguage,
                        onPickTarget = ::pickTarget,
                        onBack = { screen = Screen.CAPTURE },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        target = readTarget(target.problem)
        if (recordOnResume) {
            recordOnResume = false
            record(fromIcon = true)
        }
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

    private fun handle(intent: Intent) {
        if (intent.action == ACTION_RECORD) {
            screen = Screen.CAPTURE
            recordOnResume = true
        }
    }

    private fun readTarget(problem: String?): Target {
        val uri = Settings.targetUri(this) ?: return Target(null, false, problem)
        return Target(Settings.targetName(this), TargetFile.hasAccess(this, uri), problem)
    }

    private fun chooseLanguage(language: String) {
        this.language = language
        Settings.setLanguage(this, language)
        SpeechModel.preload(this, language)
    }

    private fun record(fromIcon: Boolean) {
        recordFromIcon = fromIcon
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Capture.start(this, fromIcon)
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

    companion object {
        private const val KEY_SCREEN = "screen"

        /** How long "Saved" shows before the app goes back to the home screen. */
        private const val CLOSE_DELAY_MS = 1500L
        private const val ACTION_RECORD = "t7helpers.voiceinbox.action.RECORD"

        /** Opens the capture screen and starts recording, also when the app is open already. */
        fun recordIntent(context: Context): Intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_RECORD)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
