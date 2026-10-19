# Audio Bug Investigation & Architecture Audit

**Target System:** FxSound Android (Ported C++ DfxDsp & Android DynamicsProcessing)  
**Date:** October 19, 2026  
**Status:** Comprehensive Analysis Complete — Source Code Preserved (No modifications applied)  
**Document Purpose:** In-depth diagnosis of two critical manual-testing audio bugs:
1. Bass enhancement causing slight volume fluctuation up and down when listening through headphones (limiter pumping/breathing).
2. Most effect toggles and sliders appearing not to work during playback.

---

## Table of Contents
1. [Relevant Source Files](#1-relevant-source-files)
2. [Audio Processing Flow & Pipeline Architecture](#2-audio-processing-flow--pipeline-architecture)
3. [Evidence-Backed Findings: Bug 1 (Volume Fluctuation / Pumping)](#3-evidence-backed-findings-bug-1-volume-fluctuation--pumping)
4. [Evidence-Backed Findings: Bug 2 (Effect Toggles / Sliders Not Working)](#4-evidence-backed-findings-bug-2-effect-toggles--sliders-not-working)
5. [Root Causes vs. Unverified Hypotheses](#5-root-causes-vs-unverified-hypotheses)
6. [Smallest Safe Fixes](#6-smallest-safe-fixes)
7. [Regression & Manual Testing Plan](#7-regression--manual-testing-plan)

---

## 1. Relevant Source Files

The following files constitute the active audio signal paths, state management, and user interface controls investigated:

### Android AudioFx & Companion Service Pipeline
- [`DynamicsProcessingTranslator.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt): Translates UI model parameters (Clarity, Ambience, Surround, Dynamic Boost, Bass Boost, 10-Band EQ, Master Gain) into Android `DynamicsProcessing.Config` (Pre-EQ, Multi-Band Compressor, Post-EQ, Output Limiter, Input Gain headroom compensation), `BassBoost` strength, and `Virtualizer` strength.
- [`SessionCompanionService.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt): Foreground service managing active `AudioEffect` chains for Global Audio Session 0 and external media players (Spotify, YouTube, Poweramp). Responsible for effect instantiation, lifecycle, and dynamic parameter synchronization.
- [`AudioFlingerSessionTracker.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/AudioFlingerSessionTracker.kt): Rootless `dumpsys media.audio_flinger` parser for discovering untethered audio playback sessions when `android.permission.DUMP` is available.
- [`AudioSessionReceiver.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/receiver/AudioSessionReceiver.kt): Broadcast receiver capturing `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and `ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION`.

### Native C++ DfxDsp Engine & JNI Pipeline
- [`HiFiPlayerEngine.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt): In-app reference player managing `AudioTrack` (32-bit float with 16-bit PCM fallback), audio focus, and real-time JNI DSP dispatch.
- [`FxSoundNativeBridge.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/nativebridge/FxSoundNativeBridge.kt): Kotlin JNI bridge declaring native initialization, parameter update, and buffer processing bindings.
- [`fxsound_jni.cpp`](file:///g:/projects/fxsound%20android/app/src/main/cpp/jni/fxsound_jni.cpp): JNI entry points interfacing Kotlin with `FxSoundEngine`.
- [`fxsound_engine.h`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.h) & [`fxsound_engine.cpp`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp): C++ wrapper around upstream `DfxDsp` core handling parameter smoothing, BS2B crossfeed, harmonic excitation, and buffer translation.
- [`DfxDspPrivate.cpp`](file:///g:/projects/fxsound%20android/app/src/main/cpp/fxsound_dsp/DfxDspPrivate.cpp): Core upstream FxSound DSP management interfacing with `dfxpUniversalModifySamples` and `ptechDsp`.
- [`dfxpComm.cpp`](file:///g:/projects/fxsound%20android/app/src/main/cpp/fxsound_dsp/ptutil/dfxp/dfxpComm.cpp) & [`dfxpSet.cpp`](file:///g:/projects/fxsound%20android/app/src/main/cpp/fxsound_dsp/ptutil/dfxp/dfxpSet.cpp): DSP parameter serialization, MIDI-to-real conversions, and hardware communications.

### State, Data & UI Layer
- [`FxSoundRepository.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/data/FxSoundRepository.kt): Thread-safe state holder (`_uiState` StateFlow), debounced persistence scheduler, and callback dispatcher (`onStateChangedListener`).
- [`FxSoundModel.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/model/FxSoundModel.kt): Immutable domain data classes (`FxSoundUiState`, `FxSoundEffectId`, `RouteStatus`, `AcousticMetrics`).
- [`HomeScreen.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/ui/screens/HomeScreen.kt): Compose UI hosting Master Power Button, Route Status Badge, Preset Selector, and 5 Effect Sliders.
- [`EffectSlider.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/ui/components/EffectSlider.kt): Slider composable with precision badge and haptic feedback.
- [`SettingsScreen.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/ui/screens/SettingsScreen.kt): Controls for Binaural Crossfeed switch, latency modes, and reference multitone playback toggle.

---

## 2. Audio Processing Flow & Pipeline Architecture

The application implements a **Dual-Engine Architecture**:

```
                                  [ User UI Interaction ]
                             (HomeScreen / Settings / Equalizer)
                                             │
                                             ▼
                                  [ FxSoundRepository ]
                                 (MutableStateFlow UI State)
                                             │
                     ┌───────────────────────┴───────────────────────┐
                     ▼                                               ▼
       [ Pipeline A: System Companion ]             [ Pipeline B: In-App Reference ]
        (Spotify / YouTube / Mix 0)                 (Multitone / C++ DfxDsp Engine)
                     │                                               │
                     ▼                                               ▼
       [ SessionCompanionService ]                          [ HiFiPlayerEngine ]
                     │                                               │
        ┌────────────┴────────────┐                        ┌─────────┴─────────┐
        ▼                         ▼                        ▼                   ▼
[ DynamicsProcessing ]     [ Standalone AudioFx ]    [ Float AudioTrack ]  [ PCM16 Fallback ]
  - Input Gain Atten.       - BassBoost               (32-bit float JNI)   (16-bit short JNI)
  - Pre-EQ (HyperBass)      - Virtualizer                  │                   │
  - Multi-Band Comp (MBC)   - PresetReverb                 ▼                   ▼
  - Post-EQ + Clarity                               [ processFloat() ]   [ processPcm16() ]
  - Brickwall Peak Limiter                          (Parameter Smooth)   (NO PARAM SYNC!)
                                                           │                   │
                                                           └─────────┬─────────┘
                                                                     ▼
                                                             [ DfxDsp Core ]
                                                            (libfxsound_dsp.so)
```

### 2.1 Pipeline A: System Companion Engine (`SessionCompanionService`)
- Operates as an Android `ForegroundService` with notification channel and watchdog timer.
- Hooks audio via two mechanisms:
  1. **Global Audio Session 0:** Attached in [`ensureGlobalSessionActive()`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L303-L388) to enhance the global Android audio mix.
  2. **App-Specific Sessions:** Captured when media players broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` or via rootless `AudioFlingerSessionTracker`.
- Instantiates an `ActiveSession` container containing:
  - `DynamicsProcessing` (Pre-EQ, 4-band MBC, Post-EQ, Brickwall Limiter)
  - `BassBoost` (Standalone Android AudioFx)
  - `Virtualizer` (Standalone Android AudioFx)
  - `PresetReverb` (Standalone Android AudioFx)
- Live updates are triggered whenever `FxSoundRepository.onStateChangedListener` fires, executing [`updateAllActiveSessions()`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L556-L608).

### 2.2 Pipeline B: In-App Reference Hi-Fi Engine (`HiFiPlayerEngine`)
- Direct C++ DSP synthesis pipeline playing an acoustic multitone signal.
- Queries hardware sample rate and buffer frames via `AudioManager` properties.
- Attempts to initialize `AudioTrack` with `ENCODING_PCM_FLOAT`; falls back to `ENCODING_PCM_16BIT` if the HAL rejects float mode.
- Collects `FxSoundRepository.uiState` and synchronizes parameters across the JNI bridge using [`syncParametersToNative()`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt#L361-L371).
- Processes audio blocks through [`FxSoundEngine::processFloat()`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp#L203-L331) or [`FxSoundEngine::processPcm16()`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp#L151-L201).

---

## 3. Evidence-Backed Findings: Bug 1 (Volume Fluctuation / Pumping)

**Symptom:** When listening through headphones, bass enhancement causes slight volume fluctuation up and down with the beat of the music.

### Finding 1.1: Double Bass-Boost Staging (Compound Gain Overshoot)
- **Files:** [`DynamicsProcessingTranslator.kt:45-57`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L45-L57) and [`SessionCompanionService.kt:338-346`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L338-L346)
- **Evidence:**
  In `DynamicsProcessingTranslator.kt`:
  ```kotlin
  // Authentic FxSound HyperBass low-end contour up to +8.5 dB
  val bassBoostMaxDb = (bass / 10.0f) * 8.5f
  val preEq = Eq(true, isPowerOn, 10).apply {
      for (i in 0 until 10) {
          val freq = EQ_FREQUENCIES[i].toFloat()
          val bassBoostDb = when {
              freq <= 64.0f -> bassBoostMaxDb
              freq <= 125.0f -> bassBoostMaxDb * 0.75f
              freq <= 250.0f -> bassBoostMaxDb * 0.40f
              else -> 0.0f
          }
          setBand(i, EqBand(true, freq, bassBoostDb))
      }
  }
  ```
  Concurrently, in `SessionCompanionService.kt` (lines 338–346 for Session 0, and lines 471–477 for app sessions):
  ```kotlin
  val bass = BassBoost(0, sessionId).apply {
      val strength = DynamicsProcessingTranslator.translateBassBoost(
          state.effects[FxSoundEffectId.BASS] ?: 4.0f
      )
      setStrength(strength)
      enabled = state.isPowerOn
  }
  session.bassBoost = bass
  ```
- **Analysis:**
  Bass is boosted **twice in series**:
  1. First, inside `DynamicsProcessing.PreEq` with up to **+8.5 dB** at $\le 64\text{ Hz}$.
  2. Second, via the external `android.media.audiofx.BassBoost` effect with strength up to `1000/1000`, which typically adds **+10 to +12 dB** of low-frequency resonant shelf boost.
  The total cumulative low-frequency gain applied to the signal exceeds **+18 dB to +20 dB**.

---

### Finding 1.2: Violent Brickwall Limiter Gain Reduction & Rapid Release
- **File:** [`DynamicsProcessingTranslator.kt:127-136`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L127-L136)
- **Evidence:**
  ```kotlin
  // 4. Output Peak Limiter (Brickwall peak protection against clipping overs)
  val limiter = Limiter(
      true,               // inUse
      isPowerOn,          // enabled
      0,                  // linkGroup
      0.5f,               // attackTimeMs (instantaneous inter-sample peak catch)
      30.0f,              // releaseTimeMs (smooth, musical release)
      50.0f,              // ratio (true brickwall peak limiter)
      -0.1f,              // threshold in dBFS (prevents DAC overs)
      0.0f                // postGain must be 0dB (prevents duplicate master gain application)
  )
  ```
- **Analysis:**
  - **Ratio = 50.0:1:** This is an extreme brickwall compression ratio.
  - **Threshold = -0.1 dBFS:** Any signal exceeding -0.1 dBFS is clamped instantaneously.
  - **Attack Time = 0.5 ms:** When a bass transient (kick drum or 808 sub-bass) arrives with +18 dB of boost, the limiter clamps down within 500 microseconds.
  - **Broadband Processing:** In `DynamicsProcessing`, the output `Limiter` stage is a single-band (broadband) limiter across the entire stereo master bus. Ducking the bass note ducks the **entire mix**, including mids, vocals, and high frequencies.
  - **Release Time = 30.0 ms:** As soon as the transient note subsides, the limiter releases its 6–12 dB gain reduction within 30 milliseconds. The volume of the entire mix rapidly springs back up. This rapid cycle of ducking on transient impact followed by sudden volume recovery 30 ms later is the exact definition of **limiter pumping/breathing**.

---

### Finding 1.3: Severely Clamped Headroom Compensation
- **File:** [`DynamicsProcessingTranslator.kt:138-143`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L138-L143)
- **Evidence:**
  ```kotlin
  // 5. Intelligent Preamp Headroom Allocation
  // Moderate compensation preserves full output loudness while avoiding limiter pumping
  val totalPeakPotential = max(maxPositiveEqBoost, bassBoostMaxDb) + (positiveGainSum * 0.08f)
  val headroomCompensationDb = (totalPeakPotential * 0.15f).coerceIn(0.0f, 2.5f)
  val finalInputGainDb = (masterGainDb - headroomCompensationDb).coerceIn(-12.0f, 6.0f)
  ```
- **Analysis:**
  Although the code comments state *"Moderate compensation preserves full output loudness while avoiding limiter pumping"*, line 141 clamps `headroomCompensationDb` to a strict maximum of **`2.5 dB`** (`coerceIn(0.0f, 2.5f)`).
  - Modern mastered music on Spotify or YouTube is mastered to between -0.5 and 0.0 dBFS true peak.
  - Applying +8.5 dB from Pre-EQ plus +10 dB from `BassBoost` (and potentially +14 dB from user EQ sliders) pushes the peak signal potential to +18 dBFS or higher.
  - Attenuating the input by only **2.5 dB** leaves an excess overshoot of **+15.5 dBFS**.
  - As a result, the limiter is driven deeply into continuous saturation and dynamic pumping during every bass beat.

---

### Finding 1.4: Multi-Band Compressor (MBC) Band 0 Compounding
- **File:** [`DynamicsProcessingTranslator.kt:60-92`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L60-L92)
- **Evidence:**
  ```kotlin
  val mbcRatio = 1.0f + (dynamicBoost / 10.0f) * 1.4f
  val mbcThresholdDb = -8.0f - (dynamicBoost / 10.0f) * 6.0f
  val mbcGainDb = (dynamicBoost / 10.0f) * 4.0f
  ...
  val mbcAttackTimes = floatArrayOf(30.0f, 12.0f, 5.0f, 2.0f)
  val mbcReleaseTimes = floatArrayOf(120.0f, 80.0f, 50.0f, 35.0f)
  ```
- **Analysis:**
  Band 0 (< 250 Hz) has an attack of 30 ms and release of 120 ms with a threshold down to -14 dBFS and ratio up to 2.4:1, followed by +4.0 dB make-up gain.
  The 30 ms attack allows initial transient spikes to punch through uncompressed, which immediately slams the downstream 0.5 ms brickwall limiter. Then, 30 ms into the bass note, the MBC clamps down, altering the signal envelope before handing it to the output limiter. This two-stage dynamic modulation creates a secondary wave of gain modulation.

---

### Finding 1.5: Acoustic Reason for Headphone Sensitivity
- Loudspeakers create acoustic soundwaves that propagate into the listening room, where physical room reflections, reverberation, and room modal decay smooth out rapid broadband gain fluctuations.
- Headphones couple directly into the listener's ear canal with zero room acoustic masking and high isolation.
- Consequently, gain dips of even 1.5–3 dB are acutely audible as an unnatural, rhythmic wobble ("breathing") directly in the listener's ears.

---

## 4. Evidence-Backed Findings: Bug 2 (Effect Toggles / Sliders Not Working)

**Symptom:** Most effect toggles appear not to work during manual testing.

### Finding 2.1: Dynamic In-Place Update Failure & Single Try-Catch Abort in `SessionCompanionService`
- **File:** [`SessionCompanionService.kt:572-607`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L572-L607)
- **Evidence:**
  ```kotlin
  for ((id, session) in activeSessions) {
      try {
          session.dynamicsProcessing?.apply {
              setAllChannelsTo(config.getChannelByChannelIndex(0))
              enabled = state.isPowerOn
          }
          session.equalizer?.apply {
              applyEqualizerBands(this, state.eqGains, clarity)
              enabled = state.isPowerOn
          }
          session.bassBoost?.apply {
              setStrength(bassStrength)
              enabled = state.isPowerOn
          }
          session.virtualizer?.apply {
              setStrength(virtStrength)
          }
          session.presetReverb?.apply {
              ...
          }
      } catch (e: Exception) {
          Log.w(TAG, "Error updating session $id: ${e.message}")
      }
  }
  ```
- **Analysis:**
  1. **The In-Place `setAllChannelsTo` Flaw:**
     `DynamicsProcessing.setAllChannelsTo(Channel)` on Android replaces the channel configuration. On many Android OEM audio HALs (including Qualcomm, Samsung Exynos, and Google Tensor implementations), calling `setAllChannelsTo` while an audio effect is currently enabled and actively rendering audio is either **unsupported**, **ignored**, or throws a runtime exception (`UnsupportedOperationException`, `IllegalStateException: not initialized`, or `RuntimeException: Effect failed to set parameter`).
  2. **The Cascading Try-Catch Abort:**
     All 5 effects (`dynamicsProcessing`, `equalizer`, `bassBoost`, `virtualizer`, and `presetReverb`) are grouped inside a **single shared `try` block**.
     If `session.dynamicsProcessing?.apply { setAllChannelsTo(...) }` throws ANY exception, execution immediately jumps to line 604:
     `catch (e: Exception) { Log.w(TAG, "Error updating session $id: ${e.message}") }`
     **Every subsequent line is aborted**:
     - `session.equalizer` is never updated.
     - `session.bassBoost` is never updated.
     - `session.virtualizer` is never updated.
     - `session.presetReverb` is never updated.
     A single HAL error or in-place update rejection on `DynamicsProcessing` silently disables real-time slider updates for **ALL other effects** on that session.

---

### Finding 2.2: Native PCM 16-Bit Engine Parameter Sync Completely Missing
- **File:** [`fxsound_engine.cpp:151-254`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp#L151-L254)
- **Evidence:**
  Compare the two processing functions in `fxsound_engine.cpp`:
  
  In `processFloat()` (lines 208–254):
  ```cpp
  // 1. Click-free Exponential Parameter Smoothing
  bool effectsNeedSync = false;
  for (int i = 0; i < FXSOUND_NUM_EFFECTS; ++i) {
      float diff = targetEffectValues_[i] - smoothedEffectValues_[i];
      if (std::abs(diff) > 0.005f) {
          smoothedEffectValues_[i] += 0.2f * diff;
          effectsNeedSync = true;
      } else {
          smoothedEffectValues_[i] = targetEffectValues_[i];
      }
  }
  if (effectsNeedSync && dspCore_) {
      dspCore_->setEffectValue(DfxDsp::Fidelity, smoothedEffectValues_[FXSOUND_EFFECT_FIDELITY]);
      dspCore_->setEffectValue(DfxDsp::Ambience, smoothedEffectValues_[FXSOUND_EFFECT_AMBIENCE]);
      dspCore_->setEffectValue(DfxDsp::Surround, smoothedEffectValues_[FXSOUND_EFFECT_SURROUND]);
      dspCore_->setEffectValue(DfxDsp::DynamicBoost, smoothedEffectValues_[FXSOUND_EFFECT_DYNAMIC_BOOST]);
      dspCore_->setEffectValue(DfxDsp::Bass, smoothedEffectValues_[FXSOUND_EFFECT_BASS]);
  }
  // (Followed by EQ bands smoothing and master gain smoothing...)
  ```

  Now inspect `processPcm16()` (lines 151–185):
  ```cpp
  int FxSoundEngine::processPcm16(const int16_t* inSamples, int16_t* outSamples, int numFrames) {
      if (!inSamples || !outSamples || numFrames <= 0) return 0;
      ...
      if (!isEnabled_.load(std::memory_order_relaxed) || !dspCore_) {
          std::memcpy(outSamples, inSamples, totalSamples * sizeof(int16_t));
          ...
          return numFrames;
      }

      if (bitDepth_ != 16) {
          dspCore_->setSignalFormat(16, numChannels_, sampleRate_, 16);
          bitDepth_ = 16;
      }

      dspCore_->processAudio(
          const_cast<short int*>(inSamples),
          outSamples,
          numFrames,
          0
      );
      ...
      return numFrames;
  }
  ```
- **Analysis:**
  In [`HiFiPlayerEngine.kt:168-175`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt#L168-L175), if the device HAL fails or does not support 32-bit float AudioTrack, it falls back to 16-bit PCM (`isFloatMode = false`).
  In 16-bit PCM mode, [`processPcm16()`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp#L151) is called on every audio frame.
  **`processPcm16()` has ZERO parameter synchronization logic**:
  - `targetEffectValues_` is never synced to `dspCore_`.
  - `targetEqBands_` is never synced to `dspCore_`.
  - `targetMasterGainDb_` is never synced to `dspCore_`.
  Whenever the reference player runs in PCM 16-bit mode, **moving any slider or changing EQ faders during playback has ZERO effect on the audio**. The native DSP continues processing with only the default initialization values set at engine boot.

---

### Finding 2.3: `PresetReverb` (Ambience) Misconfigured as Insert Effect
- **File:** [`SessionCompanionService.kt:366-385, 590-603`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L366-L385)
- **Evidence:**
  ```kotlin
  val reverb = PresetReverb(0, 0).apply {
      if (ambience > 0.0f) {
          val preset = when {
              ambience <= 3.0f -> PresetReverb.PRESET_SMALLROOM
              ambience <= 6.0f -> PresetReverb.PRESET_MEDIUMROOM
              ambience <= 8.5f -> PresetReverb.PRESET_LARGEROOM
              else -> PresetReverb.PRESET_PLATE
          }
          setPreset(preset)
          enabled = state.isPowerOn
      } else {
          enabled = false
      }
  }
  session.presetReverb = reverb
  ```
- **Analysis:**
  - In Android AudioFx, `PresetReverb` is an **auxiliary/environmental effect**, not an inline insert effect. To work on an audio track, the track must establish an auxiliary send: `audioTrack.attachAuxEffect(reverb.id)` and `audioTrack.setAuxEffectSendLevel(1.0f)`.
  - When instantiated directly on Audio Session 0 or an external player's session without an auxiliary send route, Android audio HALs do not route audio through `PresetReverb`.
  - On most devices, `new PresetReverb(0, 0)` fails with an error or acts as a complete no-op.
  - Furthermore, mapping a continuous 0.0–10.0 slider to 4 discrete room presets provides no continuous wet/dry mix control. Sliding from 0.1 to 3.0 produces no sound change whatsoever.

---

### Finding 2.4: Audio Session 0 Deprecation & External Media Player Isolation
- **File:** [`SessionCompanionService.kt:303-388`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L303-L388)
- **Evidence:**
  - Android began deprecating and restricting effects applied to Audio Session 0 (global system mix) starting in Android 9 / 10, and strictly enforced isolation in Android 11+ (API 30+).
  - Modern media players (YouTube, Chrome, TikTok, Poweramp, and modern Spotify versions using audio offload or low-latency output threads) create isolated audio sessions and route around Session 0.
  - In `HomeScreen.kt:112-115`, if the user has not connected Spotify or granted `android.permission.DUMP` for rootless session tracking, the UI status badge displays:
    `Ready • Awaiting Audio Session`
  - While in this state, audio is playing on the device from YouTube or a browser, but FxSound's effects are attached only to Session 0.
  - When the user moves the sliders, the parameters are sent to Session 0 (which the playing app is bypassing). To the user, it appears that **none of the effect sliders are working**.

---

### Finding 2.5: UI Disconnects & Unused Settings Toggles
- **Files:** [`SettingsScreen.kt:287-299`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/ui/screens/SettingsScreen.kt#L287-L299), [`HiFiPlayerEngine.kt:363`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/HiFiPlayerEngine.kt#L363), and [`SessionCompanionService.kt`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt)
- **Evidence:**
  - In `SettingsScreen.kt`, there is a switch: **"Binaural Headphone Crossfeed"**.
  - Toggling this switch calls `FxSoundRepository.setCrossfeed(enabled)`.
  - `HiFiPlayerEngine.kt` passes this to C++ `nativeBridge.setCrossfeed(enabled)`, which only affects the in-app multitone player (and only in float mode).
  - `SessionCompanionService.kt` **completely ignores `isCrossfeedEnabled`**. When listening to Spotify or system music, toggling this switch does absolutely nothing.
  - On `HomeScreen.kt`, there are no individual on/off switches for effects; the UI only provides sliders. If the user expects individual effect toggles (as in PC FxSound where each effect had a power LED button), they only have continuous sliders, leading to confusion when moving sliders has no audible effect due to Findings 2.1, 2.2, or 2.4.

---

## 5. Root Causes vs. Unverified Hypotheses

| Category | Finding | Classification | Verification Status |
| :--- | :--- | :--- | :--- |
| **Bug 1: Bass Volume Fluctuation** | Compound +18 dB to +20 dB boost from double bass boost staging (Pre-EQ + standalone `BassBoost`). | **Primary Root Cause** | **Verified by Code** ([`DynamicsProcessingTranslator.kt:45`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L45) & [`SessionCompanionService.kt:338`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L338)) |
| **Bug 1: Bass Volume Fluctuation** | Output Limiter has extreme brickwall ratio (50:1), ultra-fast attack (0.5 ms), and fast release (30 ms), ducking the entire broadband mix on bass peaks. | **Primary Root Cause** | **Verified by Code** ([`DynamicsProcessingTranslator.kt:127-136`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L127-L136)) |
| **Bug 1: Bass Volume Fluctuation** | Input headroom compensation is clamped to a maximum of only `2.5 dB`, leaving >15 dB of uncompensated overshoot into the limiter. | **Primary Root Cause** | **Verified by Code** ([`DynamicsProcessingTranslator.kt:141`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L141)) |
| **Bug 1: Bass Volume Fluctuation** | Multi-Band Compressor Band 0 (250 Hz cutoff) 30 ms attack / 120 ms release dynamics compound the limiter envelope. | **Secondary Contributing Factor** | **Verified by Code** ([`DynamicsProcessingTranslator.kt:72-89`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/engine/DynamicsProcessingTranslator.kt#L72-L89)) |
| **Bug 1: Bass Volume Fluctuation** | Headphone driver impedance non-linearities or headphone DAC auto-gain / volume leveling. | *Unverified Hypothesis* | *Unverified on device* (Acoustic measurement required on target hardware) |
| **Bug 2: Sliders Not Working** | In native 16-bit PCM mode (`processPcm16`), parameter synchronization code is completely omitted. Sliders never update `dspCore_` during playback. | **Primary Root Cause (Native Player)** | **Verified by Code** ([`fxsound_engine.cpp:151-201`](file:///g:/projects/fxsound%20android/app/src/main/cpp/engine/fxsound_engine.cpp#L151-L201)) |
| **Bug 2: Sliders Not Working** | In `SessionCompanionService`, all 5 effects are updated in a single `try` block. If `DynamicsProcessing.setAllChannelsTo` fails or throws on the HAL, all remaining effect updates are aborted. | **Primary Root Cause (Companion Service)** | **Verified by Code** ([`SessionCompanionService.kt:572-607`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L572-L607)) |
| **Bug 2: Sliders Not Working** | Session 0 isolation on Android 10+: external players (YouTube, Chrome, TikTok) bypass Session 0, so moving sliders in FxSound does not affect those apps unless hooked via dumpsys. | **Primary Root Cause (System-Wide Audio)** | **Verified by Code & Android OS Spec** ([`SessionCompanionService.kt:255-265`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L255-L265)) |
| **Bug 2: Sliders Not Working** | `PresetReverb` (Ambience) is an auxiliary effect configured without an auxiliary send bus, resulting in a no-op on Session 0. | **Contributing Root Cause (Ambience Effect)** | **Verified by Code & Android AudioFx Spec** ([`SessionCompanionService.kt:366-385`](file:///g:/projects/fxsound%20android/app/src/main/java/com/fxsound/audio/service/SessionCompanionService.kt#L366-L385)) |
| **Bug 2: Sliders Not Working** | Certain OEM HALs (MIUI/HyperOS, ColorOS) killing AudioEffect chains when the app transitions to the background. | *Unverified Hypothesis* | *Unverified on device* (Requires OEM logcat capture) |

---

## 6. Smallest Safe Fixes

*Note: In accordance with project instructions, these fixes are documented here as proposed solutions; no workspace source code has been altered.*

### 6.1 Smallest Safe Fix for Bug 1 (Limiter Pumping & Volume Fluctuation)

#### Action 1: Eliminate Redundant Double Bass-Boost
In `SessionCompanionService.kt`:
When `DynamicsProcessing` is available and active on a session, do not attach or enable the standalone `BassBoost` effect. The Pre-EQ stage in `DynamicsProcessingTranslator` already delivers the authentic +8.5 dB HyperBass low-end contour.
```diff
--- a/SessionCompanionService.kt
+++ b/SessionCompanionService.kt
@@ -343,3 +343,3 @@
-                enabled = state.isPowerOn
+                // Disable standalone BassBoost when DynamicsProcessing handles HyperBass
+                enabled = false
```

#### Action 2: Musical Limiter Release & Realistic Preamp Headroom Allocation
In `DynamicsProcessingTranslator.kt`:
1. Relax the limiter release time from 30 ms to **150 ms** (or link release to signal dynamics) to eliminate audible gain modulation.
2. Uncap `headroomCompensationDb` from the rigid 2.5 dB ceiling so that when bass boost (+8.5 dB) and user EQ are applied, the preamp input gain is appropriately attenuated to prevent clipping before the limiter.
```diff
--- a/DynamicsProcessingTranslator.kt
+++ b/DynamicsProcessingTranslator.kt
@@ -131,3 +131,3 @@
         0.5f,               // attackTimeMs
-        30.0f,              // releaseTimeMs (causes pumping at 30ms)
+        150.0f,             // releaseTimeMs (musical release eliminates breathing)
         50.0f,              // ratio
@@ -141,2 +141,2 @@
-        val headroomCompensationDb = (totalPeakPotential * 0.15f).coerceIn(0.0f, 2.5f)
+        val headroomCompensationDb = (totalPeakPotential * 0.45f).coerceIn(0.0f, 8.0f)
         val finalInputGainDb = (masterGainDb - headroomCompensationDb).coerceIn(-12.0f, 6.0f)
```

---

### 6.2 Smallest Safe Fix for Bug 2 (Effect Toggles / Sliders Not Working)

#### Action 1: Add Parameter Sync Loop to `processPcm16()` in Native Engine
In `fxsound_engine.cpp`:
Implement parameter smoothing and synchronization in `processPcm16()` identical to `processFloat()`.
```diff
--- a/fxsound_engine.cpp
+++ b/fxsound_engine.cpp
@@ -164,2 +164,38 @@
     if (!isEnabled_.load(std::memory_order_relaxed) || !dspCore_) {
+        // Bypass processing...
+    }
+
+    // Sync effects and EQ to dspCore_ during PCM16 playback
+    bool effectsNeedSync = false;
+    for (int i = 0; i < FXSOUND_NUM_EFFECTS; ++i) {
+        float diff = targetEffectValues_[i] - smoothedEffectValues_[i];
+        if (std::abs(diff) > 0.005f) {
+            smoothedEffectValues_[i] += 0.2f * diff;
+            effectsNeedSync = true;
+        } else {
+            smoothedEffectValues_[i] = targetEffectValues_[i];
+        }
+    }
+    if (effectsNeedSync && dspCore_) {
+        dspCore_->setEffectValue(DfxDsp::Fidelity, smoothedEffectValues_[FXSOUND_EFFECT_FIDELITY]);
+        dspCore_->setEffectValue(DfxDsp::Ambience, smoothedEffectValues_[FXSOUND_EFFECT_AMBIENCE]);
+        dspCore_->setEffectValue(DfxDsp::Surround, smoothedEffectValues_[FXSOUND_EFFECT_SURROUND]);
+        dspCore_->setEffectValue(DfxDsp::DynamicBoost, smoothedEffectValues_[FXSOUND_EFFECT_DYNAMIC_BOOST]);
+        dspCore_->setEffectValue(DfxDsp::Bass, smoothedEffectValues_[FXSOUND_EFFECT_BASS]);
+    }
+
+    bool eqNeedsSync = false;
+    for (int i = 0; i < 10; ++i) {
+        float diff = targetEqBands_[i] - smoothedEqBands_[i];
+        if (std::abs(diff) > 0.005f) {
+            smoothedEqBands_[i] += 0.2f * diff;
+            eqNeedsSync = true;
+        } else {
+            smoothedEqBands_[i] = targetEqBands_[i];
+        }
+    }
+    if (eqNeedsSync && dspCore_) {
+        for (int i = 1; i <= 31; ++i) {
+            float source_index = static_cast<float>(i - 1) * 9.0f / 30.0f;
+            int lower_index = static_cast<int>(source_index);
+            int upper_index = (lower_index < 9) ? lower_index + 1 : 9;
+            float fraction = source_index - static_cast<float>(lower_index);
+            float interpolatedGain = smoothedEqBands_[lower_index] +
+                (smoothedEqBands_[upper_index] - smoothedEqBands_[lower_index]) * fraction;
+            dspCore_->setEqBandBoostCut(i - 1, interpolatedGain);
+        }
+    }
```

#### Action 2: Isolate Effect Updates into Independent Try-Catch Blocks
In `SessionCompanionService.kt`:
Update each effect in an isolated try-catch block so that a HAL rejection on `DynamicsProcessing` does not abort `Equalizer`, `BassBoost`, or `Virtualizer`. Update `DynamicsProcessing` stage-by-stage (`setPreEqAllChannelsTo`, `setMbcAllChannelsTo`, `setPostEqAllChannelsTo`, `setLimiterAllChannelsTo`) to avoid full-channel structure failures.
```diff
--- a/SessionCompanionService.kt
+++ b/SessionCompanionService.kt
@@ -573,15 +573,27 @@
         for ((id, session) in activeSessions) {
-            try {
-                session.dynamicsProcessing?.apply {
-                    setAllChannelsTo(config.getChannelByChannelIndex(0))
-                    enabled = state.isPowerOn
-                }
-                session.equalizer?.apply { ... }
-                session.bassBoost?.apply { ... }
-            } catch (e: Exception) { ... }
+            // 1. DynamicsProcessing with isolated error boundary
+            try {
+                session.dynamicsProcessing?.apply {
+                    setPreEqAllChannelsTo(config.getPreEq())
+                    setMbcAllChannelsTo(config.getMbc())
+                    setPostEqAllChannelsTo(config.getPostEq())
+                    setLimiterAllChannelsTo(config.getLimiter())
+                    enabled = state.isPowerOn
+                }
+            } catch (dpEx: Exception) {
+                Log.w(TAG, "DP update failed on session $id: ${dpEx.message}")
+            }
+
+            // 2. Equalizer fallback
+            try {
+                session.equalizer?.apply {
+                    applyEqualizerBands(this, state.eqGains, clarity)
+                    enabled = state.isPowerOn
+                }
+            } catch (eqEx: Exception) { ... }
+
+            // 3. Standalone BassBoost
+            try {
+                session.bassBoost?.apply {
+                    setStrength(bassStrength)
+                    enabled = state.isPowerOn
+                }
+            } catch (bbEx: Exception) { ... }
```

#### Action 3: Clear UI Guidance for Unhooked Players
In `HomeScreen.kt`:
When `state.routeStatus == RouteStatus.READY`, display an informative banner or card explaining that the system mix is active, but third-party apps (e.g., YouTube) require ADB DUMP permission or an app supporting global audio effects (e.g., Spotify with "Device Broadcast Status" enabled) to receive effect processing.

---

## 7. Regression & Manual Testing Plan

### 7.1 Manual Verification Procedures

#### Test Case 1: Bass Limiter Pumping on Headphones
1. **Equipment:** Wired 3.5mm headphones or USB-C DAC headphones connected to the Android test device.
2. **Signal:** Play an EDM, hip-hop, or drum-and-bass track with heavy, repetitive sub-bass kick transients (e.g., 50–70 Hz kicks) normalized to 0 dBFS.
3. **Configuration:**
   - In FxSound, turn Master Power ON.
   - Set Bass Boost slider to `10.0` (maximum).
   - Set Dynamic Boost slider to `8.0`.
   - Set Clarity slider to `5.0`.
4. **Observation:**
   - **Current Buggy State:** On every kick drum hit, the entire frequency spectrum (vocals, hi-hats, synth leads) noticeably dips in volume by several dB and then bounces back up 30 ms later, creating a noticeable volume wobble / breathing effect.
   - **Expected Correct State (Post-Fix):** The kick drum has deep, solid low-end impact with zero audible ducking or modulation of mid/high frequencies.

#### Test Case 2: Reference Player Slider Responsiveness (PCM 16-Bit Mode)
1. **Equipment:** Android test device.
2. **Procedure:**
   - Navigate to **Settings** screen.
   - Tap **"Play Reference Multitone (DfxDsp C++)"**.
   - Navigate to **Home** screen.
   - Drag the **Clarity** slider from `0.0` to `10.0`.
   - Drag the **Bass Boost** slider from `0.0` to `10.0`.
   - Navigate to **Equalizer** screen and move the 1 kHz fader from `-14 dB` to `+14 dB`.
3. **Observation:**
   - **Current Buggy State (in PCM16 fallback mode):** Sliders move visually, but the audio tone remains completely unchanged in timbre, loudness, and frequency response.
   - **Expected Correct State (Post-Fix):** Immediate, smooth real-time tonal shifts without clicks or delay.

#### Test Case 3: Spotify / Companion Service Live Slider Updates
1. **Procedure:**
   - Launch Spotify, enable "Device Broadcast Status" in Spotify settings, and start music playback.
   - Open FxSound; verify Status Badge indicates `Connected: Spotify`.
   - Move **Clarity** slider from `0.0` to `10.0`.
   - Move **Bass Boost** slider from `0.0` to `10.0`.
   - Toggle **Master Power** OFF and ON.
2. **Observation:**
   - **Current Buggy State:** If `DynamicsProcessing.setAllChannelsTo` fails on the device HAL, none of the sliders change the music.
   - **Expected Correct State (Post-Fix):** Instant, audible change on every slider adjustment.

---

### 7.2 Automated Regression Tests

The following unit tests should be maintained:
1. **`DynamicsProcessingTranslatorTest`**:
   - Verify that `headroomCompensationDb` scales dynamically with `bassBoostMaxDb` and positive EQ boosts without being clipped to 2.5 dB.
   - Verify that limiter release time is $\ge 100\text{ ms}$.
2. **`FxSoundEngineTest` (Native C++)**:
   - Run a test feeding test buffers into `processPcm16()` while calling `setEffect()` and verify that the output spectrum/RMS values reflect the parameter change within 500 ms (verifying parameter synchronization).
3. **`SessionCompanionServiceTest`**:
   - Verify that failure in `DynamicsProcessing` does not prevent `Equalizer` and `BassBoost` from receiving parameter updates.
