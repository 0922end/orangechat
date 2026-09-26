package me.rerere.rikkahub.ui.pages.voice

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Call02
import me.rerere.rikkahub.service.VoiceCallService

private val BarColorCangCang = Color(0xFF5976BA)

/**
 * 悬浮通话条 - 通话中切出通话界面时显示在顶部
 *
 * 用法：在 RouteActivity 或聊天页面的顶层 Composable 里调用
 * VoiceCallOverlayBar(isOnCallPage = false, onClick = { navigateToCallPage() })
 *
 * @param isOnCallPage 当前是否在通话页面，在通话页面时不显示
 * @param onClick 点击后跳回通话页面
 */
@Composable
fun VoiceCallOverlayBar(
    isOnCallPage: Boolean = false,
    onClick: () -> Unit = {}
) {
    val activeConvId by VoiceCallService.activeConversationId.collectAsStateWithLifecycle()
    val isCallActive = activeConvId != null && !isOnCallPage

    AnimatedVisibility(
        visible = isCallActive,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(BarColorCangCang)
                .clip(RoundedCornerShape(bottomStart = 0.dp, bottomEnd = 0.dp))
                .clickable { onClick() }
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = HugeIcons.Call02,
                contentDescription = "通话中",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "通话中  点击返回",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
