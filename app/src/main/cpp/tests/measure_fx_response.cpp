/*
 * measure_fx_response.cpp
 *
 * Measures the authentic FxSound DSP's response so the Android companion engine can be
 * a *measured* emulation of it rather than a hand-authored guess.
 *
 * Two measurements are produced:
 *
 *   1. MAGNITUDE. For each effect, at a few settings, and with that effect alone
 *      active, a steady sine is played at each probe frequency and the fundamental's
 *      amplitude is measured with a Goertzel filter. The result is the effect's
 *      magnitude response in dB. Goertzel is used rather than an FFT because it isolates
 *      the fundamental from the harmonics the exciter adds, and it needs no FFT code.
 *
 *   2. LEVEL. For the dynamic stages, a 1 kHz tone is played at a series of input levels
 *      and the output level is measured, which gives the gain reduction curve that a
 *      compressor and a limiter produce.
 *
 * The output is CSV on stdout so the results can be captured as a file and turned into
 * the Android DynamicsProcessing mapping tables.
 *
 * Not upstream code. Part of the FxSound Android port.
 */

#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>
#include <vector>

#include "fxsound_engine.h"

namespace {

const double kSampleRate = 48000.0;
const int kBlockFrames = 1024;
const int kSettleBlocks = 36;             // 36864 frames, 0.77 s: lets the filter state converge
const int kMeasureBlocks = 8;             // 8192 frames: one Goertzel window
const int kMeasureWindows = 4;            // windows averaged, so a modulated reverb's comb
                                         // does not turn into a spurious notch
const int kSettleFrames = kSettleBlocks * kBlockFrames;
const int kMeasureFrames = kMeasureBlocks * kBlockFrames;

// The ten ISO band centres the app's EQ uses, plus enough extra points between and
// beyond them to describe the shape of each curve.
const double kProbeFrequencies[] = {
    20.0, 31.5, 32.0, 40.0, 50.0, 63.0, 64.0, 80.0, 100.0, 125.0,
    160.0, 200.0, 250.0, 315.0, 400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0,
    1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0, 10000.0,
    12500.0, 16000.0, 20000.0
};
const int kProbeCount = static_cast<int>(sizeof(kProbeFrequencies) / sizeof(kProbeFrequencies[0]));

const float kToneAmplitude = 0.1f;

struct EffectProbe {
    const char* name;
    FxSoundEffectId id;
};

/**
 * Amplitude of the component at `frequency` in the given mono buffer, using a Goertzel
 * filter. The buffer must be a single channel: an interleaved stereo buffer treated as a
 * mono sequence halves the apparent frequency, because successive samples are L,R,L,R
 * rather than consecutive points of the waveform.
 */
double goertzelAmplitude(const std::vector<float>& buffer, double frequency) {
    const int n = static_cast<int>(buffer.size());
    if (n == 0) return 0.0;

    const double omega = 2.0 * M_PI * frequency / kSampleRate;
    const double coeff = 2.0 * std::cos(omega);
    double s1 = 0.0;
    double s2 = 0.0;

    for (int i = 0; i < n; ++i) {
        const double s0 = static_cast<double>(buffer[i]) + coeff * s1 - s2;
        s2 = s1;
        s1 = s0;
    }

    const double real = s1 - s2 * std::cos(omega);
    const double imag = s2 * std::sin(omega);
    return 2.0 * std::sqrt(real * real + imag * imag) / n;
}

/**
 * Plays a steady sine through the engine and returns the amplitude of the fundamental
 * at the output, in dB relative to the input amplitude.
 */
double measureToneResponse(FxSoundEngine& engine, double frequency) {
    std::vector<float> inBuffer(kBlockFrames * 2, 0.0f);
    std::vector<float> outBuffer(kBlockFrames * 2, 0.0f);
    std::vector<float> measureBuffer(kMeasureFrames, 0.0f);

    const double omega = 2.0 * M_PI * frequency / kSampleRate;
    const int totalBlocks = kSettleBlocks + kMeasureWindows * kMeasureBlocks;
    int framesPlayed = 0;

    double powerSum = 0.0;
    int windowsMeasured = 0;

    for (int block = 0; block < totalBlocks; ++block) {
        for (int i = 0; i < kBlockFrames; ++i) {
            const double phase = omega * static_cast<double>(framesPlayed + i);
            const float sample = static_cast<float>(kToneAmplitude * std::sin(phase));
            inBuffer[i * 2] = sample;
            inBuffer[i * 2 + 1] = sample;
        }

        engine.processFloat(inBuffer.data(), outBuffer.data(), kBlockFrames);

        if (block >= kSettleBlocks) {
            const int slot = (block - kSettleBlocks) % kMeasureBlocks;
            for (int i = 0; i < kBlockFrames; ++i) {
                measureBuffer[slot * kBlockFrames + i] = outBuffer[i * 2];
            }
        }

        framesPlayed += kBlockFrames;

        // A window is complete once its last block has been written.
        const int measuredBlocks = block - kSettleBlocks + 1;
        if (measuredBlocks > 0 && measuredBlocks % kMeasureBlocks == 0) {
            const double amplitude = goertzelAmplitude(measureBuffer, frequency);
            powerSum += amplitude * amplitude;
            windowsMeasured++;
        }
    }

    if (windowsMeasured == 0) {
        return -120.0;
    }

    const double rmsAmplitude = std::sqrt(powerSum / windowsMeasured);
    if (rmsAmplitude <= 0.0) {
        return -120.0;
    }
    return 20.0 * std::log10(rmsAmplitude / kToneAmplitude);
}

/**
 * Plays a 1 kHz tone at the given input amplitude and returns the output amplitude.
 */
double measureLevelResponse(FxSoundEngine& engine, double inputAmplitude) {
    std::vector<float> inBuffer(kBlockFrames * 2, 0.0f);
    std::vector<float> outBuffer(kBlockFrames * 2, 0.0f);
    std::vector<float> measureBuffer(kMeasureFrames, 0.0f);

    const double omega = 2.0 * M_PI * 1000.0 / kSampleRate;
    int framesPlayed = 0;

    for (int block = 0; block < kSettleBlocks + kMeasureBlocks; ++block) {
        for (int i = 0; i < kBlockFrames; ++i) {
            const double phase = omega * static_cast<double>(framesPlayed + i);
            const float sample = static_cast<float>(inputAmplitude * std::sin(phase));
            inBuffer[i * 2] = sample;
            inBuffer[i * 2 + 1] = sample;
        }

        engine.processFloat(inBuffer.data(), outBuffer.data(), kBlockFrames);

        if (block >= kSettleBlocks) {
            const int slot = block - kSettleBlocks;
            for (int i = 0; i < kBlockFrames; ++i) {
                const int index = slot * kBlockFrames + i;
                measureBuffer[index] = outBuffer[i * 2];
            }
        }

        framesPlayed += kBlockFrames;
    }

    return goertzelAmplitude(measureBuffer, 1000.0);
}

/**
 * The upstream's ambience stage is bypassed at or below MIDI liveliness 12, but the stage
 * still runs, and the wet/dry warp in dfxp_CommunicateAmbience() gives it a *negative*
 * wet gain below that point:
 *
 *     wet_gain = (pc_liveliness - 12) * (1/28) * 0.273
 *
 * so at liveliness 0 the reverb is phase inverted rather than silent, which comb filters
 * the whole output at the graphic EQ's band centres. Measuring the tonal effects with the
 * reverb's wet mix at zero removes that contamination. 12/127 of the 0-10 control range.
 */
const float kAmbienceWetZero = (12.0f / 127.0f) * 10.0f;

/**
 * Initialises an engine with every effect silenced, so a single effect can be measured on
 * its own. The engine's constructor starts every effect at the default of 5, which has to
 * be wound back before a per-effect transfer function means anything.
 *
 * The engine is initialised in place rather than returned by value: it owns the DSP core
 * and has no move constructor, so copying it would free the core twice.
 */
void initIsolatedEngine(FxSoundEngine& engine) {
    // Measurement mode: the mapping data must isolate the upstream kernels, so the
    // static preamp headroom compensation is off (its full boost compensation would
    // cancel the effects being measured). The output limiter stays active.
    engine.setHeadroomManagementEnabled(false);
    engine.init(static_cast<int>(kSampleRate), 2);

    engine.setEffect(FXSOUND_EFFECT_BASS, 0.0f);
    engine.setEffect(FXSOUND_EFFECT_FIDELITY, 0.0f);
    engine.setEffect(FXSOUND_EFFECT_SURROUND, 0.0f);
    engine.setEffect(FXSOUND_EFFECT_DYNAMIC_BOOST, 0.0f);
    engine.setEffect(FXSOUND_EFFECT_AMBIENCE, kAmbienceWetZero);
}

} // namespace

