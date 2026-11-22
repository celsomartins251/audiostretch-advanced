package br.com.celsomartins.audiostretch

/**
 * Native Oboe boundary. PCM input is interleaved Float32 stereo at the
 * output stream's sample rate. The existing MediaPlayer path remains active
 * until the decoder/resampler pipeline has been validated on-device.
 */
object AudioNative {
    init {
        System.loadLibrary("audiostretch_native")
    }

    external fun nativeOpen(): Boolean
    external fun nativeClose()

    /** Writes interleaved stereo Float32 samples; returns the number accepted. */
    external fun nativeWritePcm(samples: FloatArray, length: Int): Int

    /** Number of queued Float32 samples (two samples per stereo frame). */
    external fun nativeQueuedSamples(): Long

    external fun nativeStatus(): String
}
