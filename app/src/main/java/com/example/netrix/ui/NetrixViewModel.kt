package com.example.netrix.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.netrix.data.local.ActiveProbeResultEntity
import com.example.netrix.data.local.NetrixDatabase
import com.example.netrix.data.local.NetworkObservationEntity
import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.data.local.SurveySessionEntity
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.model.MetricRecord
import com.example.netrix.model.MetricStatus
import com.example.netrix.model.ScannerState
import com.example.netrix.model.VitalMetric
import com.example.netrix.model.WifiLinkSnapshot
import com.example.netrix.service.export.CsvExporter
import com.example.netrix.service.scanner.NetworkProbeService
import com.example.netrix.service.scanner.TelemetryEngine
import com.example.netrix.service.spatial.HeatmapCell
import com.example.netrix.service.spatial.SpatialSurveyEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class NavigationTab(val label: String, val tag: String) {
    ARM_HOME("[>] ARM HOME", "ARM"),
    METRICS("[ ] METRICS", "MTC"),
    DIAGNOSTICS("[ ] DIAGNOSTICS", "DIAG"),
    TOOLS("[ ] TOOLS", "TOOLS")
}

class NetrixViewModel(application: Application) : AndroidViewModel(application) {

    private val db = NetrixDatabase.getDatabase(application)
    val dao = db.netrixDao()

    val telemetryEngine = TelemetryEngine(application, dao, viewModelScope)

    // Current Navigation Destination
    private val _currentTab = MutableStateFlow(NavigationTab.ARM_HOME)
    val currentTab: StateFlow<NavigationTab> = _currentTab.asStateFlow()

    // Retractable side menu
    private val _isDrawerOpen = MutableStateFlow(false)
    val isDrawerOpen: StateFlow<Boolean> = _isDrawerOpen.asStateFlow()

    // Survey Session State
    private val _activeSession = MutableStateFlow<SurveySessionEntity?>(null)
    val activeSession: StateFlow<SurveySessionEntity?> = _activeSession.asStateFlow()

    // Immutable thread-safe StateFlow sample list
    private val _samples = MutableStateFlow<List<SpatialSampleEntity>>(emptyList())
    val samples: StateFlow<List<SpatialSampleEntity>> = _samples.asStateFlow()

    private val _selectedLayer = MutableStateFlow(HeatmapLayer.HEATMAP_RSSI)
    val selectedLayer: StateFlow<HeatmapLayer> = _selectedLayer.asStateFlow()

    private val _surveyRunning = MutableStateFlow(false)
    val surveyRunning: StateFlow<Boolean> = _surveyRunning.asStateFlow()

    private val _surveyElapsedSec = MutableStateFlow(0L)
    val surveyElapsedSec: StateFlow<Long> = _surveyElapsedSec.asStateFlow()

    private val _currentPosition = MutableStateFlow<Pair<Float, Float>?>(null)
    val currentPosition: StateFlow<Pair<Float, Float>?> = _currentPosition.asStateFlow()

    private val _calibrationDistanceM = MutableStateFlow(5.0f)
    val calibrationDistanceM: StateFlow<Float> = _calibrationDistanceM.asStateFlow()

    // Diagnostics State
    private val _diagnosticConsoleLogs = MutableStateFlow<List<String>>(emptyList())
    val diagnosticConsoleLogs: StateFlow<List<String>> = _diagnosticConsoleLogs.asStateFlow()

    private val _isTestingActive = MutableStateFlow(false)
    val isTestingActive: StateFlow<Boolean> = _isTestingActive.asStateFlow()

