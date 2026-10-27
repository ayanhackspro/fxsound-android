package com.fxsound.audio.opra

import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Converts an OPRA parametric_eq profile into [HeadphoneEqPlan] for the renderers.
 *
 * Filter-support policy (audited, not assumed):
 *  - Native DfxDsp: the PEQ band cells implement generic boost/cut peaking sections.
 *    The two shelf sections are permanently disabled upstream
 *    ("if( *(volatile long *)(DSP_PEQ_SHELFS_ON) ) NO LONGER USED").
 *  - Android DynamicsProcessing (the runtime renderer for external sessions):
 *    EqBand carries only (cutoff, gain); bands render as peaking sections.
 *
 * Therefore:
 *  - peak_dip       -> peaking band, parameters used verbatim.
 *  - low/high_shelf -> peaking approximation at the same corner frequency. The
 *    constants are measured, not guessed: `ShelfApproximationCalibrationTest` grid-searches
 *    them against all 24,980 shelving bands in the real dataset. Best mean |dB| error over
 *    20 Hz–20 kHz is Q = 1.45 with the shelf gain scaled by 0.50 (0.79 dB mean vs 0.84 dB
 *    for the previous Q = 0.6 / ×0.8). The residual is not uniform: p99 is 4.12 dB and
 *    high-gain high shelves can deviate far more, so every plan also carries its own
 *    worst-case approximation error, which the UI shows. Nothing silent.
 *  - low_pass/high_pass/band_pass/band_stop -> unsupported; the band is skipped
 *    and disclosed in the conversion notes (never silently dropped) — currently
 *    negligible in the dataset (low_pass appears in a handful of curves only).
 */
object OpraProfileConverter {

    /** Bounded so a pathological curve can never blow up the effect config count. */
    const val MAX_BANDS = 20

    /** Sensible peaking-Q range; OPRA curves stay well inside this, clamping protects. */
    private const val MIN_Q = 0.4f
    private const val MAX_Q = 10.0f

    /** Measured optimum against the real dataset — see A1 in docs/OPRA_HARDENING_PLAN.md. */
    const val SHELF_APPROX_Q = 1.45f
    const val SHELF_APPROX_GAIN_SCALE = 0.50f

    /** Fallback slope when a shelf band omits `q` (the schema makes it optional). */
    private const val DEFAULT_SHELF_Q = 0.7f

    private const val EVAL_FS = 44100.0
    private const val EVAL_POINTS = 61
    private val EVAL_FREQS = DoubleArray(EVAL_POINTS) { i ->
        20.0 * 1000.0.pow(i / (EVAL_POINTS - 1).toDouble() * (ln(20000.0 / 20.0) / ln(10.0)))
    }

    /**
     * Worst |dB| deviation between the original RBJ shelf and the peaking stand-in used by
     * the renderer, over 20 Hz–20 kHz. Reported per plan so users are never told an
     * approximated curve is exact.
     */
    fun shelfApproximationWorstErrorDb(band: OpraBand): Float {
        val peaking = peakingCoeffs(
            band.frequency.toDouble(),
            band.gainDb.toDouble() * SHELF_APPROX_GAIN_SCALE,
            SHELF_APPROX_Q.toDouble()
        )
        val shelf = shelfCoeffs(
            band.frequency.toDouble(),
            band.gainDb.toDouble(),
            (band.q ?: DEFAULT_SHELF_Q).toDouble(),
            band.type == OpraBandType.LOW_SHELF
        )
        var worst = 0.0
        for (i in EVAL_FREQS.indices) {
            val d = kotlin.math.abs(responseDb(shelf, i) - responseDb(peaking, i))
            if (d > worst) worst = d
        }
        return worst.toFloat()
    }

    private fun responseDb(c: DoubleArray, i: Int): Double {
        val w = 2.0 * PI * EVAL_FREQS[i] / EVAL_FS
        val cw = cos(w)
        val sw = sin(w)
        val c2 = 2.0 * cw * cw - 1.0
        val numRe = c[0] + c[1] * cw + c[2] * c2
        val numIm = -(c[1] * sw + c[2] * 2.0 * sw * cw)
        val denRe = c[3] + c[4] * cw + c[5] * c2
        val denIm = -(c[4] * sw + c[5] * 2.0 * sw * cw)
        val mag2 = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
        return 10.0 * ln(mag2.coerceAtLeast(1e-30)) / ln(10.0)
    }

