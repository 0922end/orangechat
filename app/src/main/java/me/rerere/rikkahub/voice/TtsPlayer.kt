package me.rerere.rikkahub.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TtsPlayer(
    private val apiKey: String,
    private val voiceId: String,
    private val modelId: String = "eleven_v3",
    private val sampleRate: Int = 44100,
    private val stability: Float = 0.30f,
    private val similarityBoost: Float = 0.75f
) {
    companion object { private const val TAG = "TtsPlayer" }

    interface Listener {
        fun onPlaybackStart()
        fun onPlaybackEnd()
        fun onPlaybackLevel(rms: Float)
        fun onLatency(ms: Long)
        fun onError(message: String)
    }
    var listener: Listener? = null
    private val generationId = AtomicInteger(0)
    val isPlaying = AtomicBoolean(false)
    private val queue = ConcurrentLinkedQueue<PlayItem>()
    private var audioTrack: AudioTrack? = null
    private var playJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS).readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    private data class PlayItem(val pcmData: ByteArray, val genId: Int)

    fun speak(text: String, emotionTags: String = "") {
        if (text.isBlank()) return
        Log.i(TAG, "TTS speak called: " + text.take(50))
        val currentGenId = generationId.get()
        val fullText = if (emotionTags.isNotBlank()) "$emotionTags $text" else text
        scope.launch {
            val startTime = System.currentTimeMillis()
            var firstChunkLogged = false
            try {
                val body = JSONObject().apply {
                    put("text", fullText); put("model_id", modelId)
                    put("voice_settings", JSONObject().apply { put("stability", stability.toDouble()); put("similarity_boost", similarityBoost.toDouble()) })
                }.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder().url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId/stream").header("xi-api-key", apiKey).header("Accept", "audio/mpeg").post(body).build()
                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) { listener?.onError("TTS failed: ${response.code}"); response.close(); return@launch }
                if (generationId.get() != currentGenId) { response.close(); return@launch }
                val responseBody = response.body ?: return@launch
                val inputStream = responseBody.byteStream()
                val mp3Buffer = ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    if (generationId.get() != currentGenId) { inputStream.close(); responseBody.close(); return@launch }
                    val bytesRead = inputStream.read(buffer); if (bytesRead == -1) break
                    mp3Buffer.write(buffer, 0, bytesRead)
                    if (!firstChunkLogged) { firstChunkLogged = true; listener?.onLatency(System.currentTimeMillis() - startTime) }
                }
                inputStream.close(); responseBody.close()
                if (generationId.get() != currentGenId) return@launch
                val pcmData = decodeMp3ToPcm(mp3Buffer.toByteArray())
                if (pcmData == null || pcmData.isEmpty()) { Log.e(TAG, "MP3 decode returned null/empty"); listener?.onError("MP3 decode failed"); return@launch }
                Log.i(TAG, "TTS decoded " + pcmData.size + " PCM bytes, queuing...")
                if (generationId.get() != currentGenId) return@launch
                queue.offer(PlayItem(pcmData, currentGenId))
                ensureDraining()
            } catch (e: Exception) { if (e is CancellationException) throw e; listener?.onError("TTS error: ${e.message}") }
        }
    }

    private fun ensureDraining() {
        if (playJob?.isActive == true) return
        playJob = scope.launch {
            try {
                val track = createAudioTrack(); audioTrack = track; track.play()
                var playbackStarted = false
                while (isActive) {
                    val item = queue.poll()
                    if (item == null) { delay(50); if (queue.isEmpty()) break; continue }
                    if (item.genId != generationId.get()) continue
                    if (!playbackStarted) { playbackStarted = true; isPlaying.set(true); withContext(Dispatchers.Main) { listener?.onPlaybackStart() } }
                    val pcm = item.pcmData; var offset = 0
                    while (offset < pcm.size && isActive && item.genId == generationId.get()) {
                        val written = track.write(pcm, offset, pcm.size - offset); if (written < 0) break; offset += written
                        if (written > 0) listener?.onPlaybackLevel(calculatePcmRms(pcm, offset - written, written))
                    }
                }
                track.stop(); track.release(); audioTrack = null
                if (playbackStarted) { isPlaying.set(false); withContext(Dispatchers.Main) { listener?.onPlaybackEnd() } }
            } catch (e: Exception) { if (e is CancellationException) throw e; isPlaying.set(false); listener?.onPlaybackEnd() }
        }
    }

    fun stopPlayback() {
        generationId.incrementAndGet(); queue.clear(); playJob?.cancel(); playJob = null
        try { audioTrack?.stop(); audioTrack?.release() } catch (_: Exception) {}
        audioTrack = null; if (isPlaying.getAndSet(false)) listener?.onPlaybackEnd()
    }
    fun newGeneration(): Int = generationId.incrementAndGet()
    fun currentGeneration(): Int = generationId.get()
    fun release() { stopPlayback(); scope.cancel(); httpClient.dispatcher.executorService.shutdown() }

    private fun createAudioTrack(): AudioTrack {
        val bufferSize = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        return AudioTrack.Builder().setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferSize * 2).setTransferMode(AudioTrack.MODE_STREAM).build()
    }

    private fun decodeMp3ToPcm(mp3Data: ByteArray): ByteArray? {
        try {
            val extractor = android.media.MediaExtractor(); extractor.setDataSource(Mp3DataSource(mp3Data))
            if (extractor.trackCount == 0) return null
            extractor.selectTrack(0); val format = extractor.getTrackFormat(0)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: return null
            val codec = android.media.MediaCodec.createDecoderByType(mime); codec.configure(format, null, null, 0); codec.start()
            val output = ByteArrayOutputStream(); val bufferInfo = android.media.MediaCodec.BufferInfo()
            var inputDone = false; var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val idx = codec.dequeueInputBuffer(10000)
                    if (idx >= 0) { val buf = codec.getInputBuffer(idx)!!; val size = extractor.readSampleData(buf, 0)
                        if (size < 0) { codec.queueInputBuffer(idx, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { codec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0); extractor.advance() } }
                }
                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIdx >= 0) { if (bufferInfo.size > 0) { val ob = codec.getOutputBuffer(outIdx)!!; val chunk = ByteArray(bufferInfo.size); ob.get(chunk); output.write(chunk) }
                    codec.releaseOutputBuffer(outIdx, false); if (bufferInfo.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true }
            }
            codec.stop(); codec.release(); extractor.release(); return output.toByteArray()
        } catch (e: Exception) { Log.e(TAG, "MP3 decode error: ${e.message}"); return null }
    }

    private fun calculatePcmRms(pcm: ByteArray, offset: Int, length: Int): Float {
        if (length < 2) return 0f; var sum = 0.0; val samples = length / 2
        for (i in 0 until samples) { val idx = offset + i * 2; if (idx + 1 >= pcm.size) break
            val s = (pcm[idx].toInt() and 0xFF) or (pcm[idx + 1].toInt() shl 8); sum += (s.toShort().toFloat() / 32768f).let { (it * it).toDouble() } }
        return Math.sqrt(sum / samples).toFloat()
    }

    private class Mp3DataSource(private val data: ByteArray) : android.media.MediaDataSource() {
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= data.size) return -1; val r = (data.size - position).toInt(); val n = minOf(size, r); System.arraycopy(data, position.toInt(), buffer, offset, n); return n
        }
        override fun getSize(): Long = data.size.toLong()
        override fun close() {}
    }
}