package com.fxsound.audio.opra

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OpraSelectionStateTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repository: OpraRepository

    private val sampleProfile = OpraEqProfile(
        id = "test_profile",
        productId = "test_headphone",
        author = "Test Author",
        details = "Test Details",
        link = "https://example.com",
        preampDb = -4.5f,
        bands = listOf(
            OpraBand(
                type = OpraBandType.PEAK_DIP,
                frequency = 100f,
                gainDb = 3.0f,
                q = 1.4f
            )
        )
    )

    @Before
    fun setUp() {
        repository = OpraRepository(baseDir = tmp.newFolder("opra"))
    }

    @Test
    fun testSelectProfileStartsInPendingState() {
        val result = repository.selectProfile(sampleProfile)
        assertTrue(result.success)
        val state = repository.selectionState.value
        assertTrue("Expected Pending state", state is OpraRepository.SelectionUiState.Pending)
        assertEquals(sampleProfile.id, (state as OpraRepository.SelectionUiState.Pending).plan.profileId)
    }

    @Test
    fun testConfirmAppliedTransitionsPendingToApplied() {
        repository.selectProfile(sampleProfile)
        repository.confirmApplied(true)
        val state = repository.selectionState.value
        assertTrue("Expected Applied state", state is OpraRepository.SelectionUiState.Applied)
        assertEquals(sampleProfile.id, (state as OpraRepository.SelectionUiState.Applied).plan.profileId)
    }

    @Test
    fun testTeardownTransitionsAppliedToUnavailable() {
        repository.selectProfile(sampleProfile)
        repository.confirmApplied(true)
        assertTrue(repository.selectionState.value is OpraRepository.SelectionUiState.Applied)

        // Session teardown or attach failure occurs
        val teardownReason = "Renderer torn down"
        repository.confirmApplied(false, teardownReason)

        val state = repository.selectionState.value
        assertTrue("State must transition out of Applied upon teardown", state is OpraRepository.SelectionUiState.Unavailable)
        val unavail = state as OpraRepository.SelectionUiState.Unavailable
        assertEquals(teardownReason, unavail.reason)
        assertEquals(sampleProfile.id, unavail.plan.profileId)
        assertNotNull("Plan must still be preserved for subsequent sessions", state.planOrNull)
    }

    @Test
    fun testFailedRecreationTransitionsToUnavailable() {
        repository.selectProfile(sampleProfile)
        val failReason = "DynamicsProcessing Pre-EQ recreation failed"
        repository.confirmApplied(false, failReason)

        val state = repository.selectionState.value
        assertTrue("Failed recreation must result in Unavailable state", state is OpraRepository.SelectionUiState.Unavailable)
        assertEquals(failReason, (state as OpraRepository.SelectionUiState.Unavailable).reason)
    }

    @Test
    fun testRecoveryTransitionsUnavailableBackToApplied() {
        repository.selectProfile(sampleProfile)
        repository.confirmApplied(false, "Initial rejection")
        assertTrue(repository.selectionState.value is OpraRepository.SelectionUiState.Unavailable)

        // Next session opens and succeeds
        repository.confirmApplied(true)
        assertTrue("Recovery must transition back to Applied", repository.selectionState.value is OpraRepository.SelectionUiState.Applied)
    }
}
