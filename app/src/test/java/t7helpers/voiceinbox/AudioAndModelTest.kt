package t7helpers.voiceinbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AudioAndModelTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun wavWriterFixesSizesOnFinish() {
        val file = temp.newFile("a.wav")
        val writer = WavWriter(file)
        writer.write(ByteArray(3200) { 1 }, 3200)
        writer.write(ByteArray(3200) { 2 }, 1600)
        writer.finish()
        val bytes = file.readBytes()
        assertEquals(Wav.HEADER_SIZE + 4800, bytes.size)
        val header = ByteBuffer.wrap(bytes, 0, Wav.HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4))
        assertEquals(36 + 4800, header.getInt(4))
        assertEquals(16000, header.getInt(24))
        assertEquals("data", String(bytes, 36, 4))
        assertEquals(4800, header.getInt(40))
        assertEquals(150L, Wav.durationMs(4800))
    }

    private fun readAll(input: AudioInput): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1000)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        input.close()
        return out.toByteArray()
    }

    @Test fun wavFileInputReadsWhatWavWriterWrote() {
        val file = temp.newFile("b.wav")
        val samples = ByteArray(5000) { it.toByte() }
        WavWriter(file).apply { write(samples, samples.size) }.finish()
        assertTrue(readAll(WavFileInput(file, realTime = false)).contentEquals(samples))
    }

    /** Like ffmpeg's output: a LIST chunk between fmt and data. */
    private fun wavWithListChunk(rate: Int, samples: ByteArray): File {
        val fmt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16).array()
        val list = "INFOISFT\u0005\u0000\u0000\u0000Lavf\u0000\u0000".toByteArray(Charsets.ISO_8859_1) // odd size, padded
        val out = java.io.ByteArrayOutputStream()
        fun chunk(id: String, body: ByteArray, size: Int = body.size) {
            out.write(id.toByteArray())
            out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(size).array())
            out.write(body)
        }
        out.write("RIFF".toByteArray())
        out.write(ByteArray(4))
        out.write("WAVE".toByteArray())
        chunk("fmt ", fmt)
        chunk("LIST", list, size = list.size - 1)
        chunk("data", samples)
        return temp.newFile().apply { writeBytes(out.toByteArray()) }
    }

    @Test fun wavFileInputSkipsOtherChunks() {
        val samples = ByteArray(321) { (it * 7).toByte() }
        assertTrue(readAll(WavFileInput(wavWithListChunk(16000, samples), realTime = false)).contentEquals(samples))
    }

    @Test fun wavFileInputRefusesOtherFormats() {
        assertFails { WavFileInput(wavWithListChunk(44100, ByteArray(10)), realTime = false) }
        assertFails { WavFileInput(temp.newFile().apply { writeText("RIFF") }, realTime = false) }
    }

    @Test fun level() {
        assertEquals(0f, Wav.level(ByteArray(3200), 3200))
        val loud = ByteBuffer.allocate(3200).order(ByteOrder.LITTLE_ENDIAN)
        repeat(1600) { loud.putShort(if (it % 2 == 0) 32767 else -32767) }
        assertEquals(1f, Wav.level(loud.array(), 3200), 0.01f)
    }

    @Test fun transcript() {
        assertEquals("купить молоко", Transcript.field("{\n  \"text\" : \"купить молоко\"\n}", "text"))
        assertEquals("", Transcript.field("not json", "text"))
        assertEquals("Купить молоко и хлеб", Transcript.join(listOf("купить молоко", "", " и хлеб ")))
        assertEquals("", Transcript.join(listOf("", " ")))
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = temp.newFile()
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, content) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(content.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test fun unpacksModelWithoutItsTopFolder() {
        val archive = zip("m/" to "", "m/am/final.mdl" to "model", "m/conf/model.conf" to "conf")
        val target = File(temp.root, "out")
        ModelZip.unpack(archive, "m", target)
        assertEquals("model", File(target, "am/final.mdl").readText())
        assertEquals("conf", File(target, "conf/model.conf").readText())
    }

    @Test fun refusesPathsOutsideTheTarget() {
        val archive = zip("m/am/final.mdl" to "model", "m/../../evil" to "x")
        assertFails { ModelZip.unpack(archive, "m", File(temp.root, "out")) }
        assertTrue(!File(temp.root.parentFile, "evil").exists())
    }

    @Test fun refusesOtherArchives() {
        assertFails { ModelZip.unpack(zip("other/am/final.mdl" to "x"), "m", File(temp.root, "out1")) }
        assertFails { ModelZip.unpack(zip("m/readme" to "x"), "m", File(temp.root, "out2")) }
    }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected an IOException")
        } catch (e: IOException) {
            // expected
        }
    }
}
