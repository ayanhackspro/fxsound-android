package com.fxsound.audio.engine

import com.fxsound.audio.model.EQ_FREQUENCIES

/**
 * The measured response of the upstream FxSound DSP, and the mapping of that response onto
 * Android's `DynamicsProcessing` effect.
 *
 * The companion engine that enhances other apps (Spotify, Poweramp, and other music players)
 * cannot run the FxSound C++ core on another app's stream, so the quality of that path is
 * entirely determined by how faithfully the FxSound controls are translated into
 * `DynamicsProcessing`. That translation used to be hand-authored guesswork.
 *
 * Every number below is measured, not guessed. The data comes from
 * `app/src/main/cpp/tests/measure_fx_response.cpp`, which renders a steady sine through the
 * authentic upstream DSP and extracts the fundamental with a Goertzel filter. The committed
 * measurements are in `docs/fx_response/fx_response_measurements.csv`, and
 * `docs/FX_RESPONSE_MEASUREMENT.md` documents the method. Regenerate with:
 *
 *     ./gradlew fxResponseMeasure
 *
 * The values are the effect's *incremental* contribution over the DSP's own reference state,
 * because the companion engine starts from a flat `DynamicsProcessing` chain. They were
 * measured at every integer control setting, so the tables are exact where the app's sliders
 * land and linear interpolation between them is accurate to well under a decibel.
 *
 * A unit test (`FxSoundResponseTablesTest`) replays the committed CSV and asserts that these
 * tables still match it, so the mapping cannot drift away from its evidence.
 */
object FxSoundResponseTables {

    /**
     * The ISO band centres the tables were measured at, in hertz. The app's ten-band EQ
     * uses the same centres.
     */
    private val BAND_FREQUENCIES_HZ = DoubleArray(EQ_FREQUENCIES.size) { EQ_FREQUENCIES[it].toDouble() }

