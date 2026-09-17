/*
 * Elian - Inline Chat WebView
 * Renders a fully interactive WebView directly inside a chat message bubble.
 * Bridge auto-injects to capture user interactions.
 */

package me.rerere.rikkahub.ui.components.message

import android.webkit.WebViewClient
import android.webkit.WebView as AndroidWebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ArrowExpand
import me.rerere.rikkahub.ui.components.webview.WebView
import me.rerere.rikkahub.ui.components.webview.rememberWebViewState

/** JS script auto-injected after page load to capture user interactions */
private val BRIDGE_LISTENER_SCRIPT = """
(function() {
    if (window.__elianBridgeInjected) return;
    window.__elianBridgeInjected = true;

    document.addEventListener('click', function(e) {
        var el = e.target;
        var info = {
            type: 'click',
            tag: el.tagName,
            text: (el.innerText || '').substring(0, 200),
            href: el.href || el.closest('a')?.href || '',
            id: el.id || '',
            className: (el.className || '').substring(0, 100)
        };
        try { ElianBridge.postMessage(JSON.stringify(info)); } catch(ex) {}
    }, true);

    document.addEventListener('submit', function(e) {
        var info = {
            type: 'submit',
            action: e.target.action || '',
            method: e.target.method || ''
        };
        try { ElianBridge.postMessage(JSON.stringify(info)); } catch(ex) {}
    }, true);

    var lastTitle = document.title;
    new MutationObserver(function() {
        if (document.title !== lastTitle) {
            lastTitle = document.title;
            try {
                ElianBridge.postMessage(JSON.stringify({
                    type: 'navigation',
                    title: document.title,
                    url: location.href
                }));
            } catch(ex) {}
        }
    }).observe(document.querySelector('title') || document.head, {
        childList: true, subtree: true, characterData: true
    });

    try {
        ElianBridge.postMessage(JSON.stringify({
            type: 'page_loaded',
            title: document.title,
            url: location.href
        }));
    } catch(ex) {}
})();
""".trimIndent()

/**
 * Inline WebView rendered directly in chat message flow.
 * Fixed height, interactive, with bridge injection.
 */
@Composable
fun InlineChatWebView(
    url: String,
    modifier: Modifier = Modifier,
    onBridgeMessage: (String) -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val viewHeight = if (expanded) 500.dp else 300.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
    ) {
        // Mini toolbar: domain + expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = runCatching { java.net.URI(url).host }.getOrDefault(url),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = if (expanded) HugeIcons.Cancel01 else HugeIcons.ArrowExpand,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(16.dp),
                )
            }
        }

        HorizontalDivider()

        // Interactive WebView
        val context = androidx.compose.ui.platform.LocalContext.current
        val webViewState = rememberWebViewState(
            url = url,
            interfaces = mapOf(
                "ElianBridge" to ElianBridge(context, onBridgeMessage)
            ),
        )

        WebView(
            state = webViewState,
            modifier = Modifier
                .fillMaxWidth()
                .height(viewHeight),
            onCreated = { webView ->
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.allowContentAccess = true
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: AndroidWebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(BRIDGE_LISTENER_SCRIPT, null)
                    }
                }
            },
        )
    }
}
