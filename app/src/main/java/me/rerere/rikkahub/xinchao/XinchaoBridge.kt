package me.rerere.rikkahub.xinchao

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

class XinchaoBridge(private val onSignal: (BridgeSignal) -> Unit) {
    companion object {
        private const val TAG = "XinchaoBridge"
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    }
    data class BridgeSignal(val id: String, val eventId: String, val reason: String, val message: String, val createdAt: String)

    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MINUTES).writeTimeout(10, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var sseCall: Call? = null
    @Volatile private var running = false

    fun start() { if (running) return; running = true; scope.launch { connectLoop() } }
    fun stop() { running = false; sseCall?.cancel(); scope.cancel() }

    private suspend fun connectLoop() {
        while (running) {
            try { connectSSE() } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "SSE disconnected: ${e.message}, reconnecting...")
            }
            if (running) delay(XinchaoConfig.SSE_RECONNECT_DELAY_MS)
        }
    }

    private suspend fun connectSSE() {
        val url = XinchaoConfig.url(XinchaoConfig.PATH_BRIDGE_EVENTS)
        val request = Request.Builder().url(url)
            .addHeader("Authorization", "Bearer ${XinchaoConfig.BRIDGE_TOKEN}")
            .addHeader("Accept", "text/event-stream").get().build()
        Log.d(TAG, "Connecting SSE: $url")
        sseCall = client.newCall(request)
        val response = withContext(Dispatchers.IO) { sseCall!!.execute() }
        if (!response.isSuccessful) {
            val body = response.body?.string()?.take(200) ?: ""
            response.close()
            throw IOException("SSE connect failed: ${response.code} $body")
        }
        Log.d(TAG, "SSE connected")
        val reader = BufferedReader(InputStreamReader(response.body!!.byteStream(), Charsets.UTF_8))
        try {
            var dataBuffer = StringBuilder(); var eventType = ""
            while (running) {
                val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
                when {
                    line.startsWith("event:") -> eventType = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> dataBuffer.append(line.removePrefix("data:").trim())
                    line.isBlank() && dataBuffer.isNotEmpty() -> {
                        processEvent(eventType, dataBuffer.toString()); dataBuffer = StringBuilder(); eventType = ""
                    }
                }
            }
        } finally { reader.close(); response.close() }
    }

    private fun processEvent(type: String, data: String) {
        try {
            val json = JSONObject(data)
            if (type == "heartbeat" || json.optString("type") == "heartbeat") { Log.d(TAG, "SSE heartbeat"); return }
            val signal = BridgeSignal(
                id = json.optString("id", ""), eventId = json.optString("eventId", json.optString("event_id", "")),
                reason = json.optString("reason", ""), message = json.optString("message", ""),
                createdAt = json.optString("createdAt", json.optString("created_at", ""))
            )
            if (signal.message.isNotBlank()) {
                Log.d(TAG, "Signal: [${signal.reason}] ${signal.message.take(60)}")
                onSignal(signal)
                if (signal.id.isNotBlank()) acknowledgeAsync(signal.id)
            }
        } catch (e: Exception) { Log.w(TAG, "SSE parse failed: ${e.message}") }
    }

    private fun acknowledgeAsync(deliveryId: String) {
        scope.launch {
            try {
                val url = "${XinchaoConfig.url(XinchaoConfig.PATH_BRIDGE_ACK)}/$deliveryId/ack"
                val body = JSONObject().apply { put("status", "delivered") }.toString().toRequestBody(JSON_TYPE)
                val request = Request.Builder().url(url).addHeader("Authorization", "Bearer ${XinchaoConfig.BRIDGE_TOKEN}").post(body).build()
                client.newCall(request).execute().use { Log.d(TAG, "ACK $deliveryId -> ${it.code}") }
            } catch (e: IOException) { Log.w(TAG, "ACK failed: ${e.message}") }
        }
    }
}
