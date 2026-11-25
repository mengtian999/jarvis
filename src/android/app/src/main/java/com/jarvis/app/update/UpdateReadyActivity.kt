package com.jarvis.app.update

import android.app.AlertDialog
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.jarvis.app.R
import com.jarvis.app.data.UpdateChecker

/** Notification entry point: installation happens only after explicit confirmation. */
class UpdateReadyActivity : ComponentActivity() {
    private var awaitingInstallPermission = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (UpdateChecker.resumablePendingFile(this) == null) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.update_ready_title)
            .setMessage(R.string.update_ready_message)
            .setNegativeButton(R.string.common_cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.ok) { _, _ -> confirmInstall() }
            .setOnCancelListener { finish() }
            .show()
    }

    override fun onResume() {
        super.onResume()
        if (!awaitingInstallPermission) return
        awaitingInstallPermission = false
        if (UpdateChecker.canInstall(this)) launchInstaller() else finish()
    }

    private fun confirmInstall() {
        if (UpdateChecker.canInstall(this)) {
            launchInstaller()
            return
        }
        awaitingInstallPermission = true
        runCatching { UpdateChecker.openInstallPermissionSettings(this) }
            .onFailure { finish() }
    }

    private fun launchInstaller() {
        val apk = UpdateChecker.resumablePendingFile(this)
        if (apk != null) UpdateChecker.installApk(this, apk)
        finish()
    }
}
