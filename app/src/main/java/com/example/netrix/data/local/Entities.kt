package com.example.netrix.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "survey_sessions")
data class SurveySessionEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val floorPlanAssetUri: String = "floor_plan_default.jpg",
    val scalePixelsPerMeter: Float = 40.0f,
    val originX: Float = 0.0f,
    val originY: Float = 0.0f,
    val startTime: Long = System.currentTimeMillis(),
    val endTime: Long? = null,
    val status: String = "ACTIVE", // ACTIVE, PAUSED, FINALIZED
    val totalSamples: Int = 0,
    val deadZonesCount: Int = 0,
    val coverageScore: Float = 0f
)

@Entity(tableName = "spatial_samples")
data class SpatialSampleEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val x: Float?,
    val y: Float?,
    val positioningMethod: String, // ARCORE, SENSOR_DEAD_RECKONING, MANUAL_CHECKPOINT
    val positioningConfidence: Float,
    val rssi: Int?,
    val latencyMs: Long?,
    val packetLossRate: Float?,
    val bssid: String?,
    val ssid: String?,
    val frequencyMhz: Int?,
    val isCheckpoint: Boolean = false,
    val isUncertain: Boolean = false
)

@Entity(tableName = "network_observations")
data class NetworkObservationEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val sessionId: String?,
    val timestamp: Long = System.currentTimeMillis(),
    val ssid: String?,
    val bssid: String?,
    val rssi: Int?,
    val signalQuality: Int?,
    val channel: Int?,
    val frequencyMhz: Int?,
    val linkSpeedMbps: Int?,
    val gatewayIp: String?,
    val gatewayLatencyMs: Long?,
    val dnsResolveMs: Long?,
    val internetReachable: Boolean?,
    val packetLossPct: Float?,
    val jitterMs: Long?,
    val publicIp: String?,
    val rawVitalsJson: String
)

@Entity(tableName = "probe_results")
data class ActiveProbeResultEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val timestamp: Long = System.currentTimeMillis(),
    val testName: String,
    val target: String,
    val protocol: String,
    val durationMs: Long?,
    val status: String,
    val attemptedProbes: Int,
    val successfulProbes: Int,
    val rawOutput: String,
    val errorDetails: String?
)
