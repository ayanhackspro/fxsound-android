# FxSound-style Android Audio Enhancer — Antigravity Build Pack

This repository is a **build specification**, not a claim that Android can provide unrestricted system-wide audio processing to an ordinary third-party app.

## Target
- Primary device: Xiaomi 17
- Root: not allowed
- Goal: clean, low-artifact audio enhancement for audio from YouTube, Spotify, games, browsers and other apps
- UI: restrained, polished, FxSound-inspired visual language (do not copy proprietary artwork/assets)
- Haptics: precise, subtle, configurable, with Android-compatible fallbacks
- Engine: investigate and port the real FxSound `DfxDsp` C++ DSP where technically and legally feasible

## Critical product truth
Android does not give normal apps a guaranteed public API to intercept and process every other app's playback. `AudioPlaybackCapture` is subject to consent, audio-usage and source-app capture policies. `AudioEffect`/`DynamicsProcessing` effects are attached to audio sessions; they are not a universal system-wide processing mechanism. A normal foreground service does not change those rules.

Therefore, the first implementation milestone is a **feasibility spike on the Xiaomi 17**. Do not build the entire UI around a fictional universal audio route. If universal rootless processing cannot be demonstrated, report the limitation clearly and provide the best honest fallback: supported session-effect integration where it works and/or an in-app reference player for DSP verification. Do not claim the fallback meets the system-wide requirement.

## How to use this pack in Antigravity
1. Open a new workspace/repository.
2. Add this entire pack to the project root.
3. Start with `ANTIGRAVITY_MASTER_PROMPT.md`.
4. Follow `docs/IMPLEMENTATION_ROADMAP.md` in order.
5. Do not skip Gate 0 (platform feasibility) or Gate 1 (DSP audit).
6. Require build/test evidence for every phase. Never claim a test passed unless it was actually run.

## Required UI stack and skills
- Kotlin + Jetpack Compose + Material 3 (M3) are required for the Android UI.
- Before UI implementation, consult the Antigravity **M3 Skill** and **Android Official Skill** if available. See `docs/ANDROID_M3_SKILL.md` for exact instructions and fallback documentation.

## Documents
- `ANTIGRAVITY_MASTER_PROMPT.md` — primary agent instructions
- `AGENTS.md` — persistent coding rules
- `docs/PRODUCT_REQUIREMENTS.md` — product scope and acceptance criteria
- `docs/ARCHITECTURE.md` — app and audio architecture
- `docs/ANDROID_AUDIO_FEASIBILITY.md` — hard platform constraints and experiments
- `docs/FXSOUND_DSP_PORT.md` — upstream DSP investigation/port plan
- `docs/DSP_QUALITY_SPEC.md` — gain staging, bass, clipping and real-time requirements
- `docs/UI_UX_SPEC.md` — visual system and screen details
- `docs/ANDROID_M3_SKILL.md` — required Kotlin/Compose/Material 3 stack and skill usage
- `docs/HAPTICS_SPEC.md` — interaction-to-haptic mapping and fallbacks
- `docs/TEST_PLAN.md` — audio, performance, lifecycle and UX validation
- `docs/ROADMAP_AND_TASKS.md` — ordered implementation tickets
- `docs/LICENSING_AND_ATTRIBUTION.md` — open-source compliance checklist
- `docs/DEFINITION_OF_DONE.md` — release gates
- `docs/DECISIONS.md` — architecture decision records
- `docs/ANTIGRAVITY_WORKFLOW.md` — agent workflow and reporting format

## Suggested first command to the agent
“Read `ANTIGRAVITY_MASTER_PROMPT.md`, `AGENTS.md`, and all docs. Do only Phase 0: inspect the environment and upstream repository, produce a feasibility report for rootless system-wide audio on the Xiaomi 17, identify the exact DSP files and dependencies, and propose a build plan. Do not implement the full app until I approve the report.”
