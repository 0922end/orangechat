package me.rerere.rikkahub.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

class VoiceCallManager(
    private val context: Context,
    asrApiKey: String, asrWsUrl: String = "wss://ws-j0d35q9jai174ox1.cn-beijing.maas.aliyuncs.com/api-ws/v1/inference?model=qwen3-asr-flash-realtime",
    ttsApiKey: String, ttsVoiceId: String, ttsModelId: String = "eleven_v3",
    visionBackends: List<VisionChain.VisionBackend> = emptyList()
) {
    companion object {
        private const val TAG = "VoiceCallManager"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val CHUNK_SAMPLES = 320
        private const val FRAME_INTERVAL_LIVE = 5000L
        private const val FRAME_INTERVAL_COMPANION = 20000L
    }

    val vad = VadDetector()
    val asr = AsrClient(apiKey = asrApiKey, wsUrl = asrWsUrl)
    val tts = TtsPlayer(apiKey = ttsApiKey, voiceId = ttsVoiceId, modelId = ttsModelId)
    val splitter = SentenceSplitter { sentence -> tts.speak(sentence) }
    val vision = VisionChain().apply { backends = visionBackends }

    enum class CallMode { IDLE, LISTENING, THINKING, SPEAKING }
    private val _mode = MutableStateFlow(CallMode.IDLE); val mode: StateFlow<CallMode> = _mode
    private val _userLevel = MutableStateFlow(0f); val userLevel: StateFlow<Float> = _userLevel
    private val _aiLevel = MutableStateFlow(0f); val aiLevel: StateFlow<Float> = _aiLevel
    private val _isMuted = MutableStateFlow(false); val isMuted: StateFlow<Boolean> = _isMuted
    private val _videoEnabled = MutableStateFlow(false); val videoEnabled: StateFlow<Boolean> = _videoEnabled
    enum class VideoMode { LIVE, COMPANION }
    private val _videoMode = MutableStateFlow(VideoMode.LIVE); val videoMode: StateFlow<VideoMode> = _videoMode
    private val _isUserSpeaking = MutableStateFlow(false); val isUserSpeaking: StateFlow<Boolean> = _isUserSpeaking
    private val _transcript = MutableStateFlow(""); val transcript: StateFlow<String> = _transcript
    private val _partialTranscript = MutableStateFlow(""); val partialTranscript: StateFlow<String> = _partialTranscript

    interface ChatBridge { suspend fun sendToChatService(text: String, observation: String?, onReplyChunk: (String) -> Unit, onReplyEnd: () -> Unit) }
    var chatBridge: ChatBridge? = null

    private var audioRecord: AudioRecord? = null
    private var recordJob: Job? = null
    private var frameJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private var latestJpegFrame: ByteArray? = null
    private var turnSentAt: Long = 0L

    fun start() {
        if (_mode.value != CallMode.IDLE) return
        vad.reset(); splitter.reset(); vision.reset()
        vad.listener = object : VadDetector.Listener {
            override fun onLevel(rms: Float) { _userLevel.value = rms }
            override fun onSpeechStart(barge: Boolean) { handleSpeechStart(barge) }
            override fun onSpeechEnd() { handleSpeechEnd() }
        }
        tts.listener = object : TtsPlayer.Listener {
            override fun onPlaybackStart() { vad.isAiPlaying = true; vad.aiMode = "speaking"; _mode.value = CallMode.SPEAKING }
            override fun onPlaybackEnd() { vad.isAiPlaying = false; if (_mode.value == CallMode.SPEAKING) { _mode.value = CallMode.LISTENING; vad.aiMode = "listening" } }
            override fun onPlaybackLevel(rms: Float) { _aiLevel.value = rms }
            override fun onLatency(ms: Long) { Log.i(TAG, "TTS latency: ${ms}ms") }
            override fun onError(message: String) { Log.e(TAG, "TTS: $message") }
        }
        vision.listener = object : VisionChain.Listener {
            override fun onObservation(description: String) { Log.i(TAG, "Vision: $description") }
            override fun onCompanionEvent(kind: String, text: String) { handleCompanionEvent(kind, text) }
        }
        asr.connect()
        scope.launch { asr.events.collect { event -> when (event) {
            is AsrClient.AsrEvent.Transcript -> handleTranscript(event.text)
            is AsrClient.AsrEvent.Partial -> _partialTranscript.value = event.text
            is AsrClient.AsrEvent.Connected -> Log.i(TAG, "ASR connected")
            is AsrClient.AsrEvent.Closed -> Log.i(TAG, "ASR closed: ${event.reason}")
            is AsrClient.AsrEvent.Error -> Log.e(TAG, "ASR: ${event.message}")
        } } }
        startRecording(); _mode.value = CallMode.LISTENING
    }

    fun hangup() { stopRecording(); tts.stopPlayback(); asr.disconnect(); stopFrameCapture()
        _mode.value = CallMode.IDLE; _isUserSpeaking.value = false; _userLevel.value = 0f; _aiLevel.value = 0f; _transcript.value = ""; _partialTranscript.value = "" }
    fun release() { hangup(); asr.release(); tts.release(); vision.release(); scope.cancel(); cameraExecutor.shutdown() }

    @android.annotation.SuppressLint("MissingPermission")
    private fun startRecording() {
        val bufferSize = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING), CHUNK_SAMPLES * 2 * 4)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, SAMPLE_RATE, CHANNEL, ENCODING, bufferSize).also {
            if (it.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "AudioRecord init failed"); return }; it.startRecording() }
        recordJob = scope.launch { val buffer = ShortArray(CHUNK_SAMPLES)
            while (isActive) { val record = audioRecord ?: break; val read = record.read(buffer, 0, CHUNK_SAMPLES)
                if (read > 0) { val chunk = if (read == CHUNK_SAMPLES) buffer else buffer.copyOf(read)
                    if (!_isMuted.value) vad.onAudioChunk(chunk); if (vad.isSpeaking && !_isMuted.value) asr.pushAudio(chunk) } } }
    }
    private fun stopRecording() { recordJob?.cancel(); recordJob = null; try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}; audioRecord = null }

    private fun handleSpeechStart(barge: Boolean) { _isUserSpeaking.value = true; if (barge) { tts.stopPlayback(); asr.clearAudioBuffer() }; _mode.value = CallMode.LISTENING }
    private fun handleSpeechEnd() { _isUserSpeaking.value = false; turnSentAt = System.currentTimeMillis(); asr.commitAudioBuffer(); _mode.value = CallMode.THINKING }

    private fun handleTranscript(text: String) {
        if (text.isBlank()) return; _transcript.value = text; _partialTranscript.value = ""; tts.newGeneration(); splitter.reset(); _mode.value = CallMode.THINKING
        scope.launch { try { chatBridge?.sendToChatService(text = text, observation = if (vision.isEnabled) vision.lastObservation else null,
            onReplyChunk = { delta -> splitter.feed(delta) }, onReplyEnd = { splitter.flush() }) } catch (e: Exception) { if (e is CancellationException) throw e; _mode.value = CallMode.LISTENING } }
    }

    fun sendText(text: String) { if (text.isBlank()) return; tts.stopPlayback(); turnSentAt = System.currentTimeMillis(); handleTranscript(text) }
    fun setMuted(muted: Boolean) { _isMuted.value = muted; if (muted && vad.isSpeaking) { vad.reset(); _isUserSpeaking.value = false } }

    fun enableVideo(lifecycleOwner: LifecycleOwner, previewSurface: Preview.SurfaceProvider? = null, facing: Int = CameraSelector.LENS_FACING_FRONT) {
        val cpf = ProcessCameraProvider.getInstance(context); cpf.addListener({
            try { val cp = cpf.get(); cp.unbindAll(); val cs = CameraSelector.Builder().requireLensFacing(facing).build()
                val preview = previewSurface?.let { sp -> Preview.Builder().build().also { it.surfaceProvider = sp } }
                val analysis = ImageAnalysis.Builder().setTargetResolution(android.util.Size(480, 640)).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(cameraExecutor) { ip -> val jpeg = imageProxyToJpeg(ip); if (jpeg != null) latestJpegFrame = jpeg; ip.close() }
                val uc = if (preview != null) arrayOf(preview, analysis) else arrayOf(analysis); cp.bindToLifecycle(lifecycleOwner, cs, *uc)
                _videoEnabled.value = true; startFrameCapture()
            } catch (e: Exception) { Log.e(TAG, "Camera init failed: ${e.message}") } }, context.mainExecutor)
    }
    fun disableVideo() { stopFrameCapture(); try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (_: Exception) {}; _videoEnabled.value = false; latestJpegFrame = null }
    fun setVideoMode(mode: VideoMode) { _videoMode.value = mode; vision.resetCompanion(); if (_videoEnabled.value) { stopFrameCapture(); startFrameCapture() } }
    fun flipCamera(lo: LifecycleOwner, sp: Preview.SurfaceProvider? = null) { disableVideo(); enableVideo(lo, sp, CameraSelector.LENS_FACING_BACK) }

    private fun startFrameCapture() { frameJob?.cancel(); frameJob = scope.launch { while (isActive) {
        delay(if (_videoMode.value == VideoMode.LIVE) FRAME_INTERVAL_LIVE else FRAME_INTERVAL_COMPANION)
        val jpeg = latestJpegFrame ?: continue
        when (_videoMode.value) { VideoMode.LIVE -> { if (vision.shouldDescribe(jpeg)) vision.describe(jpeg)?.let { vision.listener?.onObservation(it) } }; VideoMode.COMPANION -> vision.processCompanionFrame(jpeg) }
    } } }
    private fun stopFrameCapture() { frameJob?.cancel(); frameJob = null }

    private fun handleCompanionEvent(kind: String, text: String) { scope.launch { try { tts.newGeneration(); splitter.reset(); _mode.value = CallMode.THINKING
        chatBridge?.sendToChatService(text = "[\u966a\u4f34\u6a21\u5f0f\u00b7$kind] $text", observation = null, onReplyChunk = { splitter.feed(it) }, onReplyEnd = { splitter.flush() })
    } catch (e: Exception) { if (e is CancellationException) throw e; _mode.value = CallMode.LISTENING } } }

    private fun imageProxyToJpeg(imageProxy: ImageProxy): ByteArray? {
        return try { val bmp = imageProxy.toBitmap(); val s = ByteArrayOutputStream(); bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 60, s); s.toByteArray() } catch (_: Exception) { null }
    }
}