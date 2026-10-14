package me.rerere.rikkahub.xinchao

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

class XinchaoService : Service() {
    companion object {
        private const val TAG = "XinchaoService"
        private const val CHANNEL_ID = "xinchao_heartbeat"
        private const val NOTIFICATION_ID = 9527
        val signalListeners = CopyOnWriteArrayList<(XinchaoBridge.BridgeSignal) -> Unit>()
        private val recentSignals = mutableListOf<XinchaoBridge.BridgeSignal>()
        private const val MAX_RECENT = 10
        fun getRecentSignals(): List<XinchaoBridge.BridgeSignal> { synchronized(recentSignals) { return recentSignals.toList() } }
        fun start(context: Context) {
            try {
                val intent = Intent(context, XinchaoService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
                Log.i(TAG, "Service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start service, falling back to coroutine mode", e)
                startFallback(context)
            }
        }
        fun stop(context: Context) { context.stopService(Intent(context, XinchaoService::class.java)) }

        // Fallback: if service can't start, run heartbeat and bridge in a global coroutine
        private var fallbackJob: Job? = null
        private fun startFallback(context: Context) {
            if (fallbackJob?.isActive == true) return
            fallbackJob = CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
                Log.i(TAG, "Fallback mode: heartbeat + bridge without foreground service")
                launch {
                    while (isActive) {
                        try { XinchaoUplink.sendHeartbeat() } catch (e: Exception) { Log.w(TAG, "Fallback heartbeat failed: ${e.message}") }
                        delay(XinchaoConfig.HEARTBEAT_INTERVAL_MS)
                    }
                }
                if (XinchaoConfig.bridgeEnabled) {
                    val bridge = XinchaoBridge { signal ->
                        synchronized(recentSignals) { recentSignals.add(signal); while (recentSignals.size > MAX_RECENT) recentSignals.removeAt(0) }
                        for (listener in signalListeners) { try { listener(signal) } catch (e: Exception) { Log.w(TAG, "Fallback signal error: ${e.message}") } }
                    }
                    bridge.start()
                }
            }
        }
    }
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var bridge: XinchaoBridge? = null
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        try {
            createNotificationChannel()
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.d(TAG, "Foreground started OK")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
        }
        startHeartbeat()
        if (XinchaoConfig.bridgeEnabled) startBridge()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() { Log.d(TAG, "Service destroyed"); heartbeatJob?.cancel(); bridge?.stop(); serviceScope.cancel(); super.onDestroy() }

    private fun startHeartbeat() {
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                try {
                    XinchaoUplink.sendHeartbeat()
                    Log.d(TAG, "Heartbeat sent")
                } catch (e: Exception) { Log.w(TAG, "Heartbeat failed: ${e.message}") }
                delay(XinchaoConfig.HEARTBEAT_INTERVAL_MS)
            }
        }
    }
    private fun startBridge() {
        bridge = XinchaoBridge { signal ->
            synchronized(recentSignals) { recentSignals.add(signal); while (recentSignals.size > MAX_RECENT) recentSignals.removeAt(0) }
            for (listener in signalListeners) { try { listener(signal) } catch (e: Exception) { Log.w(TAG, "Signal listener error: ${e.message}") } }
            try { updateNotification(signal.message.take(40)) } catch (_: Exception) {}
        }
        bridge!!.start()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Xinchao", NotificationManager.IMPORTANCE_LOW).apply { description = "Xinchao heartbeat"; setShowBadge(false) }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
    private fun buildNotification(subtitle: String? = null): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID).setContentTitle("Xinchao").setContentText(subtitle ?: "Connected")
            .setSmallIcon(android.R.drawable.ic_dialog_info).setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    }
    private fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
}
