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

    // === Elian Style System (Bubble + Toast Bar) ===
    var style = document.createElement('style');
    style.textContent = '\
        .elian-bubble { position:fixed; top:50%; left:50%; transform:translate(-50%,-50%); z-index:99999; max-width:80%; padding:16px 24px; border-radius:16px; font-size:15px; line-height:1.5; opacity:0; animation:elianIn 0.3s ease forwards; pointer-events:none; box-shadow:0 8px 32px rgba(0,0,0,0.15); text-align:center; } \
        .elian-bubble.talk { background:#fff0f5; border:2px solid #ff69b4; color:#333; } \
        .elian-bubble.action { background:#f0f4ff; border:2px solid #6495ed; color:#555; font-style:italic; } \
        .elian-bubble.fadeout { animation:elianOut 0.5s ease forwards; } \
        @keyframes elianIn { from{opacity:0;transform:translate(-50%,-50%) scale(0.8)} to{opacity:1;transform:translate(-50%,-50%) scale(1)} } \
        @keyframes elianOut { from{opacity:1;transform:translate(-50%,-50%) scale(1)} to{opacity:0;transform:translate(-50%,-50%) scale(0.8)} } \
        .elian-toast { position:fixed; bottom:20px; left:50%; transform:translateX(-50%); z-index:99998; padding:8px 18px; border-radius:20px; font-size:13px; opacity:0; animation:elianToastIn 0.25s ease forwards; pointer-events:none; max-width:85%; text-align:center; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; } \
        .elian-toast.user { background:#FFE4EC; color:#D4728C; border:1px solid #F5C2D1; } \
        .elian-toast.ai { background:#E4F0FF; color:#6B9BD2; border:1px solid #C2D8F0; } \
        .elian-toast.fadeout { animation:elianToastOut 0.3s ease forwards; } \
        @keyframes elianToastIn { from{opacity:0;transform:translateX(-50%) translateY(10px)} to{opacity:1;transform:translateX(-50%) translateY(0)} } \
        @keyframes elianToastOut { from{opacity:1} to{opacity:0} } \
    ';
    document.head.appendChild(style);

    // === Toast Bar (pink for user, blue for AI) ===
    window.ElianToast = function(text, direction) {
        var old = document.querySelector('.elian-toast');
        if (old) old.remove();
        var t = document.createElement('div');
        t.className = 'elian-toast ' + (direction || 'user');
        t.textContent = (direction === 'ai' ? '\u2190 ' : '\u2192 ') + text;
        document.body.appendChild(t);
        setTimeout(function() { t.classList.add('fadeout'); setTimeout(function() { t.remove(); }, 300); }, 2000);
    };

    function toastForUser(data) {
        var t = data.type || data.action || '';
        switch(t) {
            case 'click': return '\u70B9\u51FB\u4E86\u300C' + (data.text || data.tag || '').substring(0,30) + '\u300D';
            case 'submit': return '\u63D0\u4EA4\u4E86\u8868\u5355';
            case 'navigation': return '\u8DF3\u8F6C\u9875\u9762';
            case 'page_loaded': return '\u9875\u9762\u5DF2\u52A0\u8F7D';
            case 'page_content': return '\u5185\u5BB9\u5DF2\u6293\u53D6';
            case 'idle': return '\u7B49\u5F85\u4E2D...';
            default: return t;
        }
    }

    // === Elian Bubble System ===
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
                window.ElianToast('Eli: ' + (a.bubble.text || '').substring(0,30), 'ai');
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
                window.ElianToast('Eli: ' + (a.description || '\u6267\u884C\u64CD\u4F5C'), 'ai');
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

    // === ElianGetState protocol (for custom game pages) ===
    window.ElianQueryState = function() {
        if (typeof window.ElianGetState === 'function') {
            try {
                var state = window.ElianGetState();
                ElianBridge.postMessage(JSON.stringify({ type: 'state_response', state: state }));
            } catch(ex) {}
        }
    };

    // === Page Content Extraction (for third-party pages) ===
    function extractPageContent() {
        var text = '';
        var el = document.querySelector('article') || document.querySelector('main') || document.querySelector('.content') || document.body;
        if (el) text = (el.innerText || '').substring(0, 3000);
        var imgs = [];
        document.querySelectorAll('img[alt]').forEach(function(img) {
            if (img.alt && img.alt.length > 2) imgs.push(img.alt.substring(0, 100));
        });
        if (imgs.length > 10) imgs = imgs.slice(0, 10);
        return { type: 'page_content', url: location.href, title: document.title, content: text, images: imgs };
    }

    // === Unified bridge send with toast ===
    function bridgeSend(data) {
        try {
            var isAi = data.type === 'ai_action';
            window.ElianToast(toastForUser(data), isAi ? 'ai' : 'user');
            ElianBridge.postMessage(JSON.stringify(data));
        } catch(ex) {}
    }

    // === User Interaction Listeners (with 1s debounce) ===
    var lastClickTime = 0;
    document.addEventListener('click', function(e) {
        var now = Date.now();
        if (now - lastClickTime < 1000) return;
        lastClickTime = now;
        var el = e.target;
        var state = null;
        if (typeof window.ElianGetState === 'function') { try { state = window.ElianGetState(); } catch(ex) {} }
        var info = {
            type: 'click',
            tag: el.tagName,
            text: (el.innerText || '').substring(0, 200),
            href: el.href || (el.closest('a') ? el.closest('a').href : ''),
            id: el.id || '',
            className: (el.className || '').substring(0, 100),
            state: state
        };
        bridgeSend(info);
    }, true);

    document.addEventListener('submit', function(e) {
        bridgeSend({ type: 'submit', action: e.target.action || '', method: e.target.method || '' });
    }, true);

    var lastTitle = document.title;
    new MutationObserver(function() {
        if (document.title !== lastTitle) {
            lastTitle = document.title;
            bridgeSend({ type:'navigation', title:document.title, url:location.href });
        }
    }).observe(document.querySelector('title') || document.head, { childList:true, subtree:true, characterData:true });

    // === Idle Detection (30s no interaction) ===
    var idleTimer = null;
    function resetIdle() {
        if (idleTimer) clearTimeout(idleTimer);
        idleTimer = setTimeout(function() {
            bridgeSend({ type: 'idle', seconds: 30, url: location.href });
        }, 30000);
    }
    document.addEventListener('click', resetIdle, true);
    document.addEventListener('scroll', resetIdle, true);
    document.addEventListener('keydown', resetIdle, true);
    resetIdle();

    // === Page loaded + content extraction ===
    bridgeSend({ type:'page_loaded', title:document.title, url:location.href });
    setTimeout(function() { try { ElianBridge.postMessage(JSON.stringify(extractPageContent())); } catch(ex) {} }, 1500);
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
                        userAgentString = if (url.contains("xiaohongshu.com") || url.contains("xhslink.cn") || url.contains("xhs.cn")) {
                            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                        } else {
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Mobile Safari/537.36"
                        }
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
