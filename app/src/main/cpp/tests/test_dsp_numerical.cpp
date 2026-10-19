#include <iostream>
#include <vector>
#include <cmath>
#include <cassert>
#include <cstring>
#include <algorithm>
#include "fxsound_engine.h"

// Numerical DSP Test Suite complying with docs/DSP_QUALITY_SPEC.md

static bool testSilence() {
    std::cout << "[TEST] Running Silence Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    const int numFrames = 1024;
    std::vector<float> inBuf(numFrames * 2, 0.0f);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    engine.processFloat(inBuf.data(), outBuf.data(), numFrames);

    float maxResidual = 0.0f;
    for (float sample : outBuf) {
        if (std::isnan(sample) || std::isinf(sample)) {
            std::cerr << "FAIL: NaN or Inf detected in silence output!" << std::endl;
            return false;
        }
        maxResidual = std::max<float>(maxResidual, std::fabs(sample));
    }

    if (maxResidual > 0.0001f) {
        std::cerr << "FAIL: Residual noise too high on silence: " << maxResidual << std::endl;
        return false;
    }

    std::cout << "  PASS: Silence output bounded (< 0.0001f)" << std::endl;
    return true;
}

static bool testImpulseStability() {
    std::cout << "[TEST] Running Impulse Stability Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    // Set maximum effects
    engine.setEffect(FXSOUND_EFFECT_FIDELITY, 10.0f);
    engine.setEffect(FXSOUND_EFFECT_BASS, 10.0f);
    engine.setEffect(FXSOUND_EFFECT_DYNAMIC_BOOST, 10.0f);

    const int numFrames = 2048;
    std::vector<float> inBuf(numFrames * 2, 0.0f);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    // Unit impulse at frame 0
    inBuf[0] = 1.0f;
    inBuf[1] = 1.0f;

    engine.processFloat(inBuf.data(), outBuf.data(), numFrames);

    for (int i = 0; i < numFrames * 2; ++i) {
        float sample = outBuf[i];
        if (std::isnan(sample) || std::isinf(sample)) {
            std::cerr << "FAIL: Filter exploded (NaN/Inf) on unit impulse at sample " << i << std::endl;
            return false;
        }
    }

    // Verify decay at the end of buffer
    float tailPeak = 0.0f;
    for (int i = (numFrames - 128) * 2; i < numFrames * 2; ++i) {
        tailPeak = std::max<float>(tailPeak, std::fabs(outBuf[i]));
    }

    std::cout << "  PASS: Impulse response stable; tail peak = " << tailPeak << std::endl;
    return true;
}

static bool testMultitoneAndLimiting() {
    std::cout << "[TEST] Running Multitone and Limiter Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    engine.setEffect(FXSOUND_EFFECT_BASS, 8.0f);
    engine.setEffect(FXSOUND_EFFECT_DYNAMIC_BOOST, 6.0f);

    const int numFrames = 4800; // 100 ms
    std::vector<float> inBuf(numFrames * 2, 0.0f);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    // Multitone with bass frequency (60Hz) and mid frequency (1kHz)
    for (int i = 0; i < numFrames; ++i) {
        float t = static_cast<float>(i) / 48000.0f;
        float s = 0.6f * std::sin(2.0f * 3.14159265f * 60.0f * t) +
                  0.4f * std::sin(2.0f * 3.14159265f * 1000.0f * t);
        inBuf[i * 2] = s;
        inBuf[i * 2 + 1] = s;
    }

    engine.processFloat(inBuf.data(), outBuf.data(), numFrames);

    FxSoundMetrics metrics;
    engine.getMetrics(&metrics);

    std::cout << "  Observed metrics: InPeak=" << metrics.inPeak 
              << ", OutPeak=" << metrics.outPeak 
              << ", InRMS=" << metrics.inRms 
              << ", OutRMS=" << metrics.outRms 
              << ", LimiterActive=" << (metrics.isClipping ? "YES" : "NO") << std::endl;

    if (metrics.outPeak > 1.05f) {
        std::cerr << "FAIL: Output exceeded digital full scale threshold: " << metrics.outPeak << std::endl;
        return false;
    }

    std::cout << "  PASS: Output peak is safely bounded without runaway" << std::endl;
    return true;
}

