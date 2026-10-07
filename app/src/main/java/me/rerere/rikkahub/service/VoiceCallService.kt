package me.rerere.rikkahub.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.rikkahub.R
import me.rerere.rikkahub.RouteActivity
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayOutputStream
import kotlin.uuid.Uuid

private const val TAG = "VoiceCallService"
private const val NOTIFICATION_ID = 1001
private const val CHANNEL_ID = "voice_call_channel"

/**
 * Elian 语音/视频通话服务 - 完全按 pai-voice 架构重写
 * 
 * 核心改动：
 * 1. 完全删除橘瓣旧的 VAD 代码
 * 2. 使用 VoiceCallManager（pai-voice 算法 + 云端 API）
 * 3. 使用 VisionChain（视觉识别 + 陪伴模式）
 * 4. 通话中的 AI 就是 chatService（能调所有工具）
 */
class VoiceCallService : LifecycleService(), KoinComponent {
    private val chatService: ChatService by inject()
    
    private val serviceScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main + CoroutineExceptionHandler { _, e ->
            Log.e(TAG, "Service coroutine exception", e)
        }
    )
    
    // ========== pai-voice 核心组件 ==========
    private lateinit var voiceManager: VoiceCallManager
    private lateinit var visionChain: VisionChain
    
    // ========== 摄像头相关 ==========
    private var imageCapture: ImageCapture? = null
    private var cameraJob: Job? = null
    private var isFrontCamera = true
    
    // ========== 通话状态 ==========
    private val _callState = MutableStateFlow(VoiceCallState())
    val callState: StateFlow<VoiceCallState> = _callState.asStateFlow()
    
    private lateinit var conversationId: Uuid
    private var currentGeneration = 0
    private var accumulatedText = ""
    
    // ========== Binder ==========
    private val binder = VoiceCallBinder()
    
    inner class VoiceCallBinder : Binder() {
        fun getService(): VoiceCallService = this@VoiceCallService
    }
    
    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoiceCallService onCreate")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 初始化 pai-voice 组件
        initializePaiVoiceComponents()
    }
    
    private fun initializePaiVoiceComponents() {
        // 读取配置
        val prefs = getSharedPreferences("voice_config", MODE_PRIVATE)
        val qwenKey = prefs.getString("qwen_key", "") ?: ""
        val elevenKey = prefs.getString("eleven_key", "") ?: ""
        val elevenVoice = prefs.getString("eleven_voice", "") ?: ""
        
        // 初始化语音管理器
        voiceManager = VoiceCallManager(
            context = this,
            scope = serviceScope,
            onUserSpeech = { transcript ->
                // 用户说的话 → 处理
                serviceScope.launch {
                    handleUserSpeech(transcript)
                }
            },
            onStatusChange = { state ->
                // 更新通话状态
                _callState.value = _callState.value.copy(
                    isListening = state.isListening,
                    isSpeaking = state.isSpeaking,
                    isThinking = state.isThinking,
                    amplitude = state.amplitude
                )
            }
        )
        
        // 配置 API Keys
        voiceManager.configure(
            qwenKey = qwenKey,
            elevenKey = elevenKey,
            voiceId = elevenVoice
        )
        
        // 初始化视觉链
        visionChain = VisionChain(
            context = this,
            scope = serviceScope
        )
    }
    
    // ========== 启动/停止通话 ==========
    
    suspend fun startCall(conversationId: Uuid, videoEnabled: Boolean = false) {
        this.conversationId = conversationId
        
        // 启动前台服务
        startForeground(
            NOTIFICATION_ID,
            buildNotification("通话中"),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or 
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            } else {
                0
            }
        )
        
        // 启动语音通话
        voiceManager.startCall()
        
        // 如果开启视频，启动摄像头
        if (videoEnabled) {
            startCameraCapture()
        }
        
        _callState.value = _callState.value.copy(
            isActive = true,
            videoEnabled = videoEnabled
        )
        
        Log.d(TAG, "通话已启动 conversationId=$conversationId video=$videoEnabled")
    }
    
    fun stopCall() {
        voiceManager.stopCall()
        cameraJob?.cancel()
        imageCapture = null
        
        _callState.value = VoiceCallState()
        currentGeneration = 0
        accumulatedText = ""
        
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
        
        Log.d(TAG, "通话已停止")
    }
    
    // ========== 处理用户输入 ==========
    
    private suspend fun handleUserSpeech(text: String) {
        Log.d(TAG, "收到用户输入: $text")
        
        currentGeneration++
        val generation = currentGeneration
        
        // 更新状态为 Processing
        _callState.value = _callState.value.copy(isThinking = true)
        voiceManager.setThinking(true)
        
        // 调用 ChatService 生成回复
        try {
            chatService.sendMessage(
                conversationId = conversationId,
                text = text,
                onToken = { token ->
                    accumulatedText += token
                    
                    // 每生成一句话，立即 TTS
                    if (token.endsWith("。") || token.endsWith("！") || 
                        token.endsWith("？") || token.endsWith("\n")) {
                        val sentence = accumulatedText.trim()
                        if (sentence.isNotEmpty()) {
                            serviceScope.launch {
                                voiceManager.speak(sentence, generation)
                            }
                            accumulatedText = ""
                        }
                    }
                },
                onComplete = {
                    // 生成结束，播放剩余文字
                    if (accumulatedText.isNotEmpty()) {
                        serviceScope.launch {
                            voiceManager.speak(accumulatedText.trim(), generation)
                            accumulatedText = ""
                        }
                    }
                    
                    voiceManager.setThinking(false)
                    _callState.value = _callState.value.copy(isThinking = false)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "生成回复失败", e)
            voiceManager.setThinking(false)
            _callState.value = _callState.value.copy(isThinking = false)
        }
    }
    
    // ========== 摄像头采集 ==========
    
    private fun startCameraCapture() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                
                // 创建 ImageCapture
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()
                
                // 选择摄像头
                val cameraSelector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }
                
                // 绑定生命周期
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this as LifecycleOwner,
                    cameraSelector,
                    imageCapture
                )
                
                // 启动定时采集
                startPeriodicCapture()
                
                Log.d(TAG, "摄像头已启动")
            } catch (e: Exception) {
                Log.e(TAG, "摄像头启动失败", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }
    
    private fun startPeriodicCapture() {
        cameraJob?.cancel()
        cameraJob = serviceScope.launch {
            while (isActive) {
                val companionMode = _callState.value.companionMode
                val interval = if (companionMode) 20000L else 5000L  // 陪伴模式20秒，实时模式5秒
                
                try {
                    val jpeg = captureFrame()
                    if (jpeg != null) {
                        processFrame(jpeg, companionMode)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "采集帧失败", e)
                }
                
                delay(interval)
            }
        }
    }
    
    private suspend fun captureFrame(): ByteArray? = suspendCancellableCoroutine { cont ->
        val capture = imageCapture ?: run {
            cont.resume(null) {}
            return@suspendCancellableCoroutine
        }
        
        val outputStream = ByteArrayOutputStream()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputStream).build()
        
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    cont.resume(outputStream.toByteArray()) {}
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    cont.resume(null) {}
                }
            }
        )
    }
    
    private suspend fun processFrame(jpeg: ByteArray, companionMode: Boolean) {
        // 检查画面是否变化
        if (!visionChain.changed(jpeg)) {
            return  // 画面没变化，跳过
        }
        
        if (companionMode) {
            // 陪伴模式：结构化评估
            val assessment = visionChain.assess(jpeg)
            if (assessment != null) {
                val notification = visionChain.companionStateMachine(assessment)
                if (notification != null) {
                    // 发送系统消息给 AI（不触发回复，只插入上下文）
                    chatService.insertSystemMessage(conversationId, notification)
                }
            }
        } else {
            // 实时模式：自由描述
            val description = visionChain.describe(jpeg)
            if (description != null) {
                // 发送画面描述给 AI
                chatService.insertSystemMessage(
                    conversationId,
                    "[画面] $description"
                )
            }
        }
    }
    
    // ========== 控制方法 ==========
    
    fun toggleMute() {
        val newMuted = !_callState.value.isMuted
        voiceManager.mute(newMuted)
        _callState.value = _callState.value.copy(isMuted = newMuted)
    }
    
    fun toggleVideo() {
        val newEnabled = !_callState.value.videoEnabled
        
        if (newEnabled) {
            startCameraCapture()
        } else {
            cameraJob?.cancel()
            imageCapture = null
        }
        
        _callState.value = _callState.value.copy(videoEnabled = newEnabled)
    }
    
    fun toggleCamera() {
        isFrontCamera = !isFrontCamera
        if (_callState.value.videoEnabled) {
            startCameraCapture()  // 重新绑定摄像头
        }
    }
    
    fun toggleCompanionMode() {
        val newMode = !_callState.value.companionMode
        _callState.value = _callState.value.copy(companionMode = newMode)
        Log.d(TAG, "陪伴模式: $newMode")
    }
    
    // ========== 通知相关 ==========
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "语音通话",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Elian 语音/视频通话服务"
                setSound(null, null)
            }
            
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
    
    private fun buildNotification(content: String): Notification {
        val intent = Intent(this, RouteActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Elian 通话")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        voiceManager.stopCall()
        Log.d(TAG, "VoiceCallService onDestroy")
    }
    
    companion object {
        private val _activeConversationId = MutableStateFlow<String?>(null)
        val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()
        
        fun start(context: Context, conversationId: Uuid, videoEnabled: Boolean = false) {
            val intent = Intent(context, VoiceCallService::class.java).apply {
                putExtra("conversation_id", conversationId.toString())
                putExtra("video_enabled", videoEnabled)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}

// ========== 状态数据类 ==========

data class VoiceCallState(
    val isActive: Boolean = false,
    val isListening: Boolean = false,
    val isSpeaking: Boolean = false,
    val isThinking: Boolean = false,
    val isMuted: Boolean = false,
    val videoEnabled: Boolean = false,
    val companionMode: Boolean = false,
    val amplitude: Float = 0f
)