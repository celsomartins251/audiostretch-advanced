package br.com.celsomartins.audiostretch

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.WebViewAssetLoader
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var player: MediaPlayer? = null
    private var currentUri: Uri? = null
    private lateinit var assetLoader: WebViewAssetLoader

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

    private fun notifyError(message: String) {
        val safe = message.replace("\\", " ").replace("\"", " ").replace("\n", " ")
        webView.post {
            webView.evaluateJavascript("window.onNativeAudioError && window.onNativeAudioError(\"${safe}\")", null)
        }
    }

    private fun releasePlayer() {
        try { player?.stop() } catch (_: Exception) { }
        player?.release()
        player = null
        currentUri = null
    }

    override fun onDestroy() {
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
