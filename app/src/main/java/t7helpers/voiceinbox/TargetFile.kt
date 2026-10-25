package t7helpers.voiceinbox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
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

    fun append(context: Context, uri: Uri, line: String): AppendResult {
        val pfd = try {
            // "rw" never truncates, unlike "w"; and some providers ignore "wa".
            context.contentResolver.openFileDescriptor(uri, "rw")
        } catch (e: SecurityException) {
            return AppendResult.NotWritten("No permission to write the file any more. Choose it again in Settings.")
        } catch (e: FileNotFoundException) {
            return AppendResult.NotWritten("The file is missing or cannot be opened: ${e.message}")
        } catch (e: Exception) {
            return AppendResult.NotWritten("Could not open the file: ${e.message}")
        } ?: return AppendResult.NotWritten("Could not open the file.")
        val result = try {
            val mode = Os.fstat(pfd.fileDescriptor).st_mode
            if (OsConstants.S_ISREG(mode)) {
                MarkdownAppend.append(FdFileAccess(pfd.fileDescriptor), line)
            } else {
                AppendResult.NotWritten("This storage does not give direct access to the file, so it cannot be appended to safely.")
            }
        } catch (e: ErrnoException) {
            AppendResult.NotWritten("Could not open the file: ${e.message}")
        }
        try {
            pfd.close()
        } catch (e: IOException) {
            if (result is AppendResult.Appended) return AppendResult.Unverified("Closing the file failed: ${e.message}")
        }
        return result
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
