package me.rerere.rikkahub.ui.pages.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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

private const val TAG = "VoiceCallPage"
private val ColorDeepBlue = Color(0xFF0A1628)
private val ColorMidBlue = Color(0xFF1B3A5C)
private val ColorLightBlue = Color(0xFF87CEEB)
private val ColorAccentBlue = Color(0xFF5BA3D9)

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
    val micEnabled = boundService != null && uiState.status == VoiceCallStatus.Listening
    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ColorDeepBlue, ColorMidBlue, ColorDeepBlue)))) {
        Column(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 60.dp)) {
                Text("Eli", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                Text(statusText(uiState.status), color = ColorLightBlue, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                VoiceOrb(amplitudes = uiState.amplitudes, status = uiState.status, baseColor = ColorAccentBlue, accentColor = ColorLightBlue, size = 220.dp)
                Spacer(Modifier.height(20.dp))
                Text("Eli", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(statusEmoji(uiState.status) + " " + statusText(uiState.status), color = ColorLightBlue.copy(alpha = 0.8f), fontSize = 14.sp)
                if (boundService == null) { Spacer(Modifier.height(16.dp)); CircularProgressIndicator(color = ColorLightBlue.copy(alpha = 0.5f), strokeWidth = 2.dp, modifier = Modifier.size(20.dp)) }
            }
            val sub = when (uiState.status) { VoiceCallStatus.Listening, VoiceCallStatus.Processing -> uiState.userTranscript; VoiceCallStatus.Speaking, VoiceCallStatus.Idle -> uiState.assistantText; VoiceCallStatus.Error -> "" }
            if (sub.isNotBlank()) StreamingSubtitle(sub)
            uiState.errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp)) }
            Text("直接说就行", color = Color.White.copy(alpha = 0.4f), fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 48.dp)) {
                Btn(HugeIcons.Message01, "打字", { onBack() }, Color.White.copy(alpha = 0.12f), Color.White, true)
                Btn(HugeIcons.Cancel01, "挂断", { VoiceCallService.stop(context); onBack() }, Color(0xFFE5484D), Color.White, true, 60.dp)
                Btn(if (uiState.isMuted) HugeIcons.MicOff01 else HugeIcons.Mic01, "静音", { boundService?.toggleMute() }, if (uiState.isMuted) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f), Color.White, micEnabled)
                Btn(HugeIcons.VolumeHigh, "扬声器", {}, Color.White.copy(alpha = 0.12f), Color.White, boundService != null)
            }
        }
    }
}

@Composable private fun StreamingSubtitle(text: String, modifier: Modifier = Modifier) {
    val s = rememberScrollState()
    LaunchedEffect(text) { if (text.isNotEmpty()) s.animateScrollTo(s.maxValue) }
    Column(modifier.padding(horizontal = 36.dp, vertical = 12.dp).heightIn(max = 120.dp).verticalScroll(s), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text.ifBlank { " " }, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
    }
}

@Composable private fun Btn(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, bg: Color, tint: Color, enabled: Boolean, size: Dp = 52.dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(onClick = onClick, shape = CircleShape, color = if (enabled) bg else bg.copy(alpha = 0.2f), modifier = Modifier.size(size), enabled = enabled) {
            Box(Alignment.Center, Modifier.fillMaxSize()) { Icon(icon, label, tint = if (enabled) tint else tint.copy(alpha = 0.3f), modifier = Modifier.size(size * 0.4f)) }
        }
        Spacer(Modifier.height(6.dp))
        Text(label, color = if (enabled) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.3f), fontSize = 11.sp)
    }
}

private fun statusText(s: VoiceCallStatus) = when (s) { VoiceCallStatus.Idle -> "准备中"; VoiceCallStatus.Listening -> "正在聆听"; VoiceCallStatus.Processing -> "思考中"; VoiceCallStatus.Speaking -> "说话中"; VoiceCallStatus.Error -> "出错了" }
private fun statusEmoji(s: VoiceCallStatus) = when (s) { VoiceCallStatus.Idle -> "♪"; VoiceCallStatus.Listening -> "●"; VoiceCallStatus.Processing -> "◐"; VoiceCallStatus.Speaking -> "◉"; VoiceCallStatus.Error -> "✕" }
