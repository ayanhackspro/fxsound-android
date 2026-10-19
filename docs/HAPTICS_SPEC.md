# Haptics Specification

## Design intent
Haptics should feel precise, short, and intentional. “Best” does not mean frequent or strong. Respect Android system touch-feedback settings, device capability, accessibility and the user's explicit preference.

## Settings
- System default: use standard view/Compose action feedback where available.
- Enhanced: app-specific effects with capability checks and rate limiting.
- Off: disable app-triggered enhanced effects; never override system preferences.

## Event map
| Event | Intended feedback | Rules |
|---|---|---|
| Master enable/disable | Crisp short confirmation | Fire once after state transition, not for repeated state emissions |
| Preset selected | Light selection tick | No haptic for every list recomposition |
| Slider movement | Sparse low-intensity ticks at meaningful increments | Throttle/rate-limit; never vibrate on every pointer frame |
| Slider boundary | Very subtle boundary response | Once on entering boundary, not continuously |
| Reset confirmation | Clear confirmation | Only after reset succeeds |
| Unsupported route | Optional subtle reject | Do not repeatedly alert during retries |
| Error | Brief distinct feedback | Pair with visible/textual error; do not rely on vibration alone |
| Toggle unavailable | No false success haptic | Explain why unavailable |

## API approach
- Prefer Android's standard `HapticFeedbackConstants` for common UI interactions.
- For richer effects, use supported `VibrationEffect` APIs and check hardware/primitive support before use.
- Respect system settings. Use `VIBRATE` permission only if the chosen API requires it.
- Do not require Xiaomi private APIs. If a documented OEM-specific option is explored, isolate it behind an adapter and preserve a standard Android fallback.
- Avoid old-fashioned long on/off buzzes for normal controls.
- Do not run haptic operations on the audio callback.

## Slider implementation notes
- Convert continuous slider position to a discrete haptic bucket.
- Trigger only when the bucket changes and a minimum interval has elapsed.
- Reset the bucket on gesture end.
- Keep the UI responsive when haptics are disabled or unsupported.
- Avoid firing haptics while programmatically restoring settings.

## Test cases
- System touch feedback disabled.
- App haptics set to Off.
- Device with no vibrator or limited primitive support.
- Rapid repeated taps.
- Fast slider scrubbing.
- Slider at minimum/maximum.
- TalkBack/accessibility use.
- Background/foreground transitions.
- Ensure no haptic feedback occurs from the real-time audio thread.
