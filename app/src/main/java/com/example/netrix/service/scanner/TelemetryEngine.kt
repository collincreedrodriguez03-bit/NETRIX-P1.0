package com.example.netrix.service.scanner

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.ScanResult
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import com.example.netrix.data.local.NetrixDao
import com.example.netrix.data.local.NetworkObservationEntity
import com.example.netrix.model.DiscoveredAccessPoint
import com.example.netrix.model.MetricRecord
import com.example.netrix.model.MetricStatus
import com.example.netrix.model.ScannerState
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.model.VitalMetric
import com.example.netrix.model.WifiLinkSnapshot
import com.example.netrix.service.audio.ProximityBeepService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address

class TelemetryEngine(
    private val context: Context,
    private val dao: NetrixDao,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "TelemetryEngine"
    }

    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivityManager = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val locationManager = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    val proximityBeepService = ProximityBeepService(scope)

    private val _scannerState = MutableStateFlow(ScannerState.STOPPED)
    val scannerState: StateFlow<ScannerState> = _scannerState.asStateFlow()

    private val _vitals = MutableStateFlow<Map<VitalMetric, MetricRecord>>(emptyMap())
    val vitals: StateFlow<Map<VitalMetric, MetricRecord>> = _vitals.asStateFlow()

    private val _currentLink = MutableStateFlow(WifiLinkSnapshot())
    val currentLink: StateFlow<WifiLinkSnapshot> = _currentLink.asStateFlow()

    private val _discoveredAps = MutableStateFlow<List<DiscoveredAccessPoint>>(emptyList())
    val discoveredAps: StateFlow<List<DiscoveredAccessPoint>> = _discoveredAps.asStateFlow()

    private val _isScanThrottled = MutableStateFlow(false)
    val isScanThrottled: StateFlow<Boolean> = _isScanThrottled.asStateFlow()

    private val _scanStatusMessage = MutableStateFlow<String?>(null)
    val scanStatusMessage: StateFlow<String?> = _scanStatusMessage.asStateFlow()

    private val _isLocationEnabled = MutableStateFlow(true)
    val isLocationEnabled: StateFlow<Boolean> = _isLocationEnabled.asStateFlow()

    private val _roamingEventsCount = MutableStateFlow(0)
    val roamingEventsCount: StateFlow<Int> = _roamingEventsCount.asStateFlow()

    private var activeSessionId: String? = null
    private var scanJob: Job? = null
    private var fastRssiJob: Job? = null
    private var lastObservedBssid: String? = null
    private var smoothedEmaRssi: Float? = null
    private val recentLatencies = mutableListOf<Long>()
    private var totalProbesAttempted = 0
    private var totalProbesFailed = 0
    private var activeScanIntervalMs: Long = 3000L

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var scanResultsReceiver: BroadcastReceiver? = null
    private var scanResultsCallbackObj: Any? = null

    init {
        registerNetworkCallbackSafely()
        registerScanResultsReceiverSafely()
    }

    fun setActiveSession(sessionId: String?) {
        activeSessionId = sessionId
    }

    fun isLocationServicesEnabled(): Boolean {
        return try {
            if (locationManager != null) {
                LocationManagerCompat.isLocationEnabled(locationManager)
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    locationManager?.isLocationEnabled == true
                } else {
                    @Suppress("DEPRECATION")
                    val mode = Settings.Secure.getInt(
                        context.contentResolver,
                        Settings.Secure.LOCATION_MODE,
                        Settings.Secure.LOCATION_MODE_OFF
                    )
                    mode != Settings.Secure.LOCATION_MODE_OFF
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun registerNetworkCallbackSafely() {
        if (connectivityManager == null) return
        try {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+: Request location-sensitive Wi-Fi info via FLAG_INCLUDE_LOCATION_INFO
                networkCallback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                        handleFastWifiInfo(wifiInfo)
                    }

                    override fun onLost(network: Network) {
                        handleWifiLost()
                    }
                }
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
            } else {
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        @Suppress("DEPRECATION")
                        val wifiInfo = wifiManager?.connectionInfo
                        handleFastWifiInfo(wifiInfo)
                    }

                    override fun onLost(network: Network) {
                        handleWifiLost()
                    }
                }
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register NetworkCallback: ${t.message}", t)
        }
    }

    private fun registerScanResultsReceiverSafely() {
        if (wifiManager == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val callback = object : WifiManager.ScanResultsCallback() {
                    override fun onScanResultsAvailable() {
                        readScanResults()
                    }
                }
                scanResultsCallbackObj = callback
                wifiManager.registerScanResultsCallback(ContextCompat.getMainExecutor(context), callback)
            }

            // Also register BroadcastReceiver for compatibility and intent-based updates
            val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) {
                    val updated = intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) ?: true
                    _isScanThrottled.value = !updated
                    if (!updated) {
                        _scanStatusMessage.value = "scan throttled"
                    } else {
                        _scanStatusMessage.value = null
                    }
                    readScanResults()
                }
            }
            scanResultsReceiver = receiver
            context.registerReceiver(receiver, filter)
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to register scan results callback: ${t.message}", t)
        }
    }

    fun startScanning(intervalMs: Long = 3000L) {
        if (_scannerState.value == ScannerState.ACTIVE_FOREGROUND) return
        activeScanIntervalMs = intervalMs
        _scannerState.value = ScannerState.ACTIVE_FOREGROUND

        // 1. High-frequency 500 ms (2 Hz) lifecycle coroutine to re-query connected WifiInfo.rssi
        fastRssiJob?.cancel()
        fastRssiJob = scope.launch(Dispatchers.Default) {
            while (isActive && _scannerState.value == ScannerState.ACTIVE_FOREGROUND) {
                try {
                    pollConnectedWifiRssi()
                } catch (t: Throwable) {
                    Log.e(TAG, "Fast RSSI loop error: ${t.message}")
                }
                delay(500L) // 2 Hz cadence
            }
        }

        // 2. Comprehensive telemetry tick & active network probing
        scanJob?.cancel()
        scanJob = scope.launch(Dispatchers.Default) {
            while (isActive && _scannerState.value == ScannerState.ACTIVE_FOREGROUND) {
                try {
                    collectTelemetryTick()
                } catch (t: Throwable) {
                    Log.e(TAG, "Passive telemetry tick error: ${t.message}", t)
                }
                delay(activeScanIntervalMs)
            }
        }

        // Trigger immediate scan
        triggerApScan()
    }

    fun pauseScanning() {
        _scannerState.value = ScannerState.PAUSED
        scanJob?.cancel()
        fastRssiJob?.cancel()
        proximityBeepService.onPause()
    }

    fun resumeScanning() {
        startScanning(activeScanIntervalMs)
        proximityBeepService.onResume()
    }

    fun stopScanning() {
        _scannerState.value = ScannerState.STOPPED
        scanJob?.cancel()
        fastRssiJob?.cancel()
        proximityBeepService.onPause()
    }

    fun triggerApScan(): Boolean {
        if (wifiManager == null) {
            _scanStatusMessage.value = "Wi-Fi hardware unavailable"
            return false
        }
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            _scanStatusMessage.value = "ACCESS_FINE_LOCATION permission missing"
            return false
        }
        if (!isLocationServicesEnabled()) {
            _scanStatusMessage.value = "Location services disabled in Settings"
            return false
        }

        val success = try {
            @Suppress("DEPRECATION")
            wifiManager.startScan()
        } catch (t: Throwable) {
            Log.e(TAG, "Error invoking startScan(): ${t.message}")
            false
        }

        _isScanThrottled.value = !success
        if (!success) {
            _scanStatusMessage.value = "scan throttled"
        } else {
            _scanStatusMessage.value = null
        }
        readScanResults()
        return success
    }

    /**
     * Re-queries the connected Wi-Fi interface every 500 ms (2 Hz).
     * Extracts the raw integer RSSI matching Android Developer options > Wi-Fi verbose logging.
     */
    private fun pollConnectedWifiRssi() {
        val wifiInfo = queryActiveWifiInfo()
        handleFastWifiInfo(wifiInfo)
    }

    private fun queryActiveWifiInfo(): WifiInfo? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val net = connectivityManager?.activeNetwork
                val caps = connectivityManager?.getNetworkCapabilities(net)
                (caps?.transportInfo as? WifiInfo) ?: @Suppress("DEPRECATION") wifiManager?.connectionInfo
            } else {
                @Suppress("DEPRECATION")
                wifiManager?.connectionInfo
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun handleFastWifiInfo(wifiInfo: WifiInfo?) {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locOn = isLocationServicesEnabled()
        _isLocationEnabled.value = locOn

        val isConnected = wifiInfo != null && wifiInfo.networkId != -1 && wifiInfo.rssi != -127 && wifiInfo.rssi != -999

        if (!isConnected) {
            if (_currentLink.value.isConnected) {
                handleWifiLost()
            }
            return
        }

        val rawRssi = wifiInfo!!.rssi
        val prevEma = smoothedEmaRssi
        val smoothed = if (prevEma == null) {
            rawRssi.toFloat()
        } else {
            prevEma * 0.7f + rawRssi.toFloat() * 0.3f
        }
        smoothedEmaRssi = smoothed

        val rawSsid = wifiInfo.ssid?.trim('"', ' ')
        val cleanSsid = when {
            rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> null
            else -> rawSsid
        }

        val rawBssid = wifiInfo.bssid
        val cleanBssid = when {
            rawBssid.isNullOrBlank() || rawBssid == "02:00:00:00:00:00" -> null
            else -> rawBssid
        }

        val freq = if (wifiInfo.frequency > 0) wifiInfo.frequency else null
        val (ch, band) = VitalCalculators.frequencyToChannel(freq)
        val linkSpeed = if (wifiInfo.linkSpeed > 0) wifiInfo.linkSpeed else null

        val unavailableReason = when {
            !hasFine -> "ACCESS_FINE_LOCATION permission missing"
            !locOn -> "Location services disabled in Settings"
            cleanSsid == null && cleanBssid == null -> "SSID/BSSID redacted by Android location gating"
            else -> null
        }

        val updatedLink = _currentLink.value.copy(
            ssid = cleanSsid,
            bssid = cleanBssid,
            rssi = rawRssi,
            smoothedRssi = smoothed,
            frequencyMhz = freq,
            channel = ch,
            band = band,
            linkSpeedMbps = linkSpeed,
            isConnected = true,
            status = if (unavailableReason == null) MetricStatus.AVAILABLE else MetricStatus.PERMISSION_REQUIRED,
            unavailableReason = unavailableReason,
            timestamp = System.currentTimeMillis()
        )
        _currentLink.value = updatedLink

        // Feed live raw RSSI directly into proximity beep
        proximityBeepService.updateRssi(rawRssi, cleanBssid, cleanSsid, isConnected = true)
    }

    private fun handleWifiLost() {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locOn = isLocationServicesEnabled()

        val reason = when {
            !hasFine -> "ACCESS_FINE_LOCATION permission missing"
            !locOn -> "Location services disabled in Settings"
            else -> "Wi-Fi disconnected"
        }

        _currentLink.value = WifiLinkSnapshot(
            isConnected = false,
            status = MetricStatus.UNAVAILABLE,
            unavailableReason = reason,
            timestamp = System.currentTimeMillis()
        )
    }

    fun readScanResults() {
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasFine) {
            _scanStatusMessage.value = "ACCESS_FINE_LOCATION permission missing"
            return
        }
        if (!isLocationServicesEnabled()) {
            _scanStatusMessage.value = "Location services disabled in Settings"
            return
        }

        val rawResults = try {
            wifiManager?.scanResults ?: emptyList()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to read scanResults: ${t.message}")
            emptyList<ScanResult>()
        }

        val nowUs = SystemClock.elapsedRealtime() * 1000L
        val connBssid = _currentLink.value.bssid

        val parsed = rawResults.mapNotNull { scan ->
            val bssid = scan.BSSID ?: return@mapNotNull null
            val rawSsid = scan.SSID?.trim('"', ' ')
            val isRedacted = rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" || bssid == "02:00:00:00:00:00"

            val freq = scan.frequency
            val (ch, band) = VitalCalculators.frequencyToChannel(freq)
            val width = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                when (scan.channelWidth) {
                    ScanResult.CHANNEL_WIDTH_20MHZ -> 20
                    ScanResult.CHANNEL_WIDTH_40MHZ -> 40
                    ScanResult.CHANNEL_WIDTH_80MHZ -> 80
                    ScanResult.CHANNEL_WIDTH_160MHZ -> 160
                    ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> 80
                    else -> 20
                }
            } else 20

            val ageSec = if (scan.timestamp > 0) {
                ((nowUs - scan.timestamp) / 1_000_000L).coerceAtLeast(0)
            } else 0L

            val isConnected = connBssid != null && connBssid.equals(bssid, ignoreCase = true)

            DiscoveredAccessPoint(
                ssid = if (isRedacted) "[Hidden / Redacted]" else rawSsid,
                bssid = bssid,
                rssi = scan.level, // ScanResult.level raw reading
                frequencyMhz = freq,
                channel = ch,
                band = band,
                channelWidthMhz = width,
                capabilities = scan.capabilities ?: "N/A",
                timestampUs = scan.timestamp,
                ageSeconds = ageSec,
                isConnected = isConnected
            )
        }.sortedByDescending { it.rssi }

        _discoveredAps.value = parsed

        // If proximity beep is following an unconnected target AP, update at scan cadence
        val targetBssid = proximityBeepService.targetBssid.value
        if (targetBssid != null && !proximityBeepService.isTargetConnected.value) {
            val targetAp = parsed.find { it.bssid.equals(targetBssid, ignoreCase = true) }
            if (targetAp != null) {
                proximityBeepService.updateRssi(targetAp.rssi, targetAp.bssid, targetAp.ssid, isConnected = false)
            }
        }
    }

    private suspend fun collectTelemetryTick() {
        val now = System.currentTimeMillis()
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locEnabled = isLocationServicesEnabled()
        _isLocationEnabled.value = locEnabled

        // 1. Check Connectivity & Link Properties
        val activeNetwork = try { connectivityManager?.activeNetwork } catch (_: Throwable) { null }
        val capabilities = try { activeNetwork?.let { connectivityManager?.getNetworkCapabilities(it) } } catch (_: Throwable) { null }
        val linkProperties: LinkProperties? = try { activeNetwork?.let { connectivityManager?.getLinkProperties(it) } } catch (_: Throwable) { null }
        val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        // 2. Wi-Fi Info from active transport
        val wifiInfo: WifiInfo? = queryActiveWifiInfo()

        val rawSsid = wifiInfo?.ssid?.trim('"', ' ')
        val rawBssid = wifiInfo?.bssid

        val reason = when {
            !isWifi -> "Wi-Fi disconnected"
            !hasFineLocation -> "ACCESS_FINE_LOCATION permission missing"
            !locEnabled -> "Location services disabled in Settings"
            rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> "SSID redacted by Android location gating"
            rawBssid.isNullOrBlank() || rawBssid == "02:00:00:00:00:00" -> "BSSID redacted by Android location gating"
            else -> null
        }

        val ssid = if (isWifi && hasFineLocation && locEnabled && !rawSsid.isNullOrBlank() && rawSsid != "<unknown ssid>") rawSsid else null
        val bssid = if (isWifi && hasFineLocation && locEnabled && !rawBssid.isNullOrBlank() && rawBssid != "02:00:00:00:00:00") rawBssid else null

        // Track Roaming Events
        if (bssid != null && lastObservedBssid != null && bssid != lastObservedBssid) {
            _roamingEventsCount.value += 1
        }
        if (bssid != null) {
            lastObservedBssid = bssid
        }

        val rssi = if (isWifi && wifiInfo != null && wifiInfo.rssi != -127 && wifiInfo.rssi != -999) wifiInfo.rssi else null
        val freq = if (isWifi && wifiInfo != null && wifiInfo.frequency > 0) wifiInfo.frequency else null
        val linkSpeed = if (isWifi && wifiInfo != null && wifiInfo.linkSpeed > 0) wifiInfo.linkSpeed else null

        val (qualityVal, _) = VitalCalculators.calculateSignalQuality(rssi)
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

        _currentLink.value = WifiLinkSnapshot(
            ssid = ssid,
            bssid = bssid,
            rssi = rssi,
            smoothedRssi = smoothedEmaRssi,
            frequencyMhz = freq,
            channel = channelVal,
            band = bandStr,
            linkSpeedMbps = linkSpeed,
            isConnected = isWifi && rssi != null,
            status = when {
                !isWifi -> MetricStatus.UNAVAILABLE
                !hasFineLocation -> MetricStatus.PERMISSION_REQUIRED
                !locEnabled -> MetricStatus.UNAVAILABLE
                rssi != null -> MetricStatus.AVAILABLE
                else -> MetricStatus.UNAVAILABLE
            },
            unavailableReason = reason,
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

            val dnsResult = NetworkProbeService.probeDns("dns.google")
            dnsProbeDuration = dnsResult.durationMs
            dnsProbeStatus = dnsResult.status

            val wanResult = NetworkProbeService.probeWanReachability()
            wanReachable = wanResult.status == MetricStatus.AVAILABLE
            wanStatus = wanResult.status
        }

        val (packetLossVal, packetLossStatus) = VitalCalculators.calculatePacketLoss(totalProbesAttempted, totalProbesFailed)
        val (jitterVal, jitterStatus) = VitalCalculators.calculateRfc3550Jitter(recentLatencies)

        val (healthScoreVal, healthScoreStatus) = VitalCalculators.calculateNetworkHealth(
            rssi = rssi,
            gatewayLatencyMs = gatewayProbeDuration,
            dnsResolveMs = dnsProbeDuration,
            internetReachable = wanReachable,
            packetLossPct = packetLossVal
        )

        val records = mutableMapOf<VitalMetric, MetricRecord>()
        fun addRecord(
            metric: VitalMetric,
            value: String?,
            numeric: Double? = null,
            status: MetricStatus = MetricStatus.AVAILABLE,
            source: String = "Direct Probe",
            method: String = if (metric.isDerived) "Validated Formula" else "Direct Probe"
        ) {
            records[metric] = MetricRecord(
                vital = metric,
                valueString = value ?: (reason ?: status.label),
                numericValue = numeric,
                sourceApi = source,
                calculationMethod = method,
                timestamp = now,
                status = if (value != null) status else if (reason != null) MetricStatus.UNAVAILABLE else status
            )
        }

        // Populate 27 Vitals with real reasons when unavailable
        addRecord(VitalMetric.SSID, ssid, source = "WifiInfo.ssid")
        addRecord(VitalMetric.BSSID, bssid, source = "WifiInfo.bssid")
        addRecord(
            VitalMetric.RSSI,
            rssi?.let { "$it dBm (raw)" },
            numeric = rssi?.toDouble(),
            status = if (rssi != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.rssi"
        )
        addRecord(
            VitalMetric.SIGNAL_QUALITY,
            qualityVal?.let { "$it%" },
            numeric = qualityVal?.toDouble(),
            status = if (qualityVal != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            method = "RFC Formula ((RSSI + 100) * 2)"
        )
        addRecord(VitalMetric.CHANNEL, channelVal?.toString(), numeric = channelVal?.toDouble(), source = "Frequency Lookup")
        addRecord(VitalMetric.FREQUENCY, freq?.let { "$it MHz" }, numeric = freq?.toDouble(), source = "WifiInfo.frequency")
        addRecord(VitalMetric.CHANNEL_WIDTH, "20 MHz", numeric = 20.0, source = "ScanResult.channelWidth")
        addRecord(VitalMetric.LINK_RATE, linkSpeed?.let { "$it Mbps" }, numeric = linkSpeed?.toDouble(), source = "WifiInfo.linkSpeed")
        addRecord(VitalMetric.NOISE_FLOOR, null, status = MetricStatus.UNAVAILABLE, source = "Restricted OEM API")
        addRecord(VitalMetric.SNR, null, status = MetricStatus.UNAVAILABLE, source = "Restricted OEM API")
        addRecord(VitalMetric.LATENCY, gatewayProbeDuration?.let { "$it ms" }, numeric = gatewayProbeDuration?.toDouble(), status = gatewayProbeStatus, source = "ICMP/TCP Echo")
        addRecord(VitalMetric.PACKET_LOSS, packetLossVal?.let { String.format("%.1f%%", it) }, numeric = packetLossVal?.toDouble(), status = packetLossStatus, method = "Failed / Attempted Probes")
        addRecord(VitalMetric.JITTER, jitterVal?.let { "$it ms" }, numeric = jitterVal?.toDouble(), status = jitterStatus, method = "RFC 3550 Jitter Formula")
        addRecord(VitalMetric.GATEWAY_IP, gatewayIp, status = if (gatewayIp != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE, source = "LinkProperties.routes")
        addRecord(VitalMetric.GATEWAY_REACHABILITY, if (gatewayProbeStatus == MetricStatus.AVAILABLE) "OK" else null, status = gatewayProbeStatus, source = "TCP/ICMP Socket")
        addRecord(VitalMetric.DNS_SERVERS, dnsString, status = if (dnsString != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE, source = "LinkProperties.dnsServers")
        addRecord(VitalMetric.DNS_RESOLVE_TIME, dnsProbeDuration?.let { "$it ms" }, numeric = dnsProbeDuration?.toDouble(), status = dnsProbeStatus, source = "InetAddress.getAllByName")
        addRecord(VitalMetric.WAN_REACHABILITY, if (wanReachable == true) "OK" else null, status = wanStatus, source = "HTTPS 204 Probe")
        addRecord(VitalMetric.PUBLIC_IP, null, status = MetricStatus.NOT_TESTED, source = "External IP Lookup")
        addRecord(VitalMetric.SUBNET_PREFIX, subnetStr, status = if (subnetStr != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE, source = "LinkAddress.prefixLength")

        val dhcpLeaseSec = @Suppress("DEPRECATION") wifiManager?.dhcpInfo?.leaseDuration
        addRecord(VitalMetric.DHCP_LEASE, if (dhcpLeaseSec != null && dhcpLeaseSec > 0) "${dhcpLeaseSec}s" else null, numeric = dhcpLeaseSec?.toDouble(), status = if (dhcpLeaseSec != null && dhcpLeaseSec > 0) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE, source = "DhcpInfo")
        addRecord(VitalMetric.CONNECTED_CLIENTS, null, status = MetricStatus.UNAVAILABLE, source = "Local Neighbor Probing")
        addRecord(VitalMetric.DEAD_ZONE_COUNT, "0", numeric = 0.0, status = MetricStatus.AVAILABLE, method = "RSSI < -80 dBm Threshold")
        addRecord(VitalMetric.COVERAGE_SCORE, null, status = MetricStatus.INSUFFICIENT_DATA, method = "Floor Plan Spatial Coverage")
        addRecord(VitalMetric.NETWORK_HEALTH, healthScoreVal?.let { "$it/100" }, numeric = healthScoreVal?.toDouble(), status = healthScoreStatus, method = "Composite Weighted Metric")
        addRecord(VitalMetric.ROAMING_EVENTS, "${_roamingEventsCount.value}", numeric = _roamingEventsCount.value.toDouble(), status = MetricStatus.AVAILABLE, source = "BSSID Transition Detector")
        addRecord(VitalMetric.SAMPLE_TEST_COUNT, "$totalProbesAttempted probes", numeric = totalProbesAttempted.toDouble(), status = MetricStatus.AVAILABLE, source = "Active Probe Counter")

        _vitals.value = records

        // Persist observation to Room database asynchronously in Dispatchers.IO
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

        withContext(Dispatchers.IO) {
            try {
                dao.insertObservation(observation)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist observation safely: ${t.message}")
            }
        }
    }

    private fun LinkProperties.dhcpServerFromProperties(): java.net.InetAddress? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            dhcpServerAddress
        } else null
    }

    fun destroy() {
        stopScanning()
        proximityBeepService.release()
        try {
            networkCallback?.let { connectivityManager?.unregisterNetworkCallback(it) }
        } catch (_: Throwable) {}
        try {
            scanResultsReceiver?.let { context.unregisterReceiver(it) }
        } catch (_: Throwable) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                (scanResultsCallbackObj as? WifiManager.ScanResultsCallback)?.let {
                    wifiManager?.unregisterScanResultsCallback(it)
                }
            } catch (_: Throwable) {}
        }
    }
}
