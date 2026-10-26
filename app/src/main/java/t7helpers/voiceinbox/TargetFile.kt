package t7helpers.voiceinbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.core.net.toUri
import java.io.EOFException
import java.io.FileDescriptor
import java.io.FileNotFoundException
import java.io.IOException

/** The Markdown file captures go to, picked with the Storage Access Framework. */
object TargetFile {
    private const val ACCESS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    /**
     * Keeps access to a file the user picked across restarts and makes it the target.
     * Returns an error message if the file cannot be used.
     */
    fun choose(context: Context, uri: Uri): String? {
        val resolver = context.contentResolver
        try {
            resolver.takePersistableUriPermission(uri, ACCESS)
        } catch (e: SecurityException) {
            return "This file cannot be written. Pick one from the phone's storage."
        }
        val old = Settings.targetUri(context)
        if (old != null && old != uri) {
            runCatching { resolver.releasePersistableUriPermission(old, ACCESS) }
        }
        Settings.setTarget(context, uri, displayName(context, uri) ?: uri.lastPathSegment ?: "file")
        return null
    }

    /** False when the stored permission is gone, for example after the app's data was restored. */
    fun hasAccess(context: Context, uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }

    fun displayName(context: Context, uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Saves [record]'s text into the file at [uri]. A record with an unconfirmed earlier
     * write first gets its file checked, unless [force]. [persist] stores every step.
     */
    fun save(
        context: Context,
        record: CaptureRecord,
        uri: Uri,
        fileName: String,
        force: Boolean,
        persist: (CaptureRecord) -> Unit,
    ): CaptureRecord {
        var current = record
        val pending = record.pending
        if (pending != null && !force) {
            current = checkPending(context, record, absentMessage = null)
            if (current.status == CaptureStatus.SAVED || current.needsDecision) return current
            persist(current)
        }
        return when (val opened = open(context, uri, "rw") { file -> TaskSaver.append(current, file, uri.toString(), fileName, persist) }) {
            is Outcome.Done -> opened.value
            is Outcome.Failed -> current.copy(
                status = CaptureStatus.FAILED,
                pending = if (force) pending else null,
                message = "Not saved. ${opened.message}",
            )
        }
    }

    /** Looks in the file of [record]'s unconfirmed write for the line; see [TaskSaver.resolve]. */
    fun checkPending(context: Context, record: CaptureRecord, absentMessage: String?): CaptureRecord {
        val pending = record.pending ?: return record
        val bytes = pending.text.toByteArray(Charsets.UTF_8)
        var problem: String? = null
        val opened = open(context, pending.uri.toUri(), "r") { file ->
            try {
                MarkdownAppend.check(file, pending.offset, bytes)
            } catch (e: IOException) {
                problem = e.message
                null
            }
        }
        val check = when (opened) {
            is Outcome.Done -> opened.value
            is Outcome.Failed -> {
                problem = opened.message
                null
            }
        }
        return TaskSaver.resolve(record, check, problem, absentMessage)
    }

    /**
     * Runs [block] on the file at [uri] opened in [mode] ("rw" never truncates, unlike "w";
     * some providers ignore "wa"), or fails with a message for the user.
     */
    private fun <T> open(context: Context, uri: Uri, mode: String, block: (FileAccess) -> T): Outcome<T> {
        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, mode)
        } catch (e: SecurityException) {
            return Outcome.Failed("Voice Inbox lost access to the file. Choose it again with Change.")
        } catch (e: FileNotFoundException) {
            return Outcome.Failed("The file is missing or cannot be opened: ${e.message}")
        } catch (e: Exception) {
            return Outcome.Failed("Could not open the file: ${e.message}")
        } ?: return Outcome.Failed("Could not open the file.")
        try {
            val isFile = try {
                OsConstants.S_ISREG(Os.fstat(pfd.fileDescriptor).st_mode)
            } catch (e: ErrnoException) {
                return Outcome.Failed("Could not open the file: ${e.message}")
            }
            return if (isFile) {
                Outcome.Done(block(FdFileAccess(pfd.fileDescriptor)))
            } else {
                Outcome.Failed("This storage does not give direct access to the file, so it cannot be appended to safely.")
            }
        } finally {
            // Written bytes were flushed and read back already; closing adds nothing.
            runCatching { pfd.close() }
        }
    }

    private sealed interface Outcome<out T> {
        class Done<T>(val value: T) : Outcome<T>
        class Failed(val message: String) : Outcome<Nothing>
    }
}

/** A file descriptor read and written at explicit offsets (pread, pwrite), never truncated. */
private class FdFileAccess(private val fd: FileDescriptor) : FileAccess {
    override fun size(): Long = io { Os.fstat(fd).st_size }

    override fun read(position: Long, count: Int): ByteArray = io {
        val bytes = ByteArray(count)
        var done = 0
        while (done < count) {
            val n = Os.pread(fd, bytes, done, count - done, position + done)
            if (n <= 0) throw EOFException("The file ended early.")
            done += n
        }
        bytes
    }

    override fun write(position: Long, bytes: ByteArray) = io {
        var done = 0
        while (done < bytes.size) done += Os.pwrite(fd, bytes, done, bytes.size - done, position + done)
    }

    override fun sync() = io { Os.fsync(fd) }

    private inline fun <T> io(block: () -> T): T = try {
        block()
    } catch (e: ErrnoException) {
        throw IOException(e.message, e)
    }
}
