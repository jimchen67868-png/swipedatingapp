package com.example.swipe.data

import android.content.Context
import com.example.swipe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class StartResult(val ref: String, val link: String)

class AuthRepository(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    val token: String? get() = prefs.getString("token", null)

    suspend fun start(phone: String): Result<StartResult> =
        post("/auth/start", JSONObject().put("phone", phone)).map {
            StartResult(it.getString("ref"), it.getString("link"))
        }

    suspend fun verify(ref: String, code: String): Result<Unit> =
        post("/auth/verify", JSONObject().put("ref", ref).put("code", code)).mapCatching {
            prefs.edit().putString("token", it.getString("token")).apply()
        }

    fun logout() = prefs.edit().remove("token").apply()

    private suspend fun post(path: String, body: JSONObject): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toString().toByteArray()) }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val json = JSONObject(text.ifBlank { "{}" })
                if (status !in 200..299) throw IOException(json.optString("error", "Request failed ($status)"))
                json
            }
        }
}
