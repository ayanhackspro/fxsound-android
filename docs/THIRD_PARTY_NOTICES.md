# Third-Party Notices

Required by `docs/LICENSING_AND_ATTRIBUTION.md`. This file must be complete before any binary
is distributed.

## FxSound `DfxDsp` (the native DSP core)

| Field | Value |
| :--- | :--- |
| Upstream project | `https://github.com/fxsound2/fxsound-app` |
| Upstream commit | `d8e7a23d37ed5939c2a3090a1c1756c7f2500b17` |
| Licence | GNU Affero General Public License v3.0 |
| Location in this project | `app/src/main/cpp/fxsound_dsp/` |
| Included in the distributed binary | **Yes** — compiled into `libfxsound_dsp.so` and packaged in the APK |
| Trademarks | No FxSound logo, artwork or trademark is used. The product name and branding in this app are original. |

### Local modifications to upstream files

Every file listed here is derived from the upstream commit above. The upstream copyright and
licence headers are preserved in all of them.

| Upstream file | Local modification |
| :--- | :--- |
| `ptutil/MTH/MthBuffer.cpp` | Float-to-16-bit conversion is clamped to the representable range instead of wrapping around. Without the clamp, samples above full scale wrap to negative values and crackle. |
| `DfxDspPrivate.cpp` | `isPowerOn()` compared the bypass button against the wrong polarity, so the engine believed it was bypassed while it was active. Corrected to `value == 0`. |
| `ptutil/pstr/pstrWide.cpp` | Rewritten with POSIX `mbstowcs`/`wcstombs` instead of the Win32 `MultiByteToWideChar`/`WideCharToMultiByte` APIs. The original file was UTF-16LE encoded and did not compile with clang. |
| `ptutil/include/pstr.h` | Added the `codedefs.h` include guard and the `__has_include` fallbacks needed on a non-Windows toolchain. |
| `ptutil/include/codedefs.h` | Added `__has_include` guards for the standard headers so the file resolves outside MSVC. |
| `ptutil/include/slout.h` | Added `__has_include` guards. |
| `ptutil/include/c_aural.h`, `c_lex.h`, `c_max.h`, `c_play.h`, `c_wid.h` | **Word size fix.** The parameter overlay structs used `long`, which is 32 bits on Win32 but 64 bits on Android (LP64). Every field after the first therefore occupied two 32-bit words instead of one, and the DSP read its parameters from the wrong words. The fields are now `int32_t`/`uint32_t`, which restores the layout the upstream defines in `boardrv1.h`. No algorithmic behaviour was changed. See ADR-023. |
| `ptutil/include/dutio.h` | The stereo in flag was read with `*(volatile long *)`, which on LP64 read two words. It now reads `int32_t`. |
| `ptutil/dfxp/dfxpUniversal.cpp` | Included for the upstream comment documenting that the internal pipeline is always 32-bit float. No behavioural change. |
| `CMakeLists.txt` (upstream root) | Not used. The port has its own build files; see "Build files" below. |

### Files added by the port (not upstream)

| File | Purpose |
| :--- | :--- |
| `ptutil/android_dsp_compat.cpp` | POSIX/Android replacements for the Win32 and hardware-specific functions the core calls. **This file contains behavioural changes to the port, not just no-op stubs** — see below. |
| `ptutil/include/dfxp_param_layout.h` | Compile-time assertions that pin the parameter overlay structs to the 32-bit word indices the upstream defines in `boardrv1.h`. Fails the build if the layout is ever misaligned again. |
| `include/pt_windows_compat.h` and the `include/*.h` forwarding headers (`windows.h`, `mmsystem.h`, `shlobj.h`, `winbase.h`, `winreg.h`, `direct.h`, `tchar.h`, `conio.h`, `share.h`, `AudioPassthru.h`) | Provide the Win32 type and API surface the upstream headers expect, forwarding to a POSIX implementation. They are marked with `// IWYU pragma: export`. |

### Behavioural changes in `android_dsp_compat.cpp`