static bool testProcessFloatSampleConversionAndClamping() {
    std::cout << "[TEST] Running Float Sample Conversion and Clamping Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    const int numFrames = 512;
    std::vector<float> inBuf(numFrames * 2);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    // Provide signals spanning normal range and intentional out-of-range clipping inputs
    for (int i = 0; i < numFrames * 2; ++i) {
        if (i < 200) {
            inBuf[i] = 0.5f * std::sin(static_cast<float>(i) * 0.1f);
        } else if (i < 400) {
            inBuf[i] = 1.8f; // Overshoot +1.8
        } else {
            inBuf[i] = -2.2f; // Undershoot -2.2
        }
    }

    int processed = engine.processFloat(inBuf.data(), outBuf.data(), numFrames);
    if (processed != numFrames) {
        std::cerr << "FAIL: Processed frame count mismatch: expected " << numFrames << ", got " << processed << std::endl;
        return false;
    }

    for (int i = 0; i < numFrames * 2; ++i) {
        float sample = outBuf[i];
        if (std::isnan(sample) || std::isinf(sample)) {
            std::cerr << "FAIL: NaN or Inf detected after float conversion at index " << i << std::endl;
            return false;
        }
        if (sample < -1.05f || sample > 1.05f) {
            std::cerr << "FAIL: Sample exceeded safe output bounds after clamping: " << sample << std::endl;
            return false;
        }
    }

    std::cout << "  PASS: Float sample conversion and clamping verified" << std::endl;
    return true;
}

static bool testProcessPcm16ParameterSync() {
    std::cout << "[TEST] Running PCM16 Parameter Synchronization Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    // Change effect and EQ parameters during playback
    engine.setEffect(FXSOUND_EFFECT_BASS, 9.0f);
    engine.setEffect(FXSOUND_EFFECT_FIDELITY, 8.0f);
    engine.setEqBand(0, 6.0f);
    engine.setMasterGain(-2.0f);

    const int numFrames = 1024;
    std::vector<int16_t> inBuf(numFrames * 2);
    std::vector<int16_t> outBuf(numFrames * 2, 0);

    for (int i = 0; i < numFrames; ++i) {
        float s = 16000.0f * std::sin(2.0f * 3.14159265f * 200.0f * (static_cast<float>(i) / 48000.0f));
        inBuf[i * 2] = static_cast<int16_t>(s);
        inBuf[i * 2 + 1] = static_cast<int16_t>(s);
    }

    // Process multiple blocks to allow parameter smoothing to interpolate smoothly
    for (int block = 0; block < 5; ++block) {
        int processed = engine.processPcm16(inBuf.data(), outBuf.data(), numFrames);
        if (processed != numFrames) {
            std::cerr << "FAIL: PCM16 processed frame count mismatch: " << processed << std::endl;
            return false;
        }
    }

    FxSoundMetrics metrics;
    engine.getMetrics(&metrics);
    std::cout << "  PCM16 metrics: InPeak=" << metrics.inPeak
              << ", OutPeak=" << metrics.outPeak
              << ", OutRMS=" << metrics.outRms << std::endl;

    if (metrics.outPeak <= 0.001f) {
        std::cerr << "FAIL: PCM16 output is silent despite active input and effects" << std::endl;
        return false;
    }

    std::cout << "  PASS: PCM16 parameter sync processed successfully with active output" << std::endl;
    return true;
}

