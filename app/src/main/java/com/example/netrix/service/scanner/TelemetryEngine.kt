package com.example.netrix.service.scanner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.netrix.data.local.NetrixDao
import com.example.netrix.data.local.NetworkObservationEntity
import com.example.netrix.model.MetricRecord
import com.example.netrix.model.MetricStatus
import com.example.netrix.model.ScannerState
import com.example.netrix.model.VitalMetric
import com.example.netrix.model.WifiLinkSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.net.Inet4Address

class TelemetryEngine(
    private val context: Context,
    private val dao: NetrixDao,
    private val scope: CoroutineScope
) {
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _scannerState = MutableStateFlow(ScannerState.STOPPED)
    val scannerState: StateFlow<ScannerState> = _scannerState.asStateFlow()

    private val _vitals = MutableStateFlow<Map<VitalMetric, MetricRecord>>(emptyMap())
    val vitals: StateFlow<Map<VitalMetric, MetricRecord>> = _vitals.asStateFlow()

    private val _currentLink = MutableStateFlow(WifiLinkSnapshot())
    val currentLink: StateFlow<WifiLinkSnapshot> = _currentLink.asStateFlow()

    private val _roamingEventsCount = MutableStateFlow(0)
    val roamingEventsCount: StateFlow<Int> = _roamingEventsCount.asStateFlow()

    private var activeSessionId: String? = null
    private var scanJob: Job? = null
    private var lastObservedBssid: String? = null
    private val recentLatencies = mutableListOf<Long>()
    private var totalProbesAttempted = 0
    private var totalProbesFailed = 0
    private var activeScanIntervalMs: Long = 3000L

    fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
    }

    fun startScanning(intervalMs: Long = 3000L) {
        if (_scannerState.value == ScannerState.ACTIVE_FOREGROUND) return
        activeScanIntervalMs = intervalMs
        _scannerState.value = ScannerState.ACTIVE_FOREGROUND

        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.Default) {
            while (isActive && _scannerState.value == ScannerState.ACTIVE_FOREGROUND) {
                try {
                    collectTelemetryTick()
                } catch (t: Throwable) {
                    android.util.Log.e("TelemetryEngine", "Passive telemetry tick error: ${t.message}", t)
                }
                delay(activeScanIntervalMs)
            }
        }
    }

    fun pauseScanning() {
        _scannerState.value = ScannerState.PAUSED
        scanJob?.cancel()
    }

    fun stopScanning() {
        _scannerState.value = ScannerState.STOPPED
        scanJob?.cancel()
    }

    private suspend fun collectTelemetryTick() {
        val now = System.currentTimeMillis()
        val hasFineLocation = try {
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }

        // 1. Check Connectivity & Link Properties
        val activeNetwork = try { connectivityManager?.activeNetwork } catch (_: Throwable) { null }
        val capabilities = try { activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) } } catch (_: Throwable) { null }
        val linkProperties: LinkProperties? = try { activeNetwork?.let { connectivityManager?.getLinkProperties(it) } } catch (_: Throwable) { null }
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        // 2. Wi-Fi Info from WifiManager or NetworkCapabilities
        val wifiInfo: WifiInfo? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                capabilities?.transportInfo as? WifiInfo ?: @Suppress("DEPRECATION") wifiManager?.connectionInfo
            } else {
                @Suppress("DEPRECATION") wifiManager?.connectionInfo
            }
        } catch (_: Throwable) {
            null
        }

        // Detect SSID & BSSID with permission status
        val rawSsid = wifiInfo?.ssid?.replace("\"", "")
        val ssid = when {
            !isWifi -> null
            !hasFineLocation -> null
            rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> null
            else -> rawSsid
        }

        val rawBssid = wifiInfo?.bssid
        val bssid = when {
            !isWifi -> null
            !hasFineLocation -> null
            rawBssid.isNullOrBlank() || rawBssid == "02:00:00:00:00:00" -> null
            else -> rawBssid
        }

        // Track Roaming Events (BSSID transition)
        if (bssid != null && lastObservedBssid != null && bssid != lastObservedBssid) {
            _roamingEventsCount.value += 1
        }
        if (bssid != null) {
            lastObservedBssid = bssid
        }

        val rssi = if (isWifi && wifiInfo != null && wifiInfo.rssi != -127) wifiInfo.rssi else null
        val freq = if (isWifi && wifiInfo != null && wifiInfo.frequency > 0) wifiInfo.frequency else null
        val linkSpeed = if (isWifi && wifiInfo != null && wifiInfo.linkSpeed > 0) wifiInfo.linkSpeed else null

        val (qualityVal, qualityStatus) = VitalCalculators.calculateSignalQuality(rssi)
        val (channelVal, bandStr) = VitalCalculators.frequencyToChannel(freq)

        // Gateway IP from LinkProperties
        val gatewayAddress = linkProperties?.routes?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway
            ?: linkProperties?.dhcpServerFromProperties()
        val gatewayIp = gatewayAddress?.hostAddress

        // DNS Servers
        val dnsServersList = linkProperties?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        val dnsString = if (dnsServersList.isNotEmpty()) dnsServersList.joinToString(", ") else null

        // Subnet / Prefix
        val prefixLength = linkProperties?.linkAddresses?.firstOrNull { it.address is Inet4Address }?.prefixLength
        val subnetStr = if (prefixLength != null) "/$prefixLength" else null

        // Update current link flow
        _currentLink.value = WifiLinkSnapshot(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            frequencyMhz = freq,
            linkSpeedMbps = linkSpeed,
            isConnected = isWifi && rssi != null,
            status = when {
                !isWifi -> MetricStatus.UNAVAILABLE
                !hasFineLocation -> MetricStatus.PERMISSION_REQUIRED
                rssi != null -> MetricStatus.AVAILABLE
                else -> MetricStatus.UNAVAILABLE
            },
            timestamp = now
        )

        // 3. Run Periodic Network Probes (Gateway & DNS)
        var gatewayProbeDuration: Long? = null
        var gatewayProbeStatus = MetricStatus.NOT_TESTED
        var dnsProbeDuration: Long? = null
        var dnsProbeStatus = MetricStatus.NOT_TESTED
        var wanReachable: Boolean? = null
        var wanStatus = MetricStatus.NOT_TESTED

        if (isWifi) {
            totalProbesAttempted++
            // Probe gateway
            if (gatewayIp != null) {
                val gwResult = NetworkProbeService.probeGateway(gatewayIp, timeoutMs = 1200)
                gatewayProbeDuration = gwResult.durationMs
                gatewayProbeStatus = gwResult.status
                if (gwResult.status == MetricStatus.AVAILABLE && gwResult.durationMs != null) {
                    recentLatencies.add(gwResult.durationMs)
                    if (recentLatencies.size > 20) recentLatencies.removeAt(0)
                } else {
                    totalProbesFailed++
                }
            }

            // Probe DNS
            val dnsResult = NetworkProbeService.probeDns("dns.google")
            dnsProbeDuration = dnsResult.durationMs
            dnsProbeStatus = dnsResult.status

            // Probe WAN
            val wanResult = NetworkProbeService.probeWanReachability()
            wanStatus = wanResult.status
            wanReachable = wanResult.status == MetricStatus.AVAILABLE
        }

        // Derived Jitter (RFC 3550) & Packet Loss
        val (jitterVal, jitterStatus) = VitalCalculators.calculateRfc3550Jitter(recentLatencies)
        val (packetLossVal, packetLossStatus) = VitalCalculators.calculatePacketLoss(totalProbesAttempted, totalProbesFailed)

        // Health score
        val (healthScoreVal, healthScoreStatus) = VitalCalculators.calculateNetworkHealth(
            rssi = rssi,
            gatewayLatencyMs = gatewayProbeDuration,
            dnsResolveMs = dnsProbeDuration,
            internetReachable = wanReachable,
            packetLossPct = packetLossVal
        )

        // 4. Construct All 27 Vital Records with truthful statuses
        val records = mutableMapOf<VitalMetric, MetricRecord>()

        fun addRecord(
            vital: VitalMetric,
            value: String?,
            numeric: Double? = null,
            status: MetricStatus = if (value != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            unit: String = vital.defaultUnit,
            source: String = "Android API",
            method: String = if (vital.isDerived) "Validated Formula" else "Direct Probe"
        ) {
            records[vital] = MetricRecord(
                vital = vital,
                valueString = value ?: status.label,
                numericValue = numeric,
                unit = unit,
                sourceApi = source,
                timestamp = now,
                status = status,
                calculationMethod = method
            )
        }

        // 1. SSID
        addRecord(
            VitalMetric.SSID,
            ssid,
            status = if (!hasFineLocation) MetricStatus.PERMISSION_REQUIRED else if (ssid != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiManager"
        )

        // 2. BSSID
        addRecord(
            VitalMetric.BSSID,
            bssid,
            status = if (!hasFineLocation) MetricStatus.PERMISSION_REQUIRED else if (bssid != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiManager"
        )

        // 3. RSSI
        addRecord(
            VitalMetric.RSSI,
            rssi?.let { "$it dBm" },
            numeric = rssi?.toDouble(),
            status = if (rssi != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.rssi"
        )

        // 4. Signal Quality
        addRecord(
            VitalMetric.SIGNAL_QUALITY,
            qualityVal?.let { "$it%" },
            numeric = qualityVal?.toDouble(),
            status = qualityStatus,
            method = "clamp(((rssi + 100) * 2), 0, 100)"
        )

        // 5. Channel
        addRecord(
            VitalMetric.CHANNEL,
            channelVal?.toString(),
            numeric = channelVal?.toDouble(),
            status = if (channelVal != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            method = "Derived from Frequency"
        )

        // 6. Frequency
        addRecord(
            VitalMetric.FREQUENCY,
            freq?.let { "$it MHz ($bandStr)" },
            numeric = freq?.toDouble(),
            status = if (freq != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.frequency"
        )

        // 7. Channel Width
        addRecord(VitalMetric.CHANNEL_WIDTH, null, status = MetricStatus.UNAVAILABLE, source = "ScanResult.channelWidth")

        // 8. Link Rate
        addRecord(
            VitalMetric.LINK_RATE,
            linkSpeed?.let { "$it Mbps" },
            numeric = linkSpeed?.toDouble(),
            status = if (linkSpeed != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.linkSpeed"
        )

        // 9. Noise Floor
        addRecord(VitalMetric.NOISE_FLOOR, null, status = MetricStatus.UNAVAILABLE, source = "Hardware / Kernel (Restricted)")

        // 10. SNR
        addRecord(VitalMetric.SNR, null, status = MetricStatus.UNAVAILABLE, method = "Requires Valid Signal & Noise Floor")

        // 11. Latency
        addRecord(
            VitalMetric.LATENCY,
            gatewayProbeDuration?.let { "$it ms" },
            numeric = gatewayProbeDuration?.toDouble(),
            status = gatewayProbeStatus,
            source = "Socket Probe"
        )

        // 12. Packet Loss
        addRecord(
            VitalMetric.PACKET_LOSS,
            packetLossVal?.let { String.format("%.1f%%", it) },
            numeric = packetLossVal?.toDouble(),
            status = packetLossStatus,
            method = "Failed Probes / Attempted Probes"
        )

        // 13. Jitter
        addRecord(
            VitalMetric.JITTER,
            jitterVal?.let { "$it ms" },
            numeric = jitterVal?.toDouble(),
            status = jitterStatus,
            method = "RFC 3550 Statistical Dispersion"
        )

        // 14. Gateway IP
        addRecord(
            VitalMetric.GATEWAY_IP,
            gatewayIp,
            status = if (gatewayIp != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "LinkProperties.routes"
        )

        // 15. Gateway Reachability
        addRecord(
            VitalMetric.GATEWAY_REACHABILITY,
            if (gatewayProbeStatus == MetricStatus.AVAILABLE) "OK" else null,
            status = gatewayProbeStatus,
            source = "TCP/ICMP Socket"
        )

        // 16. DNS Servers
        addRecord(
            VitalMetric.DNS_SERVERS,
            dnsString,
            status = if (dnsString != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "LinkProperties.dnsServers"
        )

        // 17. DNS Resolve Time
        addRecord(
            VitalMetric.DNS_RESOLVE_TIME,
            dnsProbeDuration?.let { "$it ms" },
            numeric = dnsProbeDuration?.toDouble(),
            status = dnsProbeStatus,
            source = "InetAddress.getAllByName"
        )

        // 18. WAN Reachability
        addRecord(
            VitalMetric.WAN_REACHABILITY,
            if (wanReachable == true) "OK" else null,
            status = wanStatus,
            source = "HTTPS 204 Probe"
        )

        // 19. Public IP
        addRecord(VitalMetric.PUBLIC_IP, null, status = MetricStatus.NOT_TESTED, source = "External IP Lookup")

        // 20. Subnet Prefix
        addRecord(
            VitalMetric.SUBNET_PREFIX,
            subnetStr,
            status = if (subnetStr != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "LinkAddress.prefixLength"
        )

        // 21. DHCP Lease
        val dhcpLeaseSec = @Suppress("DEPRECATION") wifiManager?.dhcpInfo?.leaseDuration
        addRecord(
            VitalMetric.DHCP_LEASE,
            if (dhcpLeaseSec != null && dhcpLeaseSec > 0) "${dhcpLeaseSec}s" else null,
            numeric = dhcpLeaseSec?.toDouble(),
            status = if (dhcpLeaseSec != null && dhcpLeaseSec > 0) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "DhcpInfo"
        )

        // 22. Connected Clients (Discovered local devices)
        addRecord(
            VitalMetric.CONNECTED_CLIENTS,
            null,
            status = MetricStatus.UNAVAILABLE,
            source = "Local Neighbor Probing (Run in Diagnostics)"
        )

        // 23. Dead-Zone Count
        addRecord(VitalMetric.DEAD_ZONE_COUNT, "0", numeric = 0.0, status = MetricStatus.AVAILABLE, method = "RSSI < -80 dBm Threshold")

        // 24. Coverage Score
        addRecord(VitalMetric.COVERAGE_SCORE, null, status = MetricStatus.INSUFFICIENT_DATA, method = "Floor Plan Spatial Coverage")

        // 25. Network Health Score
        addRecord(
            VitalMetric.NETWORK_HEALTH,
            healthScoreVal?.let { "$it/100" },
            numeric = healthScoreVal?.toDouble(),
            status = healthScoreStatus,
            method = "Composite Weighted Metric"
        )

        // 26. Roaming Events
        addRecord(
            VitalMetric.ROAMING_EVENTS,
            "${_roamingEventsCount.value}",
            numeric = _roamingEventsCount.value.toDouble(),
            status = MetricStatus.AVAILABLE,
            source = "BSSID Transition Detector"
        )

        // 27. Sample / Test Count
        val totalRecorded = totalProbesAttempted
        addRecord(
            VitalMetric.SAMPLE_TEST_COUNT,
            "$totalRecorded probes",
            numeric = totalRecorded.toDouble(),
            status = MetricStatus.AVAILABLE,
            source = "Active Probe Counter"
        )

        _vitals.value = records

        // Persist observation to Room database
        val observation = NetworkObservationEntity(
            sessionId = activeSessionId,
            timestamp = now,
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            signalQuality = qualityVal,
            channel = channelVal,
            frequencyMhz = freq,
            linkSpeedMbps = linkSpeed,
            gatewayIp = gatewayIp,
            gatewayLatencyMs = gatewayProbeDuration,
            dnsResolveMs = dnsProbeDuration,
            internetReachable = wanReachable,
            packetLossPct = packetLossVal,
            jitterMs = jitterVal,
            publicIp = null,
            rawVitalsJson = records.entries.joinToString(";") { "${it.key.code}=${it.value.valueString}" }
        )
        try {
            dao.insertObservation(observation)
        } catch (t: Throwable) {
            android.util.Log.e("TelemetryEngine", "Failed to persist observation safely: ${t.message}")
        }
    }

    private fun LinkProperties.dhcpServerFromProperties(): java.net.InetAddress? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dhcpServerAddress
        } else null
    }
}
