package me.rerere.rikkahub.ui.pages.voice

/**
 * Elian 语音通话状态机 (pai-voice turn管理)
 *
 * Idle -> Listening -> Processing -> Speaking -> Listening -> ...
 *                         ↑ (queue)      ↓ (checkQueue)
 */
enum class VoiceCallStatus {
    Idle,
    Listening,
    Processing,
    Speaking,
    Error
}

data class DialogueLine(
    val speaker: String,
    val text: String,
    val isMonologue: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

data class VoiceCallUiState(
    val status: VoiceCallStatus = VoiceCallStatus.Idle,
    val userTranscript: String = "",
    val assistantText: String = "",
    val errorMessage: String? = null,
    val amplitudes: List<Float> = emptyList(),
    val isMuted: Boolean = false,
    val isSpeakerOn: Boolean = true,
    val callDurationSeconds: Int = 0,
    val dialogue: List<DialogueLine> = emptyList(),
    val queuedMessages: Int = 0,
) {
    val isActive: Boolean
        get() = status != VoiceCallStatus.Idle
    val isConnected: Boolean
        get() = status == VoiceCallStatus.Listening ||
                status == VoiceCallStatus.Processing ||
                status == VoiceCallStatus.Speaking
}
