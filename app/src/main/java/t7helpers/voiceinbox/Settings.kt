package t7helpers.voiceinbox

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** User settings in SharedPreferences. */
object Settings {
    private const val TARGET_URI = "targetUri"
    private const val TARGET_NAME = "targetName"
    private const val AUDIO_DAYS = "audioRetentionDays"

    /** Choices for [audioRetentionDays]; [KEEP_AUDIO] keeps it. */
    val AUDIO_RETENTION_CHOICES = listOf(1, 7, 30, KEEP_AUDIO)
    const val KEEP_AUDIO = -1

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun targetUri(context: Context): Uri? = prefs(context).getString(TARGET_URI, null)?.let(Uri::parse)

    fun targetName(context: Context): String? = prefs(context).getString(TARGET_NAME, null)

    fun setTarget(context: Context, uri: Uri, name: String) = prefs(context).edit(commit = true) {
        putString(TARGET_URI, uri.toString())
        putString(TARGET_NAME, name)
    }

    /** Days the audio of a saved capture is kept; unsaved captures always keep theirs. */
    fun audioRetentionDays(context: Context): Int = prefs(context).getInt(AUDIO_DAYS, 7)

    fun setAudioRetentionDays(context: Context, days: Int) = prefs(context).edit(commit = true) { putInt(AUDIO_DAYS, days) }
}
