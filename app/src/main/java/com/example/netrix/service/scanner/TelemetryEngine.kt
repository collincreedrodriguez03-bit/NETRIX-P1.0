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

    // Canonical Single Source of Truth for Wi-Fi Link Telemetry (Requirement A)
    private val _wifiState = MutableStateFlow(com.example.netrix.model.WifiState())
    val wifiState: StateFlow<com.example.netrix.model.WifiState> = _wifiState.asStateFlow()

    // Backward-compatibility alias
    val currentLink: StateFlow<com.example.netrix.model.WifiState> get() = wifiState

    // Raw Diagnostics for Tools Screen (Requirement F)
    private val _diagnostics = MutableStateFlow(com.example.netrix.model.WifiDiagnostics())
    val diagnostics: StateFlow<com.example.netrix.model.WifiDiagnostics> = _diagnostics.asStateFlow()

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

    /**
     * Requirement B: CONNECTED FLAG
     * connected = ConnectivityManager.getNetworkCapabilities(activeNetwork)?.hasTransport(TRANSPORT_WIFI) == true.
     * NEVER derive from ssid, bssid, networkId, or supplicant state. Survey gate uses this.
     */
    fun isWifiConnected(): Boolean {
        return try {
            val cm = connectivityManager ?: return false
            val activeNet = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(activeNet) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Throwable) {
            false
        }
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
                    override fun onAvailable(network: Network) {
                        val caps = connectivityManager.getNetworkCapabilities(network)
                        val wifiInfo = caps?.transportInfo as? WifiInfo
                        handleNetworkCallbackEvent(wifiInfo)
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        val wifiInfo = networkCapabilities.transportInfo as? WifiInfo
                        handleNetworkCallbackEvent(wifiInfo)
                    }

                    override fun onLost(network: Network) {
                        handleNetworkLost()
                    }
                }
                connectivityManager.registerNetworkCallback(request, networkCallback!!)
            } else {
                networkCallback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        @Suppress("DEPRECATION")
                        val wifiInfo = wifiManager?.connectionInfo
                        handleNetworkCallbackEvent(wifiInfo)
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        @Suppress("DEPRECATION")
                        val wifiInfo = wifiManager?.connectionInfo
                        handleNetworkCallbackEvent(wifiInfo)
                    }

                    override fun onLost(network: Network) {
                        handleNetworkLost()
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
     * Works via wifiManager.connectionInfo for rssi/freq/linkSpeed without requiring location flags.
     */
    private fun pollConnectedWifiRssi() {
        val connected = isWifiConnected()
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else true
        val locOn = isLocationServicesEnabled()
        _isLocationEnabled.value = locOn

        if (!connected) {
            if (_wifiState.value.connected) {
                handleNetworkLost()
            }
            return
        }

        @Suppress("DEPRECATION")
        val connInfo = try { wifiManager?.connectionInfo } catch (_: Throwable) { null }
        val rawRssi = if (connInfo != null && connInfo.rssi != -127 && connInfo.rssi != -999) connInfo.rssi else _wifiState.value.rssi
        val freq = if (connInfo != null && connInfo.frequency > 0) connInfo.frequency else _wifiState.value.freqMhz
        val (ch, band) = VitalCalculators.frequencyToChannel(freq)
        val linkSpeed = if (connInfo != null && connInfo.linkSpeed > 0) connInfo.linkSpeed else _wifiState.value.linkMbps

        // Smoothing EMA calculation
        if (rawRssi != null) {
            val prevEma = smoothedEmaRssi
            smoothedEmaRssi = if (prevEma == null) {
                rawRssi.toFloat()
            } else {
                prevEma * 0.7f + rawRssi.toFloat() * 0.3f
            }
        }

        // Try reading SSID/BSSID from connInfo if unredacted, otherwise preserve callback's unredacted values
        val rawSsid = connInfo?.ssid?.trim('"', ' ')
        val cleanSsid = when {
            rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> null
            else -> rawSsid
        }
        val rawBssid = connInfo?.bssid
        val cleanBssid = when {
            rawBssid.isNullOrBlank() || rawBssid == "02:00:00:00:00:00" -> null
            else -> rawBssid
        }

        val effectiveSsid = _wifiState.value.ssid ?: cleanSsid
        val effectiveBssid = _wifiState.value.bssid ?: cleanBssid

        val redactedReason = when {
            !hasFine -> "ACCESS_FINE_LOCATION permission missing"
            !locOn -> "Location services disabled in Settings"
            !hasNearby -> "NEARBY_WIFI_DEVICES permission missing"
            effectiveSsid == null && effectiveBssid == null -> "SSID/BSSID hidden by Android location gating"
            effectiveSsid == null -> "SSID hidden by Android location gating"
            effectiveBssid == null -> "BSSID hidden by Android location gating"
            else -> null
        }

        _wifiState.value = _wifiState.value.copy(
            connected = true,
            ssid = effectiveSsid,
            bssid = effectiveBssid,
            rssi = rawRssi,
            smoothedRssi = smoothedEmaRssi,
            freqMhz = freq,
            channel = ch,
            band = band,
            linkMbps = linkSpeed,
            redactedReason = redactedReason,
            timestamp = System.currentTimeMillis()
        )

        _diagnostics.value = _diagnostics.value.copy(
            callbackFlagActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            transportInfoClass = connInfo?.javaClass?.simpleName ?: _diagnostics.value.transportInfoClass,
            rawSsid = rawSsid ?: _diagnostics.value.rawSsid,
            rawBssid = rawBssid ?: _diagnostics.value.rawBssid,
            rssi = rawRssi,
            freqMhz = freq,
            connected = true,
            apiLevel = Build.VERSION.SDK_INT,
            releaseVersion = Build.VERSION.RELEASE,
            locationServicesEnabled = locOn,
            hasFineLocation = hasFine,
            hasNearbyDevices = hasNearby,
            timestampMs = System.currentTimeMillis()
        )

        // Feed live raw RSSI directly into proximity beep (connected AP)
        if (rawRssi != null) {
            proximityBeepService.updateRssi(rawRssi, effectiveBssid, effectiveSsid, isConnected = true)
        }
    }

    private fun handleNetworkCallbackEvent(wifiInfo: WifiInfo?) {
        val connected = isWifiConnected()
        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasNearby = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else true
        val locOn = isLocationServicesEnabled()
        _isLocationEnabled.value = locOn

        val rawSsid = wifiInfo?.ssid?.trim('"', ' ')
        val cleanSsid = when {
            rawSsid.isNullOrBlank() || rawSsid == "<unknown ssid>" -> null
            else -> rawSsid
        }
        val rawBssid = wifiInfo?.bssid
        val cleanBssid = when {
            rawBssid.isNullOrBlank() || rawBssid == "02:00:00:00:00:00" -> null
            else -> rawBssid
        }

        val effectiveSsid = cleanSsid ?: _wifiState.value.ssid
        val effectiveBssid = cleanBssid ?: _wifiState.value.bssid

        val rawRssi = if (wifiInfo != null && wifiInfo.rssi != -127 && wifiInfo.rssi != -999) {
            wifiInfo.rssi
        } else _wifiState.value.rssi

        val freq = if (wifiInfo != null && wifiInfo.frequency > 0) wifiInfo.frequency else _wifiState.value.freqMhz
        val (ch, band) = VitalCalculators.frequencyToChannel(freq)
        val linkSpeed = if (wifiInfo != null && wifiInfo.linkSpeed > 0) wifiInfo.linkSpeed else _wifiState.value.linkMbps

        val redactedReason = when {
            !hasFine -> "ACCESS_FINE_LOCATION permission missing"
            !locOn -> "Location services disabled in Settings"
            !hasNearby -> "NEARBY_WIFI_DEVICES permission missing"
            effectiveSsid == null && effectiveBssid == null -> "SSID/BSSID hidden by Android location gating"
            effectiveSsid == null -> "SSID hidden by Android location gating"
            effectiveBssid == null -> "BSSID hidden by Android location gating"
            else -> null
        }

        _wifiState.value = _wifiState.value.copy(
            connected = connected,
            ssid = effectiveSsid,
            bssid = effectiveBssid,
            rssi = rawRssi ?: _wifiState.value.rssi,
            freqMhz = freq ?: _wifiState.value.freqMhz,
            channel = ch ?: _wifiState.value.channel,
            band = band ?: _wifiState.value.band,
            linkMbps = linkSpeed ?: _wifiState.value.linkMbps,
            redactedReason = redactedReason,
            timestamp = System.currentTimeMillis()
        )

        _diagnostics.value = _diagnostics.value.copy(
            callbackFlagActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S,
            transportInfoClass = wifiInfo?.javaClass?.simpleName ?: "null",
            rawSsid = rawSsid ?: "null",
            rawBssid = rawBssid ?: "null",
            rssi = rawRssi,
            freqMhz = freq,
            connected = connected,
            apiLevel = Build.VERSION.SDK_INT,
            releaseVersion = Build.VERSION.RELEASE,
            locationServicesEnabled = locOn,
            hasFineLocation = hasFine,
            hasNearbyDevices = hasNearby,
            timestampMs = System.currentTimeMillis()
        )

        if (rawRssi != null) {
            proximityBeepService.updateRssi(rawRssi, effectiveBssid, effectiveSsid, isConnected = connected)
        }
    }

    private fun handleNetworkLost() {
        val stillConnected = isWifiConnected()
        if (stillConnected) return

        val hasFine = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val locOn = isLocationServicesEnabled()

        val reason = when {
            !hasFine -> "ACCESS_FINE_LOCATION permission missing"
            !locOn -> "Location services disabled in Settings"
            else -> "Wi-Fi disconnected"
        }

        _wifiState.value = com.example.netrix.model.WifiState(
            connected = false,
            redactedReason = reason,
            timestamp = System.currentTimeMillis()
        )

        _diagnostics.value = _diagnostics.value.copy(
            connected = false,
            rssi = null,
            timestampMs = System.currentTimeMillis()
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
        val connBssid = _wifiState.value.bssid

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
        val locEnabled = isLocationServicesEnabled()
        _isLocationEnabled.value = locEnabled

        // 1. Check Connectivity & Link Properties
        val isWifi = isWifiConnected()
        val activeNetwork = try { connectivityManager?.activeNetwork } catch (_: Throwable) { null }
        val linkProperties: LinkProperties? = try { activeNetwork?.let { connectivityManager?.getLinkProperties(it) } } catch (_: Throwable) { null }

        // Track Roaming Events
        val bssid = _wifiState.value.bssid
        if (bssid != null && lastObservedBssid != null && bssid != lastObservedBssid) {
            _roamingEventsCount.value += 1
        }
        if (bssid != null) {
            lastObservedBssid = bssid
        }

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

        // 2. Run Periodic Network Probes (Gateway & DNS)
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

        val liveRssi = _wifiState.value.rssi
        val (healthScoreVal, healthScoreStatus) = VitalCalculators.calculateNetworkHealth(
            rssi = liveRssi,
            gatewayLatencyMs = gatewayProbeDuration,
            dnsResolveMs = dnsProbeDuration,
            internetReachable = wanReachable,
            packetLossPct = packetLossVal
        )

        // Requirement D: Decouple Vitals. Each metric's error text may only describe its own failure.
        val records = mutableMapOf<VitalMetric, MetricRecord>()
        fun addRecord(
            metric: VitalMetric,
            value: String?,
            numeric: Double? = null,
            status: MetricStatus = MetricStatus.AVAILABLE,
            source: String = "Direct Probe",
            method: String = if (metric.isDerived) "Validated Formula" else "Direct Probe",
            unavailableText: String? = null
        ) {
            val displayValue = value ?: unavailableText ?: status.label
            records[metric] = MetricRecord(
                vital = metric,
                valueString = displayValue,
                numericValue = numeric,
                sourceApi = source,
                calculationMethod = method,
                timestamp = now,
                status = status
            )
        }

        val currentWifi = _wifiState.value
        val redactionText = currentWifi.redactedReason ?: "hidden by Android"

        addRecord(
            VitalMetric.SSID,
            currentWifi.ssid,
            status = if (currentWifi.ssid != null) MetricStatus.AVAILABLE else MetricStatus.PERMISSION_REQUIRED,
            source = "WifiInfo.ssid",
            unavailableText = redactionText
        )
        addRecord(
            VitalMetric.BSSID,
            currentWifi.bssid,
            status = if (currentWifi.bssid != null) MetricStatus.AVAILABLE else MetricStatus.PERMISSION_REQUIRED,
            source = "WifiInfo.bssid",
            unavailableText = redactionText
        )
        addRecord(
            VitalMetric.RSSI,
            liveRssi?.let { "$it dBm (raw)" },
            numeric = liveRssi?.toDouble(),
            status = if (liveRssi != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.rssi",
            unavailableText = if (!isWifi) "Wi-Fi disconnected" else "No signal reading"
        )
        val (qualityVal, _) = VitalCalculators.calculateSignalQuality(liveRssi)
        addRecord(
            VitalMetric.SIGNAL_QUALITY,
            qualityVal?.let { "$it%" },
            numeric = qualityVal?.toDouble(),
            status = if (qualityVal != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            method = "RFC Formula ((RSSI + 100) * 2)",
            unavailableText = "Requires active RSSI"
        )
        addRecord(
            VitalMetric.CHANNEL,
            currentWifi.channel?.toString(),
            numeric = currentWifi.channel?.toDouble(),
            status = if (currentWifi.channel != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "Frequency Lookup"
        )
        addRecord(
            VitalMetric.FREQUENCY,
            currentWifi.freqMhz?.let { "$it MHz" },
            numeric = currentWifi.freqMhz?.toDouble(),
            status = if (currentWifi.freqMhz != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.frequency"
        )
        addRecord(
            VitalMetric.CHANNEL_WIDTH,
            "${currentWifi.widthMhz ?: 20} MHz",
            numeric = (currentWifi.widthMhz ?: 20).toDouble(),
            status = MetricStatus.AVAILABLE,
            source = "ScanResult.channelWidth"
        )
        addRecord(
            VitalMetric.LINK_RATE,
            currentWifi.linkMbps?.let { "$it Mbps" },
            numeric = currentWifi.linkMbps?.toDouble(),
            status = if (currentWifi.linkMbps != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "WifiInfo.linkSpeed"
        )
        addRecord(VitalMetric.NOISE_FLOOR, null, status = MetricStatus.UNAVAILABLE, source = "Restricted OEM API", unavailableText = "Restricted OEM API")
        addRecord(VitalMetric.SNR, null, status = MetricStatus.UNAVAILABLE, source = "Restricted OEM API", unavailableText = "Restricted OEM API")
        addRecord(
            VitalMetric.LATENCY,
            gatewayProbeDuration?.let { "$it ms" },
            numeric = gatewayProbeDuration?.toDouble(),
            status = gatewayProbeStatus,
            source = "ICMP/TCP Echo",
            unavailableText = if (!isWifi) "Wi-Fi disconnected" else "Probe timed out"
        )
        addRecord(
            VitalMetric.PACKET_LOSS,
            packetLossVal?.let { String.format("%.1f%%", it) },
            numeric = packetLossVal?.toDouble(),
            status = packetLossStatus,
            method = "Failed / Attempted Probes"
        )
        addRecord(
            VitalMetric.JITTER,
            jitterVal?.let { "$it ms" },
            numeric = jitterVal?.toDouble(),
            status = jitterStatus,
            method = "RFC 3550 Jitter Formula",
            unavailableText = "Need >= 2 latency probes"
        )
        addRecord(
            VitalMetric.GATEWAY_IP,
            gatewayIp,
            status = if (gatewayIp != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "LinkProperties.routes",
            unavailableText = "Default route not resolved"
        )
        addRecord(
            VitalMetric.GATEWAY_REACHABILITY,
            if (gatewayProbeStatus == MetricStatus.AVAILABLE) "OK" else null,
            status = gatewayProbeStatus,
            source = "TCP/ICMP Socket",
            unavailableText = if (!isWifi) "Wi-Fi disconnected" else "Gateway unreachable"
        )
        addRecord(
            VitalMetric.DNS_SERVERS,
            dnsString,
            status = if (dnsString != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "LinkProperties.dnsServers",
            unavailableText = "No DNS servers configured"
        )
        addRecord(
            VitalMetric.DNS_RESOLVE_TIME,
            dnsProbeDuration?.let { "$it ms" },
            numeric = dnsProbeDuration?.toDouble(),
            status = dnsProbeStatus,
            source = "InetAddress.getAllByName",
            unavailableText = "DNS resolution failed"
        )
        addRecord(
            VitalMetric.WAN_REACHABILITY,
            if (wanReachable == true) "OK" else null,
            status = wanStatus,
            source = "HTTPS 204 Probe",
            unavailableText = "Internet unreachable"
        )
        addRecord(VitalMetric.PUBLIC_IP, null, status = MetricStatus.NOT_TESTED, source = "External IP Lookup", unavailableText = "External IP lookup not run")
        addRecord(
            VitalMetric.SUBNET_PREFIX,
            subnetStr,
            status = if (subnetStr != null) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "LinkAddress.prefixLength",
            unavailableText = "Subnet prefix unavailable"
        )

        val dhcpLeaseSec = @Suppress("DEPRECATION") wifiManager?.dhcpInfo?.leaseDuration
        addRecord(
            VitalMetric.DHCP_LEASE,
            if (dhcpLeaseSec != null && dhcpLeaseSec > 0) "${dhcpLeaseSec}s" else null,
            numeric = dhcpLeaseSec?.toDouble(),
            status = if (dhcpLeaseSec != null && dhcpLeaseSec > 0) MetricStatus.AVAILABLE else MetricStatus.UNAVAILABLE,
            source = "DhcpInfo",
            unavailableText = "DHCP lease unknown"
        )
        addRecord(VitalMetric.CONNECTED_CLIENTS, null, status = MetricStatus.UNAVAILABLE, source = "Local Neighbor Probing", unavailableText = "Local client discovery unavailable")
        addRecord(VitalMetric.DEAD_ZONE_COUNT, "0", numeric = 0.0, status = MetricStatus.AVAILABLE, method = "RSSI < -80 dBm Threshold")
        addRecord(VitalMetric.COVERAGE_SCORE, null, status = MetricStatus.INSUFFICIENT_DATA, method = "Floor Plan Spatial Coverage", unavailableText = "Need >= 3 survey points")
        addRecord(
            VitalMetric.NETWORK_HEALTH,
            healthScoreVal?.let { "$it/100" },
            numeric = healthScoreVal?.toDouble(),
            status = healthScoreStatus,
            method = "Composite Weighted Metric",
            unavailableText = "Insufficient metrics"
        )
        addRecord(VitalMetric.ROAMING_EVENTS, "${_roamingEventsCount.value}", numeric = _roamingEventsCount.value.toDouble(), status = MetricStatus.AVAILABLE, source = "BSSID Transition Detector")
        addRecord(VitalMetric.SAMPLE_TEST_COUNT, "$totalProbesAttempted probes", numeric = totalProbesAttempted.toDouble(), status = MetricStatus.AVAILABLE, source = "Active Probe Counter")

        _vitals.value = records

        // Persist observation to Room database asynchronously in Dispatchers.IO
        val observation = NetworkObservationEntity(
            sessionId = activeSessionId,
            timestamp = now,
            ssid = currentWifi.ssid,
            bssid = bssid,
            rssi = liveRssi,
            signalQuality = qualityVal,
            channel = currentWifi.channel,
            frequencyMhz = currentWifi.freqMhz,
            linkSpeedMbps = currentWifi.linkMbps,
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
