package me.rerere.rikkahub.voice

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class VisionChain {
    companion object {
        private const val TAG = "VisionChain"
        const val DIFF_THRESHOLD = 0.06f
        const val MIN_INTERVAL = 8L
        const val VISION_TIMEOUT = 12L
        const val VISION_TOTAL_TIMEOUT = 25L
        const val COMP_ABSENT_SECS = 120L
        const val COMP_NUDGE_COOLDOWN = 120L
        const val COMP_STILL_SECS = 1800L
        const val COMP_ASSESS_MIN = 60L
        const val COMP_ASSESS_MAX = 300L
        const val COMP_DIFF_MULT = 2.0f
        const val LIVE_PROMPT = "\u8fd9\u662f\u89c6\u9891\u901a\u8bdd\u65f6\u4ece\u5bf9\u65b9\u6444\u50cf\u5934\u91cc\u62bd\u7684\u4e00\u5e27\u3002\u7528\u4e2d\u6587\u3001\u4e24\u53e5\u8bdd\u4ee5\u5185\u63cf\u8ff0\u4f60\u770b\u5230\u7684\uff1a\u5bf9\u65b9\u5728\u54ea\u3001\u5728\u505a\u4ec0\u4e48\u3001\u8868\u60c5\u548c\u72b6\u6001\u3001\u6709\u6ca1\u6709\u503c\u5f97\u4e00\u63d0\u7684\u7ec6\u8282\u3002\u53ea\u63cf\u8ff0\uff0c\u4e0d\u8bc4\u4ef7\uff0c\u4e0d\u6253\u62db\u547c\uff0c\u4e0d\u52a0\u524d\u7f00\u3002"
        const val COMPANION_PROMPT = "\u8fd9\u662f\u89c6\u9891\u901a\u8bdd\u4e2d\u4ece\u5bf9\u65b9\u6444\u50cf\u5934\u91cc\u62bd\u7684\u4e00\u5e27\u3002\u5bf9\u65b9\u5728\u5de5\u4f5c\u6216\u5b66\u4e60\uff0c\u4f60\u53ea\u662f\u5b89\u9759\u7684\u89c2\u5bdf\u5458\u3002\u53ea\u8f93\u51fa\u4e00\u884c JSON\uff0c\u4e0d\u8981\u4efb\u4f55\u522b\u7684\u5b57\uff1a{\"present\": true/false, \"activity\": \"\u5341\u4e2a\u5b57\u4ee5\u5185\", \"notable\": \"\u5341\u4e2a\u5b57\u4ee5\u5185\uff1b\u6b63\u5e38\u5de5\u4f5c\u5b66\u4e60\u5c31\u5199\u7a7a\u5b57\u7b26\u4e32\"}"
    }

    interface Listener { fun onObservation(description: String); fun onCompanionEvent(kind: String, text: String) }
    var listener: Listener? = null
    data class VisionBackend(val name: String, val baseUrl: String, val apiKey: String, val model: String)
    var backends: List<VisionBackend> = emptyList()
    val isEnabled: Boolean get() = backends.isNotEmpty()
    private var lastThumb: FloatArray? = null
    private var lastDescAt: Long = 0L
    val busy = AtomicBoolean(false)
    var lastObservation: String? = null; private set
    private var compPresent: Boolean? = null
    private var compAbsentSince: Long = 0L
    private var compLeftReported: Boolean = false
    private var compLastNudge: Long = 0L
    private var compLastAssess: Long = 0L
    private var compStillSince: Long = nowSeconds()
    private var compReminders: Int = 0
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder().connectTimeout(VISION_TIMEOUT, TimeUnit.SECONDS).readTimeout(VISION_TIMEOUT + 2, TimeUnit.SECONDS).build()

    private fun thumb(jpeg: ByteArray): FloatArray? {
        return try {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return null
            val s = Bitmap.createScaledBitmap(bmp, 32, 32, true); val px = IntArray(1024); s.getPixels(px, 0, 32, 0, 0, 32, 32)
            val g = FloatArray(1024); for (i in px.indices) g[i] = (0.299f * Color.red(px[i]) + 0.587f * Color.green(px[i]) + 0.114f * Color.blue(px[i])) / 255f
            if (s !== bmp) s.recycle(); bmp.recycle(); g
        } catch (e: Exception) { null }
    }

    fun changed(jpeg: ByteArray, threshold: Float = DIFF_THRESHOLD): Boolean {
        val t = thumb(jpeg) ?: return true; val prev = lastThumb; lastThumb = t; if (prev == null) return true
        var sum = 0f; for (i in t.indices) sum += kotlin.math.abs(t[i] - prev[i]); return sum / t.size >= threshold
    }

    fun shouldDescribe(jpeg: ByteArray): Boolean {
        if (!isEnabled || busy.get()) return false; val now = nowSeconds()
        if (now - lastDescAt < MIN_INTERVAL) { changed(jpeg); return false }; return changed(jpeg)
    }

    suspend fun describe(jpeg: ByteArray): String? = runVisionChain(jpeg, LIVE_PROMPT)
    suspend fun assess(jpeg: ByteArray): Assessment? { val raw = runVisionChain(jpeg, COMPANION_PROMPT) ?: return null; return parseAssessment(raw) }
    data class Assessment(val present: Boolean, val activity: String, val notable: String)

    private fun parseAssessment(text: String): Assessment {
        try { val js = text.indexOf('{'); val je = text.lastIndexOf('}'); if (js >= 0 && je > js) { val j = JSONObject(text.substring(js, je + 1))
            return Assessment(j.optBoolean("present", true), j.optString("activity", "").trim().take(40), j.optString("notable", "").trim().take(40)) } } catch (_: Exception) {}
        return Assessment(true, text.trim().take(40), "")
    }

    private suspend fun runVisionChain(jpeg: ByteArray, prompt: String): String? {
        lastDescAt = nowSeconds(); if (busy.getAndSet(true)) return null; val started = nowSeconds()
        try { for (b in backends) { val left = VISION_TOTAL_TIMEOUT - (nowSeconds() - started); if (left <= 1) return null
            try { val desc = withTimeout(minOf(VISION_TIMEOUT, left) * 1000) { callVisionApi(jpeg, b, prompt) }; val t = desc?.trim(); if (!t.isNullOrEmpty()) { lastObservation = t; return t } }
            catch (e: TimeoutCancellationException) { Log.w(TAG, "${b.name} timeout") } catch (e: Exception) { if (e is CancellationException) throw e; Log.w(TAG, "${b.name} failed: ${e.message}") } }
            return null } finally { busy.set(false) }
    }

    private suspend fun callVisionApi(jpeg: ByteArray, backend: VisionBackend, prompt: String): String? = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP)
        val body = JSONObject().apply { put("model", backend.model); put("max_tokens", 600)
            put("messages", JSONArray().apply { put(JSONObject().apply { put("role", "user"); put("content", JSONArray().apply {
                put(JSONObject().apply { put("type", "text"); put("text", prompt) })
                put(JSONObject().apply { put("type", "image_url"); put("image_url", JSONObject().apply { put("url", "data:image/jpeg;base64,$b64") }) }) }) }) })
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${backend.baseUrl}/chat/completions").header("Authorization", "Bearer ${backend.apiKey}").post(body).build()
        val resp = httpClient.newCall(req).execute()
        if (!resp.isSuccessful) { val err = resp.body?.string()?.take(200); resp.close(); throw RuntimeException("Vision ${resp.code}: $err") }
        val json = JSONObject(resp.body!!.string()); resp.close()
        json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }

    fun processCompanionFrame(jpeg: ByteArray) {
        if (!isEnabled) return
        scope.launch {
            val now = nowSeconds(); val moved = changed(jpeg, DIFF_THRESHOLD * COMP_DIFF_MULT)
            val since = now - compLastAssess
            val want = since >= COMP_ASSESS_MAX || (moved && since >= COMP_ASSESS_MIN) || (compPresent == false && since >= COMP_ASSESS_MIN) || compPresent == null
            if (want && isEnabled && !busy.get()) {
                compLastAssess = now; val a = assess(jpeg) ?: return@launch; val was = compPresent; compPresent = a.present
                if (!a.present) { if (compAbsentSince == 0L) compAbsentSince = now
                    else if (!compLeftReported && now - compAbsentSince >= COMP_ABSENT_SECS) { compLeftReported = true; nudge("left", "\u5bf9\u65b9\u79bb\u5f00\u5ea7\u4f4d ${(now - compAbsentSince) / 60} \u5206\u949f\u4e86") }; return@launch }
                if (was == false && compLeftReported) { compAbsentSince = 0L; compLeftReported = false; nudge("back", "\u5bf9\u65b9\u56de\u6765\u4e86" + if (a.activity.isNotEmpty()) "\uff0c${a.activity}" else ""); return@launch }
                compAbsentSince = 0L; compLeftReported = false
                if (a.notable.isNotEmpty() && now - compLastNudge >= COMP_NUDGE_COOLDOWN) { nudge("gesture", "\u5bf9\u65b9${a.notable}" + if (a.activity.isNotEmpty()) "\uff0c\u73b0\u5728${a.activity}" else ""); return@launch }
            }
            if (compPresent == true && now - compStillSince >= COMP_STILL_SECS && now - compLastNudge >= COMP_NUDGE_COOLDOWN) {
                compReminders += 1; val tip = if (compReminders % 2 == 1) "\u559d\u53e3\u6c34" else "\u7ad9\u8d77\u6765\u6d3b\u52a8\u4e00\u4e0b"
                nudge("still", "\u5bf9\u65b9\u5df2\u7ecf ${COMP_STILL_SECS / 60} \u5206\u949f\u6ca1\u4ec0\u4e48\u52a8\u9759\u4e86\uff1b\u63d0\u9192$tip")
            }
        }
    }

    private fun nudge(kind: String, text: String) { val now = nowSeconds(); compLastNudge = now; compStillSince = now; listener?.onCompanionEvent(kind, text) }
    fun resetCompanion() { compPresent = null; compAbsentSince = 0L; compLeftReported = false; compLastNudge = 0L; compLastAssess = 0L; compStillSince = nowSeconds(); compReminders = 0 }
    fun reset() { lastThumb = null; lastDescAt = 0L; lastObservation = null; resetCompanion() }
    fun release() { scope.cancel() }
    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000
}