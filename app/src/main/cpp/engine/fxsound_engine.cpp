#include "fxsound_engine.h"
#include "DfxDsp.h"
// Fails the build if the upstream DSP parameter protocol is ever misaligned again.
#include "dfxp_param_layout.h"
#include <algorithm>
#include <cmath>
#include <cstring>

static inline float pcm16ToFloat(int16_t v) {
    return static_cast<float>(v) / 32768.0f;
}

static inline int16_t floatToPcm16(float v) {
    float clamped = std::clamp(v, -1.0f, 1.0f);
    int32_t val = static_cast<int32_t>(clamped * 32767.0f);
    return static_cast<int16_t>(std::clamp(val, -32768, 32767));
}

FxSoundEngine::FxSoundEngine()
    : dspCore_(nullptr),
      sampleRate_(48000),
      numChannels_(2),
      isEnabled_(true),
      currentInPeak_(0.0f),
      currentOutPeak_(0.0f),
      currentInRms_(0.0f),
      currentOutRms_(0.0f),
      isClipping_(false),
      targetMasterGainDb_(0.0f),
      smoothedMasterGainDb_(0.0f),
      isCrossfeedEnabled_(false),
      crossfeedB0_(0.0f),
      crossfeedA1_(0.0f),
      crossfeedHistoryL_(0.0f),
      crossfeedHistoryR_(0.0f),
      crossfeedWasEnabled_(false) {
    for (int i = 0; i < FXSOUND_NUM_EFFECTS; ++i) {
        targetEffectValues_[i] = 5.0f;
        smoothedEffectValues_[i] = 5.0f;
    }
    for (int i = 0; i < 10; ++i) {
        targetEqBands_[i] = 0.0f;
        smoothedEqBands_[i] = 0.0f;
    }
}

FxSoundEngine::~FxSoundEngine() {
    reset();
}

bool FxSoundEngine::init(int sampleRate, int numChannels) {
    reset();

    sampleRate_ = (sampleRate > 0) ? sampleRate : 48000;
    numChannels_ = (numChannels > 0 && numChannels <= 8) ? numChannels : 2;

    // Preallocate scratch buffers (max expected buffer 8192 frames * channels). Only the
    // 16-bit entry point uses them; the float path runs straight through the core.
    size_t preallocSize = static_cast<size_t>(MAX_FRAMES_PER_CHUNK * numChannels_);
    scratchFloatIn_.resize(preallocSize);
    scratchFloatOut_.resize(preallocSize);

    // Calculate BS2B crossfeed filter coefficients (corner frequency ~700 Hz)
    float fc = 700.0f;
    float omega = 2.0f * 3.14159265358979323846f * fc / static_cast<float>(sampleRate_);
    crossfeedB0_ = omega / (1.0f + omega);
    crossfeedA1_ = (1.0f - omega) / (1.0f + omega);
    crossfeedHistoryL_ = 0.0f;
    crossfeedHistoryR_ = 0.0f;
    crossfeedWasEnabled_ = false;

    dspCore_ = new DfxDsp();

    // The upstream core processes in 32-bit float: `realtype` is defined as float, and
    // dfxpUniversalModifySamples has a bps == 32 branch that runs the real-type kernel
    // with no integer conversion at all. Configuring the format once, here, means the
    // internal pipeline is float end to end and no format switch ever happens on the
    // audio thread.
    dspCore_->setSignalFormat(32, numChannels_, sampleRate_, 32);
    dspCore_->powerOn(true);

    // Apply baseline effects
    dspCore_->setEffectValue(DfxDsp::Fidelity, targetEffectValues_[FXSOUND_EFFECT_FIDELITY]);
    dspCore_->setEffectValue(DfxDsp::Ambience, targetEffectValues_[FXSOUND_EFFECT_AMBIENCE]);
    dspCore_->setEffectValue(DfxDsp::Surround, targetEffectValues_[FXSOUND_EFFECT_SURROUND]);
    dspCore_->setEffectValue(DfxDsp::DynamicBoost, targetEffectValues_[FXSOUND_EFFECT_DYNAMIC_BOOST]);
    dspCore_->setEffectValue(DfxDsp::Bass, targetEffectValues_[FXSOUND_EFFECT_BASS]);

    dspCore_->eqOn(true);
    // Smoothly interpolate the 10 user bands across all 31 internal GraphicEq bands
    for (int i = 1; i <= 31; ++i) {
        float source_index = static_cast<float>(i - 1) * 9.0f / 30.0f;
        int lower_index = static_cast<int>(source_index);
        int upper_index = (lower_index < 9) ? lower_index + 1 : 9;
        float fraction = source_index - static_cast<float>(lower_index);
        float interpolatedGain = targetEqBands_[lower_index] + (targetEqBands_[upper_index] - targetEqBands_[lower_index]) * fraction;
        dspCore_->setEqBandBoostCut(i - 1, interpolatedGain);
    }
    dspCore_->setMasterGain(targetMasterGainDb_.load(std::memory_order_relaxed));

    isEnabled_.store(true, std::memory_order_release);
    return true;
}

