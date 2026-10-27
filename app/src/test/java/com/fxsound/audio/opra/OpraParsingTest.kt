package com.fxsound.audio.opra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for OPRA parsing and filter conversion. Sample lines are copied verbatim
 * from the official database_v1.jsonl dump (CC BY-SA 4.0).
 */
class OpraParsingTest {

    private val eqLine =
        """{"type":"eq","id":"kiiboom:evoke::autoeq_hi_end_portable","data":{"author":"AutoEQ","details":"Measured by Hi End Portable","type":"parametric_eq","parameters":{"gain_db":-4,"bands":[{"type":"low_shelf","frequency":105,"gain_db":-1.4,"q":0.7},{"type":"peak_dip","frequency":170,"gain_db":-1.9,"q":1.47},{"type":"peak_dip","frequency":7619,"gain_db":3.3,"q":4.48},{"type":"peak_dip","frequency":5275,"gain_db":3.1,"q":3.96},{"type":"peak_dip","frequency":1400,"gain_db":-1.3,"q":2.18},{"type":"high_shelf","frequency":10000,"gain_db":2.2,"q":0.7},{"type":"peak_dip","frequency":666,"gain_db":0.4,"q":1.44},{"type":"peak_dip","frequency":67,"gain_db":0.6,"q":1.76},{"type":"peak_dip","frequency":27,"gain_db":-0.5,"q":2.55},{"type":"peak_dip","frequency":116,"gain_db":-0.4,"q":2.83}]},"product_id":"kiiboom::evoke"}}"""

    private val productLine =
        """{"type":"product","id":"kiiboom::evoke","data":{"name":"Evoke","type":"headphones","subtype":"in_ear","vendor_id":"kiiboom","line_art_svg":"assets/35.svg","line_art_96x64_png":"assets/35.png"}}"""

    private val vendorLine =
        """{"type":"vendor","id":"kiiboom","data":{"name":"KiiBOOM"}}"""

    @Test
    fun `parses eq profile with bands and preamp`() {
        val profile = OpraJson.parseProfile(eqLine)!!
        assertEquals("kiiboom:evoke::autoeq_hi_end_portable", profile.id)
        assertEquals("kiiboom::evoke", profile.productId)
        assertEquals("AutoEQ", profile.author)
        assertEquals(-4f, profile.preampDb)
        assertEquals(10, profile.bands.size)
        assertEquals(OpraBandType.LOW_SHELF, profile.bands[0].type)
        assertEquals(105f, profile.bands[0].frequency)
        assertEquals(1.47f, profile.bands[1].q)
    }

    @Test
    fun `indexes a full vendor-product-eq dump`() {
        val lines = sequenceOf(
            0L to vendorLine,
            60L to productLine,
            200L to eqLine
        )
        val result = OpraJson.index(lines)
        assertEquals(mapOf("kiiboom" to "KiiBOOM"), result.catalogue.vendors)
        assertEquals(1, result.catalogue.entries.size)
        val entry = result.catalogue.entries["kiiboom::evoke"]!!
        assertEquals("Evoke", entry.product.name)
        assertEquals("KiiBOOM", entry.product.vendorName)
        assertEquals(1, entry.summaries.size)
        assertEquals(10, entry.summaries[0].bandCount)
        assertEquals(2, entry.summaries[0].shelfBandCount)
        assertEquals(-4f, entry.summaries[0].preampDb)
        assertEquals(200L, result.profileOffsets["kiiboom:evoke::autoeq_hi_end_portable"])
    }

    @Test
    fun `malformed lines are skipped and counted not fabricated`() {
        val result = OpraJson.index(sequenceOf(0L to """{"type":"eq","id":"broken"""))
        assertEquals(1, result.skippedLines)
        assertTrue(result.catalogue.entries.isEmpty())
    }

    @Test
    fun `persistence round-trips the selected profile`() {
        val profile = OpraJson.parseProfile(eqLine)!!
        val json = OpraJson.encodeForPersistence(profile, adjustDb = -1.5f, bandOffsets = listOf(0f, 1.5f, -2f))
        val restored = OpraJson.decodeFromPersistence(json)!!
        assertEquals(profile.id, restored.profile.id)
        assertEquals(profile.productId, restored.profile.productId)
        assertEquals(profile.author, restored.profile.author)
        assertEquals(profile.bands.size, restored.profile.bands.size)
        assertEquals(-1.5f, restored.adjustDb)
        assertEquals(listOf(0f, 1.5f, -2f), restored.bandOffsets)
    }

