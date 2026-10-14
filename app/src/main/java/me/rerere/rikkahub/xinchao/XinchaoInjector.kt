package me.rerere.rikkahub.xinchao

import android.util.Log
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Xinchao signal injector: collects bridge signals and provides
 * formatted text to inject into AI context before each generation.
 *
 * ChatService calls consumePendingSignals() before generateText,
 * which drains the queue and returns formatted text to append
 * to the conversation system prompt.
 */
object XinchaoInjector {
    private const val TAG = "XinchaoInjector"
    private val pendingSignals = ConcurrentLinkedQueue<XinchaoBridge.BridgeSignal>()
    @Volatile private var registered = false

    fun register() {
        if (registered) return
        registered = true
        XinchaoService.signalListeners.add { signal ->
            pendingSignals.offer(signal)
            Log.d(TAG, "Signal queued: [${signal.reason}] ${signal.message.take(40)}")
        }
        Log.d(TAG, "Registered as signal listener")
    }

    /**
     * Drain all pending signals and format them for injection.
     * Returns empty string if no signals pending.
     */
    fun consumePendingSignals(): String {
        val signals = mutableListOf<XinchaoBridge.BridgeSignal>()
        while (true) {
            val s = pendingSignals.poll() ?: break
            signals.add(s)
        }
        if (signals.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("\n[Xinchao self_signal]\n")
        for (s in signals) {
            sb.append(s.message)
            sb.append("\n")
        }
        Log.d(TAG, "Injecting ${signals.size} signal(s)")
        return sb.toString()
    }

    /**
     * Get recent signals without consuming (for new window context).
     */
    fun getRecentContext(): String {
        val recent = XinchaoService.getRecentSignals()
        if (recent.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n[Xinchao recent_signals]\n")
        for (s in recent.takeLast(3)) {
            sb.append(s.message)
            sb.append("\n")
        }
        return sb.toString()
    }
}
