package me.rerere.rikkahub.ui.pages.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallEnd01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Video01
import me.rerere.hugeicons.stroke.VideoOff
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import kotlin.uuid.Uuid

private val ColorCangCang = Color(0xFF5976BA)
private val ColorQieLan = Color(0xFF7C9FCD)
private val ColorTextPrimary = Color.White
private val ColorTextSecondary = Color.White.copy(alpha = 0.7f)
private val ColorTextDim = Color.White.copy(alpha = 0.45f)
private val ColorMonologue = Color(0xFFB0BEC5)
private val ColorUserBubble = Color.White.copy(alpha = 0.15f)
private val ColorAssistantBubble = Color.White.copy(alpha = 0.08f)
private val ColorMonologueBubble = Color(0xFF455A64).copy(alpha = 0.2f)
private val ColorHangUp = Color(0xFFE5484D)
private val ColorVideoOn = Color(0xFF4CAF50)

@Composable
fun VoiceCallPage(conversationId: Uuid, onBack: () -> Unit) {
    val context = LocalContext.current
    var boundService by remember { mutableStateOf<VoiceCallService?>(null) }
    val asrPermission = rememberPermissionState(PermissionRecordAudio)
    val connection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                boundService = (binder as? VoiceCallService.LocalBinder)?.getService()
            }
            override fun onServiceDisconnected(name: ComponentName?) { boundService = null }
        }
    }
    DisposableEffect(conversationId) {
        if (VoiceCallService.activeConversationId.value != conversationId.toString()) {
            if (asrPermission.allRequiredPermissionsGranted) {
                VoiceCallService.start(context, conversationId.toString())
            }
        }
        val intent = Intent(context, VoiceCallService::class.java)
        context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        onDispose { try { context.unbindService(connection) } catch (_: Exception) {} }
    }
    LaunchedEffect(asrPermission.allRequiredPermissionsGranted) {
        if (asrPermission.allRequiredPermissionsGranted && VoiceCallService.activeConversationId.value == null) {
            VoiceCallService.start(context, conversationId.toString())
        }
    }
    LaunchedEffect(Unit) { if (!asrPermission.allRequiredPermissionsGranted) asrPermission.requestPermissions() }
    val uiState by (boundService?.uiState ?: MutableStateFlow(VoiceCallUiState()).asStateFlow())
        .collectAsStateWithLifecycle(initialValue = VoiceCallUiState())
    BackHandler { onBack() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ColorCangCang, ColorQieLan)))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 视频模式：摄像头预览在顶部
            if (uiState.isVideoOn) {
                CameraPreview(
                    isFrontCamera = uiState.isFrontCamera,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(280.dp)
                        .clip(RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp))
                )
                // 视频模式标签
                Text(
                    text = if (uiState.videoMode == VideoMode.Companion) "陪伴模式" else "视频通话",
                    color = ColorTextSecondary,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                // 语音模式：头像
                Spacer(Modifier.height(56.dp))
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.small_icon),
                        contentDescription = "Eli",
                        modifier = Modifier.size(100.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 名字 + 状态 + 通话时长
            Text("Eli", color = ColorTextPrimary, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            if (uiState.isConnected) {
                val mins = uiState.callDurationSeconds / 60
                val secs = uiState.callDurationSeconds % 60
                Text(
                    "${statusText(uiState.status)}  ${String.format("%02d:%02d", mins, secs)}",
                    color = ColorTextSecondary, fontSize = 14.sp
                )
            } else {
                Text(statusText(uiState.status), color = ColorTextSecondary, fontSize = 14.sp)
            }

            if (boundService == null) {
                Spacer(Modifier.height(8.dp))
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            }

            Spacer(Modifier.height(8.dp))

            // 对话列表
            val dialogueListState = rememberLazyListState()
            var userScrolled by remember { mutableStateOf(false) }
            LaunchedEffect(uiState.dialogue.size) {
                if (!userScrolled && uiState.dialogue.isNotEmpty()) {
                    dialogueListState.animateScrollToItem(uiState.dialogue.size - 1)
                }
            }
            LaunchedEffect(dialogueListState.isScrollInProgress) {
                if (dialogueListState.isScrollInProgress) userScrolled = true
            }
            LaunchedEffect(uiState.dialogue.size) {
                if (userScrolled && uiState.dialogue.isNotEmpty()) {
                    val lastVisible = dialogueListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                    if (lastVisible >= uiState.dialogue.size - 3) userScrolled = false
                }
            }

            LazyColumn(
                state = dialogueListState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.dialogue) { line -> DialogueBubble(line) }
                if (uiState.userTranscript.isNotBlank() && uiState.status == VoiceCallStatus.Listening) {
                    item { DialogueBubble(DialogueLine("user", uiState.userTranscript + " ...")) }
                }
                if (uiState.assistantText.isNotBlank() &&
                    (uiState.status == VoiceCallStatus.Processing || uiState.status == VoiceCallStatus.Speaking)
                ) {
                    item {
                        val mono = uiState.assistantText.trim().let {
                            it.startsWith("[独白]") || it.startsWith("【独白】")
                        }
                        DialogueBubble(DialogueLine("assistant", uiState.assistantText, isMonologue = mono))
                    }
                }
            }

            if (uiState.queuedMessages > 0) {
                Text("${uiState.queuedMessages} 条消息排队中", color = ColorTextDim, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }

            uiState.errorMessage?.let {
                Text(it, color = Color(0xFFEF9A9A), fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp))
            }

            Text("直接说就行", color = ColorTextDim, fontSize = 12.sp, modifier = Modifier.padding(bottom = 6.dp))

            // 底部按钮 - 根据视频模式显示不同按钮
            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 40.dp)
            ) {
                CallBtn(HugeIcons.Message01, "打字", { onBack() }, Color.White.copy(alpha = 0.15f), Color.White, true)

                // 摄像头开关
                CallBtn(
                    icon = if (uiState.isVideoOn) HugeIcons.Video01 else HugeIcons.VideoOff,
                    label = if (uiState.isVideoOn) "关摄像头" else "摄像头",
                    onClick = { boundService?.toggleVideo() },
                    bg = if (uiState.isVideoOn) ColorVideoOn.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.15f),
                    tint = Color.White,
                    enabled = boundService != null
                )

                // 挂断
                CallBtn(HugeIcons.CallEnd01, "挂断", { VoiceCallService.stop(context); onBack() }, ColorHangUp, Color.White, true, 60.dp)

                // 静音
                CallBtn(
                    if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                    "静音",
                    { boundService?.toggleMute() },
                    if (uiState.isMuted) Color.White.copy(alpha = 0.35f) else Color.White.copy(alpha = 0.15f),
                    Color.White,
                    boundService != null
                )

                // 视频模式下：翻转摄像头 / 陪伴模式切换
                if (uiState.isVideoOn) {
                    CallBtn(
                        HugeIcons.Camera01,
                        if (uiState.videoMode == VideoMode.Companion) "实时" else "陪伴",
                        { boundService?.toggleVideoMode() },
                        Color.White.copy(alpha = 0.15f),
                        Color.White,
                        true
                    )
                } else {
                    CallBtn(HugeIcons.VolumeHigh, "扬声器", {}, Color.White.copy(alpha = 0.15f), Color.White, boundService != null)
                }
            }
        }
    }
}

