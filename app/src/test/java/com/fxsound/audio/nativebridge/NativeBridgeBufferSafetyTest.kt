package com.fxsound.audio.nativebridge
 
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
 
class NativeBridgeBufferSafetyTest {
 
    @Test
    fun testUninitializedBridgeFailsSafely() {
        val bridge = FxSoundNativeBridge()
        assertFalse(bridge.isInitialized)
        val inBuf = ShortArray(256)
        val outBuf = ShortArray(256)
        val processed = bridge.processPcm16(inBuf, outBuf, 128)
        assertEquals(0, processed)
    }
 
    @Test
    fun testInvalidFrameCountFailsSafely() {
        val bridge = FxSoundNativeBridge()
        val inBuf = FloatArray(256)
        val outBuf = FloatArray(256)
        assertEquals(0, bridge.processFloat(inBuf, outBuf, 0))
        assertEquals(0, bridge.processFloat(inBuf, outBuf, -10))
    }
 
    @Test
    fun testUndersizedBufferFailsSafely() {
        val bridge = FxSoundNativeBridge()
        val inBuf = ShortArray(10)
        val outBuf = ShortArray(10)
        // Requesting 100 frames with 2 channels requires 200 samples, but buffer is only 10
        val processed = bridge.processPcm16(inBuf, outBuf, 100)
        assertEquals(0, processed)
    }
}
