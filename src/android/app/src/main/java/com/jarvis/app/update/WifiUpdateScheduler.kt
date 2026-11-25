package com.jarvis.app.update

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object WifiUpdateScheduler {
    private const val PERIODIC_WORK = "jarvis-wifi-update-periodic"
    private const val STARTUP_WORK = "jarvis-wifi-update-startup"

    private fun constraints() = Constraints.Builder()
        // UNMETERED is broader than Wi-Fi; WifiNetwork checks TRANSPORT_WIFI
        // again inside the worker before any request is made.
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresBatteryNotLow(true)
        .build()

    fun schedule(context: Context) {
        val manager = WorkManager.getInstance(context.applicationContext)
        val periodic = PeriodicWorkRequestBuilder<WifiUpdateWorker>(24, TimeUnit.HOURS)
            .setConstraints(constraints())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
            .build()
        manager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.KEEP, periodic)

        val startup = OneTimeWorkRequestBuilder<WifiUpdateWorker>()
            .setConstraints(constraints())
            .build()
        manager.enqueueUniqueWork(STARTUP_WORK, ExistingWorkPolicy.KEEP, startup)
    }
}

/** Remembers that a version was already downloaded, avoiding daily re-downloads. */
internal object WifiUpdateState {
    private const val PREFS = "wifi_update"
    private const val KEY_DOWNLOADED_VERSION = "downloaded_version"

    fun wasDownloaded(context: Context, version: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DOWNLOADED_VERSION, null) == version

    fun markDownloaded(context: Context, version: String) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DOWNLOADED_VERSION, version).apply()
    }
}
