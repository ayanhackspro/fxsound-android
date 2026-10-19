# UI / UX Specification

## Direction
A clean, restrained, premium audio utility inspired by FxSound's clarity and focus. Do not copy logos, exact layouts, or proprietary assets. Avoid over-designed neon gradients, glowing cards, busy waveform backgrounds, or fake live visualizers.

## Required implementation stack
- Kotlin + Jetpack Compose + Material 3 (M3) Design.
- Consult Antigravity's M3 Skill and Android Official Skill before implementation if available; follow `ANDROID_M3_SKILL.md`.

## Visual system
- Dark-first design: near-black/charcoal background, slightly lighter surfaces, clear separators.
- One accent color, restrained and consistent.
- Strong text contrast and readable labels.
- Use Material 3 components with custom theming where appropriate.
- Use a clear spacing scale and consistent corner radii.
- Support dynamic font sizing and TalkBack.
- Use animations sparingly and never block audio processing.
- Avoid decorative motion that reduces battery life.

## Screen 1: Home
Top:
- Original app wordmark/icon.
- Connection/route status chip: “Processing”, “Available but inactive”, “Unsupported on this route”, or “Reference player”.
- Settings button.

Main:
- Large master toggle/button.
- Clear status text that describes actual route state.
- Current preset name and preset selector.
- Compact primary controls: Bass, Clarity, Treble or the actual controls exposed by the ported engine.
- Optional EQ response curve, but only if it accurately represents current settings.
- Small input/output peak meters only when data is actually available.
- Bypass comparison action, if supported safely.

## Screen 2: Equalizer / Tuning
- Frequency curve with accessible individual controls.
- Controls should show numeric values and reset action.
- Slider changes should be smooth and should not trigger a full activity recomposition or native engine reinitialization.
- Show pre-gain/headroom changes when relevant.
- Clearly mark values that are constrained for safety.

## Screen 3: Presets
- Small set of sensible initial presets, subject to the actual engine parameters.
- Example names: Balanced, Bass Detail, Vocal Clarity, Soft Listening, Custom.
- Do not advertise a preset until its DSP mapping is verified.
- Allow duplicate/save/rename custom presets if time permits.
- Loudness-match presets in tests so louder does not falsely appear better.

## Screen 4: Settings
- Haptics: System default / Enhanced / Off.
- Haptic intensity only if supported and exposed meaningfully.
- Processing mode/route explanation.
- Audio diagnostics and compatibility status.
- Reset settings.
- Open-source notices and licences.
- Privacy: no analytics or account required in v1.

## Honest status model
Do not equate the user's master toggle with successful system-wide processing.
- `OFF`: user requested bypass.
- `INITIALIZING`: starting a supported route.
- `PROCESSING`: actual audio is being processed through a verified route.
- `UNAVAILABLE`: no compatible route.
- `PARTIAL`: only some source/route types are supported.
- `ERROR`: processing failed; show a recovery action.

## Interaction
- Use small transitions and haptic confirmation.
- Avoid long animations on sliders.
- Preserve slider position and current preset across configuration changes.
- All audio work occurs off the UI thread.
- If the app is not processing external audio, explain why and offer the supported test/reference route without pretending otherwise.

## Layout responsiveness
- Target modern Android phones, including Xiaomi 17.
- Respect display cutouts, edge-to-edge insets, landscape, font scaling and system navigation.
- Test at common density/font scales.
- Support dark theme; light theme may be added after the dark design is stable.
