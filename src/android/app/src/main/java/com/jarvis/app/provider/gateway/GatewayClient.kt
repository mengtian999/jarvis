package com.jarvis.app.provider.gateway

import android.util.Log
import com.jarvis.app.data.DeviceIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Jarvis model gateway client — Route A (real HMAC-signed requests).
 * Mirrors iOS GatewayClient.swift and desktop core/gateway/gateway-client.ts.
 */
class GatewayClient(
    private val context: android.content.Context,
    val baseURL: String = "https://gateway.bitjarvis.chat",
    private val appSecret: String = DEFAULT_APP_SECRET,
    private val okHttpClient: OkHttpClient = defaultHttpClient,
) {
    companion object {
        private const val TAG = "GatewayClient"
        const val DEFAULT_APP_SECRET = "b0d759b1b38ee762be677d5ff0159b16489da3c70ec6780d96ededb50cd98870"
        private const val PREFS_NAME = "gateway_identity"
        private const val KEY_IDENTITY = "device_identity"
        private const val HMAC_ALGORITHM = "HmacSHA256"

        val defaultHttpClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30_000L, TimeUnit.MILLISECONDS)
            .readTimeout(60_000L, TimeUnit.MILLISECONDS)
            .build()
    }

    /** Anonymous device identity for the Jarvis model gateway (§1.2). */
    data class GatewayIdentity(
        val installId: String,
        val deviceId: String,
        val token: String,
        val region: String = "cn",
        val registeredAt: Long = System.currentTimeMillis(),
    )

    val identity: GatewayIdentity? get() = loadIdentity()
    val chatBaseURL: String get() = "$baseURL/v1"

    suspend fun ensureDevice(): GatewayIdentity = withContext(Dispatchers.IO) {
        loadIdentity()?.let { return@withContext it }
        register()
    }

    suspend fun register(): GatewayIdentity = withContext(Dispatchers.IO) {
        val installId = DeviceIdentity.deviceId(context)
        val body = """{"install_id":"$installId","platform":"android","app_version":"${com.jarvis.app.BuildConfig.VERSION_NAME}","device_info_hash":"${deviceInfoHash()}"}"""

        val request = Request.Builder()
            .url("$baseURL/v1/devices/register")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
            .let { sign(it) }

        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            response.close()
            throw GatewayException("Registration failed: HTTP ${response.code}")
        }

        val json = parseJson(response.body?.string() ?: "{}")
        val deviceId = json.optString("device_id", "")
        val token = json.optString("token", "")
        if (deviceId.isEmpty() || token.isEmpty()) {
            throw GatewayException("Missing device_id or token in register response")
        }

        val identity = GatewayIdentity(installId, deviceId, token, json.optString("region", "cn"))
        saveIdentity(identity)
        Log.i(TAG, "Registered: deviceId=${identity.deviceId} region=${identity.region}")
        identity
    }

    suspend fun listModels(): JSONObject = withContext(Dispatchers.IO) {
        val id = ensureDevice()
        val request = Request.Builder()
            .url("$baseURL/v1/models")
            .header("Authorization", "Bearer ${id.token}")
            .get()
            .build()
            .let { sign(it) }
        executeRequest(request)
    }

    suspend fun getQuota(): JSONObject = withContext(Dispatchers.IO) {
        val id = ensureDevice()
        val request = Request.Builder()
            .url("$baseURL/v1/quota")
            .header("Authorization", "Bearer ${id.token}")
            .get()
            .build()
            .let { sign(it) }
        executeRequest(request)
    }

    suspend fun submitMediaJob(body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val id = ensureDevice()
        val request = Request.Builder()
            .url("$baseURL/v1/media/jobs")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("Authorization", "Bearer ${id.token}")
            .build()
            .let { sign(it) }
        executeRequest(request)
    }

    suspend fun getMediaJob(jobId: String): JSONObject = withContext(Dispatchers.IO) {
        val id = ensureDevice()
        val request = Request.Builder()
            .url("$baseURL/v1/media/jobs/$jobId")
            .header("Authorization", "Bearer ${id.token}")
            .get()
            .build()
            .let { sign(it) }
        executeRequest(request)
    }

    fun clearIdentity() {
        context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().remove(KEY_IDENTITY).apply()
        Log.i(TAG, "Identity cleared")
    }

    // MARK: - Signing (Route A, §1.3)

    /** Sign a Request with HMAC-SHA256. */
    fun sign(request: Request): Request {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        val body = buffer.readByteArray()

        val message = ByteArray(ts.length + 1 + body.size) { i ->
            when {
                i < ts.length -> ts[i].code.toByte()
                i == ts.length -> 0x0a.toByte()
                else -> body[i - ts.length - 1]!!
            }
        }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(appSecret.toByteArray(Charsets.UTF_8), HMAC_ALGORITHM))
        val signature = mac.doFinal(message).joinToString("") { "%02x".format(it) }

        return request.newBuilder()
            .header("X-Timestamp", ts)
            .header("X-Signature", signature)
            .build()
    }

    // MARK: - Private Helpers

    private suspend fun executeRequest(request: Request): JSONObject = withContext(Dispatchers.IO) {
        try {
            val response = okHttpClient.newCall(request).execute()
            if (response.code == 401) {
                clearIdentity()
                throw GatewayException("Token invalid — re-registration required")
            }
            if (!response.isSuccessful) {
                throw GatewayException("Request failed: HTTP ${response.code}")
            }
            parseJson(response.body?.string() ?: "")
        } catch (e: IOException) {
            throw GatewayException("Network error: ${e.message}", e)
        }
    }

    private fun deviceInfoHash(): String {
        val model = android.os.Build.MODEL
        val info = "android|$model|gateway-v1"
        val digest = MessageDigest.getInstance("SHA-256").digest(info.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun parseJson(json: String): JSONObject {
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            throw GatewayException("Invalid JSON: ${e.message}", e)
        }
    }

    private fun loadIdentity(): GatewayIdentity? {
        return try {
            val prefs = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_IDENTITY, null) ?: return null
            val obj = JSONObject(json)
            GatewayIdentity(
                installId = obj.getString("installId"),
                deviceId = obj.getString("deviceId"),
                token = obj.getString("token"),
                region = obj.optString("region", "cn"),
                registeredAt = obj.optLong("registeredAt", System.currentTimeMillis()),
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load identity: ${e.message}")
            null
        }
    }

    private fun saveIdentity(identity: GatewayIdentity) {
        try {
            val json = JSONObject().apply {
                put("installId", identity.installId)
                put("deviceId", identity.deviceId)
                put("token", identity.token)
                put("region", identity.region)
                put("registeredAt", identity.registeredAt)
            }.toString()
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit().putString(KEY_IDENTITY, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save identity: ${e.message}")
        }
    }

    class GatewayException(message: String, cause: Throwable? = null) : Exception(message, cause)
}
