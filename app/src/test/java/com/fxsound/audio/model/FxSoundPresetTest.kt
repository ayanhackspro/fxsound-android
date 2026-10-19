package com.fxsound.audio.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FxSoundPresetTest {

    @Test
    fun testAllPresetsHaveTenBands() {
        for (preset in FxSoundPreset.ALL_PRESETS) {
            assertEquals("Preset ${preset.name} must have 10 EQ bands", 10, preset.eqGains.size)
        }
    }

    @Test
    fun testAllPresetGainsAreBounded() {
        for (preset in FxSoundPreset.ALL_PRESETS) {
            for (gain in preset.eqGains) {
                assertTrue("Gain $gain in ${preset.name} must be within -14dB and +14dB", gain in -14.0f..14.0f)
            }
        }
    }

    @Test
    fun testAllPresetEffectsAreBounded() {
        for (preset in FxSoundPreset.ALL_PRESETS) {
            assertTrue("Clarity in ${preset.name} must be in 0..10", preset.clarity in 0.0f..10.0f)
            assertTrue("Ambience in ${preset.name} must be in 0..10", preset.ambience in 0.0f..10.0f)
            assertTrue("Surround in ${preset.name} must be in 0..10", preset.surround in 0.0f..10.0f)
            assertTrue("Dynamic in ${preset.name} must be in 0..10", preset.dynamicBoost in 0.0f..10.0f)
            assertTrue("Bass in ${preset.name} must be in 0..10", preset.bass in 0.0f..10.0f)
        }
    }

    @Test
    fun testFindByIdFallback() {
        val found = FxSoundPreset.findById("music")
        assertEquals("Music", found.name)

        val unknown = FxSoundPreset.findById("non_existent_preset")
        assertEquals("Balanced", unknown.name)
    }
}
