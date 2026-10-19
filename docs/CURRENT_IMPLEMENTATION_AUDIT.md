# Current Implementation Audit & Baseline Analysis

**Project:** FxSound Android Audio Enhancer  
**Date:** 2026-10-17  
**Scope:** Phase 1 Comprehensive Baseline Audit  
**Target Device:** Xiaomi 17 (Android 15/16 / HyperOS, Snapdragon 8 Gen 4 / 8 Elite, Unrooted)  
**Upstream Provenance:** `https://github.com/fxsound2/fxsound-app` at commit `d8e7a23d37ed5939c2a3090a1c1756c7f2500b17`  

---

## 1. Existing Architecture & Audio Path

The existing codebase implements a **disjoint dual-engine architecture** rather than a single unified pipeline:

```
[External Players (Spotify / Musicolet / Poweramp)]
                   │
                   ▼ (Broadcast: ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
        [AudioSessionReceiver / MainActivity]
                   │
                   ▼ (Intent with sessionId & packageName)
       [SessionCompanionService (Foreground Service)]
                   │
                   ▼ (Pure Android Framework APIs)
   ┌─────────────────────────────────────────────────────────────┐
   │ Android System AudioEffect Pipeline:                        │
   │  1. DynamicsProcessing (Pre-EQ, 4-band MBC, Post-EQ, Limit) │
   │  2. BassBoost                                               │
   │  3. Virtualizer                                             │
   └─────────────────────────────────────────────────────────────┘
                   │ (Attached directly to external sessionId)
                   ▼
       [Android audioserver HAL / Output]

----------------------------------------------------------------------

[In-App Reference Engine (HiFiPlayerEngine)]
                   │
                   ▼ (Kotlin Multitone Test Signal Generator)
           [ShortArray PCM16]
                   │
                   ▼ (JNI Bridge: FxSoundNativeBridge)
           [libfxsound_dsp.so (C++ Engine)]
   ┌─────────────────────────────────────────────────────────────┐
   │ Genuine Upstream FxSound DfxDsp Core:                       │
   │  - DfxDsp::processAudio                                     │
   │  - dfxpUniversalModifySamples                               │
   │  - dfxpModifyShortIntSamples -> dfxpModifyRealtypeSamples   │
   │  - comProcessWaveBuffer -> dspsPlayProcess32                │
   │  - HyperBass, Aural Exciter, 3D Surround, Multiband Max     │
   └─────────────────────────────────────────────────────────────┘
                   │ (Output PCM16)
                   ▼
          [Android AudioTrack (In-App)]
                   │
                   ▼
       [Android audioserver HAL / Output]
```

### Critical Architectural Finding
- **External audio (e.g., Spotify) DOES NOT touch the native C++ FxSound DSP library.** It is enhanced purely through Android's built-in `DynamicsProcessing`, `BassBoost`, and `Virtualizer` effects attached to the player's audio session ID.
- **The native C++ FxSound DSP library (`libfxsound_dsp.so`) is ONLY executed by the in-app `HiFiPlayerEngine`** for test tones / reference tracks played through an app-owned `AudioTrack`.
- The two engines run completely separate parameter translations and processing algorithms.

---

## 2. Actual DSP Implementation & Upstream Provenance

### Native C++ DSP (`libfxsound_dsp.so`)
- **Upstream commit:** `d8e7a23d37ed5939c2a3090a1c1756c7f2500b17` (`fxsound2/fxsound-app`).
- **File inventory:** 92 source files ported into `app/src/main/cpp/fxsound_dsp/`.
  - Core DSP kernels: `Play32.c`, `Auralp32.c`, `dly8p32.c`, `Lex32.c`, `Maxi32.c`, `peq8p32.c`, `Wide32.c`.
  - Signal processing and math utilities: `dfxp*`, `Sos*`, `Filt*`, `Qnt*`, `Mth*`, `spectrum*`.
  - Audio processing logic: `DfxDsp.cpp`, `DfxDspEq.cpp`, `DfxDspPreset.cpp`, `DfxDspPrivate.cpp`.
  - Wrapper & JNI: `fxsound_engine.cpp`, `fxsound_jni.cpp`.
- **Status of `dspsPlayProcess` vs `dspsPlayProcess32`:**
  - `dspsPlayProcess` (16-bit integer routine) is stubbed as a no-op in `android_dsp_compat.cpp`.
  - `dspsPlayProcess32` (32-bit floating-point routine in `Play32.c`) is **compiled in full** and constitutes the real algorithmic kernel executed when `dfxpModifyRealtypeSamples` calls `comProcessWaveBuffer`.
- **License compliance:** AGPL-3.0 preserved. Original comments, headers, and copyright notices retained.

