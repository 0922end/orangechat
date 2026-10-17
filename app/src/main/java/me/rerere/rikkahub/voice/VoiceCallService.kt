package me.rerere.rikkahub.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

class VoiceCallService : Service() {
    companion object {
        private const val TAG = "VoiceCallService"
        private const val CHANNEL_ID = "voice_call"
        private const val NOTIFICATION_ID = 9909
        const val ACTION_START = "me.rerere.rikkahub.voice.START"
        const val ACTION_HANGUP = "me.rerere.rikkahub.voice.HANGUP"
        const val ACTION_MUTE_TOGGLE = "me.rerere.rikkahub.voice.MUTE_TOGGLE"
        var instance: VoiceCallService? = null; private set
        fun isRunning(): Boolean = instance != null
        fun start(context: Context) { val i = Intent(context, VoiceCallService::class.java).apply { action = ACTION_START }; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i) else context.startService(i) }
        fun stop(context: Context) { context.startService(Intent(context, VoiceCallService::class.java).apply { action = ACTION_HANGUP }) }
    }
    private var wakeLock: PowerManager.WakeLock? = null
    override fun onCreate() { super.onCreate(); instance = this; createNotificationChannel() }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) { ACTION_START -> { startForeground(NOTIFICATION_ID, buildNotification("\u901a\u8bdd\u4e2d")); acquireWakeLock() }
            ACTION_HANGUP -> { releaseWakeLock(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } }; return START_NOT_STICKY }
    override fun onDestroy() { releaseWakeLock(); instance = null; super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
    fun updateNotification(text: String) { getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text)) }
    private fun buildNotification(contentText: String): Notification {
        val tapIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP }
        val tapPending = tapIntent?.let { PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE) }
        val hangupPending = PendingIntent.getService(this, 1, Intent(this, VoiceCallService::class.java).apply { action = ACTION_HANGUP }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID).setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentTitle("Elian \u8bed\u97f3\u901a\u8bdd").setContentText(contentText)
            .setContentIntent(tapPending).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).addAction(android.R.drawable.ic_menu_close_clear_cancel, "\u6302\u65ad", hangupPending).build()
    }
    private fun createNotificationChannel() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { getSystemService(NotificationManager::class.java).createNotificationChannel(
        NotificationChannel(CHANNEL_ID, "\u8bed\u97f3\u901a\u8bdd", NotificationManager.IMPORTANCE_LOW).apply { description = "\u8bed\u97f3\u901a\u8bdd\u8fdb\u884c\u4e2d"; setShowBadge(false) }) } }
    private fun acquireWakeLock() { if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Elian:VoiceCall").apply { acquire(3600000L) } }
    private fun releaseWakeLock() { wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null }
}