# Product Review & Feature Opportunities — Android FxSound

**Project:** FxSound Android Audio Enhancer  
**Author:** Senior Android Engineer, Audio DSP Specialist, Product Designer, QA Lead  
**Target Device:** Xiaomi 17 (Android 15/16, HyperOS, Snapdragon 8 Gen 4 / 8 Elite, Unrooted)  
**Date:** 2026-10-17  
**Baseline Status:** Phase 1 Complete (Unit Tests: 7/7 Passing, Debug APK: 60.6 MB Built)  

---

## 1. Executive Summary & Current Architecture Baseline

The existing application is a functioning, rootless Android audio enhancer inspired by Windows FxSound. Following Phase 1 improvements, the app features:
1. **A verified C++ DSP library (`libfxsound_dsp.so`, 645 KB)** compiled with CMake and Clang `-O3 -ffast-math`, containing genuine ported algorithmic code from `fxsound2/fxsound-app` at commit `d8e7a23d37ed5939c2a3090a1c1756c7f2500b17`. Float-to-PCM16 boundary clamping in `MthBuffer.cpp` has eliminated integer overflow cracking.
2. **A calibrated Android `DynamicsProcessing` Companion Engine (`SessionCompanionService.kt`)** operating in `VARIANT_FAVOR_TIME_RESOLUTION` with automatic preamp headroom compensation, a 4-band multiband compressor with soft-knee curves, and a brickwall peak limiter ($50:1$, $-0.2\text{ dBFS}$) attached to session IDs broadcast by Spotify and external music players.
3. **A Material 3 Dark UI** featuring a dual-ring sculpted master bypass button, honest acoustic monitor (displaying real True Peak and FFT spectrum exclusively during live C++ DSP processing), calibrated effect sliders, and a 10-band studio-grade vertical graphic equalizer console with bidirectional level fills and double-tap-to-zero.
4. **Tactile Haptics** utilizing Android 11+ `VibrationEffect.Composition` primitives (`PRIMITIVE_LOW_TICK`, `PRIMITIVE_CLICK`, `PRIMITIVE_QUICK_RISE`) with rate limiting and system touch-feedback preference respect.

### Critical Architecture Constraint
- **External Audio (Spotify, Musicolet, Poweramp):** Processed inside Android `audioserver` via the system `DynamicsProcessing`, `BassBoost`, and `Virtualizer` effects. Because Android OS security forbids user-space apps from intercepting third-party audio streams without root or loopback echo, external audio **does not** flow through the C++ `libfxsound_dsp.so` binary.
- **In-App Reference Engine (`HiFiPlayerEngine.kt`):** Runs the genuine C++ DfxDsp pipeline directly on an app-owned `AudioTrack`.

---

## 2. Comprehensive Improvement Opportunities

### A. Audio Quality & DSP

#### 1. Native C++ Floating-Point Pipeline Optimization (Bypass Double Quantization)
- **Current State:** In `fxsound_engine.cpp`, `processFloat()` converts `float` samples to `int16_t`, passes them into `processPcm16()`, where `dfxpModifyShortIntSamples` converts them back to `float` (`realtype`), applies DSP, quantizes back to `int16_t`, and converts back to `float`.
- **Opportunity:** Wire `dfxpModifyRealtypeSamples` directly into `processFloat()`. This preserves full 32-bit floating-point dynamic range (>140 dB), eliminates quantization noise floor distortion, and removes redundant array conversion loops on the audio thread.
- **Validation:** Automated THD+N (Total Harmonic Distortion + Noise) measurement script comparing 1 kHz sine output before and after.

#### 2. Ambience / Reverb Implementation in Companion Engine
- **Current State:** The Ambience effect is fully implemented in the C++ engine (`dly8p32.c`), but in `DynamicsProcessingTranslator.kt`, Ambience is completely unmapped because Android's `DynamicsProcessing` lacks a reverb stage.
- **Opportunity:** Instantiate Android framework `PresetReverb` or `EnvironmentalReverb` attached to the active session ID, mapping the Ambience slider (0.0 to 10.0) to reverb decay time and room reflections.
- **Validation:** Audible room acoustics during Spotify playback when adjusting the Ambience slider; verify no audio glitching upon attachment.

