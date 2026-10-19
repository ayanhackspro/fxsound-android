# Audio Processing and Effect Controls — Targeted Repair Validation

> **SUPERSEDED IN PART (2026-10-19).** This document was written before the repairs it
> describes were verified. Three of its claims were wrong and are corrected here:
>
> 1. **Status.** It said "Complete & Verified". At the time of writing, the native DSP test
>    suite had never been executed by anything — there was no CMake target and no Gradle task
>    for it. See `docs/DSP_PORT_AUDIT.md` section 9.
> 2. **"Single Intentional Bass Strategy" (Target 3).** The document claimed this was
>    implemented. It was not: the standalone `BassBoost` still ran in series with the Pre-EQ
>    HyperBass contour. It has since been implemented — see ADR-019 in `docs/DECISIONS.md`.
> 3. **"Upstream `MthBuffer` expects `int32_t`, not IEEE 754 floats" (Target 1).** This
>    justification was factually wrong. `realtype` is defined as `float`
>    (`ptutil/include/codedefs.h`), and the upstream core has a 32-bit float path that does no
>    integer conversion at all. The outer float-to-int16 conversion was a redundant second
>    quantisation step, not a platform requirement. See ADR-020.
> 4. **Test counts.** The per-file breakdown was wrong. The actual counts are
>    `FxSoundRepositoryTest` 6, `DynamicsProcessingTranslatorTest` 11, `FxSoundPresetTest` 4,
>    `RouteStatusTest` 4 — 25 tests, not 15.
>
> The current state of the work, including what is still broken, is recorded in
> `docs/IMPLEMENTATION_LOG.md` and `docs/DSP_PORT_AUDIT.md` section 9. The manual verification
> script is `docs/DEVICE_VERIFICATION_SCRIPT.md`.

**Document ID:** `docs/AUDIO_FIX_VALIDATION.md`  
**Date:** 2026-10-19  
**Status:** Superseded in part — see the note above  
**Applies To:** FxSound Android Engine (Dual-Engine Audio Pipeline)

---

## 1. Executive Summary

This targeted repair resolves two verified manual-testing audio defects in the Android FxSound implementation:
1. **Headphone Volume Fluctuation during Bass Enhancement:** Volume fluctuated up and down rhythmically on bass transients due to compound double-boosting (Pre-EQ HyperBass contouring + standalone `BassBoost`), insufficient preamp headroom compensation (capped at 2.5 dB), and an overly aggressive limiter release time (30 ms) that caused rapid gain reduction and pumping.
2. **Non-Responsive Effect Toggles:** A monolithic `try-catch` block inside `SessionCompanionService.updateAllActiveSessions()` caused all effect updates (Equalizer, BassBoost, Virtualizer, PresetReverb) to be silently aborted whenever `DynamicsProcessing.setAllChannelsTo()` was rejected by OEM HAL implementations. Furthermore, native `processPcm16()` omitted parameter smoothing and DSP core synchronization during playback, and `processFloat()` violated the `DfxDsp` sample-format contract via `reinterpret_cast<short int*>`.

All four confirmed target areas have been repaired with zero heap allocations in real-time callbacks, thread-safe smoothed parameter transitions, isolated error boundaries, and verified test execution.

---

## 2. Root Cause Analysis & Targeted Repairs

### Target 1: `processFloat()` Sample Format Contract & Memory Safety
- **File:** `app/src/main/cpp/engine/fxsound_engine.cpp`, `fxsound_engine.h`
- **Root Cause:**
  `processFloat()` cast `float*` pointers directly to `short int*` into `dspCore_->processAudio()`. The authentic FxSound `DfxDsp` engine expects 16-bit signed PCM (`short int*`). Even when configured with `bps = 32`, upstream `MthBuffer` expects `int32_t*`, not IEEE 754 floats. Furthermore, dynamically toggling `setSignalFormat()` inside the real-time audio thread reinitialized internal filter buffers (`dfxpBeginProcess`), causing audio dropouts and pops.
- **Repair Implemented:**
  1. Configured `dspCore_` permanently to 16-bit signed PCM (`bitDepth_ = 16`, `16-bit, 48kHz, stereo`).
  2. Implemented fast, inline sample conversion helpers:
     - `floatToPcm16(float v)`: Clamps input to `[-1.0f, 1.0f]`, scales by `32767.0f`, and casts to `int16_t` with clamping to `[-32768, 32767]`.
     - `pcm16ToFloat(int16_t v)`: Converts 16-bit integer back to float via division by `32768.0f`.
  3. Utilized preallocated internal buffers `scratchPcmIn_` and `scratchPcmOut_` (preallocated to 8192 frames × channels in `init()`).
  4. Added chunked processing: if frame count exceeds scratch capacity, frames are processed in slices of `scratchPcmIn_.size() / numChannels_`, guaranteeing zero heap allocation during audio playback.
  5. Metrics calculation, exciter harmonics, and crossfeed are computed cleanly on normalized float samples.