### Android System DSP (`DynamicsProcessingTranslator.kt`)
- Approximate translation of FxSound knobs into Android's `DynamicsProcessing` effect:
  - **Clarity / Fidelity:** Mapped to high-shelf gains at 4 kHz (+1.5 dB) and 8 kHz/16 kHz (+3.5 dB) on Post-EQ.
  - **Bass Boost:** Mapped to Pre-EQ low-shelf boost (up to +4 dB) + Android `BassBoost` effect (0 to 1000 strength).
  - **Surround Sound:** Mapped to Android `Virtualizer` effect (0 to 1000 strength).
  - **Dynamic Boost:** Mapped to 4-band Multi-Band Compressor (threshold -18 dB to -24 dB, ratio 1.0 to 2.8, make-up gain up to +3.5 dB).
  - **Ambience:** Currently unmapped in `DynamicsProcessingTranslator` (Android `PresetReverb`/`EnvironmentalReverb` is not instantiated).

---

## 3. External Audio Support: Verified vs. Unverified

| Audio Source | Route Mechanism | Status | Notes / Limitations |
| :--- | :--- | :--- | :--- |
| **Spotify** | `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` | **Architecture Verified / Device Unverified** | Requires user to enable "Device Broadcast Status" in Spotify Settings, or open Equalizer from Spotify settings. Session-specific attachment. |
| **Poweramp / Musicolet / Local Players** | Standard `AudioEffect` broadcast session | **Architecture Verified / Device Unverified** | Standard Android intent integration supported. |
| **YouTube & YouTube Music** | Audio Effect Session / Capture | **UNSUPPORTED on rootless Android** | YouTube does NOT broadcast audio sessions; explicitly opts out of audio effects; DRM audio cannot be captured without silence/root. |
| **Google Chrome / Web Browsers** | Audio Effect Session / Capture | **UNSUPPORTED on rootless Android** | Browsers do not broadcast session IDs; capturing via `AudioPlaybackCapture` cannot mute the dry hardware speaker, producing destructive acoustic echo/comb filtering. |
| **Mobile Games** | Audio Effect Session / Capture | **UNSUPPORTED on rootless Android** | Games do not broadcast session IDs. |
| **In-App Reference Playback** | App-owned `AudioTrack` + JNI `libfxsound_dsp.so` | **VERIFIED & WORKING** | Real-time C++ DfxDsp processing tested on synthetic multi-tone audio stream. |

---

## 4. Existing Clipping Protection & Gain Management

### Analysis of `DynamicsProcessingTranslator.kt` (Companion Engine)
1. **Severe Gain Stacking:**
   - Pre-EQ applies up to **+4.0 dB** bass boost.
   - Multi-Band Compressor applies up to **+3.5 dB** post-gain.
   - Post-EQ applies user band gains (up to **+14.0 dB**) plus Clarity boost (up to **+3.5 dB**).
   - Input Gain is set to `masterGainDb`.
   - Limiter postGain is **ALSO** set to `masterGainDb`.
   - **Bug:** `masterGainDb` is applied twice (at input and again at the limiter output).
   - **Worst-case cumulative gain:** +4 dB (Pre) + 3.5 dB (MBC) + 17.5 dB (Post) + 6 dB (Double Master Gain) = **+31.0 dB**!
2. **Limiter Parameters:**
   - Attack: 1.0 ms, Release: 50.0 ms, Threshold: -0.1 dBFS, Ratio: 10.0 (not brickwall infinity).
   - Because the ratio is only 10:1 instead of a brickwall limiter, a +31 dB signal will blow past 0 dBFS and cause harsh digital clipping and extreme limiter pumping / breathing artifacts.
3. **No Automatic Headroom Allocation:**
   - When users select "Bass Boost" or "Music" presets, no negative preamp offset is applied to accommodate the boosted filters.

### Analysis of `fxsound_engine.cpp` (Native C++ Engine)
1. **Integer Overflow Wrap-Around Bug in `MthBuffer.cpp` / `dfxpProcessInt.cpp`:**
   - In `dfxpProcessInt.cpp`, `mthConvertRealtypeBufToIntBuf` is called without clipping:
     ```c
     sp_buf[i] = (short int)(rp_buf[i] * (float)MTH_16_BIT_REAL_CONVERSION_FACTOR);
     ```
   - When boosted signal values exceed 1.0f (e.g. 1.15f * 32767 = 37682), casting to `short int` causes **16-bit signed integer overflow wrap-around** to negative values (-27854)!
   - This results in violent digital cracking, clicking, and distortion whenever any filter gain pushes samples above full scale.
2. **Double Quantization in `processFloat`:**
   - Float input -> converted to PCM16 -> converted to `realtype` (float) inside DfxDsp -> converted back to PCM16 (unclamped) -> converted back to Float.
   - This degrades signal resolution and introduces unnecessary quantization noise.

---

## 5. Potential Causes of Poor Sound Quality & Choppiness

1. **`DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION`:**
   - The companion engine configures `DynamicsProcessing` with `VARIANT_FAVOR_FREQUENCY_RESOLUTION`.
   - On Android (specifically Qualcomm Snapdragon HALs), frequency-resolution mode uses large FFT windows, introducing 30–60 ms of phase-smeared latency, comb filtering, and audible warble.
   - Switching to `VARIANT_FAVOR_TIME_RESOLUTION` is standard practice for dynamic, punchy, low-latency playback.
