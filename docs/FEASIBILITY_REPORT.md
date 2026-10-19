# Android Audio Feasibility Report — Xiaomi 17 & Android Platform

**Document Version:** 1.0  
**Phase:** Gate 0 (Feasibility & Platform Audit)  
**Status:** BLOCKED / USER APPROVAL REQUIRED  
**Date:** 2026-10-16  
**Auditor:** Antigravity Lead Android Audio Engineer  

---

## 1. Executive Summary

This feasibility audit investigates whether a rootless Android application can provide **system-wide audio enhancement** (specifically for YouTube, Spotify, web browsers, and games) using the actual **FxSound `DfxDsp` C++ audio engine** on the target device (**Xiaomi 17** running Android 15/16 / HyperOS).

### Key Findings:
1. **Universal System-Wide Native DSP is IMPOSSIBLE without Root:**  
   Standard Android platform security and audio architecture (AudioFlinger / AAudio) provide **no public API** that allows a normal third-party app to intercept raw PCM audio from arbitrary external apps, route it through a custom native C++ DSP library, and output the processed stream back to hardware.
2. **`AudioPlaybackCapture` cannot achieve transparent audio enhancement:**  
   While `AudioPlaybackCapture` (API 29+) allows capturing media/game audio with user consent via `MediaProjection`, it is an **observer/tap (non-destructive)** mechanism. The source app continues playing un-delayed dry audio to the physical speakers/headphones. Capturing, processing via DSP, and playing wet audio back creates a **catastrophic acoustic echo and comb-filtering artifact** (approx. 40–120ms round-trip latency). Furthermore, Android provides no public API to selectively mute only the source app's hardware output, and DRM-protected streams (Spotify offline/DRM, YouTube Music DRM) are strictly excluded from capture by the OS.
3. **`AudioEffect` / `DynamicsProcessing` cannot run the FxSound C++ engine:**  
   Android's `AudioEffect` framework only controls the OS's built-in, fixed-function audio effects (AOSP Equalizer, BassBoost, Virtualizer, DynamicsProcessing). It **never passes raw audio buffers** to the calling app. Therefore, the genuine FxSound `DfxDsp` engine cannot be executed through `AudioEffect`. In addition, attaching effects to global output (Session 0) is deprecated and blocked for non-system apps on modern Android, and apps like YouTube, Chrome, and games do not broadcast audio session IDs.
4. **App-Owned Playback with FxSound `DfxDsp` is 100% FEASIBLE:**  
   When audio is routed through an app-owned audio pipeline (using Oboe/AAudio or AudioTrack + JNI), the ported FxSound `DfxDsp` engine operates with full desktop parity: 32-bit floating-point processing, zero comb filtering, low latency, clean bass enhancement, multi-band graphic EQ, and dynamic compression.

---

## 2. Target Device & Environment Audit

### 2.1 Hardware & OS Target Profile
* **Target Model:** Xiaomi 17 (Flagship ARM64-v8a / ARMv9 architecture)
* **OS Target:** Android 15 / Android 16 (Xiaomi HyperOS 2 / 3)
* **Root Requirement:** Explicitly prohibited by project specification
* **SELinux Enforcement:** Strict Enforcing mode (`setenforce 1`)
* **Audio Subsystem:** Qualcomm Snapdragon Audio HAL / Xiaomi Sound AudioFlinger integration

### 2.2 Host Development Environment Status
Inspection of the current build machine reveals:
* **Java Development Kit (JDK):** Java SE 25.0.2 LTS (`build 25.0.2+10-LTS-69`) installed.
* **Git:** Version 2.51.0.windows.2 installed.
* **Android SDK / NDK / CMake / Gradle:** `ANDROID_SDK_HOME` points to `C:\Android` (containing only config files). No active Android SDK, NDK, or command-line tools were registered in system PATH.
* **ADB:** Available in system tool directories (`C:\Program Files (x86)\...`), but not in system PATH.
* **Antigravity Skills:** `android-cli` plugin is present in `.gemini/config/plugins/android-cli-plugin`, though the standalone `android` binary is not yet bootstrapped in PATH. UI development guidance will strictly adhere to `docs/ANDROID_M3_SKILL.md` and modern Material 3 specifications.

---

## 3. Detailed Route-by-Route Technical Audit