int main(int argc, char** argv) {
    const bool csvOnly = (argc > 1 && std::string(argv[1]) == "--csv");

    const EffectProbe effects[] = {
        { "BASS",       FXSOUND_EFFECT_BASS },
        { "FIDELITY",   FXSOUND_EFFECT_FIDELITY },
        { "AMBIENCE",   FXSOUND_EFFECT_AMBIENCE },
        { "SURROUND",   FXSOUND_EFFECT_SURROUND },
        { "DYNAMIC",    FXSOUND_EFFECT_DYNAMIC_BOOST }
    };
    // Every integer setting is measured, so the mapping tables derived from this data
    // are exact at the settings the app's sliders actually land on, and linear
    // interpolation between them is accurate to well under a decibel.
    float settings[11];
    for (int i = 0; i <= 10; ++i) {
        settings[i] = static_cast<float>(i);
    }

    if (!csvOnly) {
        printf("# FxSound native DSP response measurement\n");
        printf("# Generated by measure_fx_response.cpp\n");
        printf("#\n");
    }
    printf("measurement,effect,setting,freq_hz,value_db\n");

    // Reference: every effect off. The response should be flat at 0 dB. If it is not, the
    // measurement itself is wrong and nothing below can be trusted.
    {
        FxSoundEngine engine;
        initIsolatedEngine(engine);
        for (int p = 0; p < kProbeCount; ++p) {
            const double db = measureToneResponse(engine, kProbeFrequencies[p]);
            printf("magnitude,REFERENCE,0.0,%.1f,%.3f\n", kProbeFrequencies[p], db);
            fflush(stdout);
        }
    }

    // Diagnostic second reference: the same engine but with the 31-band graphic EQ
    // flattened to 0 dB in every upstream state storage (memory and registry). If the
    // reference notches come from the EQ's uninitialized pre-preset state, this curve is
    // flat while the default one is not; if it is identical, the notches come from
    // somewhere else.
    {
        FxSoundEngine engine;
        initIsolatedEngine(engine);
        engine.resetEQ();
        for (int p = 0; p < kProbeCount; ++p) {
            const double db = measureToneResponse(engine, kProbeFrequencies[p]);
            printf("magnitude,REFERENCE_FLATEQ,0.0,%.1f,%.3f\n", kProbeFrequencies[p], db);
            fflush(stdout);
        }
    }

    // Batón test: raw passthrough. With the power off the engine copies input to output
    // byte for byte, so this curve must read exactly 0.000 dB at every probe. If it
    // does not, the tone generator or the Goertzel extractor is broken and every other
    // curve here is meaningless.
    {
        FxSoundEngine engine;
        initIsolatedEngine(engine);
        engine.setPower(false);
        for (int p = 0; p < kProbeCount; ++p) {
            const double db = measureToneResponse(engine, kProbeFrequencies[p]);
            printf("magnitude,REFERENCE_RAW,0.0,%.1f,%.3f\n", kProbeFrequencies[p], db);
            fflush(stdout);
        }
    }

    // Diagnostic third reference: the EQ stage bypassed entirely (eqOn(false)). If this
    // curve is flat while the others are not, the notches live in the EQ stage even with
    // zero gain on every band, which points at the band frequencies or filter
    // construction rather than the gains.
    {
        FxSoundEngine engine;
        initIsolatedEngine(engine);
        engine.setEqEnabled(false);
        for (int p = 0; p < kProbeCount; ++p) {
            const double db = measureToneResponse(engine, kProbeFrequencies[p]);
            printf("magnitude,REFERENCE_NOEQ,0.0,%.1f,%.3f\n", kProbeFrequencies[p], db);
            fflush(stdout);
        }
    }

    for (const EffectProbe& effect : effects) {
        for (float setting : settings) {
            FxSoundEngine engine;
            initIsolatedEngine(engine);
            engine.setEffect(effect.id, setting);

            for (int p = 0; p < kProbeCount; ++p) {
                const double db = measureToneResponse(engine, kProbeFrequencies[p]);
                printf("magnitude,%s,%.1f,%.1f,%.3f\n",
                       effect.name, static_cast<double>(setting), kProbeFrequencies[p], db);
                fflush(stdout);
            }
        }
    }

    // Level response, which is what a compressor and a limiter actually do.
    for (float setting : settings) {
        FxSoundEngine engine;
        initIsolatedEngine(engine);
        engine.setEffect(FXSOUND_EFFECT_DYNAMIC_BOOST, setting);

        for (int db = -48; db <= 0; db += 1) {
            const double inputAmplitude = std::pow(10.0, db / 20.0);
            const double outputAmplitude = measureLevelResponse(engine, inputAmplitude);
            const double gain = (outputAmplitude > 0.0) ? 20.0 * std::log10(outputAmplitude / inputAmplitude) : -120.0;
            printf("level,DYNAMIC,%.1f,%d,%.3f\n", static_cast<double>(setting), db, gain);
            fflush(stdout);
        }
    }

    return 0;
}
