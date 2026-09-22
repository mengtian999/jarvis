package com.jarvis.app.im

import android.os.Bundle
import androidx.activity.ComponentActivity
import com.jarvis.app.logging.AppLogger
import com.jarvis.app.share.ImLinkPolicy

/**
 * Exported trampoline Activity that catches IM deep-link intent-filters
 * (bitjarvis.chat / matrix.to / matrix: / im.bitjarvis://) and routes them to
 * [ImLauncher].
 *
 * [ImFlutterActivity] must NOT carry the intent-filters directly: when the OS
 * launches a FlutterActivity cold (no cached engine), FlutterActivity creates
 * a new engine because the `flutter_engine_id` extra is missing. The trampoline
 * keeps [ImFlutterActivity] non-exported and lets us ensure the engine first.
 *
 * Theme is NoDisplay + excludeFromRecents so the Activity never appears on
 * screen; it just relays the intent and finishes.
 */
class ImDeepLinkActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ImDeepLink"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val link = intent?.getStringExtra(ImLauncher.EXTRA_DEEPLINK)
            ?: intent?.dataString?.takeIf { ImLinkPolicy.isImLink(it) }
        try {
            if (link != null) {
                ImLauncher.openImDeepLink(this, link)
            } else {
                AppLogger.warning(TAG, "no IM link found in intent")
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "route failed: ${t.message}")
        }
        finish()
    }
}
