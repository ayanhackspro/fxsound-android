# Architectural Decisions & Feature Trade-Offs

**Project:** FxSound Android Audio Enhancer  
**Status:** Living Architecture Decision Record  
**Date:** 2026-10-17  

---

## 1. Summary of Architectural Decisions

| Decision ID | Area | Decision | Primary Rationale | Trade-Off Accepted |
| :---: | :--- | :--- | :--- | :--- |
| **ADR-009** | **State Persistence** | Use Jetpack **DataStore Preferences** rather than Room Database | Perfect fit for key-value slider floats, boolean flags, and JSON string arrays; reactive `Flow` integration with zero SQL boilerplate | Querying complex relational queries is not possible (not needed for presets) |
| **ADR-010** | **Local Audio Playback** | Integrate **Media3 ExoPlayer** with custom `AudioProcessor` | High-performance demuxing & decoding for FLAC/WAV/MP3/ALAC with standardized audio sink lifecycle | Adds ~3 MB to APK size |
| **ADR-011** | **Device Route Detection** | Use **`AudioDeviceCallback`** over legacy `BroadcastReceiver` | Provides precise hardware device types (`TYPE_BLUETOOTH_A2DP`, `TYPE_USB_HEADSET`, `TYPE_BUILTIN_SPEAKER`) supporting Bluetooth LE Audio and USB-C DACs | Requires API level 23+ (app minSdk is 29, so fully compatible) |
| **ADR-012** | **Preset Serialization** | Standardized **JSON schema** (`.fxpreset`) | Human-readable, easily debugged, and interoperable with Windows FxSound preset structures | Slightly larger byte payload than binary Protocol Buffers (negligible for small preset files) |
| **ADR-013** | **Recomposition Decoupling** | Split `AcousticMetrics` into dedicated isolated `StateFlow` | Prevents 47 Hz full-screen recomposition cascade, saving CPU cycles and battery during audio playback | Two separate state flows in repository instead of a single monolithic state object |
| **ADR-014** | **Ambience Implementation** | Framework **`PresetReverb`** for companion session | `DynamicsProcessing` lacks a reverb stage; `PresetReverb` attaches directly to the external audio session ID | Android framework reverb is less customizable than native DfxDsp delay lines, but works on Spotify sessions |

---

## 2. Detailed Decision Records

### ADR-009: Jetpack DataStore Preferences for Application State Persistence
- **Context:**
  All user equalizer gains, slider positions, power state, and active preset IDs are currently held purely in memory. Process termination causes loss of all user tuning.
- **Alternatives Considered:**
  1. *Room SQLite Database:* Standard for structured relational data. Overhead includes DAO boilerplate, migration schemas, and SQLite query compilation.
  2. *SharedPreferences:* Synchronous blocking I/O on UI thread; prone to ANRs and deprecated in modern Android guidance.
  3. *Jetpack DataStore Preferences:* Asynchronous, non-blocking Kotlin Coroutines `Flow` API, fully transactional, and zero SQL boilerplate.
- **Decision:**
  Adopt `androidx.datastore:datastore-preferences`.
- **Consequences:**
  - Fast, non-blocking asynchronous reads/writes.
  - Changes are observed reactively in `FxSoundRepository`.
  - Writes are debounced by 200 ms during continuous slider drags to eliminate disk thrashing.

---

### ADR-010: Media3 ExoPlayer for In-App Hi-Fi Local Music Playback
- **Context:**
  The in-app C++ engine (`HiFiPlayerEngine`) currently only plays a synthetic multi-tone signal generated mathematically. Users want to play their personal high-resolution audio files (FLAC, WAV, ALAC, MP3) through the genuine C++ FxSound DSP core.
- **Alternatives Considered:**
  1. *Native FFmpeg Decoders via C++:* Custom decode pipeline in C++. Extremely complex, high maintenance burden, large binary size (+15 MB).
  2. *Android `MediaCodec` + `AudioTrack`:* Low-level framework API. Requires manual container parsing, threading, timestamp synchronization, and buffer queuing.
  3. *Media3 ExoPlayer with custom `AudioProcessor`:* Industry standard for Android media playback. Handles container extraction, codec selection, hardware acceleration, and gapless playback out-of-the-box.
