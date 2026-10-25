package t7helpers.voiceinbox

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** User settings in SharedPreferences. */
object Settings {
    private const val TARGET_URI = "targetUri"
    private const val TARGET_NAME = "targetName"

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun targetUri(context: Context): Uri? = prefs(context).getString(TARGET_URI, null)?.let(Uri::parse)

    fun targetName(context: Context): String? = prefs(context).getString(TARGET_NAME, null)

    fun setTarget(context: Context, uri: Uri, name: String) = prefs(context).edit(commit = true) {
        putString(TARGET_URI, uri.toString())
        putString(TARGET_NAME, name)
    }
}
