package com.fxsound.audio.nativebridge

import android.util.Log

/**
 * JNI Bridge to authentic FxSound C++ DSP Engine.
 */
class FxSoundNativeBridge {

    companion object {
        private const val TAG = "FxSoundNativeBridge"
        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("fxsound_dsp")
                isLibraryLoaded = true
                Log.i(TAG, "Native library 'fxsound_dsp' loaded successfully.")
            } catch (e: UnsatisfiedLinkError) {
                Log.w(TAG, "Could not load 'fxsound_dsp' native library: ${e.message}")
                isLibraryLoaded = false
            }
        }

        fun isNativeAvailable(): Boolean = isLibraryLoaded
    }

    private var nativeHandle: Long = 0

    init {
        if (isLibraryLoaded) {
            nativeHandle = nativeCreate()
        }
    }

    fun init(sampleRate: Int, channels: Int): Boolean {
        if (nativeHandle == 0L) return false
        return nativeInit(nativeHandle, sampleRate, channels)
    }

    fun setPower(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetPower(nativeHandle, enabled)
        }
    }

    fun isPowerOn(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeIsPowerOn(nativeHandle)
    }

    fun setEffect(effectId: Int, value: Float) {
        if (nativeHandle != 0L) {
            nativeSetEffect(nativeHandle, effectId, value.coerceIn(0f, 10f))
        }
    }

    fun getEffect(effectId: Int): Float {
        if (nativeHandle == 0L) return 0f
        return nativeGetEffect(nativeHandle, effectId)
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        if (nativeHandle != 0L) {
            nativeSetEqBand(nativeHandle, bandIndex, gainDb.coerceIn(-14f, 14f))
        }
    }

    fun getEqBand(bandIndex: Int): Float {
        if (nativeHandle == 0L) return 0f
        return nativeGetEqBand(nativeHandle, bandIndex)
    }

    fun setMasterGain(gainDb: Float) {
        if (nativeHandle != 0L) {
            nativeSetMasterGain(nativeHandle, gainDb)
        }
    }

    fun getMasterGain(): Float {
        if (nativeHandle == 0L) return 0f
        return nativeGetMasterGain(nativeHandle)
    }

    /** Flattens all 31 upstream graphic EQ bands to 0 dB in every state storage. */
    fun resetEQ() {
        if (nativeHandle != 0L) {
            nativeResetEQ(nativeHandle)
        }
    }

    fun processPcm16(inBuf: ShortArray, outBuf: ShortArray, numFrames: Int): Int {
        if (nativeHandle == 0L) return 0
        return nativeProcessPcm16(nativeHandle, inBuf, outBuf, numFrames)
    }

    fun processFloat(inBuf: FloatArray, outBuf: FloatArray, numFrames: Int): Int {
        if (nativeHandle == 0L) return 0
        return nativeProcessFloat(nativeHandle, inBuf, outBuf, numFrames)
    }

    fun getMetrics(outMetrics: FloatArray) {
        if (nativeHandle != 0L && outMetrics.size >= 5) {
            nativeGetMetrics(nativeHandle, outMetrics)
        }
    }

    fun getSpectrum(outBands: FloatArray, numBands: Int) {
        if (nativeHandle != 0L && outBands.size >= numBands) {
            nativeGetSpectrum(nativeHandle, outBands, numBands)
        }
    }

    fun setCrossfeed(enabled: Boolean) {
        if (nativeHandle != 0L) {
            nativeSetCrossfeed(nativeHandle, enabled)
        }
    }

    fun isCrossfeedEnabled(): Boolean {
        if (nativeHandle == 0L) return false
        return nativeIsCrossfeedEnabled(nativeHandle)
    }

    fun release() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
    }

    protected fun finalize() {
        release()
    }

    // Native external declarations
    private external fun nativeCreate(): Long
    private external fun nativeInit(handle: Long, sampleRate: Int, channels: Int): Boolean
    private external fun nativeSetPower(handle: Long, enabled: Boolean)
    private external fun nativeIsPowerOn(handle: Long): Boolean
    private external fun nativeSetEffect(handle: Long, effectId: Int, value: Float)
    private external fun nativeGetEffect(handle: Long, effectId: Int): Float
    private external fun nativeSetEqBand(handle: Long, bandIndex: Int, gainDb: Float)
    private external fun nativeGetEqBand(handle: Long, bandIndex: Int): Float
    private external fun nativeSetMasterGain(handle: Long, gainDb: Float)
    private external fun nativeGetMasterGain(handle: Long): Float
    private external fun nativeResetEQ(handle: Long)
    private external fun nativeProcessPcm16(handle: Long, inBuf: ShortArray, outBuf: ShortArray, numFrames: Int): Int
    private external fun nativeProcessFloat(handle: Long, inBuf: FloatArray, outBuf: FloatArray, numFrames: Int): Int
    private external fun nativeGetMetrics(handle: Long, outMetrics: FloatArray)
    private external fun nativeGetSpectrum(handle: Long, outBands: FloatArray, numBands: Int)
    private external fun nativeSetCrossfeed(handle: Long, enabled: Boolean)
    private external fun nativeIsCrossfeedEnabled(handle: Long): Boolean
    private external fun nativeDestroy(handle: Long)
}
