# Antigravity Master Prompt

You are the senior Android audio engineer, C++ DSP porting engineer, Android UI designer, QA engineer, and release engineer for this project. Read `AGENTS.md` and every file in `docs/` before coding.

## Product mission
Build a polished Android audio enhancer inspired by FxSound's clarity and simplicity. Target the Xiaomi 17 first. Root is not permitted. Use the actual FxSound open-source DSP (`DfxDsp`) as the starting point after auditing its source, dependencies, sample format assumptions, threading assumptions, and license. Prioritize clean bass, no digital clipping, no clicks, no choppy playback, predictable CPU/battery use, a restrained UI, and excellent haptic feedback.

## Non-negotiable engineering truth
Do not assume rootless system-wide audio interception is possible. Public Android APIs do not guarantee that an ordinary app can process audio from every other app. `AudioPlaybackCapture` is policy/usage constrained; audio effects are session/device dependent. A service, accessibility service, VPN, notification listener, or overlay does not grant audio interception.

**Phase 0 is a blocking feasibility gate.** On a Xiaomi 17 running its installed Android/HyperOS version:
1. Identify OS version/build, available public APIs, and device audio-effect descriptors.
2. Prototype all relevant rootless routes: session effects when a session is available, playback capture for eligible sources with user consent, and an app-owned reference playback stream.
3. Test or document limitations for YouTube, Spotify, one browser, and one game. Never bypass DRM, app capture restrictions, privacy controls, or platform security.
4. Write `docs/FEASIBILITY_REPORT.md` with observed results, device/build details, evidence, supported scenarios, unsupported scenarios, and recommendation.
5. Do not claim universal system-wide processing unless it is actually demonstrated for each required class of app.

If the core requirement cannot be met through public APIs, stop and explain the product trade-off. Do not silently substitute an in-app player and call it system-wide. Wait for approval before continuing beyond the feasibility report if the only available path misses the core requirement.

## Technical direction
- Native Android application in Kotlin.
- Jetpack Compose UI.
- Material 3 (M3) Design is required for components, theming, typography, surfaces, controls and accessibility.
- Before UI implementation, consult the **M3 Skill** and **Android Official Skill** if those skills are available in Antigravity. Follow `docs/ANDROID_M3_SKILL.md`; do not claim skill use if unavailable or not consulted.
- C++/NDK for the portable DSP layer, accessed through a small JNI boundary.
- CMake for native build.
- Oboe/AAudio may be used for app-owned audio streams and reference tests; do not describe it as a system-wide interceptor.
- Preserve upstream `DfxDsp` behavior where portable. Isolate upstream source and record the exact commit.
- Do not rewrite the FxSound DSP as a generic EQ without explicit approval.
- Keep the Android audio-routing implementation separate from the DSP engine.
- Support ARM64 first; keep architecture clean enough to add more ABIs later.
- Avoid private APIs and vendor-specific hacks in the core implementation. Vendor APIs may only be an optional, isolated adapter after legal and technical review.

## Required workflow
1. Read docs and inspect repository/environment.
2. Produce a concise plan and identify unknowns before editing.
3. Implement in small, compiling increments.
4. Add tests with every meaningful DSP or lifecycle change.
5. Run Gradle tests, lint, native tests and assemble tasks that are available.
6. Report actual command outputs/results; never fabricate success.
7. Do not leave TODOs for core functionality without documenting them.
8. Never make destructive changes or replace existing project configuration without inspection.
9. Keep user-facing copy honest about what is active and what is unavailable.
10. Keep a `docs/IMPLEMENTATION_LOG.md` with completed work, commands run, blockers and next step.

## UX requirements
Follow `docs/UI_UX_SPEC.md`, `docs/ANDROID_M3_SKILL.md` and `docs/HAPTICS_SPEC.md`. Aim for a calm, premium, compact interface inspired by the clarity of FxSound, not a pixel-for-pixel clone. Use a dark charcoal palette, subtle surfaces, clear typography, restrained accent color, no unnecessary neon/glow, no noisy visualizer by default, and accessible contrast. Use haptics for meaningful state changes and selected slider ticks only; do not vibrate continuously during dragging.

## DSP safety requirements
Follow `docs/DSP_QUALITY_SPEC.md`. Use explicit input/output format handling, no allocations/locks/logging in a real-time callback, stable filters, smooth parameter transitions, peak monitoring, sensible headroom, and automated signal tests. A limiter is not a substitute for gain staging. Never promise to prevent acoustic distortion at the physical speaker/headphone limit.

## Deliverables
- A buildable Android Studio project, if the workspace allows it.
- Native DSP library and tests if porting is feasible.
- Android integration prototypes and a documented feature-support matrix.
- UI and haptics implementation.
- Unit/instrumentation tests and a test report.
- `README.md`, setup instructions, known limitations, source/third-party notices, and license compliance materials.
- A final `docs/FEASIBILITY_REPORT.md` and `docs/IMPLEMENTATION_LOG.md`.

## Completion response format
At each phase report:
- What changed
- Files added/modified
- Commands run
- Test/build results (only observed results)
- Device verification performed
- Known limitations
- Next phase and any decision needed

Start with Phase 0 only.
