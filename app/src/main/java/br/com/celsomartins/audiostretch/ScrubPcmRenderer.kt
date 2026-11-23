package br.com.celsomartins.audiostretch

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Worker-thread PCM renderer for waveform scrubbing.
 *
 * It reads bounded positional windows from PcmFileCache and produces
 * interleaved Float32 stereo. Never call from Oboe's real-time callback.
 */
internal class ScrubPcmRenderer(
    private val cache: PcmFileCache,
    private val holdLoopMs: Int = 150,
    private val crossfadeMs: Int = 12
) {
    data class Block(
        val samples: FloatArray,
        val nextPositionFrame: Double
    )

    init {
        require(holdLoopMs in 60..500) { "Duração do loop de sustentação inválida." }
        require(crossfadeMs in 2..40 && crossfadeMs * 2 < holdLoopMs) {
            "Crossfade incompatível com o loop."
        }
    }

    /**
     * @param positionFrame absolute source position, fractional frames allowed
     * @param speed source frames advanced per output frame; negative is reverse
     * @param outputFrames number of stereo frames to render
     * @param holdStill repeats a short region centered around the current point
     */
    fun render(
        positionFrame: Double,
        speed: Double,
        outputFrames: Int,
        holdStill: Boolean = false
    ): Block {
        require(outputFrames in 1..MAX_OUTPUT_FRAMES) { "Bloco de saída muito grande." }
        require(speed.isFinite()) { "Velocidade inválida." }

        val safePosition = positionFrame.coerceIn(0.0, max(0.0, cache.totalFrames - 1.0))
        return if (holdStill || kotlin.math.abs(speed) < MIN_SPEED) {
            renderHold(safePosition, outputFrames)
        } else {
            renderMoving(safePosition, speed.coerceIn(-MAX_SPEED, MAX_SPEED), outputFrames)
        }
    }

    private fun renderMoving(position: Double, speed: Double, outputFrames: Int): Block {
        val lastPosition = position + speed * (outputFrames - 1)
        val low = floor(min(position, lastPosition)).toLong().coerceAtLeast(0L)
        val high = floor(max(position, lastPosition)).toLong().plus(2L)
            .coerceAtMost(cache.totalFrames)
        val source = cache.readFrames(low, (high - low).coerceAtLeast(0L).toInt())
        val result = FloatArray(outputFrames * CHANNELS)

        for (frame in 0 until outputFrames) {
            val absolute = (position + speed * frame).coerceIn(0.0, max(0.0, cache.totalFrames - 1.0))
            interpolate(source, absolute - low, result, frame)
        }

        val next = (position + speed * outputFrames)
            .coerceIn(0.0, cache.totalFrames.toDouble())
        return Block(result, next)
    }

    private fun renderHold(position: Double, outputFrames: Int): Block {
        if (cache.totalFrames == 0L) return Block(FloatArray(outputFrames * CHANNELS), 0.0)

        val loopFrames = min(
            cache.totalFrames,
            max(2L, cache.sampleRate.toLong() * holdLoopMs / 1_000L)
        ).toInt()
        val crossfadeFrames = min(
            loopFrames / 4,
            max(1, cache.sampleRate * crossfadeMs / 1_000)
        )
        val center = position.toLong()
        val start = (center - loopFrames / 2L)
            .coerceIn(0L, (cache.totalFrames - loopFrames).coerceAtLeast(0L))
        val source = cache.readFrames(start, loopFrames)
        val result = FloatArray(outputFrames * CHANNELS)

        for (frame in 0 until outputFrames) {
            val loopPosition = (frame.toLong() % loopFrames).toInt().toDouble()
            interpolate(source, loopPosition, result, frame)

            // Blend the tail into the beginning so the hold loop has no hard seam.
            if (loopPosition >= loopFrames - crossfadeFrames) {
                val fade = ((loopPosition - (loopFrames - crossfadeFrames)) /
                    crossfadeFrames).toFloat().coerceIn(0f, 1f)
                val headPosition = loopPosition - (loopFrames - crossfadeFrames)
                val base = frame * CHANNELS
                for (channel in 0 until CHANNELS) {
                    val head = sampleAt(source, headPosition, channel)
                    result[base + channel] =
                        result[base + channel] * (1f - fade) + head * fade
                }
            }
        }
        return Block(result, position)
    }

    private fun interpolate(source: FloatArray, framePosition: Double, destination: FloatArray, outputFrame: Int) {
        val out = outputFrame * CHANNELS
        for (channel in 0 until CHANNELS) {
            destination[out + channel] = sampleAt(source, framePosition, channel)
        }
    }

    private fun sampleAt(source: FloatArray, framePosition: Double, channel: Int): Float {
        val sourceFrames = source.size / CHANNELS
        if (sourceFrames == 0) return 0f
        val bounded = framePosition.coerceIn(0.0, (sourceFrames - 1).toDouble())
        val first = floor(bounded).toInt()
        val second = min(first + 1, sourceFrames - 1)
        val fraction = (bounded - first).toFloat()
        val a = source[first * CHANNELS + channel]
        val b = source[second * CHANNELS + channel]
        return a + (b - a) * fraction
    }

    companion object {
        private const val CHANNELS = 2
        private const val MAX_OUTPUT_FRAMES = 2_048
        private const val MAX_SPEED = 4.0
        private const val MIN_SPEED = 0.025
    }
}
