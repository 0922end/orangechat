/*
 * Elian - LocalEmbedWebView
 * CompositionLocal to pass embedded WebView state down the tree
 * without threading callbacks through ChatMessage/ChatList/ChatPage.
 */

package me.rerere.rikkahub.ui.components.message

import androidx.compose.runtime.compositionLocalOf

/**
 * Holds the callback to open a URL in the embedded WebView panel.
 * Provided at ChatPage level, consumed by WebEmbedPreviewCard.
 */
val LocalEmbedWebView = compositionLocalOf<((String) -> Unit)?> { null }