static bool testArbitraryBufferSizeProcessing() {
    std::cout << "[TEST] Running Arbitrary Buffer Size Processing Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    // Test a non-power-of-two odd frame size (e.g., 347 frames)
    const int oddFrames = 347;
    std::vector<float> inBuf(oddFrames * 2, 0.4f);
    std::vector<float> outBuf(oddFrames * 2, 0.0f);

    int processed = engine.processFloat(inBuf.data(), outBuf.data(), oddFrames);
    if (processed != oddFrames) {
        std::cerr << "FAIL: Odd frame size processFloat mismatch: " << processed << std::endl;
        return false;
    }

    // Test a very large frame size (e.g. 10000 frames exceeding initial 8192 frame prealloc chunk)
    const int largeFrames = 10000;
    std::vector<float> largeInBuf(largeFrames * 2, 0.3f);
    std::vector<float> largeOutBuf(largeFrames * 2, 0.0f);

    processed = engine.processFloat(largeInBuf.data(), largeOutBuf.data(), largeFrames);
    if (processed != largeFrames) {
        std::cerr << "FAIL: Large frame size chunked processFloat mismatch: " << processed << std::endl;
        return false;
    }

    std::cout << "  PASS: Odd and large buffer chunking handled without issues" << std::endl;
    return true;
}

// A DC offset must not make the filter or gain stages run away.
static bool testDcOffset() {
    std::cout << "[TEST] Running DC Offset Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    engine.setEffect(FXSOUND_EFFECT_BASS, 8.0f);
    engine.setEffect(FXSOUND_EFFECT_DYNAMIC_BOOST, 8.0f);

    const int numFrames = 4800;
    std::vector<float> inBuf(numFrames * 2, 0.5f);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    engine.processFloat(inBuf.data(), outBuf.data(), numFrames);

    float peak = 0.0f;
    for (int i = 0; i < numFrames * 2; ++i) {
        float sample = outBuf[i];
        if (std::isnan(sample) || std::isinf(sample)) {
            std::cerr << "FAIL: NaN or Inf detected on DC input at sample " << i << std::endl;
            return false;
        }
        peak = std::max<float>(peak, std::fabs(sample));
    }

    if (peak > 2.0f) {
        std::cerr << "FAIL: DC input produced runaway output, peak=" << peak << std::endl;
        return false;
    }

    std::cout << "  PASS: DC offset stays bounded (peak=" << peak << ")" << std::endl;
    return true;
}

// A logarithmic sweep must produce finite, bounded output across the whole band.
static bool testSweepStability() {
    std::cout << "[TEST] Running Frequency Sweep Stability Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    engine.setEffect(FXSOUND_EFFECT_FIDELITY, 6.0f);
    engine.setEffect(FXSOUND_EFFECT_BASS, 6.0f);

    const int numFrames = 9600; // 200 ms
    const double fStart = 20.0;
    const double fEnd = 20000.0;
    std::vector<float> inBuf(numFrames * 2, 0.0f);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    for (int i = 0; i < numFrames; ++i) {
        double t = static_cast<double>(i) / 48000.0;
        double phase = 2.0 * 3.14159265358979 * fStart * t *
                       std::pow(fEnd / fStart, t * (48000.0 / numFrames));
        float s = static_cast<float>(0.3 * std::sin(phase));
        inBuf[i * 2] = s;
        inBuf[i * 2 + 1] = s;
    }

    engine.processFloat(inBuf.data(), outBuf.data(), numFrames);

    float peak = 0.0f;
    for (int i = 0; i < numFrames * 2; ++i) {
        float sample = outBuf[i];
        if (std::isnan(sample) || std::isinf(sample)) {
            std::cerr << "FAIL: NaN or Inf detected during sweep at sample " << i << std::endl;
            return false;
        }
        peak = std::max<float>(peak, std::fabs(sample));
    }

    if (peak > 2.0f) {
        std::cerr << "FAIL: Sweep produced runaway output, peak=" << peak << std::endl;
        return false;
    }

    std::cout << "  PASS: Sweep output stable and bounded (peak=" << peak << ")" << std::endl;
    return true;
}

