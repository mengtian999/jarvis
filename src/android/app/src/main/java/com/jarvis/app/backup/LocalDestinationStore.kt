package com.jarvis.app.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.provider.DocumentsContract
import com.jarvis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-android-local-destination] User-picked LOCAL folders as backup
 * destinations, the peer of the rclone remote list.
 *
 * iOS delivers a finished package into mounted folders backed by the Files
 * app; Android's equivalent is a SAF tree the user picks once with
 * `ActivityResultContracts.OpenDocumentTree`. The tree URI is persisted with
 * `takePersistableUriPermission`, so Downloads, Documents, or an SD card all
 * work with no re-pick after relaunch — the same "check it once, every backup
 * after that is automatic" model the remote list has.
 *
 * Deliberately stores only the URI, a display name and the enabled flag. The
 * resolved document tree, writability and copy all stay with
 * [BackupViewModel.deliverToLocalFolders] — a permission the system revoked is
 * a DELIVERY failure to report, not a reason to drop the destination from the
 * list (disabling is the user's own action, mirroring the remote toggle).
 */
object LocalDestinationStore {

    private const val TAG = "Backup"
    private const val PREFS = "backup_local_destinations"
    private const val KEY_FOLDERS = "folders"

    data class LocalFolder(
        val uri: Uri,
        val name: String,
        val enabled: Boolean,
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun folders(context: Context): List<LocalFolder> {
        val raw = prefs(context).getString(KEY_FOLDERS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                LocalFolder(
                    uri = Uri.parse(o.getString("uri")),
                    name = o.optString("name").ifEmpty { "Folder" },
                    enabled = o.optBoolean("enabled", true),
                )
            }
        } catch (e: Exception) {
            // Corrupt JSON must not take the whole destinations list down.
            AppLogger.error(TAG, "[Backup] local destinations unreadable: ${e.message}")
            emptyList()
        }
    }

    fun enabledFolders(context: Context): List<LocalFolder> =
        folders(context).filter { it.enabled }

    /**
     * Register a SAF tree as a destination and enable it. Returns false when
     * the URI carries no persistable grant (some pickers return plain document
     * URIs) or the tree is already registered — the caller treats both as a
     * no-op rather than an error.
     */
    fun add(context: Context, treeUri: Uri): Boolean {
        try {
            context.contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        } catch (e: SecurityException) {
            AppLogger.error(TAG, "[Backup] no persistable grant for $treeUri: ${e.message}")
            return false
        }
        val current = folders(context)
        if (current.any { it.uri == treeUri }) return false
        val updated = current + LocalFolder(treeUri, displayName(context, treeUri), true)
        save(context, updated)
        AppLogger.info(TAG, "[Backup] local destination added: ${updated.last().name}")
        return true
    }

    fun setEnabled(context: Context, uri: Uri, on: Boolean) {
        save(context, folders(context).map {
            if (it.uri == uri) it.copy(enabled = on) else it
        })
    }

    fun remove(context: Context, uri: Uri) {
        save(context, folders(context).filter { it.uri != uri })
    }

    /** Directory display name from the tree's root document; "Folder" fallback. */
    fun displayName(context: Context, treeUri: Uri): String =
        try {
            val docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri),
            )
            context.contentResolver.query(
                docUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "Folder"
        } catch (e: Exception) {
            "Folder"
        }

    private fun save(context: Context, folders: List<LocalFolder>) {
        val arr = JSONArray()
        folders.forEach {
            arr.put(
                JSONObject()
                    .put("uri", it.uri.toString())
                    .put("name", it.name)
                    .put("enabled", it.enabled),
            )
        }
        prefs(context).edit().putString(KEY_FOLDERS, arr.toString()).apply()
    }
}