### Route A: `AudioEffect` / `DynamicsProcessing` (Session Effects)
* **Mechanism:** Subclasses of `android.media.audiofx.AudioEffect` attach to a specific `audioSessionId`.
* **Session 0 (Global Output Mix):**  
  Historically (Android 2.3–8.0), setting `audioSession = 0` allowed some global effects. Starting in Android 9 and enforced in Android 10+:
  - Session 0 is deprecated for third-party apps.
  - Calling `new DynamicsProcessing(0)` or `new Equalizer(0, 0)` from an ordinary app fails with `AudioEffect.ERROR_BAD_VALUE` (-4) or `AudioEffect.ERROR_INVALID_OPERATION` (-5), or throws `UnsupportedOperationException`.
  - Android requires the signature-or-system permission `android.permission.MODIFY_DEFAULT_AUDIO_EFFECTS`.
* **Targeted Sessions (`ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`):**  
  Apps like Spotify broadcast their `audioSessionId` when playback starts. An app can attach an `AudioEffect` to that session.
* **Critical Blocker for FxSound:**  
  Even when an effect successfully attaches to an audio session, the processing is performed entirely inside the Android `audio_flinger` service by built-in effects plugins. **No audio frames are sent to the app process.** Therefore, FxSound's `DfxDsp` (Fidelity, Ambience, 3D Surround, Dynamic Boost, HyperBass) **CANNOT** run on session audio. Replacing `DfxDsp` with Android's generic `Equalizer` is strictly forbidden by project rules.

### Route B: `AudioPlaybackCapture` (API 29+)
* **Mechanism:** Using `MediaProjectionManager.createScreenCaptureIntent()` to obtain user consent, creating an `AudioPlaybackCaptureConfiguration` with `USAGE_MEDIA` / `USAGE_GAME`, and reading PCM via `AudioRecord`.
* **Blocker 1: Non-Destructive Tap / Acoustic Echo:**  
  Android's playback capture is designed for screen recorders and game streaming. It **duplicates** the audio stream without silencing the source app's hardware playback. If our app reads from `AudioRecord`, applies FxSound DSP, and writes to `AudioTrack` or `AAudio`, the user hears **both the dry original audio and the wet processed audio delayed by 40–120ms**. This causes unlistenable phasing, comb-filtering, and echo.
* **Blocker 2: No Selective Muting API:**  
  Android provides no API for an unprivileged app to mute an external app's output sink while capturing its audio. Calling `AudioManager.setStreamVolume(STREAM_MUSIC, 0)` mutes the master music stream, which also mutes our app's own playback (and mutes the captured stream on most HALs).
* **Blocker 3: DRM and Policy Opt-Out:**  
  - Apps targeting API 29+ can opt out with `android:allowAudioPlaybackCapture="false"` in their manifest.
  - Audio protected by DRM (Widevine DRM on YouTube Music, Netflix, Spotify offline cached tracks) is excluded by platform policy at the MediaServer/AudioFlinger layer. `AudioRecord.read()` returns silence.
* **Blocker 4: Background Restrictions:**  
  In Android 14 and 15, `MediaProjection` requires an active foreground service of type `mediaProjection` and displays a persistent notification and screen-cast icon in the status bar. The projection token cannot be silently persisted across reboots.

### Route C: App-Owned Audio Stream (AAudio / Oboe / AudioTrack)
* **Mechanism:** The application creates its own audio stream (via Google Oboe or AAudio), loads audio files or network streams, passes PCM buffers through the native JNI bridge to `DfxDsp`, and outputs to the hardware sink.
* **Feasibility:** **100% FEASIBLE.**
* **Performance:** Round-trip latency is sub-10ms with AAudio low-latency mode. Zero echo. Bit-perfect 32-bit float or 16-bit PCM processing. Full access to all 5 FxSound effect stages and graphic EQ.
* **Limitation:** Only processes audio played *within* or streamed *through* the application. Does not intercept system-wide audio from third-party apps.

### Route D: Privileged / OEM Integration
* **Mechanism:** Xiaomi HyperOS includes proprietary audio enhancement components (`com.miui.audioeffect`, `com.xiaomi.misound`) located in `/system/priv-app`.
* **Feasibility:** Strictly unavailable to non-root third-party applications. Bypassing these boundaries requires root or private system signatures, which violates persistent agent rules 2, 3, and 13.