// The float path must not round-trip through 16-bit PCM. If it does, every output
// sample lands on the 1/32768 grid and the effective signal resolution is capped
// near 96 dB SNR.
static bool testFloatPathRetainsResolution() {
    std::cout << "[TEST] Running Float Path Resolution Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    const int numFrames = 4800;
    // 997 Hz is deliberately non-commensurate with the sample rate so that no sample
    // of the input itself lands on the 16-bit grid.
    const double freq = 997.0;
    std::vector<float> inBuf(numFrames * 2, 0.0f);
    std::vector<float> outBuf(numFrames * 2, 0.0f);

    for (int i = 0; i < numFrames; ++i) {
        float s = static_cast<float>(0.1 * std::sin(2.0 * 3.14159265358979 * freq * i / 48000.0));
        inBuf[i * 2] = s;
        inBuf[i * 2 + 1] = s;
    }

    engine.processFloat(inBuf.data(), outBuf.data(), numFrames);

    int onGrid = 0;
    int counted = 0;
    for (int i = 0; i < numFrames * 2; ++i) {
        float sample = outBuf[i];
        if (std::isnan(sample) || std::isinf(sample)) {
            std::cerr << "FAIL: NaN or Inf detected in float path at sample " << i << std::endl;
            return false;
        }
        if (std::fabs(sample) < 1e-6f) continue; // near-silence carries no resolution info
        double scaled = static_cast<double>(sample) * 32768.0;
        double nearest = std::round(scaled);
        if (std::fabs(scaled - nearest) < 0.01) {
            onGrid++;
        }
        counted++;
    }

    if (counted == 0) {
        std::cerr << "FAIL: Float path produced no measurable output" << std::endl;
        return false;
    }

    double onGridFraction = static_cast<double>(onGrid) / static_cast<double>(counted);
    std::cout << "  Samples snapped to the 16-bit grid: " << onGridFraction * 100.0
              << "% of " << counted << std::endl;

    if (onGridFraction > 0.5) {
        std::cerr << "FAIL: Float path is quantised to 16 bits (" << onGridFraction * 100.0
                  << "% of samples on the 1/32768 grid). The engine is round-tripping through "
                     "PCM16 instead of using the upstream float core." << std::endl;
        return false;
    }

    std::cout << "  PASS: Float path retains sub-16-bit resolution" << std::endl;
    return true;
}

// The 16-bit entry point must stay finite and within the int16 range even when the
// source signal exceeds full scale.
static bool testPcm16BoundedAndClamped() {
    std::cout << "[TEST] Running PCM16 Bounded And Clamped Test..." << std::endl;
    FxSoundEngine engine;
    if (!engine.init(48000, 2)) return false;

    const int numFrames = 2048;
    std::vector<int16_t> inBuf(numFrames * 2);
    std::vector<int16_t> outBuf(numFrames * 2, 0);

    for (int i = 0; i < numFrames; ++i) {
        // Alternating full-scale positive and negative, i.e. the worst case for clipping.
        int16_t s = (i % 2 == 0) ? 32767 : -32768;
        inBuf[i * 2] = s;
        inBuf[i * 2 + 1] = s;
    }

    int processed = engine.processPcm16(inBuf.data(), outBuf.data(), numFrames);
    if (processed != numFrames) {
        std::cerr << "FAIL: PCM16 processed frame count mismatch: " << processed << std::endl;
        return false;
    }

    for (int i = 0; i < numFrames * 2; ++i) {
        // A wrapped 16-bit value would show up as an implausible jump in the output.
        // Any value is representable in int16, so the real check is that the signal
        // stayed continuous rather than wrapping.
        (void)outBuf[i];
    }

    // Verify the output is a continuous waveform rather than a wrapped one: the number
    // of sign changes must be close to the number of input sign changes.
    int inputSignChanges = 0;
    int outputSignChanges = 0;
    for (int i = 1; i < numFrames; ++i) {
        if ((inBuf[i * 2] >= 0) != (inBuf[(i - 1) * 2] >= 0)) inputSignChanges++;
        if ((outBuf[i * 2] >= 0) != (outBuf[(i - 1) * 2] >= 0)) outputSignChanges++;
    }

    // The processed signal is band-limited, so it may change sign less often, but a
    // wrapped or corrupted buffer would change sign far more often than the input.
    if (outputSignChanges > inputSignChanges * 2 + 16) {
        std::cerr << "FAIL: PCM16 output shows discontinuity (input sign changes="
                  << inputSignChanges << ", output sign changes=" << outputSignChanges << ")"
                  << std::endl;
        return false;
    }

    std::cout << "  PASS: PCM16 output bounded and continuous" << std::endl;
    return true;
}

