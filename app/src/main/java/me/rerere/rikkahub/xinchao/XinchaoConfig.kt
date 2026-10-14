package me.rerere.rikkahub.xinchao

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

object XinchaoConfig {
    private const val PREFS_NAME = "xinchao_config"
    private const val DEFAULT_BASE_URL = "https://strings-citizens-seminars-pie.trycloudflare.com"
    const val CONVERSATION_TOKEN = "453ac084dee33b07916a8706a7da4550"
    const val BRIDGE_TOKEN       = "c3e24c5e3415a7b17ac6a1d4df9d1b8ba799b984"
    const val PHONE_TOKEN        = "5e93194001661c7ec26c36a69e5e36bb"
    const val PATH_CONVERSATION_EVENT = "/v1/conversation-event"
    const val PATH_HEARTBEAT          = "/v1/heartbeat"
    const val PATH_CONTEXT            = "/v1/context"
    const val PATH_PHONE_ACTIVITY     = "/v1/phone-activity"
    const val PATH_BRIDGE_EVENTS      = "/bridge/v1/events"
    const val PATH_BRIDGE_ACK         = "/bridge/v1/deliveries"
    const val HEARTBEAT_INTERVAL_MS    = 5 * 60_000L
    const val SSE_RECONNECT_DELAY_MS   = 10_000L
    const val CONVERSATION_THROTTLE_MS = 3_000L
    private lateinit var prefs: SharedPreferences
    fun init(context: Context) { prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", true)
        set(value) = prefs.edit { putBoolean("enabled", value) }
    var bridgeEnabled: Boolean
        get() = prefs.getBoolean("bridge_enabled", true)
        set(value) = prefs.edit { putBoolean("bridge_enabled", value) }
    var baseUrl: String
        get() = prefs.getString("base_url", DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit { putString("base_url", value.trimEnd('/')) }
    fun url(path: String): String = "$baseUrl$path"
}
