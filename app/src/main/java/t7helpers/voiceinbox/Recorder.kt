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

    /** The confidence (0 to 1) of each word of a Vosk result made with `setWords(true)`. */
    fun confidences(json: String): List<Double> = try {
        val words = JSONObject(json).optJSONArray("result")
        if (words == null) emptyList() else List(words.length()) { words.getJSONObject(it).getDouble("conf") }
    } catch (e: Exception) {
        emptyList()
    }

    /** The pieces with single spaces between them, first letter capitalized: Vosk gives lowercase. */
    fun join(pieces: List<String>): String = pieces.map { it.trim() }.filter { it.isNotEmpty() }
        .joinToString(" ").replaceFirstChar { it.titlecase() }
}

/**
 * Text from 16 kHz PCM through a Vosk recognizer; used from one thread. With [silenceSec],
 * Vosk ends an utterance after that much silence following speech (up to a second more
 * when it is unsure the speech ended); an utterance longer than [maxSec] is cut.
 */
class Transcriber(model: Model, silenceSec: Float? = null, maxSec: Float = 20f) : Closeable {
    private val recognizer = Recognizer(model, Wav.SAMPLE_RATE.toFloat()).apply {
        if (silenceSec != null) setEndpointerDelays(INITIAL_SILENCE_SEC, silenceSec, maxSec)
        // Finished pieces then come with each word's confidence.
        setWords(true)
    }
    private val pieces = mutableListOf<String>()
    private val confidences = mutableListOf<Double>()

    /** The mean confidence of the finished words, 0 to 1; null without words. */
    val confidence: Double? get() = if (confidences.isEmpty()) null else confidences.average()

    /** Whether the last [accept] ended an utterance. */
    var utteranceEnded = false
        private set

    /** Takes the next samples; returns the text so far, the unfinished last piece included. */
    fun accept(chunk: ByteArray, length: Int): String {
        utteranceEnded = recognizer.acceptWaveForm(chunk, length)
        return if (utteranceEnded) {
            addPiece(recognizer.result)
            Transcript.join(pieces)
        } else {
            Transcript.join(pieces + Transcript.field(recognizer.partialResult, "partial"))
        }
    }

    fun finish(): String {
        addPiece(recognizer.finalResult)
        return Transcript.join(pieces)
    }

    private fun addPiece(json: String) {
        pieces += Transcript.field(json, "text")
        confidences += Transcript.confidences(json)
    }

    override fun close() = recognizer.close()

    companion object {
        private const val INITIAL_SILENCE_SEC = 10f

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

/** Why a recording ended. */
enum class StopReason {
    /** The Stop button, or the app leaving the screen. */
    USER,

    /** [AutoStop]: the pause after speech. */
    SILENCE,

    /** [AutoStop]: nothing said for a long time. */
    NO_SPEECH,

    /** The longest recording from Settings. */
    LIMIT,

    /** The input ended, or failed. */
    END,
}

/**
 * Whether recognized text may go into the file without a look. Measured on clean
 * recordings: real speech scored 0.96 to 1.0, English through the Russian model 0.80, a
 * word made up from background noise 0.68.
 */
object AutoSave {
    const val MIN_CONFIDENCE = 0.85

    /** Why a capture with text is not saved by itself, or null to save it. */
    fun hold(reason: StopReason, confidence: Double?): String? = when {
        reason == StopReason.LIMIT -> "Not saved automatically: the recording reached its time limit. Check the text and tap Save."
        reason != StopReason.USER && reason != StopReason.SILENCE ->
            "Not saved automatically: no clear speech. Check the text and tap Save."
        confidence == null || confidence < MIN_CONFIDENCE ->
            "Not saved automatically: the recognizer is unsure of these words. Check them and tap Save."
        else -> null
    }
}

/**
 * When a recording stops by itself: at the first end of an utterance once something was
 * recognized (Vosk ends one after the set silence), or after [noSpeechMs] of audio with
 * nothing recognized.
 */
class AutoStop(val silenceMs: Long, private val noSpeechMs: Long = 10_000) {
    fun shouldStop(utteranceEnded: Boolean, text: String, audioMs: Long): Boolean =
        if (text.isBlank()) audioMs >= noSpeechMs else utteranceEnded
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
    private val autoStop: AutoStop?,
    private val listener: Listener,
) {
    interface Listener {
        /** About ten times a second from the recording thread. */
        fun onAudio(elapsedMs: Long, level: Float)

        /** From the recognition thread, after each new piece of text. */
        fun onText(text: String, recognizerReady: Boolean)

        /** From the recognition thread, when [AutoStop] ends the recording. */
        fun onAutoStop()

        /**
         * Once, when both threads are done and the audio file is complete. [error] says what
         * went wrong with the input or the recognizer; [text] is what was recognized anyway,
         * with [Transcriber.confidence].
         */
        fun onFinished(text: String, durationMs: Long, error: String?, reason: StopReason, confidence: Double?)
    }

    @Volatile private var stopRequested = false
    @Volatile private var stopReason: StopReason? = null
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
        if (stopReason == null) stopReason = StopReason.USER
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
                if (elapsed >= maxDurationMs) {
                    if (stopReason == null) stopReason = StopReason.LIMIT
                    break
                }
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
            // Longer utterances than the recording itself must not be cut.
            transcriber = Transcriber(loadModel(), autoStop?.let { it.silenceMs / 1000f }, maxDurationMs / 1000f + 5)
            listener.onText("", recognizerReady = true)
        } catch (e: Throwable) {
            // UnsatisfiedLinkError too, when the native library does not load.
            recognizeError = "Speech recognition is not available: ${e.message}"
        }
        var audioMs = 0L
        while (true) {
            val chunk = queue.take()
            if (chunk === END) break
            val t = transcriber ?: continue
            try {
                text = t.accept(chunk, chunk.size)
                audioMs += Wav.durationMs(chunk.size.toLong())
                listener.onText(text, recognizerReady = true)
                if (autoStop != null && !stopRequested && autoStop.shouldStop(t.utteranceEnded, text, audioMs)) {
                    stopReason = if (text.isBlank()) StopReason.NO_SPEECH else StopReason.SILENCE
                    stopRequested = true
                    listener.onAutoStop()
                }
            } catch (e: Throwable) {
                recognizeError = "Speech recognition failed: ${e.message}"
                transcriber = null
                runCatching { t.close() }
            }
        }
        var confidence: Double? = null
        transcriber?.let { t ->
            try {
                text = t.finish()
                confidence = t.confidence
            } catch (e: Throwable) {
                recognizeError = "Speech recognition failed: ${e.message}"
            }
            runCatching { t.close() }
        }
        listener.onFinished(text, durationMs, recordError ?: recognizeError, stopReason ?: StopReason.END, confidence)
    }

    private companion object {
        /** Put in the queue after the last buffer. */
        val END = ByteArray(0)
    }
}
