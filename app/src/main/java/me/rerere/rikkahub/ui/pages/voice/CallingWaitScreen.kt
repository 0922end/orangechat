package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.CallEnd01

private val ColorCangCang = Color(0xFF5976BA)
private val ColorQieLan = Color(0xFF7C9FCD)
private val ColorHangUp = Color(0xFFE5484D)

/**
 * Calling 等待界面 - AI 正在接听中
 */
@Composable
fun CallingWaitScreen(
    onCancel: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "calling_pulse")
    val pulse by transition.animateFloat(
        initialValue = 0.9f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 脉动的三个圆圈
        Box(
            modifier = Modifier.size(160.dp),
            contentAlignment = Alignment.Center
        ) {
            for (i in 3 downTo 1) {
                Box(
                    modifier = Modifier
                        .size((60 + i * 30).dp)
                        .scale(if (i == 1) pulse else 1f)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.15f / i))
                )
            }
        }

        Spacer(Modifier.height(32.dp))

        Text(
            "正在呼叫 Eli",
            color = Color.White,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            "等待接听中...",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 14.sp
        )

        Spacer(Modifier.height(80.dp))

        // 取消按钮
        Surface(
            onClick = onCancel,
            shape = CircleShape,
            color = ColorHangUp,
            modifier = Modifier.size(64.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    HugeIcons.CallEnd01,
                    "取消",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "取消",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 13.sp
        )
    }
}
