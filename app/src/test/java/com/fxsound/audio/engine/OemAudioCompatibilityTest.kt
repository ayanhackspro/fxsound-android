package com.fxsound.audio.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemAudioCompatibilityTest {

    @Test
    fun testSamsungDetectionAndAdvice() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "samsung",
            brand = "samsung",
            model = "SM-S918B"
        )
        assertEquals(OemBrand.SAMSUNG, advice.brand)
        assertTrue(advice.hasPotentialConflict)
        assertTrue(advice.competingSuites.contains("Dolby Atmos"))
        assertTrue(advice.competingSuites.contains("SoundAlive"))
        assertTrue(advice.recommendation.contains("Disable Dolby Atmos"))
        assertEquals("Dolby Atmos / SoundAlive", advice.brand.conflictingEffectName)
    }

    @Test
    fun testXiaomiDetectionAndAdvice() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "Xiaomi",
            brand = "Redmi",
            model = "2201116SG"
        )
        assertEquals(OemBrand.XIAOMI, advice.brand)
        assertTrue(advice.hasPotentialConflict)
        assertTrue(advice.competingSuites.contains("Dolby Atmos"))
        assertTrue(advice.competingSuites.contains("Mi Sound Enhancer"))
        assertTrue(advice.recommendation.contains("Dolby Atmos to Off or Voice"))
    }

    @Test
    fun testPocoDetection() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "Xiaomi",
            brand = "POCO",
            model = "23049PCD8G"
        )
        assertEquals(OemBrand.XIAOMI, advice.brand)
        assertTrue(advice.hasPotentialConflict)
    }

    @Test
    fun testOnePlusOppoRealmeDetection() {
        val onePlus = OemAudioCompatibility.resolveAdvice(
            manufacturer = "OnePlus",
            brand = "OnePlus",
            model = "CPH2581"
        )
        assertEquals(OemBrand.ONEPLUS_OPPO_REALME, onePlus.brand)
        assertTrue(onePlus.hasPotentialConflict)
        assertTrue(onePlus.competingSuites.contains("Dirac Audio Tuner"))

        val oppo = OemAudioCompatibility.resolveAdvice(
            manufacturer = "OPPO",
            brand = "OPPO",
            model = "CPH2437"
        )
        assertEquals(OemBrand.ONEPLUS_OPPO_REALME, oppo.brand)

        val realme = OemAudioCompatibility.resolveAdvice(
            manufacturer = "Realme",
            brand = "realme",
            model = "RMX3771"
        )
        assertEquals(OemBrand.ONEPLUS_OPPO_REALME, realme.brand)
    }

    @Test
    fun testMotorolaDetection() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "motorola",
            brand = "motorola",
            model = "motorola edge 50 pro"
        )
        assertEquals(OemBrand.MOTOROLA, advice.brand)
        assertTrue(advice.hasPotentialConflict)
        assertTrue(advice.competingSuites.contains("Moto Audio"))
        assertTrue(advice.recommendation.contains("smart routing"))
    }

    @Test
    fun testSonyDetection() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "Sony",
            brand = "Sony",
            model = "XQ-EC54"
        )
        assertEquals(OemBrand.SONY, advice.brand)
        assertTrue(advice.hasPotentialConflict)
        assertTrue(advice.competingSuites.contains("DSEE Ultimate"))
    }

    @Test
    fun testGooglePixelCleanBaseline() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "Google",
            brand = "google",
            model = "Pixel 8 Pro"
        )
        assertEquals(OemBrand.GOOGLE, advice.brand)
        assertFalse(advice.hasPotentialConflict)
        assertTrue(advice.recommendation.contains("AOSP reference audio pipeline"))
    }

    @Test
    fun testGenericDeviceFallback() {
        val advice = OemAudioCompatibility.resolveAdvice(
            manufacturer = "Nothing",
            brand = "Nothing",
            model = "A063"
        )
        assertEquals(OemBrand.GENERIC, advice.brand)
        assertFalse(advice.hasPotentialConflict)
        assertEquals("Nothing", advice.manufacturerName)
    }
}
