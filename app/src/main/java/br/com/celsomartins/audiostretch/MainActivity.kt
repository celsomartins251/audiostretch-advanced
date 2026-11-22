package br.com.celsomartins.audiostretch

import android.content.Intent
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONArray
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var player: MediaPlayer? = null
    private var currentUri: Uri? = null
    private lateinit var assetLoader: WebViewAssetLoader
    private val decodeExecutor = Executors.newSingleThreadExecutor()
    private val decodeToken = AtomicInteger(0)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) { }
            loadAudio(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = false
        webView.settings.javaScriptCanOpenWindowsAutomatically = false
        assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }
        webView.webChromeClient = WebChromeClient()
        webView.addJavascriptInterface(AudioBridge(), "AudioBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    private fun loadAudio(uri: Uri) {
        decodeToken.incrementAndGet()
        releasePlayer()
        try {
            val next = MediaPlayer()
            next.setDataSource(this, uri)
            next.setOnPreparedListener { media ->
                player = media
                currentUri = uri
                val safeName = (uri.lastPathSegment ?: "Áudio importado")
                    .replace("\\", " ").replace("\"", " ").replace("\n", " ")
                webView.evaluateJavascript(
                    "window.onNativeAudioLoaded && window.onNativeAudioLoaded(${media.duration}, \"${safeName}\")",
                    null
                )
                analyzeWaveform(uri, media.duration)
            }
            next.setOnErrorListener { _, what, extra ->
                notifyError("Falha ao decodificar áudio ($what / $extra).")
                true
            }
            next.prepareAsync()
        } catch (error: Exception) {
            notifyError(error.message ?: "Não foi possível abrir este arquivo.")
        }
    }

    private fun analyzeWaveform(uri: Uri, durationMs: Int) {
        val token = decodeToken.incrementAndGet()
        decodeExecutor.execute {
            var extractor: MediaExtractor? = null
            var decoder: MediaCodec? = null
            try {
                extractor = MediaExtractor()
                extractor.setDataSource(this, uri, null)
                var audioTrack = -1
                var audioFormat: MediaFormat? = null
                for (i in 0 until extractor.trackCount) {
                    val candidate = extractor.getTrackFormat(i)
                    val mime = candidate.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        audioTrack = i
                        audioFormat = candidate
                        break
                    }
                }
                if (audioTrack < 0 || audioFormat == null) throw IllegalArgumentException("Não foi encontrada uma faixa de áudio.")
                extractor.selectTrack(audioTrack)
                val mime = audioFormat.getString(MediaFormat.KEY_MIME)!!
                decoder = MediaCodec.createDecoderByType(mime)
                decoder.configure(audioFormat, null, null, 0)
                decoder.start()

                val peaks = FloatArray(1600)
                var sampleRate = audioFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = audioFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                var inputDone = false
                var outputDone = false
                var frameIndex = 0L
                val info = MediaCodec.BufferInfo()

                while (!outputDone && token == decodeToken.get()) {
                    if (!inputDone) {
                        val inputIndex = decoder.dequeueInputBuffer(10_000)
                        if (inputIndex >= 0) {
                            val inputBuffer = decoder.getInputBuffer(inputIndex)!!
                            inputBuffer.clear()
                            val size = extractor.readSampleData(inputBuffer, 0)
                            if (size < 0) {
                                decoder.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }

                    when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val outFormat = decoder.outputFormat
                            if (outFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            if (outFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            if (outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        else -> if (outputIndex >= 0) {
                            val output = decoder.getOutputBuffer(outputIndex)
                            if (output != null && info.size > 0 && channels > 0) {
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                output.order(ByteOrder.nativeOrder())
                                val bytesPerSample = when (pcmEncoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT -> 4
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> 2
                                }
                                val frameBytes = bytesPerSample * channels
                                val frames = info.size / frameBytes
                                val totalFrames = max(1L, durationMs.toLong() * sampleRate / 1000L)
                                for (frame in 0 until frames) {
                                    var framePeak = 0f
                                    repeat(channels) { channel ->
                                        val sample = when (pcmEncoding) {
                                            AudioFormat.ENCODING_PCM_FLOAT -> output.getFloat()
                                            AudioFormat.ENCODING_PCM_8BIT -> ((output.get().toInt() and 0xff) - 128) / 128f
                                            else -> output.getShort() / 32768f
                                        }
                                        framePeak = max(framePeak, abs(sample))
                                    }
                                    val bucket = ((frameIndex * peaks.size) / totalFrames).toInt().coerceIn(0, peaks.lastIndex)
                                    if (framePeak > peaks[bucket]) peaks[bucket] = framePeak
                                    frameIndex++
                                }
                            }
                            outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }

                if (token == decodeToken.get()) {
                    val json = JSONArray()
                    peaks.forEach { json.put(it.toDouble()) }
                    mainHandler.post {
                        if (token == decodeToken.get() && !isFinishing) {
                            webView.evaluateJavascript("window.onNativeWaveform && window.onNativeWaveform($json)", null)
                        }
                    }
                }
            } catch (error: Exception) {
                if (token == decodeToken.get()) {
                    val message = (error.message ?: "Não foi possível analisar a forma de onda.")
                        .replace("\\", " ").replace("\"", " ").replace("\n", " ")
                    mainHandler.post {
                        webView.evaluateJavascript("window.onNativeWaveformError && window.onNativeWaveformError(\"$message\")", null)
                    }
                }
            } finally {
                try { decoder?.stop() } catch (_: Exception) { }
                try { decoder?.release() } catch (_: Exception) { }
                try { extractor?.release() } catch (_: Exception) { }
            }
        }
    }

    private fun notifyError(message: String) {
        val safe = message.replace("\\", " ").replace("\"", " ").replace("\n", " ")
        webView.post {
            webView.evaluateJavascript("window.onNativeAudioError && window.onNativeAudioError(\"$safe\")", null)
        }
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        currentUri = null
    }

    override fun onDestroy() {
        decodeToken.incrementAndGet()
        decodeExecutor.shutdownNow()
        releasePlayer()
        webView.removeJavascriptInterface("AudioBridge")
        webView.destroy()
        super.onDestroy()
    }

    inner class AudioBridge {
        @JavascriptInterface fun importAudio() { runOnUiThread { audioPicker.launch(arrayOf("audio/*")) } }
        @JavascriptInterface fun play() { runOnUiThread { player?.let { if (!it.isPlaying) it.start() } } }
        @JavascriptInterface fun pause() { runOnUiThread { player?.let { if (it.isPlaying) it.pause() } } }
        @JavascriptInterface fun stop() {
            runOnUiThread {
                player?.let { if (it.isPlaying) it.pause(); it.seekTo(0) }
                publishPosition()
            }
        }
        @JavascriptInterface fun seekTo(positionMs: Int) {
            runOnUiThread { player?.seekTo(positionMs.coerceAtLeast(0)); publishPosition() }
        }
        @JavascriptInterface fun setVolume(value: Float) {
            val volume = value.coerceIn(0f, 1f)
            runOnUiThread { player?.setVolume(volume, volume) }
        }
        @JavascriptInterface fun getPosition(): Int = player?.currentPosition ?: 0
        @JavascriptInterface fun getDuration(): Int = player?.duration ?: 0
        @JavascriptInterface fun isPlaying(): Boolean = player?.isPlaying ?: false
        @JavascriptInterface fun hasAudio(): Boolean = currentUri != null
        private fun publishPosition() {
            webView.evaluateJavascript("window.onNativePosition && window.onNativePosition(${player?.currentPosition ?: 0})", null)
        }
    }
}
