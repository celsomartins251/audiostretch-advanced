package br.com.celsomartins.audiostretch

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.floor
import kotlin.math.max

/**
 * Decodes an audio URI to interleaved Float32 stereo at the requested output
 * rate. This class is deliberately independent from MainActivity/MediaPlayer.
 *
 * For files with more than two channels, channels are averaged to mono and
 * duplicated to L/R because a reliable channel-layout map is not guaranteed
 * by every MediaCodec output format.
 */
internal object PcmDecoder {
    data class Result(
        val inputSampleRate: Int,
        val outputSampleRate: Int,
        val inputChannels: Int,
        val outputFrames: Long
    )

    fun decode(
        context: Context,
        uri: Uri,
        outputSampleRate: Int,
        shouldCancel: () -> Boolean,
        onPcm: (FloatArray, Int) -> Boolean
    ): Result {
        require(outputSampleRate in 8_000..192_000) { "Taxa de saída inválida." }

        var extractor: MediaExtractor? = null
        var decoder: MediaCodec? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)

            var track = -1
            var inputFormat: MediaFormat? = null
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = index
                    inputFormat = format
                    break
                }
            }
            require(track >= 0 && inputFormat != null) {
                "Não foi encontrada uma faixa de áudio compatível."
            }

            extractor.selectTrack(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: error("Formato de áudio desconhecido.")
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val initialRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val initialChannels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var inputDone = false
            var outputDone = false
            var sourceRate = initialRate
            var sourceChannels = initialChannels
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            var outputFrames = 0L
            var sinkActive = true
            var resampler: LinearStereoResampler? = null
            val info = MediaCodec.BufferInfo()

            while (!outputDone && sinkActive && !shouldCancel()) {
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inputIndex)
                            ?: error("Buffer de entrada indisponível.")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(
                                inputIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            )
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(
                                inputIndex, 0, size, extractor.sampleTime, 0
                            )
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = decoder.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = decoder.outputFormat
                        if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            sourceRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            sourceChannels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                        require(sourceRate > 0 && sourceChannels > 0) {
                            "Formato PCM de saída inválido."
                        }
                        resampler = LinearStereoResampler(sourceRate, outputSampleRate)
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        try {
                            val output = decoder.getOutputBuffer(outputIndex)
                            if (output != null && info.size > 0) {
                                if (resampler == null) {
                                    resampler = LinearStereoResampler(sourceRate, outputSampleRate)
                                }
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                output.order(ByteOrder.nativeOrder())

                                val bytesPerSample = when (encoding) {
                                    AudioFormat.ENCODING_PCM_FLOAT,
                                    AudioFormat.ENCODING_PCM_32BIT -> 4
                                    AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
                                    AudioFormat.ENCODING_PCM_8BIT -> 1
                                    else -> 2
                                }
                                val frameBytes = bytesPerSample * sourceChannels
                                val frameCount = info.size / frameBytes
                                val stereo = FloatArray(frameCount * 2)

                                for (frame in 0 until frameCount) {
                                    var left = 0f
                                    var right = 0f
                                    var sum = 0f
                                    for (channel in 0 until sourceChannels) {
                                        val sample = readSample(output, encoding)
                                        sum += sample
                                        if (channel == 0) left = sample
                                        if (channel == 1) right = sample
                                    }
                                    when {
                                        sourceChannels == 1 -> {
                                            stereo[frame * 2] = left
                                            stereo[frame * 2 + 1] = left
                                        }
                                        sourceChannels == 2 -> {
                                            stereo[frame * 2] = left
                                            stereo[frame * 2 + 1] = right
                                        }
                                        else -> {
                                            val mono = sum / sourceChannels
                                            stereo[frame * 2] = mono
                                            stereo[frame * 2 + 1] = mono
                                        }
                                    }
                                }

                                val converted = resampler!!.process(stereo)
                                var offset = 0
                                while (offset < converted.size && !shouldCancel()) {
                                    val count = minOf(4096, converted.size - offset)
                                    val chunk = converted.copyOfRange(offset, offset + count)
                                    if (!onPcm(chunk, count)) {
                                        sinkActive = false
                                        break
                                    }
                                    outputFrames += count / 2
                                    offset += count
                                }
                            }
                            outputDone = (info.flags and
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        } finally {
                            decoder.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            }

            return Result(initialRate, outputSampleRate, initialChannels, outputFrames)
        } finally {
            try { decoder?.stop() } catch (_: Exception) { }
            try { decoder?.release() } catch (_: Exception) { }
            try { extractor?.release() } catch (_: Exception) { }
        }
    }

    private fun readSample(buffer: java.nio.ByteBuffer, encoding: Int): Float {
        val value = when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> buffer.float
            AudioFormat.ENCODING_PCM_8BIT ->
                ((buffer.get().toInt() and 0xff) - 128) / 128f
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val b0 = buffer.get().toInt() and 0xff
                val b1 = buffer.get().toInt() and 0xff
                val b2 = buffer.get().toInt()
                val signed = (b2 shl 16) or (b1 shl 8) or b0
                signed / 8_388_608f
            }
            AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2_147_483_648f
            else -> buffer.short / 32768f
        }
        return value.coerceIn(-1f, 1f)
    }

    /**
     * Streaming linear resampler. Retains the previous source frame so
     * interpolation remains continuous across MediaCodec output buffers.
     */
    private class LinearStereoResampler(
        private val inputRate: Int,
        private val outputRate: Int
    ) {
        private val step = inputRate.toDouble() / outputRate.toDouble()
        private var phase = 0.0
        private var previousLeft: Float? = null
        private var previousRight = 0f

        fun process(input: FloatArray): FloatArray {
            val frames = input.size / 2
            if (frames == 0) return FloatArray(0)

            val hasPrevious = previousLeft != null
            val totalFrames = frames + if (hasPrevious) 1 else 0
            fun sample(frame: Int, channel: Int): Float {
                if (hasPrevious && frame == 0) {
                    return if (channel == 0) previousLeft!! else previousRight
                }
                val sourceFrame = frame - if (hasPrevious) 1 else 0
                return input[sourceFrame * 2 + channel]
            }

            val output = ArrayList<Float>(max(8, (frames * outputRate / inputRate) * 2 + 4))
            val lastInterpolable = totalFrames - 1
            while (phase < lastInterpolable) {
                val index = floor(phase).toInt()
                val fraction = (phase - index).toFloat()
                val next = minOf(index + 1, totalFrames - 1)
                val left = sample(index, 0) +
                    (sample(next, 0) - sample(index, 0)) * fraction
                val right = sample(index, 1) +
                    (sample(next, 1) - sample(index, 1)) * fraction
                output.add(left)
                output.add(right)
                phase += step
            }

            phase -= lastInterpolable
            previousLeft = input[(frames - 1) * 2]
            previousRight = input[(frames - 1) * 2 + 1]
            return FloatArray(output.size) { output[it] }
        }
    }
}