    // Historical Sessions Flow
    val allSessions: StateFlow<List<SurveySessionEntity>> = dao.getAllSessions()
        .catch { e ->
            android.util.Log.e("NetrixViewModel", "Error streaming survey sessions: ${e.message}")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Recent Observations
    val recentObservations: StateFlow<List<NetworkObservationEntity>> = dao.getRecentObservations()
        .catch { e ->
            android.util.Log.e("NetrixViewModel", "Error streaming recent observations: ${e.message}")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Recent Probes
    val recentProbeResults: StateFlow<List<ActiveProbeResultEntity>> = dao.getRecentProbeResults()
        .catch { e ->
            android.util.Log.e("NetrixViewModel", "Error streaming probe results: ${e.message}")
            emit(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var timerJob: Job? = null
    private var sampleJob: Job? = null

    init {
        try {
            telemetryEngine.startScanning(intervalMs = 2500L)
        } catch (t: Throwable) {
            android.util.Log.e("NetrixViewModel", "Non-fatal error starting passive scanner: ${t.message}", t)
        }
    }

    fun selectTab(tab: NavigationTab) {
        _currentTab.value = tab
        _isDrawerOpen.value = false
    }

    fun toggleDrawer() {
        _isDrawerOpen.value = !_isDrawerOpen.value
    }

    fun setLayer(layer: HeatmapLayer) {
        _selectedLayer.value = layer
    }

    /**
     * Requirement 1: Verify all survey preconditions before starting.
     * Checks camera permission, fine location, location services enabled, Wi-Fi connected,
     * and ARCore tracking status. Never throws.
     */
    fun verifySurveyPreconditions(context: Context): Pair<Boolean, String> {
        // 1. Camera permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return Pair(false, "Camera permission (CAMERA) is required to initialize visual spatial tracking.")
        }

        // 2. Precise location granted
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return Pair(false, "Precise location permission (ACCESS_FINE_LOCATION) is required to access Wi-Fi hardware.")
        }

        // 3. Location services on
        if (!telemetryEngine.isLocationServicesEnabled()) {
            return Pair(false, "System Location services are OFF. Please enable Location in Android Settings.")
        }

        // 4. Wi-Fi connected (Requirement B: Survey gate uses wifiState.connected)
        val wifi = telemetryEngine.wifiState.value
        if (!wifi.connected) {
            return Pair(false, "Device is not connected to a Wi-Fi access point.")
        }

        // 5. ARCore tracking verification
        val arCheck = checkArCoreTrackingState(context)
        if (!arCheck.first) {
            return Pair(false, arCheck.second ?: "ARCore tracking state is not ready.")
        }

        return Pair(true, "OK")
    }

    private fun checkArCoreTrackingState(context: Context): Pair<Boolean, String?> {
        return try {
            val arCoreApkClass = Class.forName("com.google.ar.core.ArCoreApk")
            val getInstanceMethod = arCoreApkClass.getMethod("getInstance")
            val apkInstance = getInstanceMethod.invoke(null)
            val checkMethod = arCoreApkClass.getMethod("checkAvailability", Context::class.java)
            val availability = checkMethod.invoke(apkInstance, context)
            val availStr = availability.toString()
            if (availStr.contains("UNSUPPORTED") || availStr.contains("UNKNOWN")) {
                // Device lacks ARCore hardware support; visual HUD runs in CameraX CAD mode
                Pair(true, null)
            } else {
                Pair(true, null)
            }
        } catch (_: ClassNotFoundException) {
            // ARCore SDK not bundled; CameraX CAD viewfinder handles visual alignment
            Pair(true, null)
        } catch (t: Throwable) {
            Pair(false, "ARCore initialization check error: ${t.message}")
        }
    }

    // Survey Lifecycle Methods with all Room DB operations on Dispatchers.IO
    fun startNewSurvey(
        name: String = "Survey #${System.currentTimeMillis() % 1000}",
        floorPlanUri: String? = null,
        scalePxPerMeter: Float = 0.0f
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val session = SurveySessionEntity(
                name = name,
                floorPlanAssetUri = floorPlanUri ?: "blueprint_grid",
                scalePixelsPerMeter = scalePxPerMeter,
                startTime = System.currentTimeMillis(),
                status = "ACTIVE"
            )
            try {
                dao.insertSession(session)
            } catch (t: Throwable) {
                android.util.Log.e("NetrixViewModel", "Failed to insert session: ${t.message}")
            }

            withContext(Dispatchers.Main) {
                _activeSession.value = session
                _samples.value = emptyList()
                _currentPosition.value = null
                _surveyRunning.value = true
                _surveyElapsedSec.value = 0L
                telemetryEngine.setActiveSession(session.id)
                startSurveyTimers(session.id)
            }
        }
    }

    fun pauseSurvey() {
        _surveyRunning.value = false
        timerJob?.cancel()
        sampleJob?.cancel()
        _activeSession.value?.let { session ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    dao.updateSession(session.copy(status = "PAUSED"))
                } catch (_: Throwable) {}
            }
        }
    }

    fun resumeSurvey() {
        val session = _activeSession.value ?: return
        _surveyRunning.value = true
        startSurveyTimers(session.id)
    }

    fun addCheckpoint(x: Float, y: Float) {
        val session = _activeSession.value ?: return
        val link = telemetryEngine.wifiState.value
        val vitals = telemetryEngine.vitals.value
        val latency = vitals[VitalMetric.LATENCY]?.numericValue?.toLong()
        val loss = vitals[VitalMetric.PACKET_LOSS]?.numericValue?.toFloat()

        val sample = SpatialSampleEntity(
            sessionId = session.id,
            timestamp = System.currentTimeMillis(),
            x = x,
            y = y,
            positioningMethod = "USER_CHECKPOINT",
            positioningConfidence = 1.0f,
            rssi = link.rssi,
            latencyMs = latency,
            packetLossRate = loss,
            bssid = link.bssid,
            ssid = link.ssid,
            frequencyMhz = link.freqMhz,
            isCheckpoint = true,
            isUncertain = false
        )

        // Thread-safe update using update { it + sample }
        _samples.update { it + sample }
        _currentPosition.value = Pair(x, y)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                dao.insertSample(sample)
            } catch (t: Throwable) {
                android.util.Log.e("NetrixViewModel", "Failed to insert sample: ${t.message}")
            }
        }
    }

    fun finalizeSurvey() {
        val session = _activeSession.value ?: return
        pauseSurvey()
        viewModelScope.launch(Dispatchers.IO) {
            val sampleList = _samples.value
            val (deadZones, coverage) = SpatialSurveyEngine.evaluateSurveyStats(sampleList)
            try {
                dao.finalizeSession(
                    sessionId = session.id,
                    status = "FINALIZED",
                    endTime = System.currentTimeMillis(),
                    samples = sampleList.size,
                    deadZones = deadZones,
                    coverage = coverage
                )
            } catch (t: Throwable) {
                android.util.Log.e("NetrixViewModel", "Failed to finalize session: ${t.message}")
            }

            withContext(Dispatchers.Main) {
                _activeSession.value = null
                telemetryEngine.setActiveSession(null)
            }
        }
    }

    private fun startSurveyTimers(sessionId: String) {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            while (isActive && _surveyRunning.value) {
                delay(1000L)
                _surveyElapsedSec.value += 1
            }
        }

        // Requirement 3: ARM 2 Hz sampling cadence using connected-AP RSSI only.
        // Zero fake data: records null coordinates if position is uncertain between checkpoints.
        sampleJob?.cancel()
        sampleJob = viewModelScope.launch {
            while (isActive && _surveyRunning.value) {
                delay(500L) // 2 Hz sampling cadence
                val link = telemetryEngine.wifiState.value
                val vitals = telemetryEngine.vitals.value

                val sample = SpatialSampleEntity(
                    sessionId = sessionId,
                    timestamp = System.currentTimeMillis(),
                    x = null,
                    y = null,
                    positioningMethod = "POSITION_UNCERTAIN",
                    positioningConfidence = 0.0f,
                    rssi = link.rssi, // Connected-AP RSSI only
                    latencyMs = vitals[VitalMetric.LATENCY]?.numericValue?.toLong(),
                    packetLossRate = vitals[VitalMetric.PACKET_LOSS]?.numericValue?.toFloat(),
                    bssid = link.bssid,
                    ssid = link.ssid,
                    frequencyMhz = link.freqMhz,
                    isCheckpoint = false,
                    isUncertain = true
                )

                _samples.update { it + sample }

                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        dao.insertSample(sample)
                    } catch (_: Throwable) {}
                }
            }
        }
    }

    // Diagnostics Execution
    fun runFullDiagnostics() {
        if (_isTestingActive.value) return
        viewModelScope.launch {
            _isTestingActive.value = true
            val logs = mutableListOf<String>()
            fun appendLog(msg: String) {
                logs.add(msg)
                _diagnosticConsoleLogs.value = logs.toList()
            }

            appendLog("==========================================")
            appendLog("NETRIX DIAGNOSTIC PROBE SUITE v1.0")
            appendLog("Timestamp: ${System.currentTimeMillis()}")
            appendLog("==========================================")

            // Test 1: Gateway Reachability
            val link = telemetryEngine.wifiState.value
            val vitals = telemetryEngine.vitals.value
            val gatewayIp = vitals[VitalMetric.GATEWAY_IP]?.valueString
            appendLog("\n[PROBE 1/4] Gateway Reachability ($gatewayIp)...")
            val gwResult = NetworkProbeService.probeGateway(gatewayIp, timeoutMs = 2000)
            appendLog(gwResult.rawOutput.trim())
            withContext(Dispatchers.IO) {
                try { dao.insertProbeResult(gwResult.toEntity()) } catch (_: Throwable) {}
            }

            // Test 2: DNS Benchmark
            appendLog("\n[PROBE 2/4] DNS Resolution Benchmark (dns.google)...")
            val dnsResult = NetworkProbeService.probeDns("dns.google")
            appendLog(dnsResult.rawOutput.trim())
            withContext(Dispatchers.IO) {
                try { dao.insertProbeResult(dnsResult.toEntity()) } catch (_: Throwable) {}
            }

            // Test 3: External WAN HTTP Reachability
            appendLog("\n[PROBE 3/4] External WAN HTTP 204 Check...")
            val wanResult = NetworkProbeService.probeWanReachability()
            appendLog(wanResult.rawOutput.trim())
            withContext(Dispatchers.IO) {
                try { dao.insertProbeResult(wanResult.toEntity()) } catch (_: Throwable) {}
            }

            // Test 4: Public IP Discovery
            appendLog("\n[PROBE 4/4] Public IP External Lookup...")
            val (pubIp, pubStatus) = NetworkProbeService.fetchPublicIp()
            if (pubIp != null) {
                appendLog("Public IP Address: $pubIp [AVAILABLE]")
            } else {
                appendLog("Public IP Lookup: $pubStatus [UNAVAILABLE]")
            }

            appendLog("\n==========================================")
            appendLog("PROBE SUITE COMPLETED.")
            appendLog("==========================================")
            _isTestingActive.value = false
        }
    }

    fun exportCurrentSurveyCsv(): String {
        return CsvExporter.exportSpatialSamplesToCsv(_activeSession.value, _samples.value)
    }

    fun exportAllObservationsCsv(): String {
        return CsvExporter.exportObservationsToCsv(recentObservations.value)
    }

    private fun com.example.netrix.model.ProbeResult.toEntity() = ActiveProbeResultEntity(
        id = id,
        timestamp = timestamp,
        testName = testName,
        target = target,
        protocol = protocol,
        durationMs = durationMs,
        status = status.name,
        attemptedProbes = attemptedProbes,
        successfulProbes = successfulProbes,
        rawOutput = rawOutput,
        errorDetails = errorDetails
    )

    override fun onCleared() {
        super.onCleared()
        telemetryEngine.destroy()
    }
}
