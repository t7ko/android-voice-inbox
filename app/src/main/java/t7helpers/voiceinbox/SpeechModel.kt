package t7helpers.voiceinbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.zip.ZipFile

/**
 * A Vosk model the app can download: a zip with one top folder named [name], for
 * [language] (a short code such as "ru", stored with each capture).
 */
class ModelInfo(
    val language: String,
    val languageName: String,
    val name: String,
    val url: String,
    val zipSize: Long,
    val sha256: String,
)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data object Unpacking : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * The speech models: each downloaded once into app storage, the only use of the network,
 * then loaded into memory when needed and kept there for the next capture.
 */
object SpeechModel {
    val RUSSIAN = ModelInfo(
        language = "ru",
        languageName = "Russian",
        name = "vosk-model-small-ru-0.22",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        zipSize = 46_236_750,
        sha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",
    )
    val ENGLISH = ModelInfo(
        language = "en",
        languageName = "English",
        name = "vosk-model-small-en-us-0.15",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
        zipSize = 41_205_931,
        sha256 = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498",
    )
    val ALL = listOf(RUSSIAN, ENGLISH)

    fun info(language: String) = ALL.find { it.language == language } ?: RUSSIAN

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _states = MutableStateFlow(ALL.associate { it.language to (ModelState.Missing as ModelState) })

    /** By [ModelInfo.language]. */
    val states: StateFlow<Map<String, ModelState>> = _states
    private val loaded = HashMap<String, Model>()

    fun state(language: String): ModelState = _states.value[language] ?: ModelState.Missing

    private fun setState(info: ModelInfo, state: ModelState) = _states.update { it + (info.language to state) }

    private fun modelsDir(context: Context) = File(context.filesDir, "models")
    private fun dir(context: Context, info: ModelInfo) = File(modelsDir(context), info.name)

    private fun busy(info: ModelInfo) = state(info.language).let { it is ModelState.Downloading || it is ModelState.Unpacking }

    /** Updates the states from what is on disk. */
    fun refresh(context: Context) {
        for (info in ALL) {
            if (!busy(info)) setState(info, if (dir(context, info).isDirectory) ModelState.Installed else ModelState.Missing)
        }
    }

    /** Downloads, checks and unpacks a model; an interrupted download resumes. */
    fun install(context: Context, info: ModelInfo) {
        if (busy(info) || state(info.language) == ModelState.Installed) return
        setState(info, ModelState.Downloading(0, info.zipSize))
        val app = context.applicationContext
        scope.launch {
            val result = try {
                val models = modelsDir(app).apply { mkdirs() }
                val part = File(models, "${info.name}.zip.part")
                download(info, part) { setState(info, ModelState.Downloading(it, info.zipSize)) }
                if (part.length() != info.zipSize || sha256(part) != info.sha256) {
                    part.delete()
                    throw IOException("The download is damaged. Try again.")
                }
                setState(info, ModelState.Unpacking)
                val temp = File(models, "${info.name}.tmp")
                temp.deleteRecursively()
                ModelZip.unpack(part, info.name, temp)
                if (!temp.renameTo(dir(app, info))) throw IOException("Could not move the model into place.")
                part.delete()
                ModelState.Installed
            } catch (e: UnknownHostException) {
                ModelState.Failed("No connection. Connect to the internet and try again.")
            } catch (e: IOException) {
                ModelState.Failed(e.message ?: e.toString())
            }
            setState(info, result)
            // Ready for the first capture.
            if (result == ModelState.Installed && info.language == Settings.language(app)) runCatching { load(app, info.language) }
        }
    }

    /** Loads a model into memory in the background, so the next capture has it ready. */
    fun preload(context: Context, language: String) {
        if (state(language) != ModelState.Installed) return
        val app = context.applicationContext
        scope.launch { runCatching { load(app, language) } }
    }

    /** The loaded model for [language]; loads it first if needed, which takes a few seconds. */
    @Synchronized
    fun load(context: Context, language: String): Model {
        loaded[language]?.let { return it }
        val info = info(language)
        val dir = dir(context, info)
        if (!dir.isDirectory) throw IOException("The ${info.languageName} speech model is not installed. Download it in Settings.")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val started = System.currentTimeMillis()
        return Model(dir.path).also {
            loaded[language] = it
            Log.i("VoiceInbox", "Model ${info.name} loaded in ${System.currentTimeMillis() - started} ms")
        }
    }

    private fun download(info: ModelInfo, part: File, progress: (Long) -> Unit) {
        if (part.length() > info.zipSize) part.delete()
        var done = part.length()
        if (done == info.zipSize) return
        val connection = URL(info.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        if (done > 0) connection.setRequestProperty("Range", "bytes=$done-")
        try {
            val resume = when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("The download server answered HTTP $code.")
            }
            if (!resume) done = 0
            FileOutputStream(part, resume).use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var reported = done
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (done - reported >= 512 * 1024) {
                            progress(done)
                            reported = done
                        }
                    }
                }
            }
            progress(done)
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

object ModelZip {
    /**
     * Unpacks the files under [topDir]/ in [zip] into [target], refusing paths that would
     * land outside it.
     */
    fun unpack(zip: File, topDir: String, target: File) {
        val prefix = "$topDir/"
        ZipFile(zip).use { archive ->
            for (entry in archive.entries()) {
                val name = entry.name.replace('\\', '/')
                if (!name.startsWith(prefix)) throw IOException("Unexpected file in the model archive: $name")
                val relative = name.removePrefix(prefix)
                if (relative.isEmpty()) continue
                if (relative.startsWith("/") || relative.split('/').any { it == ".." }) {
                    throw IOException("Bad path in the model archive: $name")
                }
                val file = File(target, relative)
                if (entry.isDirectory) {
                    file.mkdirs()
                    continue
                }
                file.parentFile?.mkdirs()
                archive.getInputStream(entry).use { input -> file.outputStream().use { input.copyTo(it) } }
            }
        }
        if (!File(target, "am/final.mdl").isFile) throw IOException("The archive holds no Vosk model.")
    }
}