void FxSoundEngine::reset() {
    if (dspCore_) {
        delete dspCore_;
        dspCore_ = nullptr;
    }
}

void FxSoundEngine::setPower(bool enabled) {
    isEnabled_.store(enabled, std::memory_order_release);
    if (dspCore_) {
        dspCore_->powerOn(enabled);
    }
}

bool FxSoundEngine::isPowerOn() const {
    return isEnabled_.load(std::memory_order_acquire);
}

void FxSoundEngine::setEffect(FxSoundEffectId effect, float value0To10) {
    if (effect < 0 || effect >= FXSOUND_NUM_EFFECTS) return;
    targetEffectValues_[effect] = std::clamp(value0To10, 0.0f, 10.0f);
}

float FxSoundEngine::getEffect(FxSoundEffectId effect) const {
    if (effect < 0 || effect >= FXSOUND_NUM_EFFECTS) return 0.0f;
    return targetEffectValues_[effect].load(std::memory_order_relaxed);
}

void FxSoundEngine::setEqBand(int bandIndex, float gainDb) {
    if (bandIndex < 0 || bandIndex >= 10) return;
    targetEqBands_[bandIndex].store(std::clamp(gainDb, -14.0f, 14.0f), std::memory_order_relaxed);
}

float FxSoundEngine::getEqBand(int bandIndex) const {
    if (bandIndex < 0 || bandIndex >= 10) return 0.0f;
    return targetEqBands_[bandIndex].load(std::memory_order_relaxed);
}

int FxSoundEngine::getNumEqBands() const {
    return 10;
}

void FxSoundEngine::setMasterGain(float gainDb) {
    targetMasterGainDb_.store(gainDb, std::memory_order_relaxed);
}

float FxSoundEngine::getMasterGain() const {
    return targetMasterGainDb_.load(std::memory_order_relaxed);
}

void FxSoundEngine::setCrossfeed(bool enabled) {
    // The filter history is owned by the audio thread, which resets it when it observes
    // the enable transition. Resetting it here would race with the running filter and
    // produce an audible discontinuity.
    isCrossfeedEnabled_.store(enabled, std::memory_order_relaxed);
}

bool FxSoundEngine::isCrossfeedEnabled() const {
    return isCrossfeedEnabled_.load(std::memory_order_relaxed);
}