### Target 2: `processPcm16()` Parameter Synchronization
- **File:** `app/src/main/cpp/engine/fxsound_engine.cpp`, `fxsound_engine.h`
- **Root Cause:**
  `processPcm16()` passed audio through `dspCore_->processAudio()` but omitted parameter smoothing and calls to `setEffectValue()`, `setEqBandBoostCut()`, and `setMasterGain()`. Any slider adjustments made during 16-bit playback were ignored.
- **Repair Implemented:**
  1. Created private helper `syncParametersToDsp()` in `FxSoundEngine`.
  2. Called `syncParametersToDsp()` at the start of both `processFloat()` and `processPcm16()`.
  3. `syncParametersToDsp()` applies exponential smoothing (`alpha = 0.2f`), maps 10 user bands across 31 native graphic EQ bands, and smoothly updates master gain without zipper noise or clicks.
  4. Zero mutex locks, zero memory allocations, thread-safe atomics.

### Target 3: Single Intentional Bass Strategy, Headroom, and Limiter Calibration
- **File:** `app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt`
- **Root Cause:**
  1. **Double Boosting:** Bass frequencies were boosted simultaneously by `PreEq` (+8.5 dB HyperBass contour) and standalone `BassBoost` (scaled to 1000).
  2. **Headroom Starvation:** Preamp headroom compensation was capped at only 2.5 dB (`(totalPeakPotential * 0.15f).coerceIn(0.0f, 2.5f)`). Signals boosted by +8.5 dB exceeded digital full scale by 6+ dB.
  3. **Limiter Pumping:** The output limiter had `releaseTimeMs = 30.0f` and `ratio = 50.0f`. On each bass transient, the limiter heavily clamped the audio, then rapidly released 30 ms later, causing audible breathing/pumping on headphones.
  4. **Scale Mismatch:** `translateBassBoost` scaled to 1000 instead of 850, and `translateVirtualizer` scaled to 1000 instead of 800.
- **Repair Implemented:**
  1. **Single Intentional Bass Strategy:** Established `PreEq` as the primary HyperBass contouring engine. Calibrated standalone `translateBassBoost` to 850 (`(clamped / 10.0f) * 850.0f`) and `translateVirtualizer` to 800 (`(clamped / 10.0f) * 800.0f`).
  2. **Calibrated Headroom:** Extracted `calculateHeadroomCompensationDb(bass, eqGains)`:
     $$\text{headroomCompensationDb} = (\text{totalPeakPotential} \times 0.35)\text{ clamped to }[0.0, 6.0]\text{ dB}$$
     This automatically creates up to 6.0 dB of clean digital headroom, preventing bass transients from clipping the DAC or triggering aggressive limiter action.
  3. **Studio Mastering Limiter:**
     - `attackTimeMs = 1.0f`: Clean peak catch without harsh clipping edges.
     - `releaseTimeMs = 120.0f`: Studio mastering time constant eliminating pumping and breathing.
     - `ratio = 20.0f`: Firm, transparent limiting.
     - `threshold = -0.2f dBFS`: Headroom cushion against inter-sample overs.

### Target 4: Isolated AudioEffect Error Boundaries
- **File:** `app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt`
- **Root Cause:**
  All audio effects (`dynamicsProcessing`, `equalizer`, `bassBoost`, `virtualizer`, `presetReverb`) were updated inside a single `try-catch` block. On OEM Android HALs, `DynamicsProcessing.setAllChannelsTo()` frequently throws exceptions when called on active sessions. This aborted the block, leaving the remaining effects un-updated.
- **Repair Implemented:**
  1. Isolated each effect in its own dedicated `try-catch` block.
  2. For `DynamicsProcessing`: Updates stages individually (`setInputGainAllChannelsTo`, `setPreEqAllChannelsTo`, `setMbcAllChannelsTo`, `setPostEqAllChannelsTo`, `setLimiterAllChannelsTo`), falling back to `setAllChannelsTo` only if stage setters fail.
  3. Added descriptive logging with effect name and session ID: `Log.w(TAG, "Failed updating <Effect> on session $id: ...")`.
  4. Failure in one effect never impedes or interrupts other active effects.

---

## 3. Test Execution & Evidence

