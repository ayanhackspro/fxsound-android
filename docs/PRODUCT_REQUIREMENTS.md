# Product Requirements Document

## 1. Summary
A native Android audio-enhancement app inspired by FxSound, initially targeting Xiaomi 17. Its differentiators are an audited port of FxSound's DSP, conservative bass enhancement, robust gain management, smooth controls, clean UI, and polished haptics.

## 2. Primary user problem
Many equalizer apps sound harsh or distorted after bass boost, or produce clicks/dropouts under load. The product should prioritize clean processing and honest capability reporting over exaggerated effects.

## 3. Goals
- Investigate and, where feasible, reuse the real FxSound `DfxDsp` C++ engine.
- Avoid digital clipping through gain staging and peak-aware output control.
- Avoid choppy playback through real-time-safe design and buffer/performance testing.
- Provide a clean, premium, dark interface.
- Provide subtle, meaningful, configurable haptics.
- Work without root.
- Target Xiaomi 17 first and document compatibility.
- Investigate the goal of enhancing audio from YouTube, Spotify, games, browsers and other apps without claiming universal support prematurely.

## 4. Non-goals
- Root-only implementation as the main product.
- Bypassing DRM, capture policies, or app privacy settings.
- Claiming guaranteed system-wide processing before it is proven.
- A music streaming service, downloader, or media library.
- AI enhancement in v1.
- Copying FxSound's branding, assets, or exact UI.
- Cloud accounts, analytics, ads, or subscriptions in v1.

## 5. Personas and top jobs
- Listener: wants bass and clarity without clipping or harshness.
- Power user: wants a few presets and precise custom controls.
- Quality-focused user: wants to know when processing is actually active and what route is in use.

## 6. Functional requirements
### P0 — feasibility and DSP
- FR-001: Document Xiaomi 17 model/build, Android version and audio stack.
- FR-002: Test supported public APIs for session effects and eligible playback capture.
- FR-003: Test the real FxSound DSP in a native harness.
- FR-004: Detect and report route/processing state honestly.
- FR-005: Process supported PCM formats and sample rates explicitly.
- FR-006: Expose a safe bypass that does not click or leave audio in a broken state.

### P1 — controls
- FR-101: Master enable/bypass.
- FR-102: Presets and custom settings.
- FR-103: Bass enhancement and EQ controls.
- FR-104: Input/output peak status when measurable.
- FR-105: Restore defaults and persist preferences.
- FR-106: Settings for haptics and intensity where supported.

### P1 — lifecycle
- FR-201: Handle foreground/background transitions.
- FR-202: Handle headphones/Bluetooth route changes and audio focus where applicable.
- FR-203: Recover from stream errors without spinning or crashing.
- FR-204: Stop processing cleanly when user disables it.
- FR-205: Explain unavailable/unsupported routes.

## 7. Quality requirements
- QR-001: No known unstable filter or unbounded gain.
- QR-002: No digital full-scale overflow at the final digital stage.
- QR-003: No avoidable audible clicks on preset/parameter change.
- QR-004: No heap allocations or blocking operations in the real-time callback.
- QR-005: Automated tests for silence, impulse, sine sweep, multitone, full-scale input, low-level input, and parameter transitions.
- QR-006: Stable 30-minute playback test on target hardware for each supported route.
- QR-007: Haptics must not fire continuously while the slider is moving.
- QR-008: App must be usable with haptics disabled.
- QR-009: User-visible status must distinguish “enabled”, “available”, “processing”, and “unsupported”.
- QR-010: Document CPU, memory, latency and battery measurement methodology; do not invent results.

## 8. Acceptance criteria
A release is not accepted until:
1. Feasibility report exists with device/build and per-source results.
2. DSP provenance and license are documented.
3. Native build and test results are recorded.
4. Audio routing failures are visible and do not pretend to be success.
5. DSP tests show no NaN/Inf, unstable output or unexplained discontinuities.
6. Long-run playback test is completed on the Xiaomi 17 for supported modes.
7. UI and haptics specs are implemented and reviewed.
8. Known limitations are documented in app and README.
