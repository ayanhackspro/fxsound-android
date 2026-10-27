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
        val json = OpraJson.encodeForPersistence(profile, adjustDb = -1.5f)
        val restored = OpraJson.decodeFromPersistence(json)!!
        assertEquals(profile.id, restored.profile.id)
        assertEquals(profile.productId, restored.profile.productId)
        assertEquals(profile.author, restored.profile.author)
        assertEquals(profile.bands.size, restored.profile.bands.size)
        assertEquals(-1.5f, restored.adjustDb)
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
        // shelf -> peaking at same corner, Q = 0.6, gain scaled by 0.8
        assertEquals(0.6f, plan.bands[0].q)
        assertEquals(-1.4f * OpraProfileConverter.SHELF_APPROX_GAIN_SCALE, plan.bands[0].gainDb)
        // peak_dip verbatim
        assertEquals(170f, plan.bands[1].frequencyHz)
        assertEquals(1.47f, plan.bands[1].q)
        assertEquals(1, plan.conversionNotes.size) // one note: shelf approximation only
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
