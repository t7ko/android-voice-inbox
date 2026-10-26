package t7helpers.voiceinbox.ui

import android.app.Activity
import android.os.Bundle

/**
 * The "Record" launcher icon and the app shortcut: opens the app straight into recording.
 * It only passes the tap on to [MainActivity] and closes. Living in a task of its own, it
 * reaches MainActivity on every tap, also when the app is already open; a launcher icon of
 * MainActivity itself would just bring the open app to the front.
 */
class RecordActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(MainActivity.recordIntent(this))
        finish()
    }
}