    @Test
    fun `persistence without band offsets stays backward compatible`() {
        val profile = OpraJson.parseProfile(eqLine)!!
        val legacy = OpraJson.encodeForPersistence(profile, adjustDb = 0f)
        val restored = OpraJson.decodeFromPersistence(legacy)!!
        assertTrue("older payloads decode to no per-band trims", restored.bandOffsets.isEmpty())
    }
}

class OpraProfileConverterTest {

    @Test
    fun `converts shelf bands with declared approximation`() {
        val profile = OpraEqProfile(
            id = "test", productId = "x", author = "K", details = null, link = null,
            preampDb = -5f,
            bands = listOf(
                OpraBand(OpraBandType.LOW_SHELF, 105f, -1.4f, 0.7f),
                OpraBand(OpraBandType.PEAK_DIP, 170f, -1.9f, 1.47f),
                OpraBand(OpraBandType.HIGH_SHELF, 10000f, 2.2f, 0.7f)
            )
        )
        val plan = OpraProfileConverter.convert(profile)!!
        assertEquals(-5f, plan.preampDb)
        assertEquals(3, plan.bands.size)
        // shelf -> peaking at the same corner, using the calibrated constants (A1)
        assertEquals(OpraProfileConverter.SHELF_APPROX_Q, plan.bands[0].q)
        assertEquals(-1.4f * OpraProfileConverter.SHELF_APPROX_GAIN_SCALE, plan.bands[0].gainDb, 1e-6f)
        // peak_dip verbatim
        assertEquals(170f, plan.bands[1].frequencyHz)
        assertEquals(1.47f, plan.bands[1].q)
        assertNull("verbatim bands carry no approximation error", plan.bands[1].approximationErrorDb)
        assertNotNull("approximated shelves report their error", plan.bands[0].approximationErrorDb)
        assertEquals(1, plan.conversionNotes.size) // one note: shelf approximation only
        assertTrue(plan.conversionNotes[0].contains("worst-case deviation"))
    }

    @Test
    fun `calibrated constants are the measured dataset optimum`() {
        // Pinned by ShelfApproximationCalibrationTest over the real 24,980 shelving bands.
        assertEquals(1.45f, OpraProfileConverter.SHELF_APPROX_Q)
        assertEquals(0.50f, OpraProfileConverter.SHELF_APPROX_GAIN_SCALE)
    }

    @Test
    fun `every plan discloses that the renderer is peaking-only`() {
        val profile = OpraEqProfile(
            "t", "p", "A", null, null, -1f,
            listOf(OpraBand(OpraBandType.PEAK_DIP, 1000f, 3f, 1f))
        )
        val plan = OpraProfileConverter.convert(profile)!!
        assertEquals(RENDERER_DISCLOSURE, plan.rendererDisclosure)
        assertTrue(plan.rendererDisclosure.contains("band bandwidth"))
        // A peaking-only profile needs no approximation error.
        assertEquals(0f, plan.maxShelfApproximationErrorDb)
    }

    @Test
    fun `high-gain high shelf reports a large approximation error instead of hiding it`() {
        val band = OpraBand(OpraBandType.HIGH_SHELF, 2200f, 14f, 0.71f)
        val profile = OpraEqProfile(
            "t", "p", "A", null, null, 0f,
            listOf(OpraBand(OpraBandType.PEAK_DIP, 1000f, -2f, 1f), band)
        )
        val plan = OpraProfileConverter.convert(profile)!!
        assertTrue(
            "a +14 dB high shelf cannot be one bell; the error must be reported",
            plan.maxShelfApproximationErrorDb > 5f
        )
        assertTrue(plan.conversionNotes[0].contains("worst-case deviation"))
    }

