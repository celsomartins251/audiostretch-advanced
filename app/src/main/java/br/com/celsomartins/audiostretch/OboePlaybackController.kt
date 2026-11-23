package br.com.celsomartins.audiostretch

import android.content.Context
import android.net.Uri
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental PCM playback path. MediaPlayer remains the app's primary
 * transport; this controller is not wired into the UI yet.
 *
 * Decoding and producer backpressure run on a worker thread. The Oboe
 * callback remains lock-free and never waits for this producer.
 */
internal class OboePlaybackController(
    context: Context,
    private val onState: (String) -> Unit
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    fun start(uri: Uri): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        worker.execute {
            var opened = false
            try {
                if (cancelled.get()) return@execute
                if (!AudioNative.nativeOpen()) {
                    onState("Não foi possível abrir a saída de áudio Oboe.")
                    return@execute
                }
                opened = true
                val outputRate = AudioNative.nativeSampleRate()
                check(outputRate in 8_000..192_000) {
                    "A saída Oboe informou uma frequência inválida."
                }
                onState("Reprodução experimental Oboe iniciada · $outputRate Hz")

                val result = PcmDecoder.decode(
                    context = appContext,
                    uri = uri,
                    outputSampleRate = outputRate,
                    shouldCancel = { cancelled.get() },
                    onPcm = { samples, length ->
                        var offset = 0
                        while (offset < length && !cancelled.get()) {
                            val chunk = if (offset == 0 && length == samples.size) {
                                samples
                            } else {
                                samples.copyOfRange(offset, length)
                            }
                            val written = AudioNative.nativeWritePcm(chunk, chunk.size)
                            if (written > 0) {
                                offset += written
                            } else {
                                Thread.sleep(4)
                            }
                        }
                        !cancelled.get()
                    }
                )

                while (!cancelled.get() && AudioNative.nativeQueuedSamples() > 0L) {
                    Thread.sleep(8)
                }
                if (!cancelled.get()) {
                    onState(
                        "Teste Oboe concluído · ${result.outputFrames} frames · " +
                            "${result.outputSampleRate} Hz"
                    )
                } else {
                    onState("Teste Oboe interrompido.")
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                onState("Teste Oboe interrompido.")
            } catch (error: Exception) {
                onState(error.message ?: "Falha na reprodução experimental Oboe.")
            } finally {
                if (opened) AudioNative.nativeClose()
                running.set(false)
            }
        }
        return true
    }

    fun stop() {
        cancelled.set(true)
    }

    /** Worker-thread wait used before another controller opens the shared native stream. */
    fun awaitStopped() {
        while (running.get()) Thread.sleep(5)
    }

    fun isRunning(): Boolean = running.get()

    override fun close() {
        cancelled.set(true)
        worker.shutdownNow()
    }
}
