/*
 * Elian - Chat Embedded WebView
 * True split-screen: top half is WebView, bottom half is chat.
 * Auto-injects JS listener to capture user interactions.
 */

package me.rerere.rikkahub.ui.components.message

import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.WebView as AndroidWebView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** JS script auto-injected after page load to capture user interactions */
private val BRIDGE_LISTENER_SCRIPT = """
(function() {
    if (window.__elianBridgeInjected) return;
    window.__elianBridgeInjected = true;

    // Capture clicks
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

    // Capture form submits
    document.addEventListener('submit', function(e) {
        var info = {
            type: 'submit',
            action: e.target.action || '',
            method: e.target.method || ''
        };
        try { ElianBridge.postMessage(JSON.stringify(info)); } catch(ex) {}
    }, true);

    // Capture navigation (page title changes)
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

    // Notify page loaded
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
 * Split-screen WebView panel for the top half of the screen.
 * The parent layout handles the split (this is the top portion).
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

            // WebView - normal scrolling, no gesture interception
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
                    // Auto-inject bridge listener after each page load
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
}
