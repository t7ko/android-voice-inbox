package t7helpers.voiceinbox

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** A Vosk model the app can download: a zip with one top folder named [name]. */
class ModelInfo(val name: String, val language: String, val url: String, val zipSize: Long, val sha256: String)

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val done: Long, val total: Long) : ModelState
    data object Unpacking : ModelState
    data object Installed : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * The speech model: downloaded once into app storage, the only use of the network, then
 * loaded into memory and kept there for the next capture.
 */
object SpeechModel {
    val RUSSIAN = ModelInfo(
        name = "vosk-model-small-ru-0.22",
        language = "Russian",
        url = "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip",
        zipSize = 46_236_750,
        sha256 = "961d5ff98a17f4aa6de69864d0aa71fa5bac682301d2b5d17a3f24c5c99a46d4",
    )
    val info = RUSSIAN

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<ModelState>(ModelState.Missing)
    val state: StateFlow<ModelState> = _state
    private var loaded: Model? = null

    private fun modelsDir(context: Context) = File(context.filesDir, "models")
    private fun dir(context: Context) = File(modelsDir(context), info.name)

    private val busy get() = _state.value.let { it is ModelState.Downloading || it is ModelState.Unpacking }

    /** Updates [state] from what is on disk. */
    fun refresh(context: Context) {
        if (busy) return
        _state.value = if (dir(context).isDirectory) ModelState.Installed else ModelState.Missing
    }

    /** Downloads, checks and unpacks the model; an interrupted download resumes. */
    fun install(context: Context) {
        if (busy || _state.value == ModelState.Installed) return
        _state.value = ModelState.Downloading(0, info.zipSize)
        val app = context.applicationContext
        scope.launch {
            _state.value = try {
                val models = modelsDir(app).apply { mkdirs() }
                val part = File(models, "${info.name}.zip.part")
                download(part) { _state.value = ModelState.Downloading(it, info.zipSize) }
                if (part.length() != info.zipSize || sha256(part) != info.sha256) {
                    part.delete()
                    throw IOException("The download is damaged. Try again.")
                }
                _state.value = ModelState.Unpacking
                val temp = File(models, "${info.name}.tmp")
                temp.deleteRecursively()
                ModelZip.unpack(part, info.name, temp)
                if (!temp.renameTo(dir(app))) throw IOException("Could not move the model into place.")
                part.delete()
                ModelState.Installed
            } catch (e: UnknownHostException) {
                ModelState.Failed("No connection. Connect to the internet and try again.")
            } catch (e: IOException) {
                ModelState.Failed(e.message ?: e.toString())
            }
            // Ready for the first capture.
            if (_state.value == ModelState.Installed) runCatching { load(app) }
        }
    }

    /** Loads the model into memory in the background, so the first capture has it ready. */
    fun preload(context: Context) {
        if (_state.value != ModelState.Installed) return
        val app = context.applicationContext
        scope.launch { runCatching { load(app) } }
    }

    /** The loaded model; loads it first if needed, which takes a few seconds. */
    @Synchronized
    fun load(context: Context): Model {
        loaded?.let { return it }
        val dir = dir(context)
        if (!dir.isDirectory) throw IOException("The speech model is not installed. Download it in Settings.")
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        return Model(dir.path).also { loaded = it }
    }

    private fun download(part: File, progress: (Long) -> Unit) {
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
