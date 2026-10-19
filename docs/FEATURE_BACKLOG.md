# Actionable Feature Backlog & Implementation Specifications

**Project:** FxSound Android Audio Enhancer  
**Status:** Living Engineering Backlog  
**Version:** 1.1.0  
**Target Release:** Phase 2 & Phase 3  

---

## 1. Master Priority Matrix

| Priority | Proposal | Current State | User Benefit | Feasibility | Effort | Dependencies | Validation |
| :---: | :--- | :--- | :--- | :--- | :---: | :--- | :--- |
| **P0** | **Settings & Preset Persistence (DataStore)** | In-memory only; resets on process death | Sliders, power toggle, and EQ settings survive app restarts and reboots | High (Standard Android Jetpack) | Low | `androidx.datastore:datastore-preferences` | Restart app; verify all slider values, custom EQ, and active preset remain identical |
| **P0** | **Decouple Acoustic Metrics from UI Recomposition** | `updateMetrics()` mutates main `uiState` 47 Hz | Eliminates unnecessary battery drain and 47 Hz recompositions on static UI components | High (Kotlin Coroutines `StateFlow`) | Low | None | Inspect Layout Inspector recomposition counts; verify `HomeScreen` static elements do not recompose during playback |
| **P1** | **In-App Local Hi-Fi Media Player (Media3/ExoPlayer)** | Multitone tone generator only; cannot play user music files | Allows users to play their local FLAC/MP3 files directly through genuine ported C++ DfxDsp engine | High (ExoPlayer custom AudioProcessor) | Medium | `androidx.media3:media3-exoplayer` | Play 24-bit 96kHz FLAC file; verify audio processes through `libfxsound_dsp.so` without glitches |
| **P1** | **Audio Device Callback & Output Profiles** | App unaware of audio route changes (Speaker vs Headphones vs Bluetooth) | Automatically switches calibration profiles (e.g. anti-clipping for speakers, wide 3D for headphones) | High (`AudioManager.registerAudioDeviceCallback`) | Medium | Android SDK 23+ | Connect Bluetooth headphones; verify in logcat that device profile switches from Speaker to Bluetooth automatically |
| **P1** | **MediaStyle Notification with Quick Bypass Toggle** | Basic non-interactive foreground notification | Allows toggling enhancement on/off and seeing current preset from lockscreen without opening app | High (`NotificationCompat.MediaStyle`) | Low | `androidx.media:media` | Toggle power button in notification shade; verify audio effect immediately engages/disengages |
| **P1** | **Native C++ Direct Float Pipeline** | `processFloat()` converts to PCM16, calls int DSP, then converts back | Eliminates double-quantization noise floor; preserves >140 dB dynamic range of 32-bit float audio | High (Ported `dfxpModifyRealtypeSamples`) | Medium | `libfxsound_dsp.so` | Audio spectrum THD+N measurement; verify noise floor drop below -110 dBFS |
| **P1** | **Ambience Reverb in Companion Service** | Ambience slider is unmapped during external Spotify playback | Restores natural acoustic room reflections to Spotify and music player sessions | High (`android.media.audiofx.PresetReverb`) | Medium | Android AudioEffect Framework | Move Ambience slider during Spotify session; verify audible room depth and reflections |
| **P2** | **Custom User Presets (Create, Rename, Delete)** | Fixed built-in presets; modifications revert to "custom" | Enables users to save and name multiple custom acoustic tunings | High (Room or DataStore JSON) | Medium | DataStore Persistence | Create preset "My Sennheisers"; apply changes; verify preset appears in list and survives restart |
| **P2** | **Audio Focus Management in Reference Player** | AudioTrack plays unconditionally; ignores incoming calls | Pauses or ducks reference playback when phone rings or navigation speaks | High (`AudioFocusRequest`) | Low | Android AudioManager | Trigger simulated phone call via adb; verify reference playback pauses and resumes cleanly |
| **P2** | **Android Quick Settings Tile (`FxSoundTileService`)** | None | One-tap power toggle directly from system quick settings | High (`android.service.quicksettings.TileService`) | Low | Manifest permission | Add tile to system tray; tap tile; verify app power state toggles synchronously |
| **P2** | **Preset Backup & JSON Import/Export** | None | Allows users to back up custom presets or share tuned `.fxpreset` JSON files | High (Storage Access Framework) | Low | Custom Presets | Export preset to Downloads folder; import on another device; verify curves match |
| **P3** | **Dynamic Limiter Headroom Compensation** | Static headroom calculation | Dynamically attenuates pre-gain based on total positive EQ band boost sum | High | Low | `DynamicsProcessingTranslator` | Apply +14 dB on all 10 bands; verify limiter does not exhibit audible pumping or ducking |
| **P3** | **Landscape & Tablet Two-Pane Layout** | Single vertical scroll column | Adapts interface gracefully to wide screens, tablets, and foldables | High (Compose `BoxWithConstraints`) | Medium | Compose UI | Rotate device to landscape; verify 10-band EQ console and effect sliders display side-by-side |
| **P3** | **Material 3 Dynamic Theming** | Static dark palette | Harmonizes UI accent colors with user's system wallpaper on Android 12+ | High (`dynamicDarkColorScheme`) | Low | Material 3 Theme | Toggle dynamic colors in Settings; verify UI accents adapt to system palette |

