package me.rerere.rikkahub.ui.pages.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.MicOff01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Message01
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import kotlin.uuid.Uuid

private val ColorDeepBlue = Color(0xFF0A1628)
private val ColorMidBlue = Color(0xFF1B3A5C)
private val ColorLightBlue = Color(0xFF87CEEB)
private val ColorAccentBlue = Color(0xFF5BA3D9)
private val ColorMonologue = Color(0xFF6B7B8D)

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

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ColorDeepBlue, ColorMidBlue, ColorDeepBlue)))) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
            // 顶部: 名字 + 状态 + 通话时长
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 50.dp)) {
                Text("Eli", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(statusText(uiState.status), color = ColorLightBlue, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (uiState.isConnected) {
                    Spacer(Modifier.height(2.dp))
                    val mins = uiState.callDurationSeconds / 60
                    val secs = uiState.callDurationSeconds % 60
                    Text(String.format("%02d:%02d", mins, secs), color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp)
                }
            }

            // 中间: 音波球
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(vertical = 16.dp)) {
                VoiceOrb(amplitudes = uiState.amplitudes, status = uiState.status, baseColor = ColorAccentBlue, accentColor = ColorLightBlue, size = 160.dp)
                if (boundService == null) {
                    Spacer(Modifier.height(8.dp))
                    CircularProgressIndicator(color = ColorLightBlue.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                }
            }

            // 对话列表
            val dialogueListState = rememberLazyListState()
            LaunchedEffect(uiState.dialogue.size) {
                if (uiState.dialogue.isNotEmpty()) dialogueListState.animateScrollToItem(uiState.dialogue.size - 1)
            }
            LazyColumn(
                state = dialogueListState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 8.dp)
            ) {
                items(uiState.dialogue) { line ->
                    DialogueBubble(line)
                }
                // 实时显示当前正在说/正在听的内容
                if (uiState.userTranscript.isNotBlank() && uiState.status == VoiceCallStatus.Listening) {
                    item {
                        DialogueBubble(DialogueLine("user", uiState.userTranscript + " ..."))
                    }
                }
                if (uiState.assistantText.isNotBlank() && (uiState.status == VoiceCallStatus.Processing || uiState.status == VoiceCallStatus.Speaking)) {
                    item {
                        val mono = uiState.assistantText.trim().let {
                            it.startsWith("[独白]") || it.startsWith("【独白】")
                        }
                        DialogueBubble(DialogueLine("assistant", uiState.assistantText, isMonologue = mono))
                    }
                }
            }

            // 队列提示
            if (uiState.queuedMessages > 0) {
                Text("${uiState.queuedMessages} 条消息排队中", color = ColorLightBlue.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
            }

            // 错误信息
            uiState.errorMessage?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            }

            // 底部提示
            Text("直接说就行", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

            // 底部按钮
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 40.dp)) {
                Btn(HugeIcons.Message01, "打字", { onBack() }, Color.White.copy(alpha = 0.12f), Color.White, true)
                Btn(HugeIcons.Cancel01, "挂断", { VoiceCallService.stop(context); onBack() }, Color(0xFFE5484D), Color.White, true, 60.dp)
                Btn(
                    if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01,
                    "静音",
                    { boundService?.toggleMute() },
                    if (uiState.isMuted) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f),
                    Color.White,
                    boundService != null
                )
                Btn(HugeIcons.VolumeHigh, "扬声器", {}, Color.White.copy(alpha = 0.12f), Color.White, boundService != null)
            }
        }
    }
}

@Composable
private fun DialogueBubble(line: DialogueLine) {
    val isUser = line.speaker == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) ColorAccentBlue.copy(alpha = 0.3f)
                  else if (line.isMonologue) ColorMonologue.copy(alpha = 0.15f)
                  else Color.White.copy(alpha = 0.1f)
    val textColor = if (line.isMonologue) Color.White.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.9f)

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (line.isMonologue) {
            Text("独白", color = ColorMonologue.copy(alpha = 0.5f), fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
        }
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp
            ),
            color = bgColor
        ) {
            Text(
                text = line.text.removePrefix("[独白]").removePrefix("【独白】").trim(),
                color = textColor,
                fontSize = 14.sp,
                fontStyle = if (line.isMonologue) FontStyle.Italic else FontStyle.Normal,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).widthIn(max = 280.dp)
            )
        }
    }
}

@Composable
private fun Btn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, bg: Color, tint: Color, enabled: Boolean, size: Dp = 52.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = if (enabled) bg else bg.copy(alpha = 0.2f), modifier = Modifier.size(size), enabled = enabled) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.4f))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
    }
}

private fun statusText(s: VoiceCallStatus) = when (s) {
    VoiceCallStatus.Idle -> "准备中"
    VoiceCallStatus.Listening -> "正在聆听"
    VoiceCallStatus.Processing -> "思考中"
    VoiceCallStatus.Speaking -> "说话中"
    VoiceCallStatus.Error -> "出错了"
}