void FxSoundEngine::syncParametersToDsp() {
    if (!dspCore_) return;

    bool effectsNeedSync = false;
    for (int i = 0; i < FXSOUND_NUM_EFFECTS; ++i) {
        const float target = targetEffectValues_[i].load(std::memory_order_relaxed);
        float diff = target - smoothedEffectValues_[i];
        if (std::fabs(diff) > 0.005f) {
            smoothedEffectValues_[i] += 0.2f * diff;
            effectsNeedSync = true;
        } else {
            smoothedEffectValues_[i] = target;
        }
    }
    if (effectsNeedSync) {
        dspCore_->setEffectValue(DfxDsp::Fidelity, smoothedEffectValues_[FXSOUND_EFFECT_FIDELITY]);
        dspCore_->setEffectValue(DfxDsp::Ambience, smoothedEffectValues_[FXSOUND_EFFECT_AMBIENCE]);
        dspCore_->setEffectValue(DfxDsp::Surround, smoothedEffectValues_[FXSOUND_EFFECT_SURROUND]);
        dspCore_->setEffectValue(DfxDsp::DynamicBoost, smoothedEffectValues_[FXSOUND_EFFECT_DYNAMIC_BOOST]);
        dspCore_->setEffectValue(DfxDsp::Bass, smoothedEffectValues_[FXSOUND_EFFECT_BASS]);
    }

    bool eqNeedsSync = false;
    for (int i = 0; i < 10; ++i) {
        const float target = targetEqBands_[i].load(std::memory_order_relaxed);
        float diff = target - smoothedEqBands_[i];
        if (std::fabs(diff) > 0.005f) {
            smoothedEqBands_[i] += 0.2f * diff;
            eqNeedsSync = true;
        } else {
            smoothedEqBands_[i] = target;
        }
    }
    if (eqNeedsSync) {
        for (int i = 1; i <= 31; ++i) {
            float source_index = static_cast<float>(i - 1) * 9.0f / 30.0f;
            int lower_index = static_cast<int>(source_index);
            int upper_index = (lower_index < 9) ? lower_index + 1 : 9;
            float fraction = source_index - static_cast<float>(lower_index);
            float interpolatedGain = smoothedEqBands_[lower_index] +
                (smoothedEqBands_[upper_index] - smoothedEqBands_[lower_index]) * fraction;
            dspCore_->setEqBandBoostCut(i - 1, interpolatedGain);
        }
    }

    const float masterTarget = targetMasterGainDb_.load(std::memory_order_relaxed);
    float masterDiff = masterTarget - smoothedMasterGainDb_;
    if (std::fabs(masterDiff) > 0.005f) {
        smoothedMasterGainDb_ += 0.2f * masterDiff;
        dspCore_->setMasterGain(smoothedMasterGainDb_);
    } else if (smoothedMasterGainDb_ != masterTarget) {
        smoothedMasterGainDb_ = masterTarget;
        dspCore_->setMasterGain(smoothedMasterGainDb_);
    }
}

int FxSoundEngine::processPcm16(const int16_t* inSamples, int16_t* outSamples, int numFrames) {
    if (!inSamples || !outSamples || numFrames <= 0) return 0;

    const int totalSamples = numFrames * numChannels_;

    syncParametersToDsp();

    float inPeak = 0.0f;
    float inRmsSum = 0.0f;
    for (int i = 0; i < totalSamples; ++i) {
        float f = std::fabs(pcm16ToFloat(inSamples[i]));
        if (f > inPeak) inPeak = f;
        inRmsSum += f * f;
    }

    if (!isEnabled_.load(std::memory_order_relaxed) || !dspCore_) {
        std::memcpy(outSamples, inSamples, totalSamples * sizeof(int16_t));
        currentInPeak_.store(inPeak, std::memory_order_relaxed);
        currentOutPeak_.store(inPeak, std::memory_order_relaxed);
        currentInRms_.store(std::sqrt(inRmsSum / totalSamples), std::memory_order_relaxed);
        currentOutRms_.store(std::sqrt(inRmsSum / totalSamples), std::memory_order_relaxed);
        isClipping_.store(inPeak >= 0.999f, std::memory_order_relaxed);
        return numFrames;
    }

    // The core runs in float, so the 16-bit buffer is converted at the boundary. The
    // conversion is done per chunk so the scratch buffers stay a fixed size and the
    // real-time path never allocates.
    const int maxFramesChunk = (scratchFloatIn_.size() > 0 && numChannels_ > 0)
        ? static_cast<int>(scratchFloatIn_.size()) / numChannels_
        : numFrames;

    int framesRemaining = numFrames;
    int frameOffset = 0;
    while (framesRemaining > 0) {
        const int chunkFrames = std::min(framesRemaining, maxFramesChunk);
        const int chunkSamples = chunkFrames * numChannels_;
        const int sampleOffset = frameOffset * numChannels_;

        for (int i = 0; i < chunkSamples; ++i) {
            scratchFloatIn_[i] = pcm16ToFloat(inSamples[sampleOffset + i]);
        }

        processFloatCore(scratchFloatIn_.data(), scratchFloatOut_.data(), chunkFrames);
        applyPostProcessing(scratchFloatOut_.data(), chunkFrames);

        for (int i = 0; i < chunkSamples; ++i) {
            outSamples[sampleOffset + i] = floatToPcm16(scratchFloatOut_[i]);
        }

        frameOffset += chunkFrames;
        framesRemaining -= chunkFrames;
    }

    float outPeak = 0.0f;
    float outRmsSum = 0.0f;
    for (int i = 0; i < totalSamples; ++i) {
        float f = std::fabs(pcm16ToFloat(outSamples[i]));
        if (f > outPeak) outPeak = f;
        outRmsSum += f * f;
    }

    currentInPeak_.store(inPeak, std::memory_order_relaxed);
    currentOutPeak_.store(outPeak, std::memory_order_relaxed);
    currentInRms_.store(std::sqrt(inRmsSum / totalSamples), std::memory_order_relaxed);
    currentOutRms_.store(std::sqrt(outRmsSum / totalSamples), std::memory_order_relaxed);
    isClipping_.store(outPeak >= 0.999f, std::memory_order_relaxed);

    return numFrames;
}