    private fun shelfCoeffs(f0: Double, gainDb: Double, q: Double, low: Boolean): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / EVAL_FS
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val sq = sqrt(a)
        return if (low) {
            doubleArrayOf(
                a * ((a + 1) - (a - 1) * cosW + 2 * sq * alpha),
                2 * a * ((a - 1) - (a + 1) * cosW),
                a * ((a + 1) - (a - 1) * cosW - 2 * sq * alpha),
                (a + 1) + (a - 1) * cosW + 2 * sq * alpha,
                -2 * ((a - 1) + (a + 1) * cosW),
                (a + 1) + (a - 1) * cosW - 2 * sq * alpha
            )
        } else {
            doubleArrayOf(
                a * ((a + 1) + (a - 1) * cosW + 2 * sq * alpha),
                -2 * a * ((a - 1) + (a + 1) * cosW),
                a * ((a + 1) + (a - 1) * cosW - 2 * sq * alpha),
                (a + 1) - (a - 1) * cosW + 2 * sq * alpha,
                2 * ((a - 1) - (a + 1) * cosW),
                (a + 1) - (a - 1) * cosW - 2 * sq * alpha
            )
        }
    }

    private fun peakingCoeffs(f0: Double, gainDb: Double, q: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / EVAL_FS
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        return doubleArrayOf(
            1 + alpha * a, -2 * cosW, 1 - alpha * a,
            1 + alpha / a, -2 * cosW, 1 - alpha / a
        )
    }

    fun convert(profile: OpraEqProfile): HeadphoneEqPlan? {
        if (profile.bands.size > MAX_BANDS) return null

        val notes = mutableListOf<String>()
        var shelfCount = 0
        var unsupportedCount = 0
        var worstShelfError = 0f
        val bands = mutableListOf<DspEqBand>()

        for (band in profile.bands) {
            when (band.type) {
                OpraBandType.PEAK_DIP -> bands.add(
                    DspEqBand(band.frequency, band.gainDb, band.q?.coerceIn(MIN_Q, MAX_Q) ?: 1.0f)
                )

                OpraBandType.LOW_SHELF, OpraBandType.HIGH_SHELF -> {
                    shelfCount++
                    val error = shelfApproximationWorstErrorDb(band)
                    if (error > worstShelfError) worstShelfError = error
                    bands.add(
                        DspEqBand(
                            band.frequency,
                            band.gainDb * SHELF_APPROX_GAIN_SCALE,
                            SHELF_APPROX_Q,
                            approximationErrorDb = error
                        )
                    )
                }

                else -> unsupportedCount++
            }
        }

        if (shelfCount > 0) {
            notes.add(
                "$shelfCount shelving filter(s) approximated as peaking bands (Q = $SHELF_APPROX_Q, gain × $SHELF_APPROX_GAIN_SCALE) — " +
                    "the renderers support peaking filters only; worst-case deviation on this profile is " +
                    "≈${String.format(Locale.ROOT, "%.1f", worstShelfError)} dB."
            )
        }
        if (unsupportedCount > 0) {
            notes.add("$unsupportedCount unsupported filter type(s) (pass/band filters) skipped — they cannot be rendered.")
        }

        if (bands.isEmpty()) return null

        return HeadphoneEqPlan(
            profileId = profile.id,
            author = profile.author,
            details = profile.details,
            link = profile.link,
            preampDb = profile.preampDb,
            bands = bands,
            conversionNotes = notes,
            maxShelfApproximationErrorDb = worstShelfError
        )
    }

    /** True when the profile has filters the renderers cannot reproduce exactly. */
    fun hasDisclosure(profile: OpraEqProfile): Boolean =
        profile.bands.any { it.type != OpraBandType.PEAK_DIP }

    /**
     * E4: applies the user's master trim plus per-band offsets to a converted plan.
     *
     * Pure so the arithmetic is unit-tested rather than inferred from the UI: gains are
     * clamped to ±20 dB, offsets to ±6 dB, and every adjustment is disclosed in the
     * conversion notes (never a silent change to a published curve).
     */
    fun applyUserAdjustments(
        plan: HeadphoneEqPlan,
        masterAdjustDb: Float,
        bandOffsets: List<Float>
    ): HeadphoneEqPlan {
        val offsets = List(plan.bands.size) { i ->
            (bandOffsets.getOrNull(i) ?: 0f).coerceIn(-6f, 6f)
        }
        if (masterAdjustDb == 0f && offsets.all { it == 0f }) {
            return plan.copy(userBandOffsets = offsets)
        }

        val notes = plan.conversionNotes.toMutableList()
        if (masterAdjustDb != 0f) {
            val sign = if (masterAdjustDb > 0) "+" else ""
            notes.add(
                "User adjustment: $sign${String.format(Locale.ROOT, "%.1f", masterAdjustDb)} dB applied to every band."
            )
        }
        if (offsets.any { it != 0f }) {
            val detail = offsets.withIndex()
                .filter { it.value != 0f }
                .joinToString(", ") {
                    "${String.format(Locale.ROOT, "%+.1f", it.value)} dB on band ${it.index + 1}"
                }
            notes.add("User per-band trims: $detail.")
        }

        return plan.copy(
            bands = plan.bands.mapIndexed { i, band ->
                band.copy(
                    gainDb = (band.gainDb + offsets[i] + masterAdjustDb).coerceIn(-20f, 20f)
                )
            },
            conversionNotes = notes,
            userBandOffsets = offsets
        )
    }
}