- **Decision:**
  Implement a custom `androidx.media3.common.audio.AudioProcessor` in Kotlin that receives decoded PCM buffers from ExoPlayer and passes them to `FxSoundNativeBridge.processPcm16()`.
- **Consequences:**
  - Rock-solid decoding across all major audio formats.
  - Full access to the authentic C++ DfxDsp pipeline.
  - Standardized media session and lockscreen controls.

---

### ADR-011: `AudioDeviceCallback` for Audio Output Route Detection
- **Context:**
  Acoustic characteristics differ radically between phone micro-speakers, Bluetooth car audio, and in-ear monitors. The app must detect output device switches to apply device-specific calibration profiles.
- **Alternatives Considered:**
  1. *Legacy `BroadcastReceiver` (`ACTION_HEADSET_PLUG`):* Deprecated; does not detect Bluetooth A2DP, Bluetooth LE Audio, or modern USB-C digital DACs.
  2. *Polling `AudioManager.getDevices()`:* Inefficient; wastes battery and introduces latency when plugging/unplugging headphones.
  3. *`AudioManager.registerAudioDeviceCallback`:* Event-driven, low overhead, and accurately reports exact device types (`TYPE_BLUETOOTH_A2DP`, `TYPE_USB_HEADSET`, `TYPE_WIRED_HEADPHONES`, `TYPE_BUILTIN_SPEAKER`).
- **Decision:**
  Register `AudioDeviceCallback` inside `SessionCompanionService`.
- **Consequences:**
  - Instantaneous, zero-latency detection of headphone and Bluetooth connections.
  - Enables automatic profile switching without user intervention.

---

### ADR-012: JSON Format for Custom Preset Storage & Sharing
- **Context:**
  Users need to save custom presets and export/import them as files (`.fxpreset`).
- **Alternatives Considered:**
  1. *Protocol Buffers (Protobuf):* Compact binary format, but requires schema compiler tools and is unreadable by humans.
  2. *XML:* Verbose, legacy syntax.
  3. *JSON:* Lightweight, human-readable, easily parsed via `org.json` or `kotlinx.serialization`, and matches the JSON schema structure used in Windows FxSound config files.
- **Decision:**
  Adopt JSON for preset serialization:
  ```json
  {
    "schemaVersion": 1,
    "name": "Acoustic Warmth",
    "clarity": 6.5,
    "bass": 5.0,
    "surround": 3.0,
    "dynamicBoost": 4.5,
    "ambience": 2.0,
    "eqGains": [3.0, 2.5, 1.0, 0.0, 0.0, 0.5, 1.5, 2.0, 3.5, 4.0]
  }
  ```
- **Consequences:**
  - Easy export, import, and backup.
  - Users can share presets via messaging apps or cloud drives.

---

### ADR-013: Recomposition Decoupling Architecture
- **Context:**
  Audio telemetry (True Peak, RMS, FFT spectrum) updates 47 times per second. In Jetpack Compose, updating the single monolithic `FxSoundUiState` triggers recomposition across all observing composables on the screen.
- **Alternatives Considered:**
  1. *Throttle telemetry to 10 Hz:* Reduces recompositions, but makes meters feel sluggish and non-responsive.
  2. *Keep state monolithic:* High CPU and battery drain.
  3. *Split state into Control State vs. Telemetry State:*
     - `controlState: StateFlow<FxSoundUiState>` (updates on user interaction: sliders, power button, presets).
     - `metricsState: StateFlow<AcousticMetrics>` (updates at 47 Hz from audio thread; consumed exclusively by `AcousticMonitorView`).
- **Decision:**
  Split `AcousticMetrics` into a dedicated `StateFlow`.
- **Consequences:**
  - Smooth 47–60 Hz real-time meters inside `AcousticMonitorView`.
  - Zero recomposition across all other controls, sliders, and buttons on the screen.
  - Substantial battery and CPU efficiency gains.
