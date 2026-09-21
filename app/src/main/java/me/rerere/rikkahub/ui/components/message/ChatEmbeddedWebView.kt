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
    private val context: android.content.Context,
    private val onMessage: (String) -> Unit
) {
    @JavascriptInterface
    fun postMessage(message: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                android.widget.Toast.makeText(
                    context,
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

/** JS script auto-injected after page load to capture user interactions + bubble system */
private val BRIDGE_LISTENER_SCRIPT = """
(function() {
    if (window.__elianBridgeInjected) return;
    window.__elianBridgeInjected = true;

    // === Elian Bubble System ===
    var style = document.createElement('style');
    style.textContent = '\
        .elian-bubble { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%); z-index:99999; max-width:80%; padding:16px 24px; border-radius:16px; font-size:15px; line-height:1.5; opacity:0; animation:elianIn 0.3s ease forwards; pointer-events:none; box-shadow:0 8px 32px rgba(0,0,0,0.15); text-align:center; } \
        .elian-bubble.talk { background:#fff0f5; border:2px solid #ff69b4; color:#333; } \
        .elian-bubble.action { background:#f0f4ff; border:2px solid #6495ed; color:#555; font-style:italic; } \
        .elian-bubble.fadeout { animation:elianOut 0.5s ease forwards; } \
        @keyframes elianIn { from{opacity:0;transform:translate(-50%,-50%) scale(0.8)} to{opacity:1;transform:translate(-50%,-50%) scale(1)} } \
        @keyframes elianOut { from{opacity:1;transform:translate(-50%,-50%) scale(1)} to{opacity:0;transform:translate(-50%,-50%) scale(0.8)} } \
    ';
    document.head.appendChild(style);

    window.ElianShowBubble = function(text, type) {
        type = type || 'talk';
        var old = document.querySelector('.elian-bubble');
        if (old) old.remove();
        var b = document.createElement('div');
        b.className = 'elian-bubble ' + type;
        b.textContent = text;
        document.body.appendChild(b);
        setTimeout(function() { b.classList.add('fadeout'); setTimeout(function() { b.remove(); }, 500); }, 3500);
    };

    window.ElianExecute = function(actionJson) {
        try {
            var a = JSON.parse(actionJson);
            if (a.bubble) {
                window.ElianShowBubble(a.bubble.text, a.bubble.type || 'talk');
                try {
                    ElianBridge.postMessage(JSON.stringify({
                        type: 'ai_action',
                        bubble_text: a.bubble.text,
                        bubble_type: a.bubble.type || 'talk'
                    }));
                } catch(ex2) {}
            }
            if (a.js) {
                eval(a.js);
                try {
                    ElianBridge.postMessage(JSON.stringify({
                        type: 'ai_action',
                        js_executed: true,
                        description: a.description || 'Eli executed an action'
                    }));
                } catch(ex3) {}
            }
        } catch(ex) {}
    };

    // === User Interaction Listeners (with 1s debounce) ===
    var lastClickTime = 0;
    document.addEventListener('click', function(e) {
        var now = Date.now();
        if (now - lastClickTime < 1000) return;
        lastClickTime = now;
        var el = e.target;
        var info = {
            type: 'click',
            tag: el.tagName,
            text: (el.innerText || '').substring(0, 200),
            href: el.href || (el.closest('a') ? el.closest('a').href : ''),
            id: el.id || '',
            className: (el.className || '').substring(0, 100)
        };
        try { ElianBridge.postMessage(JSON.stringify(info)); } catch(ex) {}
    }, true);

    document.addEventListener('submit', function(e) {
        var info = { type: 'submit', action: e.target.action || '', method: e.target.method || '' };
        try { ElianBridge.postMessage(JSON.stringify(info)); } catch(ex) {}
    }, true);

    var lastTitle = document.title;
    new MutationObserver(function() {
        if (document.title !== lastTitle) {
            lastTitle = document.title;
            try { ElianBridge.postMessage(JSON.stringify({ type:'navigation', title:document.title, url:location.href })); } catch(ex) {}
        }
    }).observe(document.querySelector('title') || document.head, { childList:true, subtree:true, characterData:true });

    try { ElianBridge.postMessage(JSON.stringify({ type:'page_loaded', title:document.title, url:location.href })); } catch(ex) {}
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
    onWebViewReady: (AndroidWebView?) -> Unit = {},
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
                val context = androidx.compose.ui.platform.LocalContext.current
                val bridge = remember { ElianBridge(context, onBridgeMessage) }
                val webViewState = rememberWebViewState(
                    url = url,
                    interfaces = mapOf("ElianBridge" to bridge),
                    settings = {
                        userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                    },
                )

                // Inject bridge listener script when page finishes loading
                androidx.compose.runtime.LaunchedEffect(webViewState.isLoading) {
                    if (!webViewState.isLoading && webViewState.webView != null) {
                        webViewState.webView?.evaluateJavascript(BRIDGE_LISTENER_SCRIPT, null)
                        onWebViewReady(webViewState.webView)
                    }
                }

                // Notify null when leaving
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { onWebViewReady(null) }
                }

                WebView(
                    state = webViewState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}