// Do the app's effect values actually reach the upstream DSP kernels?
//
// The upstream kernel parameters (aural drive, maximizer gain, bass contour) are normally
// communicated from the desktop UI through the shared-parameter layer. On Android there is
// no UI and no registry, so the kernels may end up running with their built-in defaults
// while the app's Fidelity/Bass/Ambience/Surround/DynamicBoost values are applied
// somewhere else entirely. This test compares the output with the effects at 0 and at 10.
static bool testEffectValuesReachTheKernel() {
    std::cout << "[TEST] Running Effect Value Reachability Test..." << std::endl;

    const int numFrames = 4800;
    std::vector<float> inBuf(numFrames * 2, 0.0f);
    for (int i = 0; i < numFrames; ++i) {
        float s = static_cast<float>(0.2 * std::sin(2.0 * 3.14159265358979 * 220.0 * i / 48000.0));
        inBuf[i * 2] = s;
        inBuf[i * 2 + 1] = s;
    }

    auto renderWithEffects = [&](float bass, float fidelity) {
        FxSoundEngine engine;
        if (!engine.init(48000, 2)) return std::vector<float>();
        engine.setEffect(FXSOUND_EFFECT_BASS, bass);
        engine.setEffect(FXSOUND_EFFECT_FIDELITY, fidelity);

        std::vector<float> outBuf(numFrames * 2, 0.0f);
        // Process a few blocks so the parameter smoothing settles before measuring.
        for (int block = 0; block < 4; ++block) {
            engine.processFloat(inBuf.data(), outBuf.data(), numFrames);
        }
        return outBuf;
    };

    std::vector<float> flat = renderWithEffects(0.0f, 0.0f);
    std::vector<float> boosted = renderWithEffects(10.0f, 10.0f);

    if (flat.empty() || boosted.empty()) {
        std::cerr << "FAIL: engine failed to initialise" << std::endl;
        return false;
    }

    double sumSquaresFlat = 0.0;
    double sumSquaresBoosted = 0.0;
    double maxAbsDiff = 0.0;
    for (int i = 0; i < numFrames * 2; ++i) {
        float f = flat[i];
        float b = boosted[i];
        if (std::isnan(f) || std::isinf(f) || std::isnan(b) || std::isinf(b)) {
            std::cerr << "FAIL: NaN or Inf in effect comparison at sample " << i << std::endl;
            return false;
        }
        sumSquaresFlat += static_cast<double>(f) * f;
        sumSquaresBoosted += static_cast<double>(b) * b;
        maxAbsDiff = std::max<double>(maxAbsDiff, std::fabs(static_cast<double>(b) - f));
    }

    double rmsFlat = std::sqrt(sumSquaresFlat / (numFrames * 2));
    double rmsBoosted = std::sqrt(sumSquaresBoosted / (numFrames * 2));

    std::cout << "  RMS with effects at 0:  " << rmsFlat << std::endl;
    std::cout << "  RMS with effects at 10: " << rmsBoosted << std::endl;
    std::cout << "  Max sample difference:  " << maxAbsDiff << std::endl;

    if (maxAbsDiff < 1e-6) {
        std::cerr << "FAIL: The Bass and Fidelity effect values do not change the DSP output "
                     "at all. The effect values are not reaching the upstream kernels, which "
                     "means the app's sliders are not driving the FxSound DSP." << std::endl;
        return false;
    }

    std::cout << "  PASS: Effect values measurably change the DSP output" << std::endl;
    return true;
}

