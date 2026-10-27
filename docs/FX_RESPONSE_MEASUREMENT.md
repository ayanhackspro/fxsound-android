# FxSound DSP Response Measurement

The companion engine that enhances other apps (Spotify, Poweramp, and other music players)
drives Android's `DynamicsProcessing` effect. The mapping from the FxSound controls to that
effect was hand-authored guesswork. This document records the measurement harness that
produces the real numbers, what the measurements say, and the mapping that was derived from
them.

## Why this exists

Android will not let an app process another app's audio with the FxSound C++ code — that
would have to run inside `audioserver` on the source app's stream. The only legitimate route
is to attach Android's own audio effects to the source app's session and drive them. So the
quality of the Spotify path is entirely determined by how faithfully the FxSound controls are
translated into `DynamicsProcessing`.

Until now that translation was a set of guesses in `DynamicsProcessingTranslator.kt`:
clarity mapped to +1.5 dB at 1 kHz rising to +6.5 dB at 16 kHz, bass to three hand-picked
Pre-EQ bands, dynamic boost to a threshold of -8 to -14 dBFS with up to +4 dB of make-up.
Now that the native DSP actually runs (see `docs/DSP_PORT_AUDIT.md` section 9), the real
curves can be measured and the guesses replaced.

## The harness

`app/src/main/cpp/tests/measure_fx_response.cpp`, built and run by:

```bash
./gradlew fxResponseMeasure
```

It writes `docs/fx_response/fx_response_measurements.csv`. The task builds the tool with the
NDK, pushes it to the connected device and captures its output. Like `nativeTest`, it fails
loudly when no device is connected rather than reporting a result it did not observe.

### Method

