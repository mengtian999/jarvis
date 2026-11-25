package com.jarvis.app.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Strict Wi-Fi guard used in addition to WorkManager's UNMETERED constraint. */
object WifiNetwork {
    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
}
