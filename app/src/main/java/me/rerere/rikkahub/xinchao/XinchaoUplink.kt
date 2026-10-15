package me.rerere.rikkahub.xinchao

import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

object XinchaoUplink {
    private const val TAG = "XinchaoUplink"
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastEventMs = 0L

    fun sendConversationEvent(
        userMessage: String,
        aiReply: String,
        thinking: String = "",
        sessionId: String? = null
    ) {
        if (!XinchaoConfig.enabled) return
        val now = System.currentTimeMillis()
        if (now - lastEventMs < XinchaoConfig.CONVERSATION_THROTTLE_MS) return
        lastEventMs = now
        val exchange = buildString {
            if (userMessage.isNotBlank()) append("\u5979\u8BF4\uFF1A").append(userMessage.take(600))
            if (aiReply.isNotBlank()) {
                if (isNotEmpty()) append(" ")
                append("\u6211\u56DE\uFF1A").append(aiReply.take(600))
            }
        }
        val body = JSONObject().apply {
            put("event_id", "elian-conv-${UUID.randomUUID().toString().take(12)}")
            put("exchange", exchange)
            put("recent", userMessage.take(200))
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
        }
        postAsync(XinchaoConfig.url(XinchaoConfig.PATH_CONVERSATION_EVENT), XinchaoConfig.CONVERSATION_TOKEN, body)
    }

    fun sendHeartbeat(sessionId: String? = null) {
        if (!XinchaoConfig.enabled) return
        val body = JSONObject().apply {
            put("event_id", "elian-hb-${System.currentTimeMillis()}")
            if (!sessionId.isNullOrBlank()) put("session_id", sessionId)
        }
        postAsync(XinchaoConfig.url(XinchaoConfig.PATH_HEARTBEAT), XinchaoConfig.CONVERSATION_TOKEN, body)
    }

    fun sendPhoneActivity(appName: String, packageName: String, durationMinutes: Int) {
        if (!XinchaoConfig.enabled) return
        val body = JSONObject().apply {
            put("app_name", appName)
            put("package_name", packageName)
            put("duration_minutes", durationMinutes)
        }
        postAsync(XinchaoConfig.url(XinchaoConfig.PATH_PHONE_ACTIVITY), XinchaoConfig.PHONE_TOKEN, body)
    }

    private fun postAsync(url: String, token: String, json: JSONObject) {
        scope.launch {
            try {
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", "Bearer $token")
                    .post(json.toString().toRequestBody(JSON_TYPE))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Log.d(TAG, "POST $url -> ${response.code}")
                    else Log.w(TAG, "POST $url -> ${response.code}: ${response.body?.string()?.take(200)}")
                }
            } catch (e: IOException) { Log.e(TAG, "POST $url failed: ${e.message}") }
        }
    }

    fun shutdown() { scope.cancel() }
}
