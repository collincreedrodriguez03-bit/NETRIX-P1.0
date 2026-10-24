package com.example.netrix.service.spatial

import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.service.scanner.VitalCalculators
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

data class HeatmapCell(
    val x: Float,
    val y: Float,
    val value: Float,
    val isEstimated: Boolean,
    val isDeadZone: Boolean
)

object SpatialSurveyEngine {

    /**
     * Calibrates scale in pixels per meter given two pixel points and real distance in meters.
     */
    fun calibrateScale(
        x1: Float, y1: Float,
        x2: Float, y2: Float,
        distanceMeters: Float
    ): Float {
        if (distanceMeters <= 0f) return 40.0f
        val pixelDist = hypot(x2 - x1, y2 - y1)
        return (pixelDist / distanceMeters).coerceIn(5.0f, 500.0f)
    }

    /**
     * Computes Inverse Distance Weighting (IDW) interpolation grid over surveyed samples.
     * Enforces strict influence radius: points farther than maxInfluenceRadius remain UNMEASURED.
     */
    fun computeHeatmapGrid(
        samples: List<SpatialSampleEntity>,
        gridWidth: Int,
        gridHeight: Int,
        cellSize: Int = 16,
        layer: HeatmapLayer = HeatmapLayer.HEATMAP_RSSI,
        maxInfluenceRadiusPx: Float = 120.0f,
        deadZoneThresholdDbm: Int = -80
    ): List<HeatmapCell> {
        // Filter samples: Must have valid coordinates, not uncertain, and possess the requested layer metric.
        val validSamples = samples.filter { sample ->
            sample.x != null && sample.y != null && !sample.isUncertain && when (layer) {
                HeatmapLayer.HEATMAP_RSSI, HeatmapLayer.DEAD_ZONES -> sample.rssi != null
                HeatmapLayer.HEATMAP_LATENCY -> sample.latencyMs != null
                HeatmapLayer.HEATMAP_LOSS -> sample.packetLossRate != null
                HeatmapLayer.MEASUREMENT_POINTS -> true
            }
        }
        if (validSamples.isEmpty()) return emptyList()

        val cells = mutableListOf<HeatmapCell>()
        val cols = gridWidth / cellSize
        val rows = gridHeight / cellSize

        for (c in 0 until cols) {
            val cellX = (c * cellSize + cellSize / 2).toFloat()
            for (r in 0 until rows) {
                val cellY = (r * cellSize + cellSize / 2).toFloat()

                var sumWeights = 0.0
                var weightedSum = 0.0
                var minDistance = Float.MAX_VALUE

                for (sample in validSamples) {
                    val sx = sample.x ?: continue
                    val sy = sample.y ?: continue
                    val dist = hypot(cellX - sx, cellY - sy)
                    if (dist < minDistance) {
                        minDistance = dist
                    }

                    if (dist <= maxInfluenceRadiusPx) {
                        val weight = 1.0 / (dist + 5.0).pow(2.0)
                        val sampleValue = when (layer) {
                            HeatmapLayer.HEATMAP_RSSI -> sample.rssi?.toDouble() ?: continue
                            HeatmapLayer.HEATMAP_LATENCY -> sample.latencyMs?.toDouble() ?: continue
                            HeatmapLayer.HEATMAP_LOSS -> sample.packetLossRate?.toDouble() ?: continue
                            HeatmapLayer.DEAD_ZONES -> sample.rssi?.toDouble() ?: continue
                            HeatmapLayer.MEASUREMENT_POINTS -> 0.0
                        }
                        weightedSum += weight * sampleValue
                        sumWeights += weight
                    }
                }

                // If cell is beyond measured influence, do NOT extrapolate (remains unmeasured)
                if (sumWeights > 0.0 && minDistance <= maxInfluenceRadiusPx) {
                    val interpolatedValue = (weightedSum / sumWeights).toFloat()
                    val isDead = when (layer) {
                        HeatmapLayer.DEAD_ZONES, HeatmapLayer.HEATMAP_RSSI -> interpolatedValue < deadZoneThresholdDbm
                        HeatmapLayer.HEATMAP_LOSS -> interpolatedValue >= 5.0f
                        else -> false
                    }
                    cells.add(
                        HeatmapCell(
                            x = cellX,
                            y = cellY,
                            value = interpolatedValue,
                            isEstimated = minDistance > (cellSize / 2),
                            isDeadZone = isDead
                        )
                    )
                }
            }
        }
        return cells
    }

    /**
     * Evaluates total dead zones and spatial coverage score over floor plan.
     */
    fun evaluateSurveyStats(
        samples: List<SpatialSampleEntity>,
        deadZoneThresholdDbm: Int = -80
    ): Pair<Int, Float> {
        val validSamples = samples.filter { it.x != null && it.y != null && !it.isUncertain && it.rssi != null }
        if (validSamples.isEmpty()) return Pair(0, 0f)

        val deadZones = validSamples.count {
            VitalCalculators.isDeadZone(it.rssi, deadZoneThresholdDbm)
        }
        val (score, _) = VitalCalculators.calculateCoverageScore(validSamples.size, deadZones)
        return Pair(deadZones, score ?: 0f)
    }
}
