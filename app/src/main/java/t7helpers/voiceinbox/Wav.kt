package t7helpers.voiceinbox

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 16 kHz mono 16-bit PCM: what Vosk models expect and what the recorder writes. */
object Wav {
    const val SAMPLE_RATE = 16000
    const val BYTES_PER_SECOND = SAMPLE_RATE * 2
    const val HEADER_SIZE = 44

    /** The header of a PCM WAV file holding [dataSize] bytes of samples. */
    fun header(dataSize: Long): ByteArray = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((36 + dataSize).toInt())
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // size of the fmt chunk
        putShort(1) // PCM
        putShort(1) // mono
        putInt(SAMPLE_RATE)
        putInt(BYTES_PER_SECOND)
        putShort(2) // bytes per sample frame
        putShort(16) // bits per sample
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(dataSize.toInt())
    }.array()

    fun durationMs(dataSize: Long): Long = dataSize * 1000 / BYTES_PER_SECOND

    /**
     * Writes the sizes into the header of a WAV file from [WavWriter] that the app left
     * unfinished, going by the file's length. Returns the size of its samples.
     */
    fun repair(file: File): Long {
        RandomAccessFile(file, "rw").use { out ->
            val dataSize = if (out.length() < HEADER_SIZE) 0L else (out.length() - HEADER_SIZE) and 1L.inv()
            out.setLength(HEADER_SIZE + dataSize)
            out.seek(0)
            out.write(header(dataSize))
            out.fd.sync()
            return dataSize
        }
    }

    /**
     * Loudness of a buffer of samples from 0 (silence, -60 dBFS or less) to 1 (full scale),
     * for the level meter.
     */
    fun level(buffer: ByteArray, length: Int): Float {
        val samples = length / 2
        if (samples == 0) return 0f
        var sum = 0.0
        for (i in 0 until samples) {
            val sample = (buffer[2 * i].toInt() and 0xFF) or (buffer[2 * i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
        }
        val rms = Math.sqrt(sum / samples)
        if (rms < 1) return 0f
        val dbfs = 20 * Math.log10(rms / 32768)
        return ((dbfs + 60) / 60).toFloat().coerceIn(0f, 1f)
    }
}

/**
 * Writes samples to [file] as they come, so a recording is on disk from its first buffer.
 * The header gets its sizes in [finish]; until then it says 0 bytes, and the length of the
 * file tells how much was recorded.
 */
class WavWriter(val file: File) : Closeable {
    private val out = RandomAccessFile(file, "rw").apply {
        setLength(0)
        write(Wav.header(0))
    }

    var dataSize = 0L
        private set

    fun write(buffer: ByteArray, length: Int) {
        out.write(buffer, 0, length)
        dataSize += length
    }

    /** Writes the final header and flushes the file to storage. */
    fun finish() {
        out.seek(0)
        out.write(Wav.header(dataSize))
        out.fd.sync()
        out.close()
    }

    override fun close() = out.close()
}
