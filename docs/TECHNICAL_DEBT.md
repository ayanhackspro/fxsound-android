# Verified Technical Debt, Architectural Risks & Recommended Fixes

**Project:** FxSound Android Audio Enhancer  
**Status:** Phase 1 Technical Audit  
**Date:** 2026-10-17  

---

## 1. High-Impact Technical Debt & Risk Summary

| Category | Issue | Severity | Risk | Recommended Remediation |
| :--- | :--- | :---: | :--- | :--- |
| **Performance** | Rapid `uiState` mutations trigger full-screen recomposition at 47 Hz | **HIGH** | Increased CPU usage, battery drain, and thermal throttling on mobile devices | Split `AcousticMetrics` into a separate `StateFlow` consumed exclusively by `AcousticMonitorView` |
| **Persistence** | State is 100% in-memory; no DataStore integration | **HIGH** | User EQ customizations, slider levels, and power state are lost on process death | Implement `FxSoundPreferencesDataStore` with 200ms write debouncing |
| **Audio Quality** | Native `processFloat()` quantizes through PCM16 | **RESOLVED** | — | Fixed by ADR-020 (float core end to end). The SNR gain-staging precondition was then fixed by ADR-025: the native engine no longer clips at the digital rail (worst-case native test: output peak 0.997, zero rail-pinned samples). |
| **DSP Correctness** | The upstream FxSound DSP ran with uncalibrated default kernel parameters, and the app's five effect values never reached the kernels | **RESOLVED** | — | Fixed by ADR-023: the registry round trip was implemented in memory, and the parameter overlay structs now use 32-bit types with a compile-time layout assertion. The native suite passes 11 of 11. |
| **DSP Bring-up** | The DSP core was never initialised on Android: the registry stubs failed, so `dfxpInit()` aborted before assigning the handle | **RESOLVED** | — | Fixed by making the registry stubs report "no such value" instead of an error. See `docs/DSP_PORT_AUDIT.md` section 9.1. |
| **Version Control** | `.gitignore` excluded `/app/` and `/docs/`, so the entire project and the Gradle wrapper were untracked | **HIGH (RESOLVED, NOT COMMITTED)** | The whole codebase was unrecoverable from the repository, and a clone could not build. | The `.gitignore` was corrected. **A commit is still required.** |
| **Build Flags** | The native build uses `-ffast-math` | **LOW** | `-ffast-math` implies `-ffinite-math-only`, so NaN and Inf are not handled per IEEE, which conflicts with the "never allow NaN/Inf to propagate" requirement in `docs/DSP_QUALITY_SPEC.md`. It also diverges from the upstream Windows build, which does not use it. | Evaluate removing it and compare the output against the current build with a loudness-matched listening test before changing it. Not changed in this pass, because it alters the DSP's numerical behaviour and needs measurement. |
| **Route Tracking** | `AudioFlingerSessionTracker` parses the first integer before "active" from a `dumpsys media.audio_flinger` track line, but AudioFlinger track IDs and `AudioEffect` session IDs are different numbering spaces | **MEDIUM** | If the tracker returns IDs that are not attachable, the "full hook" path silently does nothing while the UI reports that tracking is active. | Confirm against a real `dumpsys media.audio_flinger` capture on the device that the parsed IDs are attachable `AudioEffect` session IDs. Needs the device. |
| **DSP Default State** | The upstream DSP's default state, before any preset is loaded, has deep notches of -20 to -40 dB at several 1/3-octave graphic EQ band centres | **MEDIUM** | The reference player's default sound is comb filtered. The reverb's wet mix was ruled out as the cause (`docs/FX_RESPONSE_MEASUREMENT.md`); the likely cause is the state of the 31-band graphic EQ before a preset is loaded. It does not affect the incremental curves used for the Android mapping, because the effect curves are measured relative to the same reference. | Load a preset, or establish what the upstream's default band state is, and re-measure the reference. |
| **Compatibility** | Ambience slider is ignored during external playback | **MEDIUM** | User expectation mismatch; Ambience knob has zero audible effect on Spotify | Instantiate Android framework `PresetReverb` in `SessionCompanionService` |
| **Lifecycle** | Orphaned `AudioEffect` instances on ungraceful player exit | **MEDIUM** | Memory leak in Android `audioserver` if a player terminates without closing session | Implement session expiration watchdog (timeout after 15 min of silence) |
| **Audio Routing** | No `AudioDeviceCallback` observation | **MEDIUM** | DSP settings remain static when plugging headphones or connecting Bluetooth | Register `AudioDeviceCallback` and trigger profile adaptation |
| **Audio Focus** | `HiFiPlayerEngine` does not request audio focus | **LOW** | Simultaneous playback during incoming phone calls or navigation prompts | Wrap `HiFiPlayerEngine` in `AudioFocusRequestCompat` |
| **Architecture** | Single-listener `onStateChangedListener` callback | **LOW** | Brittle coupling between repository and companion service | Collect `FxSoundRepository.uiState` directly within service coroutine scope |

---

