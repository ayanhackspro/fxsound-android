package com.fxsound.audio.opra

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
 *  - low/high_shelf -> peaking approximation at the same corner frequency with
 *    Q = 0.6 and the shelf gain scaled by 0.8. This is a documented, disclosed
 *    approximation — the UI shows the count of approximated shelves, and the
 *    conversion note says so; nothing silent.
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

    const val SHELF_APPROX_Q = 0.6f
    const val SHELF_APPROX_GAIN_SCALE = 0.8f

    private val SUPPORTED = setOf(OpraBandType.PEAK_DIP, OpraBandType.LOW_SHELF, OpraBandType.HIGH_SHELF)

    fun convert(profile: OpraEqProfile): HeadphoneEqPlan? {
        if (profile.bands.size > MAX_BANDS) return null

        val notes = mutableListOf<String>()
        var shelfCount = 0
        var unsupportedCount = 0
        val bands = mutableListOf<DspEqBand>()

        for (band in profile.bands) {
            when (band.type) {
                OpraBandType.PEAK_DIP -> bands.add(
                    DspEqBand(band.frequency, band.gainDb, band.q?.coerceIn(MIN_Q, MAX_Q) ?: 1.0f)
                )

                OpraBandType.LOW_SHELF, OpraBandType.HIGH_SHELF -> {
                    shelfCount++
                    bands.add(
                        DspEqBand(
                            band.frequency,
                            band.gainDb * SHELF_APPROX_GAIN_SCALE,
                            SHELF_APPROX_Q
                        )
                    )
                }

                else -> unsupportedCount++
            }
        }

        if (shelfCount > 0) {
            notes.add(
                "$shelfCount shelving filter(s) approximated as peaking bands (Q = $SHELF_APPROX_Q, gain × $SHELF_APPROX_GAIN_SCALE) — the renderers support peaking filters only."
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
            conversionNotes = notes
        )
    }

    /** True when the profile has filters the renderers cannot reproduce exactly. */
    fun hasDisclosure(profile: OpraEqProfile): Boolean =
        profile.bands.any { it.type !in SUPPORTED }
}