int FxSoundEngine::processFloat(const float* inSamples, float* outSamples, int numFrames) {
    if (!inSamples || !outSamples || numFrames <= 0) return 0;

    const int totalSamples = numFrames * numChannels_;

    syncParametersToDsp();

    // Input metrics accumulator
    float inPeak = 0.0f;
    float inRmsSum = 0.0f;
    for (int i = 0; i < totalSamples; ++i) {
        float f = std::fabs(inSamples[i]);
        if (f > inPeak) inPeak = f;
        inRmsSum += f * f;
    }

    if (!isEnabled_.load(std::memory_order_relaxed) || !dspCore_) {
        std::memcpy(outSamples, inSamples, totalSamples * sizeof(float));
        currentInPeak_.store(inPeak, std::memory_order_relaxed);
        currentOutPeak_.store(inPeak, std::memory_order_relaxed);
        currentInRms_.store(std::sqrt(inRmsSum / totalSamples), std::memory_order_relaxed);
        currentOutRms_.store(std::sqrt(inRmsSum / totalSamples), std::memory_order_relaxed);
        isClipping_.store(inPeak >= 0.999f, std::memory_order_relaxed);
        return numFrames;
    }

    // Run the buffer straight through the upstream float core. The core copies the input
    // to the output and processes the output in place, so no conversion and no scratch
    // buffer is needed on this path.
    processFloatCore(inSamples, outSamples, numFrames);

    applyPostProcessing(outSamples, numFrames);

    float outPeak = 0.0f;
    float outRmsSum = 0.0f;
    for (int i = 0; i < totalSamples; ++i) {
        float f = std::fabs(outSamples[i]);
        if (f > outPeak) outPeak = f;
        outRmsSum += f * f;
    }

    currentInPeak_.store(inPeak, std::memory_order_relaxed);
    currentOutPeak_.store(outPeak, std::memory_order_relaxed);
    currentInRms_.store(std::sqrt(inRmsSum / totalSamples), std::memory_order_relaxed);
    currentOutRms_.store(std::sqrt(outRmsSum / totalSamples), std::memory_order_relaxed);
    isClipping_.store(outPeak >= 0.999f, std::memory_order_relaxed);

    return numFrames;
}

