package com.fxsound.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The platform `Equalizer` fallback has device-owned bands, so these tests pin the mapping
 * from the 10 ISO sliders onto them. A 5-band OEM equalizer at 60/230/910/3600/14000 Hz is
 * used because it is the case where "the top faders do nothing" was reported.
 */
class PlatformEqMapperTest {

    private val flat = List(10) { 0.0f }

    @Test
    fun isoCentresMapExactly() {
        val gains = listOf(-14f, -7f, -3f, 0f, 3f, 6f, 2f, 1f, 9f, 12f)
        val freqs = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        for ((i, f) in freqs.withIndex()) {
            assertEquals("centre $f Hz", gains[i], PlatformEqMapper.gainAt(f, gains), 0.001f)
        }
    }

    @Test
    fun topSliderStillReachesTheTopOemBand() {
        // 16k slider at +10 dB, everything else flat. The OEM top band is 14 kHz, which sits
        // 0.807 octaves above 8 kHz, so a linear-in-hertz ramp (0.75) would under-deliver.
        val gains = flat.toMutableList().also { it[9] = 10.0f }
        val atTopBand = PlatformEqMapper.gainAt(14000, gains)
        assertEquals(8.07f, atTopBand, 0.02f)
        // And it is not silently zero, which was the reported symptom.
        assertTrue("14 kHz should follow the 16k slider", atTopBand > 7.0f)
    }

    @Test
    fun bandsAreAveragedWhenTwoSlidersShareOneOemBand() {
        // Equal and opposite: sharing one band must cancel, not pick a winner.
        val gains = flat.toMutableList().also { it[8] = 6.0f; it[9] = -6.0f }
        val atTopBand = PlatformEqMapper.gainAt(14000, gains)
        assertEquals(6.0f * (1f - 0.807f) + (-6.0f) * 0.807f, atTopBand, 0.05f)
    }

    @Test
    fun outOfRangeFrequenciesClampToTheEndSliders() {
        val gains = flat.toMutableList().also { it[0] = -5.0f; it[9] = 7.0f }
        assertEquals(-5.0f, PlatformEqMapper.gainAt(20, gains), 0.001f)
        assertEquals(7.0f, PlatformEqMapper.gainAt(20000, gains), 0.001f)
    }

    @Test
    fun millibelsAreClampedToTheDeviceRange() {
        assertEquals(1400, PlatformEqMapper.millibels(14.0f, (-1500).toShort(), 1500.toShort()).toInt())
        assertEquals(1500, PlatformEqMapper.millibels(30.0f, (-1500).toShort(), 1500.toShort()).toInt())
        assertEquals(-1500, PlatformEqMapper.millibels(-30.0f, (-1500).toShort(), 1500.toShort()).toInt())
        assertEquals(0, PlatformEqMapper.millibels(0.0f, (-1500).toShort(), 1500.toShort()).toInt())
    }
}
