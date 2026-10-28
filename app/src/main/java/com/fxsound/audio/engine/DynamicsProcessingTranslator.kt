package com.fxsound.audio.engine

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.DynamicsProcessing.Config
import android.media.audiofx.DynamicsProcessing.Eq
import android.media.audiofx.DynamicsProcessing.EqBand
import android.media.audiofx.DynamicsProcessing.Limiter
import android.media.audiofx.DynamicsProcessing.Mbc
import android.media.audiofx.DynamicsProcessing.MbcBand
import com.fxsound.audio.model.EQ_FREQUENCIES
import com.fxsound.audio.model.FxSoundEffectId
import kotlin.math.max

/**
 * Translates FxSound acoustic model parameters to Android's native DynamicsProcessing,
 * BassBoost, and Virtualizer configurations.
 *
 * Implements ADR-007 (Dual-Engine Architecture for Spotify and Music Players).
 * Enhanced for low latency, smooth gain staging, phase accuracy, and brickwall clipping protection.
 */
object DynamicsProcessingTranslator {

    // 4-Band Multi-Band Compressor cutoff boundaries (Hz)
    // Low (<250Hz), Low-Mid (250-1000Hz), High-Mid (1000-4000Hz), High (4000-20000Hz)
    private val MBC_CUTOFF_FREQUENCIES = floatArrayOf(250.0f, 1000.0f, 4000.0f, 20000.0f)

    /**
     * Upper bound for the standalone Android `BassBoost` strength used on the Equalizer
     * fallback path. That path has no preamp or input-gain stage, so bass gain cannot be
     * compensated the way it is in the DynamicsProcessing path; capping the strength bounds
     * the peak overshoot instead. See docs/GAIN_STAGING_AND_LIMITER.md.
     */
    const val FALLBACK_BASS_BOOST_MAX_STRENGTH = 550

    /**
     * Output limiter calibration. The limiter is a safety net, not the level control:
     * with full preamp compensation a static boost should never reach it, so its job is
     * only to catch peaks. A moderate ratio, slower attack and long release keep a lone
     * transient from ducking the whole mix (docs/AUDIO_BUG_INVESTIGATION.md, Finding 1.2).
     */
    object LimiterSpec {
        const val ATTACK_TIME_MS = 5.0f
        const val RELEASE_TIME_MS = 250.0f
        const val RATIO = 6.0f
        const val THRESHOLD_DBFS = -1.0f
        const val POST_GAIN_DB = 0.0f
    }

    /**
     * Which effect carries the low-frequency boost on a given session.
     *
     * Exactly one of the two may be active. The DynamicsProcessing Pre-EQ HyperBass contour
     * and the standalone Android `BassBoost` both add low-frequency gain, and running them in
     * series compounds to roughly +18 dB. That overshoot is what drove the output limiter into
     * continuous reduction, which is audible as the volume "breathing" on headphones
     * (docs/AUDIO_BUG_INVESTIGATION.md, Finding 1.1).
     */
    data class BassStrategy(
        val usesPreEqHyperBass: Boolean,
        val standaloneBassBoostStrength: Short
    )

    /**
     * Resolves the bass strategy for a session.
     *
     * @param hasDynamicsProcessing true when the session carries a DynamicsProcessing effect,
     *   whose Pre-EQ stage provides the HyperBass contour.
     * @param headphonePlanActive true when an OPRA headphone plan owns the Pre-EQ; the
     *   standalone BassBoost must then stay off as well (no duplicate low-frequency stage).
     */
    fun resolveBassStrategy(
        hasDynamicsProcessing: Boolean,
        bassEffectValue: Float,
        headphonePlanActive: Boolean = false
    ): BassStrategy {
        if (headphonePlanActive || hasDynamicsProcessing) {
            return BassStrategy(usesPreEqHyperBass = hasDynamicsProcessing && !headphonePlanActive, standaloneBassBoostStrength = 0)
        }
        val capped = minOf(
            translateBassBoost(bassEffectValue).toInt(),
            FALLBACK_BASS_BOOST_MAX_STRENGTH
        )
        return BassStrategy(
            usesPreEqHyperBass = false,
            standaloneBassBoostStrength = capped.toShort()
        )
    }

