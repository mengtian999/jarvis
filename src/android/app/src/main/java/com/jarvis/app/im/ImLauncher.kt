package com.jarvis.app.im

import android.content.Context

/**
 * Routes a deep-link URL to the IM (Flutter) module.
 *
 * 1. Ensures the cached Flutter engine is initialised.
 * 2. Builds the [ImFlutterActivity] intent (which carries the engine id extra
 *    so the activity attaches the cached engine rather than creating a fresh
 *    one).
 * 3. Stuffs the deep-link into an extra that [ImFlutterActivity.forward]
 *    forwards to Dart via MethodChannel.
 */
object ImLauncher {

    const val EXTRA_DEEPLINK = "jarvis.im/deeplink"
    const val EXTRA_SHARE = "jarvis.im/share"

    fun openImDeepLink(context: Context, link: String) {
        ImFlutterEngine.ensure(context)
        val intent = ImFlutterActivity.buildIntent(context).apply {
            putExtra(EXTRA_DEEPLINK, link)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun openImShare(context: Context, link: String) {
        ImFlutterEngine.ensure(context)
        val intent = ImFlutterActivity.buildIntent(context).apply {
            putExtra(EXTRA_SHARE, link)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
