package t7helpers.voiceinbox

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class CaptureStatus {
    /** Audio is being written. Found after a restart: the app stopped while recording. */
    RECORDING,

    /** The recognizer is finishing, or recovery transcribes the saved audio again. */
    TRANSCRIBING,

    /** Text ready, not saved yet. */
    READY,

    /** A write to the Markdown file is about to start or running; [CaptureRecord.pending] says what. */
    SAVING,
    SAVED,

    /** Saving failed; [CaptureRecord.message] says why. Saving again is possible. */
    FAILED,
}

/**
 * A write that started but was not confirmed: [text], the exact characters appended, was
 * to go at byte [offset] of the file at [uri].
 */
data class PendingWrite(val uri: String, val fileName: String, val offset: Long, val text: String) {
    val line get() = text.trim('\r', '\n')
}

/**
 * One capture as stored in `captures/<id>/capture.json`, next to its `audio.wav`. Every
 * change is written to disk before the app acts on it, so nothing is lost when the app
 * stops at any point.
 */
data class CaptureRecord(
    /** The local start time, `yyyyMMdd-HHmmss-SSS`: sorts by time. */
    val id: String,
    val createdAt: Long,
    val status: CaptureStatus,
    val text: String = "",
    val durationMs: Long = 0,
    /** A problem to show: why recognition or saving failed, or what recovery did. */
    val message: String? = null,
    /** The line may already be in the file, or that could not be checked: the user decides. */
    val needsDecision: Boolean = false,
    val pending: PendingWrite? = null,
    val savedLine: String? = null,
    val savedTo: String? = null,
    val savedAt: Long? = null,
    /** Not stored: whether `audio.wav` exists. */
    val hasAudio: Boolean = false,
) {
    val isSaved get() = status == CaptureStatus.SAVED

    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("createdAt", createdAt)
        put("status", status.name)
        put("text", text)
        put("durationMs", durationMs)
        putOpt("message", message)
        if (needsDecision) put("needsDecision", true)
        pending?.let {
            put("pending", JSONObject().put("uri", it.uri).put("fileName", it.fileName).put("offset", it.offset).put("text", it.text))
        }
        putOpt("savedLine", savedLine)
        putOpt("savedTo", savedTo)
        putOpt("savedAt", savedAt)
    }.toString(2)

    companion object {
        fun fromJson(json: String, hasAudio: Boolean): CaptureRecord {
            val o = JSONObject(json)
            return CaptureRecord(
                id = o.getString("id"),
                createdAt = o.getLong("createdAt"),
                status = CaptureStatus.valueOf(o.getString("status")),
                text = o.optString("text"),
                durationMs = o.optLong("durationMs"),
                message = o.optStringOrNull("message"),
                needsDecision = o.optBoolean("needsDecision"),
                pending = o.optJSONObject("pending")?.let {
                    PendingWrite(it.getString("uri"), it.getString("fileName"), it.getLong("offset"), it.getString("text"))
                },
                savedLine = o.optStringOrNull("savedLine"),
                savedTo = o.optStringOrNull("savedTo"),
                savedAt = if (o.has("savedAt")) o.getLong("savedAt") else null,
                hasAudio = hasAudio,
            )
        }

        private fun JSONObject.optStringOrNull(name: String): String? = if (has(name)) getString(name) else null
    }
}

/**
 * The captures on disk, one folder each under [dir], and the list of them for the screens.
 * Writes are atomic (a temporary file, flushed, then renamed over the old one) and
 * serialized, so any thread may call [put].
 */
class CaptureStore(val dir: File) {
    private val _records = MutableStateFlow<List<CaptureRecord>>(emptyList())

    /** Newest first. */
    val records: StateFlow<List<CaptureRecord>> = _records

    fun audioFile(id: String) = File(File(dir, id), AUDIO)

    /** Reads every capture folder. A folder without a readable record is a recording the app never finished describing. */
    @Synchronized
    fun load(): List<CaptureRecord> {
        val folders = dir.listFiles { file -> file.isDirectory }.orEmpty()
        val list = folders.map { folder ->
            val audio = File(folder, AUDIO)
            try {
                CaptureRecord.fromJson(File(folder, RECORD).readText(), audio.isFile)
            } catch (e: Exception) {
                CaptureRecord(
                    id = folder.name,
                    createdAt = audio.takeIf { it.isFile }?.lastModified() ?: folder.lastModified(),
                    status = CaptureStatus.RECORDING,
                    hasAudio = audio.isFile,
                )
            }
        }.sortedByDescending { it.id }
        _records.value = list
        return list
    }

    fun get(id: String): CaptureRecord? = _records.value.find { it.id == id }

    /** Writes [record] to disk, then shows it; throws if it cannot be written. */
    @Synchronized
    fun put(record: CaptureRecord): CaptureRecord {
        val folder = File(dir, record.id).apply { mkdirs() }
        val temp = File(folder, "$RECORD.tmp")
        FileOutputStream(temp).use { out ->
            out.write(record.toJson().toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(temp.toPath(), File(folder, RECORD).toPath(), StandardCopyOption.ATOMIC_MOVE)
        val stored = record.copy(hasAudio = File(folder, AUDIO).isFile)
        _records.value = (_records.value.filter { it.id != record.id } + stored).sortedByDescending { it.id }
        return stored
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, id).deleteRecursively()
        _records.value = _records.value.filter { it.id != id }
    }

    /** Deletes the audio of captures saved before [savedBefore]; unsaved ones keep theirs. */
    @Synchronized
    fun deleteOldAudio(savedBefore: Long) {
        _records.value = _records.value.map { record ->
            val savedAt = record.savedAt
            if (record.isSaved && record.hasAudio && savedAt != null && savedAt < savedBefore) {
                audioFile(record.id).delete()
                record.copy(hasAudio = audioFile(record.id).isFile)
            } else {
                record
            }
        }
    }

    private companion object {
        const val RECORD = "capture.json"
        const val AUDIO = "audio.wav"
    }
}
