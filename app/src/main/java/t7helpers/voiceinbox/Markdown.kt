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

    fun append(file: FileAccess, line: String): AppendResult {
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
}
