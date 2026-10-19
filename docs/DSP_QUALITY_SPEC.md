# DSP Quality Specification

## Objective
Make enhanced audio sound clean and remain stable. “No distortion” is not an absolute guarantee: source material, speaker/headphone limits, Bluetooth codecs and other OS effects can introduce distortion independently of the app.

## Signal path
Recommended conceptual path:
1. Validate input format and frame count.
2. Convert to internal processing format if required.
3. Apply pre-gain/headroom management.
4. Run the ported FxSound DSP in its intended order.
5. Apply additional safety processing only where justified and documented.
6. Measure peaks/metrics without blocking the audio thread.
7. Convert to output format with appropriate clipping/saturation handling.

Do not reorder upstream DSP stages without measurement and review.

## Gain and bass
- Do not add bass gain without evaluating the downstream peak increase.
- Provide pre-gain or automatic headroom reduction when a preset boosts frequencies.
- Prefer a conservative default preset.
- Parameterize maximum user gain and validate values.
- Avoid sudden coefficient changes; smooth or crossfade state where needed.
- Bass processing must not cause unstable filters or excessive low-frequency energy.
- A limiter is not a substitute for gain staging and may itself produce pumping or transient distortion if poorly configured.

## Numerical requirements
- Never allow NaN/Inf to propagate.
- Test silence, DC, impulse, sine sweeps, multitone, near-full-scale input and abrupt but valid parameter changes.
- Verify finite output and bounded gain for every supported configuration.
- Check denormal performance if relevant to the chosen DSP.
- Use safe handling for zero frames, invalid pointers, unsupported channel counts and stream restarts.

## Real-time requirements
- No heap allocations in the audio callback.
- No mutexes, blocking waits, file I/O, network calls, or UI callbacks in the audio callback.
- No synchronous log spam in the callback.
- Preallocate scratch buffers.
- Keep callback work bounded and monitor worst-case execution time.
- Publish parameters through a race-safe snapshot/queue.
- Avoid calling into Kotlin/Java from the native audio callback.
- Handle stream close/reopen without use-after-free.

## Output peak protection
- Monitor internal peak and final output peak.
- Use sufficient floating-point headroom where possible.
- Before integer output conversion, explicitly handle out-of-range samples.
- If adding a limiter, document its topology, lookahead/latency, attack/release and true-peak limitations.
- Never report “no clipping” unless verified at the actual final digital output.
- Do not claim to prevent physical speaker distortion.

## Proposed acceptance tests
- Silence in -> silence out within documented tolerance.
- Low-level sine -> expected frequency/gain response.
- Full-scale and near-full-scale signals -> finite bounded output.
- Bass-heavy multitone -> no unexpected runaway peak.
- Impulse/sweep -> stable and explainable response.
- Rapid slider/preset changes -> no discontinuity beyond documented threshold.
- 30-minute continuous test -> no audible dropout and no growing memory use.
- Compare bypass and processed paths at matched perceived loudness.

## Metrics
Record sample rate, channels, callback/frame size, underrun count if available, max callback duration, CPU, memory, input/output peak, RMS, and any route changes. Metrics must be gathered without introducing real-time blocking.
