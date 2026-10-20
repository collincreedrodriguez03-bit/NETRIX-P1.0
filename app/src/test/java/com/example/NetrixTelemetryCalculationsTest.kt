package com.example

import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.model.MetricStatus
import com.example.netrix.service.export.CsvExporter
import com.example.netrix.service.scanner.VitalCalculators
import com.example.netrix.service.spatial.SpatialSurveyEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetrixTelemetryCalculationsTest {

    @Test
    fun testSignalQualityFormula() {
        // -100 dBm -> 0%
        val (q0, s0) = VitalCalculators.calculateSignalQuality(-100)
        assertEquals(0, q0)
        assertEquals(MetricStatus.AVAILABLE, s0)

        // -50 dBm -> 100%
        val (q100, s100) = VitalCalculators.calculateSignalQuality(-50)
        assertEquals(100, q100)
        assertEquals(MetricStatus.AVAILABLE, s100)

        // -75 dBm -> 50%
        val (q50, s50) = VitalCalculators.calculateSignalQuality(-75)
        assertEquals(50, q50)
        assertEquals(MetricStatus.AVAILABLE, s50)

        // Null -> UNAVAILABLE
        val (qNull, sNull) = VitalCalculators.calculateSignalQuality(null)
        assertNull(qNull)
        assertEquals(MetricStatus.UNAVAILABLE, sNull)
    }

    @Test
    fun testFrequencyToChannel() {
        // 2.4 GHz channel 1
        val (ch1, band1) = VitalCalculators.frequencyToChannel(2412)
        assertEquals(1, ch1)
        assertEquals("2.4 GHz", band1)

        // 2.4 GHz channel 6
        val (ch6, band6) = VitalCalculators.frequencyToChannel(2437)
        assertEquals(6, ch6)
        assertEquals("2.4 GHz", band6)

        // 5 GHz channel 36
        val (ch36, band5) = VitalCalculators.frequencyToChannel(5180)
        assertEquals(36, ch36)
        assertEquals("5 GHz", band5)

        // 6 GHz
        val (ch6g, band6g) = VitalCalculators.frequencyToChannel(5955)
        assertEquals(7, ch6g)
        assertEquals("6 GHz", band6g)
    }

    @Test
    fun testPacketLossStrictTruthfulness() {
        // 0 attempted probes -> NOT_TESTED (never 0% without tests!)
        val (loss0, s0) = VitalCalculators.calculatePacketLoss(totalProbes = 0, failedProbes = 0)
        assertNull(loss0)
        assertEquals(MetricStatus.NOT_TESTED, s0)

        // 10 attempted, 2 failed -> 20.0%
        val (loss20, s20) = VitalCalculators.calculatePacketLoss(totalProbes = 10, failedProbes = 2)
        assertEquals(20.0f, loss20!!, 0.01f)
        assertEquals(MetricStatus.AVAILABLE, s20)
    }

    @Test
    fun testRfc3550Jitter() {
        // Insufficient samples (<2)
        val (j0, s0) = VitalCalculators.calculateRfc3550Jitter(listOf(15L))
        assertNull(j0)
        assertEquals(MetricStatus.INSUFFICIENT_DATA, s0)

        // Sequential latencies
        val latencies = listOf(20L, 25L, 22L, 28L, 24L)
        val (jitter, sJitter) = VitalCalculators.calculateRfc3550Jitter(latencies)
        assertTrue(jitter != null && jitter > 0)
        assertEquals(MetricStatus.AVAILABLE, sJitter)
    }

    @Test
    fun testDeadZoneThreshold() {
        assertTrue(VitalCalculators.isDeadZone(-85, -80))
        assertFalse(VitalCalculators.isDeadZone(-72, -80))
        assertFalse(VitalCalculators.isDeadZone(null, -80))
    }

    @Test
    fun testScaleCalibration() {
        // 200 pixels spanning 5 meters -> 40 px/m
        val scale = SpatialSurveyEngine.calibrateScale(0f, 0f, 200f, 0f, 5.0f)
        assertEquals(40.0f, scale, 0.01f)
    }

    @Test
    fun testCsvExportPreservesNullsWithoutZeroing() {
        val samples = listOf(
            SpatialSampleEntity(
                sessionId = "test-session",
                x = null, // unpositioned sample
                y = null,
                positioningMethod = "POSITION_UNCERTAIN",
                positioningConfidence = 0.0f,
                rssi = -68,
                latencyMs = null, // unmeasured ping
                packetLossRate = null,
                bssid = "00:11:22:33:44:55",
                ssid = "TestNet",
                frequencyMhz = 5180,
                isUncertain = true
            )
        )

        val csv = CsvExporter.exportSpatialSamplesToCsv(null, samples)
        // Verify line contains empty fields for missing x, y, latency without replacing with 0
        assertTrue(csv.contains(",,POSITION_UNCERTAIN,0.0,-68,,,TestNet,00:11:22:33:44:55,5180,false,true"))
    }
}
