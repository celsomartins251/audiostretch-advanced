package br.com.celsomartins.audiostretch

/**
 * Native Oboe boundary. Not connected to the app transport yet.
 * The existing MediaPlayer path remains the active playback implementation.
 */
object AudioNative {
    init {
        System.loadLibrary("audiostretch_native")
    }

    external fun nativeOpen(): Boolean
    external fun nativeClose()
    external fun nativeStatus(): String
}