1. **The registry is an in-memory key/value store.** `regReadKey_Wide()`, `regCreateKey_Wide()`,
   `regCreateKeyTest_Wide()`, `regCreateKeyWithKeyname_Dword_Wide()` and
   `regCreateKeyWithKeyname_String_Wide()` are implemented against a small in-memory table
   instead of the Windows registry. The upstream stores the effect knob and button values in
   the registry and reads them back when it communicates the parameters to the DSP kernels, so
   the write and the read have to round trip. A value that is not present is reported as "not
   present" rather than as an error, which is the state the upstream explicitly handles as a
   first run. See ADR-023.
2. **Hardware DSP stubs.** `dspsPlayProcess()` (the 16-bit kernel) is a no-op. The 32-bit kernel
   `dspsPlayProcess32()` is compiled in full and is the one the engine uses.
3. **File and string utilities.** `fileExist_Wide()`, `fileOpen_Wide()`, `mthIsLong_Wide()` and
   `pstrCalcLocationOfStrInStr_Wide()` are POSIX equivalents of the Win32 originals.

### Build files (project-local, not upstream)

- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/cpp/dsp_sources.cmake` — the shared source and include lists, used by both the
  app build and the native test build so the test binary always exercises the same sources
  that ship in the APK.
- `app/src/main/cpp/engine/fxsound_engine.h` / `.cpp` — the local engine wrapper around the
  upstream core. Not upstream code.
- `app/src/main/cpp/jni/fxsound_jni.cpp` — the JNI boundary. Not upstream code.
- `app/src/main/cpp/tests/test_dsp_numerical.cpp` — the native numerical test suite. Not
  upstream code.
- `app/src/test-native/` — the standalone CMake project and script that build and run the
  native test suite on a device. Not upstream code.

### Outstanding release blockers

- **The Corresponding Source has not been produced.** The AGPL requires that the complete
  corresponding source of the modified upstream work be made available to users of the binary.
  The upstream clone used for the port is not present in this workspace, so the diff against
  commit `d8e7a23d37ed5939c2a3090a1c1756c7f2500b17` has not been generated and the local
  modifications have not been machine-verified against it. **This must be done before
  distribution.** The table above records the modifications as they are described in
  `docs/IMPLEMENTATION_LOG.md`; it has not been checked against the upstream tree.

## Other components

| Component | Version | Licence | Included in the binary | Notes |
| :--- | :--- | :--- | :--- | :--- |
| JUCE | — | — | **No** | The upstream project uses JUCE in its UI and plugin host layers only. The DSP core has no JUCE dependency, and JUCE is not vendored, linked or packaged by this project. |
| Oboe | — | — | **No** | The in-app reference player uses the platform `AudioTrack` API, not Oboe. Oboe is not a dependency of this project. |
| AndroidX (Core, Lifecycle, Activity, Compose, Navigation, DataStore) | see `gradle/libs.versions.toml` | Apache-2.0 | Yes | Standard AndroidX artefacts. |
| Jetpack Compose + Material 3 | see `gradle/libs.versions.toml` | Apache-2.0 | Yes | Standard AndroidX artefacts. |
| Kotlin standard library | 2.0.21 | Apache-2.0 | Yes | |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | Yes | |
| JUnit | 4.13.2 | EPL-1.0 | **No** (test scope only) | |
| **Gilroy** typeface (Bold, Regular, Semibold) | — | **To be confirmed** | **Yes** (`app/src/main/res/font/`) | **Review required before distribution.** These three files were taken from the upstream FxSound application, which distributes them under its AGPL-3.0 licence. Gilroy is a commercial typeface from Sharp Type, and the upstream's licence for its own use does not automatically extend to third-party distribution of the font files. Confirm the terms, or replace the typeface, before shipping a binary. |
| Android SDK / NDK | 27.2.12479018 | Android Software Development Kit License Agreement | No (toolchain) | Build-time only. |
| Gradle | 8.11.1 | Apache-2.0 | No (build tool) | |
