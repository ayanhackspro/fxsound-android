# OPRA Integration Hardening Plan

Status: **implemented** (device validation still pending — see deferred items)
Date: 2026-10-27
Prerequisite: ADR-028 (initial integration). This plan closes the weaknesses found in the
first review. Every item ends with a measurable check; no "improved" claim without a run.

## Results vs. plan (2026-10-27)

A1 **measured, not guessed.** Grid search over all 24,980 shelving bands in the real dump
(`ShelfApproximationCalibrationTest`):

| Strategy | mean | p99 | max | bands > 3 dB |
|---|---|---|---|---|
| old constants (Q 0.60, ×0.80) | 0.84 dB | — | — | — |
| **shipped** (Q 1.45, ×0.50) | **0.79 dB** | 4.12 dB | 9.87 dB | 717 / 24,980 |
| two-band split (not shipped) | 0.63 dB | 2.65 dB | 8.60 dB | 183 / 24,980 |

The residual is concentrated in high-gain high shelves (a +14 dB shelf above 2.2 kHz is
not a bell at all). A two-band split halves the p99 but still cannot fix those curves,
costs a second band per shelf against the 20-band cap, and complicates the converter; the
shipped alternative is to keep one band and **report each profile's own worst-case
deviation** in the UI (and in its conversion notes). Decision recorded in ADR-028
amendment.

A2 **done.** `HeadphoneEqPlan.rendererDisclosure` is always set; the UI labels the preview
"ideal peaking model (not measured on the device)" and states that the platform chooses
the band bandwidth. Test asserts it.

B1 **done.** Bass slider disabled with an explanatory note while a plan is active.
B2 **done.** `withHeadphoneAttachResult(false)` on session teardown / service destroy;
plan stays selected (PENDING). Unit tested in `HeadphonePlanStateTest`.

C1 **done.** `.tmp` download validated (JSON object, ≥256 KB, ≥1000 lines) *and* fully
indexed before promotion; previous file kept as `.bak`.
C2 **done.** Recovery chain: live file → `.bak` → single re-download.
C3 **done.** `Mutex` + `@Volatile` index/search; load/refresh/delete serialised.
C4 **done.** Separate DOWNLOADING/INDEXING phases, stale-copy banner, "Delete downloaded
data" action, `files/opra` excluded from backup and device transfer.
Tests: `OpraCacheTest` (temp dirs, no Android context).

D1 **done.** CC BY-SA 4.0 link, modification notice on screen, creator credit first/larger.
D2 **done.** `docs/OPRA_NOTICE.md`.
D3 **done.** `app/src/test/resources/opra_fixture.jsonl` — 41 verbatim lines (10 vendors,
12 products, 19 profiles) + `OpraFixtureTest` runs by default.

E1 **done.** `OpraSearch` (pure, tested): exact → prefix → word-prefix → contains →
vendor; precomputed lowercase keys; multi-token narrowing; variant-family warning in UI.
E2 **partly done.** 11sp minimum body copy in the OPRA screen, `contentDescription` on the
response canvas and on the ± buttons, OPRA screen strings moved to `strings.xml`.
*Not done:* the rest of the app's hardcoded strings (out of scope for this workstream); the
catalogue rows are navigation (tapping opens the detail), so there is no persistent
"selected" row state to expose — rows are clickable columns with button-like behaviour
rather than a selection list. A formal TalkBack pass still needs a device.
E3 **done.** Dead `enabled` param removed, divider renamed `OpraDivider` (no longer shadows
Material 3), unused `summariesByProduct` dropped, loading phases split.
E4 **done.** `applyUserAdjustments` is pure and unit tested; per-band ±6 dB steppers, reset,
master slider; offsets persist in `opra_selection_json` and restore.

## Workstream A — Accuracy & honesty of the DSP mapping (original text)

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

- Two-band shelf approximation: measured at 0.63 dB mean / 2.65 dB p99 vs 0.79 / 4.12,
  but it still cannot represent the worst high-gain shelves (max 8.6 dB), doubles the band
  count against the 20-band cap, and complicates the converter. The residual is disclosed
  per profile instead. Revisit if real-device listening shows the tail matters.
- Product line-art thumbnails: vendor artwork introduces separate trademark/asset
  questions and a new image pipeline; revisit after the redistribution review for assets.
- Auto-matching the connected Bluetooth device name: needs `BLUETOOTH_CONNECT` and adds
  a permission prompt; not required for the PoC.
- Full app-wide string externalisation / TalkBack pass: this workstream covered the new
  OPRA screen only.
- True on-device audio validation: blocked until the Xiaomi 17 is available. Analytic and
  JVM evidence is reported, device validation stays explicitly pending (AGENTS.md rules 2
  and 14).

## Execution order

A1 → A2 → B1 → B2 → C1–C4 → D1–D3 → E1–E4 → final build/tests → ADR/log update → commit.

## Definition of done

- All unit tests pass without the dataset env var (fixture path) and with it (full dump).
- `assembleDebug` succeeds for arm64-v8a and x86_64.
- No unaddressed high/medium review item; deferrals documented here and in ADR-028.
