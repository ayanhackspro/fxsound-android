# Third-party notice — OPRA headphone profiles

This app uses data from **OPRA (Open Profiles for Revealing Audio)**.

## Attribution

- **Project:** OPRA — Open Profiles for Revealing Audio
- **Source:** <https://github.com/opra-project/OPRA>
- **Database mirror used at runtime:** <http://opra.roonlabs.net/database_v1.jsonl>
  (the generated JSONL dump described in OPRA's own consuming guidance)
- **Data licence:** Creative Commons Attribution-ShareAlike 4.0 International
  (CC BY-SA 4.0) — <https://creativecommons.org/licenses/by-sa/4.0/>
- **OPRA source code licence:** MIT (this app does not copy OPRA code; it consumes the
  published dataset)
- **Individual profile creators:** each EQ profile in the dataset names its author
  (measured profiles come from, among others, AutoEQ, oratory1990 and Rtings/AutoEQ).
  The app displays the creator of the selected profile more prominently than OPRA itself,
  as the OPRA licence requires.

## Modifications made by this app

The published curves are **modified when they are converted for playback**:

1. **Shelving filters are approximated.** The renderers available on Android in this app
   support peaking bands only (Android `DynamicsProcessing.EqBand` exposes
   cutoff + gain; the native shelf sections are permanently disabled upstream). OPRA
   `low_shelf` and `high_shelf` bands are therefore rendered as peaking bands at the same
   corner frequency, with the calibrated constants Q = 1.45 and gain × 0.50. This is a
   measured approximation: across all 24,980 shelving bands in the dataset the mean
   deviation is 0.79 dB, but high-gain high shelves can deviate by several dB. Each
   converted profile carries its own worst-case deviation, which the app displays.
2. **Pass and band filters are omitted.** `low_pass`, `high_pass`, `band_pass` and
   `band_stop` bands cannot be rendered and are skipped; the count is disclosed in the
   app. (They occur in 5 bands of the whole dataset.)
3. **A user level trim may be added** to every band when the user moves the profile level
   control, and the OPRA preamp (`gain_db`) is folded into the app's input-gain stage
   rather than being applied as a separate EQ.

These modifications are indicated in the app on the headphone-tuning screen, in the
per-profile approximation note, and here.

## Distribution policy

The dataset is **not bundled in the APK**. It is downloaded at runtime from the mirror
above into app-private storage, versioned by HTTP ETag (`If-None-Match` revalidation), and
excluded from Android backup/transfer rules. The app does not re-share the data with other
apps. Bundling or redistributing the dataset (and any derived fixtures) remains subject to
CC BY-SA 4.0 and is only done for the small test fixture described below.

## Test fixture

`app/src/test/resources/opra_fixture.jsonl` contains **41 verbatim lines** (10 vendors,
12 products, 19 EQ profiles) copied unmodified from `database_v1.jsonl` on 2026-10-27,
used only to regression-test parsing and conversion on the JVM. It is distributed under
CC BY-SA 4.0 with the same attribution and modification notice as above. Product and
vendor names remain the property of their respective owners and are used only to identify
the measured devices.
