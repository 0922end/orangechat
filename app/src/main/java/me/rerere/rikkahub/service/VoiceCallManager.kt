package me.rerere.rikkahub.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.service.SentenceSplitter
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.min
import kotlin.math.sqrt

private const val TAG = "VoiceCallManager"
private const val ASR_TAG = "RealtimeAsrClient"

/**
 * Elian 语音通话管理器 - 完整移植 pai-voice 算法
 *
 * 核心功能：
 * 1. 实时 ASR（qwen3-asr-flash-realtime WebSocket，服务端 VAD 自动断句）
 * 2. 温和打断（只在 AI 说正事时打断，思考中不打断）
 * 3. 流式音频队列（边生成边播放）
 * 4. 直接调用云端 API（阿里云实时 ASR + ElevenLabs TTS）
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

    // 客户端 VAD 仅用于音量动画/底噪自适应，断句完全交给服务端 VAD
    private val VAD_ENTER_THRESHOLD = 0.05f
    private val VAD_EXIT_THRESHOLD = 0.03f
    private val NOISE_ALPHA = 0.95f
    private var noiseFloor = 0.02f

    // API 配置
    private var realtimeAsrApiKey = RealtimeAsrClient.DEFAULT_API_KEY
    private var elevenLabsApiKey = ""
    private var elevenLabsVoiceId = ""

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .pingInterval(20, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ========== 状态 ==========
    private val _state = MutableStateFlow(VoiceCallManagerState())
    val state: StateFlow<VoiceCallManagerState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null

    private var audioStreamJob: Job? = null
    private var playbackJob: Job? = null
    private var realtimeAsrClient: RealtimeAsrClient? = null

    // 音频队列（流式播放）
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var isPlaybackActive = false
    private var currentPlaybackGeneration = 0

    // 客户端 VAD 状态（只服务于音量动画）
    private var isSpeaking = false

    // ========== 公开方法 ==========

    fun configure(qwenKey: String, elevenKey: String, voiceId: String) {
        // 兼容旧配置：只有实时 ASR 专用 Key（sk-ws- 前缀）才覆盖内置端点密钥
        if (qwenKey.startsWith("sk-ws-")) {
            realtimeAsrApiKey = qwenKey
        }
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

            startRealtimeAsr()
            startAudioStreaming()

            Log.d(TAG, "通话已启动")
        } catch (e: Exception) {
            Log.e(TAG, "启动通话失败", e)
            stopCall()
        }
    }

    fun stopCall() {
        audioStreamJob?.cancel()
        audioStreamJob = null
        playbackJob?.cancel()

        realtimeAsrClient?.stop()
        realtimeAsrClient = null

        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        audioTrack?.stop()
        audioTrack?.release()
        audioTrack = null

        audioQueue.clear()

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

        // 回声防护：TTS 播放期间直接暂停 AudioRecord，避免录到 TTS 声音
        audioRecord?.stop()

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
            audioRecord?.startRecording()
            _state.value = _state.value.copy(isSpeaking = false, isListening = true)
            onStatusChange(_state.value)
        }
    }

    /**
     * 流式 TTS：先用切句器把整段回复切成短句，再逐句请求 TTS 并依次入队播放。
     */
    suspend fun speakStreaming(fullText: String, generation: Int) {
        if (generation < currentPlaybackGeneration) {
            Log.d(TAG, "旧generation=$generation 丢弃（当前=$currentPlaybackGeneration）")
            return
        }

        currentPlaybackGeneration = generation

        // 回声防护：TTS 播放期间直接暂停 AudioRecord，避免录到 TTS 声音
        audioRecord?.stop()

        // 温和打断：只在说正事时打断
        if (isPlaybackActive && !_state.value.isThinking) {
            stopPlayback()
        }

        _state.value = _state.value.copy(isSpeaking = true, isThinking = false)
        onStatusChange(_state.value)

        try {
            var enqueued = false
            for (segment in SentenceSplitter.split(fullText)) {
                if (generation < currentPlaybackGeneration) break

                val audioData = callElevenLabsTTS(segment)
                if (audioData.isNotEmpty()) {
                    audioQueue.offer(audioData)
                    enqueued = true
                    if (!isPlaybackActive) {
                        startPlayback(generation)
                    }
                }
            }

            if (!enqueued) {
                audioRecord?.startRecording()
                _state.value = _state.value.copy(isSpeaking = false, isListening = true)
                onStatusChange(_state.value)
            }
        } catch (e: Exception) {
            Log.e(TAG, "流式 TTS 失败", e)
            audioRecord?.startRecording()
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

    // ========== 实时 ASR（WebSocket + 服务端 VAD）==========

    private fun startRealtimeAsr() {
        realtimeAsrClient?.stop()
        realtimeAsrClient = RealtimeAsrClient(
            httpClient = httpClient,
            scope = scope,
            apiKey = realtimeAsrApiKey,
            onReady = { Log.d(TAG, "实时 ASR 会话就绪") },
            onTranscript = { transcript, emotion ->
                _state.value = _state.value.copy(emotion = emotion)
                onStatusChange(_state.value)

                if (transcript.isNotBlank()) {
                    Log.d(TAG, "ASR 转写: $transcript (emotion=$emotion)")
                    scope.launch { onUserSpeech(transcript) }
                }
            },
            onConnectionLost = { cause ->
                Log.w(TAG, "实时 ASR 连接断开，客户端将自动重连", cause)
            },
            onError = { message ->
                Log.w(TAG, "实时 ASR 错误: $message")
            }
        ).also { it.start() }
    }

    /**
     * 持续采集麦克风并推送到实时 ASR。
     * 服务端 VAD 负责断句，客户端只计算音量用于 UI 动画。
     */
    private fun startAudioStreaming() {
        audioStreamJob?.cancel()
        audioStreamJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(BUFFER_SIZE)

            while (isActive) {
                if (!_state.value.isActive || _state.value.isMuted) {
                    delay(50)
                    continue
                }

                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    // TTS 播放期间会暂停录音，避免把扬声器声音送进 ASR
                    delay(20)
                    continue
                }

                val read = try {
                    record.read(buffer, 0, buffer.size)
                } catch (e: Exception) {
                    Log.e(TAG, "读取麦克风失败", e)
                    delay(100)
                    continue
                }
                if (read <= 0) {
                    delay(20)
                    continue
                }

                realtimeAsrClient?.sendAudio(buffer.copyOf(read))

                // 客户端 VAD 只用于噪声自适应和音量动画
                val energy = calculateRMS(buffer, read)
                updateClientVad(energy)
                _state.value = _state.value.copy(amplitude = energy)
            }
        }
    }

    private fun updateClientVad(energy: Float) {
        if (!isSpeaking) {
            noiseFloor = NOISE_ALPHA * noiseFloor + (1 - NOISE_ALPHA) * energy
        }
        isSpeaking = when {
            !isSpeaking && energy > VAD_ENTER_THRESHOLD -> true
            isSpeaking && energy <= VAD_EXIT_THRESHOLD -> false
            else -> isSpeaking
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
            // 播放完成，恢复录音
            audioRecord?.startRecording()
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

    private suspend fun callElevenLabsTTS(text: String): ByteArray = withContext(Dispatchers.IO) {
        val json = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_v3")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.3)
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

    // ========== 实时 ASR 客户端（内嵌 WebSocket 组件）==========

    /**
     * 基于 WebSocket 的 qwen3-asr-flash-realtime 客户端。
     *
     * 协议与 OpenAI Realtime 兼容：
     * 1. 连接成功后发送 session.update，开启服务端 VAD（silence_duration_ms = 800）；
     * 2. 持续发送 input_audio_buffer.append（Base64 编码的 PCM16 16kHz 单声道）；
     * 3. 服务端 VAD 切句后返回 conversation.item.input_audio_transcription.completed，
     *    从中读取 transcript 与 emotion。
     *
     * 连接意外断开时会在通话期间自动重连（指数退避）。
     */
    private class RealtimeAsrClient(
        private val httpClient: OkHttpClient,
        private val scope: CoroutineScope,
        private val apiKey: String = DEFAULT_API_KEY,
        private val url: String = DEFAULT_URL,
        private val onReady: () -> Unit = {},
        private val onTranscript: (String, String) -> Unit,
        private val onConnectionLost: (Throwable?) -> Unit = {},
        private val onError: (String) -> Unit = {},
    ) {
        companion object {
            const val DEFAULT_URL =
                "wss://ws-j0d35q9jai174ox1.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference" +
                    "?model=qwen3-asr-flash-realtime"

            // 该实时 ASR 端点使用独立的 MaaS API Key。
            const val DEFAULT_API_KEY =
                "sk-ws-H.PYIDHYY.h6nt.MEUCIQD2dc1xY0UjRZ_C-yCXHRN29V2fryDBhJBr9lYKFn-PGQIgIdLUIrZ3zbe3AVB0Hw7" +
                    "E3SUWPE63xEwp7Rp8rXbr3lo"

            private const val MAX_QUEUED_AUDIO_CHUNKS = 32L
            private const val RECONNECT_BASE_DELAY_MS = 500L
            private const val RECONNECT_MAX_DELAY_MS = 8_000L

            private val SUPPORTED_EMOTIONS = setOf(
                "surprised", "neutral", "happy", "sad", "disgusted", "angry", "fearful"
            )
        }

        private val lock = Any()
        private var webSocket: WebSocket? = null
        private var reconnectJob: Job? = null
        private var reconnectAttempt = 0
        private var running = false

        @Volatile
        private var ready = false

        /** 开始连接。重复调用无副作用。 */
        fun start() {
            synchronized(lock) {
                if (running) return
                running = true
                ready = false
                reconnectAttempt = 0
                reconnectJob?.cancel()
                reconnectJob = null
                openSocketLocked()
            }
        }

        /** 结束会话并关闭连接；停止后不会再自动重连。 */
        fun stop() {
            val socket = synchronized(lock) {
                running = false
                ready = false
                reconnectJob?.cancel()
                reconnectJob = null
                webSocket.also { webSocket = null }
            }
            socket ?: return
            runCatching {
                // VAD 模式下先 finish 再关闭，服务端才能正常收尾。
                socket.send(
                    JSONObject()
                        .put("event_id", "event_${System.nanoTime()}")
                        .put("type", "session.finish")
                        .toString()
                )
                socket.close(1000, "call ended")
            }.onFailure { Log.w(ASR_TAG, "关闭 WebSocket 失败: ${it.message}") }
        }

        /**
         * 发送一块 PCM16 16kHz 单声道音频。
         * 未连接或服务端会话尚未就绪时直接丢弃，避免在握手阶段堆积音频。
         */
        fun sendAudio(pcm16: ByteArray) {
            if (pcm16.isEmpty()) return
            val socket = synchronized(lock) {
                if (!running || !ready) null else webSocket
            } ?: return

            // 网络拥塞时丢弃音频，避免 OkHttp 的发送队列无限增长。
            val queued = socket.queueSize()
            if (queued > MAX_QUEUED_AUDIO_CHUNKS) {
                Log.w(ASR_TAG, "发送队列积压($queued)，丢弃音频块")
                return
            }

            val append = JSONObject()
                .put("type", "input_audio_buffer.append")
                .put("event_id", "event_${System.nanoTime()}")
                .put("audio", Base64.encodeToString(pcm16, Base64.NO_WRAP))
            if (!socket.send(append.toString())) {
                Log.w(ASR_TAG, "音频块入队失败")
            }
        }

        private fun openSocketLocked() {
            if (!running) return
            ready = false
            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Bearer $apiKey")
                .build()
            try {
                webSocket = httpClient.newWebSocket(request, listener)
                Log.d(ASR_TAG, "WebSocket 连接中: $url")
            } catch (e: Exception) {
                Log.e(ASR_TAG, "WebSocket 连接失败", e)
                handleDisconnectLocked(null, e)
            }
        }

        private fun configureSession(socket: WebSocket) {
            val turnDetection = JSONObject()
                .put("type", "server_vad")
                .put("threshold", 0.5)
                .put("silence_duration_ms", 800)
            val session = JSONObject()
                .put("input_audio_format", "pcm")
                .put("modalities", JSONArray().put("text"))
                .put("turn_detection", turnDetection)
            val update = JSONObject()
                .put("event_id", "event_${System.nanoTime()}")
                .put("type", "session.update")
                .put("session", session)
            if (!socket.send(update.toString())) {
                Log.w(ASR_TAG, "session.update 发送失败")
            }
        }

        private val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(webSocket)) return
                Log.i(ASR_TAG, "WebSocket 已连接")
                configureSession(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (!isCurrent(webSocket)) return
                handleEvent(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(ASR_TAG, "WebSocket 关闭中: code=$code reason=$reason")
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!isCurrent(webSocket)) return
                Log.w(ASR_TAG, "WebSocket 已关闭: code=$code reason=$reason")
                handleDisconnect(webSocket, null)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!isCurrent(webSocket)) return
                Log.e(ASR_TAG, "WebSocket 失败: ${t.message}", t)
                handleDisconnect(webSocket, t)
            }
        }

        private fun isCurrent(socket: WebSocket): Boolean = synchronized(lock) {
            webSocket === socket
        }

        private fun handleEvent(raw: String) {
            val json = try {
                JSONObject(raw)
            } catch (e: Exception) {
                Log.w(ASR_TAG, "无法解析事件: ${raw.take(200)}", e)
                return
            }

            when (json.optString("type")) {
                "session.created", "session.updated" -> {
                    synchronized(lock) {
                        ready = true
                        reconnectAttempt = 0
                    }
                    Log.i(ASR_TAG, "ASR 会话就绪: ${json.optJSONObject("session")?.optString("id")}")
                    onReady()
                }

                "input_audio_buffer.speech_started" -> Log.d(ASR_TAG, "服务端 VAD: 说话开始")
                "input_audio_buffer.speech_stopped" -> Log.d(ASR_TAG, "服务端 VAD: 说话结束")

                "conversation.item.input_audio_transcription.completed" -> {
                    // qwen3-asr 返回 transcript；兼容部分网关版本返回 text 的格式
                    val transcript = json.optString("transcript")
                        .ifBlank { json.optString("text") }
                        .trim()
                    val emotion = normalizeEmotion(json.optString("emotion"))
                    Log.d(ASR_TAG, "ASR 完成: $transcript, emotion=$emotion")
                    onTranscript(transcript, emotion)
                }

                "conversation.item.input_audio_transcription.failed" -> {
                    val message = json.optJSONObject("error")?.optString("message") ?: "转写失败"
                    Log.w(ASR_TAG, "ASR 转写失败: $message")
                    onError(message)
                }

                "error" -> {
                    val message = json.optJSONObject("error")?.optString("message") ?: json.toString()
                    Log.e(ASR_TAG, "ASR 服务端错误: $message")
                    onError(message)
                }
            }
        }

        private fun normalizeEmotion(raw: String?): String {
            val emotion = raw?.lowercase()?.trim().orEmpty()
            return if (emotion in SUPPORTED_EMOTIONS) emotion else "neutral"
        }

        private fun handleDisconnect(socket: WebSocket?, cause: Throwable?) {
            synchronized(lock) {
                if (webSocket !== socket) return
                handleDisconnectLocked(socket, cause)
            }
        }

        private fun handleDisconnectLocked(socket: WebSocket?, cause: Throwable?) {
            webSocket = null
            ready = false
            if (!running) return
            if (reconnectJob?.isActive == true) return

            reconnectAttempt++
            val delayMs = (RECONNECT_BASE_DELAY_MS shl min(reconnectAttempt - 1, 5))
                .coerceAtMost(RECONNECT_MAX_DELAY_MS)
            Log.w(ASR_TAG, "ASR 连接断开，${delayMs}ms 后重连（第 $reconnectAttempt 次）", cause)
            onConnectionLost(cause)

            reconnectJob = scope.launch {
                delay(delayMs)
                synchronized(lock) {
                    reconnectJob = null
                    if (running) openSocketLocked()
                }
            }
        }
    }
}

// ========== 状态数据类 ==========

data class VoiceCallManagerState(
    val isActive: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isThinking: Boolean = false,
    val isMuted: Boolean = false,
    val amplitude: Float = 0f,
    /** 服务端 ASR 识别出的用户情绪，供 UI / 心潮集成读取 */
    val emotion: String = "neutral"
)
