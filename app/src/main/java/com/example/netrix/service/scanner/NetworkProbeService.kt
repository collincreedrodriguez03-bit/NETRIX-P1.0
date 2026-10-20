package com.example.netrix.service.scanner

import com.example.netrix.model.MetricStatus
import com.example.netrix.model.ProbeResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.UUID

object NetworkProbeService {

    /**
     * Executes real socket reachability probe to target gateway IP.
     */
    suspend fun probeGateway(gatewayIp: String?, timeoutMs: Int = 1500): ProbeResult = withContext(Dispatchers.IO) {
        val testId = UUID.randomUUID().toString()
        if (gatewayIp.isNullOrBlank()) {
            return@withContext ProbeResult(
                id = testId,
                testName = "Gateway Reachability",
                target = "N/A",
                protocol = "TCP/ICMP",
                durationMs = null,
                status = MetricStatus.UNAVAILABLE,
                attemptedProbes = 1,
                successfulProbes = 0,
                rawOutput = "No gateway address reported by network interface.",
                errorDetails = "Gateway IP address is null or empty"
            )
        }

        val startTime = System.currentTimeMillis()
        var success = false
        var durationMs: Long? = null
        val outputBuilder = StringBuilder()
        outputBuilder.appendLine("Testing gateway reachability: $gatewayIp (timeout: ${timeoutMs}ms)")

        try {
            // Attempt socket connection to standard gateway ports: 80, 53, or 443
            val socket = Socket()
            val socketStart = System.nanoTime()
            try {
                socket.connect(InetSocketAddress(gatewayIp, 53), timeoutMs / 2)
                val elapsedNanos = System.nanoTime() - socketStart
                durationMs = (elapsedNanos / 1_000_000L).coerceAtLeast(1L)
                success = true
                outputBuilder.appendLine("Connected to $gatewayIp:53 in ${durationMs}ms [SUCCESS]")
            } catch (e: Exception) {
                // Try ICMP reachability fallback
                val addr = InetAddress.getByName(gatewayIp)
                val icmpStart = System.nanoTime()
                val reachable = addr.isReachable(timeoutMs)
                val elapsedNanos = System.nanoTime() - icmpStart
                if (reachable) {
                    durationMs = (elapsedNanos / 1_000_000L).coerceAtLeast(1L)
                    success = true
                    outputBuilder.appendLine("ICMP Echo to $gatewayIp responded in ${durationMs}ms [SUCCESS]")
                } else {
                    outputBuilder.appendLine("Failed socket connect and ICMP to $gatewayIp: ${e.message}")
                }
            } finally {
                try { socket.close() } catch (_: Exception) {}
            }
        } catch (e: Exception) {
            outputBuilder.appendLine("Probe error: ${e.localizedMessage}")
        }

        ProbeResult(
            id = testId,
            testName = "Gateway Reachability",
            target = gatewayIp,
            protocol = "TCP/ICMP",
            durationMs = durationMs,
            status = if (success) MetricStatus.AVAILABLE else MetricStatus.ERROR,
            attemptedProbes = 1,
            successfulProbes = if (success) 1 else 0,
            rawOutput = outputBuilder.toString(),
            errorDetails = if (success) null else "Target $gatewayIp unresponsive"
        )
    }

    /**
     * Executes real DNS resolution benchmark for specified hostname.
     */
    suspend fun probeDns(hostname: String = "dns.google"): ProbeResult = withContext(Dispatchers.IO) {
        val testId = UUID.randomUUID().toString()
        val startTime = System.nanoTime()
        val outputBuilder = StringBuilder()
        outputBuilder.appendLine("Resolving hostname: $hostname via system resolver")

        try {
            val addresses = InetAddress.getAllByName(hostname)
            val elapsedNanos = System.nanoTime() - startTime
            val durationMs = (elapsedNanos / 1_000_000L).coerceAtLeast(1L)

            outputBuilder.appendLine("Resolved ${addresses.size} address(es) in ${durationMs}ms:")
            addresses.forEach { outputBuilder.appendLine(" -> ${it.hostAddress}") }

            ProbeResult(
                id = testId,
                testName = "DNS Resolution",
                target = hostname,
                protocol = "UDP/53",
                durationMs = durationMs,
                status = MetricStatus.AVAILABLE,
                attemptedProbes = 1,
                successfulProbes = 1,
                rawOutput = outputBuilder.toString()
            )
        } catch (e: Exception) {
            outputBuilder.appendLine("DNS lookup failed: ${e.message}")
            ProbeResult(
                id = testId,
                testName = "DNS Resolution",
                target = hostname,
                protocol = "UDP/53",
                durationMs = null,
                status = MetricStatus.ERROR,
                attemptedProbes = 1,
                successfulProbes = 0,
                rawOutput = outputBuilder.toString(),
                errorDetails = e.localizedMessage
            )
        }
    }

    /**
     * Real WAN / Internet reachability test using HTTP 204 endpoint.
     */
    suspend fun probeWanReachability(endpoint: String = "https://connectivitycheck.gstatic.com/generate_204"): ProbeResult = withContext(Dispatchers.IO) {
        val testId = UUID.randomUUID().toString()
        val outputBuilder = StringBuilder()
        outputBuilder.appendLine("Checking external WAN reachability via $endpoint")

        var connection: HttpURLConnection? = null
        try {
            val url = URL(endpoint)
            val start = System.nanoTime()
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 2500
            connection.readTimeout = 2500
            connection.instanceFollowRedirects = false
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            val elapsedNanos = System.nanoTime() - start
            val durationMs = (elapsedNanos / 1_000_000L).coerceAtLeast(1L)

            val isSuccess = responseCode == 204 || responseCode in 200..299
            outputBuilder.appendLine("HTTP Response: $responseCode (${durationMs}ms)")

            ProbeResult(
                id = testId,
                testName = "WAN Reachability",
                target = endpoint,
                protocol = "HTTPS",
                durationMs = durationMs,
                status = if (isSuccess) MetricStatus.AVAILABLE else MetricStatus.ERROR,
                attemptedProbes = 1,
                successfulProbes = if (isSuccess) 1 else 0,
                rawOutput = outputBuilder.toString(),
                errorDetails = if (isSuccess) null else "HTTP code $responseCode"
            )
        } catch (e: Exception) {
            outputBuilder.appendLine("WAN probe failed: ${e.message}")
            ProbeResult(
                id = testId,
                testName = "WAN Reachability",
                target = endpoint,
                protocol = "HTTPS",
                durationMs = null,
                status = MetricStatus.ERROR,
                attemptedProbes = 1,
                successfulProbes = 0,
                rawOutput = outputBuilder.toString(),
                errorDetails = e.localizedMessage
            )
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Genuine Public IP lookup without default fallbacks.
     */
    suspend fun fetchPublicIp(): Pair<String?, MetricStatus> = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("https://api.ipify.org")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.requestMethod = "GET"

            if (connection.responseCode in 200..299) {
                val reader = BufferedReader(InputStreamReader(connection.inputStream))
                val ip = reader.readLine()?.trim()
                reader.close()
                if (!ip.isNullOrBlank()) {
                    return@withContext Pair(ip, MetricStatus.AVAILABLE)
                }
            }
            Pair(null, MetricStatus.UNAVAILABLE)
        } catch (e: Exception) {
            Pair(null, MetricStatus.ERROR)
        } finally {
            connection?.disconnect()
        }
    }
}
