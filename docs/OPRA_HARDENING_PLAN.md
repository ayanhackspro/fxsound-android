# OPRA Integration Hardening Plan

Status: in progress
Date: 2026-10-27
Prerequisite: ADR-028 (initial integration). This plan closes the weaknesses found in the
first review. Every item ends with a measurable check; no "improved" claim without a run.

## Workstream A — Accuracy & honesty of the DSP mapping

A1. Quantify the shelf→peaking approximation.
   - Build an analytic biquad comparison (RBJ low/high shelf vs RBJ peaking) on the JVM.
   - Grid-search approximation constants (peaking Q, gain scale) over the shelf bands that
     actually occur in the dataset; pick the constants with the lowest mean |dB| error
     20 Hz–20 kHz. Replace the hand-picked Q=0.6 / ×0.8 constants with the measured best.
   - Acceptance: a test prints the chosen constants and their worst/mean error; conversion
     tests updated to assert the chosen constants.

A2. Stop implying the renderer uses OPRA Q.
   - `DynamicsProcessing.EqBand` is (cutoff, gain) only. Add an explicit renderer
     disclosure to every plan (and show it in the UI next to the preview):
     "Device renderer uses platform band bandwidth; the preview shows ideal Q."
   - Preview stays analytic (useful shape), but is labelled as ideal, not measured.
   - Acceptance: plan always carries the disclosure; UI displays it; test asserts it.

## Workstream B — Honest control states

B1. Bass slider must not look active while a headphone profile owns the low end.
   - Disable the Bass slider when `state.headphonePlan != null`, with a note pointing to
     the headphone profile; restore when the profile is removed.
   - Acceptance: UI gate + no change in DSP behaviour (existing tests stay green).

B2. "APPLIED" must expire.
   - Reset `isHeadphonePlanApplied` to false when no audio session remains / the service
     tears down; the plan itself stays selected (still PENDING).
   - Acceptance: unit test for the state transition; no false APPLIED after teardown.

## Workstream C — Cache robustness & versioning

C1. Validate before replacing a good cache.
   - Download to `.tmp`, require first byte `{`, ≥1000 lines, and a successful full index
     before promoting; keep the previous file as `.bak` for rollback.
C2. Recover from a corrupt cache automatically.
   - If indexing the cached file fails: try `.bak`, otherwise delete and re-download once.
C3. Concurrency & visibility.
   - `Mutex` around load/refresh, `@Volatile` index reference; single-flight refresh.
C4. Lifecycle/UX.
   - Distinguish "Downloading" from "Indexing"; add "Delete downloaded data"; exclude the
     cache from Android backup/transfer rules.
   - Acceptance: JVM test for validation + corrupt-cache recovery using temp files;
     backup XMLs exclude `files/opra`.

## Workstream D — Attribution & licensing

D1. In-app: link the CC BY-SA 4.0 license text, state that shelf filters are modified
    (approximated) by this app, and keep creator credit more prominent than OPRA's.
D2. Repo: add `docs/OPRA_NOTICE.md` with attribution, licence links, modification note,
    and dataset source/versioning description.
D3. Test fixture: commit ~20 real OPRA lines under CC BY-SA 4.0 with an attribution
    header file so parsing/conversion runs in CI without the 12.6 MB dump.
   - Acceptance: fixture test runs by default; notice file present; UI shows license link.

## Workstream E — UX, accessibility, cleanup

E1. Search: rank exact/prefix product matches first, then contains, then vendor; precomputed
    lowercase keys; show subtype; warn when several near-identical model names exist.
E2. Accessibility: minimum 11sp for body copy, content description for the preview canvas,
    selected state exposed for profile rows; move new UI strings into `strings.xml`.
E3. Cleanup: remove dead `enabled` param, rename the custom divider so it no longer shadows
    Material3's, drop unused `summariesByProduct`, split loading phases.
E4. Per-band user adjustment (spec's "custom adjustments"): persisted offsets per converted
    band, UI rows with ±6 dB, reset; global level slider remains as master trim.
   - Acceptance: persistence round-trip test; conversion applies offsets; UI compiles.

## Deliberately deferred (with reasons)

- Product line-art thumbnails: vendor artwork introduces separate trademark/asset
  questions and a new image pipeline; revisit after the redistribution review for assets.
- Auto-matching the connected Bluetooth device name: needs `BLUETOOTH_CONNECT` and adds
  a permission prompt; not required for the PoC.
- True on-device audio validation: blocked until the Xiaomi 17 is available. Analytic and
  JVM evidence is reported, device validation stays explicitly pending.

## Execution order

A1 → A2 → B1 → B2 → C1–C4 → D1–D3 → E1–E4 → final build/tests → ADR/log update → commit.

## Definition of done

- All unit tests pass without the dataset env var (fixture path) and with it (full dump).
- `assembleDebug` succeeds for arm64-v8a and x86_64.
- No unaddressed high/medium review item; deferrals documented here and in ADR-028.
