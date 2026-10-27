package com.fxsound.audio.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Surround slider must only be movable when moving it can change the audio. These tests
 * pin that mapping so a future change cannot re-enable a dead control.
 */
class FxStageReportTest {

    @Test
    fun `surround is adjustable when the virtualizer is unknown or active`() {
        assertTrue(FxStageReport().isSurroundAdjustable)
        assertTrue(
            FxStageReport(virtualizer = StageState.ACTIVE).isSurroundAdjustable
        )
    }

    @Test
    fun `surround is locked for every non-working state`() {
        val locked = listOf(
            StageState.FIXED,
            StageState.BYPASSED,
            StageState.UNSUPPORTED,
            StageState.FAILED
        )
        for (state in locked) {
            assertFalse(
                "Virtualizer $state must lock the Surround slider",
                FxStageReport(virtualizer = state).isSurroundAdjustable
            )
            assertNotNull(
                "Virtualizer $state must explain why the slider is locked",
                FxStageReport(virtualizer = state).virtualizerUnavailableReason()
            )
        }
    }

    @Test
    fun `working states have no warning text`() {
        assertNull(FxStageReport(virtualizer = StageState.ACTIVE).virtualizerUnavailableReason())
        assertNull(FxStageReport().virtualizerUnavailableReason())
    }
}