---

## 2. Detailed Task Specifications (P0 & P1)

### Task T-001: Settings & Preset DataStore Persistence (P0)
- **Goal:** Persist all effect slider values, 10-band EQ gains, power state, haptics mode, and active preset across app termination and device reboots.
- **Technical Implementation:**
  - Create `FxSoundPreferencesDataStore.kt` wrapping `DataStore<Preferences>`.
  - Keys: `KEY_POWER_ENABLED` (Boolean), `KEY_ACTIVE_PRESET` (String), `KEY_EFFECT_FIDELITY` (Float), `KEY_EFFECT_BASS` (Float), `KEY_EFFECT_SURROUND` (Float), `KEY_EFFECT_DYNAMIC_BOOST` (Float), `KEY_EFFECT_AMBIENCE` (Float), `KEY_EQ_GAINS` (String / comma-delimited or JSON float array), `KEY_HAPTICS_MODE` (String).
  - On app launch in `MainActivity.onCreate()` / `SessionCompanionService.onCreate()`, initialize `FxSoundRepository` from DataStore.
  - When repository updates, dispatch asynchronous write with 200 ms debounce to avoid disk thrashing during slider dragging.
- **Target Files:**
  - `app/src/main/java/com/fxsound/audio/data/FxSoundPreferencesDataStore.kt` (New)
  - `app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt` (Update)
- **Acceptance Criteria:**
  1. Setting Clarity to 8.5, Bass to 7.0, and toggling power off survives `adb shell am force-stop com.fxsound.audio` and app restart.
  2. Zero UI hitching or dropped frames during continuous slider movement.

---

### Task T-002: Decouple Fast Audio Metrics from UI State (P0)
- **Goal:** Eliminate the 47 Hz full-screen recomposition cascade caused by audio peak and FFT spectrum updates.
- **Technical Implementation:**
  - Extract `inputPeak`, `outputPeak`, `inputRms`, `outputRms`, `isClipping`, and `spectrumBands` from `FxSoundUiState`.
  - Create dedicated `AcousticMetricsState`:
    ```kotlin
    data class AcousticMetrics(
        val inputPeak: Float = 0f,
        val outputPeak: Float = 0f,
        val inputRms: Float = 0f,
        val outputRms: Float = 0f,
        val isClipping: Boolean = false,
        val spectrumBands: FloatArray = FloatArray(16)
    )
    ```
  - Expose `FxSoundRepository.metrics: StateFlow<AcousticMetrics>`.
  - In `HomeScreen.kt`, pass `metricsFlow` directly to `AcousticMonitorView.kt` so only `AcousticMonitorView` recomposes on metric updates.
- **Target Files:**
  - `app/src/main/java/com/fxsound/audio/model/FxSoundUiState.kt`
  - `app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt`
  - `app/src/main/java/com/fxsound/audio/ui/components/AcousticMonitorView.kt`
  - `app/src/main/java/com/fxsound/audio/ui/screens/HomeScreen.kt`
