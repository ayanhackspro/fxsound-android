# FxSound for Android

FxSound for Android brings the audio processing from the open-source desktop application to Android phones and tablets. It provides multi-band equalization, dynamic range compression, bass contouring, and headphone auto-tuning for Spotify, YouTube Music, and other media players.

The application uses Android's native audio effect framework, which offloads processing directly to the device's audio DSP. It does not route audio through user-space CPU buffers during normal playback, keeping battery draw near zero.

---

## How Audio Routing Works on Android

Android does not provide a universal, rootless API for a third-party app to intercept audio from other applications. FxSound works within the platform's security model by using three complementary routing mechanisms:

1. **Audio session broadcasts:** Players like Spotify, Poweramp, and VLC send `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` intents when playback begins. FxSound attaches hardware `DynamicsProcessing` directly to that player's session ID.
2. **AudioFlinger tracking (Rootless ADB):** With a one-time ADB permission grant, FxSound reads active playback sessions directly from Android's audio server (`dumpsys media.audio_flinger`). This lets the app detect and enhance players that do not broadcast intents, such as YouTube or browsers.
3. **Global session 0 fallback:** For audio streams that do not match an explicit session, FxSound attaches a fallback chain to system session 0. The interface labels this route honestly as "System Audio (Partial)" because some vendor audio tracks bypass session 0 entirely.

---

## Features

### Studio Equalizer and Multi-Tier Dynamics
- **10-Band EQ:** Center frequencies tuned at 32 Hz, 64 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz, and 16 kHz, with +/-14 dB gain ranges.
- **Headroom calculation:** The preamp automatically cuts input gain based on peak EQ boost, clarity harmonics, and multiband makeup gain, preventing digital clipping before audio reaches the limiter.
- **Multi-tier OEM negotiation:** Android hardware abstraction layers (HALs) differ widely across manufacturers. FxSound tests and selects the best stage pipeline the device supports:
  - *Tier 1 (Full precision):* 10-band Pre-EQ, 4-band Multiband Compressor, 10-band Post-EQ, and peak Limiter. Works on Google Pixel and clean AOSP builds.
  - *Tier 2 (Streamlined):* Combined 10-band EQ, 4-band Multiband Compressor, and Limiter. Designed for Samsung One UI, Xiaomi HyperOS, and Motorola devices where dual-EQ stages exceed HAL buffer limits.
  - *Tier 3 (Essential):* Combined 10-band EQ and Limiter for restricted vendor implementations.
  - *Tier 4 (Platform fallback):* Standard Android Equalizer with gain matching if the hardware rejects DynamicsProcessing.
- **Renderer honesty:** The console displays the exact hardware engine running on the active session, including band counts and any HAL parameter rejections.

### Core Audio Controls
- **Clarity:** Adds high-frequency presence and overtone definition through post-EQ curve shaping.
- **Bass:** Low-end contouring using HyperBass harmonic curves in the Pre-EQ stage, falling back to Android BassBoost on legacy chains.
- **Dynamic Boost:** Four-band multiband compressor that lifts quiet details and smooths volume spikes.
- **Surround:** Soundstage widening using Android Virtualizer where hardware strength adjustment is supported.
- **Built-in and custom presets:** Ships with Music, Voice, Gaming, Bass Boost, and Neutral reference curves. Users can save, rename, and export custom presets.

### OPRA Headphone Correction
- Connects directly to the Open Parametric Resonance Archive (OPRA) dataset, with over 6,000 headphone measurements from Oratory1990, Crinacle, and others.
- Converts parametric peaking filters into hardware biquad stages in real time.
- Stores profiles locally in private cache with ETag re-validation.

### Battery and System Design
- **No wakelocks:** The app holds zero wake locks, allowing the phone to enter deep Doze mode when the screen is off.
- **Adaptive watchdog:** Polling intervals scale automatically: 2.5 seconds during active playback, 10 seconds after 15 seconds of silence, and 30 seconds after one minute of idle.
- **Zero background dumpsys:** AudioFlinger process inspection is disabled completely whenever audio is idle.
- **Quick Settings tile:** Includes a one-tap power toggle that only observes state while the notification shade is open.

---

## Setup: Enabling Rootless Session Tracking (Optional)

You can use FxSound immediately without extra setup. To enable automatic detection for all background players through AudioFlinger, grant the `DUMP` permission once via ADB:

```bash
adb shell pm grant com.fxsound.audio android.permission.DUMP
```

To verify the grant, open FxSound and tap the Settings gear. The tracking section will display a green checkmark confirming enhanced session tracking is active.

To revoke the permission at any time:

```bash
adb shell pm revoke com.fxsound.audio android.permission.DUMP
```

---

## Building from Source

### Prerequisites
- Android Studio Ladybug (2024.2.1) or newer
- Android SDK 35
- Android NDK `27.2.12479018`
- JDK 17
- CMake 3.22.1+

### Build Commands

Clone the repository:
```bash
git clone https://github.com/ayanhackspro/fxsound-android.git
cd fxsound-android
```

Run unit tests:
```bash
./gradlew testDebugUnitTest
```

Build the debug APK:
```bash
./gradlew assembleDebug
```
The debug APK will be located at `app/build/outputs/apk/debug/app-debug.apk`.

Build the optimized release APK:
```bash
./gradlew assembleRelease
```
The release APK will be located at `app/build/outputs/apk/release/app-release.apk`. It is stripped and minified via R8, with native binaries compiled for `arm64-v8a` and `x86_64` using `-O3` and `-ffast-math`.

---

## OEM Specific Tips

Pre-installed manufacturer audio sound suites can conflict with third-party audio effects by altering volume or phase:

- **Samsung:** Turn off Dolby Atmos and Adapt Sound in Settings > Sounds and vibration > Sound quality and effects.
- **Xiaomi / Redmi / POCO:** Disable Mi Sound Enhancer or Dolby Atmos under Sound & vibration > Additional settings.
- **OnePlus / OPPO / Realme:** Set Sound effects (Dirac / Dolby) to "None" or "Standard".
- **Motorola:** Turn off Moto Audio smart audio routing.

The in-app Settings screen includes direct links that open your phone's native sound settings page.

---

## Project Structure

```text
app/
├── src/main/
│   ├── cpp/                     # Ported C++ DfxDsp core and JNI bridge
│   │   ├── engine/              # Native FxSound audio engine
│   │   ├── fxsound_dsp/         # Upstream DSP algorithms (HyperBass, Valspar, Filter)
│   │   └── jni/                 # JNI bindings and bounds-checked buffer bridge
│   ├── java/com/fxsound/audio/
│   │   ├── data/                # Repository and debounced preferences storage
│   │   ├── engine/              # DynamicsProcessing translator, tables, and OEM compatibility
│   │   ├── model/               # Immutable state definitions and presets
│   │   ├── opra/                # OPRA headphone dataset downloader and parser
│   │   ├── receiver/            # Validated audio session broadcast receiver
│   │   ├── service/             # Session companion service and Quick Settings tile
│   │   └── ui/                  # Jetpack Compose UI, themes, and screen layouts
docs/                            # Architecture Decision Records (ADRs) and technical specifications
```

---

## Licensing and Attribution

- **FxSound Core:** Ported under the original open-source license terms of the FxSound project. Original code is copyright its respective authors.
- **OPRA Dataset:** Headphone measurement data is provided by the OPRA project under the Creative Commons Attribution-ShareAlike 4.0 International license (CC BY-SA 4.0). Curves are modified to fit parametric biquad approximations supported by Android renderers.
- **Application Code:** Licensed under the terms specified in the [LICENSE](LICENSE) file.
