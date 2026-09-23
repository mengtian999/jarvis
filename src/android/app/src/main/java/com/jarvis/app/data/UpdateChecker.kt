package com.jarvis.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.jarvis.app.BuildConfig
import com.jarvis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub / Gitee releases for an APK newer than [BuildConfig.VERSION_NAME]
 * and coordinates download → install. iOS has no equivalent (sideloading is
 * not permitted) so this is Android-only.
 *
 * Source strategy: Gitee first (fast in CN, 3 s timeout), then GitHub as
 * fallback. Both APIs return the same shape (`tag_name`, `assets[].browser_download_url`),
 * so parsing is shared.
 *
 * Comparison strategy: strip a leading `v` from `tag_name`, then split both
 * the tag and the local versionName on `.` and compare numerically component
 * by component. A tag like `v1.0.1` beats local `1.0.0`; `v1.0.0-rc1` beats
 * `1.0.0` because the suffix sorts higher under string fallback.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val GH_OWNER = "mengtian999"
    private const val GH_REPO = "jarvis"
    private const val GITEE_OWNER = "dreamsky111333"
    private const val GITEE_REPO = "jarvis"
    private const val DOWNLOAD_FILENAME = "jarvis-update.apk"
    private const val GITEE_TIMEOUT_MS = 3000L
    /**
     * Sub-directory of `filesDir` where we stage downloaded update APKs. We
     * moved off `cacheDir/shared/` (the original location) so the OS can't
     * evict a freshly-downloaded APK between the moment we hand the user off
     * to "install unknown apps" settings and the moment they return — the
     * eviction was a contributing factor to the "re-download after grant"
     * bug. See [PendingUpdateStore]. Exposed via `file_provider_paths.xml`
     * `<files-path name="updates" path="updates/" />`.
     */
    private const val UPDATES_DIR = "updates"

    sealed class CheckResult {
        data class UpdateAvailable(
            val tagName: String,
            val versionName: String,
            val releaseName: String,
            val changelog: String,
            val apkUrl: String,
            val apkSizeBytes: Long,
        ) : CheckResult()
        data object UpToDate : CheckResult()
        data object NoReleaseAvailable : CheckResult()
        data class NoApkAsset(val tagName: String) : CheckResult()
        data class Error(val message: String) : CheckResult()
        // GitHub returned 403 / 451 — usually a geo-block or rate-limit in CN
        // without a VPN. UI surfaces a hint with a clickable Releases link.
        data object Forbidden : CheckResult()
        data object NetworkUnreachable : CheckResult()
    }

    sealed class DownloadResult {
        data class Success(val file: File) : DownloadResult()
        data class Error(val message: String) : DownloadResult()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Gitee first (fast in CN, 3 s timeout), then GitHub as fallback.
     * Both APIs return the same JSON shape, so parsing is shared via
     * [parseReleases].
     */
    suspend fun check(): CheckResult = withContext(Dispatchers.IO) {
        val giteeResult = withTimeoutOrNull(GITEE_TIMEOUT_MS) {
            runCatching { checkFromGitee() }.getOrNull()
        }
        if (giteeResult != null) {
            AppLogger.info(TAG, "Using Gitee release data")
            return@withContext giteeResult
        }
        AppLogger.info(TAG, "Gitee check failed/timeout, falling back to GitHub")
        checkFromGitHub()
    }

    /** Gitee API v5 — returns null on any failure so caller can fall back. */
    private suspend fun checkFromGitee(): CheckResult = withContext(Dispatchers.IO) {
        val url = "https://gitee.com/api/v5/repos/$GITEE_OWNER/$GITEE_REPO/releases?page=1&per_page=20&direction=desc"
        AppLogger.info(TAG, "GET $url (local=${BuildConfig.VERSION_NAME})")
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            AppLogger.info(TAG, "Gitee HTTP ${resp.code}")
            if (!resp.isSuccessful) {
                AppLogger.warning(TAG, "Gitee API ${resp.code}")
                throw IOException("Gitee API ${resp.code}")
            }
            val body = resp.body?.string() ?: throw IOException("empty body")
            parseReleases(body)
        }
    }

    /**
     * Original GitHub-only flow. Hit `repos/{owner}/{repo}/releases` (the
     * list endpoint, NOT `/releases/latest`), pick the highest-version
     * non-draft release that carries an APK asset.
     */
    private suspend fun checkFromGitHub(): CheckResult = withContext(Dispatchers.IO) {
        val url = "https://api.github.com/repos/$GH_OWNER/$GH_REPO/releases?per_page=30"
        AppLogger.info(TAG, "GET $url (local=${BuildConfig.VERSION_NAME})")
        try {
            val req = Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .build()
            client.newCall(req).execute().use { resp ->
                AppLogger.info(TAG, "HTTP ${resp.code}")
                if (resp.code == 404) {
                    return@withContext CheckResult.NoReleaseAvailable
                }
                // 403 = rate-limit or geo-blocked. 451 = legal block.
                if (resp.code == 403 || resp.code == 451) {
                    AppLogger.warning(TAG, "GitHub API ${resp.code} — geo-block or rate-limit")
                    return@withContext CheckResult.Forbidden
                }
                if (!resp.isSuccessful) {
                    val msg = "GitHub API ${resp.code}"
                    AppLogger.warning(TAG, msg)
                    return@withContext CheckResult.Error(msg)
                }
                val body = resp.body?.string() ?: return@withContext CheckResult.Error("empty body")
                parseReleases(body)
            }
        } catch (e: UnknownHostException) {
            AppLogger.error(TAG, "check failed: UnknownHostException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: ConnectException) {
            AppLogger.error(TAG, "check failed: ConnectException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: SocketTimeoutException) {
            AppLogger.error(TAG, "check failed: SocketTimeoutException: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: IOException) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.NetworkUnreachable
        } catch (e: Exception) {
            AppLogger.error(TAG, "check failed: ${e.javaClass.simpleName}: ${e.message}")
            CheckResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * Shared release-list parser. Both GitHub and Gitee return a JSON array
     * of release objects with the same field names (`tag_name`, `assets`,
     * `body`, `draft`, `prerelease`). Fields that only exist on one platform
     * are read with `opt*` so missing keys degrade gracefully.
     */
    private suspend fun parseReleases(body: String): CheckResult = withContext(Dispatchers.IO) {
        val arr = runCatching { JSONArray(body) }.getOrNull()
        if (arr == null || arr.length() == 0) {
            AppLogger.info(TAG, "releases list empty")
            return@withContext CheckResult.NoReleaseAvailable
        }

        data class ReleaseInfo(
            val tagName: String,
            val versionName: String,
            val releaseName: String,
            val changelog: String,
            val isPrerelease: Boolean,
            val apkUrl: String?,
            val apkSize: Long,
        )

        val candidates = mutableListOf<ReleaseInfo>()
        for (i in 0 until arr.length()) {
            val r = arr.optJSONObject(i) ?: continue
            if (r.optBoolean("draft", false)) continue
            val tag = r.optString("tag_name")
            if (tag.isEmpty()) continue
            val (apkUrl, apkSize) = findApkAsset(r.optJSONArray("assets"))
            candidates += ReleaseInfo(
                tagName = tag,
                versionName = normalizeTag(tag),
                releaseName = r.optString("name").ifEmpty { tag },
                changelog = r.optString("body", ""),
                isPrerelease = r.optBoolean("prerelease", false),
                apkUrl = apkUrl,
                apkSize = apkSize,
            )
        }
        AppLogger.info(
            TAG,
            "non-draft releases=${candidates.size} (apk-bearing=${candidates.count { it.apkUrl != null }})",
        )
        if (candidates.isEmpty()) {
            return@withContext CheckResult.NoReleaseAvailable
        }

        // [T-android-updatechecker-localver-normalize] Normalize the LOCAL
        // version the same way remote tags are (normalizeTag), otherwise the
        // comparison is asymmetric.
        val localVer = normalizeTag(BuildConfig.VERSION_NAME)
        val highest = candidates.maxWithOrNull(
            compareBy { compareVersions(it.versionName, "0") },
        ) ?: candidates.first()
        AppLogger.info(
            TAG,
            "highest-published tag=${highest.tagName} parsed=${highest.versionName} prerelease=${highest.isPrerelease} apk=${highest.apkUrl != null}",
        )

        val upgradeCandidate = candidates
            .filter { it.apkUrl != null }
            .filter { compareVersions(it.versionName, localVer) > 0 }
            .maxWithOrNull(compareBy { compareVersions(it.versionName, "0") })

        if (upgradeCandidate != null) {
            AppLogger.info(
                TAG,
                "Update available: $localVer → ${upgradeCandidate.versionName} (${upgradeCandidate.tagName})",
            )
            return@withContext CheckResult.UpdateAvailable(
                tagName = upgradeCandidate.tagName,
                versionName = upgradeCandidate.versionName,
                releaseName = upgradeCandidate.releaseName,
                changelog = upgradeCandidate.changelog,
                apkUrl = upgradeCandidate.apkUrl!!,
                apkSizeBytes = upgradeCandidate.apkSize,
            )
        }

        val highestVsLocal = compareVersions(highest.versionName, localVer)
        if (highestVsLocal > 0 && highest.apkUrl == null) {
            AppLogger.info(TAG, "Release ${highest.tagName} > local but no APK asset")
            return@withContext CheckResult.NoApkAsset(highest.tagName)
        }

        AppLogger.info(TAG, "Up to date: local=$localVer highest=${highest.versionName}")
        CheckResult.UpToDate
    }

    /** GitHub Releases page — for manual download when API is blocked. */
    const val RELEASES_URL: String = "https://github.com/$GH_OWNER/$GH_REPO/releases"

    /** Gitee Releases page — domestic fallback for manual download. */
    const val RELEASES_URL_GITEE: String = "https://gitee.com/$GITEE_OWNER/$GITEE_REPO/releases"

    /** Returns (downloadUrl, sizeBytes) for the first .apk asset, or (null, 0). */
    private fun findApkAsset(assets: JSONArray?): Pair<String?, Long> {
        if (assets == null) return null to 0L
        for (i in 0 until assets.length()) {
            val a = assets.optJSONObject(i) ?: continue
            val name = a.optString("name").lowercase()
            if (name.endsWith(".apk")) {
                val u = a.optString("browser_download_url").ifEmpty { null }
                if (u != null) return u to a.optLong("size", 0)
            }
        }
        return null to 0L
    }

    /**
     * Strip the leading `v` and any `-preview` / `-rc1` / etc. trailing
     * label so the numeric comparator keeps `0.1` and `0.1-preview`
     * treated as equivalent.
     */
    private fun normalizeTag(tag: String): String {
        val trimmed = tag.trim().removePrefix("v").removePrefix("V")
        val dashIdx = trimmed.indexOf('-')
        return if (dashIdx > 0) trimmed.substring(0, dashIdx) else trimmed
    }

    /**
     * Stream the APK from [url] into filesDir, surfacing progress (0..1)
     * through [onProgress] roughly every 64 KiB.
     */
    suspend fun download(
        context: Context,
        url: String,
        versionName: String? = null,
        onProgress: (Float) -> Unit = {},
    ): DownloadResult = withContext(Dispatchers.IO) {
        try {
            val outDir = File(context.filesDir, UPDATES_DIR).apply { mkdirs() }
            val safeName = versionName
                ?.replace(Regex("[^A-Za-z0-9._-]"), "_")
                ?.takeIf { it.isNotEmpty() }
                ?.let { "minis-$it.apk" }
                ?: DOWNLOAD_FILENAME
            val outFile = File(outDir, safeName)
            if (outFile.exists()) outFile.delete()

            val req = Request.Builder().url(url).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Error("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Error("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    outFile.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var totalRead = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            totalRead += read
                            if (total > 0) {
                                val pct = ((totalRead * 100) / total).toInt()
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct / 100f)
                                }
                            }
                        }
                    }
                }
            }
            AppLogger.info(TAG, "Downloaded ${outFile.length()} bytes to ${outFile.absolutePath}")
            val sha = runCatching { PendingUpdateStore.sha256(outFile) }
                .onFailure { AppLogger.warning(TAG, "sha256 compute failed: ${it.message}") }
                .getOrNull()
            if (versionName != null) {
                PendingUpdateStore.setPending(
                    context,
                    PendingUpdateStore.PendingUpdate(
                        targetVersionName = versionName,
                        apkPath = outFile.absolutePath,
                        apkSize = outFile.length(),
                        sha256 = sha,
                        downloadedAtMs = System.currentTimeMillis(),
                    ),
                )
            }
            DownloadResult.Success(outFile)
        } catch (e: Exception) {
            AppLogger.error(TAG, "download failed: ${e.javaClass.simpleName}: ${e.message}")
            DownloadResult.Error(e.message ?: e.javaClass.simpleName)
        }
    }

    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openInstallPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    fun resumablePendingFile(context: Context): File? {
        val pending = PendingUpdateStore.getPending(context) ?: return null
        if (compareVersions(pending.targetVersionName, normalizeTag(BuildConfig.VERSION_NAME)) <= 0) {
            AppLogger.info(TAG, "pending target ${pending.targetVersionName} <= local; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        val file = PendingUpdateStore.verify(pending)
        if (file == null) {
            AppLogger.info(TAG, "pending APK failed integrity; clearing")
            PendingUpdateStore.clearPending(context)
            return null
        }
        return file
    }

    fun installApk(context: Context, apk: File): Boolean {
        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            AppLogger.info(TAG, "installApk launched apk=${apk.absolutePath} size=${apk.length()}")
            PendingUpdateStore.clearPending(context)
            true
        } catch (e: Exception) {
            AppLogger.error(TAG, "installApk failed: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * Numeric-aware version comparator. `1.0.10` beats `1.0.9`. Non-numeric
     * components fall back to lexicographic compare.
     */
    private fun compareVersions(a: String, b: String): Int {
        val ap = a.split('.', '-')
        val bp = b.split('.', '-')
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val x = ap.getOrNull(i) ?: ""
            val y = bp.getOrNull(i) ?: ""
            val xi = x.toIntOrNull()
            val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }
}
