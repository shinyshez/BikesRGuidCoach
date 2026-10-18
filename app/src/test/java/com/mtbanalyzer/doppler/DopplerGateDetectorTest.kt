package com.mtbanalyzer.doppler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DopplerGateDetectorTest {

    private val rate = 48_000
    private val c = DopplerGateDetector.SPEED_OF_SOUND

    /** A rider carrying an 18 kHz tone past the mic on a straight line, lateral distance [d]. */
    private data class Ride(val passSec: Double, val speed: Double, val d: Double) {
        fun position(t: Double) = speed * (t - passSec)
    }

    /**
     * Mic signal for [rides] (one at a time, in order), solving for each sample's emission
     * time so the Doppler shift and its d/c delay come out of the geometry, not a formula.
     */
    private fun synth(
        seconds: Double,
        rides: List<Ride>,
        toneHz: Double = 18_000.0,
        noise: Double = 0.01,
        seed: Long = 1,
    ): ShortArray {
        val rnd = Random(seed)
        return ShortArray((seconds * rate).toInt()) { i ->
            val t = i.toDouble() / rate
            val ride = rides.minByOrNull { abs(it.passSec - t) }
            var s = 0.0
            if (ride != null) {
                var tau = t
                repeat(4) {
                    val x = ride.position(tau)
                    tau = t - sqrt(x * x + ride.d * ride.d) / c
                }
                val x = ride.position(tau)
                val r = sqrt(x * x + ride.d * ride.d)
                s = 0.3 / r.coerceAtLeast(1.0) * sin(2 * PI * toneHz * tau)
            }
            s += noise * rnd.nextGaussian()
            (s.coerceIn(-1.0, 1.0) * 32767).toInt().toShort()
        }
    }

    private fun run(pcm: ShortArray): List<DopplerGateDetector.Pass> {
        val passes = mutableListOf<DopplerGateDetector.Pass>()
        val detector = DopplerGateDetector(rate)
        detector.listener = object : DopplerGateDetector.Listener {
            override fun onPass(pass: DopplerGateDetector.Pass) { passes += pass }
        }
        // Feed in AudioRecord-sized chunks, as the activity does.
        var i = 0
        while (i < pcm.size) {
            val n = minOf(960, pcm.size - i)
            detector.process(pcm, i, n)
            i += n
        }
        return passes
    }

    @Test
    fun riderAt10mps_passTimedToTheMillisecond() {
        val ride = Ride(passSec = 3.0, speed = 10.0, d = 2.0)
        val passes = run(synth(6.0, listOf(ride)))

        assertEquals(1, passes.size)
        val expected = ride.passSec + ride.d / c
        assertEquals(expected, passes[0].timeSec, 0.002)
        assertEquals(10.0, passes[0].speedMps, 1.0)
        assertTrue("confirmed promptly", passes[0].confirmedAtSec - passes[0].timeSec < 0.15)
    }

    @Test
    fun walkingPastAtArmsLength_isStillAPass() {
        val ride = Ride(passSec = 4.0, speed = 1.5, d = 1.0)
        val passes = run(synth(8.0, listOf(ride)))

        assertEquals(1, passes.size)
        assertEquals(ride.passSec + ride.d / c, passes[0].timeSec, 0.015)
    }

    @Test
    fun twoPasses_giveAnAccurateSplit() {
        val first = Ride(passSec = 2.5, speed = 8.0, d = 3.0)
        val second = Ride(passSec = 7.25, speed = 12.0, d = 3.0)
        val passes = run(synth(10.0, listOf(first, second)))

        assertEquals(2, passes.size)
        assertEquals(4.75, passes[1].timeSec - passes[0].timeSec, 0.003)
    }

    @Test
    fun stationaryToneOffByClockSkew_neverPasses() {
        val rnd = Random(2)
        val pcm = ShortArray(rate * 6) { i ->
            val s = 0.05 * sin(2 * PI * 18_004.0 * i / rate) + 0.01 * rnd.nextGaussian()
            (s * 32767).toInt().toShort()
        }
        assertTrue(run(pcm).isEmpty())
    }

    /**
     * Real noise lets a stray frame through now and then. A stray "high" and a stray "low"
     * well over a second apart, with nothing heard between, used to throw from maxOf on the
     * audio thread and take the app down. It must not crash, and it is not a pass.
     */
    @Test
    fun strayBlipsFarApart_doNotCrashOrPass() {
        val rnd = Random(3)
        val pcm = ShortArray(rate * 4) { i ->
            val t = i.toDouble() / rate
            val hz = when {
                t in 1.0..1.06 -> 18_300.0
                t in 2.6..2.66 -> 17_960.0
                else -> 0.0
            }
            val s = (if (hz > 0) 0.2 * sin(2 * PI * hz * t) else 0.0) +
                0.001 * rnd.nextGaussian()
            (s * 32767).toInt().toShort()
        }
        assertTrue(run(pcm).isEmpty())
    }

    @Test
    fun noiseAlone_neverPasses() {
        assertTrue(run(synth(6.0, emptyList(), noise = 0.2)).isEmpty())
    }
}