#### 3. Automatic Dynamic Headroom Compensation (Pre-EQ Attenuation)
- **Current State:** `DynamicsProcessingTranslator.kt` implements `headroomCompensationDb = (totalPeakPotential * 0.35f).coerceIn(0.0f, 5.0f)` applied to the input gain.
- **Opportunity:** Expand dynamic headroom calculation to account for cumulative EQ band boosts. If a user applies +10 dB at 64 Hz and +8 dB at 125 Hz in the 10-band equalizer, pre-attenuation should automatically scale to prevent the 4-band compressor and limiter from heavy pumping.
- **Validation:** Test with 0 dBFS square-wave and pink-noise sweeps; verify limiter gain reduction stays under 3 dB during extreme boost.

#### 4. Hardware Sample Rate & Buffer Size Alignment
- **Current State:** C++ engine and `HiFiPlayerEngine` hardcode `48000 Hz` and `1024 frames`.
- **Opportunity:** Query `AudioManager.getProperty(PROPERTY_OUTPUT_SAMPLE_RATE)` and `PROPERTY_OUTPUT_FRAMES_PER_BUFFER`. Dynamically initialize `DfxDsp` to the device's native hardware rate (e.g. 48000 Hz, 96000 Hz, or 192000 Hz on Xiaomi 17 Hi-Res DAC) to bypass Android OS software resampling.
- **Validation:** Verify zero sample rate conversion artifacts and reduced audio output latency via `systrace` / `perfetto`.

---

### B. Audio Compatibility & Routing

#### 1. Guaranteed vs. Unsupported Audio Sources on Unrooted Android

| Audio Source | Supported? | Mechanism | Technical Reality |
| :--- | :--- | :--- | :--- |
| **Spotify** | **YES** | `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` | Works when user enables "Device Broadcast Status" or opens Equalizer in Spotify. |
| **Poweramp / Musicolet / Retro / VLC** | **YES** | Standard `AudioEffect` broadcast session | Fully supported out-of-the-box. |
| **In-App Local Hi-Fi Playback** | **YES** | App `AudioTrack` + JNI `libfxsound_dsp.so` | 100% genuine C++ FxSound DSP execution. |
| **YouTube & YouTube Music** | **NO (Rootless)** | System AudioEffect / Capture | YouTube explicitly opts out of audio effects; DRM media cannot be captured without root. |
| **Netflix / Disney+ / Prime** | **NO (Rootless)** | AudioEffect Session | Widevine L1 DRM blocks audio capture and effect attachment. |
| **Google Chrome / Browsers** | **NO (Rootless)** | AudioEffect Session | Chrome does not broadcast session IDs; `AudioPlaybackCapture` cannot mute hardware speaker, creating severe echo. |
| **Mobile Games** | **NO (Rootless)** | AudioEffect Session | Games use OpenSL ES / Oboe directly and do not broadcast audio sessions. |

#### 2. Audio Device State Observer (`AudioDeviceCallback`)
- **Current State:** The app has no awareness of whether the user is listening through phone speakers, wired 3.5mm/USB-C headphones, or Bluetooth A2DP/LE Audio.
- **Opportunity:** Register an `AudioDeviceCallback` in `SessionCompanionService`. When a transition occurs (e.g., Bluetooth headphones connected), automatically apply device-specific calibration profiles (e.g., warmer bass for headphones, vocal clarity and anti-clipping for built-in speakers).
- **Validation:** Plug/unplug headphones and verify via logcat that preset profiles switch instantly without audio clicks.

---

### C. High-Value New Features

#### 1. In-App Local Hi-Fi Media Player (ExoPlayer / Media3 Integration)
- **User Benefit:** Allows users to play their own high-resolution audio files (FLAC, WAV, ALAC, MP3, AAC) through the **genuine C++ FxSound DSP pipeline**, delivering the exact Windows FxSound soundstage on Android without framework limitations.
- **Feasibility:** High. Media3 ExoPlayer provides audio decoders and custom `AudioProcessor` interfaces that pipe decoded PCM directly into `FxSoundNativeBridge.processPcm16()`.
- **Complexity:** Medium.
- **Dependencies:** `androidx.media3:media3-exoplayer`.

