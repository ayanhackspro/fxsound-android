package com.fxsound.audio.opra

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D3: a small, attributed fixture (41 verbatim lines: 10 vendors, 12 products, 19 EQ
 * profiles) so parsing and conversion are regression-tested in CI without the 12.6 MB
 * dump. See docs/OPRA_NOTICE.md for attribution and the modification notice
 * (CC BY-SA 4.0).
 */
class OpraFixtureTest {

    private fun fixture(): Pair<OpraJson.IndexResult, Map<Long, String>> {
        val stream = javaClass.getResourceAsStream("/opra_fixture.jsonl")
            ?: error("opra_fixture.jsonl is missing from test resources")
        val lines = mutableListOf<Pair<Long, String>>()
        var offset = 0L
        stream.use { input ->
            val line = ByteArrayOutputStream(4096)
            var lineBytes = 0L
            while (true) {
                val b = input.read()
                if (b == -1) {
                    if (lineBytes > 0) lines.add(offset to String(line.toByteArray(), Charsets.UTF_8))
                    break
                }
                if (b == '\n'.code) {
                    lines.add(offset to String(line.toByteArray(), Charsets.UTF_8))
                    // Byte-exact, so CRLF fixtures keep their offsets valid.
                    offset += lineBytes + 1
                    line.reset()
                    lineBytes = 0
                } else {
                    lineBytes++
                    if (b != '\r'.code) line.write(b)
                }
            }
        }
        val texts = lines.associate { it.first to it.second }
        return OpraJson.index(lines.asSequence()) to texts
    }

    @Test
    fun `fixture indexes exactly what the dataset contains`() {
        val (result, _) = fixture()
        assertEquals("vendors", 10, result.catalogue.vendors.size)
        assertEquals("products", 12, result.catalogue.entries.size)
        assertEquals("profiles", 19, result.profileOffsets.size)
        assertEquals("malformed lines", 0, result.skippedLines)
        // Every product in the fixture has at least one profile.
        assertTrue(result.catalogue.entries.values.all { it.summaries.isNotEmpty() })
        // Named creators are carried through, never replaced (attribution requirement).
        val authors = result.catalogue.entries.values.flatMap { it.summaries }.map { it.author }.toSet()
        assertTrue("expected real creator names, got $authors", authors.contains("AutoEQ"))
        assertTrue("expected real creator names, got $authors", authors.contains("oratory1990"))
    }

    @Test
    fun `every fixture profile converts and declares its approximation`() {
        val (result, texts) = fixture()
        var converted = 0
        var withShelves = 0
        for ((id, offset) in result.profileOffsets) {
            val profile = OpraJson.parseProfile(texts[offset]!!)
            assertTrue("profile $id did not parse", profile != null)
            val plan = OpraProfileConverter.convert(profile!!)
            assertTrue("profile $id did not convert", plan != null)
            assertEquals("plan id must match the published id", id, plan!!.profileId)
            assertEquals(
                "every plan must carry the renderer disclosure",
                RENDERER_DISCLOSURE, plan.rendererDisclosure
            )
            if (plan.maxShelfApproximationErrorDb > 0f) {
                withShelves++
                assertTrue(
                    "shelf error must be reported, not hidden",
                    plan.conversionNotes.any { it.contains("worst-case deviation") }
                )
            }
            converted++
        }
        assertEquals(19, converted)
        assertTrue("the fixture should exercise the shelf path", withShelves > 0)
    }

    @Test
    fun `conversion is deterministic and repeatable`() {
        val (result, texts) = fixture()
        val offset = result.profileOffsets.values.first()
        val profile = OpraJson.parseProfile(texts[offset]!!)!!
        val first = OpraProfileConverter.convert(profile)!!
        val second = OpraProfileConverter.convert(profile)!!
        assertEquals(first, second)
    }
}
