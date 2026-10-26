package t7helpers.voiceinbox

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
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
import java.util.concurrent.ConcurrentHashMap

/** What the capture screen shows. */
sealed interface CaptureState {
    /**
     * Ready to record; [message] says why the last attempt did not start, [openAppInfo]
     * whether App info can fix it.
     */
    data class Idle(val message: String? = null, val openAppInfo: Boolean = false) : CaptureState

    /**
     * [stopping]: the microphone is off and the recognizer is finishing the text.
     * [autoStopMs]: the silence after which it stops by itself, if it does.
     */
    data class Recording(
        val id: String,
        val autoStopMs: Long? = null,
        /** Started from the Record icon or shortcut. */
        val fromIcon: Boolean = false,
        val elapsedMs: Long = 0,
        val level: Float = 0f,
        val text: String = "",
        val recognizerReady: Boolean = false,
        val stopping: Boolean = false,
    ) : CaptureState

    /**
     * A capture's text, editable before saving; [message] is a problem to show. With
     * [needsDecision], an unconfirmed save may have worked: the user saves anyway or marks
     * it saved.
     */
    data class Editing(
        val id: String,
        val text: String,
        val message: String? = null,
        val needsDecision: Boolean = false,
    ) : CaptureState

    data class Saving(val id: String, val text: String) : CaptureState

    /** [closeApp]: saved by itself after a start from the Record icon; the app closes (Settings). */
    data class Saved(val id: String, val line: String, val fileName: String, val closeApp: Boolean = false) : CaptureState
}

/**
 * The capture on screen and the stored ones. Each capture is a [CaptureRecord] in
 * `captures/<id>/` with its audio, written before every step, so that nothing is lost when
 * the app stops; [init] recovers what an earlier process left unfinished.
 */
@SuppressLint("StaticFieldLeak") // Only the application context: see [app].
object Capture {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private const val TAG = "VoiceInbox"
    private val ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<CaptureState>(CaptureState.Idle())
    val state: StateFlow<CaptureState> = _state
    @Volatile private var recorder: Recorder? = null
    private lateinit var store: CaptureStore
    /** The application context, which lives as long as the process anyway. */
    private lateinit var app: Context

    /**
     * A Record tap that came while the last capture was still being recognized or saved:
     * starts the next one as soon as that is done (true: from the Record icon). Null: none.
     */
    @Volatile private var startWhenSettled: Boolean? = null

    /** Captures started by this process, which recovery leaves alone. */
    private val startedHere = ConcurrentHashMap.newKeySet<String>()

    /** All captures, newest first. */
    val records: StateFlow<List<CaptureRecord>> get() = store.records

    /** Loads the stored captures and, once per process, finishes those the app left unfinished. */
    @Synchronized
    fun init(context: Context) {
        if (::store.isInitialized) return
        app = context.applicationContext
        store = CaptureStore(File(app.filesDir, "captures"))
        scope.launch { recover(app) }
    }

    fun audioFile(id: String): File = store.audioFile(id)

