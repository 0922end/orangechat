/*
 * Elian - Chat Embedded WebView
 * Fullscreen WebView overlay with floating toolbar.
 * Auto-injects JS listener to capture user interactions.
 */

package me.rerere.rikkahub.ui.components.message

import android.webkit.JavascriptInterface
import android.webkit.WebViewClient
import android.webkit.WebView as AndroidWebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
 * Fullscreen WebView with floating close button.
 * Uses Box layout so toolbar floats ABOVE WebView - no touch event stealing.
 */
@Composable
fun ChatEmbeddedWebView(
    url: String,
    onDismiss: () -> Unit,
    onBridgeMessage: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.background(MaterialTheme.colorScheme.background)) {
        // WebView fills entire space
        val webViewState = rememberWebViewState(
            url = url,
            interfaces = mapOf(
                "ElianBridge" to ElianBridge(onBridgeMessage)
            ),
        )

        WebView(
            state = webViewState,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 48.dp),
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

        // Floating toolbar on top - guaranteed to receive clicks
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .zIndex(100f)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .align(Alignment.TopStart)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = url,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.weight(1f),
            )
            // Close button - large touch target
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = androidx.compose.material3.ripple(),
                        onClick = onDismiss,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = HugeIcons.Cancel01,
                    contentDescription = "Close",
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}
