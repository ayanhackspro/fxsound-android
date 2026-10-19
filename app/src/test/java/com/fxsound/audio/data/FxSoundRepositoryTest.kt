package com.fxsound.audio.data

import com.fxsound.audio.model.AudioOutputDeviceType
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.RouteStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FxSoundRepositoryTest {

    @Test
    fun testPowerToggle() {
        FxSoundRepository.setPower(true)
        assertTrue(FxSoundRepository.uiState.value.isPowerOn)

        FxSoundRepository.togglePower()
        assertFalse(FxSoundRepository.uiState.value.isPowerOn)
        assertEquals(RouteStatus.OFF, FxSoundRepository.uiState.value.routeStatus)

        FxSoundRepository.togglePower()
        assertTrue(FxSoundRepository.uiState.value.isPowerOn)
        assertEquals(RouteStatus.READY, FxSoundRepository.uiState.value.routeStatus)
    }

    @Test
    fun testCycleNextPreset() {
        val presets = FxSoundPreset.ALL_PRESETS
        FxSoundRepository.applyPreset(presets[0])
        assertEquals(presets[0].id, FxSoundRepository.uiState.value.currentPresetId)

        for (i in 1 until presets.size) {
            FxSoundRepository.cycleNextPreset()
            assertEquals("Step $i preset cycle", presets[i].id, FxSoundRepository.uiState.value.currentPresetId)
        }

        // Cycling past last preset wraps around to 0
        FxSoundRepository.cycleNextPreset()
        assertEquals("Wrap around to first preset", presets[0].id, FxSoundRepository.uiState.value.currentPresetId)
    }

    @Test
    fun testEffectClamping() {
        FxSoundRepository.setEffect(FxSoundEffectId.FIDELITY, 15.0f)
        assertEquals(10.0f, FxSoundRepository.uiState.value.effects[FxSoundEffectId.FIDELITY] ?: 0f, 0.001f)

        FxSoundRepository.setEffect(FxSoundEffectId.FIDELITY, -3.0f)
        assertEquals(0.0f, FxSoundRepository.uiState.value.effects[FxSoundEffectId.FIDELITY] ?: 0f, 0.001f)
    }

    @Test
    fun testEqBandClamping() {
        FxSoundRepository.setEqBand(0, 20.0f)
        assertEquals(14.0f, FxSoundRepository.uiState.value.eqGains[0], 0.001f)

        FxSoundRepository.setEqBand(0, -25.0f)
        assertEquals(-14.0f, FxSoundRepository.uiState.value.eqGains[0], 0.001f)

        // Invalid index should be ignored
        FxSoundRepository.setEqBand(15, 5.0f)
    }

    @Test
    fun testMetricsIsolationDoesNotModifyUiState() {
        FxSoundRepository.setPower(true)
        val initialUiState = FxSoundRepository.uiState.value

        val dummySpectrum = FloatArray(16) { 0.5f }
        FxSoundRepository.updateMetrics(
            inputPeak = 0.85f,
            outputPeak = 0.92f,
            inputRms = 0.45f,
            outputRms = 0.55f,
            isClipping = false,
            spectrum = dummySpectrum
        )

        // Telemetry updated
        val metrics = FxSoundRepository.metricsState.value
        assertEquals(0.85f, metrics.inputPeak, 0.001f)
        assertEquals(0.92f, metrics.outputPeak, 0.001f)
        assertEquals(0.5f, metrics.spectrumBands[0], 0.001f)

        // Low-frequency UI state MUST remain identical (zero recomposition on sliders/EQ)
        val currentUiState = FxSoundRepository.uiState.value
        assertEquals(initialUiState.isPowerOn, currentUiState.isPowerOn)
        assertEquals(initialUiState.currentPresetId, currentUiState.currentPresetId)
        assertEquals(initialUiState.effects, currentUiState.effects)
    }

    @Test
    fun testOutputDeviceUpdate() {
        FxSoundRepository.setOutputDevice(AudioOutputDeviceType.BLUETOOTH_A2DP)
        assertEquals(AudioOutputDeviceType.BLUETOOTH_A2DP, FxSoundRepository.uiState.value.outputDevice)

        FxSoundRepository.setOutputDevice(AudioOutputDeviceType.USB_HEADSET)
        assertEquals(AudioOutputDeviceType.USB_HEADSET, FxSoundRepository.uiState.value.outputDevice)
    }
}
