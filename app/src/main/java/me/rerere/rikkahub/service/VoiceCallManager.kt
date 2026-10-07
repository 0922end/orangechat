package me.rerere.rikkahub.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.abs
import kotlin.math.sqrt

private const val TAG = "VoiceCallManager"

/**
 * Elian 语音通话管理器 - 完整移植 pai-voice 算法
 * 
 * 核心功能：
 * 1. 精准 VAD（双阈值 + 看门狗 + 噪声自适应）
 * 2. 温和打断（只在 AI 说正事时打断，思考中不打断）
 * 3. 流式音频队列（边生成边播放）
 * 4. 直接调用云端 API（阿里云 ASR + ElevenLabs TTS）
 */
class VoiceCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUserSpeech: suspend (String) -> Unit,
    private val onStatusChange: (VoiceCallManagerState) -> Unit
) {
    // ========== 配置参数（来自 pai-voice）==========
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT) * 2
    
    // VAD 参数（pai-voice voice-call.js）
    private val VAD_ENTER_THRESHOLD = 0.05f      // 进入说话状态的能量阈值
    private val VAD_EXIT_THRESHOLD = 0.03f       // 退出说话状态的能量阈值（更低，避免抖动）
    private val SILENCE_DURATION_MS = 1200L      // 静音多久算说完（pai-voice 是 1.2秒）
    private val WATCHDOG_TIMEOUT_MS = 8000L      // 看门狗：超过8秒强制收尾
    private val SPEAKING_GAIN = 3.3f             // Speaking 状态下提高阈值倍数（过滤 TTS 回声）
    
    // 噪声自适应参数
    private val NOISE_ALPHA = 0.95f              // 底噪基线的平滑系数
    private var noiseFloor = 0.02f               // 当前底噪基线（动态调整）
    
    // API 配置
    private var qwenApiKey = ""
    private var elevenLabsApiKey = ""
    private var elevenLabsVoiceId = ""
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()
    
    // ========== 状态 ==========
    private val _state = MutableStateFlow(VoiceCallManagerState())
    val state: StateFlow<VoiceCallManagerState> = _state.asStateFlow()
    
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    
    private var vadJob: Job? = null
    private var playbackJob: Job? = null
    
    // 音频队列（流式播放）
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isPlaybackActive = false
    private var currentPlaybackGeneration = 0
    
    // VAD 状态
    private var isSpeaking = false
    private var speechStartTime = 0L
    private var lastSpeechTime = 0L
    private var watchdogStartTime = 0L
    private val recordedChunks = mutableListOf<ByteArray>()
    
    // ========== 公开方法 ==========
    
    fun configure(qwenKey: String, elevenKey: String, voiceId: String) {
        qwenApiKey = qwenKey
        elevenLabsApiKey = elevenKey
        elevenLabsVoiceId = voiceId
    }
    
    suspend fun startCall() = withContext(Dispatchers.IO) {
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                BUFFER_SIZE
            )
            
            audioTrack = AudioTrack.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AUDIO_FORMAT)
                        .build()
                )
                .setBufferSizeInBytes(BUFFER_SIZE)
                .build()
            
            audioRecord?.startRecording()
            audioTrack?.play()
            
            _state.value = _state.value.copy(isActive = true, isListening = true)
            onStatusChange(_state.value)
            
            startVAD()
            
            Log.d(TAG, "通话已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动通话失败", e)
            stopCall()
        }
    }
    
    fun stopCall() {
        vadJob?.cancel()
        playbackJob?.cancel()
        
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        
        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null
        
        audioQueue.clear()
        recordedChunks.clear()
        
        _state.value = VoiceCallManagerState()
        onStatusChange(_state.value)
        
        Log.d(TAG, "通话已停止")
    }
    
    suspend fun speak(text: String, generation: Int) {
        if (generation < currentPlaybackGeneration) {
            Log.d(TAG, "旧generation=$generation 丢弃（当前=$currentPlaybackGeneration）")
            return
        }
        
        currentPlaybackGeneration = generation
        
        // 温和打断：只在说正事时打断
        if (isPlaybackActive && !_state.value.isThinking) {
            stopPlayback()
        }
        
        _state.value = _state.value.copy(isSpeaking = true, isThinking = false)
        onStatusChange(_state.value)
        
        try {
            // 调用 ElevenLabs TTS API
            val audioData = callElevenLabsTTS(text)
            if (audioData.isNotEmpty()) {
                audioQueue.offer(audioData)
                if (!isPlaybackActive) {
                    startPlayback(generation)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS 失败", e)
            _state.value = _state.value.copy(isSpeaking = false, isListening = true)
            onStatusChange(_state.value)
        }
    }
    
    fun setThinking(thinking: Boolean) {
        _state.value = _state.value.copy(isThinking = thinking)
        onStatusChange(_state.value)
    }
    
    fun mute(muted: Boolean) {
        _state.value = _state.value.copy(isMuted = muted)
        onStatusChange(_state.value)
    }
    
    // ========== VAD 核心算法（pai-voice voice-call.js）==========
    
    private fun startVAD() {
        vadJob?.cancel()
        vadJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)
            
            while (isActive) {
                if (_state.value.isMuted) {
                    delay(100)
                    continue
                }
                
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read <= 0) continue
                
                // 计算音频能量（RMS）
                val energy = calculateRMS(buffer, read)
                
                // 更新底噪基线（噪声自适应）
                if (!isSpeaking) {
                    noiseFloor = NOISE_ALPHA * noiseFloor + (1 - NOISE_ALPHA) * energy
                }
                
                // 根据当前状态调整阈值（Speaking 时提高阈值过滤 TTS 回声）
                val effectiveEnterThreshold = if (_state.value.isSpeaking) {
                    VAD_ENTER_THRESHOLD * SPEAKING_GAIN
                } else {
                    VAD_ENTER_THRESHOLD
                }
                
                val effectiveExitThreshold = if (_state.value.isSpeaking) {
                    VAD_EXIT_THRESHOLD * SPEAKING_GAIN
                } else {
                    VAD_EXIT_THRESHOLD
                }
                
                val now = System.currentTimeMillis()
                
                // 双阈值状态机
                when {
                    !isSpeaking && energy > effectiveEnterThreshold -> {
                        // 进入说话状态
                        isSpeaking = true
                        speechStartTime = now
                        watchdogStartTime = now
                        lastSpeechTime = now
                        recordedChunks.clear()
                        recordedChunks.add(buffer.copyOf(read))
                        Log.d(TAG, "检测到说话开始 energy=$energy")
                    }
                    
                    isSpeaking && energy > effectiveExitThreshold -> {
                        // 持续说话中
                        lastSpeechTime = now
                        recordedChunks.add(buffer.copyOf(read))
                        
                        // 看门狗：说太久强制收尾
                        if (now - watchdogStartTime > WATCHDOG_TIMEOUT_MS) {
                            Log.d(TAG, "看门狗触发：说话超过${WATCHDOG_TIMEOUT_MS}ms")
                            handleSpeechEnd()
                        }
                    }
                    
                    isSpeaking && energy <= effectiveExitThreshold -> {
                        // 静音检测
                        if (now - lastSpeechTime > SILENCE_DURATION_MS) {
                            Log.d(TAG, "检测到说话结束（静音${now - lastSpeechTime}ms）")
                            handleSpeechEnd()
                        } else {
                            // 静音时间不够，继续录
                            recordedChunks.add(buffer.copyOf(read))
                        }
                    }
                }
                
                // 更新音量显示
                _state.value = _state.value.copy(amplitude = energy)
                
                delay(50) // 20 FPS
            }
        }
    }
    
    private suspend fun handleSpeechEnd() {
        isSpeaking = false
        
        if (recordedChunks.isEmpty()) {
            Log.d(TAG, "录音为空，跳过")
            return
        }
        
        // 合并所有录音块
        val audioBytes = ByteArrayOutputStream().apply {
            recordedChunks.forEach { write(it) }
        }.toByteArray()
        
        recordedChunks.clear()
        
        // 调用 ASR
        try {
            val transcript = callQwenASR(audioBytes)
            if (transcript.isNotBlank()) {
                Log.d(TAG, "ASR 转写: $transcript")
                onUserSpeech(transcript)
            }
        } catch (e: Exception) {
            Log.e(TAG, "ASR 失败", e)
        }
    }
    
    // ========== 音频播放（流式队列）==========
    
    private fun startPlayback(generation: Int) {
        if (isPlaybackActive) return
        
        isPlaybackActive = true
        playbackJob?.cancel()
        playbackJob = scope.launch(Dispatchers.IO) {
            while (isActive && generation == currentPlaybackGeneration) {
                val chunk = audioQueue.poll()
                if (chunk != null) {
                    audioTrack?.write(chunk, 0, chunk.size)
                } else if (!_state.value.isSpeaking) {
                    // TTS 生成完了且队列空了
                    break
                } else {
                    delay(50)
                }
            }
            
            isPlaybackActive = false
            _state.value = _state.value.copy(isSpeaking = false, isListening = true)
            onStatusChange(_state.value)
            Log.d(TAG, "播放完成")
        }
    }
    
    private fun stopPlayback() {
        playbackJob?.cancel()
        audioQueue.clear()
        audioTrack?.pause()
        audioTrack?.flush()
        audioTrack?.play()
        isPlaybackActive = false
        Log.d(TAG, "温和打断：停止播放")
    }
    
    // ========== API 调用 ==========
    
    private suspend fun callQwenASR(audioData: ByteArray): String = withContext(Dispatchers.IO) {
        val base64Audio = Base64.getEncoder().encodeToString(audioData)
        
        val json = JSONObject().apply {
            put("model", "paraformer-v2")
            put("input", JSONObject().apply {
                put("audio", base64Audio)
                put("sample_rate", SAMPLE_RATE)
                put("format", "pcm")
            })
        }
        
        val request = Request.Builder()
            .url("https://dashscope.aliyuncs.com/api/v1/services/audio/asr/transcription")
            .addHeader("Authorization", "Bearer $qwenApiKey")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("ASR API 失败: ${response.code}")
        }
        
        val result = JSONObject(response.body?.string() ?: "{}")
        return@withContext result.optJSONObject("output")?.optString("text") ?: ""
    }
    
    private suspend fun callElevenLabsTTS(text: String): ByteArray = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_turbo_v2_5")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.8)
            })
        }
        
        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$elevenLabsVoiceId")
            .addHeader("xi-api-key", elevenLabsApiKey)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()
        
        val response = httpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            throw Exception("TTS API 失败: ${response.code}")
        }
        
        return@withContext response.body?.bytes() ?: ByteArray(0)
    }
    
    // ========== 工具函数 ==========
    
    private fun calculateRMS(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var i = 0
        while (i < length - 1) {
            val sample = (buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xFF)
            sum += sample * sample
            i += 2
        }
        val rms = sqrt(sum / (length / 2))
        return (rms / 32768.0).toFloat()
    }
}

// ========== 状态数据类 ==========

data class VoiceCallManagerState(
    val isActive: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isThinking: Boolean = false,
    val isMuted: Boolean = false,
    val amplitude: Float = 0f
)