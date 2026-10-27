# Gain Staging and Output Limiter

Required by `docs/DSP_QUALITY_SPEC.md`: *"If adding a limiter, document its topology,
lookahead/latency, attack/release and true-peak limitations."*

This document covers the **companion engine** (the Android `AudioFx` chain that enhances other
apps' audio). The native C++ engine's gain behaviour is documented separately in
`docs/DSP_PORT_AUDIT.md` section 9, because that engine currently runs uncalibrated.

## Signal chain topology

The companion engine attaches Android's `DynamicsProcessing` effect to the audio session. Its
internal chain, in order, is:

```
input
  -> Input Gain            (preamp / headroom allocation)
  -> Pre-EQ                (10 bands; carries the HyperBass low-frequency contour)
  -> Multi-Band Compressor (4 bands: <250 Hz, 250-1000 Hz, 1-4 kHz, >4 kHz)
  -> Post-EQ               (10 bands; carries the user EQ and the Clarity exciter)
  -> Output Limiter
output
```

On top of that chain, and only when `DynamicsProcessing` is **not** available on the device's
audio HAL, the engine falls back to the platform `Equalizer` plus a standalone `BassBoost`.

## Gain staging

| Stage | Range | Source |
| :--- | :--- | :--- |
| Input gain | -12 dB to +6 dB | `masterGainDb` minus the headroom compensation |
| Pre-EQ bass contour | 0 to +8.5 dB (64 Hz), 0.75x that at 125 Hz, 0.40x at 250 Hz | `DynamicsProcessingTranslator.HYPERBASS_MAX_DB` |
| Multi-band compressor make-up | 0 to +4 dB | `dynamicBoost` |
| Post-EQ user band gain | -14 dB to +14 dB per band | the 10-band EQ |
| Post-EQ clarity contribution | +1.5 dB at 1 kHz rising to +6.5 dB at 16 kHz | `clarity` |

### Headroom compensation

`DynamicsProcessingTranslator.calculateHeadroomCompensationDb()` computes the preamp
attenuation:

```
bassPeakDb           = the measured Pre-EQ bass contour, peaking at +9.4 dB on the 125 Hz band
totalPeakPotential   = max(maxPositiveEqBoost, bassPeakDb) + (sumOfPositiveEqBoosts * 0.08)
headroomCompensation = clamp(totalPeakPotential * 0.35, 0 dB, 6 dB)
finalInputGain       = clamp(masterGainDb - headroomCompensation, -12 dB, +6 dB)
```

"PositiveEqBoosts" includes the clarity contribution, because it lives in the Post-EQ and is
part of the peak the preamp has to make room for. The headroom allowance is capped at 6 dB,
which is the largest attenuation the input-gain stage can apply without the chain losing too
much level.

### Measured Dynamic Boost

The upstream's Dynamic Boost is a multi-band automatic maximiser. Measured from the native
DSP (`docs/FX_RESPONSE_MEASUREMENT.md`):

| Control | Make-up gain | Knee |
| :--- | :--- | :--- |
| 0 | 0.0 dB | -4 dBFS |
| 2 | +2.1 dB | -13 dBFS |
| 4 | +5.9 dB | -16 dBFS |
| 6 and above | +11.8 dB | -21 dBFS |

The make-up gain is the dominant perceptual effect and is reproduced exactly. The knee falls
as the control rises, because more make-up pushes more of the material into the upstream's
shared limiter.

The companion engine applies the make-up as the `DynamicsProcessing` compressor's post-gain
and the knee as its threshold, with a ratio of 2.0. The upstream's above-knee curve is a
multi-band behaviour that a single compressor cannot reproduce, and Android does not expose
the Q of its EQ bands, so those parts remain approximations. The platform's output limiter
handles the peak.

### Single bass strategy

The Pre-EQ contour and the standalone `BassBoost` are **never active at the same time**. See
ADR-019. On the `DynamicsProcessing` path the Pre-EQ carries the bass and no `BassBoost` is
attached. On the `Equalizer` fallback path there is no Pre-EQ, so `BassBoost` carries the bass
instead, with its strength capped at 550 because that path has no input-gain stage and
therefore no way to compensate for the gain it adds.

## Output limiter

Configured in `DynamicsProcessingTranslator.LimiterSpec`:

| Parameter | Value | Note |
| :--- | :--- | :--- |
| Attack | 1.0 ms | Fast enough to catch a bass transient without being clicky. |
| Release | 120 ms | Studio mastering time constant. A faster release makes the whole broadband mix pump on every bass hit. |
| Ratio | 20:1 | A firm limiter rather than a true brickwall. |
| Threshold | -0.2 dBFS | Leaves a small cushion against inter-sample overs. |
| Post gain | 0 dB | The master gain is applied once, at the input. Applying it here as well would square it. |

### Limitations

These are properties of the platform effect, not of this project, and they are stated here
because the design spec requires them to be documented.

- **Lookahead: none.** The platform `DynamicsProcessing` limiter has no lookahead. It cannot
  anticipate a peak, so it catches the overs by acting on it. The 1 ms attack is the shortest
  the platform exposes, and even that is longer than the interval between the peak and the
  start of the distortion it is correcting.
- **True peak: not measured.** The limiter works on the sample stream, not on the
  inter-sample peak. A signal that peaks below 0 dBFS between two samples can still exceed
  0 dBFS after the DAC's reconstruction filter. The -0.2 dBFS threshold is a cushion against
  that, not a guarantee.
- **Bandwidth: the whole mix.** The limiter is a single broadband stage. Any gain reduction it
  applies is applied to every band, so a bass transient ducks the vocals and the cymbals with
  it. This is audible on headphones, where there is no room to mask it. It is the reason the
  bass strategy above matters: reducing the overshoot before the limiter is the fix, and
  softening the limiter is only a mitigation.
- **Latency: platform dependent.** The limiter runs inside `audioserver`. The chain as a whole
  adds latency that depends on the device's audio HAL and buffer size; the app does not
  control or measure it.

## Native C++ engine (reference player)

`docs/DSP_PORT_AUDIT.md` section 9 previously recorded that this engine ran uncalibrated.
It now has two protections, implemented under ADR-025 and verified by the new worst-case
native test:

1. **Static headroom pre-gain.** At parameter-sync time, the upstream master gain is set
   to the user's master gain **minus** the worst-case positive gain the boosted stages can
   add: the Bass contour (measured +13.9 dB at setting 10), the Dynamic Boost's make-up
   (measured up to +11.8 dB), the largest positive EQ band plus 8% of the summed positive
   bands, and any positive user master gain. This caps the attenuation at 30 dB. Because
   it is computed from the smoothed settings, it never jumps; the master ramp glides it.
2. **Output peak catcher.** After every other stage, a limiter holds the final sample
   stream at **-0.026 dBFS**: instantaneous attack (the required gain is applied the
   moment a sample needs it) and a one-pole 120 ms release. No lookahead; it acts on the
   peak, not before it.

`fxResponseMeasure` runs with the pre-gain off (`setHeadroomManagementEnabled(false)`) so
the mapping data isolates the upstream kernels; the limiter itself remains active there.

### Limitations

- **Lookahead: none.** Same platform-independent property as above: the limiter catches
  a peak by acting on it, so there can be a momentary overshoot within one sample's worth
  of gain correction before the attack settles.
- **True peak: not measured.** The limiter works on the sample stream; inter-sample peaks
  are not reconstructed (the -0.026 dBFS ceiling is a cushion).
- **Heavy sustained boosting below the 30 dB cap** still relies on the limiter for the
  residual, so long-duration boosts into near-full-scale bass can produce gain reduction
  that moves with the material.

## What is not claimed

- The app does **not** claim to prevent clipping at the physical speaker or headphone output.
  The limiter bounds the digital signal; the transducer can still distort.
- The app does **not** report "no clipping" unless the measured final output peak says so.
  The `isClipping` flag comes from the measured output peak of the native engine, and the
  companion engine does not expose output samples at all, so it reports no clipping state for
  external sessions.
