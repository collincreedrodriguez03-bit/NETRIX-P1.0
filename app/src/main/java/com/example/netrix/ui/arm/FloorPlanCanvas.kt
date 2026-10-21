package com.example.netrix.ui.arm

import android.graphics.Bitmap
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
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.data.local.SpatialSampleEntity
import com.example.netrix.model.HeatmapLayer
import com.example.netrix.service.spatial.HeatmapCell
import com.example.netrix.service.spatial.SpatialSurveyEngine
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSecondary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow

@Composable
fun FloorPlanCanvas(
    samples: List<SpatialSampleEntity>,
    currentLayer: HeatmapLayer,
    currentPos: Pair<Float, Float>?,
    scalePixelsPerMeter: Float,
    customFloorPlanBitmap: Bitmap? = null,
    onTapLocation: (Float, Float) -> Unit,
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

    Box(
        modifier = modifier
            .background(NetrixVoid)
            .transformable(state = transformState)
            .pointerInput(Unit) {
                detectTapGestures { tapOffset ->
                    val localX = (tapOffset.x - offset.x) / scale
                    val localY = (tapOffset.y - offset.y) / scale
                    onTapLocation(localX, localY)
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
                // Neutral blueprint grid drafting plane (No hardcoded or fabricated rooms)
                drawNeutralBlueprintGridArea(offset, scale)
            }

            // 3. Render Heatmap Cells (Inverse-Distance Weighting strictly within measured radius)
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

            // 4. Render Breadcrumb Path (Connecting line between samples)
            val validSamples = samples.filter { it.x != null && it.y != null }
            if (validSamples.size >= 2) {
                val path = Path()
                validSamples.forEachIndexed { idx, s ->
                    val sx = s.x!! * scale + offset.x
                    val sy = s.y!! * scale + offset.y
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
                val sx = s.x!! * scale + offset.x
                val sy = s.y!! * scale + offset.y
                val dotColor = rssiToColor(s.rssi?.toFloat() ?: -90f)

                if (s.isCheckpoint) {
                    // Checkpoint: Diamond marker
                    drawRect(
                        color = NetrixYellow,
                        topLeft = Offset(sx - 5f * scale, sy - 5f * scale),
                        size = Size(10f * scale, 10f * scale),
                        style = Stroke(width = 1.8f)
                    )
                } else {
                    // Discrete Sample Dot
                    drawCircle(
                        color = dotColor,
                        radius = 4f * scale,
                        center = Offset(sx, sy)
                    )
                }
            }

            // 6. Draw User Current Position Indicator
            currentPos?.let { (cx, cy) ->
                val px = cx * scale + offset.x
                val py = cy * scale + offset.y

                // Outer Crosshair
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
                // Center Pulse Node
                drawCircle(
                    color = NetrixGreen,
                    radius = 4.5f,
                    center = Offset(px, py)
                )
            }

            // 7. Draw Scale Calibration Ruler & Compass in Corner
            drawScaleRuler(canvasWidth, canvasHeight, scalePixelsPerMeter, scale)
        }

        // Floating dBm Signal Strength Legend
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp)
                .background(NetrixSurface.copy(alpha = 0.90f))
                .border(1.dp, NetrixBorder)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Column {
                Text(
                    text = "RSSI (dBm)",
                    color = NetrixPrimary,
                    fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(3.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(NetrixGreen))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(">-60 (Excellent)", color = NetrixText, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(NetrixYellow))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("-60..-75 (Fair)", color = NetrixText, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(NetrixRed))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("-75..-85 (Weak)", color = NetrixText, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(8.dp).background(Color(0xFF6B1A24)))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("<-85 (Dead Zone)", color = NetrixTextDim, fontSize = 8.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

private fun DrawScope.drawBlueprintGrid(offset: Offset, scale: Float, w: Float, h: Float) {
    val step = 36f * scale
    var x = offset.x % step
    while (x < w) {
        drawLine(
            color = NetrixBorder.copy(alpha = 0.35f),
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 0.8f
        )
        x += step
    }
    var y = offset.y % step
    while (y < h) {
        drawLine(
            color = NetrixBorder.copy(alpha = 0.35f),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = 0.8f
        )
        y += step
    }
}

/**
 * Draws a neutral technical blueprint grid drafting area.
 * Zero-fake-data policy: Never invent fabricated rooms or spaces.
 */
private fun DrawScope.drawNeutralBlueprintGridArea(offset: Offset, scale: Float) {
    val axisColor = Color(0xFF6B47A8)
    val gridLineColor = Color(0xFF332050)
    val stroke = 1.6f * scale

    fun pt(x: Float, y: Float) = Offset(x * scale + offset.x, y * scale + offset.y)

    // Outer Boundary (480x480 coordinate drafting space)
    val outerPath = Path().apply {
        moveTo(pt(20f, 20f).x, pt(20f, 20f).y)
        lineTo(pt(460f, 20f).x, pt(460f, 20f).y)
        lineTo(pt(460f, 460f).x, pt(460f, 460f).y)
        lineTo(pt(20f, 460f).x, pt(20f, 460f).y)
        close()
    }
    drawPath(path = outerPath, color = axisColor, style = Stroke(width = stroke))

    // Quadrant Axis Crosshairs (at center 240, 240)
    drawLine(color = gridLineColor, start = pt(240f, 20f), end = pt(240f, 460f), strokeWidth = 1.0f * scale)
    drawLine(color = gridLineColor, start = pt(20f, 240f), end = pt(460f, 240f), strokeWidth = 1.0f * scale)

    // Corner Calibration / Targeting Brackets
    val cornerLen = 16f * scale
    // Top-Left
    drawLine(color = NetrixPrimary, start = pt(20f, 20f), end = pt(20f + cornerLen, 20f), strokeWidth = 2.0f)
    drawLine(color = NetrixPrimary, start = pt(20f, 20f), end = pt(20f, 20f + cornerLen), strokeWidth = 2.0f)
    // Top-Right
    drawLine(color = NetrixPrimary, start = pt(460f, 20f), end = pt(460f - cornerLen, 20f), strokeWidth = 2.0f)
    drawLine(color = NetrixPrimary, start = pt(460f, 20f), end = pt(460f, 20f + cornerLen), strokeWidth = 2.0f)
    // Bottom-Left
    drawLine(color = NetrixPrimary, start = pt(20f, 460f), end = pt(20f + cornerLen, 460f), strokeWidth = 2.0f)
    drawLine(color = NetrixPrimary, start = pt(20f, 460f), end = pt(20f, 460f - cornerLen), strokeWidth = 2.0f)
    // Bottom-Right
    drawLine(color = NetrixPrimary, start = pt(460f, 460f), end = pt(460f - cornerLen, 460f), strokeWidth = 2.0f)
    drawLine(color = NetrixPrimary, start = pt(460f, 460f), end = pt(460f, 460f - cornerLen), strokeWidth = 2.0f)

    drawContext.canvas.nativeCanvas.apply {
        val labelPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#8F7CA8")
            textSize = (11f * scale).coerceIn(9f, 16f)
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        drawText("NEUTRAL SURVEY GRID [NO PLAN LOADED]", pt(28f, 42f).x, pt(28f, 42f).y, labelPaint)
        drawText("CENTER (240, 240)", pt(246f, 256f).x, pt(246f, 256f).y, labelPaint)
    }
}

private fun DrawScope.drawScaleRuler(w: Float, h: Float, pxPerMeter: Float, currentScale: Float) {
    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            textSize = 14f
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }

        if (pxPerMeter <= 0f) {
            paint.color = android.graphics.Color.parseColor("#E5A93C")
            drawText("SCALE: UNCALIBRATED", w - 200f, h - 20f, paint)
        } else {
            val rulerMeters = 5.0f
            val rulerLengthPx = (pxPerMeter * rulerMeters * currentScale).coerceIn(40f, 160f)
            val startX = w - rulerLengthPx - 16f
            val startY = h - 24f

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

        // North Compass Indicator
        paint.color = android.graphics.Color.parseColor("#BB66FF")
        drawText("N ↑", 16f, h - 20f, paint)
    }
}

private fun rssiToColor(rssi: Float): Color {
    return when {
        rssi >= -60f -> NetrixGreen.copy(alpha = 0.55f)
        rssi >= -75f -> NetrixYellow.copy(alpha = 0.55f)
        rssi >= -85f -> NetrixRed.copy(alpha = 0.60f)
        else -> Color(0xFF6B1A24).copy(alpha = 0.70f)
    }
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