    @Test
    fun `pass-type filters are skipped and disclosed`() {
        val profile = OpraEqProfile(
            id = "x", productId = "p", author = "oratory1990", details = null, link = null,
            preampDb = -3f,
            bands = listOf(
                OpraBand(OpraBandType.PEAK_DIP, 1000f, 1f, 1f),
                OpraBand(OpraBandType.LOW_PASS, 20000f, 0f, null, 24f),
                OpraBand(OpraBandType.BAND_STOP, 5000f, 0f, 0.9f)
            )
        )
        val plan = OpraProfileConverter.convert(profile)!!
        assertEquals(1, plan.bands.size)
        assertTrue(plan.conversionNotes.any { it.contains("unsupported") && it.contains("2") })
    }

    @Test
    fun `excess-band curves are rejected not silently clamped`() {
        val bands = (1..OpraProfileConverter.MAX_BANDS + 1).map {
            OpraBand(OpraBandType.PEAK_DIP, it * 500f, 0f, 1f)
        }
        val profile = OpraEqProfile("x", "p", "A", null, null, -3f, bands)
        assertNull(OpraProfileConverter.convert(profile))
    }

    @Test
    fun `per-band trims and the master trim combine and are disclosed`() {
        val profile = OpraEqProfile(
            "t", "p", "A", null, null, -1f,
            listOf(
                OpraBand(OpraBandType.PEAK_DIP, 1000f, 2f, 1f),
                OpraBand(OpraBandType.PEAK_DIP, 4000f, -3f, 1f)
            )
        )
        val plan = OpraProfileConverter.convert(profile)!!
        val adjusted = OpraProfileConverter.applyUserAdjustments(
            plan,
            masterAdjustDb = 1f,
            bandOffsets = listOf(2f, -1f)
        )
        assertEquals(2f + 2f + 1f, adjusted.bands[0].gainDb, 1e-6f)
        assertEquals(-3f - 1f + 1f, adjusted.bands[1].gainDb, 1e-6f)
        assertEquals(listOf(2f, -1f), adjusted.userBandOffsets)
        assertTrue(adjusted.conversionNotes.any { it.contains("User adjustment: +1.0 dB") })
        assertTrue(adjusted.conversionNotes.any { it.contains("User per-band trims") })
        // The original plan is untouched (pure function).
        assertEquals(2f, plan.bands[0].gainDb, 1e-6f)
    }

    @Test
    fun `band trims are clamped to the documented range`() {
        val band = OpraBand(OpraBandType.PEAK_DIP, 1000f, 0f, 1f)
        val plan = OpraProfileConverter.convert(
            OpraEqProfile("t", "p", "A", null, null, 0f, listOf(band))
        )!!
        val adjusted = OpraProfileConverter.applyUserAdjustments(plan, 0f, listOf(50f))
        assertEquals(6f, adjusted.userBandOffsets[0], 1e-6f)
        assertEquals(6f, adjusted.bands[0].gainDb, 1e-6f)
    }

    @Test
    fun `untouched plan needs no adjustment note`() {
        val band = OpraBand(OpraBandType.PEAK_DIP, 1000f, 2f, 1f)
        val plan = OpraProfileConverter.convert(
            OpraEqProfile("t", "p", "A", null, null, 0f, listOf(band))
        )!!
        val same = OpraProfileConverter.applyUserAdjustments(plan, 0f, emptyList())
        assertEquals(plan.bands, same.bands)
        assertEquals(plan.conversionNotes, same.conversionNotes)
    }

    @Test
    fun `no positive bass peak is double counted with a plan`() {
        val baseline = com.fxsound.audio.engine.DynamicsProcessingTranslator
            .calculateHeadroomCompensationDb(0f, List(10) { 0f }, 0f, null)
        val withPlan = com.fxsound.audio.engine.DynamicsProcessingTranslator
            .calculateHeadroomCompensationDb(
                0f, List(10) { 0f }, 0f,
                com.fxsound.audio.opra.HeadphoneEqPlan(
                    profileId = "p", author = "A", details = null, link = null,
                    preampDb = -12f,
                    bands = listOf(com.fxsound.audio.opra.DspEqBand(40f, -14f, 0.7f)),
                    conversionNotes = emptyList()
                )
            )
        // With a fully negative plan the required headroom is never larger than the default.
        assertTrue(withPlan <= baseline)
    }
}
