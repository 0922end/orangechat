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

    // ChatService sets this callback to insert proactive messages.
    // 返回 true 表示已就绪、能立刻处理这条信号；false 表示当前没有活跃对话，交给队列等下次。
    var onSignalForChat: ((String) -> Boolean)? = null
    @Volatile var lastActiveConversationId: Uuid? = null

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    fun register() {
        if (registered) return
        registered = true
        XinchaoService.signalListeners.add { signal ->
            // 单路径处理，避免同一条信号被消费两次：
            //  - 有活跃对话 → 走主动回复（triggerSystemReply），不再进队列
            //  - 没有活跃对话 / 触发失败 → 入队，等她下一次说话时注入上下文
            val callback = onSignalForChat
            var handled = false
            if (callback != null && signal.message.isNotBlank()) {
                try {
                    handled = callback(signal.message)
                    if (handled) Log.d(TAG, "Signal pushed to chat: [${signal.reason}] ${signal.message.take(40)}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to push signal to chat: ${e.message}")
                }
            }
            if (!handled) {
                pendingSignals.offer(signal)
                Log.d(TAG, "Signal queued for next turn: [${signal.reason}] ${signal.message.take(40)}")
            }
        }
        Log.d(TAG, "Registered as signal listener")
    }

    /**
     * Fetch full context envelope from /v1/context (the "now block").
     * Contains drives, emotion, dreams, handoff notes.
     * Called synchronously before each AI generation.
     */
    @Volatile private var cachedContext: String = ""
    @Volatile private var lastContextRefreshMs: Long = 0L
    private const val CONTEXT_TTL_MS = 60_000L
    private val contextExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 后台刷新 now block 到缓存；比 TTL 新鲜时不重复请求。
     * 生成前只读缓存，绝不阻塞在网络上。
     */
    fun refreshContextAsync() {
        if (!XinchaoConfig.enabled) return
        val now = System.currentTimeMillis()
        if (now - lastContextRefreshMs < CONTEXT_TTL_MS) return
        lastContextRefreshMs = now
        try {
            contextExecutor.execute {
                val ctx = fetchContext()
                if (ctx.isNotBlank()) cachedContext = ctx
            }
        } catch (e: Exception) { Log.w(TAG, "refreshContextAsync failed: ${e.message}") }
    }

    /** 同步拉取（显式调用时用；常规路径请用 refreshContextAsync + cachedContext）。 */
    fun fetchContext(): String {
        if (!XinchaoConfig.enabled) return ""
        try {
            val sessionId = lastActiveConversationId?.toString() ?: "elian-default"
            val url = XinchaoConfig.url(XinchaoConfig.PATH_CONTEXT) + "?session_id=$sessionId&mode=turn"
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
        // 1. now block：后台刷新，读缓存，不阻塞本轮生成
        refreshContextAsync()
        val context = cachedContext
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
