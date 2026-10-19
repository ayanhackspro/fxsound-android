# FxSound DSP Port Audit & Technical Specification

**Document Version:** 1.0  
**Phase:** Gate 1 (DSP Architecture & Portability Audit)  
**Status:** COMPLETE / READY FOR BUILD  
**Date:** 2026-10-16  
**Auditor:** Antigravity Lead DSP Porting Engineer  

---

## 1. Upstream Provenance & Licensing

* **Repository:** [https://github.com/fxsound2/fxsound-app](https://github.com/fxsound2/fxsound-app)
* **Audited Commit:** `d8e7a23d37ed5939c2a3090a1c1756c7f2500b17`
* **Commit Date:** Thu Sep 17 10:08:41 2026 +0530
* **Commit Subject:** `Merge pull request #619 from fxsound2/bvijay74-patch-1`
* **License:** **GNU Affero General Public License v3.0 (AGPL-3.0)**
* **License Headers:** Verified across all source files in `dsp/`:
  ```text
  FxSound
  Copyright (C) 2025 FxSound LLC
  This program is free software: you can redistribute it and/or modify
  it under the terms of the GNU Affero General Public License as published by
  the Free Software Foundation, either version 3 of the License...
  ```
* **Compliance Obligations:**
  - Derivative works must preserve original copyright notices and license headers.
  - Any distributed binary containing the compiled DSP must provide Corresponding Source code under AGPL-3.0.
  - Branding, logos, and trademarks of FxSound LLC must not be copied; product branding must remain original.

---

## 2. DSP Directory & Source Architecture

The upstream DSP implementation is self-contained in the `dsp/` folder and is built as a static library via `DfxDsp.vcxproj`. It contains no JUCE or external DSP dependencies, relying entirely on standard C/C++ math.

### Directory Layout:
```text
dsp/
├── include/
│   └── DfxDsp.h              # Public C++ interface (DfxDsp class, Effect enum)
├── u_DfxDsp.h                # Private implementation header (DfxDspPrivate)
├── DfxDsp.cpp                # Pimpl bridge wrapping DfxDspPrivate
├── DfxDspPrivate.cpp         # DSP lifecycle, parameter faders, format setup
├── DfxDspEq.cpp              # Equalizer control and band mapping
├── DfxDspPreset.cpp          # Preset file loading/saving
├── DfxDspRegistry.cpp        # Windows registry persistence (to be replaced)
├── ptComSftDfx/
│   ├── Comsftwr.c            # Software DSP function table and dispatcher
│   └── ComsftwrCPP.cpp       # C++ dispatch wrappers
├── ptechDsp/                 # Core DSP filter kernels
│   ├── Aural/                # Fidelity (Aural Exciter / harmonic generator)
│   ├── Lex/                  # Ambience (Lexicon-style diffusion reverb)
│   ├── Maximizer/            # Dynamic Boost (Peak limiter / dynamic compression)
│   ├── Play/                 # Play32.c — Master cascade & HyperBass boost
│   └── wide/                 # 3D Surround (Stereo widening)
└── ptutil/                   # Utilities, filters, and parameter quantization
    ├── COM/                  # Wave buffer processor (comProcessWaveBuffer)
    ├── dfxp/                 # Universal buffer modification and channel routing
    ├── DspUtil/
    │   ├── BinauralSync/     # Headphone 3D spatializer
    │   ├── GraphicEq/        # Multi-band graphic equalizer (biquad SOS)
    │   ├── spectrum/         # FFT spectrum analyzer for visualizer
    │   └── SurroundSyn/      # Surround synthesizer
    ├── Filt/                 # Biquad SOS calculation and Butterworth filter routines
    ├── Qnt/                  # Parameter quantization curves (MIDI to float/dB)
    ├── SOS/                  # Second Order Section filter cascade
    └── VALS/                 # Parameter state serialization
```

---

## 3. Signal Chain & Algorithmic Cascade

When built with `PT_DSP_BUILD=PT_DSP_DFX` (the official FxSound build mode), the active audio pipeline in `dfxpUniversalModifySamples()` and `Play32.c` processes samples in the following strict order:

```
[Input PCM (16-bit int or 32-bit float)]
                  │
                  ▼
   Format & Buffer Validation (`DAW_MAX_BUFFER_SIZE`)
                  │
                  ▼
   Bypass Check (`DFX_UI_BUTTON_BYPASS`)
   ├── [BYPASS = ON]  ──► GraphicEqProcess_MasterGainOnly() ──► [Output]
   └── [BYPASS = OFF] ──┐
                        ▼
   1. Graphic Equalizer (`GraphicEqProcess`)
      - Multi-band parametric/peaking biquad filters (SOS)
      - Independent frequency, Q, and boost/cut per band (-12dB to +12dB)
                        │
                        ▼
   2. Binaural Headphone Spatializer (`BinauralSynProcess`)
      - Optional HRTF / 3D depth filter for headphones
                        │
                        ▼
   3. Fidelity Stage (`dspsAuralProcess32`)
      - High-frequency harmonic exciter / spectral restorer
      - Restores high-frequency harmonics lost in lossy compression
                        │
                        ▼
   4. Ambience Stage (`dspsLexReverbProcess32`)
      - Lexicon-style diffusion reverb network
      - Generates natural acoustic room reflections
                        │
                        ▼
   5. 3D Surround Stage (`dspsWideProcess32`)
      - Mid-Side (M/S) stereo cross-feed and phase decorrelation
      - Expands perceived stereo soundstage
                        │
                        ▼
   6. HyperBass Boost Stage (`dspsPlayProcess32` internal)
      - Transformed Direct Form II parametric low-frequency boost
      - Sub-harmonic synthesis with bounded saturation
                        │
                        ▼
   7. Dynamic Boost / Maximizer Stage (`dspsMaximizerProcess32`)
      - Peak-aware dynamic compressor and soft limiter
      - Maximizes perceived loudness while preventing inter-sample overflow
                        │
                        ▼
   8. Spectrum Analysis (`spectrumProcess`)
      - Real-time FFT computation for UI visualization
                        │
                        ▼
[Output PCM (Interleaved 32-bit float or 16-bit int)]
```

---

## 4. Audio Formats, Buffers, and Numerical Limits

* **Internal Processing Format:** 32-bit IEEE 754 Floating Point (`realtype` is `#define realtype float`).
* **Supported Input/Output Formats:**
  - 16-bit signed integer PCM (`short int*`)
  - 24-bit signed integer PCM (packed or in 32-bit words)
  - 32-bit float PCM (`float*`)
* **Channel Layouts:**
  - Mono (1 channel)
  - Stereo (2 channels)
  - Quad (4 channels)
  - 5.1 Surround (6 channels)
  - 7.1 Surround (8 channels)
* **Sample Rates:**
  - 44,100 Hz, 48,000 Hz (native)
  - 88.2 kHz, 96 kHz, 176.4 kHz, 192 kHz (supported via built-in decimation / oversampling in `Comwave.cpp`)
* **Maximum Buffer Size:** `DAW_MAX_BUFFER_SIZE` (buffers larger than this are transparently processed in chunks).
* **Numerical Safety Checks:**
  - Denormal numbers: Upstream code adds low-level DC bias offsets (`1.0e-30`) in filter states to avoid CPU denormal stalls.
  - Legal value bounding: `realSampleForceLegalValues_ArrayOnly()` clamps out-of-range floats before output conversion.

---

## 5. Real-Time Safety & Threading Audit

Per Rule 7 of `AGENTS.md` (*"No blocking operations, heap allocation, mutex acquisition, file I/O, or verbose logging in real-time audio callbacks"*):

| Check Item | Upstream Status | Android Port Action |
| :--- | :--- | :--- |
| **Heap Allocation** | Preallocated during initialization (`comSftwrAllocDspMem`). No `malloc`/`new` in `processAudio()`. | **COMPLIANT:** Ensure all scratch buffers remain preallocated on initialization. |
| **Mutex / Locks** | No locks in `DSPS_PLAY_PROCESS` or `comProcessWaveBuffer`. | **COMPLIANT:** Maintain lock-free snapshot exchange for UI parameter updates. |
| **File / Disk I/O** | `DfxDspPrivate::processTimer()` calls registry checks on Windows. | **ACTION REQUIRED:** Strip Windows registry calls. Parameter updates must be pushed via atomic state snapshot from Kotlin. |
| **Managed Runtime Callbacks** | Pure native C. | **COMPLIANT:** No JNI calls will be made from inside the native audio callback. |
| **Denormal Stalls** | Handled via small offset constants. | **COMPLIANT:** Enable `-ffast-math` or flush-to-zero (FTZ/DAZ) on ARM64. |

---

## 6. Windows Dependencies & Porting Strategy

The upstream code was designed for Visual C++ on Windows. The following dependencies must be isolated and adapted for Android NDK:

### 1. `<windows.h>` and Win32 Types
* **Finding:** Files include `<windows.h>` for basic types: `BYTE`, `WORD`, `DWORD`, `BOOL`, `LARGE_INTEGER`, `QueryPerformanceCounter`.
* **Historical Precedent:** `dsp/ptutil/include/codedefs.h` and `pt_defs.h` already have `#ifndef __ANDROID__` guards added by original authors in 2013!
* **Port Strategy:** Create `pt_windows_compat.h` for Android:
  ```c
  #pragma once
  #include <stdint.h>
  #include <stdbool.h>
  #include <time.h>
  
  typedef uint8_t  BYTE;
  typedef uint16_t WORD;
  typedef uint32_t DWORD;
  typedef int32_t  BOOL;
  typedef int32_t  LONG;
  #define TRUE  1
  #define FALSE 0
  #define MB_OK 0
  ```

### 2. Registry Persistence (`DfxDspRegistry.cpp`)
* **Finding:** Windows registry is used to persist settings between sessions.
* **Port Strategy:** Disable `update_from_registry_` (`update_from_registry_ = false`). All settings are managed by Kotlin Jetpack DataStore and passed via JNI.

### 3. Wide Characters (`wchar_t*` / `std::wstring`)
* **Finding:** Windows uses 16-bit `wchar_t`; Android/Linux uses 32-bit `wchar_t`.
* **Port Strategy:** The audio processing loop uses numbers, not strings. Preset names and paths will be converted to standard UTF-8 `std::string` at the JNI boundary.

---

## 7. Android Native Build Plan (CMake & NDK)

### CMake Target Configuration:
* **Target:** `libfxsound_dsp.so`
* **Target ABI:** `arm64-v8a` (primary target for Xiaomi 17), with `armeabi-v7a` and `x86_64` supported.
* **Compiler Flags:**
  ```cmake
  target_compile_definitions(fxsound_dsp PRIVATE
      __ANDROID__
      PT_NON_MFC
      DSPSOFT_TARGET
      PT_DSP_BUILD=PT_DSP_DFX
      _LIB
  )
  target_compile_options(fxsound_dsp PRIVATE
      -O3
      -ffast-math
      -fvisibility=hidden
  )
  ```

### C Wrapper API (Exported via JNI):
```c
// Stable C interface for JNI bridge
typedef void* FxSoundHandle;

FxSoundHandle fxsound_create(int sample_rate, int channels, int bit_depth);
void fxsound_set_power(FxSoundHandle handle, bool enabled);
void fxsound_set_effect(FxSoundHandle handle, int effect_id, float value); // 0..10
void fxsound_set_eq_band(FxSoundHandle handle, int band_index, float gain_db);
void fxsound_set_master_gain(FxSoundHandle handle, float gain_db);
int  fxsound_process_float(FxSoundHandle handle, const float* in, float* out, int frames);
int  fxsound_process_pcm16(FxSoundHandle handle, const int16_t* in, int16_t* out, int frames);
void fxsound_get_spectrum(FxSoundHandle handle, float* out_bands, int num_bands);
void fxsound_destroy(FxSoundHandle handle);
```

---

## 8. Audit Conclusion

The FxSound `DfxDsp` engine is **fully portable to Android ARM64**. It contains no unresolvable proprietary libraries, no reliance on Windows audio drivers, and its mathematical core is self-contained C/C++. The port will preserve the exact DSP algorithms without substituting a generic equalizer.

---

## 9. Verified Runtime Findings (2026-10-19)

**This section supersedes the assumptions in sections 6 and 8 above.** Those sections were a
static source audit. Section 9 records what the ported DSP actually does when it is executed,
measured with the native numerical test suite (`app/src/main/cpp/tests/test_dsp_numerical.cpp`,
run on an x86_64 Android emulator via `./gradlew nativeTest`).

**All three findings below were reproduced, root-caused and fixed on 2026-10-19.** The native
suite now passes 11 of 11. The findings are kept here because each one contradicts something
the static audit asserted, and because the fixes are only meaningful in the light of them.
See ADR-023 in `docs/DECISIONS.md`.

### 9.0 Summary of the three defects

| # | Defect | Effect | Status |
| :--- | :--- | :--- | :--- |
| 9.1 | The registry stubs failed, so `dfxpInit()` aborted before assigning the core handle | The DSP core was never initialised; `FxSoundEngine::init()` segfaulted; every process call was a no-op | **Fixed** |
| 9.2 | The effect knob values were written to the registry and read back as *defaults* | The application's effect sliders never reached the DSP kernels | **Fixed** |
| 9.3 | The parameter overlay structs used `long`, which is 64 bits on Android | Every parameter field after the first was read from the wrong word; the master gain was never applied and silence was amplified to full scale | **Fixed** |

### 9.1 The DSP core was never initialised on Android

`dfxpInit()` reads the installation and last-used dates from the Windows registry before it
assigns the core handle:

```
dfxpInit.cpp:92   if (dfxp_InitFirstTimeRunFlag(...) != OKAY) return(NOT_OKAY);
dfxpInit.cpp:216  *hpp_dfxp = (PT_HANDLE *)cast_handle;   <-- only reached on success
```

`dfxp_InitFirstTimeRunFlag()` calls `dfxpGetInstallationDate()` and `dfxp_RecordLastUsedDate()`,
which call `regReadKey_Wide()` and `regCreateKey_Wide()`. The Android stubs in
`ptutil/android_dsp_compat.cpp` returned `NOT_OKAY`, so `dfxpInit()` bailed out at line 93 and
`*hpp_dfxp` was never assigned. The result:

- `DfxDspPrivate::dfxp_handle_` stayed `NULL` for the lifetime of the engine.
- `FxSoundEngine::init()` **segfaulted**, in `GraphicEqSetMasterGain` (via
  `DfxDspPrivate::setMasterGain`): `dfxpEqGetGraphicEqHdl()` returns early without assigning
  its output pointer when the handle is `NULL`, so the caller passed an uninitialised stack
  pointer into the EQ setter, whose null check did not catch it.
- Every subsequent `processAudio()` call returned immediately without processing anything,
  because `dfxpUniversalModifySamples()` also returns early on a `NULL` handle.

**Fix:** the registry stubs now report "no such value" (`OKAY` with the exists flag cleared)
rather than an error, which is the correct semantic for a platform with no registry and is a
state the upstream explicitly handles (a missing date means "first run"). See ADR-019 in
`docs/DECISIONS.md` and the "Registry stubs" entry in `docs/THIRD_PARTY_NOTICES.md`.

### 9.2 The effect values do not reach the DSP kernels

With the core initialised, the suite measures:

```
[TEST] Running Effect Value Reachability Test...
  RMS with effects at 0:  0.866025
  RMS with effects at 10: 0.866025
  Max sample difference:  0
FAIL: The Bass and Fidelity effect values do not change the DSP output at all.
```

The output was **bit-identical** whether the Bass and Fidelity effects were 0 or 10. The
application's five effect sliders therefore did not drive the upstream DSP.

There were **two** independent causes, and both had to be fixed.

**Cause A: the knob values were lost between the write and the read.** The upstream stores the
effect knob and button values in the registry and reads them back when it communicates the
parameters to the DSP kernels:

```
dfxpSetKnobValue -> dfxp_SetKnobValue_MIDI
     -> dfxp_SessionWriteIntegerValue   (writes the value)
     -> dfxp_CommunicateFidelity        (reads the value back)
          -> dfxp_GetKnobValue_MIDI -> dfxp_SessionReadIntegerValue
```

The Android stubs discarded the write and reported "not present" on the read, so the
communication always fell back to the default value. The registry entry points are now
implemented against an in-memory key/value store, so the value round trips and the upstream's
own mapping works unmodified.

**Cause B: the parameter overlay structs were misaligned.** The parameter block is an array of
32-bit words, and the DSP overlays it with structs whose field order is defined to match the
word indices in `boardrv1.h`. Those structs used `long`, which is 32 bits on Win32 but **64
bits on Android (LP64)**. Every `long` field therefore occupied two words, and every field
after the first was read from the wrong word. Measured before the fix, the DSP was reading
`dry_gain` where `aural_drive` was expected, and `master_gain` was never applied at all.

The parameter structs now use `int32_t`/`uint32_t`, and
`ptutil/include/dfxp_param_layout.h` asserts every protocol-relevant field against the word
index that `boardrv1.h` defines, so the layout cannot silently break again.

**After both fixes** the same test measures:

```
  RMS with effects at 0:  0.379624
  RMS with effects at 10: 0.455021
  Max sample difference:  0.339681
PASS: Effect values measurably change the DSP output
```

Digital silence now stays silent, and the master gain is applied.

### 9.3 The float path could not be validated for quality

Before the fixes, `testFloatPathRetainsResolution` reported that 96.6% of output samples sat on
the 1/32768 grid. That was a *symptom* of 9.2 rather than a separate defect: the output was
clipped at the digital rail, and a clipped sample is exactly a multiple of 1/32768.

After the fixes the same test reports **1.96%** of samples on the grid, and the float path
(ADR-020) is confirmed to carry sub-16-bit resolution end to end.

### 9.4 Porting assumptions that were wrong

| Audit section | Assumption | What actually happens |
| :--- | :--- | :--- |
| 6.2 Registry | "Disable `update_from_registry_`; settings are managed by Kotlin and passed via JNI." | The registry is on the **initialisation critical path**, not only a persistence path. A failing registry read aborts `dfxpInit()` and leaves the core uninitialised. It is also where the effect knob values live, so the values have to round trip through it. |
| 8. Conclusion | "The FxSound `DfxDsp` engine is fully portable to Android ARM64." | Portability is fine; the gaps were in the **registry semantics** and the **word size of the parameter protocol**, neither of which the static audit looked at. |
| 3. Signal chain | The five effects map onto the upstream cascade. | The mapping is entirely upstream (`dfxp_CommunicateFidelity` and friends) and works once the values round trip and the struct layout matches the word indices. |
| 4. Audio formats | The parameter block is a float array indexed by word. | True, but the overlay structs that read it were built with `long`, so on LP64 every field after the first was misaligned. A word-size assumption, not a format assumption. |
