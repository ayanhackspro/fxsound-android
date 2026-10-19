# Architecture

## Design principle
Separate DSP from Android audio routing. The DSP must be testable offline without Android and must not assume that it owns system audio.

## Layers
1. Kotlin + Jetpack Compose + Material 3 UI
   - Use MaterialTheme and Material 3 components for state rendering and interaction.
   - Consult the M3 Skill and Android Official Skill when available; see `ANDROID_M3_SKILL.md`.
   - No DSP work on the main thread.
2. Domain/controller layer
   - Presets, settings, route status, enable/bypass state.
   - State machine and capability reporting.
3. Audio integration adapters
   - Session-effect adapter where an audio session is legitimately available.
   - Playback-capture experiment for eligible playback only.
   - App-owned reference player/stream for DSP tests.
   - Do not claim global output access unless validated.
4. JNI boundary
   - Minimal stable C API around native DSP.
   - No Kotlin object access from the real-time callback.
5. Native DSP
   - Ported upstream DfxDsp and wrapper.
   - Format conversion, parameter smoothing and test instrumentation outside upstream code where possible.
6. Test tools
   - Offline PCM fixtures and objective signal tests.
   - Device test matrix and long-run playback checks.

## Suggested package layout
`com.example.audioenhancer`
- `ui/`
- `domain/`
- `settings/`
- `audio/api/`
- `audio/session/`
- `audio/capture/`
- `audio/reference/`
- `audio/nativebridge/`
- `haptics/`
- `diagnostics/`

## State machine
`UNINITIALIZED -> INITIALIZING -> READY`
From READY:
- `PROCESSING` only when an actual supported route is active.
- `BYPASSED` when user disables enhancement.
- `UNAVAILABLE` when no compatible route exists.
- `ERROR_RECOVERABLE` on recoverable stream/effect failure.
- `ERROR_FATAL` only for unrecoverable initialization errors.

The UI must not use a single toggle to imply system-wide processing. The toggle is a request; the route state confirms whether processing is active.

## Native API proposal
Expose a C ABI so JNI remains thin:
- `dsp_create(config, sample_rate, channels, format)`
- `dsp_process(handle, input, output, frame_count)`
- `dsp_set_parameters(handle, parameter_snapshot)`
- `dsp_reset(handle)`
- `dsp_get_metrics(handle, metrics_out)`
- `dsp_destroy(handle)`

This is a proposed wrapper contract, not an assertion that upstream already provides these functions. Adapt after auditing upstream API and thread-safety.

## Threading
- UI/main thread: user input and status display.
- Control thread: validate and publish immutable parameter snapshots.
- Audio callback: read a stable snapshot, process preallocated buffers, update lock-free or low-cost metrics.
- Do not take UI locks, allocate memory, write logs, access disk, or call JNI into managed code from the real-time callback.
- Destruction must wait until processing has stopped safely.

## Build direction
- Kotlin + Jetpack Compose.
- Gradle.
- Android NDK + CMake.
- C++ for the DSP port.
- Oboe/AAudio only for app-owned streams and test/reference pipeline.
- ARM64 first; no hardcoded sample rate or buffer size.
