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

    suspend fun get(path: String) = call("GET", path, null, null)
    suspend fun put(path: String, body: JSONObject) = call("PUT", path, body.toString().toByteArray(), "application/json")
    suspend fun post(path: String, body: JSONObject) = call("POST", path, body.toString().toByteArray(), "application/json")
    suspend fun delete(path: String) = call("DELETE", path, null, null)
    suspend fun postBytes(path: String, bytes: ByteArray, type: String) = call("POST", path, bytes, type)

    private suspend fun call(method: String, path: String, body: ByteArray?, type: String?, headers: Map<String, String> = emptyMap()): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                val conn = URL(BuildConfig.API_BASE_URL + path).openConnection() as HttpURLConnection
                conn.requestMethod = method
                conn.connectTimeout = 60_000
                conn.readTimeout = 60_000
                prefs.getString("token", null)?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", type ?: "application/json")
                    conn.outputStream.use { it.write(body) }
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
