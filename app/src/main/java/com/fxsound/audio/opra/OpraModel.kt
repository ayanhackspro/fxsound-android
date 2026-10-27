package com.fxsound.audio.opra

/**
 * OPRA (Open Profiles for Revealing Audio) dataset model.
 *
 * OPRA data is licensed CC BY-SA 4.0; code in this file consumes it but must never
 * fabricate entries. Every object here mirrors the published schema in the OPRA
 * repository (schemas/eq_info.json, schemas/product_info.json,
 * schemas/vendor_info.json). See docs/CONSUMING guidance: the JSONL dump
 * (`database_v1.jsonl`) is the supported dataset format; the website is never scraped.
 */

/** The filter types named by the OPRA eq schema. */
enum class OpraBandType(val wireName: String) {
    PEAK_DIP("peak_dip"),
    LOW_SHELF("low_shelf"),
    HIGH_SHELF("high_shelf"),
    LOW_PASS("low_pass"),
    HIGH_PASS("high_pass"),
    BAND_PASS("band_pass"),
    BAND_STOP("band_stop");

    companion object {
        fun fromWire(name: String): OpraBandType? =
            entries.firstOrNull { it.wireName == name }
    }
}

data class OpraBand(
    val type: OpraBandType,
    val frequency: Float,
    val gainDb: Float = 0.0f,
    val q: Float? = null,
    /** 6–36 dB/oct; required by the schema for low_pass/high_pass. */
    val slopeDbPerOctave: Float? = null
)

/** A full parametric EQ profile, as published in the dump. */
data class OpraEqProfile(
    val id: String,
    val productId: String,
    val author: String,
    val details: String?,
    val link: String?,
    val preampDb: Float,
    val bands: List<OpraBand>
)

/** Lightweight per-eq summary used to browse without retaining every band in memory. */
data class OpraEqSummary(
    val id: String,
    val productId: String,
    val author: String,
    val details: String?,
    val link: String?,
    val preampDb: Float,
    val bandCount: Int,
    val shelfBandCount: Int,
    val unsupportedBandCount: Int
)

data class OpraProduct(
    val id: String,
    val name: String,
    val vendorId: String,
    val vendorName: String,
    /** over_the_ear / on_the_ear / in_ear / earbuds */
    val subtype: String?
)

/** Disambiguation warning state when searching by a plain product name. */
data class CatalogueEntry(
    val product: OpraProduct,
    val summaries: List<OpraEqSummary>
)

data class OpraCatalogue(
    val vendors: Map<String, String>,
    val entries: Map<String, CatalogueEntry>
)

/**
 * The OPRA profile translated into the filter format the renderers support
 * (peaking bands only — the DfxDsp PEQ shelf sections are compile-time disabled
 * ("DSP_PEQ_SHELFS_ON … NO LONGER USED") and Android's DynamicsProcessing EqBand
 * exposes only (cutoff, gain)).
 */
data class DspEqBand(
    val frequencyHz: Float,
    val gainDb: Float,
    val q: Float,
    /**
     * For approximated shelving bands: worst |dB| deviation from the original shelf across
     * 20 Hz–20 kHz. Null for bands that were rendered verbatim.
     */
    val approximationErrorDb: Float? = null
)

/**
 * Always shown wherever a converted curve is presented. Android's DynamicsProcessing
 * `EqBand` accepts only (cutoff, gain) — the Q below is used by the native renderer and
 * by the preview, but the platform chooses the actual band bandwidth for external
 * sessions, so the rendered curve is an approximation of the modelled one.
 */
const val RENDERER_DISCLOSURE: String =
    "Peaking-only renderer: the device applies its own band bandwidth and the preview " +
        "shows the ideal Q, so this is an approximation of the OPRA target, not a " +
        "measured transfer function."

data class HeadphoneEqPlan(
    val profileId: String,
    val author: String,
    val details: String?,
    val link: String?,
    /** OPRA's own gain_db preamp, folded into the input-gain stage — never a second EQ. */
    val preampDb: Float,
    val bands: List<DspEqBand>,
    /** Human-readable, UIVisible conversion disclosures (never silent substitutions). */
    val conversionNotes: List<String>,
    /** Worst shelving-band approximation error on this profile, 0 when it has none. */
    val maxShelfApproximationErrorDb: Float = 0f,
    val rendererDisclosure: String = RENDERER_DISCLOSURE,
    /** Per-band trims the user added (E4), aligned with [bands]; empty when untouched. */
    val userBandOffsets: List<Float> = emptyList()
)

data class OpraApplyResult(val success: Boolean, val message: String)
