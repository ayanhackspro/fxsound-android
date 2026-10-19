# Phased Engineering Roadmap — Android FxSound

**Project:** FxSound Android Audio Enhancer  
**Author:** Lead Android Engineer & Audio DSP Specialist  
**Target Device:** Xiaomi 17 (Android 15/16, HyperOS)  
**Status:** Planning & Execution Roadmap  

---

## 1. Roadmap Overview

```
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 1: Audit, Audio Quality Fixes & UI Redesign (COMPLETED)           │
│  - C++ float clamping in MthBuffer.cpp (No integer overflow cracking) │
│  - DynamicsProcessing calibration: Time Resolution, Preamp Headroom    │
│  - Material 3 Dark UI & Studio 10-Band Vertical EQ Console             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 2: Reliability, State Persistence & Recomposition Decoupling      │
│  - DataStore preference persistence (Settings survive app kill)        │
│  - Split AcousticMetrics StateFlow (Stops 47 Hz full-screen recompose) │
│  - AudioDeviceCallback (Detects Speaker vs. Headphone vs. Bluetooth)   │
│  - Audio focus handling (Pause/resume on incoming phone calls)         │
│  - Ambience framework reverb in SessionCompanionService                │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 3: In-App Hi-Fi Local Music Player (Media3 / ExoPlayer)          │
│  - ExoPlayer custom AudioProcessor piping PCM to libfxsound_dsp.so     │
│  - Support for local FLAC, WAV, ALAC, MP3, AAC playback                │
│  - Direct 32-bit floating-point C++ DSP pipeline (>140 dB SNR)         │
│  - Native hardware sample rate querying (48k / 96k / 192k)             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 4: Pro-Audio Customization & System Integration                  │
│  - Custom user presets with JSON import/export (.fxpreset)             │
│  - MediaStyle ongoing notification with active Bypass & Preset toggle  │
│  - Android Quick Settings Tile (`FxSoundTileService`)                  │
│  - Per-device auto-switching calibration profiles                      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ Phase 5: Hardware Benchmarking, Profiling & Release Readiness          │
│  - Physical Xiaomi 17 validation with real Spotify playback sessions   │
│  - Audio latency & CPU profiling via Perfetto                          │
│  - Automated Robolectric & Compose UI test suites                      │
│  - Google Play Store compliance, AGPL-3.0 attribution, zero telemetry  │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Milestone Breakdown

### Phase 2: Reliability, State Persistence & Recomposition Decoupling
* **Primary Objective:** Ensure rock-solid background stability, state persistence across reboots, and eliminate idle CPU/battery drain.
* **Duration Estimate:** 1 sprint (3–4 days).
* **Key Deliverables:**
  1. `FxSoundPreferencesDataStore`: Persist all slider values, custom EQ curves, and power state via Jetpack DataStore with 200 ms write debouncing.
  2. Recomposition Decoupling: Extract `AcousticMetrics` from `FxSoundUiState` so only `AcousticMonitorView` recomposes during audio playback.
  3. `AudioDeviceCallback`: Register audio device listener to observe wired headphone connections, Bluetooth A2DP pairing, and phone speaker transitions.
  4. Audio Focus: Implement `AudioFocusRequestCompat` in `HiFiPlayerEngine`.
  5. Framework Ambience: Instantiate `android.media.audiofx.PresetReverb` in `SessionCompanionService` and bind to the Ambience slider.
* **Done Criteria:**
  - App state survives `adb shell am force-stop com.fxsound.audio`.
  - Layout Inspector confirms zero recomposition on static UI elements during audio playback.
  - Ambience slider produces audible room reflections during Spotify playback.

---

### Phase 3: In-App Hi-Fi Local Music Player (Media3 / ExoPlayer)
* **Primary Objective:** Provide a native audio playback surface where users can play their own high-res music library through the **authentic C++ FxSound DSP core**, bypassing all Android OS framework limitations.
* **Duration Estimate:** 1.5 sprints (5–7 days).
* **Key Deliverables:**
  1. Media3 ExoPlayer integration with custom `FxSoundAudioProcessor` piping decoded PCM directly into JNI `libfxsound_dsp.so`.
  2. Direct 32-bit floating-point C++ pipeline (`dfxpModifyRealtypeSamples`) eliminating double PCM16 quantization.
  3. Dynamic sample rate alignment: Query `AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE` to operate at the device's native DAC rate (48 kHz, 96 kHz, 192 kHz).
  4. Local audio file picker and in-app track queue screen.
* **Done Criteria:**
  - Playback of 24-bit 96 kHz FLAC file verified through `libfxsound_dsp.so` without dropouts or buffer underruns.
  - Real-time FFT spectrum and True Peak meters animate synchronously with audio playback.

---

### Phase 4: Pro-Audio Customization & System Integration
* **Primary Objective:** Empower power users with custom preset creation, file backup/sharing, and seamless lockscreen/quick-settings access.
* **Duration Estimate:** 1 sprint (4–5 days).
* **Key Deliverables:**
  1. Custom Presets: Create, rename, edit, and delete personal user presets.
  2. JSON Preset Import/Export: Backup and share `.fxpreset` configuration files via Storage Access Framework.
  3. `MediaStyle` Notification: Interactive notification shade with power toggle and preset cycling buttons.
  4. Quick Settings Tile: `FxSoundTileService` for instant system tray enhancement toggling.
  5. Per-Device Profiles: Auto-apply optimized curves when switching between Phone Speaker, Bluetooth, and Headphones.
* **Done Criteria:**
  - Presets exported to device storage and imported on another device successfully.
  - Quick Settings Tile toggles enhancement status synchronously with the main UI.

---

### Phase 5: Hardware Benchmarking, Profiling & Release Readiness
* **Primary Objective:** On-device hardware validation on the Xiaomi 17, audio quality measurements, battery optimization, and distribution readiness.
* **Duration Estimate:** 1 sprint (4–5 days).
* **Key Deliverables:**
  1. Physical Xiaomi 17 Hardware Testing: Test Spotify, Musicolet, Poweramp, and Bluetooth LE Audio sessions.
  2. Latency & CPU Profiling: Record Perfetto traces to confirm zero audio thread priority inversions and <15 ms buffer latency.
  3. Automated Test Suite Expansion: Robolectric Compose tests and CMake native DSP unit test automation.
  4. Legal & Store Readiness: Verify AGPL-3.0 attribution, licensing disclaimers, and zero telemetry guarantee.
* **Done Criteria:**
  - 0 crashes, 0 ANRs, 0 memory leaks across 24-hour continuous playback stress test.
  - All automated unit and UI tests pass cleanly.
