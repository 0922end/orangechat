package me.rerere.rikkahub.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.*
import kotlin.math.abs

private const val TAG = "VisionChain"

/**
 * Elian 视觉识别链 - 完整移植 pai-voice vision.py
 * 
 * 核心功能：
 * 1. 多模型降级链（百炼 → Groq → 用户自定义）
 * 2. 画面变化检测（32×32 灰度缩略图像素差）
 * 3. 陪伴模式状态机（在座/离开/小动作）
 */
class VisionChain(
    private val context: Context,
    private val scope: CoroutineScope
) {
    // ========== 配置参数（来自 pai-vision）==========
    private val THUMBNAIL_SIZE = 32                    // 缩略图尺寸
    private val CHANGE_THRESHOLD = 0.15f               // 画面变化阈值（15%像素变化）
    private val MODEL_TIMEOUT_MS = 12000L              // 单个模型超时12秒
    private val TOTAL_TIMEOUT_MS = 25000L              // 整轮超时25秒
    
    // ========== 陪伴模式状态 ==========
    private var lastPresent = true
    private var absentSince = 0L
    private var lastActivity = ""
    private var lastNotificationTime = 0L
    private val ABSENT_NOTIFY_THRESHOLD_MS = 120000L   // 离开2分钟提醒
    private val IDLE_NOTIFY_THRESHOLD_MS = 1800000L    // 30分钟没动静提醒
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // ========== 缓存上一帧缩略图 ==========
    private var lastThumbnailHash: String? = null
    
    // ========== 公开方法 ==========
    
    /**
     * 描述画面（实时模式）
     * @param jpeg JPEG格式图片字节数组
     * @return 画面描述文字，如"你在电脑前工作，桌上有咖啡杯"
     */
    suspend fun describe(jpeg: ByteArray): String? {
        val backends = loadVisionBackends()
        if (backends.isEmpty()) {
            Log.w(TAG, "没有配置任何视觉模型")
            return null
        }
        
        val base64 = Base64.getEncoder().encodeToString(jpeg)
        val prompt = "用中文两句话描述画面，简洁具体"
        
        return withTimeout(TOTAL_TIMEOUT_MS) {
            for (backend in backends) {
                try {
                    val result = withTimeout(MODEL_TIMEOUT_MS) {
                        callVisionModel(backend, base64, prompt)
                    }
                    if (result.isNotBlank()) {
                        Log.d(TAG, "视觉模型 ${backend.name} 返回: $result")
                        return@withTimeout result
                    }
                } catch (e: TimeoutCancellationException) {
                    Log.w(TAG, "视觉模型 ${backend.name} 超时，切换下一个")
                } catch (e: Exception) {
                    Log.w(TAG, "视觉模型 ${backend.name} 失败: ${e.message}，切换下一个")
                }
            }
            return@withTimeout null
        }
    }
    
    /**
     * 陪伴模式：评估画面并返回结构化JSON
     * @param jpeg JPEG格式图片字节数组
     * @return CompanionAssessment 或 null
     */
    suspend fun assess(jpeg: ByteArray): CompanionAssessment? {
        val backends = loadVisionBackends()
        if (backends.isEmpty()) return null
        
        val base64 = Base64.getEncoder().encodeToString(jpeg)
        val prompt = """
用JSON格式回答，只返回JSON不要其他文字：
{
  "present": true/false,
  "activity": "在做什么（如：敲键盘）",
  "notable": "值得搭话的小动作（如：伸懒腰），没有就空字符串"
}
        """.trimIndent()
        
        return withTimeout(TOTAL_TIMEOUT_MS) {
            for (backend in backends) {
                try {
                    val result = withTimeout(MODEL_TIMEOUT_MS) {
                        callVisionModel(backend, base64, prompt)
                    }
                    if (result.isNotBlank()) {
                        return@withTimeout parseAssessment(result)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "陪伴模式评估失败: ${e.message}")
                }
            }
            return@withTimeout null
        }
    }
    
    /**
     * 检测画面是否变化（pai-voice changed 函数）
     * @param jpeg JPEG格式图片字节数组
     * @return true 表示画面有明显变化
     */
    suspend fun changed(jpeg: ByteArray): Boolean = withContext(Dispatchers.Default) {
        val currentHash = computeThumbnailHash(jpeg)
        val lastHash = lastThumbnailHash
        
        lastThumbnailHash = currentHash
        
        if (lastHash == null) {
            // 第一帧，算作变化
            return@withContext true
        }
        
        // 比较两个缩略图的像素差异
        val diff = hammingDistance(lastHash, currentHash)
        val totalPixels = THUMBNAIL_SIZE * THUMBNAIL_SIZE
        val changeRatio = diff.toFloat() / totalPixels
        
        Log.d(TAG, "画面变化率: ${(changeRatio * 100).toInt()}%")
        return@withContext changeRatio > CHANGE_THRESHOLD
    }
    
    /**
     * 陪伴模式状态机（pai-voice companion_frame 函数）
     * @param assessment 画面评估结果
     * @return 需要通知AI的消息，null表示不需要通知
     */
    fun companionStateMachine(assessment: CompanionAssessment): String? {
        val now = System.currentTimeMillis()
        
        // 检测离开/回来
        when {
            !assessment.present && lastPresent -> {
                // 刚离开
                absentSince = now
                lastPresent = false
                Log.d(TAG, "用户离开座位")
                return null // 刚离开不提醒
            }
            
            !assessment.present && !lastPresent -> {
                // 持续不在
                if (now - absentSince > ABSENT_NOTIFY_THRESHOLD_MS && 
                    now - lastNotificationTime > 60000L) {
                    // 离开超过2分钟，提醒一次
                    lastNotificationTime = now
                    Log.d(TAG, "用户离开超过2分钟，提醒")
                    return "[陪伴模式] 你离开座位${(now - absentSince) / 60000}分钟了，去哪了？"
                }
            }
            
            assessment.present && !lastPresent -> {
                // 回来了
                lastPresent = true
                absentSince = 0L
                Log.d(TAG, "用户回到座位")
                return "[陪伴模式] 回来啦"
            }
        }
        
        // 在座时检测小动作
        if (assessment.present && assessment.notable.isNotBlank()) {
            if (now - lastNotificationTime > 60000L) {
                lastNotificationTime = now
                Log.d(TAG, "检测到小动作: ${assessment.notable}")
                return "[陪伴模式] ${assessment.notable}，累了吗？"
            }
        }
        
        // 检测长时间无活动
        if (assessment.present && assessment.activity != lastActivity) {
            lastActivity = assessment.activity
            lastNotificationTime = now
        } else if (assessment.present && now - lastNotificationTime > IDLE_NOTIFY_THRESHOLD_MS) {
            lastNotificationTime = now
            Log.d(TAG, "30分钟没动静")
            return "[陪伴模式] 30分钟了，喝口水休息一下吧"
        }
        
        return null
    }
    
    // ========== 私有方法 ==========
    
    private fun loadVisionBackends(): List<VisionBackendConfig> {
        val prefs = context.getSharedPreferences("vision_config", Context.MODE_PRIVATE)
        val json = prefs.getString("backends", null)
        
        if (json != null) {
            try {
                val array = JSONArray(json)
                val list = mutableListOf<VisionBackendConfig>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val config = VisionBackendConfig(
                        name = obj.getString("name"),
                        apiKey = obj.getString("apiKey"),
                        baseUrl = obj.getString("baseUrl"),
                        model = obj.getString("model"),
                        enabled = obj.optBoolean("enabled", true)
                    )
                    if (config.enabled && config.apiKey.isNotEmpty()) {
                        list.add(config)
                    }
                }
                return list
            } catch (e: Exception) {
                Log.e(TAG, "解析视觉配置失败", e)
            }
        }
        
        // 返回默认配置：百炼 + Groq占位
        return listOf(
            VisionBackendConfig(
                name = "qwen",
                apiKey = prefs.getString("qwen_key", "") ?: "",
                baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
                model = "qwen-vl-max",
                enabled = true
            )
        ).filter { it.apiKey.isNotEmpty() }
    }
    
    private suspend fun callVisionModel(
        config: VisionBackendConfig,
        base64Image: String,
        prompt: String
    ): String = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("model", config.model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", prompt)
                        })
                        put(JSONObject().apply {
                            put("type", "image_url")
                            put("image_url", JSONObject().apply {
                                put("url", "data:image/jpeg;base64,$base64Image")
                            })
                        })
                    })
                })
            })
        }
        
        val request = Request.Builder()
            .url("${config.baseUrl}/chat/completions")
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("视觉API失败: ${response.code}")
        }
        
        val result = JSONObject(response.body?.string() ?: "{}")
        return@withContext result.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content") ?: ""
    }
    
    private fun parseAssessment(json: String): CompanionAssessment? {
        return try {
            // 提取JSON（可能被markdown包裹）
            val cleaned = json.trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            
            val obj = JSONObject(cleaned)
            CompanionAssessment(
                present = obj.optBoolean("present", false),
                activity = obj.optString("activity", ""),
                notable = obj.optString("notable", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "解析陪伴模式JSON失败: $json", e)
            null
        }
    }
    
    /**
     * 计算32×32灰度缩略图的哈希（pai-voice _thumb 函数）
     */
    private fun computeThumbnailHash(jpeg: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        val thumbnail = Bitmap.createScaledBitmap(bitmap, THUMBNAIL_SIZE, THUMBNAIL_SIZE, true)
        
        val hash = StringBuilder()
        for (y in 0 until THUMBNAIL_SIZE) {
            for (x in 0 until THUMBNAIL_SIZE) {
                val pixel = thumbnail.getPixel(x, y)
                val gray = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
                hash.append(if (gray > 128) '1' else '0')
            }
        }
        
        bitmap.recycle()
        thumbnail.recycle()
        
        return hash.toString()
    }
    
    /**
     * 计算两个二进制串的汉明距离（不同位数）
     */
    private fun hammingDistance(hash1: String, hash2: String): Int {
        if (hash1.length != hash2.length) return Int.MAX_VALUE
        return hash1.zip(hash2).count { (a, b) -> a != b }
    }
}

// ========== 数据类 ==========

data class VisionBackendConfig(
    val name: String,
    val apiKey: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = true
)

data class CompanionAssessment(
    val present: Boolean,      // 画面里有没有人
    val activity: String,      // 在做什么
    val notable: String        // 值得搭话的小动作
)