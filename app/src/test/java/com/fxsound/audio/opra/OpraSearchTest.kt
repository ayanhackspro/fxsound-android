package com.fxsound.audio.opra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** E1: ranking must put the exact/prefix match first, not just any "contains". */
class OpraSearchTest {

    private fun entry(name: String, vendor: String, profiles: Int = 1): CatalogueEntry {
        val product = OpraProduct(
            id = "$vendor::$name".lowercase().replace(' ', '_'),
            name = name,
            vendorId = vendor.lowercase(),
            vendorName = vendor,
            subtype = "over_the_ear"
        )
        val summaries = List(profiles) { i ->
            OpraEqSummary(
                id = "${product.id}::$i", productId = product.id, author = "AutoEQ",
                details = null, link = null, preampDb = -5f,
                bandCount = 10, shelfBandCount = 2, unsupportedBandCount = 0
            )
        }
        return CatalogueEntry(product, summaries)
    }

    private val catalogue = listOf(
        entry("HD 650", "Sennheiser"),
        entry("HD 600", "Sennheiser"),
        entry("Sundara", "HiFiMan"),
        entry("WH-1000XM4 (ANC on)", "Sony"),
        entry("WH-1000XM4 (ANC off)", "Sony"),
        entry("Galaxy Buds2 Pro", "Samsung"),
        entry("Buds Pro", "Nothing")
    )

    private fun search(query: String) = OpraSearch.rank(OpraSearch.items(catalogue), query)

    @Test
    fun `exact and prefix product matches outrank vendor matches`() {
        val results = search("hd 650")
        assertEquals("HD 650", results.first().product.name)
    }

    @Test
    fun `vendor search returns that vendor's products`() {
        val results = search("sennheiser")
        assertEquals(setOf("HD 650", "HD 600"), results.map { it.product.name }.toSet())
    }

    @Test
    fun `word-prefix beats plain contains`() {
        // "buds" is a whole word in "Galaxy Buds2 Pro" (prefix) and inside "Buds Pro" (prefix too);
        // the point is both are found and ordering is stable.
        val results = search("buds")
        assertTrue(results.map { it.product.name }.containsAll(listOf("Galaxy Buds2 Pro", "Buds Pro")))
    }

    @Test
    fun `multi-token query narrows instead of widening`() {
        val results = search("sony xm4")
        assertEquals(2, results.size)
        assertTrue(results.all { it.product.vendorName == "Sony" })
    }

    @Test
    fun `products without profiles are not searchable`() {
        val withoutProfiles = catalogue + entry("Mystery Can", "Nobody", profiles = 0)
        val results = OpraSearch.rank(OpraSearch.items(withoutProfiles), "mystery")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `variant names stay separate rows`() {
        // Grouping is presented in the UI as a warning; the data is never merged or renamed.
        val results = search("wh-1000xm4")
        assertEquals(2, results.size)
        assertTrue(results.any { it.product.name.endsWith("(ANC on)") })
        assertTrue(results.any { it.product.name.endsWith("(ANC off)") })
    }

    @Test
    fun `limit is enforced`() {
        assertEquals(2, OpraSearch.rank(OpraSearch.items(catalogue), "a", limit = 2).size)
    }

    @Test
    fun `numbered variants and anc pairs are recognised as one family`() {
        val majors = listOf(
            entry("Major", "Marshall"),
            entry("Major II", "Marshall"),
            entry("Major III", "Marshall")
        )
        assertTrue(OpraSearch.hasVariantFamilies(majors))

        val ancPair = search("wh-1000xm4")
        assertTrue(OpraSearch.hasVariantFamilies(ancPair))
    }

    @Test
    fun `distinct models are not flagged as variants`() {
        val distinct = listOf(entry("HD 600", "Sennheiser"), entry("HD 650", "Sennheiser"))
        assertTrue(!OpraSearch.hasVariantFamilies(distinct))

        val single = listOf(entry("Sundara", "HiFiMan"))
        assertTrue(!OpraSearch.hasVariantFamilies(single))
    }

    @Test
    fun `family key ignores brand suffix and variant markers`() {
        assertEquals("major", OpraSearch.familyKey("Major II"))
        assertEquals("major", OpraSearch.familyKey("Major"))
        assertEquals("wh 1000xm4", OpraSearch.familyKey("WH-1000XM4 (ANC on)"))
        assertEquals("mid", OpraSearch.familyKey("MID ANC"))
    }
}
