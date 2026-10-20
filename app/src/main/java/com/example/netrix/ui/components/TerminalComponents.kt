package com.example.netrix.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.netrix.model.MetricStatus
import com.example.ui.theme.NetrixBorder
import com.example.ui.theme.NetrixBorderActive
import com.example.ui.theme.NetrixGreen
import com.example.ui.theme.NetrixPrimary
import com.example.ui.theme.NetrixRed
import com.example.ui.theme.NetrixSecondary
import com.example.ui.theme.NetrixSurface
import com.example.ui.theme.NetrixSurfaceSubtle
import com.example.ui.theme.NetrixText
import com.example.ui.theme.NetrixTextDim
import com.example.ui.theme.NetrixTextMuted
import com.example.ui.theme.NetrixVoid
import com.example.ui.theme.NetrixYellow

@Composable
fun TerminalCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    borderColor: Color = NetrixBorder,
    backgroundColor: Color = NetrixSurface,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.border(BorderStroke(1.dp, borderColor), RectangleShape),
        shape = RectangleShape,
        color = backgroundColor
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            if (title != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title.uppercase(),
                        color = NetrixPrimary,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(NetrixPrimary)
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(NetrixBorder)
                )
                Spacer(modifier = Modifier.height(6.dp))
            }
            content()
        }
    }
}

@Composable
fun TerminalButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    color: Color = if (isSelected) NetrixPrimary else NetrixBorder,
    textColor: Color = if (isSelected) NetrixVoid else NetrixPrimary,
    backgroundColor: Color = if (isSelected) NetrixPrimary else NetrixSurface,
    testTag: String = "btn_${text.lowercase().replace(" ", "_")}"
) {
    Box(
        modifier = modifier
            .testTag(testTag)
            .defaultMinSize(minWidth = 48.dp, minHeight = 44.dp)
            .border(BorderStroke(1.dp, color), RectangleShape)
            .background(backgroundColor)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
fun TerminalBadge(
    status: MetricStatus,
    modifier: Modifier = Modifier
) {
    val bgColor: Color
    val txtColor: Color

    when (status) {
        MetricStatus.AVAILABLE -> {
            bgColor = NetrixGreen.copy(alpha = 0.15f)
            txtColor = NetrixGreen
        }
        MetricStatus.UNAVAILABLE, MetricStatus.NOT_TESTED -> {
            bgColor = NetrixTextMuted.copy(alpha = 0.2f)
            txtColor = NetrixTextDim
        }
        MetricStatus.PERMISSION_REQUIRED, MetricStatus.STALE, MetricStatus.INSUFFICIENT_DATA -> {
            bgColor = NetrixYellow.copy(alpha = 0.15f)
            txtColor = NetrixYellow
        }
        MetricStatus.TIMED_OUT, MetricStatus.ERROR -> {
            bgColor = NetrixRed.copy(alpha = 0.15f)
            txtColor = NetrixRed
        }
        MetricStatus.POSITION_UNCERTAIN -> {
            bgColor = NetrixSecondary.copy(alpha = 0.2f)
            txtColor = NetrixPrimary
        }
    }

    Box(
        modifier = modifier
            .border(BorderStroke(1.dp, txtColor.copy(alpha = 0.5f)), RectangleShape)
            .background(bgColor)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = status.label,
            color = txtColor,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
fun StatusIndicatorDot(
    isActive: Boolean,
    color: Color = if (isActive) NetrixGreen else NetrixRed,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .border(BorderStroke(1.dp, color), RectangleShape)
            .background(color)
    )
}
