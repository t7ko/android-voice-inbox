package t7helpers.voiceinbox

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Where a recording's samples come from: 16 kHz mono 16-bit PCM. */
interface AudioInput : Closeable {
    /** Fills [buffer] with the next samples, waiting for them; returns their byte count, -1 at the end. */
    fun read(buffer: ByteArray): Int
}

class MicInput private constructor(private val audio: AudioRecord) : AudioInput {
    override fun read(buffer: ByteArray): Int {
        val n = audio.read(buffer, 0, buffer.size)
        if (n < 0) throw IOException("The microphone stopped working (error $n).")
        return n
    }

    override fun close() {
        runCatching { audio.stop() }
        audio.release()
    }

    companion object {
        /** Starts the microphone; needs the RECORD_AUDIO permission. The message of the exception is for the user. */
        @SuppressLint("MissingPermission")
        fun start(): MicInput {
            val minBuffer = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw IOException("This phone cannot record 16 kHz audio.")
            val audio = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    Wav.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, Wav.BYTES_PER_SECOND),
                )
            } catch (e: Exception) {
                throw IOException("Could not open the microphone: ${e.message}")
            }
            if (audio.state != AudioRecord.STATE_INITIALIZED) {
                audio.release()
                throw IOException("Could not open the microphone.")
            }
            try {
                audio.startRecording()
            } catch (e: IllegalStateException) {
                audio.release()
                throw IOException("Could not start the microphone: ${e.message}")
            }
            if (audio.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                audio.release()
                throw IOException("The microphone is in use by another app.")
            }
            return MicInput(audio)
        }
    }
}

/**
 * The samples of a 16 kHz mono 16-bit WAV file. With [realTime], they come as fast as from
 * the microphone: debug builds use that to test with recorded speech on the emulator, whose
 * microphone gives silence.
 */
class WavFileInput(file: File, private val realTime: Boolean) : AudioInput {
    private val input: InputStream = file.inputStream().buffered()
    private val started = System.nanoTime()
    private var bytesRead = 0L

    init {
        try {
            skipToSamples(DataInputStream(input))
        } catch (e: IOException) {
            input.close()
            val reason = if (e is EOFException) "it ends early" else e.message
            throw IOException("${file.name} is not a 16 kHz mono 16-bit WAV file: $reason")
        }
    }

    override fun read(buffer: ByteArray): Int {
        val n = input.read(buffer)
        if (n <= 0) return -1
        bytesRead += n
        if (realTime) {
            val dueNanos = started + bytesRead * 1_000_000_000L / Wav.BYTES_PER_SECOND
            val waitMs = (dueNanos - System.nanoTime()) / 1_000_000
            if (waitMs > 0) Thread.sleep(waitMs)
        }
        return n
    }

    override fun close() = input.close()

    /** Reads the RIFF chunks up to the start of the samples, checking the format on the way. */
    private fun skipToSamples(data: DataInputStream) {
        val riff = ByteArray(12).also { data.readFully(it) }
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("no RIFF/WAVE header")
        }
        while (true) {
            val header = ByteArray(8).also { data.readFully(it) }
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
            if (id == "data") return
            val body = ByteArray(size.toInt() + (size and 1).toInt()).also { data.readFully(it) }
            if (id == "fmt ") {
                val fmt = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
                val format = fmt.getShort(0).toInt()
                val channels = fmt.getShort(2).toInt()
                val rate = fmt.getInt(4)
                val bits = fmt.getShort(14).toInt()
                if (format != 1 || channels != 1 || rate != Wav.SAMPLE_RATE || bits != 16) {
                    throw IOException("format $format, $channels channels, $rate Hz, $bits bits")
                }
            }
        }
    }
}