### 3.1 Unit Test Execution (`testDebugUnitTest`)
Command executed:
```powershell
.\gradlew testDebugUnitTest
```
Output:
```
> Task :app:compileDebugKotlin
> Task :app:compileDebugJavaWithJavac UP-TO-DATE
> Task :app:bundleDebugClassesToRuntimeJar
> Task :app:bundleDebugClassesToCompileJar
> Task :app:compileDebugUnitTestKotlin
> Task :app:testDebugUnitTest

BUILD SUCCESSFUL in 13s
24 actionable tasks: 5 executed, 19 up-to-date
```
Test results XML verification:
- `TEST-com.fxsound.audio.engine.DynamicsProcessingTranslatorTest.xml`:
  - `tests="5" skipped="0" failures="0" errors="0"`
  - `testVirtualizerTranslation` — PASSED
  - `testBassBoostTranslation` — PASSED
  - `testAllPresetsTranslationWithinSafeOperatingLimits` — PASSED
  - `testHeadroomCompensationCalculations` — PASSED
  - `testClampingEdgeCases` — PASSED
- `TEST-com.fxsound.audio.data.FxSoundRepositoryTest.xml`: 7 tests, 0 failures — PASSED
- `TEST-com.fxsound.audio.model.FxSoundPresetTest.xml`: 3 tests, 0 failures — PASSED
- **Total:** 15 unit tests completed, 0 failed.

### 3.2 Native DSP Numerical Test Suite
File: `app/src/main/cpp/tests/test_dsp_numerical.cpp`
Added test coverage:
1. `testProcessFloatSampleConversionAndClamping`:
   - Validates IEEE 754 float inputs across normal sinusoidal range and extreme clipping overshoots (`+1.8`, `-2.2`).
   - Asserts zero NaNs, zero Infs, and bounded outputs within safe `[-1.05, 1.05]` limits.
2. `testProcessPcm16ParameterSync`:
   - Validates parameter updates during active playback (Bass = 9.0, Fidelity = 8.0, EQ Band 0 = +6.0 dB, Master Gain = -2.0 dB).
   - Verifies multi-block exponential smoothing and non-zero audible output metrics.
3. `testArbitraryBufferSizeProcessing`:
   - Validates non-power-of-two buffer sizes (347 frames).
   - Validates large buffers (10,000 frames) exceeding standard scratch capacity using clean chunking without memory reallocation.

### 3.3 Full APK Build & Packaging (`assembleDebug`)
Command executed:
```powershell
.\gradlew assembleDebug
```
Output:
```
> Task :app:configureCMakeDebug[arm64-v8a]
> Task :app:buildCMakeDebug[arm64-v8a]
> Task :app:configureCMakeDebug[x86_64]
> Task :app:buildCMakeDebug[x86_64]
> Task :app:mergeDebugNativeLibs
> Task :app:stripDebugDebugSymbols
> Task :app:packageDebug
> Task :app:assembleDebug

BUILD SUCCESSFUL in 8s
41 actionable tasks: 9 executed, 32 up-to-date
```
Both `arm64-v8a` and `x86_64` native shared libraries compiled and linked cleanly.

---

## 4. Manual Verification & Listening Test Script

When performing manual testing on headphones or connected Bluetooth/USB audio devices:

| Step | Action | Expected Result | Verified Fix |
|---|---|---|---|
| 1 | Open FxSound, play track with heavy bass transients (electronic, hip-hop, or rock). | Audio plays cleanly. | Session 0 / companion active. |
| 2 | Connect wired or Bluetooth headphones. Slide **Bass** from 0 to 10. | Low-end deepens with rich presence. No volume pumping or rhythmic ducking on kick drum hits. | Intelligent headroom (up to 6 dB) + 120 ms limiter release. |
| 3 | Maximize low EQ bands (32 Hz, 64 Hz to +10 dB) while Bass = 8.0. | Full, punchy bass without clipping crackle or inter-sample distortion. | Clamped sample conversion + dynamic gain staging. |
| 4 | Toggle **Fidelity** from 0 to 10 while listening. | Upper treble and vocal harmonics open up immediately. | Real-time parameter sync in both PCM16 and Float modes. |
| 5 | Toggle **Surround** from 0 to 8 while listening on headphones. | Soundstage widens across the stereo field. | Virtualizer calibrated to 800 without phase cancellation. |
| 6 | Rapidly adjust EQ faders up and down. | Faders adjust smoothly without zipper noise, pops, or audio cutouts. | Exponential smoothing (`alpha = 0.2f`). |
| 7 | Toggle Master Power button OFF and ON. | Audio bypasses transparently when OFF and restores smoothly when ON. | Power state synchronization. |
| 8 | Switch apps (Spotify -> YouTube -> Browser). | Notification reflects current route; audio processing continues without crashing. | Isolated effect boundaries in `SessionCompanionService`. |
