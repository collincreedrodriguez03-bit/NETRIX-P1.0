package com.example.netrix.service.scanner

import com.example.netrix.model.MetricStatus
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

object VitalCalculators {

    /**
     * Documented, validated RSSI to Signal Quality conversion formula.
     * Maps [-100 dBm .. -50 dBm] -> [0% .. 100%].
     */
    fun calculateSignalQuality(rssi: Int?): Pair<Int?, MetricStatus> {
        if (rssi == null) return Pair(null, MetricStatus.UNAVAILABLE)
        val quality = ((rssi + 100) * 2).coerceIn(0, 100)
        return Pair(quality, MetricStatus.AVAILABLE)
    }

    /**
     * Converts frequency in MHz to Wi-Fi Channel and Band string.
     */
    fun frequencyToChannel(freqMhz: Int?): Pair<Int?, String> {
        if (freqMhz == null || freqMhz <= 0) return Pair(null, "N/A")
        return when {
            freqMhz in 2412..2484 -> {
                val channel = if (freqMhz == 2484) 14 else ((freqMhz - 2412) / 5) + 1
                Pair(channel, "2.4 GHz")
            }
            freqMhz in 5170..5825 -> {
                val channel = ((freqMhz - 5170) / 5) + 34
                Pair(channel, "5 GHz")
            }
            freqMhz in 5925..7125 -> {
                val channel = ((freqMhz - 5925) / 5) + 1
                Pair(channel, "6 GHz")
            }
            else -> Pair(null, "Unknown")
        }
    }

    /**
     * Calculates packet loss percentage based on real probe attempts and failures.
     * Returns null if totalProbes == 0 (strict truthfulness: missing data is not 0%).
     */
    fun calculatePacketLoss(totalProbes: Int, failedProbes: Int): Pair<Float?, MetricStatus> {
        if (totalProbes <= 0) return Pair(null, MetricStatus.NOT_TESTED)
        val clampedFailed = failedProbes.coerceIn(0, totalProbes)
        val lossPct = (clampedFailed.toFloat() / totalProbes.toFloat()) * 100f
        return Pair(lossPct, MetricStatus.AVAILABLE)
    }

    /**
     * RFC 3550 statistical jitter calculation over sequential real latency observations.
     * Algorithm: J(i) = J(i-1) + (|D(i-1, i)| - J(i-1)) / 16.0
     * Requires minimum 2 sequential samples.
     */
    fun calculateRfc3550Jitter(latenciesMs: List<Long>): Pair<Long?, MetricStatus> {
        if (latenciesMs.size < 2) return Pair(null, MetricStatus.INSUFFICIENT_DATA)
        var jitter = 0.0
        for (i in 1 until latenciesMs.size) {
            val d = abs(latenciesMs[i] - latenciesMs[i - 1]).toDouble()
            jitter += (d - jitter) / 16.0
        }
        return Pair(jitter.roundToLong(), MetricStatus.AVAILABLE)
    }

    /**
     * Dead-zone classification based on documented signal threshold (< -80 dBm).
     */
    fun isDeadZone(rssi: Int?, thresholdDbm: Int = -80): Boolean {
        if (rssi == null) return false
        return rssi < thresholdDbm
    }

    /**
     * Network Health Score out of 100.
     * Requires minimum 4 valid measurements (RSSI, Gateway Ping, DNS Time, Internet Reachable).
     * If insufficient evidence, returns null with INSUFFICIENT_DATA.
     */
    fun calculateNetworkHealth(
        rssi: Int?,
        gatewayLatencyMs: Long?,
        dnsResolveMs: Long?,
        internetReachable: Boolean?,
        packetLossPct: Float?
    ): Pair<Int?, MetricStatus> {
        var validMetricsCount = 0
        var scoreSum = 0.0

        // 1. RSSI score (0..30)
        if (rssi != null) {
            validMetricsCount++
            val rssiScore = ((rssi + 95) / 45.0 * 30.0).coerceIn(0.0, 30.0)
            scoreSum += rssiScore
        }

        // 2. Gateway Latency score (0..25)
        if (gatewayLatencyMs != null && gatewayLatencyMs >= 0) {
            validMetricsCount++
            val latencyScore = when {
                gatewayLatencyMs <= 10 -> 25.0
                gatewayLatencyMs <= 30 -> 20.0
                gatewayLatencyMs <= 80 -> 12.0
                gatewayLatencyMs <= 200 -> 5.0
                else -> 0.0
            }
            scoreSum += latencyScore
        }

        // 3. DNS Resolve Time score (0..20)
        if (dnsResolveMs != null && dnsResolveMs >= 0) {
            validMetricsCount++
            val dnsScore = when {
                dnsResolveMs <= 30 -> 20.0
                dnsResolveMs <= 80 -> 15.0
                dnsResolveMs <= 200 -> 8.0
                else -> 2.0
            }
            scoreSum += dnsScore
        }

        // 4. Internet reachability (0..25)
        if (internetReachable != null) {
            validMetricsCount++
            val wanScore = if (internetReachable) 25.0 else 0.0
            scoreSum += wanScore
        }

        // 5. Packet loss deduction if tested
        if (packetLossPct != null) {
            scoreSum -= (packetLossPct * 0.5).coerceAtMost(30.0)
        }

        if (validMetricsCount < 4) {
            return Pair(null, MetricStatus.INSUFFICIENT_DATA)
        }

        val finalScore = scoreSum.coerceIn(0.0, 100.0).roundToInt()
        return Pair(finalScore, MetricStatus.AVAILABLE)
    }

    /**
     * Coverage score over surveyed spatial points.
     * Evaluates spatial distribution and proportion of healthy vs dead zones.
     */
    fun calculateCoverageScore(totalPoints: Int, deadZonePoints: Int): Pair<Float?, MetricStatus> {
        if (totalPoints < 3) return Pair(null, MetricStatus.INSUFFICIENT_DATA)
        val healthyPoints = (totalPoints - deadZonePoints).coerceAtLeast(0)
        val score = (healthyPoints.toFloat() / totalPoints.toFloat()) * 100f
        return Pair(score, MetricStatus.AVAILABLE)
    }
}