/**
 * CameraX 预览
 */
@Composable
private fun CameraPreview(isFrontCamera: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = modifier,
        update = { previewView ->
            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA
                    else CameraSelector.DEFAULT_BACK_CAMERA
                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                } catch (_: Exception) {}
            }, ContextCompat.getMainExecutor(context))
        }
    )
}

@Composable
private fun DialogueBubble(line: DialogueLine) {
    val isUser = line.speaker == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = when {
        isUser -> ColorUserBubble
        line.isMonologue -> ColorMonologueBubble
        else -> ColorAssistantBubble
    }
    val textColor = when {
        line.isMonologue -> ColorMonologue
        else -> ColorTextPrimary.copy(alpha = 0.9f)
    }
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (line.isMonologue) {
            Text("独白", color = ColorMonologue.copy(alpha = 0.6f), fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp
            ),
            color = bgColor
        ) {
            Text(
                text = line.text.removePrefix("[独白]").removePrefix("【独白】").trim(),
                color = textColor, fontSize = 14.sp, lineHeight = 20.sp,
                fontStyle = if (line.isMonologue) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 260.dp)
            )
        }
    }
}

@Composable
private fun CallBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, bg: Color, tint: Color, enabled: Boolean, size: Dp = 48.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick, shape = CircleShape,
            color = if (enabled) bg else bg.copy(alpha = 0.2f),
            modifier = Modifier.size(size), enabled = enabled
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.4f))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) ColorTextSecondary else ColorTextDim, fontSize = 10.sp)
    }
}

private fun statusText(s: VoiceCallStatus) = when (s) {
    VoiceCallStatus.Idle -> "准备中"
    VoiceCallStatus.Calling -> "正在呼叫"
    VoiceCallStatus.Listening -> "听你说"
    VoiceCallStatus.Processing -> "思考中"
    VoiceCallStatus.Speaking -> "说话中"
    VoiceCallStatus.Error -> "出错了"
}
