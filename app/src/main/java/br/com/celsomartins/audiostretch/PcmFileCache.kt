package br.com.celsomartins.audiostretch

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.UUID

/**
 * File-backed, position-addressable PCM cache for the future scrubbing transport.
 *
 * PCM is stored as interleaved Float32 stereo at the requested output rate.
 * Decoding and all disk I/O happen on the caller's worker thread; this class
 * must never be read from Oboe's real-time callback.
 */
internal class PcmFileCache private constructor(
    private val file: File,
    val sampleRate: Int,
    val totalFrames: Long
) : Closeable {
    private val bytesPerFrame = 2L * Float.SIZE_BYTES
    private val input = RandomAccessFile(file, "r")
    private var closed = false

    init {
        require(sampleRate in 8_000..192_000) { "Taxa de amostragem inválida." }
        require(totalFrames >= 0L) { "Duração PCM inválida." }
    }

    val durationMs: Long
        get() = totalFrames * 1_000L / sampleRate

    /**
     * Reads a bounded region by absolute frame position. The returned array
     * contains interleaved L/R samples. Call from a worker, never audio callback.
     */
    @Synchronized
    fun readFrames(startFrame: Long, frameCount: Int): FloatArray {
        check(!closed) { "Cache PCM já foi fechado." }
        require(startFrame >= 0L && frameCount >= 0) { "Intervalo PCM inválido." }
        require(frameCount <= MAX_READ_FRAMES) { "Leitura PCM solicitada é muito grande." }
        if (frameCount == 0 || startFrame >= totalFrames) return FloatArray(0)

        val available = (totalFrames - startFrame).coerceAtMost(frameCount.toLong()).toInt()
        val sampleCount = available * 2
        val byteCount = sampleCount * Float.SIZE_BYTES
        val buffer = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        input.seek(startFrame * bytesPerFrame)
        input.readFully(buffer.array())
        buffer.position(0)
        val floats = FloatArray(sampleCount)
        buffer.asFloatBuffer().get(floats)
        return floats
    }

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
            try { input.close() } catch (_: Exception) { }
            if (file.exists()) file.delete()
        }
    }

    companion object {
        private const val MAX_READ_FRAMES = 96_000

        /**
         * Decodes the selected audio to a temporary, random-access PCM file.
         * Run on a background executor. Cancellation deletes the partial file.
         */
        fun build(
            context: Context,
            uri: Uri,
            outputSampleRate: Int,
            shouldCancel: () -> Boolean
        ): PcmFileCache {
            require(outputSampleRate in 8_000..192_000) { "Taxa de saída inválida." }
            val cacheDirectory = File(context.cacheDir, "scrub_pcm").apply {
                if (!exists() && !mkdirs()) error("Não foi possível criar o cache PCM.")
            }
            val output = File(cacheDirectory, "pcm_${UUID.randomUUID()}.f32")
            var completed = false
            var totalSamples = 0L

            try {
                FileChannel.open(
                    output.toPath(),
                    java.nio.file.StandardOpenOption.CREATE_NEW,
                    java.nio.file.StandardOpenOption.WRITE
                ).use { channel ->
                    PcmDecoder.decode(
                        context = context.applicationContext,
                        uri = uri,
                        outputSampleRate = outputSampleRate,
                        shouldCancel = shouldCancel,
                        onPcm = { samples, length ->
                            if (shouldCancel()) {
                                false
                            } else {
                                require(length in 0..samples.size && length % 2 == 0) {
                                    "Bloco PCM estéreo inválido."
                                }
                                val bytes = ByteBuffer.allocate(length * Float.SIZE_BYTES)
                                    .order(ByteOrder.LITTLE_ENDIAN)
                                bytes.asFloatBuffer().put(samples, 0, length)
                                bytes.position(0)
                                bytes.limit(length * Float.SIZE_BYTES)
                                while (bytes.hasRemaining()) {
                                    if (shouldCancel()) return@decode false
                                    channel.write(bytes)
                                }
                                totalSamples += length
                                true
                            }
                        }
                    )
                    channel.force(false)
                }

                if (shouldCancel()) throw InterruptedException("Preparação do cache PCM cancelada.")
                check(totalSamples % 2L == 0L) { "O cache PCM terminou com um bloco incompleto." }
                val result = PcmFileCache(output, outputSampleRate, totalSamples / 2L)
                completed = true
                return result
            } finally {
                if (!completed && output.exists()) output.delete()
            }
        }
    }
}
