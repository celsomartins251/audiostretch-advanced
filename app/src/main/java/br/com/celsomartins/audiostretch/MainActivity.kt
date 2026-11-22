package br.com.celsomartins.audiostretch

import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.content.ActivityNotFoundException
import androidx.core.content.FileProvider
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
import org.json.JSONObject
import java.io.File
import java.io.BufferedInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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
    private val updateExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var pendingUpdateFile: File? = null
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

    private fun updateMessage(function: String, message: String) {
        val safe = message.replace("\\", " ").replace("\"", " ").replace("\n", " ")
        runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.$function && window.$function(\"$safe\")", null) }
    }

    private fun checkForUpdates() {
        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/celsomartins251/audiostretch-advanced/releases?per_page=20").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"; connectTimeout = 15000; readTimeout = 20000
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("User-Agent", "AudioStretch-Advanced-Android")
                }
                if (connection.responseCode !in 200..299) error("GitHub respondeu HTTP ${connection.responseCode}.")
                val releases = JSONArray(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
                var release: JSONObject? = null
                for (i in 0 until releases.length()) { val item = releases.getJSONObject(i); if (item.optBoolean("prerelease") && !item.optBoolean("draft")) { release = item; break } }
                val latest = release ?: run { updateMessage("onUpdateStatus", "Nenhuma versão beta publicada ainda."); return@execute }
                val tag = latest.optString("tag_name")
                val code = Regex("beta-(\\d+)", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)?.toIntOrNull() ?: error("Tag de versão inválida.")
                val packageInfo = packageManager.getPackageInfo(packageName, 0)
                @Suppress("DEPRECATION") val installed = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode.toInt() else packageInfo.versionCode
                if (code <= installed) { updateMessage("onUpdateStatus", "Seu aplicativo já está na versão beta mais recente."); return@execute }
                val assets = latest.getJSONArray("assets")
                var apk: JSONObject? = null
                for (i in 0 until assets.length()) { val item = assets.getJSONObject(i); if (item.optString("name") == "audiostretch-advanced-beta.apk") { apk = item; break } }
                val asset = apk ?: error("APK não encontrado nesta publicação.")
                val url = asset.optString("browser_download_url")
                if (!url.startsWith("https://github.com/")) throw SecurityException("Endereço de atualização não permitido.")
                val digest = asset.optString("digest").takeIf { it.startsWith("sha256:") }?.substringAfter("sha256:")
                val info = JSONObject().put("tag", tag).put("title", latest.optString("name", tag)).put("notes", latest.optString("body", "")).put("url", url).put("digest", digest ?: JSONObject.NULL)
                runOnUiThread { webView.evaluateJavascript("window.onUpdateAvailable && window.onUpdateAvailable($info)", null) }
            } catch (e: Exception) { updateMessage("onUpdateError", e.message ?: "Falha ao verificar atualizações.") }
            finally { connection?.disconnect() }
        }
    }

    private fun downloadUpdate(url: String, expectedDigest: String?) {
        if (!url.startsWith("https://github.com/")) { updateMessage("onUpdateError", "Endereço de download não permitido."); return }
        updateExecutor.execute {
            var connection: HttpURLConnection? = null
            try {
                val dir = File(cacheDir, "updates").apply { mkdirs() }
                val temp = File(dir, "update.apk.part"); val apk = File(dir, "update.apk")
                connection = (URL(url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 20000; readTimeout = 30000; setRequestProperty("User-Agent", "AudioStretch-Advanced-Android") }
                if (connection.responseCode !in 200..299) error("Falha no download: HTTP ${connection.responseCode}.")
                val total = connection.contentLengthLong; var received = 0L
                val sha = MessageDigest.getInstance("SHA-256")
                BufferedInputStream(connection.inputStream).use { input -> FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer, 0, count); sha.update(buffer, 0, count); received += count
                        val percent = if (total > 0) (received * 100 / total).toInt() else -1
                        runOnUiThread { webView.evaluateJavascript("window.onUpdateProgress && window.onUpdateProgress($percent)", null) }
                    }; output.fd.sync()
                } }
                if (received < 100000L) error("O APK baixado parece incompleto.")
                if (!expectedDigest.isNullOrBlank()) { val actual = sha.digest().joinToString("") { "%02x".format(it) }; if (!actual.equals(expectedDigest, true)) throw SecurityException("A verificação SHA-256 falhou.") }
                apk.delete(); if (!temp.renameTo(apk)) error("Não foi possível preparar o instalador.")
                pendingUpdateFile = apk; updateMessage("onUpdateDownloaded", "Download concluído. Preparando instalação…")
                runOnUiThread { installPendingUpdate() }
            } catch (e: Exception) { updateMessage("onUpdateError", e.message ?: "Falha ao baixar a atualização.") }
            finally { connection?.disconnect() }
        }
    }

    private fun installPendingUpdate() {
        val apk = pendingUpdateFile ?: return
        if (!apk.exists()) { pendingUpdateFile = null; return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try { startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))) }
            catch (_: ActivityNotFoundException) { updateMessage("onUpdateError", "Ative a permissão para instalar aplicativos desta origem nas configurações do Android.") }
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, "application/vnd.android.package-archive"); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK) }
            startActivity(intent); pendingUpdateFile = null
        } catch (e: Exception) { updateMessage("onUpdateError", e.message ?: "Não foi possível abrir o instalador.") }
    }

    override fun onResume() { super.onResume(); if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) installPendingUpdate() }
    override fun onDestroy() {
        decodeToken.incrementAndGet()
        decodeExecutor.shutdownNow()
        updateExecutor.shutdownNow()
        releasePlayer()
        webView.removeJavascriptInterface("AudioBridge")
        webView.destroy()
        super.onDestroy()
    }

    inner class AudioBridge {
        @JavascriptInterface fun importAudio() { runOnUiThread { audioPicker.launch(arrayOf("audio/*")) } }
        @JavascriptInterface fun checkForUpdates() { this@MainActivity.checkForUpdates() }
        @JavascriptInterface fun downloadUpdate(url: String, digest: String) { this@MainActivity.downloadUpdate(url, digest.takeIf { it.isNotBlank() && it != "null" }) }
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
