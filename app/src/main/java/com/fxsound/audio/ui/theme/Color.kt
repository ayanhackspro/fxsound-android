package com.fxsound.audio.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Authentic FxSound PC Dark Palette.
 * Extracted directly from upstream FxTheme.cpp:
 * - WindowBackground: 0x181818
 * - ControlBackground: 0x0F0F0F
 * - Outline: 0x2B2B2B
 * - TextButtonBackground: 0xD51535
 * - ImageButton: 0xE63462
 * - SliderTrack: 0xE33250
 * - SliderHighlight: 0xF7546F
 * - EqStart: 0xEF4B65
 * - EqEnd: 0x742834
 * - GraphHigh: 0xD51535
 * - GraphLow: 0xFE566A
 */
val DarkBackground = Color(0xFF181818)          // Main Window Background (#181818)
val DarkSurface = Color(0xFF141414)             // Secondary Surface (#141414)
val DarkSurfaceCard = Color(0xFF0F0F0F)         // Control Card Background (#0F0F0F)
val DarkSurfaceElevated = Color(0xFF222222)     // Elevated control panels (#222222)
val DarkSurfaceVariant = Color(0xFF1A1A1A)      // Recessed fader wells (#1A1A1A)

// Outlines & Borders
val BorderSubtle = Color(0xFF2B2B2B)            // Outline (#2B2B2B)
val BorderStrong = Color(0xFF383838)            // Outline highlight (#383838)

// Signature FxSound PC Crimson Red Accents (from FxTheme.cpp)
val FxRedPrimary = Color(0xFFE63462)            // Primary Button & Accent (#E63462)
val FxRedDark = Color(0xFFD51535)               // Dark Crimson Base (#D51535)
val FxRedLight = Color(0xFFEF4B65)              // EQ Gradient Start (#EF4B65)
val FxRedHighlight = Color(0xFFF7546F)          // Slider Glow / Highlight (#F7546F)
val FxRedGlow = Color(0x40E63462)               // Ambient Red Aura

// Equalizer & Visualizer Gradients (from FxTheme.cpp)
val EqGradientStart = Color(0xFFEF4B65)         // EqStart: #EF4B65
val EqGradientEnd = Color(0xFF742834)           // EqEnd: #742834
val VisualizerHigh = Color(0xFFD51535)          // GraphHigh: #D51535
val VisualizerLow = Color(0xFFFE566A)           // GraphLow: #FE566A

// Accent aliases (Mapped to FxSound Red for unified desktop aesthetics)
val CyanAccent = FxRedPrimary
val CyanAccentHover = FxRedHighlight
val CyanAccentMuted = Color(0xFF742834)
val CyanAccentGlow = FxRedGlow

val AmberAccent = Color(0xFFFE566A)
val AmberAccentMuted = Color(0xFF5A222B)
val AmberAccentGlow = Color(0x33FE566A)

val DangerRed = Color(0xFFFF2B4A)
val DangerRedGlow = Color(0x40FF2B4A)

// Authentic FxSound Typography Colors (from FxTheme.cpp)
val TextPrimary = Color(0xFFFFFFFF)             // HighlightedText: #FFFFFF
val TextSecondary = Color(0xFFB1B1B1)           // DefaultText: #B1B1B1
val TextTertiary = Color(0xFF7F7F7F)            // HintText: #7F7F7F
val TextDisabled = Color(0xFF4A4A4A)

// Slider Tracks
val SliderTrackActive = FxRedPrimary
val SliderTrackInactive = Color(0xFF282828)
val SliderThumb = Color(0xFFFFFFFF)