void FxSoundEngine::processFloatCore(const float* inSamples, float* outSamples, int numFrames) {
    if (!dspCore_ || !inSamples || !outSamples || numFrames <= 0) return;

    // The upstream public API takes short int* even for float data: the core is
    // configured for 32-bit float and reinterprets the pointer by sample format. Buffer
    // offsets therefore have to be in bytes, not in short int elements.
    const size_t bytesPerFrame = static_cast<size_t>(numChannels_) * sizeof(float);
    char* inBytes = reinterpret_cast<char*>(const_cast<float*>(inSamples));
    char* outBytes = reinterpret_cast<char*>(outSamples);

    int framesRemaining = numFrames;
    int frameOffset = 0;
    while (framesRemaining > 0) {
        const int chunkFrames = std::min(framesRemaining, MAX_FRAMES_PER_CHUNK);
        const size_t byteOffset = static_cast<size_t>(frameOffset) * bytesPerFrame;

        dspCore_->processAudio(
            reinterpret_cast<short int*>(inBytes + byteOffset),
            reinterpret_cast<short int*>(outBytes + byteOffset),
            chunkFrames,
            0
        );

        frameOffset += chunkFrames;
        framesRemaining -= chunkFrames;
    }
}

void FxSoundEngine::applyPostProcessing(float* samples, int numFrames) {
    if (!samples || numFrames <= 0) return;

    const int totalSamples = numFrames * numChannels_;

    // Non-Linear Harmonic Exciter (Fidelity 2nd and 3rd harmonic saturation)
    float fidelityAmt = smoothedEffectValues_[FXSOUND_EFFECT_FIDELITY] / 10.0f;
    if (fidelityAmt > 0.02f) {
        float drive = fidelityAmt * 0.15f;
        for (int i = 0; i < totalSamples; ++i) {
            float s = samples[i];
            float s2 = s * s;
            float harm = (s >= 0.0f) ? (0.55f * s2 - 0.2f * s2 * s) : (-0.35f * s2 - 0.2f * s2 * s);
            samples[i] = std::clamp(s + drive * harm, -1.0f, 1.0f);
        }
    }

    // BS2B Binaural Headphone Crossfeed
    // The filter history is audio-thread state, so the reset happens here rather than in
    // setCrossfeed(). That keeps the UI thread from touching the filter while it runs.
    const bool crossfeedEnabled = isCrossfeedEnabled_.load(std::memory_order_relaxed);
    if (crossfeedEnabled && !crossfeedWasEnabled_) {
        crossfeedHistoryL_ = 0.0f;
        crossfeedHistoryR_ = 0.0f;
    }
    crossfeedWasEnabled_ = crossfeedEnabled;

    if (crossfeedEnabled && numChannels_ == 2) {
        const float crossGain = 0.32f; // -9.9 dB crossfeed
        for (int i = 0; i < numFrames; ++i) {
            int idxL = i * 2;
            int idxR = i * 2 + 1;
            float l = samples[idxL];
            float r = samples[idxR];

            crossfeedHistoryL_ = crossfeedB0_ * l + crossfeedA1_ * crossfeedHistoryL_;
            crossfeedHistoryR_ = crossfeedB0_ * r + crossfeedA1_ * crossfeedHistoryR_;

            samples[idxL] = std::clamp(l + crossGain * crossfeedHistoryR_, -1.0f, 1.0f);
            samples[idxR] = std::clamp(r + crossGain * crossfeedHistoryL_, -1.0f, 1.0f);
        }
    }
}

void FxSoundEngine::getMetrics(FxSoundMetrics* metrics) {
    if (!metrics) return;
    metrics->inPeak = currentInPeak_.load(std::memory_order_relaxed);
    metrics->outPeak = currentOutPeak_.load(std::memory_order_relaxed);
    metrics->inRms = currentInRms_.load(std::memory_order_relaxed);
    metrics->outRms = currentOutRms_.load(std::memory_order_relaxed);
    metrics->isClipping = isClipping_.load(std::memory_order_relaxed);
}

void FxSoundEngine::getSpectrum(float* outBands, int numBands) {
    if (!outBands || numBands <= 0) return;
    if (dspCore_) {
        dspCore_->getSpectrumBandValues(outBands, numBands);
    } else {
        std::memset(outBands, 0, numBands * sizeof(float));
    }
}
