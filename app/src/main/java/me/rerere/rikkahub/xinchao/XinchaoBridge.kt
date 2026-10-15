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

    // readTimeout 不用 0（永不超时）：SSE 假死时不会重连。
    // 设为 150 秒静默上限——服务端心跳正常时不会触发，真断了就抛 IOException 走重连。
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(150, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build()
    private val fetchClient = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
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
                    line.startsWith(":"  ) -> { /* SSE comment, e.g. ': heartbeat' - ignore */ }
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
            when (type) {
                "connected" -> { Log.d(TAG, "SSE connected event received"); return }
                "delivery" -> {
                    val deliveryId = json.optString("deliveryId", "")
                    if (deliveryId.isNotBlank()) {
                        scope.launch { fetchAndProcessDelivery(deliveryId) }
                    }
                }
                else -> Log.d(TAG, "SSE unknown event type: $type")
            }
        } catch (e: Exception) { Log.w(TAG, "SSE parse failed: ${e.message}") }
    }

    private suspend fun fetchAndProcessDelivery(deliveryId: String) {
        try {
            val url = "${XinchaoConfig.url(XinchaoConfig.PATH_BRIDGE_ACK)}/$deliveryId"
            val request = Request.Builder().url(url)
                .addHeader("Authorization", "Bearer ${XinchaoConfig.BRIDGE_TOKEN}")
                .get().build()
            val response = withContext(Dispatchers.IO) { fetchClient.newCall(request).execute() }
            if (!response.isSuccessful) {
                Log.w(TAG, "Fetch delivery $deliveryId failed: ${response.code}")
                response.close()
                return
            }
            val body = response.body?.string() ?: ""
            response.close()
            val json = JSONObject(body)
            val signal = BridgeSignal(
                id = json.optString("id", deliveryId),
                eventId = json.optString("eventId", json.optString("event_id", "")),
                reason = json.optString("reason", ""),
                message = json.optString("message", ""),
                createdAt = json.optString("createdAt", json.optString("created_at", ""))
            )
            if (signal.message.isNotBlank()) {
                Log.d(TAG, "Signal: [${signal.reason}] ${signal.message.take(60)}")
                onSignal(signal)
                acknowledgeAsync(deliveryId)
            }
        } catch (e: Exception) { Log.w(TAG, "Fetch delivery failed: ${e.message}") }
    }

    private fun acknowledgeAsync(deliveryId: String) {
        scope.launch {
            try {
                val url = "${XinchaoConfig.url(XinchaoConfig.PATH_BRIDGE_ACK)}/$deliveryId/ack"
                val body = JSONObject().apply { put("status", "delivered") }.toString().toRequestBody(JSON_TYPE)
                val request = Request.Builder().url(url).addHeader("Authorization", "Bearer ${XinchaoConfig.BRIDGE_TOKEN}").post(body).build()
                fetchClient.newCall(request).execute().use { Log.d(TAG, "ACK $deliveryId -> ${it.code}") }
            } catch (e: IOException) { Log.w(TAG, "ACK failed: ${e.message}") }
        }
    }
}
