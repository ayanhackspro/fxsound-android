# Android UI Skills and Required Stack

## Required stack
- Kotlin for application logic.
- Jetpack Compose for all new native UI.
- Material 3 (Material You) components and theming.
- Use Material 3 adaptive/responsive patterns where appropriate.
- Do not introduce XML-based layouts for new screens unless an Android integration requires it.
- Keep UI state in Compose/ViewModel/domain state; do not put audio processing in composables.
- All DSP/audio callbacks must remain independent of Compose and the main thread.

## Antigravity skills
Before implementing UI, check whether the following skills are available in the Antigravity environment:
1. **M3 Skill**
2. **Android Official Skill**

If available, read and follow both skills. Use M3 Skill for Material 3 component selection, design tokens, theming, layout, accessibility and interaction guidance. Use Android Official Skill for current Android APIs, lifecycle, permissions, audio integration, background execution, testing and platform conventions.

Do not claim a skill was used unless it was actually available and consulted. If either skill is unavailable, record that in `docs/IMPLEMENTATION_LOG.md` and use the official documentation links below instead.

## Official references
- Jetpack Compose: https://developer.android.com/compose
- Material 3 for Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Material components and theming: https://developer.android.com/develop/ui/compose/designsystems/material3
- Android app architecture: https://developer.android.com/topic/architecture
- Android audio: https://developer.android.com/media
- Android haptics: https://developer.android.com/develop/ui/views/haptics

## Design requirements
- Use Material 3 `MaterialTheme`, color scheme, typography and shapes as the foundation.
- Create a coherent custom dark color scheme with one restrained accent; do not scatter hardcoded colors throughout the UI.
- Use Material 3 controls where they fit: switches, sliders, cards/surfaces, navigation, dialogs, snackbars and settings controls.
- Prefer custom composables only when the product needs a distinct audio control; preserve Material semantics, accessibility and focus behavior.
- Use previews for important screens and reusable controls.
- Add Compose UI tests for master toggle state, route-status messaging, preset selection, slider changes, haptic preferences and unavailable-route handling.
- Ensure the UI never displays “Processing” unless an actual supported audio route is active.