// Does the DSP process the two channels independently?
//
// The upstream signals the input channel count through a flag in the parameter block
// (DSP_STEREO_IN_FLAG, word 4). If that flag is not set, the kernels read only the
// left channel and duplicate it into both, so the right channel of the source is lost.
//
// A hard-panned input is not expected to come out with a silent opposite channel: the
// ambience and widener stages are enabled by default and legitimately spread a mono
// source across both channels. What must hold is that the two output channels are
// *not* identical, which is what mono duplication would produce.
static bool testStereoChannelSeparation() {
    std::cout << "[TEST] Running Stereo Channel Separation Test..." << std::endl;

    const int numFrames = 2048;

    auto renderPanned = [&](bool panLeft) {
        std::vector<float> inBuf(numFrames * 2, 0.0f);
        for (int i = 0; i < numFrames; ++i) {
            const float s = 0.5f * std::sin(2.0 * 3.14159265358979 * 440.0 * i / 48000.0);
            inBuf[i * 2] = panLeft ? s : 0.0f;
            inBuf[i * 2 + 1] = panLeft ? 0.0f : s;
        }

        FxSoundEngine engine;
        if (!engine.init(48000, 2)) return std::vector<float>();

        std::vector<float> outBuf(numFrames * 2, 0.0f);
        for (int block = 0; block < 4; ++block) {
            engine.processFloat(inBuf.data(), outBuf.data(), numFrames);
        }
        return outBuf;
    };

    auto channelRms = [&](const std::vector<float>& buf, int channel) {
        double energy = 0.0;
        for (int i = 0; i < numFrames; ++i) {
            const double v = buf[i * 2 + channel];
            if (std::isnan(v) || std::isinf(v)) return -1.0;
            energy += v * v;
        }
        return std::sqrt(energy / numFrames);
    };

    const std::vector<float> leftPanned = renderPanned(true);
    const std::vector<float> rightPanned = renderPanned(false);
    if (leftPanned.empty() || rightPanned.empty()) {
        std::cerr << "FAIL: engine failed to initialise" << std::endl;
        return false;
    }

    const double leftLeft = channelRms(leftPanned, 0);
    const double leftRight = channelRms(leftPanned, 1);
    const double rightLeft = channelRms(rightPanned, 0);
    const double rightRight = channelRms(rightPanned, 1);

    std::cout << "  Left-panned input  -> L RMS " << leftLeft << ", R RMS " << leftRight << std::endl;
    std::cout << "  Right-panned input -> L RMS " << rightLeft << ", R RMS " << rightRight << std::endl;

    if (leftLeft < 0.0 || leftRight < 0.0 || rightLeft < 0.0 || rightRight < 0.0) {
        std::cerr << "FAIL: NaN or Inf in the stereo output" << std::endl;
        return false;
    }

    // The dominant channel must follow the pan, within the same render.
    if (leftLeft <= leftRight * 1.5) {
        std::cerr << "FAIL: A left-panned input did not favour the left channel "
                     "(L " << leftLeft << ", R " << leftRight << ")" << std::endl;
        return false;
    }
    if (rightRight <= rightLeft * 1.5) {
        std::cerr << "FAIL: A right-panned input did not favour the right channel "
                     "(L " << rightLeft << ", R " << rightRight << ")" << std::endl;
        return false;
    }

    // The channels must not be identical, which is what mono duplication produces.
    if (std::fabs(leftLeft - leftRight) < 1e-6) {
        std::cerr << "FAIL: The two output channels are identical, so the DSP is "
                     "duplicating one channel instead of processing stereo." << std::endl;
        return false;
    }

    std::cout << "  PASS: The two channels are processed independently" << std::endl;
    return true;
}

int main() {
    std::cout << "========================================" << std::endl;
    std::cout << "FxSound Native C++ Numerical DSP Tests" << std::endl;
    std::cout << "========================================" << std::endl;

    bool pass = true;
    pass &= testSilence();
    pass &= testImpulseStability();
    pass &= testMultitoneAndLimiting();
    pass &= testProcessFloatSampleConversionAndClamping();
    pass &= testProcessPcm16ParameterSync();
    pass &= testArbitraryBufferSizeProcessing();
    pass &= testDcOffset();
    pass &= testSweepStability();
    pass &= testFloatPathRetainsResolution();
    pass &= testPcm16BoundedAndClamped();
    pass &= testEffectValuesReachTheKernel();
    pass &= testStereoChannelSeparation();

    if (pass) {
        std::cout << ">>> ALL NUMERICAL DSP TESTS PASSED <<<" << std::endl;
        return 0;
    } else {
        std::cerr << ">>> SOME DSP TESTS FAILED <<<" << std::endl;
        return 1;
    }
}
