package com.fxsound.audio.opra

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A1 calibration harness: how well can a peaking band stand in for an RBJ shelf?
 *
 * Both renderers expose peaking bands only (the native shelf sections are permanently
 * disabled; Android `DynamicsProcessing.EqBand` is cutoff+gain). This measures candidate
 * peaking (Q, gain-scale) pairs against real OPRA shelf shapes and reports the constants
 * with the lowest mean |dB| error, plus the worst case that must be disclosed.
 *
 * With `OPRA_DATASET_PATH` set the population is the real dataset; otherwise a
 * representative grid is used so the test still runs in CI.
 */
class ShelfApproximationCalibrationTest {

    private val fs = 44100.0

    // --- response evaluation ---------------------------------------------------

    private val freqs: DoubleArray = DoubleArray(61) { i ->
        20.0 * 1000.0.pow(i / 60.0 * (ln(20000.0 / 20.0) / ln(10.0)))
    }
    private val cw = DoubleArray(freqs.size) { cos(2.0 * PI * freqs[it] / fs) }
    private val sw = DoubleArray(freqs.size) { sin(2.0 * PI * freqs[it] / fs) }
    private val c2 = DoubleArray(freqs.size) { 2.0 * cw[it] * cw[it] - 1.0 }

    /** b0, b1, b2, a0, a1, a2 (un-normalised, as in the RBJ cookbook). */
    private fun responseDb(c: DoubleArray, i: Int): Double {
        val numRe = c[0] + c[1] * cw[i] + c[2] * c2[i]
        val numIm = -(c[1] * sw[i] + c[2] * 2.0 * sw[i] * cw[i])
        val denRe = c[3] + c[4] * cw[i] + c[5] * c2[i]
        val denIm = -(c[4] * sw[i] + c[5] * 2.0 * sw[i] * cw[i])
        val mag2 = (numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm)
        return 10.0 * ln(mag2.coerceAtLeast(1e-30)) / ln(10.0)
    }

