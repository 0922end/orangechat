package me.rerere.rikkahub.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AsrClient(
    private val apiKey: String,
    private val wsUrl: String = "wss://ws-j0d35q9jai174ox1.cn-beijing.maas.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime",
    private val vadThreshold: Float = 0.5f,
    private val silenceDurationMs: Int = 800
) {
    companion object { private const val TAG = "AsrClient" }

    sealed class AsrEvent {
        data class Transcript(val text: String) : AsrEvent()
        data class Partial(val text: String) : AsrEvent()
        object Connected : AsrEvent()
        data class Closed(val reason: String) : AsrEvent()
        data class Error(val message: String) : AsrEvent()
    }

    private val _events = MutableSharedFlow<AsrEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AsrEvent> = _events
    private var ws: WebSocket? = null
    private var isConnected = false
    private var sessionConfigured = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(30, TimeUnit.SECONDS).build()

    fun connect() {
        if (isConnected) return
        val request = Request.Builder().url(wsUrl).header("Authorization", "Bearer $apiKey").header("OpenAI-Beta", "realtime=v1").build()
        ws = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true; sessionConfigured = false; sendSessionUpdate(webSocket); _events.tryEmit(AsrEvent.Connected)
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handleMessage(text) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { isConnected = false; sessionConfigured = false; _events.tryEmit(AsrEvent.Closed(reason)) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { isConnected = false; sessionConfigured = false; _events.tryEmit(AsrEvent.Error(t.message ?: "WebSocket failed")) }
        })
    }

    fun disconnect() { ws?.close(1000, "Client disconnect"); ws = null; isConnected = false; sessionConfigured = false }
    fun release() { disconnect(); scope.cancel() }

    fun pushAudio(pcm16: ShortArray) {
        if (!isConnected || !sessionConfigured) return
        val currentWs = ws ?: return
        val bytes = ByteArray(pcm16.size * 2)
        for (i in pcm16.indices) { bytes[i * 2] = (pcm16[i].toInt() and 0xFF).toByte(); bytes[i * 2 + 1] = (pcm16[i].toInt() shr 8 and 0xFF).toByte() }
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        currentWs.send(JSONObject().apply { put("type", "input_audio_buffer.append"); put("audio", b64) }.toString())
    }

    fun commitAudioBuffer() { if (!isConnected) return; ws?.send(JSONObject().apply { put("type", "input_audio_buffer.commit") }.toString()) }
    fun clearAudioBuffer() { if (!isConnected) return; ws?.send(JSONObject().apply { put("type", "input_audio_buffer.clear") }.toString()) }

    private fun sendSessionUpdate(webSocket: WebSocket) {
        val session = JSONObject().apply {
            put("model", "qwen3-asr-flash-realtime")
            put("modalities", JSONArray().apply { put("text") })
            put("input_audio_format", "pcm")
            put("sample_rate", 16000)
            put("turn_detection", JSONObject().apply { put("type", "server_vad") })
        }
        val sent = webSocket.send(JSONObject().apply { put("type", "session.update"); put("session", session) }.toString())
        if (sent) sessionConfigured = true else _events.tryEmit(AsrEvent.Error("Failed to send session config"))
    }

    private fun handleMessage(text: String) {
        try {
            val json = JSONObject(text)
            when (json.optString("type", "")) {
                "session.created", "session.updated" -> { sessionConfigured = true }
                "response.audio_transcript.done" -> { val t = json.optString("transcript", "").trim().ifBlank { json.optString("text", "").trim() }; if (t.isNotEmpty()) _events.tryEmit(AsrEvent.Transcript(t)) }
                "response.audio_transcript.delta" -> { val d = json.optString("delta", "").trim().ifBlank { json.optString("text", "").trim() }; if (d.isNotEmpty()) _events.tryEmit(AsrEvent.Partial(d)) }
                "error" -> { val msg = json.optJSONObject("error")?.optString("message", "Unknown") ?: "Unknown"; _events.tryEmit(AsrEvent.Error(msg)) }
                else -> {}
            }
        } catch (e: Exception) { Log.e(TAG, "Parse error: ${e.message}") }
    }
}