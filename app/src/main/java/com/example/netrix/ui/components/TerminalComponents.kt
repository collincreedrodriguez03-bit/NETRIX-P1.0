package com.example.netrix.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.example.ui.theme.NetrixCyan
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
    trailingHeader: (@Composable () -> Unit)? = null,
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(NetrixPrimary)
                        )
                        Text(
                            text = title.uppercase(),
                            color = NetrixPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 0.5.sp
                        )
                    }
                    if (trailingHeader != null) {
                        trailingHeader()
                    }
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
    enabled: Boolean = true,
    isSelected: Boolean = false,
    color: Color = if (isSelected) NetrixPrimary else NetrixBorder,
    textColor: Color = if (isSelected) NetrixVoid else NetrixPrimary,
    backgroundColor: Color = if (isSelected) NetrixPrimary else NetrixSurface,
    testTag: String = "btn_${text.lowercase().replace(" ", "_")}"
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val effectiveBorderColor = when {
        !enabled -> NetrixBorder.copy(alpha = 0.4f)
        isPressed -> NetrixGreen
        isSelected -> NetrixPrimary
        else -> color
    }

    val effectiveBackgroundColor = when {
        !enabled -> NetrixSurfaceSubtle
        isPressed -> NetrixPrimary.copy(alpha = 0.2f)
        isSelected -> NetrixPrimary
        else -> backgroundColor
    }

    val effectiveTextColor = when {
        !enabled -> NetrixTextMuted
        isSelected -> NetrixVoid
        isPressed -> NetrixGreen
        else -> textColor
    }

    Box(
        modifier = modifier
            .testTag(testTag)
            .defaultMinSize(minWidth = 48.dp, minHeight = 40.dp)
            .border(BorderStroke(1.dp, effectiveBorderColor), RectangleShape)
            .background(effectiveBackgroundColor)
            .clickable(
                enabled = enabled,
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = effectiveTextColor,
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
            .border(BorderStroke(1.dp, txtColor.copy(alpha = 0.6f)), RectangleShape)
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
            .size(7.dp)
            .border(BorderStroke(1.dp, color), RectangleShape)
            .background(color)
    )
}

@Composable
fun TerminalProgressBar(
    isLoading: Boolean,
    modifier: Modifier = Modifier
) {
    if (!isLoading) return
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(NetrixBorder)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .height(2.dp)
                .background(NetrixPrimary)
        )
    }
}
