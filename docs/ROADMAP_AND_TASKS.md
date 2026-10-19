# Roadmap and Task Breakdown

## Phase 0 — Feasibility gate (must happen first)
- [ ] Inspect local environment and available Android SDK/NDK.
- [ ] Record Xiaomi 17 model, OS and build.
- [ ] Prototype public Android audio routes.
- [ ] Test YouTube, Spotify, browser, game and app-owned player separately.
- [ ] Write `FEASIBILITY_REPORT.md`.
- [ ] Decide PASS/PARTIAL/FAIL.
**Stop for user approval if the universal rootless goal cannot be met.**

## Phase 1 — Upstream DSP audit
- [ ] Clone FxSound repository.
- [ ] Record upstream commit.
- [ ] Identify required DSP files and dependencies.
- [ ] Map API, formats, sample rates, channels, state and parameter update behavior.
- [ ] Review AGPL and dependency licences.
- [ ] Produce DSP port report.
- [ ] Build offline/native reference if possible.

## Phase 2 — Native DSP library
- [ ] Create CMake Android ARM64 target.
- [ ] Isolate upstream code.
- [ ] Add wrapper C API and JNI.
- [ ] Add buffer/format validation.
- [ ] Add deterministic test fixtures.
- [ ] Add parity and numerical-stability tests.
- [ ] Add real-time-safe parameter publication.
- [ ] Document unsupported configurations.

## Phase 3 — Android integration
- [ ] Implement route capability discovery.
- [ ] Implement session effects only where supported and permitted.
- [ ] Implement playback capture prototype only within public policy.
- [ ] Implement app-owned reference player for DSP validation.
- [ ] Implement honest route-state machine.
- [ ] Add error recovery and lifecycle handling.
- [ ] Add diagnostics screen.

## Phase 4 — UI and haptics
- [ ] Apply UI design system.
- [ ] Build Home, Tuning, Presets, Settings and Diagnostics screens.
- [ ] Add master toggle tied to actual route state.
- [ ] Add verified DSP controls.
- [ ] Add haptic preference and event mapping.
- [ ] Add accessibility labels and font scaling support.
- [ ] Verify no false visualizer or meter data.

## Phase 5 — QA and hardening
- [ ] Run unit, native and instrumentation tests.
- [ ] Run long-run playback tests.
- [ ] Test route changes and background lifecycle.
- [ ] Test haptics and accessibility.
- [ ] Check CPU, memory, callback timing and battery.
- [ ] Review licence notices and source-offer obligations.
- [ ] Document known limitations.

## Phase 6 — Release candidate
- [ ] Build signed/debug APK as appropriate.
- [ ] Write setup and troubleshooting guide.
- [ ] Write support matrix.
- [ ] Include open-source notices.
- [ ] Complete `DEFINITION_OF_DONE.md`.
- [ ] Do not claim system-wide compatibility beyond demonstrated sources/routes.
