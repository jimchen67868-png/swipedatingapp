package com.example.swipe.data

import android.content.Context
import com.example.swipe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class StartResult(val link: String, val secret: String)

/** The server answered with an error (as opposed to a network problem). */
class ApiException(message: String) : IOException(message)

class AuthRepository(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)

    suspend fun start(): Result<StartResult> =
        post("/auth/start", JSONObject()).map {
            StartResult(it.getString("link"), it.getString("secret"))
        }

    /** Returns true once login is complete, false while still waiting for Telegram. */
    suspend fun checkStatus(secret: String): Result<Boolean> =
        post("/auth/status", JSONObject().put("secret", secret)).mapCatching {
            val token = it.optString("token", "")
            if (token.isNotEmpty()) {
                prefs.edit().putString("token", token).apply()
                true
            } else false
        }

    fun logout() = prefs.edit().remove("token").apply()

    private suspend fun post(path: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 60_000
                conn.readTimeout = 60_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = JSONObject(text.ifBlank { "{}" })
                if (status !in 200..299) throw ApiException(json.optString("error", "Request failed ($status)"))
                json
            }
        }
}
