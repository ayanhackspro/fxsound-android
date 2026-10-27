# Phones Screen (OPRA Headphone Tuning) - Rebuild Summary

Status: implemented and verified on the API 37 emulator only. No audible validation on the
Xiaomi 17 (no device connected). Commit: `603ccc5`.

## Purpose

The Phones tab lets the user pick a headphone model, choose a community-measured correction
profile, preview its predicted response, trim it, and stage it for the pre-EQ stage of the
renderer. The catalogue is the OPRA dataset (`database_v1.jsonl`), fetched at runtime and
never bundled in the APK.

## What was wrong

Reported as "the phones section isn't looking good". Two of the three defects below were
functional bugs, not styling.

| # | Defect | Root cause | Evidence |
|---|--------|-----------|----------|
| 1 | Licence card occupied roughly half the screen with dead space | Four `TextButton`s in one unbounded `Row`; "Delete downloaded data" could not fit at 320 dp, wrapped to ~10 lines, forcing the card to 987 px tall | `uiautomator dump` node bounds before/after |
| 2 | "Indexing headphone database" on every visit to the tab | `OpraRepository.loadOrRefresh(forceNetwork = false)` only checked the cache file, never the in-memory catalogue, so it re-indexed 12.6 MB each time | Timed re-entry; guard added; re-entry now instant |
| 3 | Green APPLIED pill shown as soon as a profile was selected | UI conflated "selected" with "applied by the renderer" | Screenshot after Apply; state is now amber PENDING until `isHeadphonePlanApplied` |
| 4 | "1 profiles"; brand missing from titles such as `$2 earphones` | Hardcoded plural; title logic replaced the product name with `name.ifBlank { vendor }` | List screenshot |

## Current behaviour

### Browse list (single `LazyColumn`)
1. Header: "HEADPHONE TUNING" plus a one-line explanation.
2. Compact attribution card - OPRA logo tinted, project name, CC BY-SA 4.0 notice, the
   in-app modification notice for shelving bands, refresh as an icon button, and links
   (`OPRA project`, `CC BY-SA 4.0`, `Delete data`) on their own row.
3. Search field with leading magnifier and a clear button; placeholder
   "Search headphones or brand...".
4. Status strip for the current selection: amber PENDING or green APPLIED, creator and
   profile name, `Remove`, and - while pending - "Applies when a music player attaches its
   audio session."
5. Advisory banner when a query returns two rows of the same product family (e.g. ANC on/off
   pairs, `Major II` / `Major III`).
6. Result rows: product title (vendor prepended only when the name does not already contain
   it), plural profile count, sub-type, chevron. Capped at 100 matches with an explicit note.

### Detail pane
- Fixed header: back link, product name, vendor and profile count.
- Per profile: creator credit (`Profile by <author>`, deliberately more prominent than
  OPRA's own attribution), measurement source link, preamp/band/shelf summary, response
  chart with a log-frequency axis (20 Hz - 20 kHz) and +12 / 0 / -12 dB grid labels, the
  "ideal peaking model, not measured on the device" disclosure, and the shelf-approximation
  error when it exceeds 3 dB.
- Controls: master level (-6..+6 dB, continuous) and an optional per-band trim list
  (0.5 dB steps, +/-6 dB, resettable), then `APPLY PROFILE`.

## Design decisions and the reasoning

- **One scrollable list instead of fixed header plus list.** The previous layout clipped
  content on short screens; a single `LazyColumn` makes every block reachable and removes
  the nested-scroll problem.
- **Selection is not application.** The companion service confirms attachment per audio
  session. Amber PENDING (#FFB74D) therefore means "staged", green APPLIED (#4CAF50) means
  "the renderer reported the plan attached". This satisfies the project rule that a failure
  to obtain a route must never be rendered as a fake active state.
- **Screen-local colour tokens.** The shared `AmberAccent` is an alias of the crimson brand
  colour and read as an error, so this screen defines `PendingAmber` and `AppliedGreen`.
- **Attribution is required, but it is not the content.** The OPRA licence requires the
  logo, description, licence link and a modification statement; none of that justifies
  taking half the screen, so the card is compact and the links live on one line.
- **Variant warning is advisory.** Grouping is a display heuristic in
  `OpraSearch.hasVariantFamilies` / `familyKey`; the dataset is never merged, renamed or
  filtered, and a wrong grouping can only add a warning.
- **Search ranking is unchanged** (exact/prefix/contains over name and vendor, all tokens
  must match) and stays Android-free so it is unit-tested.

## Verification performed

- `.\gradlew.bat testDebugUnitTest assembleDebug --no-daemon`: BUILD SUCCESSFUL,
  82 tests, 0 failures, 1 skipped (`OpraDatasetSmokeTest`, needs the full dump).
- Emulator `Medium_Phone_API_37.0` (1080x2400, 420 dpi): checked browse list, filtered
  search ("marshall"), detail pane for `Marshall MID ANC`, pending state after Apply, and
  the card height before/after (987 px to ~275 px).
- Release build (`assembleRelease`, 3.73 MB) installed fresh and exercised on the same
  emulator, so R8 minification does not break the screen or the dataset indexing.

## Known limits

- Nothing here validates the audible result. The preview is an analytic magnitude plot of
  the converted peaking filters at 44.1 kHz, not a measurement of the device.
- Android `DynamicsProcessing.EqBand` exposes no Q, so the platform path uses its own
  bandwidth; the UI states this and shows the shelving approximation error.
- The PENDING to APPLIED transition was only exercised against the emulator's companion
  service.
- Rows show at most 100 matches; refining the query is the documented way to narrow.

## File map

| Path | Role |
|------|------|
| `ui/screens/OpraScreen.kt` | Browse list, attribution card, search field, status strip, detail pane, chart |
| `opra/OpraSearch.kt` | Ranking plus `hasVariantFamilies` / `familyKey` |
| `opra/OpraRepository.kt` | Download, cache, index, selection state; early return when already `Ready` |
| `opra/OpraProfileConverter.kt` | OPRA profile to renderable plan, user adjustments |
| `res/values/strings.xml` | `opra_*` strings and the `opra_profile_count` plural |
| `res/drawable/ic_search.xml`, `ic_close.xml` | Search field icons |
| `test/.../OpraSearchTest.kt` | 10 tests including the variant-family cases |

## Build artefacts

- Debug: `app/build/outputs/apk/debug/app-debug.apk` (28.4 MB, unminified)
- Release: `app/build/outputs/apk/release/app-release.apk` (3.73 MB, debug-signed - fine for
  sideloading, not for distribution)
