package t7helpers.voiceinbox.ui

import android.media.MediaPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Plays one capture's audio at a time in History; used from the main thread. */
object AudioPlayer {
    private var player: MediaPlayer? = null
    private val _playing = MutableStateFlow<String?>(null)

    /** The id of the capture playing, or null. */
    val playing: StateFlow<String?> = _playing

    /** Returns false if the file cannot be played. */
    fun play(id: String, file: File): Boolean {
        stop()
        return try {
            player = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { this@AudioPlayer.stop() }
                prepare()
                start()
            }
            _playing.value = id
            true
        } catch (e: Exception) {
            stop()
            false
        }
    }

    fun stop() {
        player?.let {
            runCatching { it.stop() }
            it.release()
        }
        player = null
        _playing.value = null
    }
}