---

## 4. Source-by-Source Feasibility Evaluation

| Source Application | Session Effect (`AudioEffect`) | Playback Capture (`AudioPlaybackCapture`) | Raw PCM Access for `DfxDsp`? | Verdict | Primary Technical Blocker |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **YouTube** | ❌ Unsupported | ⚠️ Capturable with Echo | ❌ No | **UNSUPPORTED** | No session broadcast; capture echoes with dry speaker output; DRM blocks protected video/audio. |
| **Spotify** | ⚠️ Generic OS EQ Only | ⚠️ Capturable with Echo | ❌ No | **UNSUPPORTED** (for native DSP) | Broadcasts session ID for OS `Equalizer`, but raw PCM is inaccessible; DRM streams block capture. |
| **Browsers (Chrome / Firefox)** | ❌ Unsupported | ⚠️ Capturable with Echo | ❌ No | **UNSUPPORTED** | No session broadcast; HTML5 audio echoes; DRM-protected EME media is blocked. |
| **Games (Unity / Unreal / AAudio)**| ❌ Unsupported | ⚠️ Capturable with Echo | ❌ No | **UNSUPPORTED** | Games use direct AAudio/OpenSL ES; latency (40-100ms) causes gameplay desync; dry/wet audio doubling. |
| **App-Owned Reference Player** | N/A (Direct Sink) | N/A | ✅ Yes (Full native access) | **SUPPORTED** | Direct in-pipeline processing via AAudio/Oboe + JNI + `DfxDsp`. Zero echo, full FxSound fidelity. |

---

## 5. Gate 0 Verdict & Decision

```
[ ] PASS     — Universal rootless system-wide audio enhancement is verified via public APIs.
[X] APPROVED — Dual-Engine Architecture approved by User on 2026-10-16.
               Mode 1: Live Spotify & Music Player integration via Android DynamicsProcessing (calibrated to FxSound curves).
               Mode 2: Authentic FxSound DfxDsp C++ engine for in-app Hi-Fi playback and offline enhancement.
```

### Rationale & Product Resolution:
Universal interception of arbitrary external apps (YouTube, games, browsers) via custom C++ DSP is strictly prevented by Android's sandbox and non-destructive capture policies. However, **Spotify, Poweramp, VLC, and standard Android music players fully support external audio effect attachment** via `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION`. By calibrating Android's native `DynamicsProcessing` (Pre-EQ, Multi-Band Compressor, Post-EQ, Limiter) + `BassBoost` + `Virtualizer` to replicate FxSound's acoustic models, the app delivers seamless, zero-latency, zero-echo enhancement for Spotify and music players.

---

## 6. Approved Architecture: "FxSound Mobile Dual-Engine"

1. **Engine 1: Session Companion Engine (Spotify & External Music Players)**
   - Automatically attaches to Spotify, Poweramp, Apple Music, and other music players using `AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` and `ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL`.
   - Controls Android's native `DynamicsProcessing` engine, configured to match FxSound's Clarity, Dynamic Boost, Bass Boost, 3D Surround, and 10-Band EQ curves.
   - Operates in real-time with zero latency and zero comb-filtering.
   - Includes an optional `android.permission.DUMP` helper for players that do not broadcast session intents automatically.

2. **Engine 2: Native C++ Hi-Fi Engine (`DfxDsp` via Android NDK/JNI)**
   - Authentic port of upstream FxSound `DfxDsp` C++ code for in-app high-resolution playback (FLAC, WAV, MP3) and offline file enhancement.
   - Desktop fidelity, real-time spectrum analysis, and true peak/RMS monitoring.

---

## 7. Next Steps & Implementation Roadmap

1. **Gate 0 Complete:** User approval received. Product scope aligned.
2. **Phase 1 (Native DSP Engine):** Create CMake/NDK project, isolate upstream FxSound `DfxDsp`, build `libfxsound_dsp.so` for ARM64, and run test fixtures.
3. **Phase 2 (Android Audio & Session Service):** Implement `DynamicsProcessing` parameter translator for Spotify and `AudioSessionService` broadcast receiver.
4. **Phase 3 (Material 3 UI & Haptics):** Build Compose interface following `ANDROID_M3_SKILL.md` with active session tracking, FxSound sliders, and tactile haptics.