    /**
     * DynamicsProcessing capability tiers for universal Android OEM compatibility.
     * Allows negotiating down from dual-EQ to streamlined configurations on memory-constrained HALs.
     */
    enum class DynamicsTier(val label: String) {
        TIER_1_FULL("Tier 1: Full Precision (Dual-EQ + MBC + Limiter)"),
        TIER_2_STREAMLINED("Tier 2: Streamlined (Combined 10-Band EQ + MBC + Limiter)"),
        TIER_3_ESSENTIAL("Tier 3: Essential (Combined 10-Band EQ + Limiter)")
    }

    /**
     * Builds a comprehensive DynamicsProcessing.Config object representing current FxSound settings.
     * Uses VARIANT_FAVOR_TIME_RESOLUTION for phase accuracy and low-latency transient response.
     *
     * @param headphonePlan optional OPRA headphone-correction plan. It REPLACES the Pre-EQ
     *   bass contour while active — the headphone curve is then the single low-frequency
     *   stage (ADR-019 single-bass rule) and the standalone BassBoost stays off.
     * @param tier capability tier to generate. Tier 2 cuts stage count in half for constrained OEM HALs.
     */
    fun buildDynamicsConfig(
        isPowerOn: Boolean,
        effects: Map<FxSoundEffectId, Float>,
        eqGains: List<Float>,
        masterGainDb: Float = 0.0f,
        headphonePlan: com.fxsound.audio.opra.HeadphoneEqPlan? = null,
        tier: DynamicsTier = DynamicsTier.TIER_1_FULL
    ): Config {
        val clarity = effects[FxSoundEffectId.FIDELITY] ?: 5.0f
        val dynamicBoost = effects[FxSoundEffectId.DYNAMIC_BOOST] ?: 4.0f
        val bass = effects[FxSoundEffectId.BASS] ?: 4.0f

        val channelCount = 2 // Stereo

        // Multi-Band Compressor (FxSound Dynamic Boost / Maximizer)
        val mbcMakeUpDb = FxSoundResponseTables.dynamicMakeUpDb(dynamicBoost)
        val mbcThresholdDb = FxSoundResponseTables.dynamicKneeDb(dynamicBoost)
        val mbcRatio = 2.0f
        val mbcAttackTimes = floatArrayOf(30.0f, 12.0f, 5.0f, 2.0f)
        val mbcReleaseTimes = floatArrayOf(120.0f, 80.0f, 50.0f, 35.0f)

        val mbc = Mbc(true, isPowerOn, 4).apply {
            for (i in 0 until 4) {
                val band = MbcBand(
                    true,
                    MBC_CUTOFF_FREQUENCIES[i],
                    mbcAttackTimes[i],
                    mbcReleaseTimes[i],
                    mbcRatio,
                    mbcThresholdDb,
                    4.0f,          // soft knee (4 dB) for natural compression transitions
                    -60.0f,        // noiseGateThreshold
                    1.0f,          // expanderRatio
                    0.0f,          // preGain
                    mbcMakeUpDb    // postGain: the upstream's measured make-up gain
                )
                setBand(i, band)
            }
        }

        // Output Peak Limiter (Brickwall peak protection against clipping overs)
        val limiter = Limiter(
            true,                     // inUse
            isPowerOn,                // enabled
            0,                        // linkGroup
            LimiterSpec.ATTACK_TIME_MS,   // clean transient peak catch
            LimiterSpec.RELEASE_TIME_MS,  // smooth, musical recovery without volume pumping
            LimiterSpec.RATIO,            // firm transparent limiter
            LimiterSpec.THRESHOLD_DBFS,   // headroom cushion against inter-sample overs
            LimiterSpec.POST_GAIN_DB      // must be 0dB (prevents duplicate master gain application)
        )

        val headroomCompensationDb = calculateHeadroomCompensationDb(
            bass,
            eqGains,
            clarity,
            headphonePlan,
            if (tier != DynamicsTier.TIER_3_ESSENTIAL) dynamicBoost else 0.0f
        )
        val finalInputGainDb = (
            masterGainDb - headroomCompensationDb + (headphonePlan?.preampDb ?: 0.0f)
            ).coerceIn(-24.0f, 6.0f)

        val clarityBandGains = FxSoundResponseTables.fidelityBandGainsDb(clarity)
        val bassBandGains = FxSoundResponseTables.bassBandGainsDb(bass)

        return when (tier) {
            DynamicsTier.TIER_1_FULL -> {
                var preEqBandCount = 10
                val preEq: Eq = if (headphonePlan != null) {
                    preEqBandCount = headphonePlan.bands.size
                    Eq(true, isPowerOn, headphonePlan.bands.size).apply {
                        for ((i, band) in headphonePlan.bands.withIndex()) {
                            setBand(i, EqBand(true, band.frequencyHz, band.gainDb))
                        }
                    }
                } else {
                    Eq(true, isPowerOn, 10).apply {
                        for (i in 0 until 10) {
                            setBand(i, EqBand(true, EQ_FREQUENCIES[i].toFloat(), bassBandGains[i]))
                        }
                    }
                }

                val postEq = Eq(true, isPowerOn, 10).apply {
                    for (i in 0 until 10) {
                        val userGain = eqGains.getOrElse(i) { 0.0f }
                        val totalGain = (userGain + clarityBandGains[i]).coerceIn(-14.0f, 14.0f)
                        setBand(i, EqBand(true, EQ_FREQUENCIES[i].toFloat(), totalGain))
                    }
                }

                Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    channelCount,
                    true,  // usePreEq
                    preEqBandCount,
                    true,  // useMbc
                    4,     // mbcBandCount
                    true,  // usePostEq
                    10,    // postEqBandCount
                    true   // useLimiter
                ).apply {
                    setInputGainAllChannelsTo(finalInputGainDb)
                    setPreEqAllChannelsTo(preEq)
                    setMbcAllChannelsTo(mbc)
                    setPostEqAllChannelsTo(postEq)
                    setLimiterAllChannelsTo(limiter)
                }.build()
            }

            DynamicsTier.TIER_2_STREAMLINED -> {
                // Tier 2: Streamlined combined EQ stage + 4-band MBC + Limiter
                // Halves total stage count to fit memory-constrained OEM HALs (Samsung One UI, Motorola, MediaTek)
                val combinedEq = Eq(true, isPowerOn, 10).apply {
                    for (i in 0 until 10) {
                        val userGain = eqGains.getOrElse(i) { 0.0f }
                        val bassGain = if (headphonePlan == null) bassBandGains[i] else 0.0f
                        val totalGain = (userGain + bassGain + clarityBandGains[i]).coerceIn(-14.0f, 14.0f)
                        setBand(i, EqBand(true, EQ_FREQUENCIES[i].toFloat(), totalGain))
                    }
                }

                Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    channelCount,
                    false, // usePreEq disabled to stay under HAL buffer allocation
                    0,
                    true,  // useMbc
                    4,     // mbcBandCount
                    true,  // usePostEq
                    10,    // postEqBandCount
                    true   // useLimiter
                ).apply {
                    setInputGainAllChannelsTo(finalInputGainDb)
                    setMbcAllChannelsTo(mbc)
                    setPostEqAllChannelsTo(combinedEq)
                    setLimiterAllChannelsTo(limiter)
                }.build()
            }

