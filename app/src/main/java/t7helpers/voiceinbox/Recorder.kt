package t7helpers.voiceinbox

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
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
        val pieces = mutableListOf<String>()
        var recognizeError: String? = null
        var recognizer: Recognizer? = null
        try {
            recognizer = Recognizer(loadModel(), Wav.SAMPLE_RATE.toFloat())
            listener.onText("", recognizerReady = true)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError too, when the native library does not load.
            recognizeError = "Speech recognition is not available: ${e.message}"
        }
        while (true) {
            val chunk = queue.take()
            if (chunk === END) break
            val r = recognizer ?: continue
            try {
                // True at the end of an utterance, after a pause.
                if (r.acceptWaveForm(chunk, chunk.size)) {
                    pieces += Transcript.field(r.result, "text")
                    listener.onText(Transcript.join(pieces), recognizerReady = true)
                } else {
                    listener.onText(Transcript.join(pieces + Transcript.field(r.partialResult, "partial")), recognizerReady = true)
                }
            } catch (e: Throwable) {
                recognizeError = "Speech recognition failed: ${e.message}"
                recognizer = null
                runCatching { r.close() }
            }
        }
        recognizer?.let { r ->
            try {
                pieces += Transcript.field(r.finalResult, "text")
            } catch (e: Throwable) {
                recognizeError = "Speech recognition failed: ${e.message}"
            }
            runCatching { r.close() }
        }
        listener.onFinished(Transcript.join(pieces), durationMs, recordError ?: recognizeError)
    }

    private companion object {
        /** Put in the queue after the last buffer. */
        val END = ByteArray(0)
    }
}
