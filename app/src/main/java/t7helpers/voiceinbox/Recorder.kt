package t7helpers.voiceinbox

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.Closeable
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** Joins Vosk's recognized pieces into the text shown to the user. */
object Transcript {
    /** The [field] of a Vosk result such as `{"text" : "..."}` or `{"partial" : "..."}`. */
    fun field(json: String, field: String): String = try {
        JSONObject(json).optString(field)
    } catch (e: Exception) {
        ""
    }

    /** The pieces with single spaces between them, first letter capitalized: Vosk gives lowercase. */
    fun join(pieces: List<String>): String = pieces.map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(" ").replaceFirstChar { it.titlecase() }
}

/** Text from 16 kHz PCM through a Vosk recognizer; used from one thread. */
class Transcriber(model: Model) : Closeable {
    private val recognizer = Recognizer(model, Wav.SAMPLE_RATE.toFloat())
    private val pieces = mutableListOf<String>()

    /** Takes the next samples; returns the text so far, the unfinished last piece included. */
    fun accept(chunk: ByteArray, length: Int): String =
        // True at the end of an utterance, after a pause.
        if (recognizer.acceptWaveForm(chunk, length)) {
            pieces += Transcript.field(recognizer.result, "text")
            Transcript.join(pieces)
        } else {
            Transcript.join(pieces + Transcript.field(recognizer.partialResult, "partial"))
        }

    fun finish(): String {
        pieces += Transcript.field(recognizer.finalResult, "text")
        return Transcript.join(pieces)
    }

    override fun close() = recognizer.close()

    companion object {
        /** The text of a WAV file, as fast as the recognizer goes. */
        fun transcribe(model: Model, file: File): String = Transcriber(model).use { transcriber ->
            WavFileInput(file, realTime = false).use { input ->
                val buffer = ByteArray(Wav.BYTES_PER_SECOND / 10)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    transcriber.accept(buffer, n)
                }
            }
            transcriber.finish()
        }
    }
}

/**
 * One recording. A thread reads the [AudioInput] into [audioFile] and queues the same samples
 * for a second thread that feeds them to Vosk. The audio is on disk from the first buffer,
 * recording starts before the model has loaded, and the text is ready soon after [stop].
 */
class Recorder(
    private val audioFile: File,
    private val loadModel: () -> Model,
    private val maxDurationMs: Long,
    private val listener: Listener,
) {
    interface Listener {
        /** About ten times a second from the recording thread. */
        fun onAudio(elapsedMs: Long, level: Float)

        /** From the recognition thread, after each new piece of text. */
        fun onText(text: String, recognizerReady: Boolean)

        /**
         * Once, when both threads are done and the audio file is complete. [error] says what
         * went wrong with the input or the recognizer; [text] is what was recognized anyway.
         */
        fun onFinished(text: String, durationMs: Long, error: String?)
    }

    @Volatile private var stopRequested = false
    private val queue = LinkedBlockingQueue<ByteArray>()
    @Volatile private var recordError: String? = null
    @Volatile private var durationMs = 0L

    /** Takes over [input] and closes it at the end. Returns an error message if recording cannot start. */
    fun start(input: AudioInput): String? {
        val wav = try {
            audioFile.parentFile?.mkdirs()
            WavWriter(audioFile)
        } catch (e: Exception) {
            input.close()
            return "Could not create the audio file: ${e.message}"
        }
        thread(name = "recorder") { record(input, wav) }
        thread(name = "recognizer") { recognize() }
        return null
    }

    fun stop() {
        stopRequested = true
    }

    private fun record(input: AudioInput, wav: WavWriter) {
        val buffer = ByteArray(Wav.BYTES_PER_SECOND / 10)
        try {
            while (!stopRequested) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                wav.write(buffer, n)
                queue.put(buffer.copyOf(n))
                val elapsed = Wav.durationMs(wav.dataSize)
                listener.onAudio(elapsed, Wav.level(buffer, n))
                if (elapsed >= maxDurationMs) break
            }
        } catch (e: Exception) {
            recordError = e.message ?: "Recording failed: $e"
        } finally {
            runCatching { input.close() }
            try {
                wav.finish()
            } catch (e: Exception) {
                recordError = recordError ?: "Could not finish the audio file: ${e.message}"
            }
            durationMs = Wav.durationMs(wav.dataSize)
            queue.put(END)
        }
    }

    private fun recognize() {
        var text = ""
        var recognizeError: String? = null
        var transcriber: Transcriber? = null
        try {
            transcriber = Transcriber(loadModel())
            listener.onText("", recognizerReady = true)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError too, when the native library does not load.
            recognizeError = "Speech recognition is not available: ${e.message}"
        }
        while (true) {
            val chunk = queue.take()
            if (chunk === END) break
            val t = transcriber ?: continue
            try {
                text = t.accept(chunk, chunk.size)
                listener.onText(text, recognizerReady = true)
            } catch (e: Throwable) {
                recognizeError = "Speech recognition failed: ${e.message}"
                transcriber = null
                runCatching { t.close() }
            }
        }
        transcriber?.let { t ->
            try {
                text = t.finish()
            } catch (e: Throwable) {
                recognizeError = "Speech recognition failed: ${e.message}"
            }
            runCatching { t.close() }
        }
        listener.onFinished(text, durationMs, recordError ?: recognizeError)
    }

    private companion object {
        /** Put in the queue after the last buffer. */
        val END = ByteArray(0)
    }
}
