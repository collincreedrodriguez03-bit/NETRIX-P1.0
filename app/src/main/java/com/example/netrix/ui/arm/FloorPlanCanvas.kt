package com.example.netrix.ui.arm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.platform.LocalContext
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
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FloorPlanCanvas(
    samples: List<SpatialSampleEntity>,
    currentLayer: HeatmapLayer,
    currentPos: Pair<Float, Float>?,
    scalePixelsPerMeter: Float,
    onTapLocation: (Float, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var floorPlanBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val inputStream = context.assets.open("floor_plan_default.jpg")
                val bmp = BitmapFactory.decodeStream(inputStream)
                inputStream.close()
                floorPlanBitmap = bmp
            } catch (_: Exception) {}
        }
    }

    var scale by remember { mutableStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    val transformState = rememberTransformableState { zoomChange, offsetChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.5f, 4.0f)
        offset += offsetChange
    }

    // Precalculate IDW Heatmap Cells
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
                    // Reverse pan/zoom transformation to calculate floor-plan local coordinate
                    val localX = (tapOffset.x - offset.x) / scale
                    val localY = (tapOffset.y - offset.y) / scale
                    onTapLocation(localX, localY)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 1. Draw Architectural Floor Plan Bitmap or Blueprint Grid
            if (floorPlanBitmap != null) {
                val bmp = floorPlanBitmap!!
                drawImage(
                    image = bmp.asImageBitmap(),
                    topLeft = offset,
                    alpha = 0.45f
                )
            } else {
                // Fallback Blueprint Grid
                drawBlueprintGrid(offset, scale, canvasWidth, canvasHeight)
            }

            // 2. Render Heatmap Cells (if active layer is not Dots Only)
            if (currentLayer != HeatmapLayer.MEASUREMENT_POINTS) {
                for (cell in heatmapCells) {
                    val px = cell.x * scale + offset.x
                    val py = cell.y * scale + offset.y
                    val cellDrawSize = 18f * scale

                    val color = when (currentLayer) {
                        HeatmapLayer.HEATMAP_RSSI -> rssiToColor(cell.value)
                        HeatmapLayer.HEATMAP_LATENCY -> latencyToColor(cell.value)
                        HeatmapLayer.HEATMAP_LOSS -> lossToColor(cell.value)
                        HeatmapLayer.DEAD_ZONES -> if (cell.isDeadZone) NetrixRed.copy(alpha = 0.7f) else Color.Transparent
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

            // 3. Render Breadcrumb Path (Connecting line between samples)
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
                    color = NetrixPrimary.copy(alpha = 0.5f),
                    style = Stroke(width = 1.5f * scale)
                )
            }

            // 4. Render Discrete Sample Dots & Checkpoints
            for (s in validSamples) {
                val sx = s.x!! * scale + offset.x
                val sy = s.y!! * scale + offset.y
                val dotColor = rssiToColor(s.rssi?.toFloat() ?: -90f)

                if (s.isCheckpoint) {
                    // Checkpoint: Diamond marker
                    drawRect(
                        color = NetrixYellow,
                        topLeft = Offset(sx - 4f * scale, sy - 4f * scale),
                        size = Size(8f * scale, 8f * scale),
                        style = Stroke(width = 1.5f)
                    )
                } else {
                    // Normal Sample Dot
                    drawCircle(
                        color = dotColor,
                        radius = 3.5f * scale,
                        center = Offset(sx, sy)
                    )
                }
            }

            // 5. Draw User Current Position Indicator
            currentPos?.let { (cx, cy) ->
                val px = cx * scale + offset.x
                val py = cy * scale + offset.y

                // Outer Crosshair
                drawLine(
                    color = NetrixGreen,
                    start = Offset(px - 14f, py),
                    end = Offset(px + 14f, py),
                    strokeWidth = 1.5f
                )
                drawLine(
                    color = NetrixGreen,
                    start = Offset(px, py - 14f),
                    end = Offset(px, py + 14f),
                    strokeWidth = 1.5f
                )
                // Center Node
                drawCircle(
                    color = NetrixGreen,
                    radius = 4f,
                    center = Offset(px, py)
                )
            }

            // 6. Draw Scale Calibration Ruler & Compass in Corner
            drawScaleRuler(canvasWidth, canvasHeight, scalePixelsPerMeter, scale)
        }
    }
}

private fun DrawScope.drawBlueprintGrid(offset: Offset, scale: Float, w: Float, h: Float) {
    val step = 40f * scale
    var x = offset.x % step
    while (x < w) {
        drawLine(
            color = NetrixBorder.copy(alpha = 0.4f),
            start = Offset(x, 0f),
            end = Offset(x, h),
            strokeWidth = 0.8f
        )
        x += step
    }
    var y = offset.y % step
    while (y < h) {
        drawLine(
            color = NetrixBorder.copy(alpha = 0.4f),
            start = Offset(0f, y),
            end = Offset(w, y),
            strokeWidth = 0.8f
        )
        y += step
    }
}

private fun DrawScope.drawScaleRuler(w: Float, h: Float, pxPerMeter: Float, currentScale: Float) {
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

    drawContext.canvas.nativeCanvas.apply {
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.parseColor("#BB66FF")
            textSize = 22f
            isAntiAlias = true
            typeface = android.graphics.Typeface.MONOSPACE
        }
        drawText("5 m", startX + rulerLengthPx / 2 - 16f, startY - 8f, paint)
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