    private fun shelfCoeffs(f0: Double, gainDb: Double, q: Double, low: Boolean): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val cosW = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val sq = sqrt(a)
        return if (low) {
            doubleArrayOf(
                a * ((a + 1) - (a - 1) * cosW + 2 * sq * alpha),
                2 * a * ((a - 1) - (a + 1) * cosW),
                a * ((a + 1) - (a - 1) * cosW - 2 * sq * alpha),
                (a + 1) + (a - 1) * cosW + 2 * sq * alpha,
                -2 * ((a - 1) + (a + 1) * cosW),
                (a + 1) + (a - 1) * cosW - 2 * sq * alpha
            )
        } else {
            doubleArrayOf(
                a * ((a + 1) + (a - 1) * cosW + 2 * sq * alpha),
                -2 * a * ((a - 1) + (a + 1) * cosW),
                a * ((a + 1) + (a - 1) * cosW - 2 * sq * alpha),
                (a + 1) - (a - 1) * cosW + 2 * sq * alpha,
                2 * ((a - 1) - (a + 1) * cosW),
                (a + 1) - (a - 1) * cosW - 2 * sq * alpha
            )
        }
    }

    private fun peakingCoeffs(f0: Double, gainDb: Double, q: Double): DoubleArray {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * f0 / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        return doubleArrayOf(
            1 + alpha * a, -2 * cosW, 1 - alpha * a,
            1 + alpha / a, -2 * cosW, 1 - alpha / a
        )
    }

    private fun meanAbsError(target: DoubleArray, approx: DoubleArray): Double {
        var sum = 0.0
        for (i in freqs.indices) sum += abs(responseDb(target, i) - responseDb(approx, i))
        return sum / freqs.size
    }

    // --- population ------------------------------------------------------------

    private data class Shelf(val f0: Double, val gain: Double, val q: Double, val low: Boolean)

    /** Real shelf bands from the full dataset, or null when it is not supplied. */
    private fun realShelves(): List<Shelf>? {
        val path = System.getenv("OPRA_DATASET_PATH") ?: return null
        val file = java.io.File(path)
        if (!file.exists()) return null

        val result = OpraJson.index(
            sequence {
                var off = 0L
                for (line in file.readLines()) {
                    yield(off to line)
                    off += line.toByteArray(Charsets.UTF_8).size + 1
                }
            }
        )
        val shelves = ArrayList<Shelf>(25_000)
        // One handle for the whole scan; readLine is ISO-8859-1 so bytes round-trip.
        java.io.RandomAccessFile(file, "r").use { raf ->
            for (entry in result.catalogue.entries.values) {
                for (summary in entry.summaries) {
                    val offset = result.profileOffsets[summary.id] ?: continue
                    raf.seek(offset)
                    val raw = raf.readLine() ?: continue
                    val profile = OpraJson.parseProfile(
                        String(raw.toByteArray(Charsets.ISO_8859_1), Charsets.UTF_8)
                    ) ?: continue
                    for (b in profile.bands) {
                        if (b.type == OpraBandType.LOW_SHELF || b.type == OpraBandType.HIGH_SHELF) {
                            shelves.add(
                                Shelf(
                                    b.frequency.toDouble(),
                                    b.gainDb.toDouble(),
                                    (b.q ?: 0.7f).toDouble(),
                                    b.type == OpraBandType.LOW_SHELF
                                )
                            )
                        }
                    }
                }
            }
        }
        return shelves
    }

    private fun representativeShelves(): List<Shelf> = buildList {
        for (low in listOf(true, false)) {
            for (f0 in listOf(60.0, 105.0, 200.0, 8000.0, 10000.0)) {
                for (q in listOf(0.5, 0.7, 1.0)) {
                    for (g in listOf(-10.0, -6.0, -3.0, 3.0, 6.0, 10.0)) {
                        add(Shelf(f0, g, q, low))
                    }
                }
            }
        }
    }

    /** Deterministic even-spaced subsample, so the grid search stays fast. */
    private fun sample(shelves: List<Shelf>, max: Int): List<Shelf> {
        if (shelves.size <= max) return shelves
        val step = shelves.size.toDouble() / max
        return List(max) { shelves[(it * step).toInt().coerceAtMost(shelves.lastIndex)] }
    }

    private fun meanErrorOn(shelves: List<Shelf>, q: Double, scale: Double): Double {
        var sum = 0.0
        for (s in shelves) {
            val target = shelfCoeffs(s.f0, s.gain, s.q, s.low)
            val approx = peakingCoeffs(s.f0, s.gain * scale, q)
            sum += meanAbsError(target, approx)
        }
        return sum / shelves.size
    }

    @Test
    fun measureBestShelfApproximation() {
        val real = realShelves()
        val shelves = real ?: representativeShelves()
        val searchSet = sample(shelves, if (real != null) 400 else shelves.size)

        println(
            "A1 population: ${shelves.size} shelf bands " +
                "(${if (real != null) "real dataset" else "representative"}), " +
                "search sample=${searchSet.size}"
        )

        var bestQ = 0.0
        var bestScale = 0.0
        var bestErr = Double.MAX_VALUE
        var q = 0.35
        while (q <= 1.6001) {
            var scale = 0.20
            while (scale <= 1.2001) {
                val err = meanErrorOn(searchSet, q, scale)
                if (err < bestErr) {
                    bestErr = err
                    bestQ = q
                    bestScale = scale
                }
                scale += 0.05
            }
            q += 0.05
        }
        println(
            String.format(
                Locale.ROOT,
                "A1 BEST single peaking: q=%.2f scale=%.2f meanAbsError=%.2f dB",
                bestQ, bestScale, bestErr
            )
        )

        // Report on the whole population, not just the search sample.
        val currentQ = OpraProfileConverter.SHELF_APPROX_Q.toDouble()
        val currentScale = OpraProfileConverter.SHELF_APPROX_GAIN_SCALE.toDouble()
        val bestMean = meanErrorOn(shelves, bestQ, bestScale)
        val currentMean = meanErrorOn(shelves, currentQ, currentScale)

        var worst = 0.0
        for (s in shelves) {
            val target = shelfCoeffs(s.f0, s.gain, s.q, s.low)
            val approx = peakingCoeffs(s.f0, s.gain * bestScale, bestQ)
            for (i in freqs.indices) {
                val e = abs(responseDb(target, i) - responseDb(approx, i))
                if (e > worst) worst = e
            }
        }
        println(
            String.format(
                Locale.ROOT,
                "A1 full population: best=%.2f dB mean / %.2f dB worst | " +
                    "current(q=%.2f,scale=%.2f)=%.2f dB mean",
                bestMean, worst, currentQ, currentScale, currentMean
            )
        )

        // Per-band error distribution: the mean hides profiles that render badly, so the
        // tail has to be reported (and disclosed) rather than averaged away.
        data class BandError(val shelf: Shelf, val mean: Double, val worst: Double)
        val perBand = shelves.map { s ->
            val target = shelfCoeffs(s.f0, s.gain, s.q, s.low)
            val approx = peakingCoeffs(s.f0, s.gain * bestScale, bestQ)
            var sum = 0.0
            var w = 0.0
            for (i in freqs.indices) {
                val e = abs(responseDb(target, i) - responseDb(approx, i))
                sum += e
                if (e > w) w = e
            }
            BandError(s, sum / freqs.size, w)
        }.sortedByDescending { it.mean }
        val sorted = perBand.map { it.mean }.sorted()
        fun pct(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        println(
            String.format(
                Locale.ROOT,
                "A1 per-band mean error: p50=%.2f p90=%.2f p99=%.2f max=%.2f dB " +
                    "| bands over 3 dB: %d / %d",
                pct(0.50), pct(0.90), pct(0.99), sorted.last(),
                perBand.count { it.mean > 3.0 }, perBand.size
            )
        )
        println(
            String.format(
                Locale.ROOT,
                "A1 shelf parameter range: f0=%.0f..%.0f Hz gain=%.1f..%.1f dB q=%.2f..%.2f",
                shelves.minOf { it.f0 }, shelves.maxOf { it.f0 },
                shelves.minOf { it.gain }, shelves.maxOf { it.gain },
                shelves.minOf { it.q }, shelves.maxOf { it.q }
            )
        )
        for (b in perBand.take(5)) {
            println(
                String.format(
                    Locale.ROOT,
                    "A1 worst band: %s f0=%.0f Hz gain=%.1f dB q=%.2f -> mean %.2f dB, worst %.2f dB",
                    if (b.shelf.low) "low_shelf " else "high_shelf",
                    b.shelf.f0, b.shelf.gain, b.shelf.q, b.mean, b.worst
                )
            )
        }

        // Two-band option: peaking at f0 and 2*f0 with split gain.
        var bestTwo: DoubleArray? = null // q1, s1, q2, s2, mean
        for (q1 in listOf(0.5, 0.7, 1.0, 1.4)) {
            for (s1 in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                for (q2 in listOf(0.5, 0.7, 1.0)) {
                    for (s2 in listOf(0.0, 0.25, 0.5, 0.75, 1.0)) {
                        var sum = 0.0
                        for (s in searchSet) {
                            val target = shelfCoeffs(s.f0, s.gain, s.q, s.low)
                            val c1 = peakingCoeffs(s.f0, s.gain * s1, q1)
                            val c2c = peakingCoeffs(s.f0 * 2.0, s.gain * s2, q2)
                            for (i in freqs.indices) {
                                val approx = responseDb(c1, i) + responseDb(c2c, i)
                                sum += abs(responseDb(target, i) - approx)
                            }
                        }
                        val mean = sum / (searchSet.size * freqs.size)
                        if (bestTwo == null || mean < bestTwo!![4]) {
                            bestTwo = doubleArrayOf(q1, s1, q2, s2, mean)
                        }
                    }
                }
            }
        }
        val two = bestTwo!!
        println(
            String.format(
                Locale.ROOT,
                "A1 BEST two-band: q1=%.2f s1=%.2f q2=%.2f s2=%.2f meanAbsError=%.2f dB",
                two[0], two[1], two[2], two[3], two[4]
            )
        )

        // Full-population tail for each candidate strategy. The mean is not the point: the
        // worst-rendered profiles are what users will actually hear.
        fun twoBandError(s: Shelf): Double {
            val target = shelfCoeffs(s.f0, s.gain, s.q, s.low)
            val c1 = peakingCoeffs(s.f0, s.gain * two[1], two[0])
            val c2c = peakingCoeffs(s.f0 * 2.0, s.gain * two[3], two[2])
            var sum = 0.0
            for (i in freqs.indices) {
                sum += abs(responseDb(target, i) - (responseDb(c1, i) + responseDb(c2c, i)))
            }
            return sum / freqs.size
        }

        fun report(label: String, errors: List<Double>) {
            val s = errors.sorted()
            fun p(p: Double) = s[((s.size - 1) * p).toInt()]
            println(
                String.format(
                    Locale.ROOT,
                    "A1 %s: mean=%.2f p50=%.2f p90=%.2f p99=%.2f max=%.2f dB, over3dB=%d/%d",
                    label, s.average(), p(0.50), p(0.90), p(0.99), s.last(),
                    errors.count { it > 3.0 }, errors.size
                )
            )
        }

        report("single-band best", perBand.map { it.mean })
        report("single-band best", shelves.map { s ->
            meanAbsError(shelfCoeffs(s.f0, s.gain, s.q, s.low), peakingCoeffs(s.f0, s.gain * bestScale, bestQ))
        })
        report("two-band best", shelves.map { twoBandError(it) })

        // Where does the error actually live? Split by |gain| and shelf type.
        for (minGain in listOf(0.0, 3.0, 6.0)) {
            val subset = shelves.filter { abs(it.gain) >= minGain }
            if (subset.isEmpty()) continue
            val single = subset.map { s ->
                meanAbsError(shelfCoeffs(s.f0, s.gain, s.q, s.low), peakingCoeffs(s.f0, s.gain * bestScale, bestQ))
            }
            val twob = subset.map { twoBandError(it) }
            println(
                String.format(
                    Locale.ROOT,
                    "A1 |gain|>=%.0f dB (n=%d, mean|gain|=%.1f): single mean=%.2f max=%.2f | two-band mean=%.2f max=%.2f",
                    minGain, subset.size, subset.map { abs(it.gain) }.average(),
                    single.average(), single.max(), twob.average(), twob.max()
                )
            )
        }

        // Ceiling for a single band: optimise (q, scale) per shelf instead of globally.
        val optimal = searchSet.map { s ->
            val target = shelfCoeffs(s.f0, s.gain, s.q, s.low)
            var bestE = Double.MAX_VALUE
            var qq = 0.35
            while (qq <= 1.6001) {
                var sc = 0.20
                while (sc <= 1.2001) {
                    val e = meanAbsError(target, peakingCoeffs(s.f0, s.gain * sc, qq))
                    if (e < bestE) bestE = e
                    sc += 0.05
                }
                qq += 0.05
            }
            bestE
        }
        println(
            String.format(
                Locale.ROOT,
                "A1 per-band-optimal single band (ceiling, sample=%d): mean=%.2f max=%.2f dB",
                searchSet.size, optimal.average(), optimal.max()
            )
        )

        // Guard rail: the mapping must stay a reasonable approximation, never exact.
        assertTrue("shelf approximation should stay within 6 dB mean error", bestMean <= 6.0)

        // The constants shipped in OpraProfileConverter must be the ones measured here.
        if (real != null) {
            org.junit.Assert.assertEquals(
                "SHELF_APPROX_Q drifted from the measured optimum", bestQ, currentQ, 0.001
            )
            org.junit.Assert.assertEquals(
                "SHELF_APPROX_GAIN_SCALE drifted from the measured optimum",
                bestScale, currentScale, 0.001
            )
        }
    }
}