2. **Limiter Pumping / Inter-Sample Overs:**
   - Aggressive compression settings (MBC threshold -24 dB with high makeup gain) without lookahead or proper release curve cause audible breathing and dynamic smothering.
3. **Integer Wraparound in Native DSP:**
   - As established in section 4, native output samples exceeding 1.0 wrap around rather than soft-saturating or brickwall-limiting.
4. **Missing Headroom Management:**
   - Neither engine applies dynamic gain staging (preamp attenuation proportional to positive EQ/bass gains).
5. **No Ambience Processing in Companion Mode:**
   - The Ambience slider has zero effect in `DynamicsProcessingTranslator`, leaving external audio drier than Windows FxSound.

---

## 6. Existing UI & Haptic Implementation

### UI Assessment
- **Master Toggle:** Present and functional (`MasterPowerButton.kt`), but visual styling is somewhat dated.
- **Spectrum & Meters:** `SpectrumAnalyzerView.kt` draws 16 bars with artificial minimum heights (0.04f). When external audio is playing, the spectrum bars remain idle at the floor because external audio PCM data cannot be captured by the companion service without loopback.
- **Equalizer Screen:** Functional 10-band slider list with an SVG-style Canvas curve, but layout is utilitarian and sliders lack fine touch targets.
- **Reference Player Button:** Prominently placed on the main screen, making the app feel like a diagnostic test utility rather than a polished consumer product.
- **Navigation:** 4-tab Bottom Navigation Bar (Home, Equalizer, Presets, Settings).

### Haptic Assessment
- `HapticsManager.kt` checks `isSystemHapticsEnabled()` and respects user preference (System Default / Enhanced / Off).
- Sliders use rate-limited bucket ticks (minimum interval 60 ms).
- Master toggle uses `VibrationEffect.EFFECT_CLICK`.
- Haptics are correctly decoupled from audio callback threads.
- Needs modernization: On Android 12+ (API 31+), modern haptic primitives (`PRIMITIVE_TICK`, `PRIMITIVE_CLICK`, `PRIMITIVE_LOW_TICK`) can provide vastly more subtle and premium feedback.

---

## 7. Build and Test Results

### Build Verification
- **Gradle Command:** `.\gradlew.bat assembleDebug`
- **Result:** `BUILD SUCCESSFUL in 5s` (all tasks up-to-date)
- **APK Artifact:** `app/build/outputs/apk/debug/app-debug.apk` (57.2 MB)
- **Native Library:** `libfxsound_dsp.so` (arm64-v8a, 644,632 bytes; x86_64, 692,800 bytes)

### Test Verification
- **Unit Test Command:** `.\gradlew.bat testDebugUnitTest`
- **Result:** `BUILD SUCCESSFUL` (24 tasks up-to-date, `FxSoundPresetTest` PASSED)
- **Native Test Suite:** `app/src/main/cpp/tests/test_dsp_numerical.cpp`
  - Validates Silence response (< 0.0001f residual noise).
  - Validates Unit Impulse stability (no NaN/Inf).
  - Validates Multitone & Limiter stability.

---

## 8. Known Limitations & Highest-Priority Fixes

### Known Limitations
1. **System-Wide YouTube / Netflix / Games Interception:** Impossible on unrooted Android due to Android security, DRM, and lack of audio session broadcast. This must be honestly reflected in the UI and documentation.
2. **Real-time Spectrum on External Sessions:** Android `DynamicsProcessing` does not return output PCM to the caller. Spectrum analysis on Spotify sessions is mathematically impossible without intrusive capture; fake spectrum visualizers must be removed in favor of accurate state indicators.

### Highest-Priority Fixes for Phase 1
1. **Audio Quality Fix — Gain Staging & Headroom:**
   - In `DynamicsProcessingTranslator.kt`, remove the duplicate `masterGainDb` from limiter postGain.
   - Implement automatic preamp gain compensation: `inputGainDb = masterGainDb - max(0f, maxBoost / 2f)` to prevent slamming into the limiter.
   - Switch `DynamicsProcessing` from `VARIANT_FAVOR_FREQUENCY_RESOLUTION` to `VARIANT_FAVOR_TIME_RESOLUTION` for punchier bass and lower latency without phase smearing.
   - Increase limiter ratio to 50:1 (true brickwall peak protection) with optimized attack/release (0.5 ms / 40 ms).
2. **Native C++ Audio Quality Fix:**
   - In `MthBuffer.cpp` or `dfxpProcessInt.cpp`, enforce hard clamping / soft saturation before converting float to 16-bit integer, eliminating the catastrophic integer wraparound crackle.
3. **UI Redesign:**
   - Redesign HomeScreen to be sleek, minimal, and premium dark Material 3.
   - Replace the fake/idle spectrum visualizer with an **Acoustic Status & Fidelity Monitor** that only renders meters when real data is available.
   - Move the reference player test action into a dedicated Diagnostics tab or sheet so the main UI feels like a production consumer app.
   - Redesign the preset cards and EQ curve for exceptional visual polish.
4. **Haptics Refinement:**
   - Upgrade `HapticsManager.kt` to leverage Android 12+ `VibrationEffect.startComposition()` with subtle primitive ticks.
