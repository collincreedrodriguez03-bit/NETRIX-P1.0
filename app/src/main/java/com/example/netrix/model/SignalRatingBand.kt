package com.example.netrix.model

import androidx.compose.ui.graphics.Color

/**
 * Single constant table for RF Signal Rating Bands.
 * Used consistently across text labels, UI color indicators, spatial map heatmaps, and proximity beep.
 *
 * Rules:
 *  Excellent: >= -40 dBm (use >= so -30 dBm is never unknown)
 *  Very good: -41 .. -50 dBm
 *  Good:      -51 .. -60 dBm
 *  Fair:      -61 .. -70 dBm
 *  Poor:      -71 .. -80 dBm
 *  Unusable:  < -80 dBm
 * Color ramp: Teal/Green (-30) to Red (-90).
 */
enum class SignalRatingBand(
    val label: String,
    val minRssi: Int,
    val maxRssi: Int,
    val color: Color
) {
    EXCELLENT("Excellent", -40, Int.MAX_VALUE, Color(0xFF00E676)),
    VERY_GOOD("Very good", -50, -41, Color(0xFF00BFA5)),
    GOOD("Good", -60, -51, Color(0xFF66BB6A)),
    FAIR("Fair", -70, -61, Color(0xFFFFD54F)),
    POOR("Poor", -80, -71, Color(0xFFFF7043)),
    UNUSABLE("Unusable", Int.MIN_VALUE, -81, Color(0xFFE53935));

    companion object {
        fun fromRssi(rssi: Int?): SignalRatingBand? {
            if (rssi == null) return null
            return when {
                rssi >= -40 -> EXCELLENT
                rssi in -50..-41 -> VERY_GOOD
                rssi in -60..-51 -> GOOD
                rssi in -70..-61 -> FAIR
                rssi in -80..-71 -> POOR
                else -> UNUSABLE
            }
        }

        fun colorForRssi(rssi: Int?, alpha: Float = 1.0f): Color {
            val band = fromRssi(rssi) ?: return Color(0xFF6B1A24).copy(alpha = alpha)
            return band.color.copy(alpha = alpha)
        }

        fun colorForRssiFloat(rssi: Float?, alpha: Float = 1.0f): Color {
            if (rssi == null) return Color(0xFF6B1A24).copy(alpha = alpha)
            return colorForRssi(rssi.toInt(), alpha)
        }
    }
}