    /** Needs the RECORD_AUDIO permission. [fromIcon]: started from the Record icon or shortcut. */
    fun start(context: Context, fromIcon: Boolean = false) {
        val current = _state.value
        if ((current is CaptureState.Recording && current.stopping) || current is CaptureState.Saving) {
            startWhenSettled = fromIcon
            return
        }
        if (current is CaptureState.Recording) return
        val app = context.applicationContext
        val language = Settings.language(app)
        // The capture on screen stays in History, with its edits, whether a new one starts or not.
        if (current is CaptureState.Editing) keep(current)
        val problem = FocusedInput.blocked(app) ?: if (SpeechModel.state(language) != ModelState.Installed) {
            "Download the ${SpeechModel.info(language).languageName} speech model first."
        } else {
            null
        }
        if (problem != null) {
            _state.value = CaptureState.Idle(problem)
            return
        }
        val input = try {
            FocusedInput(testInput(app)?.let { WavFileInput(it, realTime = true) } ?: MicInput.start(), app)
        } catch (e: IOException) {
            _state.value = CaptureState.Idle(e.message)
            return
        }
        val id = LocalDateTime.now().format(ID_FORMAT)
        startedHere += id
        try {
            store.put(CaptureRecord(id, System.currentTimeMillis(), CaptureStatus.RECORDING, language = language))
        } catch (e: IOException) {
            input.close()
            _state.value = CaptureState.Idle("Could not store the capture: ${e.message}")
            return
        }
        val autoStop = if (Settings.autoStop(app)) AutoStop(Settings.silenceMs(app).toLong()) else null
        val listener = Listener(id, fromIcon)
        val r = Recorder(store.audioFile(id), { SpeechModel.load(app, language) }, Settings.maxSeconds(app) * 1000L, autoStop, listener)
        listener.recorder = r
        recorder = r
        _state.value = CaptureState.Recording(id, autoStopMs = autoStop?.silenceMs, fromIcon = fromIcon)
        Log.i(TAG, "Recording started")
        r.start(input)?.let { error ->
            recorder = null
            store.delete(id)
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

    /** The app leaves the screen: a recording ends there, edited text is stored. */
    fun onHidden() {
        startWhenSettled = null
        stop()
        (_state.value as? CaptureState.Editing)?.let(::keep)
    }

    fun edit(text: String) = _state.update { if (it is CaptureState.Editing) it.copy(text = text) else it }

    /** Back to the record button; the capture stays in History. */
    fun close() {
        val current = _state.value
        if (current is CaptureState.Editing) keep(current)
        if (current is CaptureState.Editing || current is CaptureState.Saved) _state.value = CaptureState.Idle()
    }

    /** Opens a capture from History for editing and saving. Returns false if that is not possible now. */
    fun open(id: String): Boolean {
        val current = _state.value
        if (current is CaptureState.Recording || current is CaptureState.Saving) return false
        val record = store.get(id) ?: return false
        if (record.status != CaptureStatus.READY && record.status != CaptureStatus.FAILED) return false
        if (current is CaptureState.Editing) keep(current)
        _state.value = CaptureState.Editing(id, record.text, record.message, record.needsDecision)
        return true
    }

    /** Deletes a capture and its audio, unless it is being recorded, recovered or saved. */
    fun delete(id: String) {
        val record = store.get(id) ?: return
        if (record.status == CaptureStatus.RECORDING || record.status == CaptureStatus.TRANSCRIBING || record.status == CaptureStatus.SAVING) return
        val current = _state.value
        if (current is CaptureState.Saving && current.id == id) return
        if ((current as? CaptureState.Editing)?.id == id || (current as? CaptureState.Saved)?.id == id) {
            _state.value = CaptureState.Idle()
        }
        scope.launch { store.delete(id) }
    }

    /**
     * Saves the text on screen into the Markdown file. With [force], a possible earlier
     * write is not looked for (the user chose "Save anyway").
     */
    fun save(context: Context, force: Boolean = false) = save(context, force, closeApp = false)

    /** [closeApp]: saved by itself after a start from the Record icon; see [CaptureState.Saved]. */
    private fun save(context: Context, force: Boolean, closeApp: Boolean) {
        val editing = _state.value as? CaptureState.Editing ?: return
        val app = context.applicationContext
        val uri = Settings.targetUri(app)
        if (uri == null) {
            _state.value = editing.copy(message = "Choose the Markdown file first.")
            return
        }
        if (TaskLine.format(editing.text) == null) {
            _state.value = editing.copy(message = "Nothing to save: the text is empty.")
            return
        }
        val record = store.get(editing.id)?.copy(text = editing.text) ?: return
        _state.value = CaptureState.Saving(editing.id, editing.text)
        scope.launch {
            val result = try {
                store.put(record)
                TargetFile.save(app, record, uri, Settings.targetName(app) ?: "the file", force) { store.put(it) }
            } catch (e: Exception) {
                record.copy(status = CaptureStatus.FAILED, message = "Not saved: ${e.message}")
            }
            // If this fails, the record still says SAVING and recovery checks the file.
            runCatching { store.put(result) }
            show(result, closeApp)
            settled()
        }
    }

    /** The user says the line is in the file already. */
    fun markSaved(context: Context) {
        val editing = _state.value as? CaptureState.Editing ?: return
        val record = store.get(editing.id)?.copy(text = editing.text) ?: return
        val pending = record.pending
        val line = pending?.line ?: TaskLine.format(editing.text) ?: return
        val fileName = pending?.fileName ?: Settings.targetName(context) ?: "the file"
        _state.value = CaptureState.Saving(editing.id, editing.text)
        scope.launch {
            val result = TaskSaver.saved(record, line, fileName)
            runCatching { store.put(result) }
            show(result)
            settled()
        }
    }

    /** Deletes the audio of captures saved longer ago than Settings says. */
    fun deleteOldAudio(context: Context) {
        val days = Settings.audioRetentionDays(context)
        if (days == Settings.KEEP_AUDIO) return
        scope.launch { store.deleteOldAudio(System.currentTimeMillis() - days * DAY_MS) }
    }

    /** Starts the capture a Record tap asked for while the last one was finishing. */
    private fun settled() {
        val fromIcon = startWhenSettled ?: return
        startWhenSettled = null
        start(app, fromIcon)
    }

    private fun show(record: CaptureRecord, closeApp: Boolean = false) {
        _state.value = if (record.isSaved) {
            CaptureState.Saved(
                record.id,
                record.savedLine.orEmpty(),
                record.savedTo.orEmpty(),
                closeApp && startWhenSettled == null && Settings.closeAfterAutoSave(app),
            )
        } else {
            CaptureState.Editing(record.id, record.text, record.message, record.needsDecision)
        }
    }

    /** Stores edited text, in the background. */
    private fun keep(editing: CaptureState.Editing) {
        scope.launch {
            val record = store.get(editing.id) ?: return@launch
            if (record.text != editing.text && !record.isSaved) runCatching { store.put(record.copy(text = editing.text)) }
        }
    }

    private fun recover(app: Context) {
        val records = store.load()
        for (record in records) {
            if (record.id in startedHere) continue
            try {
                when (record.status) {
                    CaptureStatus.RECORDING, CaptureStatus.TRANSCRIBING -> transcribeAgain(app, record)
                    CaptureStatus.SAVING -> store.put(
                        if (record.pending == null) {
                            record.copy(status = CaptureStatus.FAILED, message = "The app stopped before saving. Save again.")
                        } else {
                            TargetFile.checkPending(app, record, "The app stopped while saving; the task is not in the file. Save again.")
                        },
                    )
                    else -> {}
                }
            } catch (e: Exception) {
                // Left as it is; the next start tries again.
            }
        }
        deleteOldAudio(app)
    }

    /** A recording the app stopped in the middle of: repair its audio and recognize it again. */
    private fun transcribeAgain(app: Context, record: CaptureRecord) {
        val audio = store.audioFile(record.id)
        if (!audio.isFile) {
            store.put(record.copy(status = CaptureStatus.READY, message = "The app stopped during this recording; no audio was kept."))
            return
        }
        store.put(record.copy(status = CaptureStatus.TRANSCRIBING))
        val dataSize = Wav.repair(audio)
        val recovered = record.copy(status = CaptureStatus.READY, durationMs = Wav.durationMs(dataSize))
        store.put(
            try {
                val text = Transcriber.transcribe(SpeechModel.load(app, record.language), audio)
                recovered.copy(text = text, message = "Recovered: the app stopped during this recording, so the text was recognized again from its audio.")
            } catch (e: Throwable) {
                recovered.copy(message = "The app stopped during this recording. Its audio is kept, but recognizing it failed: ${e.message}")
            },
        )
    }

    private class Listener(val id: String, val fromIcon: Boolean) : Recorder.Listener {
        lateinit var recorder: Recorder
        private val active get() = Capture.recorder === recorder

        override fun onAudio(elapsedMs: Long, level: Float) {
            if (active) _state.update { if (it is CaptureState.Recording) it.copy(elapsedMs = elapsedMs, level = level) else it }
        }

        override fun onText(text: String, recognizerReady: Boolean) {
            if (active) _state.update { if (it is CaptureState.Recording) it.copy(text = text, recognizerReady = recognizerReady) else it }
        }

        override fun onAutoStop() {
            if (active) _state.update { if (it is CaptureState.Recording) it.copy(stopping = true) else it }
        }

        override fun onFinished(text: String, durationMs: Long, error: String?, reason: StopReason, confidence: Double?) {
            if (!active) return
            Capture.recorder = null
            var message = error ?: if (text.isEmpty()) "Nothing was recognized. Type the task or record again." else null
            // Only a clean result is saved without a look: no error, words the recognizer is sure of.
            val autoSave = message == null && Settings.autoSave(app)
            if (autoSave) message = AutoSave.hold(reason, confidence)
            val record = (store.get(id) ?: CaptureRecord(id, System.currentTimeMillis(), CaptureStatus.READY))
                .copy(status = CaptureStatus.READY, text = text, durationMs = durationMs, message = message)
            var stored = true
            try {
                store.put(record)
            } catch (e: IOException) {
                message = "Could not store the capture: ${e.message}"
                stored = false
            }
            _state.value = CaptureState.Editing(id, text, message)
            if (autoSave && stored && message == null) save(app, force = false, closeApp = fromIcon) else settled()
        }
    }
}
