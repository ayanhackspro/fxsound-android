package com.fxsound.audio.data

import com.fxsound.audio.model.FxSoundPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the custom-preset JSON codec. The DataStore layer feeds this codec the raw
 * stored string, so round-tripping and malformed-input handling are the behaviours that
 * matter: a corrupted payload must yield an empty or partial list, never a crash.
 */
class PresetCodecTest {

    private val sample = FxSoundPreset(
        id = "user_123",
        name = "My Sennheisers",
        description = "Headphone tuning",
        clarity = 8.5f,
        ambience = 2.0f,
        surround = 3.0f,
        dynamicBoost = 6.0f,
        bass = 7.0f,
        eqGains = listOf(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, -5.0f, -4.0f, -3.0f, -2.0f, -1.0f)
    )

    @Test
    fun roundTripPreservesAllFields() {
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(sample)))
        assertEquals(1, decoded.size)
        val p = decoded[0]
        assertEquals(sample.id, p.id)
        assertEquals(sample.name, p.name)
        assertEquals(sample.description, p.description)
        assertEquals(sample.clarity, p.clarity, 0.0001f)
        assertEquals(sample.ambience, p.ambience, 0.0001f)
        assertEquals(sample.surround, p.surround, 0.0001f)
        assertEquals(sample.dynamicBoost, p.dynamicBoost, 0.0001f)
        assertEquals(sample.bass, p.bass, 0.0001f)
        assertEquals(sample.eqGains.size, p.eqGains.size)
        sample.eqGains.forEachIndexed { i, g ->
            assertEquals(g, p.eqGains[i], 0.0001f)
        }
    }

    @Test
    fun multiplePresetsRoundTripInOrder() {
        val second = sample.copy(id = "user_456", name = "Second")
        val decoded = PresetCodec.decode(PresetCodec.encode(listOf(sample, second)))
        assertEquals(2, decoded.size)
        assertEquals("user_123", decoded[0].id)
        assertEquals("user_456", decoded[1].id)
    }

    @Test
    fun nullAndBlankYieldEmptyList() {
        assertTrue(PresetCodec.decode(null).isEmpty())
        assertTrue(PresetCodec.decode("").isEmpty())
        assertTrue(PresetCodec.decode("   ").isEmpty())
    }

    @Test
    fun garbageYieldsEmptyList() {
        assertTrue(PresetCodec.decode("not json at all").isEmpty())
        assertTrue(PresetCodec.decode("{\"broken\":[}").isEmpty())
    }

    @Test
    fun malformedEntriesAreSkippedButValidOnesSurvive() {
        // First entry is missing the name; second is a complete preset.
        val json = "[{\"id\":\"user_bad\",\"bass\":9.0}," +
                "{\"id\":\"user_ok\",\"name\":\"Ok\",\"clarity\":5.0,\"ambience\":2.0," +
                "\"surround\":3.0,\"dynamic_boost\":4.0,\"bass\":4.0," +
                "\"eq_gains\":[0,0,0,0,0,0,0,0,0,0]}]"
        val decoded = PresetCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals("user_ok", decoded[0].id)
    }

    @Test
    fun wrongEqBandCountIsRejected() {
        val json = "[{\"id\":\"user_bad\",\"name\":\"Bad\",\"clarity\":5.0," +
                "\"ambience\":2.0,\"surround\":3.0,\"dynamic_boost\":4.0,\"bass\":4.0," +
                "\"eq_gains\":[0,0,0,0,0]}]"
        assertTrue(PresetCodec.decode(json).isEmpty())
    }

    @Test
    fun outOfRangeValuesAreClampedNotFatal() {
        val json = "[{\"id\":\"user_x\",\"name\":\"X\",\"clarity\":99.0,\"ambience\":-7.0," +
                "\"surround\":3.0,\"dynamic_boost\":4.0,\"bass\":4.0," +
                "\"eq_gains\":[99,0,0,0,0,0,0,0,-99,0]}]"
        val decoded = PresetCodec.decode(json)
        assertEquals(1, decoded.size)
        assertEquals(10.0f, decoded[0].clarity, 0.0001f)
        assertEquals(0.0f, decoded[0].ambience, 0.0001f)
        assertEquals(14.0f, decoded[0].eqGains[0], 0.0001f)
        assertEquals(-14.0f, decoded[0].eqGains[8], 0.0001f)
    }
}
