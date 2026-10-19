package com.fxsound.audio.engine

import com.fxsound.audio.model.FxSoundPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicsProcessingTranslatorTest {

    @Test
    fun testBassBoostTranslation() {
        assertEquals(0.toShort(), DynamicsProcessingTranslator.translateBassBoost(0.0f))
        assertEquals(850.toShort(), DynamicsProcessingTranslator.translateBassBoost(10.0f))
        assertEquals(425.toShort(), DynamicsProcessingTranslator.translateBassBoost(5.0f))
        // Extreme values clamped
        assertEquals(0.toShort(), DynamicsProcessingTranslator.translateBassBoost(-5.0f))
        assertEquals(850.toShort(), DynamicsProcessingTranslator.translateBassBoost(15.0f))
    }

    @Test
    fun testVirtualizerTranslation() {
        assertEquals(0.toShort(), DynamicsProcessingTranslator.translateVirtualizer(0.0f))
        assertEquals(800.toShort(), DynamicsProcessingTranslator.translateVirtualizer(10.0f))
        assertEquals(400.toShort(), DynamicsProcessingTranslator.translateVirtualizer(5.0f))
        // Extreme values clamped
        assertEquals(0.toShort(), DynamicsProcessingTranslator.translateVirtualizer(-5.0f))
        assertEquals(800.toShort(), DynamicsProcessingTranslator.translateVirtualizer(15.0f))
    }

    @Test
    fun testAllPresetsTranslationWithinSafeOperatingLimits() {
        for (preset in FxSoundPreset.ALL_PRESETS) {
            val bassStrength = DynamicsProcessingTranslator.translateBassBoost(preset.bass)
            val virtStrength = DynamicsProcessingTranslator.translateVirtualizer(preset.surround)

            assertTrue("Bass strength for ${preset.name} in safe range", bassStrength in 0..850)
            assertTrue("Virtualizer strength for ${preset.name} in safe range", virtStrength in 0..800)
        }
    }

    @Test
    fun testHeadroomCompensationCalculations() {
        val flatGains = List(10) { 0.0f }

        // Flat EQ, no bass and no clarity: nothing is boosted, so nothing to compensate for.
        val zeroHeadroom = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flatGains, 0.0f)
        assertEquals(0.0f, zeroHeadroom, 0.001f)

        // Moderate bass (5.0 / 10.0) now follows the measured HyperBass curve, which peaks at
        // +9.4 dB at 125 Hz rather than the +8.5 dB the old hand-authored curve used.
        val moderateHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(5.0f, flatGains, 0.0f)
        assertTrue("Moderate headroom should be between 0.5 and 2.0 dB", moderateHeadroom in 0.5f..2.0f)

        val maxBassHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(10.0f, flatGains, 0.0f)
        assertTrue("Max bass headroom should be around 3 dB", maxBassHeadroom in 2.0f..4.0f)

        // The clarity contribution lives in the Post-EQ, so it counts towards the peak the
        // preamp has to make room for.
        val clarityHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flatGains, 10.0f)
        assertTrue("Clarity must add headroom", clarityHeadroom > 0.5f)

        // Max bass plus max EQ boost -> safely capped.
        val extremeBoostGains = List(10) { 14.0f }
        val cappedHeadroom = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            10.0f, extremeBoostGains, 10.0f
        )
        assertEquals(6.0f, cappedHeadroom, 0.001f)

        // Negative EQ cuts should not produce negative headroom compensation.
        val negativeGains = List(10) { -10.0f }
        val cutHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, negativeGains, 0.0f)
        assertEquals(0.0f, cutHeadroom, 0.001f)
    }

    @Test
    fun testClampingEdgeCases() {
        assertEquals(0.toShort(), DynamicsProcessingTranslator.translateBassBoost(-100.0f))
        assertEquals(850.toShort(), DynamicsProcessingTranslator.translateBassBoost(100.0f))
        assertEquals(0.toShort(), DynamicsProcessingTranslator.translateVirtualizer(-100.0f))
        assertEquals(800.toShort(), DynamicsProcessingTranslator.translateVirtualizer(100.0f))
    }

    // --- Single bass strategy -------------------------------------------------
    // The Pre-EQ HyperBass contour and the standalone Android BassBoost both add
    // low-frequency gain. Running them in series compounds to roughly +18 dB, which drove
    // the output limiter into continuous reduction (audible "breathing" on headphones).
    // docs/AUDIO_BUG_INVESTIGATION.md, Finding 1.1.

    @Test
    fun testBassStrategyUsesPreEqWhenDynamicsProcessingIsPresent() {
        val strategy = DynamicsProcessingTranslator.resolveBassStrategy(
            hasDynamicsProcessing = true,
            bassEffectValue = 10.0f
        )
        assertTrue("Pre-EQ must carry the bass contour", strategy.usesPreEqHyperBass)
        assertEquals(
            "Standalone BassBoost must be off when the Pre-EQ is carrying the bass",
            0.toShort(),
            strategy.standaloneBassBoostStrength
        )
    }

    @Test
    fun testBassStrategyUsesStandaloneBassBoostOnFallbackPath() {
        val strategy = DynamicsProcessingTranslator.resolveBassStrategy(
            hasDynamicsProcessing = false,
            bassEffectValue = 5.0f
        )
        assertTrue("Pre-EQ is unavailable on the fallback path", !strategy.usesPreEqHyperBass)
        assertEquals(
            "The Bass slider must still drive the standalone BassBoost on the fallback path",
            DynamicsProcessingTranslator.translateBassBoost(5.0f),
            strategy.standaloneBassBoostStrength
        )
    }

    @Test
    fun testBassStrategyNeverActivatesBothBassPaths() {
        for (hasDp in listOf(true, false)) {
            for (bass in 0..10) {
                val strategy = DynamicsProcessingTranslator.resolveBassStrategy(hasDp, bass.toFloat())
                if (strategy.usesPreEqHyperBass) {
                    assertEquals(
                        "Pre-EQ and standalone BassBoost must never run in series (dp=$hasDp, bass=$bass)",
                        0.toShort(),
                        strategy.standaloneBassBoostStrength
                    )
                }
            }
        }
    }

    @Test
    fun testFallbackBassBoostStrengthIsCapped() {
        // The Equalizer fallback path has no input-gain stage, so bass gain cannot be
        // compensated; the strength is capped to bound the peak overshoot instead.
        val strategy = DynamicsProcessingTranslator.resolveBassStrategy(
            hasDynamicsProcessing = false,
            bassEffectValue = 10.0f
        )
        assertEquals(
            DynamicsProcessingTranslator.FALLBACK_BASS_BOOST_MAX_STRENGTH.toShort(),
            strategy.standaloneBassBoostStrength
        )
        assertTrue(
            "Fallback strength must not exceed the cap",
            strategy.standaloneBassBoostStrength <=
                DynamicsProcessingTranslator.FALLBACK_BASS_BOOST_MAX_STRENGTH.toShort()
        )
    }

    // --- Limiter calibration --------------------------------------------------
    // docs/AUDIO_BUG_INVESTIGATION.md, Finding 1.2: a fast-releasing brickwall limiter
    // ducks the whole broadband mix on every bass transient.

    @Test
    fun testLimiterIsNotProneToPumping() {
        assertTrue(
            "Limiter release must be at least 100 ms to avoid audible gain modulation",
            DynamicsProcessingTranslator.LimiterSpec.RELEASE_TIME_MS >= 100.0f
        )
        assertTrue(
            "Limiter attack must stay fast enough to catch a peak",
            DynamicsProcessingTranslator.LimiterSpec.ATTACK_TIME_MS in 0.1f..5.0f
        )
        assertTrue(
            "Limiter ratio must not be an extreme brickwall",
            DynamicsProcessingTranslator.LimiterSpec.RATIO in 2.0f..20.0f
        )
        assertTrue(
            "Limiter threshold must leave headroom against inter-sample overs",
            DynamicsProcessingTranslator.LimiterSpec.THRESHOLD_DBFS <= -0.1f
        )
        assertEquals(
            "Master gain must not be applied twice (input gain and limiter post gain)",
            0.0f,
            DynamicsProcessingTranslator.LimiterSpec.POST_GAIN_DB,
            0.0001f
        )
    }

    // --- Headroom allocation --------------------------------------------------

    @Test
    fun testHeadroomCompensationGrowsWithBass() {
        val flatGains = List(10) { 0.0f }
        val low = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flatGains, 0.0f)
        val mid = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(5.0f, flatGains, 0.0f)
        val high = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(10.0f, flatGains, 0.0f)

        assertEquals(0.0f, low, 0.001f)
        assertTrue("Headroom must grow with bass ($mid vs $high)", mid < high)
        assertTrue("Headroom must stay within the documented 0..6 dB band", high <= 6.0f)
    }
}
