package com.jarvis.app.update

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jarvis.app.data.PendingUpdateStore
import com.jarvis.app.data.UpdateChecker
import com.jarvis.app.logging.AppLogger

/** Downloads at most one APK per version, only after both network guards pass. */
class WifiUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!WifiNetwork.isConnected(applicationContext)) return Result.success()
        return when (val check = UpdateChecker.check()) {
            is UpdateChecker.CheckResult.UpdateAvailable -> download(check)
            UpdateChecker.CheckResult.NetworkUnreachable -> retryOrFinish("network unreachable")
            is UpdateChecker.CheckResult.Error -> retryOrFinish(check.message)
            else -> Result.success()
        }
    }

    private suspend fun download(update: UpdateChecker.CheckResult.UpdateAvailable): Result {
        if (WifiUpdateState.wasDownloaded(applicationContext, update.versionName)) {
            return Result.success()
        }

        // A manual download may already have staged the same APK. Reuse its
        // verified bytes rather than consuming another 60+ MB.
        val pending = PendingUpdateStore.getPending(applicationContext)
        if (pending?.targetVersionName == update.versionName &&
            PendingUpdateStore.verify(pending) != null
        ) {
            PendingUpdateStore.markAutoDownloaded(applicationContext)
            WifiUpdateState.markDownloaded(applicationContext, update.versionName)
            UpdateReadyNotifier.show(applicationContext)
            return Result.success()
        }

        if (!WifiNetwork.isConnected(applicationContext)) return retryOrFinish("Wi-Fi lost")
        return when (val result = UpdateChecker.download(
            context = applicationContext,
            url = update.apkUrl,
            versionName = update.versionName,
        )) {
            is UpdateChecker.DownloadResult.Success -> {
                PendingUpdateStore.markAutoDownloaded(applicationContext)
                WifiUpdateState.markDownloaded(applicationContext, update.versionName)
                UpdateReadyNotifier.show(applicationContext)
                Result.success()
            }
            is UpdateChecker.DownloadResult.Error -> retryOrFinish(result.message)
        }
    }

    private fun retryOrFinish(reason: String): Result {
        AppLogger.warning(TAG, "Wi-Fi update attempt failed: $reason (attempt=$runAttemptCount)")
        return if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.success()
    }

    companion object {
        private const val TAG = "WifiUpdateWorker"
        private const val MAX_RETRIES = 2
    }
}
