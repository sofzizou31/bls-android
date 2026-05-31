package com.sofzizou.bls

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.TimeUnit

class GitHubClient {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Exposé pour LivenessActivity (intercept OzForensics requests) */
    fun httpClient() = http

    // ── Modèle de données ────────────────────────────────────────────────────

    data class LivenessData(
        val userId:        String,
        val transactionId: String,
        val ip:            String,
        val userAgent:     String,
        val sha:           String,
        val rawJson:       JSONObject
    )

    // ── Lecture du fichier livenesse-XXXX.json ───────────────────────────────

    suspend fun readFile(filename: String): LivenessData? = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.github.com/repos/${Constants.GITHUB_OWNER}/${Constants.GITHUB_REPO}" +
                      "/contents/$filename?ref=${Constants.GITHUB_BRANCH}"

            val resp = get(url) ?: return@withContext null
            val api  = JSONObject(resp)
            val sha  = api.getString("sha")
            val raw  = String(Base64.decode(api.getString("content").replace("\n", ""), Base64.DEFAULT))
            val data = JSONObject(raw)

            LivenessData(
                userId        = data.optString("user_id",        "N/A"),
                transactionId = data.optString("transaction_id", "N/A"),
                ip            = data.optString("x_forwarded_for", data.optString("ip", "N/A")),
                userAgent     = data.optString("user_agent",     "N/A"),
                sha           = sha,
                rawJson       = data
            )
        } catch (e: Exception) {
            Log.e("GitHub", "readFile: ${e.message}")
            null
        }
    }

    // ── Marquer "viewed" → retourne le nouveau SHA ───────────────────────────

    suspend fun markViewed(filename: String, sha: String, original: JSONObject): String? =
        withContext(Dispatchers.IO) {
            try {
                val updated = JSONObject(original.toString()).apply {
                    put("status",    "viewed")
                    put("viewed_at", Instant.now().toString())
                }
                putFile(filename, sha, updated, "👁️ Status: viewed")
            } catch (e: Exception) {
                Log.e("GitHub", "markViewed: ${e.message}")
                null
            }
        }

    // ── Écrire le Liveness ID (utilise sha2 issu de markViewed) ─────────────

    suspend fun writeLivenessId(
        filename:   String,
        sha:        String,
        original:   JSONObject,
        livenessId: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val updated = JSONObject(original.toString()).apply {
                put("Liveness ID", livenessId)
            }
            putFile(filename, sha, updated, "✅ Liveness ID: $livenessId") != null
        } catch (e: Exception) {
            Log.e("GitHub", "writeLivenessId: ${e.message}")
            false
        }
    }

    // ── Enregistrer le token FCM (clients/{phone}.json) ──────────────────────

    suspend fun registerFCMToken(phone: String, fcmToken: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val filename  = "clients/$phone.json"
                val existSha  = getFileSha(filename)  // null si nouveau fichier
                val data = JSONObject().apply {
                    put("phone",         phone)
                    put("fcm_token",     fcmToken)
                    put("platform",      "android")
                    put("registered_at", Instant.now().toString())
                }
                putFile(filename, existSha, data, "📱 Register: $phone") != null
            } catch (e: Exception) {
                Log.e("GitHub", "registerFCMToken: ${e.message}")
                false
            }
        }

    // ── Envoyer un message Telegram ──────────────────────────────────────────

    suspend fun sendTelegram(text: String) = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("chat_id",    Constants.TELEGRAM_CHAT)
                put("text",       text)
                put("parse_mode", "Markdown")
            }
            val req = Request.Builder()
                .url("https://api.telegram.org/bot${Constants.TELEGRAM_BOT}/sendMessage")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().close()
        } catch (e: Exception) {
            Log.e("Telegram", "sendTelegram: ${e.message}")
        }
    }

    // ── Helpers privés ───────────────────────────────────────────────────────

    private fun get(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "token ${Constants.GITHUB_TOKEN}")
            .header("Accept",        "application/vnd.github.v3+json")
            .header("User-Agent",    "BLS-Android/1.0")
            .build()
        val resp = http.newCall(req).execute()
        return if (resp.isSuccessful) resp.body?.string() else null.also {
            Log.e("GitHub", "GET $url → ${resp.code}")
        }
    }

    private fun getFileSha(filename: String): String? {
        val url = "https://api.github.com/repos/${Constants.GITHUB_OWNER}/${Constants.GITHUB_REPO}" +
                  "/contents/$filename?ref=${Constants.GITHUB_BRANCH}"
        return try {
            val body = get(url) ?: return null
            JSONObject(body).optString("sha").takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    /** PUT un fichier GitHub, retourne le nouveau SHA ou null en cas d'erreur */
    private fun putFile(filename: String, sha: String?, data: JSONObject, message: String): String? {
        val content = Base64.encodeToString(
            data.toString(2).toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        val body = JSONObject().apply {
            put("message", message)
            put("content", content)
            put("branch",  Constants.GITHUB_BRANCH)
            if (sha != null) put("sha", sha)
        }
        val url = "https://api.github.com/repos/${Constants.GITHUB_OWNER}/${Constants.GITHUB_REPO}" +
                  "/contents/$filename"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "token ${Constants.GITHUB_TOKEN}")
            .header("Accept",        "application/vnd.github.v3+json")
            .header("User-Agent",    "BLS-Android/1.0")
            .put(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return try {
            val resp = http.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.e("GitHub", "PUT $filename → ${resp.code}: ${resp.body?.string()}")
                return null
            }
            JSONObject(resp.body!!.string())
                .optJSONObject("content")?.optString("sha")
        } catch (e: Exception) {
            Log.e("GitHub", "putFile: ${e.message}")
            null
        }
    }
}
