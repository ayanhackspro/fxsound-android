# Test Plan

## 1. Build and static checks
- Gradle unit tests.
- Android lint.
- Kotlin formatting/static analysis if configured.
- CMake native build for arm64-v8a.
- Native unit tests.
- Dependency/license scan.
- Ensure debug and release build variants compile.

## 2. Native DSP tests
Fixtures:
- Silence.
- Impulse.
- Low-level sine.
- Frequency sweep.
- Multitone.
- Bass-heavy test signal.
- Near-full-scale input.
- DC offset.
- Abrupt parameter changes.
- Unsupported formats and invalid frame counts.

Assertions:
- Output samples finite.
- No unbounded output.
- Expected channel/frame count.
- Stable output after reset/reinitialize.
- Parameter changes do not introduce unexpected discontinuities.
- Compare Android port to desktop/reference output where deterministic.

## 3. Android route tests
On Xiaomi 17 record OS/HyperOS version and build:
- Initialize and tear down each adapter.
- Permission denial and revocation.
- Unsupported capture policy.
- No valid audio session.
- Bluetooth connect/disconnect.
- Wired headset connect/disconnect if hardware available.
- Speaker route.
- Backgrounding and screen off.
- Audio focus interruptions.
- App process recreation.
- Source app paused/resumed.
- Network loss should not affect local DSP.
- Verify unsupported routes never show “Processing”.

## 4. Required source-app matrix
Test YouTube, Spotify, a browser and a game separately. Record only routes permitted by public APIs. Do not circumvent DRM or capture restrictions. If one source cannot be processed, record it as unsupported rather than infer support from another source.

## 5. Long-run performance
For each supported route:
- 30-minute continuous playback.
- Repeat with bass/processing at conservative and high permitted settings.
- Record underruns, callback timing, CPU, memory, temperature observations and battery delta if reliable.
- Test incoming calls/notifications only if relevant to route behavior.
- Repeat after route changes.

## 6. Listening evaluation
- Use licensed test material or synthetic signals.
- Loudness-match processed and bypass output.
- Compare at low, moderate and high listening levels.
- Check bass-heavy passages, vocals, cymbals, transients and stereo placement.
- Use multiple output routes where available.
- Document subjective findings separately from objective metrics.

## 7. UI and haptic tests
- Dark theme, contrast, font scaling, landscape and cutouts.
- TalkBack labels and slider values.
- Toggle state matches actual route state.
- Haptics Off is respected.
- Slider haptics are throttled.
- No continuous haptic while dragging.
- Preset changes update DSP without clicks.
- App remains responsive while audio is processing.

## 8. Bug report template
- Device/OS/build:
- App commit/build:
- Source app and route:
- Steps:
- Expected:
- Actual:
- Logs/metrics:
- Reproducibility:
- Severity:
- Is system-wide support actually verified?:
