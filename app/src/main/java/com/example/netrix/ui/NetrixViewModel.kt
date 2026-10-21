package com.example.netrix.ui

import android.app.Application
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
            // Start foreground passive telemetry scanning safely
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

    // Survey Lifecycle Methods
    fun startNewSurvey(
        name: String = "Survey #${System.currentTimeMillis() % 1000}",
        floorPlanUri: String? = null,
        scalePxPerMeter: Float = 0.0f
    ) {
        viewModelScope.launch {
            val session = SurveySessionEntity(
                name = name,
                floorPlanAssetUri = floorPlanUri ?: "blueprint_grid",
                scalePixelsPerMeter = scalePxPerMeter,
                startTime = System.currentTimeMillis(),
                status = "ACTIVE"
            )
            dao.insertSession(session)
            _activeSession.value = session
            _samples.value = emptyList()
            _currentPosition.value = null
            _surveyRunning.value = true
            _surveyElapsedSec.value = 0L
            telemetryEngine.setActiveSession(session.id)

            startSurveyTimers(session.id)
        }
    }

    fun pauseSurvey() {
        _surveyRunning.value = false
        timerJob?.cancel()
        sampleJob?.cancel()
        _activeSession.value?.let {
            viewModelScope.launch {
                dao.updateSession(it.copy(status = "PAUSED"))
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
        viewModelScope.launch {
            val link = telemetryEngine.currentLink.value
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
                frequencyMhz = link.frequencyMhz,
                isCheckpoint = true,
                isUncertain = false
            )
            dao.insertSample(sample)
            _samples.value = _samples.value + sample
            _currentPosition.value = Pair(x, y)
        }
    }

    fun finalizeSurvey() {
        val session = _activeSession.value ?: return
        pauseSurvey()
        viewModelScope.launch {
            val sampleList = _samples.value
            val (deadZones, coverage) = SpatialSurveyEngine.evaluateSurveyStats(sampleList)
            dao.finalizeSession(
                sessionId = session.id,
                status = "FINALIZED",
                endTime = System.currentTimeMillis(),
                samples = sampleList.size,
                deadZones = deadZones,
                coverage = coverage
            )
            _activeSession.value = null
            telemetryEngine.setActiveSession(null)
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

        // Automatic passive telemetry collection every 3 seconds
        // Zero-fake-data policy: Do not invent indoor tracking between checkpoints.
        // Save telemetry with null coordinates and mark position uncertain.
        sampleJob?.cancel()
        sampleJob = viewModelScope.launch {
            while (isActive && _surveyRunning.value) {
                delay(3000L)
                val link = telemetryEngine.currentLink.value
                val vitals = telemetryEngine.vitals.value

                val sample = SpatialSampleEntity(
                    sessionId = sessionId,
                    timestamp = System.currentTimeMillis(),
                    x = null,
                    y = null,
                    positioningMethod = "POSITION_UNCERTAIN",
                    positioningConfidence = 0.0f,
                    rssi = link.rssi,
                    latencyMs = vitals[VitalMetric.LATENCY]?.numericValue?.toLong(),
                    packetLossRate = vitals[VitalMetric.PACKET_LOSS]?.numericValue?.toFloat(),
                    bssid = link.bssid,
                    ssid = link.ssid,
                    frequencyMhz = link.frequencyMhz,
                    isCheckpoint = false,
                    isUncertain = true
                )
                dao.insertSample(sample)
                _samples.value = _samples.value + sample
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
            val link = telemetryEngine.currentLink.value
            val vitals = telemetryEngine.vitals.value
            val gatewayIp = vitals[VitalMetric.GATEWAY_IP]?.valueString
            appendLog("\n[PROBE 1/4] Gateway Reachability ($gatewayIp)...")
            val gwResult = NetworkProbeService.probeGateway(gatewayIp, timeoutMs = 2000)
            appendLog(gwResult.rawOutput.trim())
            dao.insertProbeResult(gwResult.toEntity())

            // Test 2: DNS Benchmark
            appendLog("\n[PROBE 2/4] DNS Resolution Benchmark (dns.google)...")
            val dnsResult = NetworkProbeService.probeDns("dns.google")
            appendLog(dnsResult.rawOutput.trim())
            dao.insertProbeResult(dnsResult.toEntity())

            // Test 3: External WAN HTTP Reachability
            appendLog("\n[PROBE 3/4] External WAN HTTP 204 Check...")
            val wanResult = NetworkProbeService.probeWanReachability()
            appendLog(wanResult.rawOutput.trim())
            dao.insertProbeResult(wanResult.toEntity())

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
}
