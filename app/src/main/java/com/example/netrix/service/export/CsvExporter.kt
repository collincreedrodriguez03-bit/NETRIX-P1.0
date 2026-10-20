package com.example.netrix.service.export

import com.example.netrix.data.local.NetworkObservationEntity
import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.data.local.SurveySessionEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    /**
     * Exports survey session spatial samples to RFC 4180 compliant CSV.
     * Preserves null/missing values as empty strings without converting to 0.
     */
    fun exportSpatialSamplesToCsv(
        session: SurveySessionEntity?,
        samples: List<SpatialSampleEntity>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("# NETRIX Survey Export — RFC 4180")
        sb.appendLine("# Session ID: ${session?.id ?: "N/A"}")
        sb.appendLine("# Session Name: ${session?.name ?: "N/A"}")
        sb.appendLine("# Scale: ${session?.scalePixelsPerMeter ?: "N/A"} px/m")
        sb.appendLine("sample_id,timestamp_iso,timestamp_epoch,x_px,y_px,position_method,position_confidence,rssi_dbm,latency_ms,packet_loss_pct,ssid,bssid,frequency_mhz,is_checkpoint,is_uncertain")

        for (s in samples) {
            val isoTime = dateFormat.format(Date(s.timestamp))
            val xStr = s.x?.toString() ?: ""
            val yStr = s.y?.toString() ?: ""
            val rssiStr = s.rssi?.toString() ?: ""
            val latencyStr = s.latencyMs?.toString() ?: ""
            val lossStr = s.packetLossRate?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val ssidStr = escapeCsv(s.ssid ?: "")
            val bssidStr = s.bssid ?: ""
            val freqStr = s.frequencyMhz?.toString() ?: ""

            sb.appendLine("${s.id},$isoTime,${s.timestamp},$xStr,$yStr,${s.positioningMethod},${s.positioningConfidence},$rssiStr,$latencyStr,$lossStr,$ssidStr,$bssidStr,$freqStr,${s.isCheckpoint},${s.isUncertain}")
        }
        return sb.toString()
    }

    /**
     * Exports 27-vital observations to RFC 4180 CSV.
     */
    fun exportObservationsToCsv(observations: List<NetworkObservationEntity>): String {
        val sb = StringBuilder()
        sb.appendLine("# NETRIX Telemetry Observations — RFC 4180")
        sb.appendLine("observation_id,timestamp_iso,timestamp_epoch,ssid,bssid,rssi_dbm,signal_quality_pct,channel,frequency_mhz,link_rate_mbps,gateway_ip,gateway_latency_ms,dns_resolve_ms,internet_reachable,packet_loss_pct,jitter_ms,public_ip")

        for (o in observations) {
            val isoTime = dateFormat.format(Date(o.timestamp))
            val ssidStr = escapeCsv(o.ssid ?: "")
            val bssidStr = o.bssid ?: ""
            val rssiStr = o.rssi?.toString() ?: ""
            val qualityStr = o.signalQuality?.toString() ?: ""
            val channelStr = o.channel?.toString() ?: ""
            val freqStr = o.frequencyMhz?.toString() ?: ""
            val linkSpeedStr = o.linkSpeedMbps?.toString() ?: ""
            val gwIpStr = o.gatewayIp ?: ""
            val gwLatencyStr = o.gatewayLatencyMs?.toString() ?: ""
            val dnsTimeStr = o.dnsResolveMs?.toString() ?: ""
            val wanStr = o.internetReachable?.toString() ?: ""
            val lossStr = o.packetLossPct?.let { String.format(Locale.US, "%.1f", it) } ?: ""
            val jitterStr = o.jitterMs?.toString() ?: ""
            val pubIpStr = o.publicIp ?: ""

            sb.appendLine("${o.id},$isoTime,${o.timestamp},$ssidStr,$bssidStr,$rssiStr,$qualityStr,$channelStr,$freqStr,$linkSpeedStr,$gwIpStr,$gwLatencyStr,$dnsTimeStr,$wanStr,$lossStr,$jitterStr,$pubIpStr")
        }
        return sb.toString()
    }

    private fun escapeCsv(value: String): String {
        return if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else {
            value
        }
    }
}
