#include <jni.h>
#include "fxsound_engine.h"
#include <android/log.h>

#define TAG "FxSoundJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static inline FxSoundEngine* getEngine(jlong handle) {
    return reinterpret_cast<FxSoundEngine*>(handle);
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeCreate(
    JNIEnv* env, jobject thiz) {
    auto* engine = new FxSoundEngine();
    return reinterpret_cast<jlong>(engine);
}

JNIEXPORT jboolean JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeInit(
    JNIEnv* env, jobject thiz, jlong handle, jint sampleRate, jint channels) {
    auto* engine = getEngine(handle);
    if (!engine) return JNI_FALSE;
    return engine->init(sampleRate, channels) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeSetPower(
    JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    auto* engine = getEngine(handle);
    if (engine) engine->setPower(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeIsPowerOn(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = getEngine(handle);
    if (!engine) return JNI_FALSE;
    return engine->isPowerOn() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeSetEffect(
    JNIEnv* env, jobject thiz, jlong handle, jint effectId, jfloat value) {
    auto* engine = getEngine(handle);
    if (engine) engine->setEffect(static_cast<FxSoundEffectId>(effectId), value);
}

JNIEXPORT jfloat JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeGetEffect(
    JNIEnv* env, jobject thiz, jlong handle, jint effectId) {
    auto* engine = getEngine(handle);
    if (!engine) return 0.0f;
    return engine->getEffect(static_cast<FxSoundEffectId>(effectId));
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeSetEqBand(
    JNIEnv* env, jobject thiz, jlong handle, jint bandIndex, jfloat gainDb) {
    auto* engine = getEngine(handle);
    if (engine) engine->setEqBand(bandIndex, gainDb);
}

JNIEXPORT jfloat JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeGetEqBand(
    JNIEnv* env, jobject thiz, jlong handle, jint bandIndex) {
    auto* engine = getEngine(handle);
    if (!engine) return 0.0f;
    return engine->getEqBand(bandIndex);
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeSetMasterGain(
    JNIEnv* env, jobject thiz, jlong handle, jfloat gainDb) {
    auto* engine = getEngine(handle);
    if (engine) engine->setMasterGain(gainDb);
}

JNIEXPORT jfloat JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeGetMasterGain(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = getEngine(handle);
    if (!engine) return 0.0f;
    return engine->getMasterGain();
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeResetEQ(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = getEngine(handle);
    if (engine) engine->resetEQ();
}

JNIEXPORT jint JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeProcessPcm16(
    JNIEnv* env, jobject thiz, jlong handle, jshortArray inBuffer, jshortArray outBuffer, jint numFrames) {
    auto* engine = getEngine(handle);
    if (!engine || !inBuffer || !outBuffer || numFrames <= 0) return 0;

    const int channels = engine->getNumChannels();
    if (channels <= 0) return 0;

    const jsize inLen = env->GetArrayLength(inBuffer);
    const jsize outLen = env->GetArrayLength(outBuffer);
    const jsize requiredSamples = static_cast<jsize>(numFrames) * channels;
    if (inLen < requiredSamples || outLen < requiredSamples) {
        return 0;
    }

    jshort* inPtr = env->GetShortArrayElements(inBuffer, nullptr);
    if (!inPtr) return 0;
    jshort* outPtr = env->GetShortArrayElements(outBuffer, nullptr);
    if (!outPtr) {
        env->ReleaseShortArrayElements(inBuffer, inPtr, JNI_ABORT);
        return 0;
    }

    int processed = engine->processPcm16(
        reinterpret_cast<const int16_t*>(inPtr),
        reinterpret_cast<int16_t*>(outPtr),
        numFrames
    );

    env->ReleaseShortArrayElements(inBuffer, inPtr, JNI_ABORT);
    env->ReleaseShortArrayElements(outBuffer, outPtr, 0);

    return processed;
}

JNIEXPORT jint JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeProcessFloat(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray inBuffer, jfloatArray outBuffer, jint numFrames) {
    auto* engine = getEngine(handle);
    if (!engine || !inBuffer || !outBuffer || numFrames <= 0) return 0;

    const int channels = engine->getNumChannels();
    if (channels <= 0) return 0;

    const jsize inLen = env->GetArrayLength(inBuffer);
    const jsize outLen = env->GetArrayLength(outBuffer);
    const jsize requiredSamples = static_cast<jsize>(numFrames) * channels;
    if (inLen < requiredSamples || outLen < requiredSamples) {
        return 0;
    }

    jfloat* inPtr = env->GetFloatArrayElements(inBuffer, nullptr);
    if (!inPtr) return 0;
    jfloat* outPtr = env->GetFloatArrayElements(outBuffer, nullptr);
    if (!outPtr) {
        env->ReleaseFloatArrayElements(inBuffer, inPtr, JNI_ABORT);
        return 0;
    }

    int processed = engine->processFloat(inPtr, outPtr, numFrames);

    env->ReleaseFloatArrayElements(inBuffer, inPtr, JNI_ABORT);
    env->ReleaseFloatArrayElements(outBuffer, outPtr, 0);

    return processed;
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeGetMetrics(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray outMetrics) {
    auto* engine = getEngine(handle);
    if (!engine || !outMetrics) return;
    if (env->GetArrayLength(outMetrics) < 5) return;

    FxSoundMetrics m;
    engine->getMetrics(&m);

    jfloat arr[5] = { m.inPeak, m.outPeak, m.inRms, m.outRms, m.isClipping ? 1.0f : 0.0f };
    env->SetFloatArrayRegion(outMetrics, 0, 5, arr);
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeGetSpectrum(
    JNIEnv* env, jobject thiz, jlong handle, jfloatArray outBands, jint numBands) {
    auto* engine = getEngine(handle);
    if (!engine || !outBands || numBands <= 0) return;
    if (env->GetArrayLength(outBands) < numBands) return;

    jfloat* ptr = env->GetFloatArrayElements(outBands, nullptr);
    if (!ptr) return;
    engine->getSpectrum(ptr, numBands);
    env->ReleaseFloatArrayElements(outBands, ptr, 0);
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeSetCrossfeed(
    JNIEnv* env, jobject thiz, jlong handle, jboolean enabled) {
    auto* engine = getEngine(handle);
    if (engine) engine->setCrossfeed(enabled == JNI_TRUE);
}

JNIEXPORT jboolean JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeIsCrossfeedEnabled(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = getEngine(handle);
    return (engine && engine->isCrossfeedEnabled()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_fxsound_audio_nativebridge_FxSoundNativeBridge_nativeDestroy(
    JNIEnv* env, jobject thiz, jlong handle) {
    auto* engine = getEngine(handle);
    if (engine) {
        delete engine;
    }
}

} // extern "C"
