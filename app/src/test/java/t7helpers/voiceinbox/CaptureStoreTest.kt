package t7helpers.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CaptureStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun recordSurvivesJson() {
        val record = CaptureRecord(
            id = "20261025-160013-070",
            createdAt = 1_793_000_000_000,
            status = CaptureStatus.FAILED,
            text = "Позвонить \"маме\"\nи папе",
            durationMs = 7100,
            message = "The save could not be confirmed.",
            needsDecision = true,
            pending = PendingWrite("content://x/Inbox.md", "Inbox.md", 1234, "\r\n- [ ] Позвонить\r\n"),
            savedLine = null,
            savedTo = null,
            savedAt = null,
        )
        assertEquals(record, CaptureRecord.fromJson(record.toJson(), hasAudio = false))
        val saved = CaptureRecord("a", 1, CaptureStatus.SAVED, savedLine = "- [ ] x", savedTo = "Inbox.md", savedAt = 5)
        assertEquals(saved, CaptureRecord.fromJson(saved.toJson(), hasAudio = false))
    }

    @Test fun putReplacesAndLoadReads() {
        val store = CaptureStore(temp.newFolder("captures"))
        val record = CaptureRecord("20261025-160000-000", 1, CaptureStatus.RECORDING)
        store.put(record)
        store.put(record.copy(status = CaptureStatus.READY, text = "a"))
        assertEquals(listOf(record.copy(status = CaptureStatus.READY, text = "a")), CaptureStore(store.dir).load())
        assertFalse(File(store.dir, "${record.id}/capture.json.tmp").exists())
    }

    @Test fun newestFirst() {
        val store = CaptureStore(temp.newFolder("captures"))
        for (id in listOf("20261025-100000-000", "20261026-090000-000", "20261025-230000-000")) {
            store.put(CaptureRecord(id, 1, CaptureStatus.READY))
        }
        assertEquals(listOf("20261026-090000-000", "20261025-230000-000", "20261025-100000-000"), store.records.value.map { it.id })
    }

    @Test fun folderWithoutRecordIsAnUnfinishedRecording() {
        val dir = temp.newFolder("captures")
        File(dir, "20261025-160000-000").mkdirs()
        File(dir, "20261025-160000-000/audio.wav").writeBytes(ByteArray(100))
        File(dir, "20261025-170000-000").mkdirs()
        File(dir, "20261025-170000-000/capture.json").writeText("{ broken")
        val loaded = CaptureStore(dir).load()
        assertEquals(listOf(CaptureStatus.RECORDING, CaptureStatus.RECORDING), loaded.map { it.status })
        assertEquals(listOf(false, true), loaded.map { it.hasAudio })
    }

    @Test fun deleteRemovesTheFolder() {
        val store = CaptureStore(temp.newFolder("captures"))
        store.put(CaptureRecord("a", 1, CaptureStatus.READY))
        store.audioFile("a").writeBytes(ByteArray(10))
        store.delete("a")
        assertFalse(File(store.dir, "a").exists())
        assertTrue(store.records.value.isEmpty())
    }

    @Test fun oldAudioGoesOnlyFromSavedCaptures() {
        val store = CaptureStore(temp.newFolder("captures"))
        val records = listOf(
            CaptureRecord("old-saved", 1, CaptureStatus.SAVED, savedAt = 100),
            CaptureRecord("new-saved", 1, CaptureStatus.SAVED, savedAt = 300),
            CaptureRecord("old-unsaved", 1, CaptureStatus.FAILED),
        )
        for (record in records) {
            store.put(record)
            store.audioFile(record.id).writeBytes(ByteArray(10))
        }
        store.load()
        store.deleteOldAudio(savedBefore = 200)
        assertFalse(store.audioFile("old-saved").exists())
        assertTrue(store.audioFile("new-saved").exists())
        assertTrue(store.audioFile("old-unsaved").exists())
        assertEquals(mapOf("old-saved" to false, "new-saved" to true, "old-unsaved" to true), store.records.value.associate { it.id to it.hasAudio })
        assertTrue(File(store.dir, "old-saved/capture.json").exists())
    }

    private fun header(file: File): ByteBuffer =
        ByteBuffer.wrap(file.readBytes(), 0, Wav.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun repairsTheHeaderOfAnUnfinishedWav() {
        val file = temp.newFile("a.wav")
        // What a crash leaves: the header still says 0 bytes, and an odd byte at the end.
        WavWriter(file).apply { write(ByteArray(5001) { 7 }, 5001) }.close()
        assertEquals(0, header(file).getInt(40))
        assertEquals(5000L, Wav.repair(file))
        assertEquals(Wav.HEADER_SIZE + 5000L, file.length())
        assertEquals(5000, header(file).getInt(40))
        assertEquals(36 + 5000, header(file).getInt(4))
        assertEquals(5000, WavFileInput(file, realTime = false).use { input ->
            val buffer = ByteArray(1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
            }
            total
        })
    }

    @Test fun repairsAWavCutInsideItsHeader() {
        val file = temp.newFile("b.wav")
        RandomAccessFile(file, "rw").use { it.write(ByteArray(20)) }
        assertEquals(0L, Wav.repair(file))
        assertEquals(Wav.HEADER_SIZE.toLong(), file.length())
        assertEquals("RIFF", String(file.readBytes(), 0, 4))
    }
}