    /**
     * Bass boost, in dB per band, at control settings 0 to 10.
     *
     * The upstream's HyperBass is a bell peaking at about +13.8 dB near 100 Hz that falls to
     * nothing by 1 kHz. The old translation applied at most +8.5 dB across three broad bands
     * centred at 64, 125 and 250 Hz.
     */
    private val BASS_BANDS_DB = arrayOf(
        floatArrayOf(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.04f, 0.38f, 0.41f, 0.04f, 0.01f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.08f, 0.74f, 0.78f, 0.08f, 0.02f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.13f, 1.14f, 1.20f, 0.12f, 0.03f, 0.01f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.18f, 1.56f, 1.62f, 0.17f, 0.04f, 0.01f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.35f, 2.65f, 2.75f, 0.34f, 0.07f, 0.02f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.57f, 3.76f, 3.89f, 0.56f, 0.12f, 0.03f, 0.01f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.89f, 5.06f, 5.22f, 0.89f, 0.20f, 0.05f, 0.01f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(1.32f, 6.44f, 6.60f, 1.31f, 0.30f, 0.07f, 0.02f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(1.80f, 7.75f, 7.92f, 1.81f, 0.44f, 0.11f, 0.03f, 0.01f, 0.00f, 0.00f),
        floatArrayOf(2.47f, 9.20f, 9.38f, 2.47f, 0.64f, 0.16f, 0.04f, 0.01f, 0.00f, 0.00f)
    )

    /**
     * Clarity, in dB per band, at control settings 0 to 10.
     *
     * The upstream's aural exciter opens a presence-and-air shelf from 2.5 kHz to 16 kHz
     * (+9.4 dB at the top) while scooping 500 Hz to 1 kHz (-2.2 dB at 1 kHz). The old
     * translation applied a monotonic high-shelf with no mid scoop at all, so the companion
     * engine sounded like a treble booster rather than an exciter.
     */
    private val FIDELITY_BANDS_DB = arrayOf(
        floatArrayOf(0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f, 0.00f),
        floatArrayOf(0.00f, 0.00f, 0.00f, -0.03f, -0.12f, -0.33f, 0.36f, 1.31f, 1.52f, 1.56f),
        floatArrayOf(0.00f, 0.00f, -0.01f, -0.05f, -0.24f, -0.63f, 0.84f, 2.43f, 2.73f, 2.79f),
        floatArrayOf(0.01f, 0.00f, -0.01f, -0.08f, -0.37f, -0.93f, 1.45f, 3.52f, 3.88f, 3.96f),
        floatArrayOf(0.01f, 0.00f, -0.01f, -0.11f, -0.50f, -1.22f, 2.12f, 4.52f, 4.90f, 4.99f),
        floatArrayOf(0.01f, 0.01f, -0.02f, -0.14f, -0.63f, -1.48f, 2.81f, 5.42f, 5.82f, 5.91f),
        floatArrayOf(0.01f, 0.01f, -0.02f, -0.17f, -0.75f, -1.70f, 3.46f, 6.19f, 6.59f, 6.68f),
        floatArrayOf(0.01f, 0.01f, -0.02f, -0.20f, -0.89f, -1.89f, 4.14f, 6.95f, 7.35f, 7.45f),
        floatArrayOf(0.02f, 0.01f, -0.03f, -0.23f, -1.02f, -2.04f, 4.81f, 7.64f, 8.05f, 8.15f),
        floatArrayOf(0.02f, 0.01f, -0.03f, -0.25f, -1.15f, -2.14f, 5.40f, 8.24f, 8.65f, 8.75f),
        floatArrayOf(0.02f, 0.01f, -0.03f, -0.28f, -1.29f, -2.19f, 6.02f, 8.83f, 9.26f, 9.36f)
    )

    /**
     * The make-up gain the upstream's maximiser applies below its knee, in dB, at control
     * settings 0 to 10. It saturates at 11.8 dB, which is where the upstream's maximiser
     * runs out of headroom.
     */
    private val DYNAMIC_MAKE_UP_DB = floatArrayOf(
        0.00f, 0.88f, 2.10f, 3.71f, 5.87f, 9.91f, 11.80f, 11.80f, 11.80f, 11.80f, 11.80f
    )

    /**
     * The input level, in dBFS, at which the upstream's maximiser starts bending, at control
     * settings 0 to 10. The knee falls as the control rises, because more make-up pushes more
     * of the material into the limiter.
     */
    private val DYNAMIC_KNEE_DB = floatArrayOf(
        -4f, -11f, -13f, -14f, -16f, -20f, -21f, -21f, -21f, -21f, -21f
    )

    /**
     * Linear interpolation across the 0-10 control range, for a per-band table.
     */
    private fun interpolate(table: Array<FloatArray>, value: Float): FloatArray {
        val clamped = value.coerceIn(0f, 10f)
        val lower = clamped.toInt().coerceIn(0, table.size - 2)
        val fraction = clamped - lower
        val a = table[lower]
        val b = table[lower + 1]
        return FloatArray(a.size) { index -> a[index] + (b[index] - a[index]) * fraction }
    }

    /**
     * Linear interpolation across the 0-10 control range, for a scalar table.
     */
    private fun interpolate(table: FloatArray, value: Float): Float {
        val clamped = value.coerceIn(0f, 10f)
        val lower = clamped.toInt().coerceIn(0, table.size - 2)
        val fraction = clamped - lower
        return table[lower] + (table[lower + 1] - table[lower]) * fraction
    }

    /** The measured bass boost, in dB, for each of the ten ISO bands. */
    fun bassBandGainsDb(bass: Float): FloatArray = interpolate(BASS_BANDS_DB, bass)

    /** The measured clarity contribution, in dB, for each of the ten ISO bands. */
    fun fidelityBandGainsDb(clarity: Float): FloatArray = interpolate(FIDELITY_BANDS_DB, clarity)

    /** The measured make-up gain, in dB, that the upstream's maximiser applies. */
    fun dynamicMakeUpDb(dynamicBoost: Float): Float = interpolate(DYNAMIC_MAKE_UP_DB, dynamicBoost)

    /** The measured level, in dBFS, at which the upstream's maximiser starts bending. */
    fun dynamicKneeDb(dynamicBoost: Float): Float = interpolate(DYNAMIC_KNEE_DB, dynamicBoost)

    /**
     * The measured bass response at an arbitrary frequency, in dB.
     *
     * Used by the Equalizer fallback path, whose five bands sit at their own fixed centres
     * rather than at the ISO ones. The band table is interpolated in log frequency, which is
     * how a filter's response behaves.
     */
    fun bassGainDbAt(bass: Float, frequencyHz: Float): Float =
        interpolateAtFrequency(bassBandGainsDb(bass), frequencyHz)

    /**
     * The measured clarity response at an arbitrary frequency, in dB.
     */
    fun fidelityGainDbAt(clarity: Float, frequencyHz: Float): Float =
        interpolateAtFrequency(fidelityBandGainsDb(clarity), frequencyHz)

    /**
     * Interpolates a band table in log frequency between the ISO band centres.
     */
    private fun interpolateAtFrequency(bandsDb: FloatArray, frequencyHz: Float): Float {
        val frequencies = BAND_FREQUENCIES_HZ
        if (frequencyHz <= frequencies.first()) return bandsDb.first()
        if (frequencyHz >= frequencies.last()) return bandsDb.last()

        for (i in 0 until frequencies.size - 1) {
            if (frequencyHz >= frequencies[i] && frequencyHz <= frequencies[i + 1]) {
                val low = kotlin.math.log10(frequencies[i].toDouble())
                val high = kotlin.math.log10(frequencies[i + 1].toDouble())
                val position = kotlin.math.log10(frequencyHz.toDouble())
                val fraction = ((position - low) / (high - low)).toFloat()
                return bandsDb[i] + (bandsDb[i + 1] - bandsDb[i]) * fraction
            }
        }
        return 0.0f
    }
}
