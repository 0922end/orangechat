package me.rerere.rikkahub.ui.pages.voice

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallIncoming01
import me.rerere.hugeicons.stroke.CallEnd01
import me.rerere.rikkahub.R

// 苍苍窃蓝配色
private val ColorCangCang = Color(0xFF5976BA)
private val ColorQieLan = Color(0xFF7C9FCD)
private val ColorAccept = Color(0xFF4CAF50)
private val ColorDecline = Color(0xFFE5484D)

@Composable
fun IncomingCallPage(
    conversationId: String,
    assistantName: String,
    onStartCall: () -> Unit,
    onDecline: () -> Unit,
) {
    val context = LocalContext.current

    LaunchedEffect(Unit) {
        val vibrator = getVibrator(context)
        if (vibratorHasAmplitudeControl(vibrator)) {
            val pattern = longArrayOf(0, 800, 800)
            vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(longArrayOf(0, 800, 800), 0)
        }
    }
    DisposableEffect(Unit) {
        onDispose { getVibrator(context)?.cancel() }
    }

    BackHandler { onDecline() }

    val transition = rememberInfiniteTransition(label = "incoming_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(ColorCangCang, ColorQieLan)))
            .drawBehind {
                drawRect(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.15f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        ),
                        center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height * 0.35f),
                        radius = size.maxDimension * 0.5f
                    )
                )
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 顶部来电提示
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 80.dp)
            ) {
                Text(
                    text = "语音通话来电",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.size(12.dp))
                Text(
                    text = assistantName.ifBlank { "Eli" },
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 中部头像脉动
            Box(
                modifier = Modifier.scale(pulse),
                contentAlignment = Alignment.Center
            ) {
                // 外圈光晕
                Box(
                    modifier = Modifier
                        .size(180.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                )
                // 头像
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .clip(CircleShape)
                        .border(2.dp, Color.White.copy(alpha = 0.3f), CircleShape)
                        .background(Color.White.copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(R.drawable.small_icon),
                        contentDescription = "Eli",
                        modifier = Modifier.size(120.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                }
            }

            // 底部接听/拒接
            Row(
                horizontalArrangement = Arrangement.spacedBy(80.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(bottom = 72.dp)
            ) {
                IncomingCallButton(
                    icon = HugeIcons.CallEnd01,
                    contentDescription = "拒接",
                    onClick = onDecline,
                    backgroundColor = ColorDecline,
                    iconTint = Color.White,
                    label = "拒接"
                )
                IncomingCallButton(
                    icon = HugeIcons.CallIncoming01,
                    contentDescription = "接听",
                    onClick = onStartCall,
                    backgroundColor = ColorAccept,
                    iconTint = Color.White,
                    label = "接听",
                    size = 76.dp
                )
            }
        }
    }
}

@Composable
private fun IncomingCallButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    backgroundColor: Color,
    iconTint: Color,
    label: String,
    size: Dp = 64.dp,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = backgroundColor,
            modifier = Modifier.size(size)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Icon(icon, contentDescription, tint = iconTint, modifier = Modifier.size(size * 0.4f))
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(label, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
    }
}

private fun getVibrator(context: Context): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
    manager?.defaultVibrator
} else {
    @Suppress("DEPRECATION")
    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

private fun vibratorHasAmplitudeControl(vibrator: Vibrator?): Boolean =
    vibrator != null && vibrator.hasAmplitudeControl()
