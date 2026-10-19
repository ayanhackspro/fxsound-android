# Device Verification Script

The on-device procedure for the audio work, consolidated from `docs/AUDIO_BUG_INVESTIGATION.md`
section 7, `docs/TEST_PLAN.md` sections 3, 5 and 6, and `docs/DEFINITION_OF_DONE.md`.

**Nothing in this document has been performed on the Xiaomi 17 yet.** The target device was not
connected while the code changes in `docs/IMPLEMENTATION_LOG.md` were made. Record the actual
results here when it is.

## Prerequisites

- Xiaomi 17 on the target Android / HyperOS build. Record the build fingerprint:
  `adb shell getprop ro.build.fingerprint`.
- Wired headphones, a USB-C DAC, and Bluetooth headphones, for the route matrix.
- `adb` on the host, device authorised.
- For the native suite: `./gradlew nativeTest` (builds and runs the native DSP tests on the
  device; it fails loudly if no device is connected).

## 1. Native DSP suite

```bash
./gradlew nativeTest --no-daemon
```

Expected: the suite runs on the device and **all eleven tests pass**. The three that used to
fail have been fixed:

| Test | Was | Now |
| :--- | :--- | :--- |
| `testSilence` | FAIL - digital silence was amplified to full scale | PASS - the residual is below 0.0001 |
| `testFloatPathRetainsResolution` | FAIL - 96.6% of samples sat on the 16-bit grid | PASS - 1.96% |
| `testEffectValuesReachTheKernel` | FAIL - the output was identical with the effects at 0 and at 10 | PASS - the effects change the output measurably |

**Any failure is a regression and must be investigated before the build is installed on the
device.** See `docs/DSP_PORT_AUDIT.md` section 9 for what these three were failing on.

## 2. Bass pumping on headphones (the original defect)

1. Connect wired or USB-C headphones.
2. Play a track with heavy, repetitive sub-bass transients (EDM, hip-hop, drum and bass),
   normalised so the kick sits at about -6 dBFS.
3. In FxSound, turn Master Power **ON**.
4. Set **Bass Boost** to 10.0, **Dynamic Boost** to 8.0, **Clarity** to 5.0.
5. Listen for 60 seconds.

**Expected after the fix:** the kick has deep, solid low-end impact. No part of the mix ducks
or "breathes" in time with it. The vocal and the hi-hats hold their level.

**Before the fix:** the whole mix visibly dips by a few dB on every kick and springs back
about 30 ms later.

**Also check:** with Bass Boost at 0 and the EQ flat, the output level should not change as the
music plays. Any pumping that remains at Bass 0 is a separate defect in the limiter or the
compressor, not in the bass staging.

## 3. Live slider response (Spotify)

1. Launch Spotify, enable **Settings -> Device Broadcast Status**, start playback.
2. Confirm the status badge reads **"Connected: Spotify"** and not "System Mix Only".
3. Move **Clarity** from 0 to 10. Move **Bass Boost** from 0 to 10. Move the 1 kHz EQ fader
   from -14 dB to +14 dB.
4. Toggle Master Power OFF and ON.

**Expected:** every change is audible immediately, with no click and no drop-out.

**Also check:** open YouTube or a browser and play something. The status badge must change to
**"System Mix Only"** and the banner must explain that the source cannot be intercepted. Moving
a slider there must **not** be presented as working.

## 4. Crossfeed toggle

1. Settings -> Native C++ DSP Test Harness -> Play Reference Multitone.
2. Toggle **Binaural Headphone Crossfeed** on and off five times, quickly.

**Expected:** no click, no pop, no discontinuity in the tone on either transition. The switch
must state that it applies to the reference player only.

## 5. 30-minute long run

Per `docs/TEST_PLAN.md` section 5, for each supported route:

- 30 minutes continuous playback with conservative settings (Bass 3, Clarity 3, EQ flat).
- 30 minutes continuous playback with high settings (Bass 10, Dynamic Boost 8, Clarity 8).
- Record: under-runs, callback timing, CPU, memory, temperature, battery delta.

The screen may be off for part of the run. The app must not stop enhancing when backgrounded.

## 6. Loudness-matched listening comparison

Per `docs/TEST_PLAN.md` section 6, the processed and bypassed outputs must be compared at
**matched perceived loudness** - a louder processed signal is not better.

1. Play the same passage with Master Power OFF, then ON.
2. Adjust the device volume so the two are equally loud.
3. Compare bass depth, vocal clarity, cymbals, transients and stereo placement.
4. Record the subjective findings **separately** from the objective measurements.

## 7. Route matrix

Record the actual result for each. Do not infer one source's behaviour from another's.

| Source | Expected route | Result |
| :--- | :--- | :--- |
| Spotify (Device Broadcast Status ON) | Session effect | |
| Spotify (Device Broadcast Status OFF) | Session effect, via the equalizer control panel | |
| Poweramp / local music player | Session effect | |
| YouTube | **Unsupported** - must show "System Mix Only" | |
| Chrome / any browser | **Unsupported** - must show "System Mix Only" | |
| A game | **Unsupported** - must show "System Mix Only" | |
| DRM video (Netflix, Prime) | **Unsupported** - never processed | |
| In-app reference player | Native C++ DfxDsp | |

For any source the app cannot intercept, the UI must not display a processing state. See
ADR-022.

## 8. Accessibility and haptics

Per `docs/TEST_PLAN.md` section 7:

- Dark theme contrast, font scaling to the largest size, landscape, and display cutouts.
- TalkBack labels on the sliders, the master button and the route badge.
- Haptics off in the system settings are respected; the app's own "Off" haptic mode is
  respected.
- Slider haptics are rate-limited and there is no continuous haptic during a drag.
- Preset changes update the DSP without a click.

## Recording the results

Add the results to `docs/IMPLEMENTATION_LOG.md` and tick the corresponding boxes in
`docs/DEFINITION_OF_DONE.md`. Do not write a result that was not observed.
