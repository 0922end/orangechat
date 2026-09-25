package me.rerere.rikkahub.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.createCustomAsrState
import me.rerere.rikkahub.ui.hooks.createCustomTtsState
import me.rerere.rikkahub.ui.pages.voice.DialogueLine
import me.rerere.rikkahub.ui.pages.voice.VoiceCallStatus
import me.rerere.rikkahub.ui.pages.voice.VoiceCallUiState
import okhttp3.OkHttpClient
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"

/**
 * Elian 语音通话服务 — pai-voice turn管理 + 橘瓣chatService
 *
 * 核心改动:
 * 1. generation 机制: 每轮对话递增, 旧回复 generation 不匹配则丢弃
 * 2. Mode B 消息队列: TTS 播放/AI思考期间用户新话排队, 播完再处理
 * 3. 独白机制: AI回复 [独白] 前缀的内容只显示不播 TTS
 * 4. 通话计时: 实时秒数
 * 5. 对话记录: 完整 dialogue 列表供 UI 滚动显示
 */
class VoiceCallService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val httpClient: OkHttpClient by inject()
    private val settingsStore: SettingsStore by inject()

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "coroutine exception", e)
        }
    )

    private lateinit var conversationId: Uuid
    private lateinit var asr: CustomAsrState
    private lateinit var tts: CustomTtsState

    private val _uiState = MutableStateFlow(VoiceCallUiState())
    val uiState: StateFlow<VoiceCallUiState> = _uiState.asStateFlow()

    val conversation: StateFlow<Conversation>
        get() = chatService.getConversationFlow(conversationId)

    // === pai-voice turn管理 ===
    private var generation = 0
    private val pendingQueue = java.util.concurrent.ConcurrentLinkedQueue<String>()
    private val dialogueLines = mutableListOf<DialogueLine>()

    // === 状态跟踪 ===
    private var isMuted = false
    private var ttsSentLength = 0
    private var lastAssistantText = ""
    private var callStartTime = 0L

    // === 协程任务 ===
    private var vadJob: Job? = null
    private var conversationMonitorJob: Job? = null
    private var speakingMonitorJob: Job? = null
    private var asrMonitorJob: Job? = null
    private var timerJob: Job? = null

    companion object {
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

        fun isRunning(): Boolean = _activeConversationId.value != null

        fun start(context: Context, conversationId: String) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra(EXTRA_CONVERSATION_ID, conversationId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                Log.e(TAG, "启动 VoiceCallService 失败", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, VoiceCallService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "停止 VoiceCallService 失败", e)
            }
        }

        const val EXTRA_CONVERSATION_ID = "conversationId"
        const val ACTION_HANG_UP = "me.rerere.rikkahub.VOICE_CALL_HANG_UP"
        const val NOTIFICATION_ID = 40001
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }

    private val binder = LocalBinder()
    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_HANG_UP) {
            endCall()
            stopSelf()
            return START_NOT_STICKY
        }

        val convIdStr = intent?.getStringExtra(EXTRA_CONVERSATION_ID)
        if (convIdStr == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (_activeConversationId.value == convIdStr) return START_NOT_STICKY
        if (_activeConversationId.value != null && _activeConversationId.value != convIdStr) {
            return START_NOT_STICKY
        }

        try {
            conversationId = Uuid.parse(convIdStr)
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }

        _activeConversationId.value = convIdStr

        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(_uiState.value),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } catch (e: Exception) {
            _activeConversationId.value = null
            stopSelf()
            return START_NOT_STICKY
        }

        serviceScope.launch {
            try {
                asr = createCustomAsrState(applicationContext, httpClient, settingsStore)
                tts = createCustomTtsState(applicationContext, settingsStore)
                startCall()

                launch {
                    uiState.collect { state ->
                        try {
                            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                            manager.notify(NOTIFICATION_ID, buildNotification(state))
                        } catch (_: Exception) {}
                    }
                }

                launch {
                    asr.state.collect { asrState ->
                        updateAmplitudes(asrState.amplitudes)
                        if (asrState.status == me.rerere.asr.ASRStatus.Error) {
                            _uiState.update {
                                it.copy(status = VoiceCallStatus.Error,
                                    errorMessage = "语音识别错误: ${asrState.errorMessage}")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(status = VoiceCallStatus.Error, errorMessage = "初始化失败: ${e.message}")
                }
            }
        }

        return START_NOT_STICKY
    }

    // ==================== 核心通话逻辑 ====================

    fun startCall() {
        if (_uiState.value.status != VoiceCallStatus.Idle) return
        generation = 0
        pendingQueue.clear()
        dialogueLines.clear()
        callStartTime = System.currentTimeMillis()
        isMuted = false
        ttsSentLength = 0
        lastAssistantText = ""

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "", assistantText = "",
                errorMessage = null, isMuted = false,
                callDurationSeconds = 0, dialogue = emptyList(), queuedMessages = 0
            )
        }

        try {
            asr.start { transcript -> _uiState.update { it.copy(userTranscript = transcript) } }
        } catch (e: Exception) {
            _uiState.update { it.copy(status = VoiceCallStatus.Error, errorMessage = "麦克风启动失败: ${e.message}") }
            return
        }

        startVadDetection()
        startAsrMonitor()
        startConversationMonitor()
        startCallTimer()
    }

    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (true) {
                delay(1000)
                val elapsed = ((System.currentTimeMillis() - callStartTime) / 1000).toInt()
                _uiState.update { it.copy(callDurationSeconds = elapsed) }
            }
        }
    }

    private fun addDialogueLine(line: DialogueLine) {
        dialogueLines.add(line)
        _uiState.update { it.copy(dialogue = dialogueLines.toList()) }
    }

    // ==================== VAD 检测 ====================

    private fun startVadDetection() {
        vadJob?.cancel()
        vadJob = serviceScope.launch {
            var lastTranscript = ""
            var silenceStartTime = 0L
            var lastAmplitudeTime = System.currentTimeMillis()
            var hadVoiceActivity = false
            var voiceSilenceStart = 0L
            val silenceThresholdMs = 500L
            val minTranscriptLength = 2
            val amplitudeTimeoutMs = 2000L
            val voiceSilenceThresholdMs = 800L

            while (true) {
                delay(100)
                if (_uiState.value.status != VoiceCallStatus.Listening) break
                if (isMuted) continue

                val currentTranscript = _uiState.value.userTranscript
                val amplitudes = _uiState.value.amplitudes
                val recentAmplitude = amplitudes.takeLast(3).average().toFloat()

                if (recentAmplitude > 0.05f) {
                    lastAmplitudeTime = System.currentTimeMillis()
                    hadVoiceActivity = true
                    voiceSilenceStart = 0L
                }

                if (currentTranscript != lastTranscript) {
                    lastTranscript = currentTranscript
                    silenceStartTime = 0L
                } else if (currentTranscript.length >= minTranscriptLength) {
                    if (silenceStartTime == 0L) silenceStartTime = System.currentTimeMillis()
                    val silentFor = System.currentTimeMillis() - silenceStartTime
                    val ampSilentFor = System.currentTimeMillis() - lastAmplitudeTime
                    if (silentFor >= silenceThresholdMs || ampSilentFor >= amplitudeTimeoutMs) {
                        onUserSpeechEnd(currentTranscript)
                        break
                    }
                }

                if (currentTranscript.isEmpty() && hadVoiceActivity && recentAmplitude <= 0.05f) {
                    if (voiceSilenceStart == 0L) voiceSilenceStart = System.currentTimeMillis()
                    if (System.currentTimeMillis() - voiceSilenceStart >= voiceSilenceThresholdMs) {
                        hadVoiceActivity = false
                        voiceSilenceStart = 0L
                        try { asr.stop() } catch (_: Exception) {}
                        break
                    }
                } else if (recentAmplitude > 0.05f) {
                    voiceSilenceStart = 0L
                }
            }
        }
    }

    // ==================== 核心: 用户说完话 ====================

    private fun onUserSpeechEnd(transcript: String) {
        vadJob?.cancel()
        val text = transcript.trim()
        if (text.isBlank()) {
            enterListeningState()
            return
        }

        val status = _uiState.value.status
        if (status == VoiceCallStatus.Speaking || status == VoiceCallStatus.Processing) {
            // Mode B: AI正在说话或思考, 排队等候
            pendingQueue.add(text)
            _uiState.update { it.copy(queuedMessages = pendingQueue.size, userTranscript = "") }
            restartAsr()
            startVadDetection()
        } else {
            processNextTurn(text)
        }
    }

    // ==================== 核心: 处理一轮对话 ====================

    private fun processNextTurn(text: String) {
        generation++
        addDialogueLine(DialogueLine("user", text))

        try { asr.stop() } catch (_: Exception) {}

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Processing,
                userTranscript = "", assistantText = ""
            )
        }
        ttsSentLength = 0
        lastAssistantText = ""

        try {
            chatService.sendMessage(conversationId, listOf(UIMessagePart.Text(text)))
        } catch (e: Exception) {
            Log.e(TAG, "发送消息失败", e)
            _uiState.update { it.copy(status = VoiceCallStatus.Error, errorMessage = "发送失败: ${e.message}") }
        }
    }

    // ==================== 独白检测 ====================

    private fun isMonologue(text: String): Boolean {
        val t = text.trim()
        if (t.startsWith("[独白]") || t.startsWith("【独白】")) return true
        val patterns = listOf("继续听", "不出声", "继续等", "没问我", "不说话")
        if (patterns.any { t.contains(it) } && t.length < 30) return true
        return false
    }

    private fun stripMonologuePrefix(text: String): String {
        return text.trim().removePrefix("[独白]").removePrefix("【独白】").trim()
    }

    // ==================== 对话流监听 ====================

    private fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = serviceScope.launch {
            conversation.collect { conv ->
                val status = _uiState.value.status
                if (status != VoiceCallStatus.Processing && status != VoiceCallStatus.Speaking) return@collect

                val lastMessage = conv.currentMessages.lastOrNull()
                if (lastMessage?.role != MessageRole.ASSISTANT) return@collect

                val currentText = lastMessage.toText()
                _uiState.update { it.copy(assistantText = currentText) }

                val mono = isMonologue(currentText)

                // 非独白内容: 流式TTS
                if (!mono && currentText.length > ttsSentLength) {
                    val newText = currentText.substring(ttsSentLength)
                    val sentences = extractCompleteSentences(newText)
                    for (s in sentences) {
                        if (s.isNotBlank()) tts.enqueueText(s)
                    }
                    ttsSentLength = currentText.length - getPendingRemainder(newText).length
                }

                // AI开始输出非独白内容 -> Speaking
                if (status == VoiceCallStatus.Processing && currentText.isNotBlank() && !mono) {
                    _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
                }

                lastAssistantText = currentText
            }
        }

        speakingMonitorJob?.cancel()
        speakingMonitorJob = serviceScope.launch {
            chatService.generationDoneFlow.collect { convId ->
                if (convId != conversationId) return@collect
                onGenerationDone()
            }
        }
    }

    private suspend fun onGenerationDone() {
        val finalText = _uiState.value.assistantText
        val mono = isMonologue(finalText)
        val displayText = stripMonologuePrefix(finalText)

        if (displayText.isNotBlank()) {
            addDialogueLine(DialogueLine("assistant", displayText, isMonologue = mono))
        }

        if (!mono) {
            if (finalText.length > ttsSentLength) {
                val remaining = finalText.substring(ttsSentLength)
                if (remaining.isNotBlank()) {
                    tts.enqueueText(remaining)
                    ttsSentLength = finalText.length
                }
            }
            _uiState.update { it.copy(status = VoiceCallStatus.Speaking) }
            waitForTtsToFinish()
        }

        checkQueue()
    }

    // ==================== 队列检查 ====================

    private fun checkQueue() {
        val next = pendingQueue.poll()
        if (next != null) {
            _uiState.update { it.copy(queuedMessages = pendingQueue.size) }
            processNextTurn(next)
        } else {
            enterListeningState()
        }
    }

    // ==================== 状态切换 ====================

    private fun enterListeningState() {
        tts.stop()
        ttsSentLength = 0
        lastAssistantText = ""

        _uiState.update {
            it.copy(
                status = VoiceCallStatus.Listening,
                userTranscript = "", errorMessage = null,
                queuedMessages = pendingQueue.size
            )
        }

        restartAsr()
        startVadDetection()
    }

    private fun restartAsr() {
        if (!isMuted) {
            runCatching {
                asr.start { transcript -> _uiState.update { it.copy(userTranscript = transcript) } }
            }.onFailure { Log.e(TAG, it.toString(), it) }
        }
    }

    // ==================== ASR 状态监听 (非流式ASR) ====================

    private fun startAsrMonitor() {
        asrMonitorJob?.cancel()
        asrMonitorJob = serviceScope.launch {
            var wasRecording = false
            asr.state.collect { asrState ->
                val isRecording = asrState.isRecording
                if (wasRecording && !isRecording && !isMuted && _uiState.value.status == VoiceCallStatus.Listening) {
                    val transcript = asrState.transcript.trim()
                    if (transcript.isNotEmpty()) {
                        onUserSpeechEnd(transcript)
                    } else {
                        restartAsr()
                    }
                }
                wasRecording = isRecording
            }
        }
    }

    // ==================== TTS 等待 ====================

    private suspend fun waitForTtsToFinish() {
        var waitStart = System.currentTimeMillis()
        while (!tts.isSpeaking.value && System.currentTimeMillis() - waitStart < 5000) {
            delay(100)
        }
        val idleTimeoutMs = 5_000L
        val hardDeadlineMs = 300_000L
        val startTime = System.currentTimeMillis()
        var lastActiveTime = System.currentTimeMillis()
        while (true) {
            val now = System.currentTimeMillis()
            val status = tts.playbackState.value.status
            val active = tts.isSpeaking.value ||
                status == me.rerere.tts.model.PlaybackStatus.Playing ||
                status == me.rerere.tts.model.PlaybackStatus.Buffering
            if (active) lastActiveTime = now
            if (!active && now - lastActiveTime >= idleTimeoutMs) break
            if (now - startTime > hardDeadlineMs) {
                tts.stop()
                break
            }
            delay(300)
        }
        delay(300)
    }

    // ==================== 文本工具 ====================

    private fun extractCompleteSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        for (char in text) {
            current.append(char)
            if (char == '。' || char == '？' || char == '！' || char == '.' ||
                char == '?' || char == '!' || char == '\n'
            ) {
                val sentence = current.toString().trim()
                if (sentence.isNotEmpty()) result.add(sentence)
                current.clear()
            }
        }
        return result
    }

    private fun getPendingRemainder(text: String): String {
        val lastEnd = text.lastIndexOfAny(charArrayOf('。', '？', '！', '.', '?', '!', '\n'))
        return if (lastEnd >= 0 && lastEnd < text.length - 1) text.substring(lastEnd + 1)
        else if (lastEnd < 0) text
        else ""
    }

    // ==================== 控制方法 ====================

    fun toggleMute() {
        isMuted = !isMuted
        _uiState.update { it.copy(isMuted = isMuted) }
        try {
            if (isMuted) asr.stop()
            else restartAsr()
        } catch (e: Exception) {
            _uiState.update { it.copy(errorMessage = "麦克风切换失败: ${e.message}") }
        }
    }

    fun endCall() {
        vadJob?.cancel()
        conversationMonitorJob?.cancel()
        speakingMonitorJob?.cancel()
        asrMonitorJob?.cancel()
        timerJob?.cancel()
        try { asr.stop() } catch (_: Exception) {}
        tts.stop()
        pendingQueue.clear()
        _uiState.update { it.copy(status = VoiceCallStatus.Idle) }
        _activeConversationId.value = null
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    fun updateAmplitudes(amplitudes: List<Float>) {
        _uiState.update { it.copy(amplitudes = amplitudes) }
    }

    // ==================== 通知 ====================

    private fun buildNotification(state: VoiceCallUiState): android.app.Notification {
        val mins = state.callDurationSeconds / 60
        val secs = state.callDurationSeconds % 60
        val timer = String.format("%02d:%02d", mins, secs)
        val statusStr = when (state.status) {
            VoiceCallStatus.Listening -> "正在聆听"
            VoiceCallStatus.Processing -> "思考中"
            VoiceCallStatus.Speaking -> "说话中"
            VoiceCallStatus.Error -> state.errorMessage ?: "出错"
            VoiceCallStatus.Idle -> "通话中"
        }
        val contentText = if (state.isConnected) "$statusStr · $timer" else statusStr

        val contentIntent = PendingIntent.getActivity(
            this, conversationId.hashCode(),
            Intent(this, RouteActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra("openVoiceCallConversationId", conversationId.toString())
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val hangUpIntent = PendingIntent.getService(
            this, 0,
            Intent(this, VoiceCallService::class.java).apply { action = ACTION_HANG_UP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("语音通话 · $timer")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.small_icon)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            .addAction(0, "挂断", hangUpIntent)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { endCall() } catch (_: Exception) {}
        serviceScope.cancel()
    }
}
