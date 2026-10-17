package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.abs

private const val TAG = "VisionChain"

/**
 * Elian 视觉链 - 从 pai-voice vision.py 逐行移植
 *
 * 功能：
 * 1. 32x32 灰度缩图 + 帧差检测（对应 _thumb + changed）
 * 2. 多后端 fallback 视觉描述链（对应 backend_chain + _run + _describe）
 * 3. 陪伴模式评估（对应 assess + parse_assessment）
 * 4. 单次超时 VISION_TIMEOUT=12s + 整轮超时 VISION_TOTAL_TIMEOUT=25s
 * 5. MIN_INTERVAL=8s 防过频 + busy 防并发
 */
class VisionChain(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // ========== 常量（对应 vision.py 顶部环境变量，Android 端写死）==========
    companion object {
        const val VISION_TIMEOUT = 12_000L       // 单个后端超时（毫秒）(line 30)
        const val VISION_TOTAL_TIMEOUT = 25_000L // 整轮超时（毫秒）(line 31)
        const val DIFF_THRESHOLD = 0.06f          // 32x32 灰度平均像素差阈值 (line 32)
        const val MIN_INTERVAL = 8_000L           // 最小描述间隔（毫秒）(line 33)

        // 实时模式提示词 (line 34-37)
        const val PROMPT = "这是视频通话时从对方摄像头里抽的一帧。用中文、两句话以内描述你看到的：对方在哪、在做什么、表情和状态、有没有值得一提的细节。只描述，不评价，不打招呼，不加前缀。"

        // 陪伴模式提示词 (line 40-45)
        const val COMPANION_PROMPT = "这是视频通话中从对方摄像头里抽的一帧。对方在工作或学习，你只是安静的观察员。只输出一行 JSON，不要任何别的字：" +
            "{\"present\": 画面里有没有人（true/false）, \"activity\": \"在做什么，十个字以内\", " +
            "\"notable\": \"值得搭话的小动作：伸懒腰、趴桌、揉眼、打哈欠、明显发呆走神、一直玩手机、对镜头笑或摆手之类，十个字以内；正常工作学习就写空字符串\"}"
    }

    // ========== 后端链配置（对应 backend_chain，line 63-75）==========
    data class VisionBackend(
        val name: String,
        val baseUrl: String,
        val apiKey: String,
        val model: String,
        val proxy: String? = null
    )

    private var chain: List<VisionBackend> = emptyList()
    val enabled: Boolean get() = chain.isNotEmpty()

    /** 配置后端链。调用方按优先级传入，内部逐家尝试。(line 63-75) */
    fun configureBackends(backends: List<VisionBackend>) {
        chain = backends.filter {
            it.baseUrl.isNotBlank() && it.apiKey.isNotBlank() && it.model.isNotBlank()
        }
        Log.i(TAG, "vision 链：${chain.joinToString(" → ") { it.name }.ifEmpty { "off" }}")
    }

    // ========== 帧差检测状态（对应 Eyes.__init__，line 90-98）==========
    private var _lastThumb: FloatArray? = null  // (line 93)
    private var _lastDescAt = 0L                // (line 94) 毫秒
    @Volatile var busy = false                  // (line 95)
        private set
    var lastObservation: String? = null          // (line 96)
        private set
    var lastBackend: String? = null              // (line 97)
        private set

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(VISION_TIMEOUT + 2000, TimeUnit.MILLISECONDS)
        .readTimeout(VISION_TIMEOUT + 2000, TimeUnit.MILLISECONDS)
        .build()

    // ========== _thumb: JPEG → 32x32 灰度 (line 78-86) ==========
    private fun toGrayThumb(jpeg: ByteArray): FloatArray? {
        // 对应 vision.py _thumb (line 78-86)
        // Python: Image.open(io.BytesIO(jpeg)).convert("L").resize((32, 32))
        // Kotlin: BitmapFactory → createScaledBitmap → 手动灰度
        return try {
            val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
            val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options) ?: return null
            val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
            bitmap.recycle()

            val pixels = IntArray(32 * 32)
            scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
            scaled.recycle()

            // np.asarray(im, dtype="float32") / 255.0
            FloatArray(pixels.size) { i ->
                val p = pixels[i]
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                // PIL convert("L") 公式：L = 0.299*R + 0.587*G + 0.114*B
                (0.299f * r + 0.587f * g + 0.114f * b) / 255f
            }
        } catch (e: Exception) {
            // 对应 except Exception: return None (line 85-86)
            Log.w(TAG, "缩图失败", e)
            null
        }
    }

    // ========== changed (line 100-107) ==========
    fun changed(jpeg: ByteArray, threshold: Float = DIFF_THRESHOLD): Boolean {
        val t = toGrayThumb(jpeg)
        if (t == null) return true                         // (line 102-103)
        val prev = _lastThumb
        _lastThumb = t                                     // (line 104) prev, self._last_thumb = self._last_thumb, t
        if (prev == null) return true                      // (line 105-106)
        if (prev.size != t.size) return true
        // float(abs(t - prev).mean()) >= threshold        // (line 107)
        var sum = 0f
        for (i in t.indices) {
            sum += abs(t[i] - prev[i])
        }
        return (sum / t.size) >= threshold
    }

    // ========== should_describe (line 109-115) ==========
    fun shouldDescribe(jpeg: ByteArray): Boolean {
        if (!enabled || busy) return false                 // (line 110-111)
        val now = System.currentTimeMillis()
        if (now - _lastDescAt < MIN_INTERVAL) {            // (line 112)
            changed(jpeg)                                  // (line 113) 间隔内也更新基线
            return false                                   // (line 114)
        }
        return changed(jpeg)                               // (line 115)
    }

    // ========== describe (line 117-119) ==========
    suspend fun describe(jpeg: ByteArray): String? {
        return run(jpeg, PROMPT)
    }

    // ========== assess + parse_assessment (line 121-128 + line 48-60) ==========
    data class Assessment(
        val present: Boolean = true,
        val activity: String = "",
        val notable: String = ""
    )

    suspend fun assess(jpeg: ByteArray): Assessment? {
        val raw = run(jpeg, COMPANION_PROMPT)               // (line 123)
        if (raw == null) return null                        // (line 124-125)
        val d = parseAssessment(raw)                        // (line 126)
        // (line 127) self.last_observation = d["activity"] or ("画面里没人" if not d["present"] else None)
        lastObservation = d.activity.ifBlank {
            if (!d.present) "画面里没人" else null
        }
        return d                                            // (line 128)
    }

    /** 对应 parse_assessment (line 48-60) */
    private fun parseAssessment(text: String): Assessment {
        // 解模型回的那行 JSON；解不出就当"在、看不清"，不打扰。(line 49)
        val m = Regex("\\{.*\\}", RegexOption.DOT_MATCHES_ALL).find(text)
        if (m != null) {
            try {
                val d = JSONObject(m.value)
                return Assessment(
                    present = d.optBoolean("present", true),              // (line 55)
                    activity = (d.optString("activity") ?: "").trim().take(40), // (line 56)
                    notable = (d.optString("notable") ?: "").trim().take(40)   // (line 57)
                )
            } catch (_: Exception) { /* (line 58-59) pass */ }
        }
        // (line 60) return {"present": True, "activity": (text or "").strip()[:40], "notable": ""}
        return Assessment(present = true, activity = text.trim().take(40), notable = "")
    }

    // ========== _run: 多后端 fallback + 整轮超时 (line 130-157) ==========
    private suspend fun run(jpeg: ByteArray, prompt: String): String? {
        _lastDescAt = System.currentTimeMillis()            // (line 131)
        if (busy) return null                               // (line 132-133)
        busy = true                                         // (line 134)
        val started = System.currentTimeMillis()             // (line 135)
        try {
            for (backend in chain) {                         // (line 137)
                val elapsed = System.currentTimeMillis() - started
                val left = VISION_TOTAL_TIMEOUT - elapsed    // (line 138)
                if (left <= 1000) {                          // (line 139)
                    Log.w(TAG, "画面描述整轮超过 ${VISION_TOTAL_TIMEOUT/1000}s，这帧丢掉") // (line 140)
                    return null                              // (line 141)
                }
                val timeoutMs = minOf(VISION_TIMEOUT, left)  // (line 143) min(VISION_TIMEOUT, left)
                try {
                    // asyncio.wait_for(self._describe(...), timeout=...) (line 143)
                    val desc = withTimeoutOrNull(timeoutMs) {
                        describeWithBackend(jpeg, backend, prompt)
                    }
                    if (desc == null) {
                        // TimeoutError (line 144-146)
                        Log.w(TAG, "画面描述 ${backend.name} 超过 ${VISION_TIMEOUT/1000}s，换下一家")
                        continue
                    }
                    val trimmed = desc.trim()                // (line 150)
                    if (trimmed.isNotBlank()) {               // (line 151)
                        lastObservation = trimmed             // (line 152)
                        lastBackend = backend.name            // (line 153)
                        return trimmed                        // (line 154)
                    }
                } catch (e: Exception) {
                    // (line 147-149) except Exception as e: log.warning; continue
                    Log.w(TAG, "画面描述 ${backend.name} 失败：${e.message}，换下一家")
                    continue
                }
            }
            return null                                      // (line 155)
        } finally {
            busy = false                                     // (line 157)
        }
    }

    // ========== _describe: 单个后端调用 (line 159-176) ==========
    private suspend fun describeWithBackend(
        jpeg: ByteArray,
        backend: VisionBackend,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        val b64 = Base64.encodeToString(jpeg, Base64.NO_WRAP) // (line 160)
        // (line 161-168) body = {model, max_tokens, messages: [{role: user, content: [text, image_url]}]}
        val content = JSONArray().apply {
            put(JSONObject().put("type", "text").put("text", prompt))
            put(JSONObject().put("type", "image_url").put("image_url",
                JSONObject().put("url", "data:image/jpeg;base64,$b64")))
        }
        val messages = JSONArray().apply {
            put(JSONObject().put("role", "user").put("content", content))
        }
        val body = JSONObject().apply {
            put("model", backend.model)
            put("max_tokens", 600)
            put("messages", messages)
        }

        // (line 169-172) session.post(f"{backend['base']}/chat/completions", ...)
        val request = Request.Builder()
            .url("${backend.baseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer ${backend.apiKey}")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()
        val data = JSONObject(response.body?.string() ?: "{}")
        if (!response.isSuccessful) {                        // (line 174)
            throw RuntimeException(data.toString().take(200)) // (line 175)
        }
        // (line 176) data["choices"][0]["message"]["content"]
        data.getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }
}
