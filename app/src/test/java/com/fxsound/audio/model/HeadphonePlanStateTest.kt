package com.fxsound.audio.model

import com.fxsound.audio.opra.DspEqBand
import com.fxsound.audio.opra.HeadphoneEqPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B2: "APPLIED" must expire with the renderer. The plan stays selected (so it can attach
 * again on the next session) but the status flags must never claim a stale success.
 */
class HeadphonePlanStateTest {

    private val plan = HeadphoneEqPlan(
        profileId = "p", author = "A", details = null, link = null,
        preampDb = -6f,
        bands = listOf(DspEqBand(105f, -2f, 0.7f)),
        conversionNotes = emptyList()
    )

    @Test
    fun `attach confirmation flips the status only`() {
        val selected = FxSoundUiState(headphonePlan = plan)
        assertFalse(selected.isHeadphonePlanApplied)

        val applied = selected.withHeadphoneAttachResult(true)
        assertTrue(applied.isHeadphonePlanApplied)
        assertSame("the plan itself must not be replaced", plan, applied.headphonePlan)

        // Idempotent: repeated confirmations do not disturb anything.
        assertEquals(true, applied.withHeadphoneAttachResult(true).isHeadphonePlanApplied)
    }

    @Test
    fun `session teardown clears APPLIED but keeps the selection pending`() {
        val applied = FxSoundUiState(headphonePlan = plan, isHeadphonePlanApplied = true)

        val tornDown = applied.withHeadphoneAttachResult(false)
        assertFalse("no renderer is attached any more, so APPLIED is a lie", tornDown.isHeadphonePlanApplied)
        assertNotNull("the profile stays selected and re-applies on the next session", tornDown.headphonePlan)
        assertEquals(plan.profileId, tornDown.headphonePlan!!.profileId)
    }

    @Test
    fun `rejected attach never reports applied`() {
        val selected = FxSoundUiState(headphonePlan = plan)
        assertFalse(selected.withHeadphoneAttachResult(false).isHeadphonePlanApplied)
    }
}
