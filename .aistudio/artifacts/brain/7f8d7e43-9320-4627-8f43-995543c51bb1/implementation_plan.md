# NETRIX — Authoritative Specification Reconciliation (v1.1)

Consolidated implementation blueprint and architectural contract for **NETRIX**, strictly adhering to the Authoritative Project Clarification v1.1. Resolves all previous ambiguities, locks the two-engine architecture, enforces non-negotiable data integrity, decouples spatial positioning from telemetry collection, and formalizes the three-level validation model.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> **Authoritative Specification Reconciliation (v1.1 Source of Truth)**:
> All earlier requirements are superseded by this reconciled architecture. The following core architectural contracts are permanently locked:

1. **Two Core Engines Only**:
   - **Engine A (ARM — Augmented Reality Mapper)**: Calibrated floor-plan spatial surveying, checkpoint tracking, optional ARCore/CameraX spatial integration, and honest heatmap estimation with strict boundary isolation.
   - **Engine B (Scanner — Shared Telemetry & Diagnostics)**: Single source of truth across all views for passive link observation, Wi-Fi scan results, active network probes, derived metrics calculation, and telemetry persistence.
2. **Strict Zero-Fake-Data & State Vocabulary**:
   - No placeholder RSSI, ping, jitter, client counts, or synthetic survey data in production mode.
   - Missing or untested metrics are **never zero** (e.g. untested packet loss is not 0%, untested latency is not 0 ms).
   - Standardized state vocabulary applied to all 27 metrics: `AVAILABLE`, `UNAVAILABLE`, `NOT TESTED`, `PERMISSION REQUIRED`, `STALE`, `TIMED OUT`, `ERROR`, `INSUFFICIENT DATA`, `POSITION UNCERTAIN`.
3. **Decoupled Spatial Sampling**:
   - Telemetry collection and spatial positioning are separate capabilities. Valid network measurements are always preserved even if positioning is lost, uncertain, or unsupported, tagged with `POSITION UNCERTAIN` rather than fabricated coordinates.
4. **Three Distinct Validation Levels**:
   - **Level 1**: Deterministic software unit tests with isolated synthetic fixtures.
   - **Level 2**: Recorded-data replay through calculation, storage, and export logic (labeled explicitly as historical replay).
   - **Level 3**: Physical-device validation documenting device model, OS API level, RF chipset, and actual versus unsupported capabilities.
5. **Locked Visual Identity**:
   - Authentic Termux/Linux-terminal aesthetic: background `#08060D`, primary purple `#BB66FF`, secondary purple `#7D2CCB`, healthy `#00FF66`, warning `#FFCC00`, failure `#FF3366`, unavailable `#5A4D73`, IBM VGA8/monospace typography, thin outlines, dense tabular data.

---

## 1. Overview & Core Concept

**NETRIX** is a phone-first Android application for real Wi-Fi RF surveying, network diagnostics, and historical analytics. It provides:
- **ARM Home**: Floor-plan scale calibration, origin/orientation configuration, automatic spatial sampling, breadcrumb path, live camera viewfinder HUD, and interpolated RF heatmaps (RSSI, latency, packet loss, dead zones).
- **Metrics Spreadsheet**: Horizontally and vertically scrollable terminal grid with 27-vital metrics, column sorting, search/filters, time-window selection, and RFC 4180 CSV export.
- **Diagnostics Workspace**: Real network probe runner (Gateway socket ping, DNS query benchmarks, HTTP WAN check, port reachability) with raw outputs and evidence-based explanations.
- **Tools & Settings**: Survey calibration, scan interval policies, signal thresholds, permission management, and local database management.
- **Survey History**: Inspection, checkpoint review, and export of completed and resumed sessions.

---

## 2. Seven-Layer System Architecture

