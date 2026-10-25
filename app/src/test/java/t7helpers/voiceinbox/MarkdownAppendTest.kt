package t7helpers.voiceinbox

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

class MarkdownAppendTest {
    @get:Rule val temp = TemporaryFolder()

    /** The real thing on a JVM: positional reads and writes on a file opened read-write. */
    private open class RafAccess(file: File) : FileAccess {
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

    private fun fileWith(content: ByteArray) = temp.newFile().apply { writeBytes(content) }
    private fun fileWith(content: String) = fileWith(content.toByteArray())

    private fun append(file: File, line: String): AppendResult = RafAccess(file).let { access ->
        MarkdownAppend.append(access, line).also { access.raf.close() }
    }

    @Test fun emptyFile() {
        val file = fileWith("")
        assertTrue(append(file, "- [ ] a") is AppendResult.Appended)
        assertEquals("- [ ] a\n", file.readText())
    }

    @Test fun fileEndingWithNewline() {
        val file = fileWith("# Inbox\n")
        append(file, "- [ ] a")
        assertEquals("# Inbox\n- [ ] a\n", file.readText())
    }

    @Test fun fileWithoutTrailingNewline() {
        val file = fileWith("# Inbox\n- [ ] old")
        append(file, "- [ ] new")
        assertEquals("# Inbox\n- [ ] old\n- [ ] new\n", file.readText())
    }

    @Test fun keepsExistingTasksByteForByte() {
        val before = "# Inbox\n\n- [x] done ✅ 2026-10-01\n- [ ] Купить молоко\n"
        val file = fileWith(before)
        val result = append(file, "- [ ] Позвонить маме") as AppendResult.Appended
        assertEquals(before.toByteArray().size.toLong(), result.offset)
        assertEquals(before + "- [ ] Позвонить маме\n", file.readText())
    }

    @Test fun repeatedCapturesAddNoBlankLines() {
        val file = fileWith("- [ ] one")
        append(file, "- [ ] two")
        append(file, "- [ ] three")
        append(file, "- [ ] four")
        assertEquals("- [ ] one\n- [ ] two\n- [ ] three\n- [ ] four\n", file.readText())
    }

    @Test fun existingBlankLineAtEndIsLeftAlone() {
        val file = fileWith("text\n\n")
        append(file, "- [ ] a")
        assertEquals("text\n\n- [ ] a\n", file.readText())
    }

    @Test fun followsWindowsLineBreaks() {
        val file = fileWith("# Inbox\r\n- [ ] old")
        append(file, "- [ ] new")
        assertEquals("# Inbox\r\n- [ ] old\r\n- [ ] new\r\n", file.readText())
    }

    @Test fun fileWithOnlyByteOrderMark() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val file = fileWith(bom)
        append(file, "- [ ] a")
        assertArrayEquals(bom + "- [ ] a\n".toByteArray(), file.readBytes())
    }

    @Test fun lineBreakFarBeforeTheEndIsStillSeen() {
        val longLine = "x".repeat(10_000)
        val file = fileWith("a\r\n$longLine")
        append(file, "- [ ] b")
        // The last 4 KB hold no line break, so the default LF is used.
        assertEquals("a\r\n$longLine\n- [ ] b\n", file.readText())
    }

    @Test fun failedWriteIsUnverified() {
        val file = fileWith("a\n")
        val access = object : RafAccess(file) {
            override fun write(position: Long, bytes: ByteArray) = throw IOException("disk full")
        }
        val result = MarkdownAppend.append(access, "- [ ] b")
        assertTrue(result is AppendResult.Unverified)
    }

    @Test fun writeThatDoesNotStickIsUnverified() {
        val file = fileWith("a\n")
        val access = object : RafAccess(file) {
            override fun write(position: Long, bytes: ByteArray) = super.write(position, bytes.copyOf(bytes.size - 1) + 'X'.code.toByte())
        }
        assertTrue(MarkdownAppend.append(access, "- [ ] b") is AppendResult.Unverified)
    }

    @Test fun unreadableFileIsNotWritten() {
        val file = fileWith("a\n")
        val access = object : RafAccess(file) {
            override fun read(position: Long, count: Int) = throw IOException("gone")
        }
        assertTrue(MarkdownAppend.append(access, "- [ ] b") is AppendResult.NotWritten)
        assertEquals("a\n", file.readText())
    }

    @Test fun fileGrowingWhileSavingIsLeftAlone() {
        val file = fileWith("a\n")
        val access = object : RafAccess(file) {
            var calls = 0
            override fun size() = super.size() + if (calls++ > 0) 5 else 0
        }
        assertTrue(MarkdownAppend.append(access, "- [ ] b") is AppendResult.NotWritten)
        assertEquals("a\n", file.readText())
    }

    @Test fun taskLineFoldsWhitespace() {
        assertEquals("- [ ] Купить хлеб и молоко", TaskLine.format("  Купить хлеб\nи   молоко \r\n"))
        assertNull(TaskLine.format(" \n\t "))
    }
}
