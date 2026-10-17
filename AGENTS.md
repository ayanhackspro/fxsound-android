# Persistent Agent Rules

1. Read `ANTIGRAVITY_MASTER_PROMPT.md` and relevant specs before coding.
2. Never assert that rootless system-wide audio works unless demonstrated on the target device and tested source apps.
3. Never bypass app capture restrictions, DRM, Android security, or user-consent requirements.
4. Never misrepresent an in-app playback pipeline as system-wide processing.
5. Port actual FxSound DSP code where feasible; document upstream commit, modifications, dependencies, and license.
6. Do not copy FxSound trademarks, logos, or proprietary UI assets. Product branding should be original.
7. No blocking operations, heap allocation, mutex acquisition, file I/O, or verbose logging in real-time audio callbacks.
8. Parameter updates must be thread-safe and smoothed; avoid unsynchronized shared mutable DSP state.
9. Prefer tests and measured evidence over subjective claims. Loudness-match comparisons.
10. Keep all sample-rate, channel-count, PCM-format, and buffer-size assumptions explicit.
11. Treat failure to obtain the requested audio route as a visible state, not as a fake active toggle.
12. Respect Android touch-feedback settings and offer a way to disable app-specific enhanced haptics.
13. Do not use private or undocumented APIs in the core app.
14. Never claim tests were run unless commands were actually executed and results captured.
15. Keep the app functional and understandable when audio integration is unavailable.
16. Do not add analytics, tracking, account creation, ads, or cloud services unless explicitly approved.
17. Ask for approval before adding paid SDKs, proprietary services, or dependencies with incompatible licensing.
18. Document every major architectural decision in `docs/DECISIONS.md`.