- **Acceptance Criteria:**
  1. During active Hi-Fi reference playback, Android Studio Layout Inspector shows recompositions occurring **only** inside `AcousticMonitorView`.
  2. CPU utilization of the main thread drops by at least 25% during active playback.

---

### Task T-003: In-App Local Hi-Fi Media Player via Media3 ExoPlayer (P1)
- **Goal:** Enable users to pick and play their own music files (FLAC, WAV, MP3, AAC) through the genuine native C++ FxSound DSP library.
- **Technical Implementation:**
  - Add dependency `androidx.media3:media3-exoplayer:1.5.0`.
  - Implement a custom `ExoPlayerFxSoundAudioProcessor : androidx.media3.common.audio.AudioProcessor`.
  - In `queueInput(inputBuffer: ByteBuffer)`, pass raw interleaved PCM16 to `FxSoundNativeBridge.processPcm16()`, returning processed buffer to ExoPlayer's audio sink.
  - Add a "Local Music" file picker screen using `ActivityResultContracts.GetContent()` for `audio/*`.
- **Target Files:**
  - `app/src/main/java/com/fxsound/audio/engine/FxSoundAudioProcessor.kt` (New)
  - `app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt` (Refactor to use Media3)
  - `app/src/main/java/com/fxsound/audio/ui/screens/LocalPlayerScreen.kt` (New)
- **Acceptance Criteria:**
  1. User can pick an arbitrary `.flac` or `.mp3` file from device storage and play it.
  2. Audio is enhanced in real time by `libfxsound_dsp.so`.
  3. Sliders, EQ, and Master Power toggles immediately alter the sound of the playing track.

---

### Task T-004: Audio Device Callback & Dynamic Calibration Profiles (P1)
- **Goal:** Automatically adjust DSP parameters when output switches between Built-in Speaker, Bluetooth A2DP, and Wired/USB Headphones.
- **Technical Implementation:**
  - Implement `AudioDeviceCallback` inside `SessionCompanionService`:
    ```kotlin
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            evaluateActiveAudioDevice()
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            evaluateActiveAudioDevice()
        }
    }
    ```
  - Define device profile targets:
    - **Phone Speaker:** High-pass filter rolloff (<100 Hz), Vocal Clarity boost (+3 dB), Dynamic Boost (+4 dB), Limiter threshold (-0.5 dBFS) to protect micro-speakers.
    - **Headphones / Earphones:** Extended HyperBass, 3D Surround (+5 dB), relaxed limiter.
    - **Bluetooth / Car Audio:** Balanced loudness curve with mild dynamic leveling.
- **Target Files:**
  - `app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt`
  - `app/src/main/java/com/fxsound/audio/model/AudioOutputDeviceType.kt` (New)
- **Acceptance Criteria:**
  1. Connecting Bluetooth headphones switches active device profile without audio interruptions or clicks.
  2. UI displays active output badge (e.g., "Bluetooth • Sony WH-1000XM5").

---

### Task T-005: MediaStyle Foreground Notification with Quick Controls (P1)
- **Goal:** Upgrade the companion foreground notification into a full `MediaStyle` notification with active power toggle and preset cycling actions.
- **Technical Implementation:**
  - Use `androidx.media.app.NotificationCompat.MediaStyle`.
  - Add Action 1: "Toggle Power" (`ACTION_TOGGLE_POWER` handled via `BroadcastReceiver` / PendingIntent).
  - Add Action 2: "Next Preset" (`ACTION_CYCLE_PRESET`).
  - Set `setShowActionsInCompactView(0, 1)`.
- **Target Files:**
  - `app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt`
  - `app/src/main/java/com/fxsound/audio/receiver/NotificationActionReceiver.kt` (New)
- **Acceptance Criteria:**
  1. Notification displays Master Bypass button and Next Preset button in locked screen and notification pull-down.
  2. Tapping bypass toggles FxSound processing immediately without bringing the app to the foreground.
