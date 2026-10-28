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

        // Moderate bass (5.0 / 10.0) follows the measured HyperBass curve. Full peak
        // compensation now covers the whole boost instead of a 0.35 fraction.
        val moderateHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(5.0f, flatGains, 0.0f)
        assertTrue("Moderate headroom should be around 3 dB", moderateHeadroom in 2.0f..4.0f)

        val maxBassHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(10.0f, flatGains, 0.0f)
        assertTrue("Max bass headroom should be around 11 dB", maxBassHeadroom in 9.0f..13.0f)

        // The clarity contribution lives in the Post-EQ, so it counts towards the peak the
        // preamp has to make room for.
        val clarityHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flatGains, 10.0f)
        assertTrue("Clarity must add headroom", clarityHeadroom > 5.0f)

        // Max bass plus max EQ boost -> safely capped at the input-gain floor.
        val extremeBoostGains = List(10) { 14.0f }
        val cappedHeadroom = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            10.0f, extremeBoostGains, 10.0f
        )
        assertEquals(24.0f, cappedHeadroom, 0.001f)

        // Negative EQ cuts should not produce negative headroom compensation.
        val negativeGains = List(10) { -10.0f }
        val cutHeadroom =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, negativeGains, 0.0f)
        assertEquals(0.0f, cutHeadroom, 0.001f)
    }

    @Test
    fun testMaximiserMakeUpCountsTowardsHeadroom() {
        val flat = List(10) { 0.0f }
        val withoutMaximiser =
            DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flat, 0.0f)
        val withMaximiser = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            0.0f, flat, 0.0f, null, 4.0f
        )
        // BALANCED dynamic boost measures +5.87 dB of make-up; it sits between the EQs and
        // must be compensated or loud material is pushed into the limiter.
        assertTrue(
            "Make-up gain must raise the headroom requirement ($withoutMaximiser -> $withMaximiser)",
            withMaximiser > withoutMaximiser
        )
        assertTrue(
            "The delta should match the measured make-up gain",
            withMaximiser - withoutMaximiser in 5.0f..6.5f
        )
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

    @Test
    fun testLimiterIsASafetyNetNotALevelControl() {
        // With full preamp compensation the limiter should rarely engage. A moderate ratio
        // and a long release keep a lone transient from ducking the mix, which was the
        // audible "breathing" at high volume.
        assertTrue(
            "Ratio must be moderate, not brickwall",
            DynamicsProcessingTranslator.LimiterSpec.RATIO <= 8.0f
        )
        assertTrue(
            "Attack must be slow enough not to clamp on every transient",
            DynamicsProcessingTranslator.LimiterSpec.ATTACK_TIME_MS >= 2.0f
        )
        assertTrue(
            "Release must be long enough to avoid pumping",
            DynamicsProcessingTranslator.LimiterSpec.RELEASE_TIME_MS >= 200.0f
        )
        assertTrue(
            "Threshold must sit below full scale",
            DynamicsProcessingTranslator.LimiterSpec.THRESHOLD_DBFS <= -1.0f
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
        assertTrue("Headroom must stay within the documented 0..24 dB band", high <= 24.0f)
    }

    // --- OPRA headphone plans (ADR-028) ---------------------------------------

    private fun plan(vararg bands: Pair<Float, Float>) = com.fxsound.audio.opra.HeadphoneEqPlan(
        profileId = "test::profile",
        author = "Test",
        details = null,
        link = null,
        preampDb = -6f,
        bands = bands.map { com.fxsound.audio.opra.DspEqBand(it.first, it.second, 1.0f) },
        conversionNotes = emptyList()
    )

    @Test
    fun testHeadphonePlanOwnsTheOnlyBassStage() {
        val strategy = DynamicsProcessingTranslator.resolveBassStrategy(
            hasDynamicsProcessing = true,
            bassEffectValue = 10.0f,
            headphonePlanActive = true
        )
        assertTrue(
            "With an OPRA plan the Pre-EQ carries the headphone curve, not the FxSound bass contour",
            !strategy.usesPreEqHyperBass
        )
        assertEquals(
            "No standalone BassBoost may stack on top of the headphone curve",
            0.toShort(),
            strategy.standaloneBassBoostStrength
        )
    }

    @Test
    fun testHeadphonePlanCountsTowardsHeadroom() {
        val flat = List(10) { 0.0f }
        val baseline = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flat, 0f)
        val boosted = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            0.0f, flat, 0f,
            plan(100f to 6f, 3000f to 3f)
        )
        assertTrue("Plan boosts must raise required headroom", boosted > baseline)
        assertEquals("Headroom must stay capped at 24 dB", true, boosted <= 24.0f)
    }

    @Test
    fun testNegativePlanDoesNotAddHeadroom() {
        val flat = List(10) { 0.0f }
        val baseline = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(0.0f, flat, 0f)
        val cut = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            0.0f, flat, 0f,
            plan(100f to -8f, 3000f to -2f)
        )
        assertEquals("Cuts must not create headroom compensation", baseline, cut, 0.001f)
    }

    @Test
    fun testDynamicsTierLabelsAndHierarchy() {
        val tiers = DynamicsProcessingTranslator.DynamicsTier.values()
        assertEquals(3, tiers.size)
        assertEquals(DynamicsProcessingTranslator.DynamicsTier.TIER_1_FULL, tiers[0])
        assertEquals(DynamicsProcessingTranslator.DynamicsTier.TIER_2_STREAMLINED, tiers[1])
        assertEquals(DynamicsProcessingTranslator.DynamicsTier.TIER_3_ESSENTIAL, tiers[2])

        assertTrue(tiers[0].label.contains("Tier 1: Full Precision"))
        assertTrue(tiers[1].label.contains("Tier 2: Streamlined"))
        assertTrue(tiers[2].label.contains("Tier 3: Essential"))
    }

    @Test
    fun testTier3ExcludesMaximiserFromHeadroomCalculation() {
        val flat = List(10) { 0.0f }
        val boost = 5.0f
        val tier1Headroom = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            bassEffectValue = 0.0f,
            eqGains = flat,
            clarityEffectValue = 0.0f,
            headphonePlan = null,
            dynamicBoostEffectValue = boost
        )
        val tier3Headroom = DynamicsProcessingTranslator.calculateHeadroomCompensationDb(
            bassEffectValue = 0.0f,
            eqGains = flat,
            clarityEffectValue = 0.0f,
            headphonePlan = null,
            dynamicBoostEffectValue = 0.0f
        )
        assertTrue(
            "Tier 1 (with Maximiser MBC) must require more headroom than Tier 3 (MBC bypassed)",
            tier1Headroom > tier3Headroom
        )
        assertEquals(0.0f, tier3Headroom, 0.001f)
    }
}