```
┌────────────────────────────────────────────────────────────────────────┐
│                        LAYER 7: PRESENTATION                           │
│  - ARM Home (CameraX HUD + FloorPlan Canvas + Survey Controller)      │
│  - Metrics Workspace (Spreadsheet Grid + Detail Inspector + Charts)   │
│  - Diagnostics Workspace (Terminal Test Runner + Evidence Logs)       │
│  - Tools & Survey History (Calibration + Retention + CSV Exporter)    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   LAYER 6: APPLICATION STATE & NAV                     │
│  - ArmViewModel (Session lifecycle, checkpoints, heatmap layers)      │
│  - ScannerViewModel (Live 27 vitals, filter/sort state, freshness)    │
│  - DiagnosticsViewModel (Active probe execution state & raw output)   │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│             LAYER 4: SPATIAL MAPPING & POSITIONING SERVICES            │
│  - FloorPlanScaleCalibrator (Pixel-to-meter ratio, coordinate origin)  │
│  - SpatialTracker (Step/inertial motion estimation & drift control)   │
│  - HeatmapEngine (Inverse-distance-weighting with unmeasured masks)    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│            LAYER 3: VALIDATION & DERIVED CALCULATION SERVICES          │
│  - SignalQualityCalculator (Validated formula: clamp((rssi+100)*2))    │
│  - NetworkHealthEvaluator (Minimum 4 valid probes required)            │
│  - JitterCalculator (RFC 3550 statistical algorithm over latencies)    │
│  - PacketLossCalculator (Failed probes / total valid attempts)         │
│  - DeadZoneDetector (Documented RSSI threshold: < -80 dBm)             │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│           LAYER 2: SHARED TELEMETRY & PROBE SERVICES (SCANNER)         │
│  - TelemetryEngine (Source of truth: coordinates link, scan, probes)   │
│  - SocketPingProbe (Direct TCP/ICMP socket reachability to target)     │
│  - DnsResolutionProbe (Direct resolver query timing & answer parsing)  │
│  - WanReachabilityProbe (HTTP 204 endpoint check with timeouts)        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   LAYER 1: ANDROID PLATFORM ADAPTERS                   │
│  - WifiManager & ScanResults (Respecting scan throttles & permissions) │
│  - ConnectivityManager, NetworkCapabilities, LinkProperties            │
│  - SensorManager (Step counter, accelerometer, gyroscope)              │
│  - CameraX & ARCore (Lifecycle-aware viewfinder & spatial tracking)    │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│                   LAYER 5: PERSISTENT STORAGE (ROOM)                   │
│  - SurveySessionEntity & FloorPlanEntity                               │
│  - SpatialSampleEntity (Coordinates, confidence, timestamp, link)      │
│  - NetworkObservationEntity (27 vitals raw snapshots)                  │
│  - ActiveProbeResultEntity (Probe logs, targets, latencies, errors)    │
│  - NetworkEventEntity (Roaming transitions, link drops, warnings)      │
└────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Data Integrity & The 27-Vital Metrics

Every metric record preserves:
- `metricId`: Stable enum key (1..27).
- `valueString`: Formatted display value (or status string).
- `numericValue`: Nullable double for math/sorting.
- `unit`: Specific engineering unit (`dBm`, `MHz`, `Mbps`, `ms`, `%`, etc.).
- `sourceApi`: Origin (`WifiInfo`, `ScanResult`, `LinkProperties`, `SocketProbe`, etc.).
- `timestamp`: Epoch milliseconds.
- `status`: One of `AVAILABLE`, `UNAVAILABLE`, `NOT TESTED`, `PERMISSION REQUIRED`, `STALE`, `TIMED OUT`, `ERROR`, `INSUFFICIENT DATA`, `POSITION UNCERTAIN`.
- `calculationMethod`: Raw observation vs. derived formula identifier.
- `freshnessAgeMs`: Elapsed time since observation.

### Explicit 27-Vital Specifications:
1. **SSID**: Real name or `UNAVAILABLE` / `PERMISSION REQUIRED`.
2. **BSSID**: Real MAC or `UNAVAILABLE` / redacted.
3. **RSSI**: Real dBm reading. Never positive, never 0 unless valid measurement.
4. **Signal Quality**: Derived percentage `clamp(((rssi + 100) * 2), 0, 100)%`.
5. **Channel**: Derived from center frequency (e.g. 2412 MHz -> Ch 1).
6. **Frequency**: Real center frequency in MHz & band (2.4 GHz, 5 GHz, 6 GHz).
7. **Channel Width**: Real bandwidth (20 MHz, 40 MHz, 80 MHz, 160 MHz) or `UNAVAILABLE`.
8. **Link Rate**: Negotiated PHY rate in Mbps.
9. **Noise Floor**: Hardware value or `UNAVAILABLE` (no guessing).
10. **SNR**: Signal-to-noise ratio only when both RSSI and Noise Floor are valid.
11. **Latency**: Timed socket round-trip duration in ms (target identified).
12. **Packet Loss**: Exact percentage of failed probes over attempted valid probes.
13. **Jitter**: RFC 3550 statistical variance: `J(i) = J(i-1) + (|D(i-1,i)| - J(i-1))/16`.
14. **Gateway IP**: Configured router address from LinkProperties.
15. **Gateway Reachability**: Real socket ping outcome to gateway IP.
16. **DNS Servers**: Configured resolver IPs.
17. **DNS Resolve Time**: Real query duration in ms.
18. **WAN / Internet Reachability**: Real HTTP check result (`AVAILABLE`, `TIMED OUT`, `ERROR`).
19. **Public IP**: Real external IP lookup or `UNAVAILABLE`.
20. **Subnet / Prefix**: Network prefix length (e.g. /24).
21. **DHCP Lease Status**: Real lease duration from DhcpInfo or `UNAVAILABLE`.
22. **Connected Client Count**: Discovered local ARP/subnet neighbors with clear disclosure that it is a limited observation, not an authoritative router table.
23. **Dead-Zone Count**: Spatial count of samples with valid coordinates and RSSI < -80 dBm.
24. **Coverage Score**: Spatial coverage percentage of surveyed floor plan area.
25. **Network Health Score**: Composite weighted score requiring at least 4 valid telemetry probes.
26. **Roaming / Anomaly Events**: Real timestamped events when BSSID transitions occur.
27. **Sample / Test Count**: Explicit counts of total valid observations, spatial samples, and active probe runs.

---

## 4. ARM Spatial Mapping & Heatmap Interpolation

- **Floor Plan Engine**: Custom Jetpack Compose Canvas supporting pan, pinch-to-zoom, coordinate origin placement, distance calibration ruler, and user starting point.
- **Positioning Separation**:
  - If step/inertial tracking is confident: sample placed at `(x, y)` with `AVAILABLE` confidence.
  - If tracking is lost or uncalibrated: sample saved to database with `x = null, y = null` and marked `POSITION UNCERTAIN`. The measurement is never discarded and never assigned invented coordinates.
- **Heatmap Boundaries**:
  - Uses Inverse Distance Weighting (IDW) interpolation strictly within a defined influence radius of real samples.
  - Cells beyond the sampling radius are rendered as unmeasured grid cells (muted purple-gray grid lines with void background), **never** as weak signal or interpolated fiction.
  - Clear visual distinction between raw sample points (bright dots) and estimated interpolated grid.

---

## 5. Persistence, Export & Background Policy

- **Room Database Entities**:
  - `SurveySessionEntity`: UUID, title, floorPlanUri, scalePixelsPerMeter, startTime, endTime, status (`ACTIVE`, `PAUSED`, `FINALIZED`).
  - `SpatialSampleEntity`: UUID, sessionId, timestamp, x, y, positioningMethod, positioningConfidence, rssi, latencyMs, packetLossRate, bssid.
  - `NetworkObservationEntity`: UUID, sessionId, timestamp, 27-vital serialized snapshot, freshness.
  - `ActiveProbeResultEntity`: UUID, testType, targetHost, targetPort, durationMs, outcomeStatus, rawOutput.
- **RFC 4180 CSV Export**:
  - Complete export of sessions, spatial points, and raw observations preserving exact units, timestamps, and explicit empty/status strings for missing values (never converting missing values to zeroes).
- **Background Policy**:
  - Active sampling runs in foreground. If survey is ongoing, user-authorized foreground service is maintained with an active notification. When user leaves ARM, camera/AR hardware is immediately released.

---

## 6. Three-Level Backtesting & Release Gates

- **Level 1 (Deterministic Software Tests)**:
  - Unit tests verifying RFC 3550 jitter calculation, packet-loss ratio math, signal quality clamp, channel-to-frequency conversions, dead-zone threshold filtering, and Room database migrations.
- **Level 2 (Recorded-Data Replay)**:
  - Replay engine feeding historical survey datasets through calculations and CSV export to verify deterministic processing and raw data preservation.
- **Level 3 (Physical-Device Validation Check)**:
  - System capability audit recording device model, Android API level, Wi-Fi hardware constraints, and platform permission states.
- **Release Verification**:
  - Project compilation check (`compile_applet`).
  - Verification of APK artifact generation and zero critical warnings.
