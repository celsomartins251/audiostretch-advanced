package br.com.celsomartins.audiostretch

/** Native Oboe boundary. PCM is Float32 stereo at the stream's actual sample rate. */
object AudioNative {
    init { System.loadLibrary("audiostretch_native") }
    external fun nativeOpen(): Boolean
    external fun nativeClose()
    external fun nativePause(): Boolean
    external fun nativeResume(): Boolean
    external fun nativeSampleRate(): Int
    /** Linear output gain in the range 0.0..1.0, applied in the audio callback. */
    external fun nativeSetGain(gain: Float)
    /** Clear only while paused or closed; stop the PCM producer before calling. */
    external fun nativeClearBuffer(): Boolean
    external fun nativeWritePcm(samples: FloatArray, length: Int): Int
    external fun nativeQueuedSamples(): Long
    external fun nativeStatus(): String
}
