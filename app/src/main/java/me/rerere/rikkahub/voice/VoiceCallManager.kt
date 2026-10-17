package me.rerere.rikkahub.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "VoiceCallManager"

/**
 * Elian 语音通话管理器 v2 - pai-voice 完整算法
 *
 * 接口兼容现有 VoiceCallService 的调用方式：
 * - 构造: (context, scope, onUserSpeech, onStatusChange)
 * - configure() / startCall() / stopCall() / speak() / speakStreaming() / mute() / setThinking()
 *
 * 内部用 pai-voice 的好算法：
 * - VadDetector: 双阈值+看门狗+温和打断+噪声自适应+speakingGain
 * - AsrClient: 阿里云百炼 qwen3-asr-flash-realtime WebSocket 实时 ASR
 * - TtsPlayer: ElevenLabs 流式 TTS + MP3 硬解码 + generationId 队列
 * - SentenceSplitter: 流式分句
 */

data class VoiceCallManagerState(
    val isActive: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isThinking: Boolean = false,
    val isMuted: Boolean = false,
    val amplitude: Float = 0f,
    val emotion: String = ""
)

class VoiceCallManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onUserSpeech: suspend (String) -> Unit,
    private val onStatusChange: (VoiceCallManagerState) -> Unit
) {
    // ========== 配置 ==========
    private val SAMPLE_RATE = 16000
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val CHUNK_SAMPLES = 320  // ~20ms @16kHz

    private var asrApiKey = ""
    private var elevenLabsApiKey = ""
    private var elevenLabsVoiceId = ""

    // ========== 子组件（pai-voice 算法） ==========
    private val vad = VadDetector()  // 双阈值+看门狗+温和打断
    private var asr: AsrClient? = null
    private var tts: TtsPlayer? = null
    private val splitter = SentenceSplitter { sentence ->
        // 流式分句回调：每句切好后立刻调 TTS
        tts?.speak(sentence)
    }

    // ========== 状态 ==========
    private val _state = MutableStateFlow(VoiceCallManagerState())
    val state: StateFlow<VoiceCallManagerState> = _state.asStateFlow()

    private var audioRecord: AudioRecord? = null
    private var audioStreamJob: Job? = null
    private var currentGeneration = 0

    // ========== 公开方法 ==========

    fun configure(qwenKey: String, elevenKey: String, voiceId: String) {
        if (qwenKey.startsWith("sk-ws-")) {
            asrApiKey = qwenKey
        } else if (qwenKey.isNotBlank()) {
            asrApiKey = qwenKey
        }
        elevenLabsApiKey = elevenKey
        elevenLabsVoiceId = voiceId
    }

    suspend fun startCall() = withContext(Dispatchers.IO) {
        try {
            vad.reset()
            splitter.reset()

            // 初始化 ASR
            if (asrApiKey.isNotBlank()) {
                asr = AsrClient(apiKey = asrApiKey).also { it.connect() }
            }

            // 初始化 TTS
            if (elevenLabsApiKey.isNotBlank() && elevenLabsVoiceId.isNotBlank()) {
                tts = TtsPlayer(
                    apiKey = elevenLabsApiKey,
                    voiceId = elevenLabsVoiceId
                )
            }

            // 设置 VAD 回调
            vad.listener = object : VadDetector.Listener {
                override fun onLevel(rms: Float) {
                    updateState { it.copy(amplitude = rms) }
                }

                override fun onSpeechStart(barge: Boolean) {
                    if (barge) {
                        Log.i(TAG, "Barge-in! Stopping playback")
                        tts?.stopPlayback()
                        asr?.clearAudioBuffer()
                    }
                    updateState { it.copy(isListening = true, isSpeaking = false) }
                }

                override fun onSpeechEnd() {
                    asr?.commitAudioBuffer()
                    updateState { it.copy(isListening = true) }
                }
            }

            // 设置 TTS 回调
            tts?.listener = object : TtsPlayer.Listener {
                override fun onPlaybackStart() {
                    vad.isAiPlaying = true
                    vad.aiMode = "speaking"
                    updateState { it.copy(isSpeaking = true, isThinking = false) }
                }

                override fun onPlaybackEnd() {
                    vad.isAiPlaying = false
                    vad.aiMode = "listening"
                    updateState { it.copy(isSpeaking = false, isListening = true) }
                }

                override fun onPlaybackLevel(rms: Float) {
                    // TTS 播放音量可选用于 UI
                }

                override fun onLatency(ms: Long) {
                    Log.i(TAG, "TTS first chunk latency: ${ms}ms")
                }

                override fun onError(message: String) {
                    Log.e(TAG, "TTS error: $message")
                }
            }

            // 监听 ASR 事件
            scope.launch {
                asr?.events?.collect { event ->
                    when (event) {
                        is AsrClient.AsrEvent.Transcript -> {
                            if (event.text.isNotBlank()) {
                                Log.i(TAG, "ASR transcript: ${event.text}")
                                onUserSpeech(event.text)
                            }
                        }
                        is AsrClient.AsrEvent.Partial -> {
                            // 实时字幕，暂不处理
                        }
                        is AsrClient.AsrEvent.Connected -> Log.i(TAG, "ASR connected")
                        is AsrClient.AsrEvent.Closed -> {
                            Log.w(TAG, "ASR closed: ${event.reason}")
                            // 自动重连
                            if (_state.value.isActive) {
                                delay(1000)
                                asr?.connect()
                            }
                        }
                        is AsrClient.AsrEvent.Error -> Log.e(TAG, "ASR error: ${event.message}")
                    }
                }
            }

            // 初始化录音
            val bufferSize = maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                CHUNK_SAMPLES * 2 * 4
            )
            @Suppress("MissingPermission")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize
            ).also {
                if (it.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord init failed")
                    return@withContext
                }
                it.startRecording()
            }

            // 开始音频采集循环
            startAudioStreaming()

            updateState { VoiceCallManagerState(isActive = true, isListening = true) }
            Log.d(TAG, "Voice call started")

        } catch (e: Exception) {
            Log.e(TAG, "Start call failed", e)
            stopCall()
        }
    }

    fun stopCall() {
        audioStreamJob?.cancel()
        audioStreamJob = null

        asr?.disconnect()
        tts?.stopPlayback()

        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null

        vad.reset()
        splitter.reset()

        updateState { VoiceCallManagerState() }
        Log.d(TAG, "Voice call stopped")
    }

    /**
     * 整段文字 TTS 播放（VoiceCallService 调这个）。
     * 用 SentenceSplitter 流式分句，每句独立调 TTS。
     */
    suspend fun speakStreaming(fullText: String, generation: Int) {
        if (generation < currentGeneration) {
            Log.d(TAG, "Old generation=$generation, current=$currentGeneration, skip")
            return
        }
        currentGeneration = generation
        tts?.newGeneration()
        splitter.reset()

        updateState { it.copy(isSpeaking = true, isThinking = false) }

        // 流式分句 → 每句自动触发 TTS（通过 splitter 的回调）
        splitter.feed(fullText)
        splitter.flush()
    }

    /**
     * 单句 TTS 播放（兼容旧接口）。
     */
    suspend fun speak(text: String, generation: Int) {
        speakStreaming(text, generation)
    }

    fun setThinking(thinking: Boolean) {
        updateState { it.copy(isThinking = thinking) }
    }

    fun mute(muted: Boolean) {
        updateState { it.copy(isMuted = muted) }
        if (muted && vad.isSpeaking) {
            vad.reset()
        }
    }

    // ========== 音频采集循环 ==========

    private fun startAudioStreaming() {
        audioStreamJob?.cancel()
        audioStreamJob = scope.launch(Dispatchers.IO) {
            val buffer = ShortArray(CHUNK_SAMPLES)

            while (isActive) {
                if (!_state.value.isActive || _state.value.isMuted) {
                    delay(50)
                    continue
                }

                val record = audioRecord
                if (record == null || record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    delay(20)
                    continue
                }

                val read = try {
                    record.read(buffer, 0, CHUNK_SAMPLES)
                } catch (e: Exception) {
                    Log.e(TAG, "Mic read failed", e)
                    delay(100)
                    continue
                }

                if (read <= 0) {
                    delay(20)
                    continue
                }

                val chunk = if (read == CHUNK_SAMPLES) buffer else buffer.copyOf(read)

                // 1. 始终喂 VAD（即使 AI 在说话也喂，VAD 需要跟踪噪声底）
                vad.onAudioChunk(chunk)

                // 2. 说话中才推给 ASR
                if (vad.isSpeaking) {
                    asr?.pushAudio(chunk)
                }
            }
        }
    }

    // ========== 状态更新 ==========

    private inline fun updateState(transform: (VoiceCallManagerState) -> VoiceCallManagerState) {
        val newState = transform(_state.value)
        _state.value = newState
        onStatusChange(newState)
    }
}
