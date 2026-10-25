package t7helpers.voiceinbox

import java.io.IOException

/** The Markdown task line a capture becomes. */
object TaskLine {
    /** `- [ ] text` with all whitespace, line breaks too, folded into single spaces; null if blank. */
    fun format(text: String): String? {
        val oneLine = text.split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
        return if (oneLine.isEmpty()) null else "- [ ] $oneLine"
    }

    private val WHITESPACE = Regex("\\s+")
}

/** Random access to the bytes of the target file. Reads return exactly [count] bytes or throw. */
interface FileAccess {
    fun size(): Long
    fun read(position: Long, count: Int): ByteArray
    fun write(position: Long, bytes: ByteArray)

    /** Flushes written bytes to storage (fsync). */
    fun sync()
}

sealed interface AppendResult {
    /** [bytes] are in the file at [offset], read back after the write. */
    class Appended(val offset: Long, val bytes: ByteArray) : AppendResult

    /** Nothing was written; the file is as it was. Saving again is safe. */
    data class NotWritten(val message: String) : AppendResult

    /**
     * The write started but could not be confirmed: the line may or may not be in the
     * file. Saving again blindly may add it twice.
     */
    data class Unverified(val message: String) : AppendResult
}

/**
 * Appends one line at the end of a text file without ever truncating or rewriting it: the
 * bytes go at the end offset of a file opened read-write, then are flushed and read back.
 * Opening in append mode is avoided because some document providers ignore it, and
 * read-modify-write because a crash between truncating and writing loses the file.
 */
object MarkdownAppend {
    /** Enough of the file's end to see its last line break. */
    private const val TAIL_SIZE = 4096
    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private const val LF = '\n'.code.toByte()
    private const val CR = '\r'.code.toByte()

    /**
     * What to write after [tail], the last bytes of a file of [fileSize] bytes, so that
     * [line] becomes its last line: a line break first if the file does not end with one,
     * no blank line ever, and the file's own line breaks (CRLF if its last one is CRLF).
     */
    fun bytesToAppend(tail: ByteArray, fileSize: Long, line: String): ByteArray {
        val lastBreak = tail.lastIndexOf(LF)
        val eol = if (lastBreak > 0 && tail[lastBreak - 1] == CR) "\r\n" else "\n"
        val atLineStart = fileSize == 0L ||
            tail.last() == LF ||
            (fileSize == UTF8_BOM.size.toLong() && tail.contentEquals(UTF8_BOM))
        return ((if (atLineStart) "" else eol) + line + eol).toByteArray(Charsets.UTF_8)
    }

    /**
     * [beforeWrite] gets the offset and the bytes right before they are written, to store
     * them; if it throws, nothing is written.
     */
    fun append(file: FileAccess, line: String, beforeWrite: (offset: Long, bytes: ByteArray) -> Unit = { _, _ -> }): AppendResult {
        val size: Long
        val bytes: ByteArray
        try {
            size = file.size()
            val tailSize = minOf(size, TAIL_SIZE.toLong()).toInt()
            bytes = bytesToAppend(file.read(size - tailSize, tailSize), size, line)
            // Another app may be writing it right now; then leave it alone.
            if (file.size() != size) return AppendResult.NotWritten("The file changed while saving. Try again.")
        } catch (e: IOException) {
            return AppendResult.NotWritten("Could not read the file: ${e.message}")
        }
        try {
            beforeWrite(size, bytes)
        } catch (e: Exception) {
            return AppendResult.NotWritten("Could not store the capture before saving: ${e.message}")
        }
        try {
            file.write(size, bytes)
            file.sync()
        } catch (e: IOException) {
            return AppendResult.Unverified("Writing failed: ${e.message}")
        }
        val written = try {
            file.read(size, bytes.size)
        } catch (e: IOException) {
            return AppendResult.Unverified("Could not read the file back: ${e.message}")
        }
        return if (written.contentEquals(bytes)) {
            AppendResult.Appended(size, bytes)
        } else {
            AppendResult.Unverified("The file does not hold the new line after writing it.")
        }
    }

