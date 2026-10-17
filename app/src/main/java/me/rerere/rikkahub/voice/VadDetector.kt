package me.rerere.rikkahub.voice

class VadDetector(
    private val startHold: Int = 3,
    private val endSilenceMs: Long = 650L,
    private val bargeInMs: Long = 820L,
    private val floor: Float = 0.006f,
    private val gain: Float = 3.2f,
    private val exitRatio: Float = 0.62f,
    private val speakingGain: Float = 3.3f,
    private val watchdogMs: Long = 1600L
) {
    interface Listener {
        fun onLevel(rms: Float)
        fun onSpeechStart(barge: Boolean)
        fun onSpeechEnd()
    }
    var listener: Listener? = null
    var isSpeaking: Boolean = false; private set
    var isAiPlaying: Boolean = false
    var aiMode: String = "listening"
    private var noise: Float = 0.01f
    private var hot: Int = 0
    private var hotSince: Long = 0L
    private var silenceSince: Long = 0L
    private var lastHotAt: Long = 0L
    private var speechMin: Float = 1.0f

    fun onAudioChunk(pcm16: ShortArray) {
        if (pcm16.isEmpty()) return
        var sum = 0.0
        for (sample in pcm16) { val n = sample.toFloat() / 32768f; sum += (n * n).toDouble() }
        val rms = Math.sqrt(sum / pcm16.size).toFloat()
        listener?.onLevel(rms)
        val now = System.currentTimeMillis()
        if (!isSpeaking) { noise = noise * 0.97f + rms * 0.03f }
        else { speechMin = minOf(speechMin, rms); noise = minOf(noise * 1.0015f, maxOf(noise, speechMin)) }
        var enter = maxOf(floor, noise * gain)
        if (isAiPlaying) enter *= speakingGain
        val exit = enter * exitRatio
        if (rms > enter) lastHotAt = now
        if (!isSpeaking) {
            if (rms > enter) {
                hot += 1; if (hotSince == 0L) hotSince = now
                if (hot >= startHold) {
                    val needed = if (isAiPlaying && aiMode == "speaking") bargeInMs else 0L
                    if (now - hotSince >= needed) doSpeechStart()
                }
            } else { hot = 0; hotSince = 0L }
        } else {
            if (rms < exit) {
                if (silenceSince == 0L) silenceSince = now
                else if (now - silenceSince >= endSilenceMs) { doSpeechEnd(); return }
            } else { silenceSince = 0L }
            if (lastHotAt > 0 && now - lastHotAt >= watchdogMs) doSpeechEnd()
        }
    }

    private fun doSpeechStart() {
        isSpeaking = true; speechMin = 1.0f; lastHotAt = System.currentTimeMillis(); silenceSince = 0L
        val barge = isAiPlaying && aiMode == "speaking"
        listener?.onSpeechStart(barge)
    }
    private fun doSpeechEnd() {
        isSpeaking = false; silenceSince = 0L; hot = 0; hotSince = 0L
        listener?.onSpeechEnd()
    }
    fun reset() {
        isSpeaking = false; isAiPlaying = false; aiMode = "listening"
        noise = 0.01f; hot = 0; hotSince = 0L; silenceSince = 0L; lastHotAt = 0L; speechMin = 1.0f
    }
}