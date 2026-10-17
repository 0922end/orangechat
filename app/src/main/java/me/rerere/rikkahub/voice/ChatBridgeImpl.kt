package me.rerere.rikkahub.voice

import android.util.Log
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.service.ChatService
import kotlin.uuid.Uuid

class ChatBridgeImpl(
    private val chatService: ChatService,
    private val conversationId: Uuid,
    private val conversationFlow: StateFlow<Conversation>
) : VoiceCallManager.ChatBridge {

    companion object {
        private const val TAG = "ChatBridgeImpl"
        private const val REPLY_TIMEOUT_MS = 120_000L
        private const val REPLY_IDLE_MS = 3_000L
        private const val POLL_INTERVAL_MS = 100L
    }

    private var lastSentLength = 0

    override suspend fun sendToChatService(
        text: String,
        observation: String?,
        onReplyChunk: (String) -> Unit,
        onReplyEnd: () -> Unit
    ) {
        val parts = mutableListOf<UIMessagePart>()
        parts.add(UIMessagePart.Text(text))
        if (!observation.isNullOrBlank()) {
            parts.add(UIMessagePart.Text("\n[画面描述] $observation"))
        }

        val beforeAssistantText = conversationFlow.value.currentMessages
            .lastOrNull { it.role == MessageRole.ASSISTANT }
            ?.parts?.filterIsInstance<UIMessagePart.Text>()
            ?.joinToString("") { it.text } ?: ""
        lastSentLength = beforeAssistantText.length

        Log.i(TAG, "Sending to chatService: $text")
        chatService.sendMessage(
            conversationId = conversationId,
            content = parts
        )

        var lastSeenText = beforeAssistantText
        var lastChangeTime = System.currentTimeMillis()
        var replyStarted = false

        val result = withTimeoutOrNull(REPLY_TIMEOUT_MS) {
            while (true) {
                delay(POLL_INTERVAL_MS)

                val currentMessages = conversationFlow.value.currentMessages
                val assistantText = currentMessages
                    .lastOrNull { it.role == MessageRole.ASSISTANT }
                    ?.parts?.filterIsInstance<UIMessagePart.Text>()
                    ?.joinToString("") { it.text } ?: ""

                if (assistantText.length > lastSentLength) {
                    val newText = assistantText.substring(lastSentLength)
                    lastSentLength = assistantText.length
                    replyStarted = true
                    lastChangeTime = System.currentTimeMillis()
                    onReplyChunk(newText)
                }

                if (assistantText != lastSeenText) {
                    lastSeenText = assistantText
                    lastChangeTime = System.currentTimeMillis()
                }

                if (replyStarted && System.currentTimeMillis() - lastChangeTime >= REPLY_IDLE_MS) {
                    break
                }

                val lastMsg = currentMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                if (replyStarted && lastMsg != null && lastMsg.loading == false) {
                    val finalText = lastMsg.parts.filterIsInstance<UIMessagePart.Text>()
                        .joinToString("") { it.text }
                    if (finalText.length > lastSentLength) {
                        onReplyChunk(finalText.substring(lastSentLength))
                        lastSentLength = finalText.length
                    }
                    break
                }
            }
        }

        if (result == null) {
            Log.w(TAG, "AI reply timeout after ${REPLY_TIMEOUT_MS}ms")
        }

        onReplyEnd()
        Log.i(TAG, "AI reply ended, total length: $lastSentLength")
    }
}