    /** Files larger than this are not searched for a line; the user decides instead. */
    private const val MAX_SEARCH = 32L * 1024 * 1024

    /**
     * Whether [bytes], once meant to go at [offset], are in the file now. The file being
     * exactly as long as it was before the write means they are not.
     */
    fun check(file: FileAccess, offset: Long, bytes: ByteArray): WriteCheck {
        val size = file.size()
        if (size == offset) return WriteCheck.ABSENT
        if (size >= offset + bytes.size && file.read(offset, bytes.size).contentEquals(bytes)) return WriteCheck.PRESENT
        // The file changed otherwise since: the line may have been written and moved.
        if (size > MAX_SEARCH) return WriteCheck.ELSEWHERE
        val line = String(bytes, Charsets.UTF_8).trim('\r', '\n')
        val text = String(file.read(0, size.toInt()), Charsets.UTF_8)
        return if (text.lineSequence().any { it == line }) WriteCheck.ELSEWHERE else WriteCheck.ABSENT
    }
}

enum class WriteCheck {
    /** Exactly where it was written. */
    PRESENT,

    /** Not in the file. */
    ABSENT,

    /** The same line is in the file, but not where it was written: maybe an earlier capture's. */
    ELSEWHERE,
}

/**
 * Saving a capture's text into the Markdown file without losing it or adding it twice.
 * Before the bytes are written, the record says exactly what goes where (status SAVING,
 * [CaptureRecord.pending]); whenever a write was not confirmed, the file is checked before
 * the next attempt.
 */
object TaskSaver {
    /**
     * What to make of a record's unconfirmed write, from [check] on its file (null when the
     * file could not be read, with [problem] saying why). [absentMessage] goes with a record
     * whose line is not in the file.
     */
    fun resolve(record: CaptureRecord, check: WriteCheck?, problem: String?, absentMessage: String?): CaptureRecord {
        val pending = record.pending ?: return record
        return when (check) {
            WriteCheck.PRESENT -> saved(record, pending.line, pending.fileName)
            WriteCheck.ABSENT -> record.copy(status = CaptureStatus.FAILED, pending = null, needsDecision = false, message = absentMessage)
            WriteCheck.ELSEWHERE -> record.copy(
                status = CaptureStatus.FAILED,
                needsDecision = true,
                message = "${pending.fileName} already has this line, but not where the unconfirmed save put it. " +
                    "It may be from that save or from an earlier capture.",
            )
            null -> record.copy(
                status = CaptureStatus.FAILED,
                needsDecision = true,
                message = "Could not check whether ${pending.fileName} already has this line: $problem",
            )
        }
    }

    /** Appends the record's text to [file] (at [uri], named [fileName]); [persist] stores each step. */
    fun append(record: CaptureRecord, file: FileAccess, uri: String, fileName: String, persist: (CaptureRecord) -> Unit): CaptureRecord {
        val line = TaskLine.format(record.text)
            ?: return record.copy(status = CaptureStatus.FAILED, message = "Nothing to save: the text is empty.")
        var started = record
        val result = MarkdownAppend.append(file, line) { offset, bytes ->
            started = record.copy(
                status = CaptureStatus.SAVING,
                pending = PendingWrite(uri, fileName, offset, String(bytes, Charsets.UTF_8)),
                needsDecision = false,
                message = null,
            )
            persist(started)
        }
        return when (result) {
            is AppendResult.Appended -> saved(record, line, fileName)
            is AppendResult.NotWritten -> record.copy(
                status = CaptureStatus.FAILED,
                pending = null,
                needsDecision = false,
                message = "Not saved. ${result.message}",
            )
            is AppendResult.Unverified -> started.copy(
                status = CaptureStatus.FAILED,
                message = "The save could not be confirmed. ${result.message} Saving again checks the file first.",
            )
        }
    }

    fun saved(record: CaptureRecord, line: String, fileName: String) = record.copy(
        status = CaptureStatus.SAVED,
        pending = null,
        needsDecision = false,
        message = null,
        savedLine = line,
        savedTo = fileName,
        savedAt = System.currentTimeMillis(),
    )
}
