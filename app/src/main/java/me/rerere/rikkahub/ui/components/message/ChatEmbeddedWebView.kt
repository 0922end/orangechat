/*
 * Elian - Chat Embedded WebView
 * Split-screen WebView panel that renders web pages inline within the chat.
 * Supports JS bridge for bidirectional communication.
 */

package me.rerere.rikkahub.ui.components.message

import android.webkit.JavascriptInterface
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState
import kotlin.math.roundToInt

/**
 * Bridge JS interface injected into every embedded web page.
 * Allows web pages to send messages back to the chat.
 */
class ElianBridge(
    private val onMessage: (String) -> Unit
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        onMessage(message)
    }

    @JavascriptInterface
    fun getAppInfo(): String {
        return "{\"app\":\"Elian\",\"version\":\"1.0\"}"
    }
}

/**
 * Embedded WebView panel that slides up from bottom.
 * Shows a toolbar with title, URL, and close button.
 * Supports pull-down to dismiss gesture.
 */
@Composable
fun ChatEmbeddedWebView(
    url: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    onBridgeMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Handle back press to close
    BackHandler(enabled = visible) {
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        val density = LocalDensity.current
        var offsetY by remember { mutableFloatStateOf(0f) }
        val dismissThreshold = with(density) { 150.dp.toPx() }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .offset { IntOffset(0, offsetY.roundToInt().coerceAtLeast(0)) }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragEnd = {
                            if (offsetY > dismissThreshold) {
                                onDismiss()
                            }
                            offsetY = 0f
                        },
                        onDragCancel = {
                            offsetY = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            offsetY = (offsetY + dragAmount).coerceAtLeast(0f)
                        }
                    )
                },
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Drag handle
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        modifier = Modifier
                            .width(32.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                // Toolbar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    ) {
                        Text(
                            text = url,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = "Close",
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                // WebView
                val webViewState = rememberWebViewState(
                    url = url,
                    interfaces = mapOf(
                        "ElianBridge" to ElianBridge(onBridgeMessage)
                    ),
                )

                WebView(
                    state = webViewState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onCreated = { webView ->
                        // Inject bridge script after page loads
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.allowContentAccess = true
                    },
                )
            }
        }
    }
}
