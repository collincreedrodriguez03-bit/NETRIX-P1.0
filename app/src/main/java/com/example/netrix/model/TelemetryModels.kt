package com.example.netrix.model

/**
 * Locked v1.1 State Vocabulary for all NETRIX metrics.
 * Missing or untested data is never zero.
 */
enum class MetricStatus(val label: String) {
    AVAILABLE("OK"),
    UNAVAILABLE("N/A"),
    NOT_TESTED("NOT TESTED"),
    PERMISSION_REQUIRED("PERM REQ"),
    STALE("STALE"),
    TIMED_OUT("TIMED OUT"),
    ERROR("ERROR"),
    INSUFFICIENT_DATA("INSUFFICIENT"),
    POSITION_UNCERTAIN("UNCERTAIN")
}

/**
 * Collection state of the Scanner engine.
 */
enum class ScannerState {
    STOPPED,
    ACTIVE_FOREGROUND,
    PAUSED,
    RESTRICTED
}

/**
 * 27-Vital Metrics identities as defined in specification.
 */
enum class VitalMetric(
    val id: Int,
    val code: String,
    val displayName: String,
    val defaultUnit: String,
    val isDerived: Boolean
) {
    SSID(1, "SSID", "SSID", "", false),
    BSSID(2, "BSSID", "BSSID / MAC", "", false),
    RSSI(3, "RSSI", "Signal Strength", "dBm", false),
    SIGNAL_QUALITY(4, "SIG_QUAL", "Signal Quality", "%", true),
    CHANNEL(5, "CH", "Channel", "", true),
    FREQUENCY(6, "FREQ", "Frequency", "MHz", false),
    CHANNEL_WIDTH(7, "WIDTH", "Channel Width", "MHz", false),
    LINK_RATE(8, "LINK_RATE", "Negotiated PHY Rate", "Mbps", false),
    NOISE_FLOOR(9, "NOISE", "Noise Floor", "dBm", false),
    SNR(10, "SNR", "Signal-to-Noise Ratio", "dB", true),
    LATENCY(11, "LATENCY", "Target Latency", "ms", false),
    PACKET_LOSS(12, "LOSS", "Packet Loss", "%", true),
    JITTER(13, "JITTER", "Jitter (RFC 3550)", "ms", true),
    GATEWAY_IP(14, "GW_IP", "Gateway IP", "", false),
    GATEWAY_REACHABILITY(15, "GW_REACH", "Gateway Reachability", "", false),
    DNS_SERVERS(16, "DNS_IP", "DNS Resolver IPs", "", false),
    DNS_RESOLVE_TIME(17, "DNS_TIME", "DNS Resolution Time", "ms", false),
    WAN_REACHABILITY(18, "WAN_REACH", "WAN / Internet Reachability", "", false),
    PUBLIC_IP(19, "PUB_IP", "Public IP Address", "", false),
    SUBNET_PREFIX(20, "SUBNET", "Subnet Mask / Prefix", "", false),
    DHCP_LEASE(21, "DHCP", "DHCP Lease Status", "s", false),
    CONNECTED_CLIENTS(22, "CLIENTS", "Observed Local Devices", "", false),
    DEAD_ZONE_COUNT(23, "DEAD_ZONES", "Dead-Zone Count (< -80dBm)", "", true),
    COVERAGE_SCORE(24, "COVERAGE", "Spatial Coverage Score", "%", true),
    NETWORK_HEALTH(25, "HEALTH", "Network Health Score", "/100", true),
    ROAMING_EVENTS(26, "ROAM_EVT", "Observed Roaming Events", "", false),
    SAMPLE_TEST_COUNT(27, "SAMPLES", "Samples / Probes Count", "", false);

    companion object {
        fun fromId(id: Int): VitalMetric? = entries.find { it.id == id }
    }
}

/**
 * Immutable measurement or status record for a single vital.
 */
data class MetricRecord(
    val vital: VitalMetric,
    val valueString: String,
    val numericValue: Double? = null,
    val unit: String = vital.defaultUnit,
    val sourceApi: String = "Android API",
    val timestamp: Long = System.currentTimeMillis(),
    val status: MetricStatus = MetricStatus.AVAILABLE,
    val calculationMethod: String = if (vital.isDerived) "Validated Formula" else "Direct Probe",
    val target: String? = null,
    val freshnessAgeMs: Long = 0L
)

/**
 * Snapshot of current Wi-Fi link parameters.
 */
data class WifiLinkSnapshot(
    val ssid: String? = null,
    val bssid: String? = null,
    val rssi: Int? = null,
    val frequencyMhz: Int? = null,
    val linkSpeedMbps: Int? = null,
    val isConnected: Boolean = false,
    val status: MetricStatus = MetricStatus.UNAVAILABLE,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Result of an active diagnostic network probe.
 */
data class ProbeResult(
    val id: String,
    val testName: String,
    val target: String,
    val protocol: String,
    val durationMs: Long?,
    val status: MetricStatus,
    val attemptedProbes: Int = 1,
    val successfulProbes: Int = 0,
    val rawOutput: String = "",
    val errorDetails: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Spatial coordinate point for ARM survey.
 */
data class SpatialPoint(
    val x: Float?,
    val y: Float?,
    val floorPlanId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val positioningMethod: String = "FLOOR_PLAN_MANUAL",
    val positioningConfidence: Float = 1.0f,
    val rssi: Int? = null,
    val latencyMs: Long? = null,
    val packetLossRate: Float? = null,
    val bssid: String? = null,
    val isCheckpoint: Boolean = false,
    val isUncertain: Boolean = false
)

/**
 * ARM Visualization Layer.
 */
enum class HeatmapLayer(val displayName: String) {
    HEATMAP_RSSI("Signal Heatmap"),
    HEATMAP_LATENCY("Latency Heatmap"),
    HEATMAP_LOSS("Loss Heatmap"),
    DEAD_ZONES("Dead Zones"),
    MEASUREMENT_POINTS("Sample Dots")
}
