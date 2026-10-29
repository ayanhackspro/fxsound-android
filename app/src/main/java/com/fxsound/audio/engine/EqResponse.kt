package com.fxsound.audio.engine

import com.fxsound.audio.model.EQ_FREQUENCIES
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundUiState

/**
 * The ten faders and the Clarity exciter both write into the same rendered EQ.
 *
 * The renderer sums them: `postEq[i] = faderOffset[i] + clarityCurve[i]` (clamped to
 * +-14 dB). The console used to display only `faderOffset`, so a "flat" console could be
 * shipping +5.9 dB of air at 16 kHz, and dragging the 16 kHz fader to its -14 dB stop only
 * produced a -8.1 dB cut. That is exactly the "equalizer can't lower the treble" report:
 * the number on screen was not the number being applied.
 *
 * This helper makes the console show the *rendered* gain and converts back to the stored
 * offset when the user moves a fader. It is the single place that knows about the
 * offset/e affected split, so the UI and the translator cannot drift apart again.
 *
 * The measured clarity curve lives in [FxSoundResponseTables] (see docs/FX_RESPONSE_MEASUREMENT.md).
 */
object EqResponse {

    /** The console / renderer band range, in dB. */
    const val BAND_MIN_DB = -14.0f
    const val BAND_MAX_DB = 14.0f

    /**
     * Storage range for the user's fader offset. It is wider than [BAND_MIN_DB] because a
     * +14 dB fader position must still be reachable when Clarity scoops that band (it lowers
     * 1 kHz by up to 2.19 dB), and a -14 dB position must be reachable when Clarity boosts
     * that band (it lifts 16 kHz by up to 9.36 dB).
     */
    const val OFFSET_MIN_DB = -24.0f
    const val OFFSET_MAX_DB = 17.0f

    /** The clarity contribution, in dB, for each of the ten ISO bands. */
    fun clarityCurveDb(effects: Map<FxSoundEffectId, Float>): FloatArray {
        val clarity = effects[FxSoundEffectId.FIDELITY] ?: FxSoundEffectId.FIDELITY.defaultValue
        return FxSoundResponseTables.fidelityBandGainsDb(clarity)
    }

    /**
     * The gain the renderer actually applies on each band: the stored offset plus the
     * clarity curve, clamped to the band range. This is what the faders and the response
     * curve must display.
     */
    fun effectiveGains(eqGains: List<Float>, effects: Map<FxSoundEffectId, Float>): List<Float> {
        val clarity = clarityCurveDb(effects)
        return List(EQ_FREQUENCIES.size) { index ->
            (eqGains.getOrElse(index) { 0.0f } + clarity[index]).coerceIn(BAND_MIN_DB, BAND_MAX_DB)
        }
    }

    fun effectiveGains(state: FxSoundUiState): List<Float> =
        effectiveGains(state.eqGains, state.effects)

    /**
     * Converts a fader position (the dB the renderer will apply) into the stored offset that
     * produces it given the current clarity curve.
     */
    fun offsetFor(effectiveDb: Float, bandIndex: Int, effects: Map<FxSoundEffectId, Float>): Float {
        val clarity = clarityCurveDb(effects)
        val target = effectiveDb.coerceIn(BAND_MIN_DB, BAND_MAX_DB)
        return (target - clarity.getOrElse(bandIndex) { 0.0f }).coerceIn(OFFSET_MIN_DB, OFFSET_MAX_DB)
    }

    fun offsetFor(effectiveDb: Float, bandIndex: Int, state: FxSoundUiState): Float =
        offsetFor(effectiveDb, bandIndex, state.effects)
}
