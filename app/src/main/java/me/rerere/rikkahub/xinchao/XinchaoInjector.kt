package me.rerere.rikkahub.xinchao

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.uuid.Uuid

object XinchaoInjector {
    private const val TAG = "XinchaoInjector"
    private val pendingSignals = ConcurrentLinkedQueue<XinchaoBridge.BridgeSignal>()
    @Volatile private var registered = false

    // ChatService sets this callback to insert proactive messages
    var onSignalForChat: ((String) -> Unit)? = null
    @Volatile var lastActiveConversationId: Uuid? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun register() {
        if (registered) return
        registered = true
        XinchaoService.signalListeners.add { signal ->
            pendingSignals.offer(signal)
            Log.d(TAG, "Signal queued: [${signal.reason}] ${signal.message.take(40)}")
            // If ChatService registered a callback, push signal as proactive message
            val callback = onSignalForChat
            if (callback != null && signal.message.isNotBlank()) {
                try {
                    callback(signal.message)
                    Log.d(TAG, "Signal pushed to chat: ${signal.message.take(40)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to push signal to chat: ${e.message}")
                }
            }
        }
        Log.d(TAG, "Registered as signal listener")
    }

    /**
     * Fetch full context envelope from /v1/context (the "now block").
     * Contains drives, emotion, dreams, handoff notes.
     * Called synchronously before each AI generation.
     */
    fun fetchContext(): String {
        if (!XinchaoConfig.enabled) return ""
        try {
            val url = XinchaoConfig.url(XinchaoConfig.PATH_CONTEXT)
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${XinchaoConfig.CONVERSATION_TOKEN}")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchContext failed: ${response.code}")
                    return ""
                }
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val ctx = json.optString("additionalContext", "")
                if (ctx.isNotBlank()) {
                    Log.d(TAG, "Context fetched: ${ctx.take(80)}...")
                }
                return ctx
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchContext error: ${e.message}")
            return ""
        }
    }

    /**
     * Drain pending signals + fetch context envelope.
     * Injected into conversationSystemPrompt before each generateText.
     */
    fun consumePendingSignals(): String {
        val sb = StringBuilder()
        // 1. Fetch the "now block" from xinchao
        val context = fetchContext()
        if (context.isNotBlank()) {
            sb.append("\n").append(context).append("\n")
        }
        // 2. Drain any bridge signals that arrived
        val signals = mutableListOf<XinchaoBridge.BridgeSignal>()
        while (true) {
            val s = pendingSignals.poll() ?: break
            signals.add(s)
        }
        if (signals.isNotEmpty()) {
            sb.append("\n[Xinchao self_signal]\n")
            for (s in signals) {
                sb.append(s.message).append("\n")
            }
            Log.d(TAG, "Injecting ${signals.size} signal(s)")
        }
        return sb.toString()
    }
}
