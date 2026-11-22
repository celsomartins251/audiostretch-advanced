package br.com.celsomartins.audiostretch

/**
 * Stable boundary reserved for the native DSP engine.
 * The current playback path is Android MediaPlayer; native DSP is not yet integrated.
 */
object AudioNative {
    const val STATUS = "DSP engine integration pending"
}
