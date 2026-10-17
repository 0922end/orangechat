package me.rerere.rikkahub.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

private val ElianDarkBlue = Color(0xFF0A1628)
private val ElianMidBlue = Color(0xFF1A3A5C)
private val ElianLightBlue = Color(0xFF5BA3D9)
private val ElianAccent = Color(0xFF7EC8E3)
private val ElianPink = Color(0xFFE8A0BF)

@Composable
fun VoiceCallPage(manager: VoiceCallManager, onHangup: () -> Unit) {
    val mode by manager.mode.collectAsState()
    val userLevel by manager.userLevel.collectAsState()
    val aiLevel by manager.aiLevel.collectAsState()
    val isMuted by manager.isMuted.collectAsState()
    val videoEnabled by manager.videoEnabled.collectAsState()
    val videoMode by manager.videoMode.collectAsState()
    val isUserSpeaking by manager.isUserSpeaking.collectAsState()
    val transcript by manager.transcript.collectAsState()
    val partialTranscript by manager.partialTranscript.collectAsState()

    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(ElianDarkBlue, ElianMidBlue, ElianDarkBlue)))) {
        Column(modifier = Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(60.dp))
            Text(when (mode) { VoiceCallManager.CallMode.IDLE -> "\u7b49\u5f85\u63a5\u901a..."; VoiceCallManager.CallMode.LISTENING -> if (isUserSpeaking) "\u5728\u542c\u4f60\u8bf4..." else "\u6211\u5728\u542c"
                VoiceCallManager.CallMode.THINKING -> "\u8ba9\u6211\u60f3\u60f3..."; VoiceCallManager.CallMode.SPEAKING -> "\u5728\u8bf4\u8bdd" }, color = ElianAccent, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(200.dp), contentAlignment = Alignment.Center) { VoiceOrb(userLevel, aiLevel, mode, isUserSpeaking) }
            Spacer(Modifier.height(32.dp))
            val displayText = partialTranscript.ifEmpty { transcript }
            if (displayText.isNotEmpty()) Text(displayText, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).heightIn(max = 80.dp))
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                CallButton(if (isMuted) Icons.Filled.MicOff else Icons.Filled.Mic, if (isMuted) "\u53d6\u6d88\u9759\u97f3" else "\u9759\u97f3", isMuted, ElianPink) { manager.setMuted(!isMuted) }
                CallButton(if (videoEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff, if (videoEnabled) "\u5173\u6444\u50cf\u5934" else "\u5f00\u6444\u50cf\u5934", videoEnabled, ElianLightBlue) { }
                if (videoEnabled) CallButton(if (videoMode == VoiceCallManager.VideoMode.COMPANION) Icons.Filled.Visibility else Icons.Filled.RemoveRedEye,
                    if (videoMode == VoiceCallManager.VideoMode.COMPANION) "\u966a\u4f34\u6a21\u5f0f" else "\u5b9e\u65f6\u6a21\u5f0f", videoMode == VoiceCallManager.VideoMode.COMPANION, ElianAccent) {
                    manager.setVideoMode(if (videoMode == VoiceCallManager.VideoMode.LIVE) VoiceCallManager.VideoMode.COMPANION else VoiceCallManager.VideoMode.LIVE) }
                CallButton(Icons.Filled.CallEnd, "\u6302\u65ad", true, Color(0xFFE53935), true, onHangup)
            }
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun VoiceOrb(userLevel: Float, aiLevel: Float, mode: VoiceCallManager.CallMode, isUserSpeaking: Boolean) {
    val infiniteTransition = rememberInfiniteTransition(label = "orb")
    val phase by infiniteTransition.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(3000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
    val smoothUser by animateFloatAsState(userLevel, tween(100), label = "u")
    val smoothAi by animateFloatAsState(aiLevel, tween(100), label = "a")
    Canvas(Modifier.fillMaxSize()) {
        val cx = size.width / 2; val cy = size.height / 2; val br = size.minDimension / 4
        val rc = when { isUserSpeaking -> ElianPink; mode == VoiceCallManager.CallMode.SPEAKING -> ElianLightBlue; mode == VoiceCallManager.CallMode.THINKING -> ElianAccent.copy(0.6f); else -> ElianLightBlue.copy(0.3f) }
        val act = when { isUserSpeaking -> smoothUser * 8f; mode == VoiceCallManager.CallMode.SPEAKING -> smoothAi * 6f; else -> 0.1f }.coerceIn(0f, 1.5f)
        for (l in 0..2) { val lo = l * 0.8f; val amp = br * 0.15f * act * (1f - l * 0.2f)
            for (i in 0 until 60) { val a = (i / 60f) * 2 * PI.toFloat(); val w = sin(a * 3 + phase + lo) * amp; val r = br + w + l * 12f
                drawCircle(rc.copy((0.8f - l * 0.2f).coerceIn(0.1f, 1f)), 2f + act * 1.5f, Offset(cx + r * kotlin.math.cos(a), cy + r * sin(a))) } }
        drawCircle(Brush.radialGradient(listOf(rc.copy(0.4f), rc.copy(0.1f), Color.Transparent), Offset(cx, cy), br * (1f + act * 0.15f)), br * (1f + act * 0.15f), Offset(cx, cy))
    }
}

@Composable
private fun CallButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, isActive: Boolean, activeColor: Color, isHangup: Boolean = false, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick, Modifier.size(if (isHangup) 64.dp else 56.dp).clip(CircleShape).background(if (isActive) activeColor.copy(if (isHangup) 1f else 0.3f) else Color.White.copy(0.1f))) {
            Icon(icon, label, tint = if (isActive) { if (isHangup) Color.White else activeColor } else Color.White.copy(0.7f), modifier = Modifier.size(if (isHangup) 32.dp else 28.dp)) }
        Spacer(Modifier.height(6.dp)); Text(label, color = Color.White.copy(0.6f), fontSize = 11.sp)
    }
}