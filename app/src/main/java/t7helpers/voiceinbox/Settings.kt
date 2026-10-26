package t7helpers.voiceinbox

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** User settings in SharedPreferences. */
object Settings {
    private const val TARGET_URI = "targetUri"
    private const val TARGET_NAME = "targetName"
    private const val AUDIO_DAYS = "audioRetentionDays"
    private const val LANGUAGE = "language"
    private const val AUTO_STOP = "autoStop"
    private const val SILENCE_MS = "silenceMs"
    private const val MAX_SECONDS = "maxSeconds"
    private const val AUTO_SAVE = "autoSave"
    private const val CLOSE_AFTER_AUTO_SAVE = "closeAfterAutoSave"

    /** Choices for [audioRetentionDays]; [KEEP_AUDIO] keeps it. */
    val AUDIO_RETENTION_CHOICES = listOf(1, 7, 30, KEEP_AUDIO)
    const val KEEP_AUDIO = -1
    val SILENCE_CHOICES = listOf(1000, 1500, 2000, 3000, 5000)
    val MAX_SECONDS_CHOICES = listOf(30, 60, 120, 300)

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

    /** The language of new captures, a [ModelInfo.language]. */
    fun language(context: Context): String = prefs(context).getString(LANGUAGE, null) ?: SpeechModel.RUSSIAN.language

    fun setLanguage(context: Context, language: String) = prefs(context).edit(commit = true) { putString(LANGUAGE, language) }

    /** Whether a recording stops by itself after [silenceMs] of silence. */
    fun autoStop(context: Context): Boolean = prefs(context).getBoolean(AUTO_STOP, true)

    fun setAutoStop(context: Context, on: Boolean) = prefs(context).edit(commit = true) { putBoolean(AUTO_STOP, on) }

    fun silenceMs(context: Context): Int = prefs(context).getInt(SILENCE_MS, 2000)

    fun setSilenceMs(context: Context, ms: Int) = prefs(context).edit(commit = true) { putInt(SILENCE_MS, ms) }

    /** The longest recording; it stops there even while someone speaks. */
    fun maxSeconds(context: Context): Int = prefs(context).getInt(MAX_SECONDS, 120)

    fun setMaxSeconds(context: Context, seconds: Int) = prefs(context).edit(commit = true) { putInt(MAX_SECONDS, seconds) }

    /** Whether recognized text is saved without tapping Save. */
    fun autoSave(context: Context): Boolean = prefs(context).getBoolean(AUTO_SAVE, false)

    fun setAutoSave(context: Context, on: Boolean) = prefs(context).edit(commit = true) { putBoolean(AUTO_SAVE, on) }

    /** Whether the app goes back to the home screen after saving by itself what the Record icon started. */
    fun closeAfterAutoSave(context: Context): Boolean = prefs(context).getBoolean(CLOSE_AFTER_AUTO_SAVE, true)

    fun setCloseAfterAutoSave(context: Context, on: Boolean) =
        prefs(context).edit(commit = true) { putBoolean(CLOSE_AFTER_AUTO_SAVE, on) }
}
