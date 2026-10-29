package com.fxsound.audio.engine

import com.fxsound.audio.model.EQ_FREQUENCIES
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.FxSoundUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The console and the renderer used to disagree: the faders showed the user's offset while
 * the DSP applied offset + Clarity. A "flat" console could therefore ship +5.9 dB at 16 kHz
 * and a -14 dB fader only cut 8.1 dB. These tests pin the contract that the number on a
 * fader is the number the renderer applies.
 */
class EqResponseTest {

    private fun clarityOnly(clarity: Float) = mapOf(FxSoundEffectId.FIDELITY to clarity)

    @Test
    fun effectiveGainsMatchTheMeasuredClarityCurve() {
        val state = FxSoundUiState(
            effects = clarityOnly(5.0f),
            eqGains = List(10) { 0.0f }
        )
        val expected = FxSoundResponseTables.fidelityBandGainsDb(5.0f)

        val effective = EqResponse.effectiveGains(state)
        assertEquals(EQ_FREQUENCIES.size, effective.size)
        for (i in effective.indices) {
            assertEquals("Band ${EQ_FREQUENCIES[i]} Hz", expected[i], effective[i], 0.001f)
            assertTrue("Effective gain must stay in the rendered range", effective[i] in -14f..14f)
        }
    }

    @Test
    fun aMinusFourteenFaderIsAMinusFourteenDecibelCutEvenWithClarityOn() {
        val effects = clarityOnly(5.0f)
        for (band in 0 until 10) {
            val offset = EqResponse.offsetFor(-14.0f, band, effects)
            val effective = EqResponse.effectiveGains(List(10) { if (it == band) offset else 0f }, effects)
            assertEquals(
                "Band $band must be cut by the full 14 dB, not the offset the console used to show",
                -14.0f,
                effective[band],
                0.001f
            )
        }
    }

    @Test
    fun everyFaderPositionRoundTrips() {
        for (clarity in listOf(0.0f, 2.0f, 5.0f, 8.0f, 10.0f)) {
            val effects = clarityOnly(clarity)
            for (band in 0 until 10) {
                for (target in listOf(-14.0f, -7.0f, 0.0f, 7.0f, 14.0f)) {
                    val offset = EqResponse.offsetFor(target, band, effects)
                    val effective = EqResponse.effectiveGains(
                        List(10) { if (it == band) offset else 0f },
                        effects
                    )[band]
                    assertEquals(
                        "clarity=$clarity band=$band target=$target",
                        target,
                        effective,
                        0.001f
                    )
                }
            }
        }
    }

    @Test
    fun defaultBalancedIsGentlerThanTheOldPreset() {
        // Version 1 shipped clarity 5.0, which measured +5.91 dB at 16 kHz. Balanced is now a
        // preset rather than the first-run state, but it must still be a gentle presence lift,
        // not a treble booster.
        val balanced = FxSoundPreset.BALANCED
        assertTrue("Balanced clarity must be a gentle lift", balanced.clarity in 1.0f..3.0f)

        val air = FxSoundResponseTables.fidelityBandGainsDb(balanced.clarity).last()
        assertTrue("Balanced must not boost 16 kHz by more than 3 dB (was $air)", air <= 3.0f)
    }

    @Test
    fun aFreshInstallStartsNeutral() {
        // First launch must not colour the user's audio: every effect is 0, every fader is
        // 0 dB, and no preset is claimed until the user picks one.
        val state = FxSoundUiState()

        assertEquals(FxSoundPreset.NEUTRAL.id, state.currentPresetId)
        for (effect in FxSoundEffectId.entries) {
            assertEquals(
                "${effect.displayName} must start at 0",
                0.0f,
                state.effects[effect] ?: -1f,
                0.001f
            )
        }
        assertEquals(List(10) { 0.0f }, state.eqGains)
        assertTrue("A neutral state must render a flat curve", EqResponse.effectiveGains(state).all { abs(it) < 0.001f })
    }
}
