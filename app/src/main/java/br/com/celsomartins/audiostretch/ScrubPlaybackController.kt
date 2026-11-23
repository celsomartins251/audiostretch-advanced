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
    private val onState: (String) -> Unit,
    private val waitForPrevious: () -> Unit = {}
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
    private val cacheGeneration = AtomicLong(0L)
    private val command = AtomicReference(Command(0.0, 0.0, true, 0L))
    private val cacheLock = Any()
    private var cachedUri: Uri? = null
    private var cachedPcm: PcmFileCache? = null
    private var invalidateWhenStopped = false

    /**
     * Decode the random-access cache after import, before the first gesture.
     * This runs on the same worker as playback, so a drag that starts during
     * preparation is queued and begins as soon as the cache is ready.
     */
    fun prepare(uri: Uri) {
        val generation = cacheGeneration.get()
        worker.execute {
            var opened = false
            try {
                onState("Preparando cache de áudio para o scrubbing…")
                waitForPrevious()
                if (generation != cacheGeneration.get()) return@execute
                if (!AudioNative.nativeOpen()) error("Não foi possível preparar o motor Oboe.")
                opened = true
                check(AudioNative.nativePause()) { "Não foi possível pausar Oboe durante a preparação." }
                val rate = AudioNative.nativeSampleRate()
                check(rate in 8_000..192_000) { "Taxa de saída Oboe inválida." }

                val reusable = synchronized(cacheLock) {
                    cachedPcm?.takeIf { cachedUri == uri && it.sampleRate == rate }
                }
                if (reusable == null) {
                    val built = PcmFileCache.build(
                        context = appContext,
                        uri = uri,
                        outputSampleRate = rate,
                        shouldCancel = { generation != cacheGeneration.get() }
                    )
                    synchronized(cacheLock) {
                        if (generation == cacheGeneration.get()) {
                            val previous = cachedPcm
                            cachedPcm = built
                            cachedUri = uri
                            if (previous !== built) previous?.close()
                        } else {
                            built.close()
                        }
                    }
                }
                if (generation == cacheGeneration.get()) {
                    onState("Cache de scrubbing preparado · $rate Hz")
                }
            } catch (error: Exception) {
                if (generation == cacheGeneration.get()) {
                    onState(error.message ?: "Não foi possível preparar o cache de scrubbing.")
                }
            } finally {
                if (opened) AudioNative.nativeClose()
            }
        }
    }

    fun start(uri: Uri, initialPositionMs: Double): Boolean {
        if (!running.compareAndSet(false, true)) return false
        cancelled.set(false)
        update(initialPositionMs, 0.0, true)
        worker.execute {
            var opened = false
            try {
                onState("Preparando áudio para o scrubbing…")
                waitForPrevious()
                if (cancelled.get()) return@execute
                if (!AudioNative.nativeOpen()) {
                    error("Não foi possível abrir a saída Oboe para o scrubbing.")
                }
                opened = true
                check(AudioNative.nativePause()) {
                    "Não foi possível pausar a saída Oboe durante a preparação do áudio."
                }
                val rate = AudioNative.nativeSampleRate()
                check(rate in 8_000..192_000) { "Taxa de saída Oboe inválida." }

                val reusableCache = synchronized(cacheLock) {
                    cachedPcm?.takeIf { cachedUri == uri && it.sampleRate == rate }
                }
                val builtCache = reusableCache ?: PcmFileCache.build(
                    context = appContext,
                    uri = uri,
                    outputSampleRate = rate,
                    shouldCancel = { cancelled.get() }
                ).also { newCache ->
                    synchronized(cacheLock) {
                        val previous = cachedPcm
                        cachedPcm = newCache
                        cachedUri = uri
                        if (previous !== newCache) previous?.close()
                    }
                }
                check(!cancelled.get()) { "Scrubbing cancelado." }
                val renderer = ScrubPcmRenderer(builtCache)
                check(AudioNative.nativeClearBuffer()) {
                    "Não foi possível limpar o buffer Oboe antes do scrubbing."
                }
                check(AudioNative.nativeResume()) {
                    "Não foi possível iniciar a saída Oboe para o scrubbing."
                }
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
                            .coerceIn(0.0, builtCache.totalFrames.toDouble())
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
                        val chunk = if (offset == 0) block.samples
                            else block.samples.copyOfRange(offset, block.samples.size)
                        val count = AudioNative.nativeWritePcm(chunk, chunk.size)
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
                synchronized(cacheLock) {
                    if (invalidateWhenStopped) {
                        cachedPcm?.close()
                        cachedPcm = null
                        cachedUri = null
                        invalidateWhenStopped = false
                    }
                    running.set(false)
                }
                if (cancelled.get()) onState("Scrubbing finalizado · ${AudioNative.nativeStatus()}")
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

    /** Drops the previous file's PCM cache after its active worker has stopped. */
    fun invalidateCache() {
        cacheGeneration.incrementAndGet()
        synchronized(cacheLock) {
            if (running.get()) {
                invalidateWhenStopped = true
            } else {
                cachedPcm?.close()
                cachedPcm = null
                cachedUri = null
                invalidateWhenStopped = false
            }
        }
    }

    override fun close() {
        cancelled.set(true)
        cacheGeneration.incrementAndGet()
        worker.shutdownNow()
        synchronized(cacheLock) {
            if (running.get()) {
                invalidateWhenStopped = true
            } else {
                cachedPcm?.close()
                cachedPcm = null
                cachedUri = null
            }
        }
    }

    companion object {
        private const val BLOCK_FRAMES = 256
        private const val MAX_QUEUED_SAMPLES = 2_048L
    }
}