#### 2. Per-Device Calibration Profiles (Speaker vs. Headphones vs. Bluetooth)
- **User Benefit:** Phone speakers need aggressive dynamic boost, bass rolloff below 120 Hz, and limiter protection; high-end headphones need wide 3D surround, sub-bass extension, and flat EQ. Automatic switching saves the user from manually adjusting sliders.
- **Feasibility:** High. Android's `AudioManager.getDevices()` provides accurate device classification (`TYPE_BUILTIN_SPEAKER`, `TYPE_BLUETOOTH_A2DP`, `TYPE_WIRED_HEADPHONES`, `TYPE_USB_HEADSET`).
- **Complexity:** Low-Medium.
- **Dependencies:** `AudioDeviceCallback`.

#### 3. Custom Presets with JSON Export & Import
- **User Benefit:** Power users can create, rename, save, and share their own custom acoustic tunings (e.g., "EDM Bass Punch", "Podcast Vocal Clarity", "Classical Hall").
- **Feasibility:** High. Serialize preset models into JSON via Kotlin Serialization or standard `JSONObject`.
- **Complexity:** Low.
- **Dependencies:** Android storage access framework (SAF) for `.fxpreset` file export/import.

#### 4. MediaStyle Foreground Notification with Quick Controls
- **User Benefit:** Users can toggle enhancement on/off and switch between favorite presets directly from the notification shade or lockscreen without opening the app.
- **Feasibility:** High. Standard `NotificationCompat.Builder` with `MediaStyle` actions.
- **Complexity:** Low.
- **Dependencies:** None.

#### 5. Android Quick Settings Tile (`FxSoundTileService`)
- **User Benefit:** Instant one-tap enhancement bypass/activate toggle in the Android system quick settings tray.
- **Feasibility:** High. Extend `TileService`.
- **Complexity:** Low.
- **Dependencies:** None.

---

### D. UI/UX & Material 3 Refinement

1. **Light / Dark Theme Parity:** While pro-audio tools thrive in dark mode, implementing an optional Material 3 Light theme with tinted slate surfaces ensures complete design system completeness.
2. **Preset Reordering and Favorites:** Allow pinning favorite presets (e.g., Music, Voice, Gaming) to the top of the Presets screen.
3. **Landscape / Tablet Responsive Split Layout:** On foldables, tablets, or in landscape orientation, display the 10-band vertical EQ console and effect sliders side-by-side using a two-pane scaffold.

---

### E. Reliability, Performance & Battery

1. **Decouple Fast Audio Metrics from Screen Recomposition:**
   - **Finding:** `FxSoundRepository.updateMetrics()` currently writes to the main `_uiState` StateFlow every 21 ms. Any screen observing `uiState` recomposes 47 times per second.
   - **Fix:** Split `metricsState` into a standalone `MutableStateFlow<AcousticMetrics>` collected only by `AcousticMonitorView`.
2. **Audio Focus Handling:**
   - **Finding:** `HiFiPlayerEngine` does not request `AudioManager.AUDIOFOCUS_GAIN`. An incoming phone call or navigation prompt will play simultaneously over the reference audio.
   - **Fix:** Implement `AudioFocusRequest` with transient ducking.
3. **DataStore Settings Persistence:**
   - **Finding:** Current repository state is memory-only; killing the app resets slider positions to defaults.
   - **Fix:** Wire existing `androidx.datastore.preferences` dependency to persist effect values, EQ bands, power state, and active preset.

---

### F. Privacy, Security & Distribution

1. **AGPL-3.0 Compliance Verification:** The app integrates C++ source from `fxsound2/fxsound-app`. The repository must clearly state AGPL-3.0 licensing in `LICENSING_AND_ATTRIBUTION.md` and provide full source code access.
2. **Zero Telemetry Integrity:** Strict adherence to zero tracking, zero advertising SDKs, and zero internet permissions in `AndroidManifest.xml` (the app does not even request `android.permission.INTERNET`).
3. **Google Play Store Policy:** The app uses standard foreground service type `mediaPlayback`, compliant with Google Play's 2024–2026 foreground service policies.

