package br.com.celsomartins.audiostretch

import android.content.Context
import android.net.Uri
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Experimental Oboe scrubbing transport.
 *
 * All decoding, file reads and PCM rendering run on this worker. The Oboe
 * callback only consumes the native ring buffer; it never accesses Kotlin,
 * the PCM cache, files, or gesture state.
 */
internal class ScrubPlaybackController(
    context: Context,
    private val onState: (String) -> Unit
) : AutoCloseable {
    private data class Command(
        val positionMs: Double,
        val speed: Double,
        val hold: Boolean,
        val version: Long
    )

    private val appContext = context.applicationContext
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private val running = AtomicBoolean(false)
    private val version = AtomicLong(0L)
    private val command = AtomicReference(Command(0.0, 0.0, true, 0L))

    fun start(uri: Uri, initialPositionMs: Double): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        update(initialPositionMs, 0.0, true)
        worker.execute {
            var opened = false
            var cache: PcmFileCache? = null
            try {
                onState("Preparando áudio para o scrubbing…")
                if (!AudioNative.nativeOpen()) {
                    error("Não foi possível abrir a saída Oboe para o scrubbing.")
                }
                opened = true
                val rate = AudioNative.nativeSampleRate()
                check(rate in 8_000..192_000) { "Taxa de saída Oboe inválida." }

                cache = PcmFileCache.build(
                    context = appContext,
                    uri = uri,
                    outputSampleRate = rate,
                    shouldCancel = { cancelled.get() }
                )
                check(!cancelled.get()) { "Scrubbing cancelado." }
                val renderer = ScrubPcmRenderer(cache)
                onState("Scrubbing pronto · $rate Hz")

                var positionFrame = 0.0
                var speed = 0.0
                var hold = true
                var holdPhase = 0L
                var appliedVersion = -1L

                while (!cancelled.get()) {
                    val latest = command.get()
                    if (latest.version != appliedVersion) {
                        positionFrame = (latest.positionMs.coerceAtLeast(0.0) * rate / 1_000.0)
                            .coerceIn(0.0, cache.totalFrames.toDouble())
                        speed = latest.speed.coerceIn(-4.0, 4.0)
                        hold = latest.hold
                        holdPhase = 0L
                        appliedVersion = latest.version
                    }

                    // Keep latency bounded; a short queue is important because
                    // the finger can reverse direction at any time.
                    if (AudioNative.nativeQueuedSamples() >= MAX_QUEUED_SAMPLES) {
                        Thread.sleep(2)
                        continue
                    }

                    val block = renderer.render(
                        positionFrame = positionFrame,
                        speed = speed,
                        outputFrames = BLOCK_FRAMES,
                        holdStill = hold,
                        holdPhaseFrame = holdPhase
                    )
                    var offset = 0
                    while (offset < block.samples.size && !cancelled.get()) {
                        val count = AudioNative.nativeWritePcm(
                            if (offset == 0 && block.samples.size == block.samples.size) {
                                block.samples
                            } else {
                                block.samples.copyOfRange(offset, block.samples.size)
                            },
                            block.samples.size - offset
                        )
                        if (count > 0) offset += count else Thread.sleep(2)
                    }
                    positionFrame = block.nextPositionFrame
                    holdPhase = block.nextHoldPhaseFrame
                }
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                onState("Scrubbing interrompido.")
            } catch (error: Exception) {
                if (!cancelled.get()) onState(error.message ?: "Falha no scrubbing.")
            } finally {
                if (opened) AudioNative.nativeClose()
                cache?.close()
                running.set(false)
                if (cancelled.get()) onState("Scrubbing finalizado.")
            }
        }
        return true
    }

    /** Called by the UI thread as the finger moves or pauses on the waveform. */
    fun update(positionMs: Double, speed: Double, hold: Boolean) {
        if (!positionMs.isFinite() || !speed.isFinite()) return
        val nextVersion = version.incrementAndGet()
        command.set(
            Command(
                positionMs = positionMs.coerceAtLeast(0.0),
                speed = speed.coerceIn(-4.0, 4.0),
                hold = hold,
                version = nextVersion
            )
        )
    }

    fun stop() {
        cancelled.set(true)
    }

    fun isRunning(): Boolean = running.get()

    override fun close() {
        cancelled.set(true)
        worker.shutdownNow()
    }

    companion object {
        private const val BLOCK_FRAMES = 256
        private const val MAX_QUEUED_SAMPLES = 2_048L
    }
}
