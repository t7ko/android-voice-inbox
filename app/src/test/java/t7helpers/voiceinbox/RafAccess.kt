package t7helpers.voiceinbox

import java.io.File
import java.io.RandomAccessFile

/** The real thing on a JVM: positional reads and writes on a file opened read-write. */
open class RafAccess(file: File) : FileAccess {
    val raf = RandomAccessFile(file, "rw")

    override fun size() = raf.length()

    override fun read(position: Long, count: Int): ByteArray {
        val bytes = ByteArray(count)
        raf.seek(position)
        raf.readFully(bytes)
        return bytes
    }

    override fun write(position: Long, bytes: ByteArray) {
        raf.seek(position)
        raf.write(bytes)
    }

    override fun sync() = raf.fd.sync()
}
