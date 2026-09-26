package me.rerere.rikkahub.ui.pages.voice

enum class VoiceCallStatus {
    Idle,
    Calling,    // 正在呼叫，等AI接听
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

/**
 * 视频模式
 */
enum class VideoMode {
    Off,       // 摄像头关闭
    Live,      // 实时：画面变化就描述
    Companion  // 陪伴：只在离开/回来/太久没动静时说话
}

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
    // 视频相关
    val videoMode: VideoMode = VideoMode.Off,
    val isFrontCamera: Boolean = true,
    val lastFrameDescription: String = "",
) {
    val isActive: Boolean
        get() = status != VoiceCallStatus.Idle
    val isConnected: Boolean
        get() = status == VoiceCallStatus.Listening ||
                status == VoiceCallStatus.Processing ||
                status == VoiceCallStatus.Speaking
    val isVideoOn: Boolean
        get() = videoMode != VideoMode.Off
}
