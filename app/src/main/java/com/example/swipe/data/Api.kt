package com.example.swipe.data

import android.content.Context
import com.example.swipe.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Small HTTP client that sends the login token with every request. */
class Api(context: Context) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)

    suspend fun get(path: String) = call("GET", path, null)
    suspend fun put(path: String, body: JSONObject) = call("PUT", path, body)
    suspend fun post(path: String, body: JSONObject) = call("POST", path, body)

    private suspend fun call(method: String, path: String, body: JSONObject?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 60_000
                conn.readTimeout = 60_000
                prefs.getString("token", null)?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val status = conn.responseCode
                val stream = if (status in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) {
                    val msg = runCatching { JSONObject(text).optString("error") }.getOrDefault("")
                    throw ApiException(msg.ifBlank { "Request failed ($status)" }, status)
                }
                text
            }
        }
}
