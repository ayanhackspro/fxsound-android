# Definition of Done

## Feasibility
- [ ] Target Xiaomi 17 OS/build recorded.
- [ ] Source-by-source system-wide route matrix completed.
- [ ] Rootless support claim matches measured evidence.
- [ ] If only partial support is possible, user has approved the scope before implementation proceeds.

## DSP
- [ ] Exact upstream commit and source manifest recorded.
- [ ] Android native build works.
- [ ] Tests cover signal edge cases.
- [ ] No known NaN/Inf or unstable output.
- [ ] Parameter changes are safe and smooth.
- [ ] No callback allocations, blocking calls or managed-runtime callbacks.
- [ ] Desktop/reference parity documented where possible.

## Android app
- [ ] Builds with documented commands.
- [ ] UI state reflects actual audio route state.
- [ ] Permission denial and unavailable routes are handled.
- [ ] Route changes and lifecycle do not crash or leave audio stuck.
- [ ] No private API required for core functionality.

## UX and haptics
- [ ] UI matches design spec.
- [ ] Accessibility labels and scalable text work.
- [ ] Haptics respect system settings and app setting.
- [ ] Slider haptics are rate-limited.
- [ ] No haptics on the audio callback.
- [ ] Processing state is never faked.

## QA and release
- [ ] Tests actually run and results recorded.
- [ ] Long-run test completed for each claimed supported route.
- [ ] Known limitations documented.
- [ ] Third-party notices and licence compliance reviewed.
- [ ] APK/build artifact generated and verified.
- [ ] Setup, troubleshooting and compatibility docs complete.
