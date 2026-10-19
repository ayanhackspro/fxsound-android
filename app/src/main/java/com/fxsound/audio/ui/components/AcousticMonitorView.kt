package com.fxsound.audio.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import com.fxsound.audio.R
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.ui.theme.AmberAccent
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DangerRed
import com.fxsound.audio.ui.theme.DarkSurface
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.FxRedLight
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary
import com.fxsound.audio.ui.theme.VisualizerHigh
import com.fxsound.audio.ui.theme.VisualizerLow

import com.fxsound.audio.model.AcousticMetrics
import com.fxsound.audio.model.AudioOutputDeviceType

/**
 * Honest Acoustic Monitor View.
 * Satisfies AGENTS.md rules:
 * - NO fake visualizers or synthetic bar animations when real data is unavailable.
 * - Accurate peak meters displayed only during live C++ DSP reference processing.
 * - Clear, transparent explanation of active audio route during Spotify/player playback.
 * - Consumes isolated AcousticMetrics to decouple fast telemetry from full UI recomposition.
 */
@Composable
fun AcousticMonitorView(
    routeStatus: RouteStatus,
    activePackage: String?,
    outputDevice: AudioOutputDeviceType,
    metrics: AcousticMetrics,
    isPowerOn: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        when {
            // Mode 1: Real-time Live C++ DSP stream (True Peak and Spectrum available)
            routeStatus == RouteStatus.PLAYING_HIFI && isPowerOn -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(FxRedPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "LIVE C++ DSP SPECTRUM",
                                color = FxRedPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (metrics.isClipping) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(DangerRed)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "LIMITER ENGAGED",
                                        color = Color.White,
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Spacer(modifier = Modifier.width(6.dp))
                            }

                            Text(
                                text = "True Peak: ${String.format("%.2f", metrics.outputPeak)} FS",
                                color = if (metrics.outputPeak >= 0.98f) DangerRed else TextSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    // Authentic FxVisualizer.cpp layout: bars radiating from the center horizontal axis
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                    ) {
                        val numBands = if (metrics.spectrumBands.isNotEmpty()) metrics.spectrumBands.size else 16
                        val totalWidth = size.width
                        val totalHeight = size.height

                        val spacing = 3.dp.toPx()
                        val barWidth = (totalWidth - (numBands - 1) * spacing) / numBands
                        val centerY = totalHeight / 2.0f

                        for (i in 0 until numBands) {
                            val rawVal = if (i < metrics.spectrumBands.size) metrics.spectrumBands[i] else 0.0f
                            val normalized = rawVal.coerceIn(0.01f, 1.0f)
                            val barHeight = (totalHeight * normalized * 0.9f).coerceAtLeast(3.dp.toPx())

                            val x = i * (barWidth + spacing)
                            val y = centerY - (barHeight / 2.0f)

                            val barColor = when {
                                normalized > 0.88f -> VisualizerHigh
                                else -> VisualizerLow
                            }

                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(x, y),
                                size = Size(barWidth, barHeight),
                                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurfaceElevated)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "OUTPUT: ${outputDevice.displayName.uppercase()}",
                                color = VisualizerLow,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }

                        Text(
                            text = "NATIVE C++ DfxDsp DSP",
                            color = FxRedPrimary.copy(alpha = 0.85f),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                    }
                }
            }

            // Mode 2: External Player Connected (Spotify, Musicolet, Poweramp)
            routeStatus.isProcessing && isPowerOn -> {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f, fill = false)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(FxRedPrimary)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "PROCESSING SESSION",
                                color = FxRedPrimary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Text(
                            text = activePackage?.substringAfterLast('.') ?: "External Audio",
                            color = TextPrimary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Real-time acoustic translation active via Dynamics Engine. Pre/Post-EQ, 4-band multiband compressor, and brickwall peak protection applied directly to the audio session.",
                        color = TextSecondary,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurfaceElevated)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "OUTPUT: ${outputDevice.displayName.uppercase()}",
                                color = AmberAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // Mode 3: Partial route — audio is playing, but only the global mix is attached
            // and this source may be bypassing it. Not a processing state.
            routeStatus == RouteStatus.PARTIAL -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "System Mix Only",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Audio is playing, but this source may be bypassing FxSound. " +
                                "Music players that publish an audio session are enhanced.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurfaceElevated)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ROUTE: ${outputDevice.displayName.uppercase()}",
                                color = AmberAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // Mode 4: Ready / Waiting for playback
            isPowerOn -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_music_note),
                        contentDescription = null,
                        tint = AmberAccent,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "Awaiting Audio Stream",
                            color = TextPrimary,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Play audio in a music player that supports audio effects (Spotify, Poweramp) to activate enhancement.",
                            color = TextSecondary,
                            fontSize = 11.sp
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(DarkSurfaceElevated)
                                .border(1.dp, BorderSubtle, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "ROUTE: ${outputDevice.displayName.uppercase()}",
                                color = AmberAccent,
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }
            }

            // Mode 5: Power Off / Bypassed
            else -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_info),
                        contentDescription = null,
                        tint = TextTertiary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "FxSound is bypassed. Output is completely unaltered.",
                        color = TextTertiary,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}
