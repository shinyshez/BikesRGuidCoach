package com.mtbanalyzer.doppler

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Finds the moment a constant tone's source passes the microphone, from its Doppler shift.
 *
 * Approaching, the tone arrives above [toneHz]; receding, below. At closest approach the shift
 * crosses zero, and that is also where it changes fastest (slope ≈ f0·v²/(c·d)), so the
 * crossing is a sharp timestamp where loudness would be a flat peak. A pass is reported once
 * the shift has gone from above +[thresholdHz] to below −[thresholdHz]; its time is the
 * zero crossing, refined by a line fit, not the moment it was confirmed.
 *
 * With the tone on the moving rider the crossing reaches the mic d/c after closest approach
 * (≈6 ms at 2 m). That is constant for a given gate, so it cancels in a split.
 *
 * Pure Kotlin, no Android types: feed it PCM through [process]. Not thread-safe.
 */
class DopplerGateDetector(
    val sampleRate: Int,
    val toneHz: Double = 18_000.0,
    /** ±Hz searched around the tone. 1000 Hz covers ~19 m/s at 18 kHz. */
    searchHz: Double = 1_000.0,
    /** Shift either side of zero a pass has to reach. 25 Hz ≈ 0.5 m/s, so a walk counts. */
    val thresholdHz: Double = 25.0,
    /** Tone must stand this far above the band's median to count as heard. */
    val minSnrDb: Double = 12.0,
    val windowSize: Int = 2048,
    val hopSize: Int = sampleRate / 100,
) {

    /** One analysis window. [shiftHz] is relative to the learned baseline; NaN when unheard. */
    data class Frame(val timeSec: Double, val shiftHz: Double, val snrDb: Double) {
        val heard: Boolean get() = !shiftHz.isNaN()
    }

    data class Pass(
        /** Zero crossing, seconds since the first sample. */
        val timeSec: Double,
        /** When the receding side confirmed it; [confirmedAtSec] − [timeSec] is the latency. */
        val confirmedAtSec: Double,
        /** Largest approach shift seen in the second before the crossing. */
        val approachHz: Double,
        /** Speed implied by [approachHz]: a lower bound, exact only far from the gate. */
        val speedMps: Double,
    )

    interface Listener {
        fun onFrame(frame: Frame) {}
        fun onPass(pass: Pass) {}
    }

    var listener: Listener? = null

    /** Received frequency of the tone at rest. Learned slowly, so clock skew between phones drops out. */
    var baselineHz: Double = toneHz
        private set

    private val binHz = sampleRate.toDouble() / windowSize
    private val loBin = ((toneHz - searchHz) / binHz).toInt().coerceAtLeast(1)
    private val hiBin = ((toneHz + searchHz) / binHz).roundToInt().coerceAtMost(windowSize / 2 - 1)
    private val hann = FloatArray(windowSize) { (0.5 - 0.5 * cos(2 * PI * it / (windowSize - 1))).toFloat() }
    private val goertzelCoeff = DoubleArray(hiBin - loBin + 1) { 2 * cos(2 * PI * (loBin + it) / windowSize) }

    private val ring = FloatArray(windowSize)
    private var ringPos = 0
    private var totalSamples = 0L
    private var sinceHop = 0
    private val work = FloatArray(windowSize)
    private val power = DoubleArray(hiBin - loBin + 1)
    private val sorted = DoubleArray(power.size)

    private val history = ArrayDeque<Frame>()
    private var lastHighSec = Double.NEGATIVE_INFINITY

    fun process(samples: ShortArray, offset: Int = 0, length: Int = samples.size) {
        for (i in offset until offset + length) {
            ring[ringPos] = samples[i] / 32768f
            ringPos = (ringPos + 1) % windowSize
            totalSamples++
            if (++sinceHop >= hopSize && totalSamples >= windowSize) {
                sinceHop = 0
                analyse()
            }
        }
    }

    fun reset() {
        ring.fill(0f); ringPos = 0; totalSamples = 0; sinceHop = 0
        history.clear(); lastHighSec = Double.NEGATIVE_INFINITY; baselineHz = toneHz
    }

    private fun analyse() {
        for (i in 0 until windowSize) work[i] = ring[(ringPos + i) % windowSize] * hann[i]
        // Goertzel over the search band only: ~90 bins, far cheaper than a full FFT.
        var peak = 0
        for (b in power.indices) {
            val c = goertzelCoeff[b]
            var s1 = 0.0; var s2 = 0.0
            for (x in work) { val s0 = x + c * s1 - s2; s2 = s1; s1 = s0 }
            power[b] = s1 * s1 + s2 * s2 - c * s1 * s2
            if (power[b] > power[peak]) peak = b
        }
        power.copyInto(sorted); sorted.sort()
        val noise = max(sorted[sorted.size / 2], 1e-12)
        val snrDb = 10 * log10(max(power[peak], 1e-12) / noise)
        val timeSec = (totalSamples - windowSize / 2.0) / sampleRate

        val frame = if (snrDb < minSnrDb || peak == 0 || peak == power.lastIndex) {
            Frame(timeSec, Double.NaN, snrDb)
        } else {
            // Parabolic interpolation on log power: within ~1 Hz with a Hann window.
            val a = log10(max(power[peak - 1], 1e-12))
            val b = log10(power[peak])
            val g = log10(max(power[peak + 1], 1e-12))
            val delta = 0.5 * (a - g) / (a - 2 * b + g)
            val hz = (loBin + peak + delta) * binHz
            val shift = hz - baselineHz
            if (abs(shift) < BASELINE_GATE_HZ) baselineHz += BASELINE_ALPHA * shift
            Frame(timeSec, shift, snrDb)
        }
        history.addLast(frame)
        while (history.first().timeSec < timeSec - HISTORY_SEC) history.removeFirst()
        listener?.onFrame(frame)
        if (frame.heard) track(frame)
    }

    private fun track(frame: Frame) {
        if (frame.shiftHz > thresholdHz) lastHighSec = frame.timeSec
        if (frame.shiftHz >= -thresholdHz || frame.timeSec - lastHighSec > APPROACH_MEMORY_SEC) return

        val heard = history.filter { it.heard && it.timeSec >= lastHighSec }
        // The last place the shift went from ≥0 to <0 between the approach and now.
        var cross = -1
        for (i in heard.lastIndex downTo 1) {
            if (heard[i - 1].shiftHz >= 0 && heard[i].shiftHz < 0) { cross = i; break }
        }
        lastHighSec = Double.NEGATIVE_INFINITY // one pass per approach
        if (cross < 0) return

        val p = heard[cross - 1]; val q = heard[cross]
        // A real pass sweeps through zero while the tone is heard. A gap here means two
        // unrelated frames (stray noise peaks, or the tone dropping out) either side of it.
        if (q.timeSec - p.timeSec > MAX_CROSS_GAP_SEC) return
        val rough = p.timeSec + (q.timeSec - p.timeSec) * p.shiftHz / (p.shiftHz - q.shiftHz)
        val timeSec = fitCrossing(heard.filter { abs(it.timeSec - rough) <= FIT_HALF_WIDTH_SEC })
            ?.takeIf { it in p.timeSec..q.timeSec }
            ?: rough

        val approachHz = history
            .filter { it.heard && it.timeSec in (timeSec - 1.0)..timeSec }
            .maxOfOrNull { it.shiftHz } ?: p.shiftHz
        listener?.onPass(
            Pass(timeSec, frame.timeSec, approachHz, SPEED_OF_SOUND * approachHz / toneHz)
        )
    }

    /** Least-squares line through the points, solved for zero; null if it isn't falling. */
    private fun fitCrossing(points: List<Frame>): Double? {
        if (points.size < 3) return null
        val t0 = points.first().timeSec
        val n = points.size
        val mt = points.sumOf { it.timeSec - t0 } / n
        val mf = points.sumOf { it.shiftHz } / n
        var sxy = 0.0; var sxx = 0.0
        for (pt in points) {
            val dt = pt.timeSec - t0 - mt
            sxy += dt * (pt.shiftHz - mf); sxx += dt * dt
        }
        if (sxx == 0.0) return null
        val slope = sxy / sxx
        if (slope >= 0) return null
        return t0 + mt - mf / slope
    }

    companion object {
        const val SPEED_OF_SOUND = 343.0
        private const val HISTORY_SEC = 4.0
        private const val APPROACH_MEMORY_SEC = 3.0
        private const val FIT_HALF_WIDTH_SEC = 0.03
        private const val MAX_CROSS_GAP_SEC = 0.1
        private const val BASELINE_GATE_HZ = 8.0
        private const val BASELINE_ALPHA = 0.02
    }
}
