package com.fxsound.audio.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the honest-status invariant required by AGENTS.md rule 11 and
 * docs/UI_UX_SPEC.md ("Honest status model"): the UI must never present a
 * processing state unless audio is genuinely being processed through a
 * verified route.
 */
class RouteStatusTest {

    @Test
    fun testPartialRouteIsNotAProcessingState() {
        // Audio may be playing, but only the global mix is attached and the source may be
        // bypassing it, so the app cannot claim to be processing that audio.
        assertFalse(
            "PARTIAL must not render as a processing state",
            RouteStatus.PARTIAL.isProcessing
        )
    }

    @Test
    fun testOnlyVerifiedRoutesAreProcessingStates() {
        val processing = RouteStatus.entries.filter { it.isProcessing }
        assertTrue(
            "Only a genuinely attached session or the app-owned player may be processing",
            processing.toSet() == setOf(
                RouteStatus.CONNECTED_SPOTIFY,
                RouteStatus.CONNECTED_PLAYER,
                RouteStatus.PLAYING_HIFI
            )
        )
    }

    @Test
    fun testNonProcessingStatesCoverTheUnavailableCases() {
        val nonProcessing = RouteStatus.entries.filter { !it.isProcessing }
        assertTrue(
            "Bypassed, idle, partial, unavailable and error states must all be non-processing",
            nonProcessing.containsAll(
                listOf(
                    RouteStatus.OFF,
                    RouteStatus.READY,
                    RouteStatus.PARTIAL,
                    RouteStatus.UNAVAILABLE,
                    RouteStatus.ERROR
                )
            )
        )
    }

    @Test
    fun testEveryRouteStatusHasUserFacingCopy() {
        for (status in RouteStatus.entries) {
            assertTrue("RouteStatus.$status must have non-blank label", status.label.isNotBlank())
        }
    }
}
