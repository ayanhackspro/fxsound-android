package com.fxsound.audio.engine

import com.fxsound.audio.model.EQ_FREQUENCIES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the companion engine's FxSound mapping to the measured evidence.
 *
 * The mapping in `FxSoundResponseTables` is only meaningful if it still matches the
 * measurements it was derived from, so this test replays the committed CSV and checks
 * the tables against it. That makes the translation verifiable rather than merely
 * asserted, and it fails if the tables are hand-edited or the measurements are
 * regenerated with different results.
 */
class FxSoundResponseTablesTest {

    private data class Row(val measurement: String, val effect: String, val setting: String, val key: String, val value: Float)

    private fun loadRows(): List<Row> {
        val csv = File("docs/fx_response/fx_response_measurements.csv")
        assertTrue(
            "The measured DSP response is missing at ${csv.absolutePath}. " +
                "Regenerate it with ./gradlew fxResponseMeasure.",
            csv.exists()
        )
        return csv.readLines()
            .filter { it.startsWith("magnitude,") || it.startsWith("level,") }
            .mapNotNull { line ->
                val parts = line.split(",")
                if (parts.size != 5) return@mapNotNull null
                val value = parts[4].toFloatOrNull() ?: return@mapNotNull null
                Row(
                    measurement = parts[0],
                    effect = parts[1],
                    setting = parts[2],
                    key = parts[3],
                    value = value
                )
            }
    }

    /**
     * The effect's incremental contribution over the DSP's own reference state, which is
     * what the companion engine's flat DynamicsProcessing chain has to reproduce.
     */
    private fun incremental(rows: List<Row>, effect: String, setting: String, frequency: String): Float {
        val reference = rows.first { it.measurement == "magnitude" && it.effect == "REFERENCE" && it.key == frequency }
        val measured = rows.first {
            it.measurement == "magnitude" && it.effect == effect && it.setting == setting && it.key == frequency
        }
        return measured.value - reference.value
    }

    @Test
    fun testBassTableMatchesTheMeasurements() {
        val rows = loadRows()
        for (setting in 0..10) {
            val gains = FxSoundResponseTables.bassBandGainsDb(setting.toFloat())
            for (band in EQ_FREQUENCIES.indices) {
                val frequency = "%.1f".format(EQ_FREQUENCIES[band].toDouble())
                val expected = incremental(rows, "BASS", "$setting.0", frequency)
                assertEquals(
                    "Bass band $band at $frequency, setting $setting, does not match the measurement",
                    expected,
                    gains[band],
                    0.05f
                )
            }
        }
    }

    @Test
    fun testFidelityTableMatchesTheMeasurements() {
        val rows = loadRows()
        for (setting in 0..10) {
            val gains = FxSoundResponseTables.fidelityBandGainsDb(setting.toFloat())
            for (band in EQ_FREQUENCIES.indices) {
                val frequency = "%.1f".format(EQ_FREQUENCIES[band].toDouble())
                val expected = incremental(rows, "FIDELITY", "$setting.0", frequency)
                assertEquals(
                    "Clarity band $band at $frequency, setting $setting, does not match the measurement",
                    expected,
                    gains[band],
                    0.05f
                )
            }
        }
    }

    @Test
    fun testDynamicBoostMakeUpAndKneeMatchTheMeasurements() {
        val rows = loadRows()

        // The reference level response is the Dynamic Boost at setting 0, which is the
        // upstream's own reference state.
        val referenceFlat = rows.first {
            it.measurement == "level" && it.effect == "DYNAMIC" && it.setting == "0.0" && it.key == "-40"
        }.value

        for (setting in 0..10) {
            val label = "$setting.0"
            val flat = rows.first {
                it.measurement == "level" && it.effect == "DYNAMIC" && it.setting == label && it.key == "-40"
            }.value

            assertEquals(
                "Dynamic Boost make-up gain at setting $setting does not match the measurement",
                flat - referenceFlat,
                FxSoundResponseTables.dynamicMakeUpDb(setting.toFloat()),
                0.05f
            )

            // The knee is the highest input level at which the response is still flat.
            var knee = -48
            for (db in -48..0) {
                val value = rows.first {
                    it.measurement == "level" && it.effect == "DYNAMIC" && it.setting == label && it.key == "$db"
                }.value
                if (value >= flat - 0.001f) knee = db
            }

            assertEquals(
                "Dynamic Boost knee at setting $setting does not match the measurement",
                knee.toFloat(),
                FxSoundResponseTables.dynamicKneeDb(setting.toFloat()),
                0.5f
            )
        }
    }

    @Test
    fun testInterpolationStaysBetweenTheMeasuredSettings() {
        for (setting in 0..9) {
            val low = FxSoundResponseTables.bassBandGainsDb(setting.toFloat())
            val high = FxSoundResponseTables.bassBandGainsDb((setting + 1).toFloat())
            val mid = FxSoundResponseTables.bassBandGainsDb(setting + 0.5f)
            for (band in EQ_FREQUENCIES.indices) {
                val lower = minOf(low[band], high[band])
                val upper = maxOf(low[band], high[band])
                assertTrue(
                    "Interpolated bass band $band at setting ${setting + 0.5} is outside the measured range",
                    mid[band] in (lower - 1e-4f)..(upper + 1e-4f)
                )
            }
        }
    }

    @Test
    fun testBassBoostGrowsWithTheControl() {
        var previous = -Float.MAX_VALUE
        for (setting in 0..10) {
            val peak = FxSoundResponseTables.bassBandGainsDb(setting.toFloat()).max()
            assertTrue("The bass boost must not shrink as the control rises", peak >= previous)
            previous = peak
        }
        assertTrue(
            "The maximum bass boost must be substantial",
            FxSoundResponseTables.bassBandGainsDb(10f).max() > 8.0f
        )
    }

    @Test
    fun testClarityOpensTheTopEndAsTheControlRises() {
        val low = FxSoundResponseTables.fidelityBandGainsDb(0f)
        val high = FxSoundResponseTables.fidelityBandGainsDb(10f)
        val topBand = EQ_FREQUENCIES.size - 1
        assertTrue(
            "Clarity must brighten the top end as the control rises",
            high[topBand] > low[topBand] + 5.0f
        )
        assertTrue(
            "Clarity must scoop the mids as the control rises",
            high[5] < low[5]
        )
    }

    @Test
    fun testControlsOutsideTheRangeAreClamped() {
        assertEquals(
            FxSoundResponseTables.bassBandGainsDb(0f).toList(),
            FxSoundResponseTables.bassBandGainsDb(-5f).toList()
        )
        assertEquals(
            FxSoundResponseTables.bassBandGainsDb(10f).toList(),
            FxSoundResponseTables.bassBandGainsDb(99f).toList()
        )
    }
}