            DynamicsTier.TIER_3_ESSENTIAL -> {
                // Tier 3: Essential combined EQ + Limiter (no MBC) for ultra-restricted OEM HALs
                val combinedEq = Eq(true, isPowerOn, 10).apply {
                    for (i in 0 until 10) {
                        val userGain = eqGains.getOrElse(i) { 0.0f }
                        val bassGain = if (headphonePlan == null) bassBandGains[i] else 0.0f
                        val totalGain = (userGain + bassGain + clarityBandGains[i]).coerceIn(-14.0f, 14.0f)
                        setBand(i, EqBand(true, EQ_FREQUENCIES[i].toFloat(), totalGain))
                    }
                }

                Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_TIME_RESOLUTION,
                    channelCount,
                    false, // usePreEq
                    0,
                    false, // useMbc
                    0,
                    true,  // usePostEq
                    10,    // postEqBandCount
                    true   // useLimiter
                ).apply {
                    setInputGainAllChannelsTo(finalInputGainDb)
                    setPostEqAllChannelsTo(combinedEq)
                    setLimiterAllChannelsTo(limiter)
                }.build()
            }
        }
    }

    /**
     * Calculates the input headroom, in dB, that keeps the chain linear.
     *
     * The output is level-independent: a static boost is compensated before it reaches the
     * limiter, so the perceived amount of bass/clarity no longer depends on how loud the
     * source material or the phone volume is. The previous 0.35 factor deliberately
     * under-compensated, which left the limiter doing the level control and produced audible
     * pumping. The compensation now covers the full peak potential:
     *
     * - the largest simultaneous static boost (bass Pre-EQ peak or the highest post-EQ band),
     * - a small allowance for neighbouring bands summing,
     * - the maximiser's broadband make-up gain, which sits between the two EQs.
     *
     * It is capped at 24 dB because the DynamicsProcessing input gain is clamped to -24 dB;
     * asking for more than that would silently stop being applied.
     *
     * The Equalizer fallback path has no input-gain stage, so it cannot use this; it folds
     * its peak boost into a band-level offset instead (see the service's applyEqualizerBands).
     *
     * @param headphonePlan when present its positive band gains are added to the peaks the
     *   preamp must make room for; its negative preamp is folded in by the caller.
     * @param dynamicBoostEffectValue the Dynamic Boost control value, whose make-up gain is
     *   part of the chain and therefore part of the headroom requirement.
     */
    fun calculateHeadroomCompensationDb(
        bassEffectValue: Float,
        eqGains: List<Float>,
        clarityEffectValue: Float = 5.0f,
        headphonePlan: com.fxsound.audio.opra.HeadphoneEqPlan? = null,
        dynamicBoostEffectValue: Float = 0.0f
    ): Float {
        val bassBandGains = FxSoundResponseTables.bassBandGainsDb(bassEffectValue)
        val clarityBandGains = FxSoundResponseTables.fidelityBandGainsDb(clarityEffectValue)

        var maxPositiveEqBoost = 0.0f
        var positiveGainSum = 0.0f
        for (i in 0 until 10) {
            // The clarity contribution lives in the Post-EQ, so it is part of the peak the
            // preamp has to make room for.
            val totalGain = eqGains.getOrElse(i) { 0.0f } + clarityBandGains[i]
            if (totalGain > 0.0f) {
                positiveGainSum += totalGain
            }
            if (totalGain > maxPositiveEqBoost) {
                maxPositiveEqBoost = totalGain
            }
        }

        if (headphonePlan != null) {
            for (band in headphonePlan.bands) {
                if (band.gainDb > 0.0f) {
                    positiveGainSum += band.gainDb
                }
                if (band.gainDb > maxPositiveEqBoost) {
                    maxPositiveEqBoost = band.gainDb
                }
            }
        }

        // While the headphone plan is active, the FxSound bass contour is skipped (the plan
        // is the single low-frequency stage), so its peak must not be counted twice.
        val bassPeakDb = bassBandGains.max().takeIf { headphonePlan == null } ?: 0.0f
        val mbcMakeUpDb = FxSoundResponseTables.dynamicMakeUpDb(dynamicBoostEffectValue)
        val totalPeakPotential =
            max(maxPositiveEqBoost, bassPeakDb) + (positiveGainSum * 0.08f) + mbcMakeUpDb
        return totalPeakPotential.coerceIn(0.0f, 24.0f)
    }

    /**
     * Translates FxSound Bass effect (0 to 10) into Android BassBoost strength (0 to 850).
     */
    fun translateBassBoost(bassEffectValue: Float): Short {
        val clamped = bassEffectValue.coerceIn(0.0f, 10.0f)
        return ((clamped / 10.0f) * 850.0f).toInt().toShort()
    }

    /**
     * Translates FxSound Surround effect (0 to 10) into Android Virtualizer strength (0 to 800).
     */
    fun translateVirtualizer(surroundEffectValue: Float): Short {
        val clamped = surroundEffectValue.coerceIn(0.0f, 10.0f)
        return ((clamped / 10.0f) * 800.0f).toInt().toShort()
    }
}
