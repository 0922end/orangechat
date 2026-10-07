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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import me.rerere.rikkahub.VOICE_CALL_NOTIFICATION_CHANNEL_ID
import me.rerere.rikkahub.data.service.CameraService
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.ui.pages.voice.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"

/**
 * Elian 语音/视频通话服务 - pai-voice 完整版
 * 
 * 核心模块：
 * 1. VoiceCallManager - 精准VAD + 温和打断 + 云端ASR/TTS
 * 2. VisionChain - 多模型视觉链 + 画面检测 + 陪伴模式
 * 3. generation机制 - 防止旧回复覆盖新回复
 * 4. 消息队列 - AI说话时用户输入排队
 */
class VoiceCallService : Service(), KoinComponent {
    private val chatService: ChatService by inject()
    private val settingsStore: SettingsStore by inject()
    private val cameraService by lazy { CameraService(this) }

    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "协程异常", e)
        }
    )

    // ========== 核心模块 ==========
    private var voiceManager: VoiceCallManager? = null
    private var visionChain: VisionChain? = null
    
    // ========== 对话管理 ==========
    private lateinit var conversationId: Uuid
    private var generation = 0
    private val pendingQueue = ConcurrentLinkedQueue<String>()
    private val dialogueLines = mutableListOf<DialogueLine>()
    
    // ========== UI状态 ==========
    private val _uiState = MutableStateFlow(VoiceCallUiState())
    val uiState: StateFlow<VoiceCallUiState> = _uiState.asStateFlow()
    
    val conversation: StateFlow<Conversation>
        get() = chatService.getConversationFlow(conversationId)
    
    // ========== 通话状态 ==========
    private var callStartTime = 0L
    private var isMuted = false
    private var lastAssistantText = ""
    private var ttsSentLength = 0
    
    // ========== 协程任务 ==========
    private var conversationMonitorJob: Job? = null
    private var timerJob: Job? = null
    private var cameraJob: Job? = null
    private var callingMonitorJob: Job? = null
    
    // ========== 来电状态 ==========
    private var aiHungUp = false
    
    // ========== Service生命周期 ==========
    
    override fun onBind(intent: Intent?): IBinder = VoiceCallBinder()
    
    inner class VoiceCallBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service创建")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        endCall()
        serviceScope.cancel()
        Log.d(TAG, "Service销毁")
    }
    
    // ========== 通话控制 ==========
    
    /**
     * 启动通话
     */
    fun startCall(
        conversationId: Uuid,
        videoMode: VideoMode = VideoMode.Off,
        incomingCall: Boolean = false
    ) {
        if (isInCall()) {
            Log.w(TAG, "已在通话中")
            return
        }
        
        this.conversationId = conversationId
        this.generation = 0
        this.callStartTime = System.currentTimeMillis()
        this.aiHungUp = false
        
        _activeConversationId.value = conversationId.toString()
        
        _uiState.update {
            it.copy(
                status = if (incomingCall) VoiceCallStatus.Calling else VoiceCallStatus.Listening,
                videoMode = videoMode,
                callDurationSeconds = 0,
                dialogue = emptyList()
            )
        }
        
        // 初始化语音管理器
        initVoiceManager()
        
        // 初始化视觉链
        if (videoMode != VideoMode.Off) {
            initVisionChain()
        }
        
        // 启动前台服务
        startForeground()
        
        // 启动监控任务
        if (incomingCall) {
            startCallingMonitor()
        } else {
            startConversationMonitor()
            startCallTimer()
            
            serviceScope.launch {
                voiceManager?.startCall()
            }
        }
        
        Log.d(TAG, "通话已启动: conversationId=$conversationId, videoMode=$videoMode")
    }
    
    /**
     * 结束通话
     */
    fun endCall() {
        if (!isInCall()) return
        
        // 停止所有任务
        conversationMonitorJob?.cancel()
        timerJob?.cancel()
        cameraJob?.cancel()
        callingMonitorJob?.cancel()
        
        // 停止语音管理器
        voiceManager?.stopCall()
        voiceManager = null
        
        // 清理视觉链
        visionChain = null
        
        // 清理状态
        _activeConversationId.value = null
        pendingQueue.clear()
        dialogueLines.clear()
        
        _uiState.value = VoiceCallUiState()
        
        // 停止前台服务
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.d(TAG, "通话已结束")
    }
    
    /**
     * 接听来电
     */
    fun acceptCall() {
        if (_uiState.value.status != VoiceCallStatus.Calling) return
        
        callingMonitorJob?.cancel()
        
        _uiState.update { it.copy(status = VoiceCallStatus.Listening) }
        
        startConversationMonitor()
        startCallTimer()
        
        serviceScope.launch {
            voiceManager?.startCall()
        }
        
        Log.d(TAG, "来电已接听")
    }
    
    /**
     * 拒绝来电
     */
    fun rejectCall() {
        endCall()
        Log.d(TAG, "来电已拒绝")
    }
    
    /**
     * 切换静音
     */
    fun toggleMute() {
        isMuted = !isMuted
        voiceManager?.mute(isMuted)
        _uiState.update { it.copy(isMuted = isMuted) }
        Log.d(TAG, "静音状态: $isMuted")
    }
    
    /**
     * 切换视频模式
     */
    fun toggleVideoMode() {
        val current = _uiState.value.videoMode
        val next = when (current) {
            VideoMode.Off -> VideoMode.Live
            VideoMode.Live -> VideoMode.Companion
            VideoMode.Companion -> VideoMode.Off
        }
        
        _uiState.update { it.copy(videoMode = next) }
        
        if (next == VideoMode.Off) {
            cameraJob?.cancel()
            cameraJob = null
        } else if (cameraJob == null) {
            startCameraCapture()
        }
        
        Log.d(TAG, "视频模式切换: $current -> $next")
    }
    
    // ========== 初始化模块 ==========
    
    private fun initVoiceManager() {
        voiceManager = VoiceCallManager(
            context = this,
            scope = serviceScope,
            onUserSpeech = { text ->
                serviceScope.launch {
                    handleUserSpeech(text)
                }
            },
            onStatusChange = { state ->
                _uiState.update {
                    it.copy(
                        status = when {
                            state.isThinking -> VoiceCallStatus.Processing
                            state.isSpeaking -> VoiceCallStatus.Speaking
                            state.isListening -> VoiceCallStatus.Listening
                            else -> it.status
                        },
                        amplitudes = it.amplitudes + state.amplitude,
                        isMuted = state.isMuted
                    )
                }
            }
        )
        
        // 配置API Keys
        val prefs = getSharedPreferences("voice_config", MODE_PRIVATE)
        voiceManager?.configure(
            qwenKey = prefs.getString("qwen_key", "") ?: "",
            elevenKey = prefs.getString("eleven_key", "") ?: "",
            voiceId = prefs.getString("eleven_voice", "") ?: ""
        )
    }
    
    private fun initVisionChain() {
        visionChain = VisionChain(
            context = this,
            scope = serviceScope
        )
        
        startCameraCapture()
    }
    
    // ========== 用户输入处理 ==========
    
    private suspend fun handleUserSpeech(text: String) {
        if (text.isBlank()) return
        
        // 添加到对话记录
        val dialogue = DialogueLine(
            speaker = "user",
            text = text,
            timestamp = System.currentTimeMillis()
        )
        dialogueLines.add(dialogue)
        updateDialogueUI()
        
        val currentStatus = _uiState.value.status
        
        when (currentStatus) {
            VoiceCallStatus.Speaking, VoiceCallStatus.Processing -> {
                // AI正在说话或思考，排队
                pendingQueue.offer(text)
                Log.d(TAG, "用户输入排队: $text")
            }
            else -> {
                // 立即处理
                processUserInput(text)
            }
        }
    }
    
    private suspend fun processUserInput(text: String) {
        generation++
        
        // 发送给ChatService
        chatService.sendMessage(
            conversationId = conversationId,
            content = listOf(UIMessagePart.Text(text))
        )
        
        // 设置思考状态
        voiceManager?.setThinking(true)
        
        Log.d(TAG, "处理用户输入 (generation=$generation): $text")
    }
    
    // ========== 对话监控 ==========
    
    private fun startConversationMonitor() {
        conversationMonitorJob?.cancel()
        conversationMonitorJob = serviceScope.launch {
            conversation.collect { conv ->
                val messages = conv.currentMessages
                if (messages.isEmpty()) return@collect
                
                val lastMessage = messages.last()
                
                // 只处理助手消息
                if (lastMessage.role != MessageRole.ASSISTANT) return@collect
                
                // 提取文本内容
                val text = lastMessage.parts.filterIsInstance<UIMessagePart.Text>()
                    .joinToString("") { it.text }
                
                if (text.isBlank()) return@collect
                
                // 检查是否已经处理过
                if (text == lastAssistantText) return@collect
                lastAssistantText = text
                
                // 处理助手回复
                handleAssistantResponse(text)
            }
        }
    }
    
    private suspend fun handleAssistantResponse(fullText: String) {
        // 只处理新增的部分
        val newText = if (ttsSentLength < fullText.length) {
            fullText.substring(ttsSentLength)
        } else {
            return
        }
        
        ttsSentLength = fullText.length
        
        // 分句处理
        val sentences = newText.split(Regex("[。！？\n]+")).filter { it.isNotBlank() }
        
        for (sentence in sentences) {
            val trimmed = sentence.trim()
            if (trimmed.isEmpty()) continue
            
            // 检查是否是独白（不播放TTS）
            val isMonologue = trimmed.startsWith("[独白]") || trimmed.startsWith("[") && trimmed.contains("独白")
            
            val displayText = trimmed.removePrefix("[独白]").trim()
            
            // 添加到对话记录
            val dialogue = DialogueLine(
                speaker = "assistant",
                text = displayText,
                timestamp = System.currentTimeMillis()
            )
            dialogueLines.add(dialogue)
            updateDialogueUI()
            
            // 播放TTS（独白除外）
            if (!isMonologue) {
                voiceManager?.setThinking(false)
                voiceManager?.speak(displayText, generation)
            }
        }
        
        // 检查是否有排队的用户输入
        if (_uiState.value.status == VoiceCallStatus.Listening) {
            val pending = pendingQueue.poll()
            if (pending != null) {
                Log.d(TAG, "处理排队的用户输入: $pending")
                processUserInput(pending)
            }
        }
    }
    
    private fun updateDialogueUI() {
        _uiState.update { it.copy(dialogue = dialogueLines.toList()) }
    }
    
    // ========== 视频陪伴模式 ==========
    
    private fun startCameraCapture() {
        if (_uiState.value.videoMode == VideoMode.Off) return
        
        cameraJob?.cancel()
        cameraJob = serviceScope.launch {
            while (isActive) {
                try {
                    val jpeg = captureCurrentFrame()
                    if (jpeg != null) {
                        // 检测画面是否变化
                        if (visionChain?.changed(jpeg) == true) {
                            // 陪伴模式评估
                            val assessment = visionChain?.assess(jpeg)
                            if (assessment != null) {
                                val notification = visionChain?.companionStateMachine(assessment)
                                if (notification != null) {
                                    // 注入系统消息
                                    injectSystemMessage(notification)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "摄像头采集失败", e)
                }
                
                delay(3000) // 3秒一帧
            }
        }
    }
    
    private suspend fun captureCurrentFrame(): ByteArray? {
        if (!cameraService.hasCameraPermission()) {
            Log.w(TAG, "摄像头权限未授予")
            return null
        }
        
        val useFront = _uiState.value.videoMode == VideoMode.Companion
        
        return try {
            val result = cameraService.capturePhoto(
                useFrontCamera = useFront,
                enableFlash = false
            )
            
            if (result.success && result.imageData != null) {
                result.imageData
            } else {
                Log.w(TAG, "拍照失败: ${result.error}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "拍照异常", e)
            null
        }
    }
    
    private suspend fun injectSystemMessage(text: String) {
        // 系统消息注入上下文，不触发AI回复
        val dialogue = DialogueLine(
            speaker = "system",
            text = text,
            timestamp = System.currentTimeMillis()
        )
        dialogueLines.add(dialogue)
        updateDialogueUI()
        
        Log.d(TAG, "系统消息: $text")
    }
    
    // ========== 来电监控 ==========
    
    private fun startCallingMonitor() {
        callingMonitorJob?.cancel()
        callingMonitorJob = serviceScope.launch {
            delay(8000) // 8秒超时
            
            if (_uiState.value.status == VoiceCallStatus.Calling) {
                Log.d(TAG, "来电超时，自动挂断")
                endCall()
            }
        }
    }
    
    // ========== 通话计时 ==========
    
    private fun startCallTimer() {
        timerJob?.cancel()
        timerJob = serviceScope.launch {
            while (isActive) {
                val duration = (System.currentTimeMillis() - callStartTime) / 1000
                _uiState.update { it.copy(callDurationSeconds = duration.toInt()) }
                delay(1000)
            }
        }
    }
    
    // ========== 前台服务通知 ==========
    
    private fun startForeground() {
        val intent = Intent(this, RouteActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("openVoiceCallConversationId", conversationId.toString())
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(this, VOICE_CALL_NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Elian 通话中")
            .setContentText("点击返回通话界面")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        
        ServiceCompat.startForeground(
            this,
            1,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )
    }
    
    companion object {
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()
        
        fun isInCall(): Boolean = _activeConversationId.value != null
        
        /**
         * 停止通话服务
         */
        fun stopService(context: Context) {
            context.stopService(Intent(context, VoiceCallService::class.java))
        }
    }
}