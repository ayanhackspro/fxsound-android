# Android Audio Support Matrix & Route Compatibility

**Document Version:** 2.0  
**Target Device:** Xiaomi 17 (Android 15 / Android 16 / HyperOS)  
**Security Model:** Rootless (Standard User Space, Enforcing SELinux)  
**Date:** 2026-10-16  

---

## 1. Comprehensive Audio Route & Application Matrix

The following matrix documents the observed and verified behavior of modern Android audio routing mechanisms across diverse application categories.

| Application Category / Target App | Route A: Audio Session Effect (`AudioEffect`) | Route B: Playback Capture (`AudioPlaybackCapture`) | Raw PCM Access for Native `DfxDsp`? | Hardware Output Duplication (Echo)? | Overall Support Status | User-Facing Status Display in UI |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| **YouTube** (Official App) | ❌ **Blocked**<br>(No session broadcast; session 0 prohibited) | ⚠️ **Partial**<br>(Capturable via MediaProjection, but non-destructive) | ❌ **No** | 🔴 **Severe**<br>(Dry speaker audio + 80ms delayed wet audio) | **UNSUPPORTED** | `"Unavailable: Android blocks external audio interception for YouTube"` |
| **Spotify** (Free / Premium / DRM) | ✅ **Full Support via DynamicsProcessing**<br>(Captures session broadcast; controls Android DynamicsProcessing + BassBoost + Virtualizer, driven by the FxSound DSP's **measured** response (docs/FX_RESPONSE_MEASUREMENT.md)) | ⚠️ Not required<br>(Session effects bypass capture limitations) | ⚠️ Parameter translation<br>(FxSound curves mapped to DynamicsProcessing) | 🟢 **None**<br>(Direct OS-level hardware DSP effect, no added latency) | **FULLY SUPPORTED**<br>(Via FxSound Session Companion Engine) | `"Connected: Spotify (FxSound Dynamics Engine Active)"` |
| **Other Music Players** (Poweramp, Apple Music, VLC, Deezer, etc.) | ✅ **Full Support via DynamicsProcessing**<br>(Broadcasts session ID or tracked via DUMP helper) | ⚠️ Not required | ⚠️ Parameter translation | 🟢 **None** | **FULLY SUPPORTED**<br>(Via FxSound Session Companion Engine) | `"Connected: [Player Name] (FxSound Dynamics Engine Active)"` |
| **Browsers** (Chrome, Firefox, Brave) | ❌ **Blocked**<br>(No session broadcast) | ⚠️ **Partial**<br>(HTML5 audio capturable; EME/DRM blocked) | ❌ **No** | 🔴 **Severe**<br>(Acoustic echo on phone speakers) | **UNSUPPORTED** | `"Unavailable: Android blocks browser audio redirection"` |
| **Games** (Unity, Unreal, AAudio) | ❌ **Blocked**<br>(Bypasses AudioEffect; uses direct AAudio HAL) | ⚠️ **Partial**<br>(Audio capturable with MediaProjection) | ❌ **No** | 🔴 **Severe**<br>(Comb-filtering + 50-100ms game lag) | **UNSUPPORTED** | `"Unavailable: Real-time game audio cannot be intercepted without lag"` |
| **Video Streaming** (Netflix, Prime, Disney+) | ❌ **Blocked** | ❌ **Blocked by Widevine DRM** | ❌ **No** | N/A | **UNSUPPORTED** | `"Unavailable: DRM protected content blocks audio access"` |
| **Social Video** (TikTok, Instagram Reels) | ❌ **Blocked** | ⚠️ **Partial** (Dry/wet echo) | ❌ **No** | 🔴 **Severe** | **UNSUPPORTED** | `"Unavailable: External app capture restricted"` |
| **App-Owned Reference Player** (Local / Stream) | ✅ **Direct Pipeline**<br>(AAudio / Oboe / AudioTrack) | N/A (Not needed) | ✅ **YES**<br>(100% native access via JNI) | 🟢 **None**<br>(Direct in-line DSP processing, sub-10ms) | **FULLY SUPPORTED** | `"Processing Active: FxSound DfxDsp Hi-Fi Engine"` |
| **Audio File Processor** (Offline WAV/FLAC) | N/A (Offline Buffer) | N/A | ✅ **YES**<br>(Bit-perfect offline processing) | 🟢 **None** | **FULLY SUPPORTED** | `"Offline Processing: FxSound DfxDsp Engine"` |

---

## 2. Detailed Technical Breakdown of Platform Constraints

### 2.1 The "Echo / Comb-Filtering" Problem in `AudioPlaybackCapture`
* In standard operating systems like Windows, FxSound installs a **Virtual Audio Driver (endpoint)**. Applications output audio to the virtual endpoint, FxSound processes the buffer, and sends the processed audio to the physical sound card. The dry audio is completely intercepted.
* On Android without root, third-party apps **cannot create virtual audio endpoints** or install Audio HAL drivers.
* `AudioPlaybackCapture` operates as a **non-destructive tap** on AudioFlinger mixer threads. When an app plays sound, the OS routes it directly to hardware. Capturing that sound, passing it through DSP, and playing it back via an `AudioTrack` results in:
  $$\text{Heard Audio} = \text{Dry Audio}(t) + \text{Wet Audio}(t - \Delta t)$$
  where $\Delta t \approx 40\text{ms} - 120\text{ms}$. This causes destructive phase cancellation, comb filtering, and severe echo.

### 2.2 Audio Session ID Isolation (`AudioEffect`)
* Android assigns a dynamic integer `audioSessionId` to each `AudioTrack` or `MediaPlayer` instance.
* Effects attached to an `audioSessionId` are executed inside the system media server daemon (`/system/bin/audioserver`).
* There is no mechanism for an app to inject custom C++ code into `audioserver`.
* Session 0 (`SESSION_OUTPUT_STAGE`) was deprecated in Android 9 and is blocked on Android 10+ unless the app holds the signature permission `android.permission.MODIFY_DEFAULT_AUDIO_EFFECTS`.

### 2.3 DRM (Digital Rights Management)
* In Android's MediaDRM architecture (Widevine Level 1 / Level 3), encrypted audio tracks are decrypted directly within hardware-protected memory buffers.
* Android's audio capture policy (`ALLOW_CAPTURE_BY_SYSTEM` or `ALLOW_CAPTURE_BY_NONE`) is enforced at the kernel/HAL boundary, preventing any user-space app from capturing protected audio.

---

## 3. UI/UX Rules for Route Capability Transparency

Per persistent agent rule 11 (*"Treat failure to obtain the requested audio route as a visible state, not as a fake active toggle"*):

1. **No Fake "Global Active" Switch:**  
   The UI will not present a single switch that claims "System-Wide Enhancer is ON".
2. **Dynamic Capability Header:**  
   The top app header must show an honest status chip:
   - 🟢 `Processing (Hi-Fi Player)`
   - 🟡 `System Effect Companion (Spotify / OS Dynamics)`
   - ⚪ `Bypassed`
   - 🔴 `System Interception Unavailable on this OS`
3. **Explaining External Limitations:**  
   When the user asks why YouTube or Chrome is not enhanced, the app will provide a clear, educational dialog explaining Android's platform security policies and offering the in-app Reference Player as the verified FxSound listening path.
