package t7helpers.voiceinbox

import android.content.Context
import android.content.pm.ApplicationInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

sealed interface CaptureState {
    /** Ready to record; [message] says why the last attempt did not start, [openAppInfo] whether App info can fix it. */
    data class Idle(val message: String? = null, val openAppInfo: Boolean = false) : CaptureState

    /** [stopping]: the microphone is off and the recognizer is finishing the text. */
    data class Recording(
        val elapsedMs: Long = 0,
        val level: Float = 0f,
        val text: String = "",
        val recognizerReady: Boolean = false,
        val stopping: Boolean = false,
    ) : CaptureState

    /** The recognized text, editable before saving; [message] is a problem to show. */
    data class Editing(val text: String, val message: String? = null) : CaptureState

    data class Saving(val text: String) : CaptureState

    data class Saved(val line: String, val fileName: String) : CaptureState
}

/**
 * The capture in progress, kept for the whole process so that it survives screen rotation.
 * Audio goes to `captures/<time>/audio.wav` in app storage and stays there.
 */
object Capture {
    private const val MAX_DURATION_MS = 120_000L
    private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle())
    val state: StateFlow<CaptureState> = _state
    @Volatile private var recorder: Recorder? = null

    /** Needs the RECORD_AUDIO permission. */
    fun start(context: Context) {
        val current = _state.value
        if (current is CaptureState.Recording || current is CaptureState.Saving) return
        val app = context.applicationContext
        val input = try {
            testInput(app)?.let { WavFileInput(it, realTime = true) } ?: MicInput.start()
        } catch (e: IOException) {
            _state.value = CaptureState.Idle(e.message)
            return
        }
        val file = File(app.filesDir, "captures/${LocalDateTime.now().format(ID_FORMAT)}/audio.wav")
        val listener = Listener()
        val r = Recorder(file, { SpeechModel.load(app) }, MAX_DURATION_MS, listener)
        listener.recorder = r
        recorder = r
        _state.value = CaptureState.Recording()
        r.start(input)?.let { error ->
            recorder = null
            _state.value = CaptureState.Idle(error)
        }
    }

    /**
     * In debug builds, `files/test-input.wav` replaces the microphone while it exists, so
     * recognition can be tried with recorded speech on the emulator (see AGENTS.md).
     */
    private fun testInput(context: Context): File? {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
        return File(context.filesDir, "test-input.wav").takeIf { it.isFile }
    }

    /** Shows why recording cannot start, such as a denied permission. */
    fun refuse(message: String, openAppInfo: Boolean) =
        _state.update { if (it is CaptureState.Idle) CaptureState.Idle(message, openAppInfo) else it }

    fun stop() {
        recorder?.stop() ?: return
        _state.update { if (it is CaptureState.Recording) it.copy(stopping = true) else it }
    }

    fun edit(text: String) = _state.update { if (it is CaptureState.Editing) it.copy(text = text) else it }

    /** Leaves the text unsaved; its audio stays in app storage. */
    fun discard() = _state.update { if (it is CaptureState.Editing || it is CaptureState.Saved) CaptureState.Idle() else it }

    fun save(context: Context) {
        val editing = _state.value as? CaptureState.Editing ?: return
        val app = context.applicationContext
        val uri = Settings.targetUri(app)
        if (uri == null) {
            _state.value = editing.copy(message = "Choose the Markdown file first.")
            return
        }
        val line = TaskLine.format(editing.text)
        if (line == null) {
            _state.value = editing.copy(message = "Nothing to save: the text is empty.")
            return
        }
        _state.value = CaptureState.Saving(editing.text)
        scope.launch {
            _state.value = when (val result = TargetFile.append(app, uri, line)) {
                is AppendResult.Appended -> CaptureState.Saved(line, Settings.targetName(app) ?: "the file")
                is AppendResult.NotWritten -> CaptureState.Editing(editing.text, "Not saved. ${result.message}")
                is AppendResult.Unverified -> CaptureState.Editing(
                    editing.text,
                    "The save could not be confirmed. ${result.message} Look in the file before saving " +
                        "again, or the task may be added twice.",
                )
            }
        }
    }

    private class Listener : Recorder.Listener {
        lateinit var recorder: Recorder
        private val active get() = Capture.recorder === recorder

        override fun onAudio(elapsedMs: Long, level: Float) {
            if (active) _state.update { if (it is CaptureState.Recording) it.copy(elapsedMs = elapsedMs, level = level) else it }
        }

        override fun onText(text: String, recognizerReady: Boolean) {
            if (active) _state.update { if (it is CaptureState.Recording) it.copy(text = text, recognizerReady = recognizerReady) else it }
        }

        override fun onFinished(text: String, durationMs: Long, error: String?) {
            if (!active) return
            Capture.recorder = null
            val message = error ?: if (text.isEmpty()) "Nothing was recognized. Type the task or record again." else null
            _state.value = CaptureState.Editing(text, message)
        }
    }
}
