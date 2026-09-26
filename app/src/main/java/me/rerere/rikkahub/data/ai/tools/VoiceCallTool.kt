package me.rerere.rikkahub.data.ai.tools

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.VoiceCallService
import me.rerere.common.android.Logging

/**
 * AI 主动挂断语音/视频通话的系统工具
 *
 * 场景：AI 在通话中生气/不想聊了/觉得该挂了，
 * 可以在思考链里决定调用这个工具突然挂断，
 * 用户看到的只是 "对方已挂断" + 聊天框追加的话
 */
fun createEndVoiceCallTool(context: Context): Tool = Tool(
    name = "end_voice_call",
    description = "End the current voice/video call. Use this when you want to hang up the call — for example when you're upset, don't want to talk anymore, or think the conversation should end. The user will see 'The other party hung up' on their screen. After hanging up, your next text reply will appear in the chat as a follow-up message.",
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                putJsonObject("reason") {
                    put("type", "string")
                    put("description", "Optional internal reason for hanging up (not shown to user, logged only).")
                }
            },
            required = emptyList()
        )
    },
    execute = { args ->
        val params = args.jsonObject
        val reason = params["reason"]?.jsonPrimitive?.contentOrNull ?: "AI decided to hang up"

        try {
            if (!VoiceCallService.isRunning()) {
                return@Tool listOf(UIMessagePart.Text(
                    buildJsonObject {
                        put("success", false)
                        put("error", "No active voice call to end")
                    }.toString()
                ))
            }

            Logging.log("VoiceCallTool", "AI hanging up call, reason: $reason")

            // 在主线程执行挂断
            Handler(Looper.getMainLooper()).post {
                VoiceCallService.stop(context)
            }

            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", true)
                    put("message", "Voice call ended. The user sees 'The other party hung up'. You can now send a follow-up text message explaining why you hung up.")
                }.toString()
            ))
        } catch (e: Exception) {
            Logging.log("VoiceCallTool", "Failed to end call: ${e.message}")
            listOf(UIMessagePart.Text(
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Unknown error")
                }.toString()
            ))
        }
    }
)
