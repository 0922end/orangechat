/*
 * Elian - Chat Embedded WebView
 * True split-screen: top half is WebView, bottom half is chat.
 * No overlay, no drag-to-dismiss (avoids scroll conflict).
 */

package me.rerere.rikkahub.ui.components.message

import android.webkit.JavascriptInterface
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState

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
 * Split-screen WebView panel for the top half of the screen.
 * Just the WebView with a toolbar — the parent layout handles the split.
 */
@Composable
fun ChatEmbeddedWebView(
    url: String,
    onDismiss: () -> Unit,
    onBridgeMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
    ) {
        Column {
            // Toolbar: URL + close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Close",
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            HorizontalDivider()

            // WebView - no gesture interception, normal scrolling
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
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.allowContentAccess = true
                },
            )
        }
    }
}