- **Magnitude response.** For each effect, at every integer control setting, and with that
  effect alone active, a steady sine is played at each of 32 probe frequencies (20 Hz to
  20 kHz, including the ten ISO band centres the app's EQ uses). The output's fundamental is
  extracted with a **Goertzel filter** rather than an FFT, because the exciter adds harmonics
  and only the fundamental is of interest.
- **Level response.** For the dynamic stages, a 1 kHz tone is played at every input level from
  -48 to 0 dBFS and the output level is measured, which gives the gain reduction curve a
  compressor and a limiter produce.
- Each measurement settles for 0.77 s before the window is taken, and the magnitude is averaged
  over four consecutive windows (Welch averaging).
- The measurement is **deterministic**: two consecutive runs produce byte-identical output.

Two measurement bugs were found and fixed while building this, both of which produced
plausible-looking but meaningless numbers, so the checks below matter:

- The measurement window was written past the end of its buffer, and the settle length was not
  block-aligned. The reference curve came out as noise.
- The Goertzel filter was fed an **interleaved** stereo buffer, which halves the apparent
  frequency, because successive samples were L,R,L,R rather than consecutive points of the
  waveform. Every reading was wrong by a factor of two in frequency.

### Self-checks built into the harness

- A `REFERENCE` curve is measured with every effect off. It must be reproducible run to run.
- Each effect at setting 0 must reproduce the reference exactly. If it does not, the
  measurement is not isolating that effect.
- The incremental curves (effect minus reference) are the usable data, and they are smooth.

The reference curve is *not* flat — it has deep notches at several 1/3-octave band centres,
which are stable across runs and survived averaging. The reverb's wet mix was ruled out as the
cause.

**2026-10-27 investigation update:** the graphic EQ is now ruled out too. Three diagnostic
reference curves were added to the harness:
`REFERENCE_FLATEQ` (all 31 bands forced to 0 dB in every upstream state storage via a new
`DfxDsp::resetEQ()` path) and `REFERENCE_NOEQ` (the EQ stage bypassed entirely with
`eqOn(false)`). Both are byte-identical to the plain reference, so neither the band gains
nor the EQ stage explains the notches. A raw-passthrough check (`REFERENCE_RAW`, DSP power
off, the engine copying the input verbatim) reads 0.000 dB at every probe, so the tone
generator and the Goertzel extractor are correct. The notches therefore originate inside
the upstream kernel itself, in stages that remain active at zero settings — the pre-preset
graphic EQ hypothesis in the older text above is wrong, and this debt item is still open.

## What the measurements say

All values below are the effect's **incremental** contribution over the reference, in dB.

### Bass Boost — a bell centred near 100 Hz

| Frequency | 20 Hz | 50 Hz | 64 Hz | 80 Hz | 100 Hz | 125 Hz | 160 Hz | 250 Hz | 500 Hz | 1 kHz | 2 kHz and above |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Boost at setting 10 | +0.9 | +5.7 | +9.2 | +13.7 | **+13.9** | +9.4 | +5.8 | +2.5 | +0.6 | +0.2 | 0.0 |

A clean bell peaking at **+13.9 dB at 100 Hz** and falling to nothing by 1 kHz. The old
hand-authored translation applied at most +8.5 dB across three broad bands centred at
64/125/250 Hz — the right shape, but roughly **half the gain and the wrong centre**.

### Clarity (Fidelity) — presence and air with a mid scoop

| Frequency | 250 Hz | 500 Hz | 800 Hz | 1 kHz | 1.25 kHz | 1.6 kHz | 2 kHz | 4 kHz | 8 kHz | 16 kHz |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Setting 0 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 | 0.00 |
| Setting 5 | -0.14 | -0.63 | -1.48 | -1.48 | -0.12 | 1.65 | 2.81 | 5.42 | 5.82 | 5.91 |
| Setting 10 | -0.28 | -1.29 | -2.59 | -2.19 | +0.12 | +3.57 | +6.02 | +8.83 | +9.26 | +9.36 |

As the control rises: a presence-and-air shelf from about 1.6 kHz to 16 kHz opens up, and a
scoop around 800 Hz to 1 kHz deepens. That is what an aural exciter is supposed to do, and
it is not what the old translation did — that one added a monotonic high-shelf with no mid
scoop at all, so the companion engine sounded like a treble booster rather than FxSound's
exciter.

### Dynamic Boost — make-up gain plus a knee that falls as the control rises

Measured gain (dB) applied to a 1 kHz tone:

| Input level | -48 | -30 | -21 | -18 | -15 | -12 | -9 | -6 | 0 dBFS |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| Setting 0 | -5.3 | -5.3 | -5.3 | -5.3 | -5.3 | -5.3 | -5.1 | -5.1 | -5.8 |
| Setting 5 | +4.6 | +4.6 | +4.6 | +4.6 | +1.2 | -3.4 | -4.9 | -5.1 | -5.8 |
| Setting 10 | +6.5 | +6.5 | +6.5 | +4.7 | +1.2 | -3.4 | -4.9 | -5.1 | -5.8 |

Two clear findings:

- **The make-up gain** is +0 dB at setting 0, rising to **+11.8 dB** by setting 6, where it
  saturates. The old translation guessed at most +4 dB with a threshold of -8 to -14 dBFS.
- **Above the knee every setting converges on the same curve** — that is the upstream's shared
  limiter, not a compressor. The knee itself falls with the control, from -4 dBFS at setting 0
  to **-21 dBFS** at setting 6 and above, because more make-up pushes more of the material
  into the limiter.

The old translation's threshold was **4 to 10 dB too high**, which meant quiet passages got
lifted far more than FxSound lifts them and loud passages were held back too early.

## The mapping that was derived

`app/src/main/java/com/fxsound/audio/engine/FxSoundResponseTables.kt` holds the measured
tables, and `DynamicsProcessingTranslator` uses them:

| Control | Before (guessed) | Now (measured) |
| :--- | :--- | :--- |
| Bass Boost | +8.5 dB max, three hand-picked bands at 64/125/250 Hz | the measured bell, peaking at +9.4 dB on the 125 Hz band and +9.2 dB on the 64 Hz band, falling to zero by 2 kHz |
| Clarity | monotonic shelf, +1.5 dB at 1 kHz to +6.5 dB at 16 kHz | the measured presence-and-air shelf (+9.4 dB at 16 kHz) **with** the measured mid scoop (-2.2 dB at 1 kHz) |
| Dynamic Boost | threshold -8 to -14 dBFS, ratio up to 2.4, +4 dB make-up | measured make-up 0 to +11.8 dB, measured knee -4 to -21 dBFS, ratio 2.0 |

The tables are measured at every integer control setting, so they are exact where the sliders
land, and linear interpolation between them is accurate to well under a decibel.

`FxSoundResponseTablesTest` replays the committed CSV and asserts that the tables still
match it, so the mapping cannot drift away from its evidence.

The platform `Equalizer` fallback path, whose five bands sit at their own fixed centres, uses
the same measured clarity curve evaluated at those centres, interpolated in log frequency.

## What the measurements cannot tell us

- **Ambience.** A reverb has no magnitude signature to fit an EQ to; its measured curve is the
  reverb's own comb. Android has no usable reverb for another app's stream, so Ambience stays
  out of the external path and is labelled as in-app only.
- **Surround.** A stereo widener changes the left/right balance, not the spectrum, so a mono
  probe does not characterise it. It is mapped onto `Virtualizer` strength instead, and that
  mapping is an OEM-dependent approximation rather than a measured curve.
- **The exciter's harmonics.** FxSound's Clarity adds harmonic content as well as the
  magnitude curve above. Android's `DynamicsProcessing` has no exciter, so the *tone* can be
  matched but the *warmth* cannot. The measurement captures only the fundamental.
- **Band Q.** Android's `DynamicsProcessing` does not expose the Q of its EQ bands, so the
  *shape* between the band centres will differ from the upstream's filters even though the
  gains at the centres are exact.
- **The Dynamic Boost's above-knee curve.** The upstream's is a multi-band automatic
  maximiser whose output level is not even monotonic in the input level. A single compressor
  cannot reproduce that, so only the make-up gain and the knee are matched; the platform's
  output limiter handles the peak.

## Regenerating

```bash
./gradlew fxResponseMeasure
```

The CSV is committed so the mapping tables derived from it are auditable, and the measurement
is deterministic so a re-run must reproduce it exactly.
