# FxSound DSP Port Plan

## Upstream source
Repository: https://github.com/fxsound2/fxsound-app
Primary area: `dsp/`, including `DfxDsp.cpp`, `DfxDsp.h`, `u_DfxDsp.*` and referenced DSP implementation/dependencies.

Upstream repository describes three broad parts: GUI, audio passthrough and DfxDsp processing. The Android port should investigate the DSP layer and replace the Windows audio integration, not port the Windows virtual audio driver.

## Phase A — source audit
- Clone upstream repository.
- Record exact commit hash and date.
- Inspect top-level and DSP-specific build files.
- Map public API and lifecycle.
- Identify all source files needed to build DfxDsp.
- Identify dependencies, compiler assumptions, SIMD code, platform-specific headers and license notices.
- Determine how presets/parameters are represented and how processing state is initialized.
- Determine supported sample rates, channel layouts, sample formats and frame counts.
- Identify memory allocation, locks, global state and unsafe parameter updates.
- Produce a dependency graph and source manifest.

## Phase B — build a desktop/offline reference
- Compile the DSP in its supported upstream environment if available.
- Create deterministic PCM fixtures.
- Record input/output pairs for known configurations.
- Keep tests loudness-aware and avoid clipping the reference recording path.
- Store fixture metadata; do not commit copyrighted music or user-private audio.

## Phase C — portable wrapper
- Keep upstream files in a clearly marked directory or a tracked upstream subtree.
- Add a thin wrapper rather than rewriting upstream classes.
- Establish an explicit ownership model for DSP state.
- Handle mono/stereo and sample-rate assumptions explicitly.
- Convert formats only at boundaries.
- Smooth parameter changes outside or through a documented safe mechanism.
- Avoid calling UI or managed-runtime APIs from the DSP processing thread.

## Phase D — Android build
- Build arm64-v8a with Android NDK and CMake.
- Resolve only dependencies that can legally be included and built on Android.
- Add ABI checks and unit tests.
- Verify JNI lifetime and error handling.
- Keep upstream and local changes easy to diff.

## Phase E — parity validation
For each fixture and configuration:
- Compare sample count and channel count.
- Compare peak/RMS levels.
- Calculate maximum absolute error and normalized RMS error where deterministic output is expected.
- Inspect spectrum and impulse response.
- Verify no NaN/Inf, runaway gain, unstable filter state or unexpected DC offset.
- Explain any expected differences due to compiler, floating-point behavior or format conversion.

## Fallback rule
If upstream DfxDsp cannot reasonably compile on Android, document the exact blockers and estimate a minimal compatibility patch. Do not silently replace the engine with a generic EQ. Ask for approval before abandoning the real engine.

## Attribution
Keep upstream copyright/license headers. Record all local modifications and third-party dependencies. See `LICENSING_AND_ATTRIBUTION.md`.
