# Android Audio Feasibility Gate — Xiaomi 17, no root

## Why this is a blocking gate
An ordinary Android app is not given a universal public API to intercept and alter the output of all other apps. The product's system-wide goal may be impossible on stock firmware using only public APIs. This must be experimentally evaluated before full implementation.

## Routes to investigate
### A. AudioEffect / DynamicsProcessing
Effects attach to audio sessions and device support varies. A session effect requires a valid session identifier and permission/ownership conditions. Do not assume session 0 or a global output mix works on current Xiaomi HyperOS. Inspect effect descriptors and actual behavior.

### B. AudioPlaybackCapture
Requires user-approved MediaProjection flow and only captures eligible playback. Source app capture policy, audio usage, Android version and device behavior can prevent capture. Never attempt to bypass those restrictions. Capture is not automatically equivalent to transparent low-latency processing and rerouting.

### C. App-owned audio
Use Oboe/AAudio or standard media APIs to validate the DSP in an app-owned stream. This is a valid reference/test path, not a system-wide solution.

### D. OEM/private integration
Do not rely on undocumented Xiaomi APIs or hidden permissions in the core product. An optional vendor-specific adapter may be evaluated separately only after technical, licensing and distribution review. Root is out of scope.

## Required experiment matrix
Record date, exact device model, HyperOS/Android version, build number, app version, audio route, sample rate if observed, and logs.

| Source | Session effect | Playback capture | Actual processed output | Notes |
|---|---|---|---|---|
| YouTube app | Test | Test within policy | Yes/No | Record source capture policy behavior |
| Spotify | Test | Test within policy | Yes/No | Do not bypass DRM/capture restrictions |
| Chrome or chosen browser | Test | Test within policy | Yes/No | Use ordinary web audio |
| One game | Test | Test within policy | Yes/No | Avoid disrupting gameplay |
| App-owned test player | Test | Not required | Yes/No | Baseline DSP validation |
| Wired headphones | Repeat supported routes | Repeat supported routes | Yes/No | Route-specific behavior |
| Bluetooth headphones | Repeat supported routes | Repeat supported routes | Yes/No | Latency/codec caveats |

## Procedure
1. Capture device/build details from Android settings and `adb shell getprop` where available.
2. Enumerate audio effects using public APIs and record descriptors.
3. Build the smallest possible test APK for each route.
4. Use known test tones and a recording/loopback method that does not violate source-app policies.
5. Verify the processed output objectively (spectrum/gain change) and by listening at loudness-matched levels.
6. Check latency, underruns, CPU, battery and lifecycle behavior.
7. Repeat after app backgrounding, screen-off, headset change and process recreation.
8. Document per-source evidence. Do not generalize from one app to all apps.

## Gate decision
- **PASS:** every required source class is demonstrably processed on the target device through supported, distributable APIs, without root and without bypassing restrictions.
- **PARTIAL:** some sources/routes work; product must show a support matrix and cannot claim universal system-wide processing.
- **FAIL:** no acceptable route meets the core goal. Stop and ask the user whether to proceed with an in-app reference player/session-only alternative. Do not silently downgrade the requirement.

## Required output
Create `docs/FEASIBILITY_REPORT.md` containing:
- Device and OS details.
- Experiment build and commands.
- Source-by-source outcomes.
- Audio-effect descriptors and relevant errors.
- Observed latency/dropouts.
- Screenshots/log references where safe.
- Gate decision and recommended next step.
- Explicit list of untested scenarios.
