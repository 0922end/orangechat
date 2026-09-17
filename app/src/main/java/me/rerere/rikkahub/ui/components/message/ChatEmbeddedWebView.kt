/*
 * Elian - Chat Embedded WebView
 * Fullscreen WebView overlay with bridge.
 * Column layout: toolbar above WebView (not overlapping) so close button always works.
 */

package me.rerere.rikkahub.ui.components.message

import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.WebView as AndroidWebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 */
class ElianBridge(
    private val onMessage: (String) -> Unit
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(
                    me.rerere.rikkahub.RikkaHubApplication.instance,
                    "Bridge: ${message.take(80)}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {}
            onMessage(message)
        }
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
 * Fullscreen WebView with AnimatedVisibility + Column layout.
 * Toolbar is ABOVE WebView in Column (not overlapping), so close button always works.
 * No drag gesture (avoids scroll conflict).
 */
@Composable
fun ChatEmbeddedWebView(
    url: String,
    visible: Boolean,
    onDismiss: () -> Unit,
    onBridgeMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = visible) {
        onDismiss()
    }

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it },
        modifier = modifier,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            tonalElevation = 4.dp,
        ) {
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
                // Toolbar - in Column above WebView, no overlap
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

                // WebView with bridge injection
                val bridge = remember { ElianBridge(onBridgeMessage) }
                val webViewState = rememberWebViewState(url = url)

                WebView(
                    state = webViewState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onCreated = { webView ->
                        webView.settings.javaScriptEnabled = true
                        webView.settings.domStorageEnabled = true
                        webView.settings.allowContentAccess = true
                        webView.addJavascriptInterface(bridge, "ElianBridge")
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
}
