package com.fxsound.audio.engine

import com.fxsound.audio.model.EQ_FREQUENCIES
import kotlin.math.log2

/**
 * Maps the app's 10 ISO sliders onto a platform `android.media.audiofx.Equalizer`, whose band
 * count and centre frequencies belong to the device (5 bands at 60/230/910/3600/14000 Hz is
 * common on OEM builds) and change nothing about the 10-band console.
 *
 * Interpolation is octave (log-frequency) based, not linear in hertz, because EQ bands are
 * spaced by ratio: on a linear scale every band above 1 kHz would be weighted towards its
 * upper neighbour, which silently attenuates the high sliders the user is dragging.
 *
 * Docs: DECISIONS.md, "Renderer honesty".
 */
object PlatformEqMapper {

    /** Gain in dB the console would show at [freqHz], interpolated between the ISO bands. */
    fun gainAt(freqHz: Int, eqGains: List<Float>): Float {
        if (eqGains.isEmpty()) return 0.0f
        val freqs = EQ_FREQUENCIES
        val first = freqs.first()
        val last = freqs.last()
        if (freqHz <= first) return eqGains.getOrElse(0) { 0.0f }
        if (freqHz >= last) return eqGains.getOrElse(eqGains.lastIndex) { 0.0f }

        for (i in 0 until freqs.size - 1) {
            val f1 = freqs[i]
            val f2 = freqs[i + 1]
            if (freqHz >= f1 && freqHz <= f2) {
                val octaves = log2(freqHz.toFloat() / f1)
                val spanOctaves = log2(f2.toFloat() / f1)
                val t = if (spanOctaves <= 0f) 0f else (octaves / spanOctaves).coerceIn(0f, 1f)
                val g1 = eqGains.getOrElse(i) { 0.0f }
                val g2 = eqGains.getOrElse(i + 1) { 0.0f }
                return g1 + t * (g2 - g1)
            }
        }
        return 0.0f
    }

    /**
     * Converts dB to the platform's millibel scale, clamped to the range the effect reports.
     * The platform rejects values outside [minMb, maxMb] with an exception, and many devices
     * only offer +-1500 mB (+-15 dB) around a 0 dB console.
     */
    fun millibels(totalDb: Float, minMb: Short, maxMb: Short): Short =
        (totalDb * 100.0f).toInt().coerceIn(minMb.toInt(), maxMb.toInt()).toShort()
}
