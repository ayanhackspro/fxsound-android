package com.fxsound.audio.opra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Full-dataset validation harness. Only runs when OPRA_DATASET_PATH points at the real
 * `database_v1.jsonl` (the file is never committed — CC BY-SA 4.0 redistribution of the
 * dataset is still under review; see ADR-028).
 */
class OpraDatasetSmokeTest {

    private fun dataset(): File? {
        val path = System.getenv("OPRA_DATASET_PATH") ?: return null
        val f = File(path)
        return if (f.exists()) f else null
    }

    @Test
    fun `real dump indexes, converts and reports counts`() {
        val file = dataset()
        assumeTrue("OPRA_DATASET_PATH not set — skipping full-dataset validation", file != null)

        var offset = 0L
        val lines = file!!.readLines()
        val result = OpraJson.index(
            sequence {
                for (line in lines) {
                    yield(offset to line)
                    offset += line.toByteArray(Charsets.UTF_8).size + 1
                }
            }
        )

        val vendors = result.catalogue.vendors.size
        val products = result.catalogue.entries.size
        val productsWithProfiles = result.catalogue.entries.values.count { it.summaries.isNotEmpty() }
        val profiles = result.profileOffsets.size

        var converted = 0
        var rejected = 0
        var withNotes = 0
        var maxPositivePreamp = -999f
        var minPreamp = 999f

        for (entry in result.catalogue.entries.values) {
            for (summary in entry.summaries) {
                val profile = OpraJson.parseProfile(readLineAt(file, result.profileOffsets[summary.id]!!))
                if (profile == null) {
                    rejected++
                    continue
                }
                val plan = OpraProfileConverter.convert(profile)
                if (plan == null) {
                    rejected++
                } else {
                    converted++
                    if (plan.conversionNotes.isNotEmpty()) withNotes++
                    maxPositivePreamp = maxOf(maxPositivePreamp, plan.preampDb)
                    minPreamp = minOf(minPreamp, plan.preampDb)
                    assertTrue(plan.bands.isNotEmpty())
                    assertTrue(plan.bands.size <= OpraProfileConverter.MAX_BANDS)
                }
            }
        }

        println("OPRA dataset report: vendors=$vendors products=$products " +
            "productsWithProfiles=$productsWithProfiles profiles=$profiles " +
            "converted=$converted rejected=$rejected withNotes=$withNotes " +
            "preampRange=[$minPreamp, $maxPositivePreamp] skippedLines=${result.skippedLines}")

        assertTrue("expected >= 600 vendors", vendors >= 600)
        assertTrue("expected >= 6000 products", products >= 6000)
        assertTrue("expected >= 6000 products with profiles", productsWithProfiles >= 6000)
        assertTrue("expected >= 12000 profiles", profiles >= 12000)
        assertTrue("expected nearly every profile to convert", converted >= profiles - 50)
        assertEquals(0, result.skippedLines)

        // Proof-of-concept models spread across vendors/architectures.
        val poc = listOf(
            "sony::wh_1000xm4_anc_on", "sony::wh_1000xm5", "sennheiser::hd_650",
            "sennheiser::hd_600", "sennheiser::momentum_4_wireless", "apple::airpods_pro_2_60db_anc",
            "samsung::galaxy_buds2_pro", "audeze::lcd_5", "moondrop::variations", "moondrop::aria_2021",
            "hifiman::sundara", "hifiman::edition_xs", "beyerdynamic::dt_770_pro",
            "beyerdynamic::dt_990_pro_black_edition", "akg::k371bt", "sennheiser::hd_6xx",
            "focal::clear_mg", "focal::elex", "sennheiser::hd_800_s",
            "audio_technica::ath_m50xbt2", "hifiman::he400se", "moondrop::blessing_2",
            "sony::wh_xb910n", "akg::q701_quincy_jones", "dan_clark_audio::ether_2"
        )
        for (id in poc) {
            val entry = result.catalogue.entries[id]
            assertTrue("PoC model missing from dump: $id", entry != null)
            assertTrue("PoC model has no profiles: $id", entry!!.summaries.isNotEmpty())
            val profile = OpraJson.parseProfile(readLineAt(file, result.profileOffsets[entry.summaries[0].id]!!))
            assertTrue("PoC profile did not convert: $id", profile != null && OpraProfileConverter.convert(profile) != null)
        }
        println("PoC coverage: ${poc.size} models, all present and convertible")
    }

    private fun readLineAt(file: File, offset: Long): String {
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(offset)
            return raf.readLine()
        }
    }
}