---

## 3. Prioritized Improvement Backlog & Scoring

Each proposal is scored from 1 to 5:
- **UI:** User Impact (1 = minimal, 5 = transformational)
- **AQ:** Audio Quality / Product Polish (1 = negligible, 5 = major leap)
- **TF:** Technical Feasibility (1 = highly speculative, 5 = fully proven)
- **IE:** Implementation Effort (1 = massive effort, 5 = trivial / low effort)
- **CF:** Confidence (1 = uncertain, 5 = verified fact)

| Priority | Proposal | Current State | User Benefit | Feasibility | Effort | UI | AQ | TF | IE | CF | Score |
| :---: | :--- | :--- | :--- | :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| **P0** | **Settings Persistence via DataStore** | In-memory only; resets on app kill | Retains user EQ, effect sliders, and power state across reboots | High | Low | 5 | 3 | 5 | 4 | 5 | **4.4** |
| **P0** | **Decouple Metrics State to Stop UI Recomposition** | Metrics update main `uiState` 47 Hz | Drops idle CPU & battery drain during C++ audio playback | High | Low | 4 | 3 | 5 | 5 | 5 | **4.4** |
| **P1** | **Audio Device Callback & Auto Output Profiles** | No device awareness | Auto-tunes DSP for speakers vs. headphones vs. Bluetooth | High | Medium | 5 | 5 | 4 | 3 | 5 | **4.4** |
| **P1** | **MediaStyle Notification with Quick Bypass Toggle** | Basic ongoing notification | Control FxSound directly from lockscreen / notification shade | High | Low | 4 | 2 | 5 | 4 | 5 | **4.0** |
| **P1** | **In-App Local Hi-Fi Media Player (ExoPlayer)** | Multitone tone generator only | Play personal FLAC/MP3 files through genuine C++ FxSound DSP | High | Medium | 5 | 5 | 5 | 3 | 5 | **4.6** |
| **P1** | **Native C++ Direct Float Pipeline** | PCM16 quantization loop | Maximizes 32-bit SNR; eliminates quantization floor noise | High | Medium | 3 | 5 | 4 | 3 | 4 | **3.8** |
| **P1** | **Ambience Framework Reverb in Companion Service** | Ambience slider ignored in external audio | Restores room ambience & acoustic reflections to Spotify | High | Medium | 4 | 4 | 4 | 3 | 4 | **3.8** |
| **P2** | **Custom User Presets (Create, Save, Edit, Delete)** | Fixed presets only; edits revert to "Custom" | Save personalized tunings for specific albums or headphones | High | Medium | 4 | 3 | 5 | 3 | 5 | **4.0** |
| **P2** | **Audio Focus Management in Player Engine** | No focus request; ignores phone calls | Pauses or ducks reference playback when calls/alarms trigger | High | Low | 3 | 2 | 5 | 5 | 5 | **4.0** |
| **P2** | **Android Quick Settings Tile Service** | None | One-tap power toggle from system notification shade | High | Low | 4 | 2 | 5 | 5 | 5 | **4.2** |
| **P2** | **Preset Backup & JSON Import/Export** | None | Share tuned presets with other users or backup before reset | High | Low | 3 | 2 | 5 | 4 | 5 | **3.8** |
| **P3** | **Adaptive Dynamic Limiter Headroom Scaling** | Static peak headroom scaling | Dynamically calculates EQ boost sum to avoid limiter pumping | Medium | Medium | 3 | 4 | 4 | 3 | 4 | **3.6** |
| **P3** | **Landscape & Tablet Two-Pane Split Layout** | Single vertical column | Optimal layout utilization on tablets and foldables | Medium | Medium | 3 | 2 | 5 | 3 | 5 | **3.6** |
| **P3** | **Material 3 Dynamic Theming (Material You)** | Fixed custom dark theme | Colors harmonize with user wallpaper on Android 12+ | Medium | Low | 3 | 1 | 5 | 4 | 5 | **3.6** |