## 2. Deep Dive: Code-Level Technical Debt

### TD-001: 47 Hz Recomposition Cascade from Live Metrics
- **Location:** [`app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt:115-125`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt#L115-L125) and [`app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt:138-145`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt#L138-L145)
- **Problem Analysis:**
  During C++ playback, `HiFiPlayerEngine` executes a buffer of 1024 frames every 21.3 ms (at 48 kHz). At the end of every buffer:
  ```kotlin
  FxSoundRepository.updateMetrics(
      inputPeak = metricsBuffer[0],
      outputPeak = metricsBuffer[1],
      inputRms = metricsBuffer[2],
      outputRms = metricsBuffer[3],
      isClipping = metricsBuffer[4] > 0.5f,
      spectrum = spectrumBuffer.clone()
  )
  ```
  `updateMetrics` invokes `_uiState.update { ... }`.
  In `HomeScreen.kt`:
  ```kotlin
  val state by FxSoundRepository.uiState.collectAsState()
  ```
  Because `state` changes 47 times per second, Compose invalidates the entire `HomeScreen` composable tree — including the header text, status badge, master power button, preset card, and five effect sliders.
- **Measurable Impact:** 
  Unnecessary CPU cycling, battery drain, and increased garbage collection from the cloned `spectrumBuffer`.
- **Recommended Fix:**
  Extract metrics from `FxSoundUiState`. Create `val metricsState: StateFlow<AcousticMetrics>` and only collect it inside `AcousticMonitorView`.

---

### TD-002: In-Memory State Loss on App Death
- **Location:** [`app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt:18`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt#L18)
- **Problem Analysis:**
  All user parameter modifications (EQ gains, clarity, bass, surround, power state) are stored purely in memory in `_uiState = MutableStateFlow(FxSoundUiState())`.
  When Android reclaims the application process in the background (or the user reboots their phone), all custom user adjustments are permanently wiped and reset to default preset values.
- **Dependency Status:**
  `androidx.datastore:datastore-preferences` is already declared in `app/build.gradle.kts:84`, but zero code utilizes it.
- **Recommended Fix:**
  Create a lightweight repository persistence layer that asynchronously reads settings on startup and debounces saves on slider changes (200 ms timeout).

---

### TD-003: Native C++ Double Quantization in `processFloat` — RESOLVED 2026-10-19
- **Status:** Fixed by ADR-020. The upstream `realtype` is `float`, and
  `dfxpUniversalModifySamples` has a `bps == 32` branch that runs the real-type kernel with no
  integer conversion at all, so the outer float-to-int16-to-float round trip was a redundant
  second quantisation step rather than a platform requirement. The core is now configured for
  32-bit float once at init, and the PCM16 entry point converts at the boundary through the
  same float core.
- **Residual risk:** ~~The SNR improvement cannot be measured until the DSP's gain staging is correct, because the output currently clips at the digital rail.~~ Resolved by ADR-025 (2026-10-27): headroom pre-gain plus an output peak catcher removed the rail clipping (worst-case native test: output peak 0.997, zero rail-pinned samples). The SNR benefit itself still awaits a loudness-matched on-device measurement.
- **Original location:** [`app/src/main/cpp/engine/fxsound_engine.cpp:210-232`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp#L210-L232)
- **Problem Analysis:**
  The native engine currently processes float audio via:
  ```cpp
  int FxSoundEngine::processFloat(const float* inSamples, float* outSamples, int numFrames) {
      // 1. Float -> PCM16
      for (int i = 0; i < totalSamples; ++i) {
          scratchPcmIn_[i] = floatToPcm16(inSamples[i]);
      }
      // 2. Call PCM16 processor
      processPcm16(scratchPcmIn_.data(), scratchPcmOut_.data(), numFrames);
      // 3. PCM16 -> Float
      for (int i = 0; i < totalSamples; ++i) {
          outSamples[i] = pcm16ToFloat(scratchPcmOut_[i]);
      }
  }
  ```
  Inside `processPcm16`, `dspCore_->processAudio()` calls `dfxpModifyShortIntSamples()`, which immediately converts the PCM16 back to `realtype` (float)!
  This round-trip causes:
  - Redundant conversion passes through memory.
  - Loss of 32-bit floating point precision (clamped to 16-bit dynamic range, limiting SNR to ~96 dB).
- **Recommended Fix:**
  Expose `dfxpModifyRealtypeSamples` directly in `DfxDsp` and call it from `FxSoundEngine::processFloat` without touching PCM16 scratch buffers.

---

### TD-004: Lack of AudioDeviceCallback (Output Device Blindness)
- **Location:** [`app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt)
- **Problem Analysis:**
  The companion service initializes `DynamicsProcessing` once per session ID with static parameters.
  If the user starts music on phone speakers and then plugs in high-end headphones or connects Bluetooth:
  - Phone speakers require aggressive limiter protection, bass high-pass rolloff at 120 Hz, and voice lift.
  - Headphones require deep sub-bass (<60 Hz) and 3D soundstage widening.
  - Without `AudioDeviceCallback`, the app cannot adapt its acoustics to the active hardware transducer.
- **Recommended Fix:**
  Register an `AudioDeviceCallback` in `SessionCompanionService`. When the active device changes, apply the corresponding transducer calibration profile.

---

### TD-005: Orphaned AudioEffect Leak Risk
- **Location:** [`app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt:68`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L68)
- **Problem Analysis:**
  `activeSessions` tracks sessions based on `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`.
  However, buggy or non-standard third-party music players may crash or be force-closed by the user without ever dispatching `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`.
  The companion service would retain the `DynamicsProcessing`, `BassBoost`, and `Virtualizer` effect instances indefinitely inside `audioserver`.
- **Recommended Fix:**
  Add a watchdog timer or polling check: if no audio is detected on a session for more than 15 minutes, or if `AudioManager.isMusicActive()` is false for an extended duration, gracefully release the effect instances.

---

### TD-006: Hardcoded Sample Rate (48000 Hz)
- **Location:** [`app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt:33`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt#L33)
- **Problem Analysis:**
  `HiFiPlayerEngine` hardcodes `SAMPLE_RATE = 48000`.
  While 48 kHz is standard on Android, modern flagship devices like the Xiaomi 17 feature native 96 kHz or 192 kHz Hi-Res DACs. Forcing 48 kHz forces the Android audio HAL to perform unnecessary software resampling.
- **Recommended Fix:**
  Query `audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)` and initialize the C++ engine at the device's native hardware sample rate.

---

### TD-007: Test Suite Coverage Deficit — PARTIALLY RESOLVED 2026-10-19
- **Status:** The native suite is now executable; the JVM suite has grown; the Compose UI tests
  are still missing.
- **Current State:**
  - JVM unit tests: **25** across four suites (`FxSoundRepositoryTest` 6,
    `DynamicsProcessingTranslatorTest` 11, `FxSoundPresetTest` 4, `RouteStatusTest` 4), run by
    `.\gradlew.bat testDebugUnitTest`.
  - Native C++ tests: `app/src/main/cpp/tests/test_dsp_numerical.cpp` now has **11** cases and is
    built and executed by the new `./gradlew.bat nativeTest` task, which builds the binary with
    the NDK, pushes it to a connected device and runs it. It fails loudly when no device is
    connected rather than reporting a pass it did not observe. **Three of the eleven currently
    fail**, and each failure documents a real defect - see `docs/DSP_PORT_AUDIT.md` section 9.
  - UI tests: still 0 Compose UI tests. The route-status messaging and unavailable-route
    handling that `docs/ANDROID_M3_SKILL.md` requires are covered at the model level by
    `RouteStatusTest`, not at the UI level.
  - Service tests: still 0 integration tests for `SessionCompanionService`.
- **Risk:**
  Regressions in the Compose layer and in the service's session lifecycle cannot be caught
  automatically without manual testing. The native DSP's *correctness* is now covered, but its
  *fidelity to the desktop reference* is not: the native suite checks stability, bounds and
  parameter reachability, not parity with the upstream desktop output.
- **Recommended Fix:**
  1. Add `androidx.compose.ui:ui-test-junit4` and `org.robolectric:robolectric` (both
     Apache-2.0, test scope only) and implement the Compose UI tests the M3 skill requires.
  2. Add a service-level test that exercises the session attach and update path.

---

### TD-008: The Upstream DSP Kernels Run Uncalibrated and the Effect Values Do Not Reach Them — RESOLVED 2026-10-19
- **Status:** Fixed by ADR-023. The native numerical suite passes 11 of 11.
- **Root cause:** Two independent defects, not one.
  1. **The registry round trip was broken.** The upstream stores the effect knob and button
     values in the registry and reads them back when it communicates the parameters to the
     kernels. The Android stubs discarded the write and reported "not present" on the read, so
     the communication always fell back to the default value. The registry entry points are now
     implemented against an in-memory key/value store.
  2. **The parameter overlay structs were misaligned.** The parameter block is an array of
     32-bit words, and the overlay structs (`dspPlayStructType` and friends) are defined to
     match the word indices in `boardrv1.h`. They used `long`, which is 64 bits on Android
     (LP64), so every field after the first was read from the wrong word. They now use
     `int32_t`/`uint32_t`, and `ptutil/include/dfxp_param_layout.h` asserts the layout at
     compile time.
- **Measured before:** the output was bit-identical with the effects at 0 and at 10 (max
  sample difference 0); a 0.2-amplitude sine was amplified to full scale; digital silence was
  amplified to full scale.
- **Measured after:** the effects change the output measurably (max sample difference 0.34);
  silence stays silent; the float path carries sub-16-bit resolution (1.96% of samples on the
  16-bit grid, was 96.6%).
- **Residual risk:** the native suite checks stability, bounds and parameter reachability. It
  does not check parity with the upstream desktop output, which needs a loudness-matched
  listening comparison on the target device (`docs/DEVICE_VERIFICATION_SCRIPT.md`).

