#ifndef _FXSOUND_ENGINE_H_
#define _FXSOUND_ENGINE_H_

#include <stdint.h>
#include <stdbool.h>
#include <atomic>
#include <vector>

#ifdef __cplusplus
extern "C" {
#endif

// FxSound Effect IDs (corresponding to upstream DfxDsp::Effect)
typedef enum {
    FXSOUND_EFFECT_FIDELITY     = 0, // Clarity / High-Frequency Aural Exciter
    FXSOUND_EFFECT_AMBIENCE     = 1, // Reverb / Ambient Reflections
    FXSOUND_EFFECT_SURROUND     = 2, // 3D Surround / Stereo Widener
    FXSOUND_EFFECT_DYNAMIC_BOOST= 3, // Dynamic Range Maximizer / Limiter
    FXSOUND_EFFECT_BASS         = 4, // HyperBass / Low Frequency Boost
    FXSOUND_NUM_EFFECTS         = 5
} FxSoundEffectId;

typedef struct FxSoundMetrics {
    float inPeak;
    float outPeak;
    float inRms;
    float outRms;
    bool  isClipping;
} FxSoundMetrics;

#ifdef __cplusplus
}

class DfxDsp;

class FxSoundEngine {
public:
    FxSoundEngine();
    ~FxSoundEngine();

    /**
     * Initialises the engine. The upstream core is configured for 32-bit float, so the
     * sample format is chosen once here and never switched on the audio thread.
     */
    bool init(int sampleRate, int numChannels);
    void reset();

    void setPower(bool enabled);
    bool isPowerOn() const;

    void setEffect(FxSoundEffectId effect, float value0To10);
    float getEffect(FxSoundEffectId effect) const;

    void setEqBand(int bandIndex, float gainDb);
    float getEqBand(int bandIndex) const;
    int getNumEqBands() const;

    void setMasterGain(float gainDb);
    float getMasterGain() const;

    int getNumChannels() const { return numChannels_; }
    int getSampleRate() const { return sampleRate_; }

    /**
     * Flattens all 31 upstream graphic EQ bands to 0 dB in every state storage
     * (memory and registry), including any filter state a preset would have left
     * behind. Used to establish a known-flat baseline before measurement.
     */
    void resetEQ();

    /** Bypasses or re-enables the upstream 31-band graphic EQ stage entirely. */
    void setEqEnabled(bool enabled);
    void setCrossfeed(bool enabled);
    bool isCrossfeedEnabled() const;

    /**
     * Measurement mode: disables the static preamp headroom compensation so that
     * effect-minus-reference curves isolate the upstream kernels alone. The limiter
     * stays active. User playback must keep compensation on; only the response
     * measurement harness turns this off.
     */
    void setHeadroomManagementEnabled(bool enabled);
    bool isHeadroomManagementEnabled() const;

    // Real-time audio processing functions (must be lock-free and allocation-free)
    int processFloat(const float* inSamples, float* outSamples, int numFrames);
    int processPcm16(const int16_t* inSamples, int16_t* outSamples, int numFrames);

    void getMetrics(FxSoundMetrics* metrics);
    void getSpectrum(float* outBands, int numBands);

private:
    /**
     * The upstream core refuses to process buffers larger than DAW_MAX_BUFFER_SIZE
     * (16384 sample sets) and returns them unprocessed, so every call is chunked to stay
     * below that limit.
     */
    static constexpr int MAX_FRAMES_PER_CHUNK = 8192;

    DfxDsp* dspCore_;
    int sampleRate_;
    int numChannels_;
    std::atomic<bool> isEnabled_;

    // Preallocated internal scratch buffers to ensure zero real-time heap allocation.
    // Only the 16-bit entry point needs them, because it converts to and from the
    // float core.
    std::vector<float> scratchFloatIn_;
    std::vector<float> scratchFloatOut_;

    // Thread-safe metrics
    std::atomic<float> currentInPeak_;
    std::atomic<float> currentOutPeak_;
    std::atomic<float> currentInRms_;
    std::atomic<float> currentOutRms_;
    std::atomic<bool>  isClipping_;

    // Static preamp headroom management is applied during user playback. It must be
    // off while the response mapping data is measured, or the compensation would cancel
    // the very kernel boosts the measurement is meant to isolate.
    std::atomic<bool> isHeadroomManagementEnabled_;

    // Target parameters are published from the UI/JNI thread and consumed by the audio
    // thread, so they are atomic. The smoothed copies are written and read by the audio
    // thread only, so they stay plain floats.
    std::atomic<float> targetEffectValues_[FXSOUND_NUM_EFFECTS];
    float smoothedEffectValues_[FXSOUND_NUM_EFFECTS];
    std::atomic<float> targetEqBands_[10];
    float smoothedEqBands_[10];

    // The user's requested master gain. The gain actually sent to the core is this minus
    // the headroom compensation computed from the smoothed boost settings, so boosted
    // presets are attenuated before they clip.
    std::atomic<float> userMasterGainDb_;
    std::atomic<float> targetMasterGainDb_;
    float smoothedMasterGainDb_;

    // Output peak catcher. Owned by the audio thread: no atomic, no lock. The gain is
    // applied instantly on an over and releases through a one-pole ramp afterwards.
    float limiterGain_;

    // BS2B Binaural Headphone Crossfeed state
    std::atomic<bool> isCrossfeedEnabled_;
    float crossfeedB0_;
    float crossfeedA1_;
    // Owned by the audio thread: the filter history is reset here on an enable
    // transition, never from the UI thread while the filter is running.
    float crossfeedHistoryL_;
    float crossfeedHistoryR_;
    bool crossfeedWasEnabled_;

    void syncParametersToDsp();

    // Estimated worst-case positive gain the boosted stages can add, derived from the
    // upstream DSP's measured response (docs/FX_RESPONSE_MEASUREMENT.md). Used to
    // attenuate the pre-gain before the boosts run, so the limiter only ever guards a
    // small residual.
    float computeHeadroomCompensationDb() const;

    // Output limiter. One-pole 120 ms release, instantaneous attack, ceiling at
    // kLimiterCeil. Runs last, after every other gain stage. Audio-thread only.
    void applyLimiter(float* samples, int numFrames);

    // Runs the buffer through the upstream float core. The upstream copies the input to
    // the output and then processes the output in place, so no scratch buffer is needed.
    void processFloatCore(const float* inSamples, float* outSamples, int numFrames);

    // Harmonic exciter and binaural crossfeed, applied to a float buffer.
    void applyPostProcessing(float* samples, int numFrames);
};

// The audio thread may not take a lock, so the published parameters must be lock-free.
static_assert(std::atomic<float>::is_always_lock_free,
              "std::atomic<float> must be lock-free for the real-time audio path");
static_assert(std::atomic<bool>::is_always_lock_free,
              "std::atomic<bool> must be lock-free for the real-time audio path");

#endif // __cplusplus

#endif // _FXSOUND_ENGINE_H_
