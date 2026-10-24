package com.example.netrix.ui.arm

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.model.SignalRatingBand
import com.example.netrix.service.spatial.SpatialSurveyEngine
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow
import kotlin.math.hypot

data class PeakApMarker(
    val bssid: String,
    val ssid: String?,
    val x: Float,
    val y: Float,
    val peakRssi: Int
)

@Composable
fun FloorPlanCanvas(
    samples: List<SpatialSampleEntity>,
    currentLayer: HeatmapLayer,
    currentPos: Pair<Float, Float>?,
    scalePixelsPerMeter: Float,
    customFloorPlanBitmap: Bitmap? = null,
    onTapLocation: (Float, Float) -> Unit,
    onTapApMarker: (String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset(40f, 40f)) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.4f, 4.0f)
        offset += offsetChange
    }

    // Precalculate IDW Heatmap Cells based strictly on genuine recorded samples
    val heatmapCells = remember(samples, currentLayer) {
        SpatialSurveyEngine.computeHeatmapGrid(
            samples = samples,
            gridWidth = 600,
            gridHeight = 600,
            cellSize = 18,
            layer = currentLayer
        )
    }

    // Map AP Pings: Find the point on the walked path with the strongest reading for each BSSID
    val peakApMarkers = remember(samples) {
        samples.filter { it.x != null && it.y != null && !it.isUncertain && it.bssid != null && it.rssi != null }
            .groupBy { it.bssid ?: "" }
            .filterKeys { it.isNotBlank() }
            .mapNotNull { (bssid, apSamples) ->
                val strongest = apSamples.maxByOrNull { it.rssi ?: Int.MIN_VALUE } ?: return@mapNotNull null
                val sx = strongest.x ?: return@mapNotNull null
                val sy = strongest.y ?: return@mapNotNull null
                val sRssi = strongest.rssi ?: return@mapNotNull null
                PeakApMarker(
                    bssid = bssid,
                    ssid = strongest.ssid,
                    x = sx,
                    y = sy,
                    peakRssi = sRssi
                )
            }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ApPulse")
    val pulseProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "PulseProgress"
    )

    val density = LocalDensity.current
    val maxTapDistancePx = with(density) { 24.dp.toPx() }

    Box(
        modifier = modifier
            .background(NetrixVoid)
            .transformable(state = transformState)
            .pointerInput(peakApMarkers, offset, scale) {
                detectTapGestures { tapOffset ->
                    // Nearest AP marker within 24dp screen radius
                    val hitMarker = peakApMarkers.firstOrNull { marker ->
                        val mx = marker.x * scale + offset.x
                        val my = marker.y * scale + offset.y
                        hypot(tapOffset.x - mx, tapOffset.y - my) <= maxTapDistancePx
                    }

                    if (hitMarker != null) {
                        onTapApMarker(hitMarker.bssid)
                    } else {
                        val localX = (tapOffset.x - offset.x) / scale
                        val localY = (tapOffset.y - offset.y) / scale
                        onTapLocation(localX, localY)
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 1. Draw Technical Blueprint Grid
            drawBlueprintGrid(offset, scale, canvasWidth, canvasHeight)

            // 2. Render Floor Plan: Custom user bitmap if imported, or neutral blueprint grid drafting area
            if (customFloorPlanBitmap != null) {
                drawImage(
                    image = customFloorPlanBitmap.asImageBitmap(),
                    topLeft = offset,
                    alpha = 0.55f
                )
            } else {
                drawNeutralBlueprintGridArea(offset, scale)
            }

            // 3. Render Heatmap Cells
            if (currentLayer != HeatmapLayer.MEASUREMENT_POINTS) {
                for (cell in heatmapCells) {
                    val px = cell.x * scale + offset.x
                    val py = cell.y * scale + offset.y
                    val cellDrawSize = 18f * scale

                    val color = when (currentLayer) {
                        HeatmapLayer.HEATMAP_RSSI -> rssiToColor(cell.value)
                        HeatmapLayer.HEATMAP_LATENCY -> latencyToColor(cell.value)
                        HeatmapLayer.HEATMAP_LOSS -> lossToColor(cell.value)
                        HeatmapLayer.DEAD_ZONES -> if (cell.isDeadZone) NetrixRed.copy(alpha = 0.70f) else Color.Transparent
                        else -> Color.Transparent
                    }

                    if (color != Color.Transparent) {
                        drawRect(
                            color = color,
                            topLeft = Offset(px - cellDrawSize / 2, py - cellDrawSize / 2),
                            size = Size(cellDrawSize, cellDrawSize)
                        )
                    }
                }
            }

            // 4. Render Breadcrumb Path
            val validSamples = samples.filter { it.x != null && it.y != null }
            if (validSamples.size >= 2) {
                val path = Path()
                validSamples.forEachIndexed { idx, s ->
                    val sx = (s.x ?: 0f) * scale + offset.x
                    val sy = (s.y ?: 0f) * scale + offset.y
                    if (idx == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                drawPath(
                    path = path,
                    color = NetrixPrimary.copy(alpha = 0.6f),
                    style = Stroke(width = 2.0f * scale)
                )
            }

            // 5. Render Discrete Sample Dots & Checkpoints
            for (s in validSamples) {
                val sx = (s.x ?: 0f) * scale + offset.x
                val sy = (s.y ?: 0f) * scale + offset.y
                val dotColor = rssiToColor(s.rssi?.toFloat() ?: -90f)

                if (s.isCheckpoint) {
                    drawRect(
                        color = NetrixYellow,
                        topLeft = Offset(sx - 5f * scale, sy - 5f * scale),
                        size = Size(10f * scale, 10f * scale),
                        style = Stroke(width = 1.8f)
                    )
                } else {
                    drawCircle(
                        color = dotColor,
                        radius = 4f * scale,
                        center = Offset(sx, sy)
                    )
                }
            }

            // 6. Draw AP Peak Markers with Pulsing Ring (Requirement 6)
            drawContext.canvas.nativeCanvas.apply {
                val paint = Paint().apply {
                    isAntiAlias = true
                    textSize = (10f * scale).coerceIn(10f, 18f)
                    typeface = android.graphics.Typeface.MONOSPACE
                    isFakeBoldText = true
                }

                for (marker in peakApMarkers) {
                    val mx = marker.x * scale + offset.x
                    val my = marker.y * scale + offset.y
                    val bandColor = SignalRatingBand.colorForRssi(marker.peakRssi)

                    // Pulsing Ring
                    val ringRadius = (8f + pulseProgress * 16f) * scale
                    drawCircle(
                        color = bandColor.copy(alpha = (1f - pulseProgress) * 0.75f),
                        radius = ringRadius,
                        center = Offset(mx, my),
                        style = Stroke(width = 1.5f * scale)
                    )

                    // Solid AP Center Node
                    drawCircle(
                        color = bandColor,
                        radius = 5.5f * scale,
                        center = Offset(mx, my)
                    )
                    drawCircle(
                        color = Color.Black,
                        radius = 2.5f * scale,
                        center = Offset(mx, my)
                    )

                    // Label: "peak", not exact location
                    paint.color = android.graphics.Color.WHITE
                    val labelSsid = marker.ssid ?: marker.bssid.takeLast(5)
                    drawText("$labelSsid (peak)", mx + 8f * scale, my - 4f * scale, paint)
                }
            }

            // 7. Draw User Current Position Indicator
            currentPos?.let { (cx, cy) ->
                val px = cx * scale + offset.x
                val py = cy * scale + offset.y

                drawLine(
                    color = NetrixGreen,
                    start = Offset(px - 14f, py),
                    end = Offset(px + 14f, py),
                    strokeWidth = 2.0f
                )
                drawLine(
                    color = NetrixGreen,
                    start = Offset(px, py - 14f),
                    end = Offset(px, py + 14f),
                    strokeWidth = 2.0f
                )
                drawCircle(
                    color = NetrixGreen,
                    radius = 4.5f,
                    center = Offset(px, py)
                )
            }

            // 8. Draw Scale Calibration Ruler & Compass in Corner
            drawScaleRuler(canvasWidth, canvasHeight, scalePixelsPerMeter, scale)
        }

        // Floating dBm Signal Strength Legend adhering strictly to Requirement 4 Rating Bands
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(NetrixSurface.copy(alpha = 0.92f))
                .border(1.dp, NetrixBorder)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column {
                Text(
                    text = "SIGNAL BANDS (dBm)",
                    color = NetrixPrimary,
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                SignalRatingBand.entries.forEach { band ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 1.dp)
                    ) {
                        Box(modifier = Modifier.size(8.dp).background(band.color))
                        Spacer(modifier = Modifier.width(4.dp))
                        val rangeStr = when (band) {
                            SignalRatingBand.EXCELLENT -> ">= -40"
                            SignalRatingBand.VERY_GOOD -> "-41..-50"
                            SignalRatingBand.GOOD -> "-51..-60"
                            SignalRatingBand.FAIR -> "-61..-70"
                            SignalRatingBand.POOR -> "-71..-80"
                            SignalRatingBand.UNUSABLE -> "< -80"
                        }
                        Text(
                            text = "$rangeStr (${band.label})",
                            color = NetrixText,
                            fontSize = 7.5.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawBlueprintGrid(offset: Offset, scale: Float, w: Float, h: Float) {
    val gridSize = 32f * scale
    val gridColor = Color(0xFF1E1435).copy(alpha = 0.40f)

    var x = offset.x % gridSize
    while (x < w) {
        drawLine(color = gridColor, start = Offset(x, 0f), end = Offset(x, h), strokeWidth = 1f)
        x += gridSize
    }

    var y = offset.y % gridSize
    while (y < h) {
        drawLine(color = gridColor, start = Offset(0f, y), end = Offset(w, y), strokeWidth = 1f)
        y += gridSize
    }
}

private fun DrawScope.drawNeutralBlueprintGridArea(offset: Offset, scale: Float) {
    val axisColor = Color(0xFF6B4E9B).copy(alpha = 0.35f)
    val boundaryColor = Color(0xFF8A5CB8).copy(alpha = 0.50f)

    val fieldWidthPx = 480f * scale
    val fieldHeightPx = 480f * scale
    val startX = offset.x
    val startY = offset.y

    drawRect(
        color = Color(0xFF120C1F).copy(alpha = 0.5f),
        topLeft = Offset(startX, startY),
        size = Size(fieldWidthPx, fieldHeightPx)
    )

    drawRect(
        color = boundaryColor,
        topLeft = Offset(startX, startY),
        size = Size(fieldWidthPx, fieldHeightPx),
        style = Stroke(width = 1.5f * scale)
    )

    drawLine(
        color = axisColor,
        start = Offset(startX, startY + fieldHeightPx / 2),
        end = Offset(startX + fieldWidthPx, startY + fieldHeightPx / 2),
        strokeWidth = 1f
    )
    drawLine(
        color = axisColor,
        start = Offset(startX + fieldWidthPx / 2, startY),
        end = Offset(startX + fieldWidthPx / 2, startY + fieldHeightPx),
        strokeWidth = 1f
    )

    drawCircle(
        color = NetrixPrimary.copy(alpha = 0.7f),
        radius = 3.5f * scale,
        center = Offset(startX, startY)
    )

    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            color = android.graphics.Color.parseColor("#8A5CB8")
            textSize = (9f * scale).coerceIn(9f, 15f)
            typeface = android.graphics.Typeface.MONOSPACE
            isAntiAlias = true
        }
        drawText("DRAFTING GRID (0,0)", startX + 6f * scale, startY + 12f * scale, paint)
    }
}

private fun DrawScope.drawScaleRuler(w: Float, h: Float, pxPerMeter: Float, currentZoom: Float) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = Paint().apply {
            color = android.graphics.Color.parseColor("#BB66FF")
            textSize = 18f
            typeface = android.graphics.Typeface.MONOSPACE
            isAntiAlias = true
        }

        if (pxPerMeter <= 0f) {
            paint.color = android.graphics.Color.parseColor("#776699")
            drawText("[UNCALIBRATED SCALE]", 16f, h - 36f, paint)
        } else {
            val rulerLengthPx = 5.0f * pxPerMeter * currentZoom
            val startX = 16f
            val startY = h - 26f

            drawLine(
                color = NetrixPrimary,
                start = Offset(startX, startY),
                end = Offset(startX + rulerLengthPx, startY),
                strokeWidth = 2.0f
            )
            drawLine(color = NetrixPrimary, start = Offset(startX, startY - 4f), end = Offset(startX, startY + 4f), strokeWidth = 2f)
            drawLine(color = NetrixPrimary, start = Offset(startX + rulerLengthPx, startY - 4f), end = Offset(startX + rulerLengthPx, startY + 4f), strokeWidth = 2f)

            paint.color = android.graphics.Color.parseColor("#BB66FF")
            drawText("5 m", startX + rulerLengthPx / 2 - 14f, startY - 6f, paint)
        }

        paint.color = android.graphics.Color.parseColor("#BB66FF")
        drawText("N ↑", 16f, h - 8f, paint)
    }
}

private fun rssiToColor(rssi: Float): Color {
    return SignalRatingBand.colorForRssiFloat(rssi, alpha = 0.60f)
}

private fun latencyToColor(latency: Float): Color {
    return when {
        latency <= 25f -> NetrixGreen.copy(alpha = 0.55f)
        latency <= 75f -> NetrixYellow.copy(alpha = 0.55f)
        else -> NetrixRed.copy(alpha = 0.60f)
    }
}

private fun lossToColor(lossPct: Float): Color {
    return when {
        lossPct <= 0.5f -> NetrixGreen.copy(alpha = 0.55f)
        lossPct <= 3.0f -> NetrixYellow.copy(alpha = 0.55f)
        else -> NetrixRed.copy(alpha = 0.60f)
    }
}
