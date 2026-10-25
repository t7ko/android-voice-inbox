package t7helpers.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** Saving a capture without losing it or adding it twice, across failures and restarts. */
class TaskSaverTest {
    @get:Rule val temp = TemporaryFolder()

    private val uri = "content://test/Inbox.md"
    private val record = CaptureRecord("20261025-160000-000", 0, CaptureStatus.READY, text = "Купить молоко")

    private fun fileWith(content: String) = temp.newFile().apply { writeText(content) }

    /** Every record [TaskSaver] stores, with the file's content at that moment. */
    private class Persisted(val file: File) : (CaptureRecord) -> Unit {
        val records = mutableListOf<Pair<CaptureRecord, String>>()
        override fun invoke(record: CaptureRecord) {
            records += record to file.readText()
        }
    }

    private fun save(file: File, record: CaptureRecord, access: FileAccess = RafAccess(file)): Pair<CaptureRecord, Persisted> {
        val persisted = Persisted(file)
        val result = TaskSaver.append(record, access, uri, "Inbox.md", persisted)
        (access as RafAccess).raf.close()
        return result to persisted
    }

    /** What recovery or a retry makes of [record]'s unconfirmed write. */
    private fun resolve(file: File, record: CaptureRecord): CaptureRecord {
        val pending = record.pending!!
        val access = RafAccess(file)
        val check = MarkdownAppend.check(access, pending.offset, pending.text.toByteArray())
        access.raf.close()
        return TaskSaver.resolve(record, check, null, "not saved")
    }

    @Test fun savingStoresTheWriteBeforeMakingIt() {
        val file = fileWith("# Inbox\n")
        val (result, persisted) = save(file, record)
        assertEquals(CaptureStatus.SAVED, result.status)
        assertEquals("- [ ] Купить молоко", result.savedLine)
        assertEquals("Inbox.md", result.savedTo)
        assertNull(result.pending)
        assertEquals("# Inbox\n- [ ] Купить молоко\n", file.readText())
        // Stored once, as SAVING, while the file was still untouched.
        val (saving, contentThen) = persisted.records.single()
        assertEquals(CaptureStatus.SAVING, saving.status)
        assertEquals("# Inbox\n", contentThen)
        assertEquals(PendingWrite(uri, "Inbox.md", 8, "- [ ] Купить молоко\n"), saving.pending)
    }

    @Test fun emptyTextIsNotSaved() {
        val file = fileWith("a\n")
        val (result, persisted) = save(file, record.copy(text = " \n "))
        assertEquals(CaptureStatus.FAILED, result.status)
        assertTrue(persisted.records.isEmpty())
        assertEquals("a\n", file.readText())
    }

    @Test fun nothingIsWrittenWhenTheRecordCannotBeStored() {
        val file = fileWith("a\n")
        val access = RafAccess(file)
        val result = TaskSaver.append(record, access, uri, "Inbox.md") { throw IOException("disk full") }
        access.raf.close()
        assertEquals(CaptureStatus.FAILED, result.status)
        assertNull(result.pending)
        assertEquals("a\n", file.readText())
    }

    @Test fun unreadableFileFailsWithoutPendingWrite() {
        val file = fileWith("a\n")
        val (result, persisted) = save(file, record, object : RafAccess(file) {
            override fun size(): Long = throw IOException("gone")
        })
        assertEquals(CaptureStatus.FAILED, result.status)
        assertNull(result.pending)
        assertTrue(persisted.records.isEmpty())
    }

    @Test fun retryAfterAnUnconfirmedWriteThatWorkedAddsNothing() {
        val file = fileWith("a\n")
        // The bytes land, but reading them back fails.
        val (first, _) = save(file, record, object : RafAccess(file) {
            var wrote = false
            override fun write(position: Long, bytes: ByteArray) = super.write(position, bytes).also { wrote = true }
            override fun read(position: Long, count: Int): ByteArray =
                if (wrote) throw IOException("read failed") else super.read(position, count)
        })
        assertEquals(CaptureStatus.FAILED, first.status)
        assertNotNull(first.pending)
        val resolved = resolve(file, first)
        assertEquals(CaptureStatus.SAVED, resolved.status)
        assertEquals("- [ ] Купить молоко", resolved.savedLine)
        assertEquals("a\n- [ ] Купить молоко\n", file.readText())
    }

    @Test fun retryAfterAnUnconfirmedWriteThatFailedWritesOnce() {
        val file = fileWith("a\n")
        val (first, _) = save(file, record, object : RafAccess(file) {
            override fun write(position: Long, bytes: ByteArray) = throw IOException("no space")
        })
        assertNotNull(first.pending)
        val resolved = resolve(file, first)
        assertEquals(CaptureStatus.FAILED, resolved.status)
        assertNull(resolved.pending)
        assertFalse(resolved.needsDecision)
        val (second, _) = save(file, resolved)
        assertEquals(CaptureStatus.SAVED, second.status)
        assertEquals("a\n- [ ] Купить молоко\n", file.readText())
    }

    @Test fun anEarlierCaptureWithTheSameTextIsNotMistakenForThisOne() {
        val file = fileWith("- [ ] Купить молоко\n")
        val (first, _) = save(file, record, object : RafAccess(file) {
            override fun write(position: Long, bytes: ByteArray) = throw IOException("no space")
        })
        // The file ends with the same line, but it is exactly as long as before the write.
        assertEquals(CaptureStatus.FAILED, resolve(file, first).status)
        assertNull(resolve(file, first).pending)
    }

    @Test fun linesAddedAfterOursStillFindIt() {
        val file = fileWith("a\n")
        val pending = PendingWrite(uri, "Inbox.md", 2, "- [ ] Купить молоко\n")
        file.appendText("- [ ] Купить молоко\n- [ ] other\n")
        assertEquals(CaptureStatus.SAVED, resolve(file, record.copy(status = CaptureStatus.SAVING, pending = pending)).status)
    }

    @Test fun ourLineMovedLeavesItToTheUser() {
        val pending = PendingWrite(uri, "Inbox.md", 2, "- [ ] Купить молоко\n")
        // Someone added a line at the top after the write: the line is there, elsewhere.
        val file = fileWith("new\na\n- [ ] Купить молоко\n")
        val resolved = resolve(file, record.copy(status = CaptureStatus.SAVING, pending = pending))
        assertEquals(CaptureStatus.FAILED, resolved.status)
        assertTrue(resolved.needsDecision)
        assertNotNull(resolved.pending)
    }

    @Test fun fileChangedWithoutOurLineIsSafeToWrite() {
        val pending = PendingWrite(uri, "Inbox.md", 2, "- [ ] Купить молоко\n")
        val file = fileWith("a\nedited meanwhile\n")
        val resolved = resolve(file, record.copy(status = CaptureStatus.SAVING, pending = pending))
        assertEquals(CaptureStatus.FAILED, resolved.status)
        assertFalse(resolved.needsDecision)
        assertNull(resolved.pending)
        assertEquals("not saved", resolved.message)
    }

    @Test fun uncheckableFileLeavesItToTheUser() {
        val pending = PendingWrite(uri, "Inbox.md", 2, "x\n")
        val resolved = TaskSaver.resolve(record.copy(pending = pending), null, "no permission", null)
        assertTrue(resolved.needsDecision)
        assertTrue(resolved.message!!.contains("no permission"))
    }
}
