package com.mtbanalyzer.tuning

import android.net.Uri
import com.mtbanalyzer.detector.RiderDetector

/**
 * Result of detection for a single frame
 */
data class DetectionFrameResult(
    val frameIndex: Int,
    val timestampMs: Long,
    val riderDetected: Boolean,
    val confidence: Double,
    val debugInfo: String,
    val processingTimeMs: Long
) {
    companion object {
        fun fromDetectionResult(
            frameIndex: Int,
            timestampMs: Long,
            result: RiderDetector.DetectionResult,
            processingTimeMs: Long
        ) = DetectionFrameResult(
            frameIndex = frameIndex,
            timestampMs = timestampMs,
            riderDetected = result.riderDetected,
            confidence = result.confidence,
            debugInfo = result.debugInfo,
            processingTimeMs = processingTimeMs
        )
    }
}

/**
 * A complete detection session representing a full run through a video
 */
data class DetectionSession(
    val id: String = java.util.UUID.randomUUID().toString(),
    val videoUri: Uri,
    val detectorType: String,
    val parameters: Map<String, Any>,
    val results: List<DetectionFrameResult>,
    val totalFrames: Int,
    val processedFrames: Int,
    val createdAt: Long = System.currentTimeMillis()
) {
    val avgProcessingTimeMs: Double
        get() = if (results.isNotEmpty()) {
            results.map { it.processingTimeMs }.average()
        } else 0.0

    val detectionRate: Double
        get() = if (results.isNotEmpty()) {
            results.count { it.riderDetected }.toDouble() / results.size
        } else 0.0

    val avgConfidence: Double
        get() = if (results.isNotEmpty()) {
            results.map { it.confidence }.average()
        } else 0.0
}

/**
 * Ground truth interval marking when a rider is present
 */
data class GroundTruthInterval(
    val startMs: Long,
    val endMs: Long,
    val label: Boolean = true // true = rider present, false = no rider
)

/**
 * Ground truth for a video
 */
data class GroundTruth(
    val videoUri: Uri,
    val intervals: List<GroundTruthInterval> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis()
) {
    /**
     * Check if a rider is present at the given timestamp
     */
    fun isRiderPresentAt(timestampMs: Long): Boolean {
        return intervals.any { interval ->
            interval.label && timestampMs >= interval.startMs && timestampMs <= interval.endMs
        }
    }

    /**
     * Add or modify an interval
     */
    fun withInterval(interval: GroundTruthInterval): GroundTruth {
        // Remove overlapping intervals
        val filtered = intervals.filter { existing ->
            existing.endMs < interval.startMs || existing.startMs > interval.endMs
        }
        return copy(
            intervals = (filtered + interval).sortedBy { it.startMs },
            modifiedAt = System.currentTimeMillis()
        )
    }

    /**
     * Remove interval containing the timestamp
     */
    fun removeIntervalAt(timestampMs: Long): GroundTruth {
        return copy(
            intervals = intervals.filter { !(timestampMs >= it.startMs && timestampMs <= it.endMs) },
            modifiedAt = System.currentTimeMillis()
        )
    }
}

/**
 * Benchmark result comparing detection against ground truth
 */
data class BenchmarkResult(
    val truePositives: Int,
    val falsePositives: Int,
    val trueNegatives: Int,
    val falseNegatives: Int,
    val avgDetectionLatencyMs: Double = 0.0,
    val minDetectionLatencyMs: Long = 0,
    val maxDetectionLatencyMs: Long = 0
) {
    val precision: Double
        get() = if (truePositives + falsePositives > 0) {
            truePositives.toDouble() / (truePositives + falsePositives)
        } else 0.0

    val recall: Double
        get() = if (truePositives + falseNegatives > 0) {
            truePositives.toDouble() / (truePositives + falseNegatives)
        } else 0.0

    val f1Score: Double
        get() = if (precision + recall > 0) {
            2 * (precision * recall) / (precision + recall)
        } else 0.0

    val accuracy: Double
        get() {
            val total = truePositives + falsePositives + trueNegatives + falseNegatives
            return if (total > 0) {
                (truePositives + trueNegatives).toDouble() / total
            } else 0.0
        }

    val totalSamples: Int
        get() = truePositives + falsePositives + trueNegatives + falseNegatives

    companion object {
        /**
         * Calculate benchmark from detection results and ground truth
         */
        fun calculate(
            results: List<DetectionFrameResult>,
            groundTruth: GroundTruth
        ): BenchmarkResult {
            var tp = 0
            var fp = 0
            var tn = 0
            var fn = 0
            val latencies = mutableListOf<Long>()

            for (result in results) {
                val actualRiderPresent = groundTruth.isRiderPresentAt(result.timestampMs)
                val predictedRiderPresent = result.riderDetected

                when {
                    actualRiderPresent && predictedRiderPresent -> tp++
                    !actualRiderPresent && predictedRiderPresent -> fp++
                    !actualRiderPresent && !predictedRiderPresent -> tn++
                    actualRiderPresent && !predictedRiderPresent -> fn++
                }

                latencies.add(result.processingTimeMs)
            }

            return BenchmarkResult(
                truePositives = tp,
                falsePositives = fp,
                trueNegatives = tn,
                falseNegatives = fn,
                avgDetectionLatencyMs = if (latencies.isNotEmpty()) latencies.average() else 0.0,
                minDetectionLatencyMs = latencies.minOrNull() ?: 0,
                maxDetectionLatencyMs = latencies.maxOrNull() ?: 0
            )
        }
    }
}

/**
 * Comparison result between two detection sessions
 */
data class ComparisonResult(
    val session1: DetectionSession,
    val session2: DetectionSession,
    val agreementRate: Double, // Percentage of frames where both detectors agree
    val session1OnlyDetections: Int, // Frames where only session1 detected
    val session2OnlyDetections: Int, // Frames where only session2 detected
    val bothDetected: Int,
    val neitherDetected: Int
) {
    companion object {
        fun compare(session1: DetectionSession, session2: DetectionSession): ComparisonResult {
            val results1 = session1.results.associateBy { it.frameIndex }
            val results2 = session2.results.associateBy { it.frameIndex }

            val allFrames = (results1.keys + results2.keys).toSet()

            var agreements = 0
            var s1Only = 0
            var s2Only = 0
            var both = 0
            var neither = 0

            for (frame in allFrames) {
                val r1 = results1[frame]?.riderDetected ?: false
                val r2 = results2[frame]?.riderDetected ?: false

                when {
                    r1 && r2 -> { both++; agreements++ }
                    !r1 && !r2 -> { neither++; agreements++ }
                    r1 && !r2 -> s1Only++
                    !r1 && r2 -> s2Only++
                }
            }

            return ComparisonResult(
                session1 = session1,
                session2 = session2,
                agreementRate = if (allFrames.isNotEmpty()) agreements.toDouble() / allFrames.size else 0.0,
                session1OnlyDetections = s1Only,
                session2OnlyDetections = s2Only,
                bothDetected = both,
                neitherDetected = neither
            )
        }
    }
}
