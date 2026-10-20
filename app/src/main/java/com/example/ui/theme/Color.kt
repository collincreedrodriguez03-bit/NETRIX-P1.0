package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// NETRIX Terminal Palette (Locked v1.1 Spec)
val NetrixVoid = Color(0xFF08060D)           // Near-black background
val NetrixSurface = Color(0xFF120D1F)        // Dark terminal surface
val NetrixSurfaceSubtle = Color(0xFF19122B)  // Elevated table row / card
val NetrixBorder = Color(0xFF331A54)         // Thin purple outline
val NetrixBorderActive = Color(0xFF7D2CCB)   // Active focus outline

val NetrixPrimary = Color(0xFFBB66FF)        // Electric Terminal Purple
val NetrixSecondary = Color(0xFF7D2CCB)      // Deep Royal Purple
val NetrixText = Color(0xFFE8DCFF)           // High contrast terminal text
val NetrixTextDim = Color(0xFF8F7CA8)        // Low contrast dim label
val NetrixTextMuted = Color(0xFF5A4D73)      // Unavailable / Stale status

// Semantic Status Indicators
val NetrixGreen = Color(0xFF00FF66)          // Healthy / Connected / OK
val NetrixYellow = Color(0xFFFFCC00)         // Warning / Degraded / Jitter
val NetrixRed = Color(0xFFFF3366)            // Failure / Problem / Timeout
val NetrixCyan = Color(0xFF00F0FF)           // Scanning / Network active
