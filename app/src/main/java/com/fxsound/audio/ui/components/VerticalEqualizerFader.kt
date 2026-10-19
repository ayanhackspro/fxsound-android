package com.fxsound.audio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkSurface
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.DarkSurfaceVariant
import com.fxsound.audio.ui.theme.FxRedDark
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

/**
 * Studio-Grade Vertical Graphic Equalizer Fader.
 *
 * Features:
 * - Fluid vertical drag and direct tap response.
 * - Center-zero baseline with dynamic bidirectional fill (cyan upwards for boost, amber downwards for cut).
 * - Tactile haptic feedback ticks on gain changes.
 * - Double-tap to zero: instantly snaps the individual band to 0.0 dB flat.
 * - Hardware-style metallic fader cap with illuminated center grip line.
 */
@Composable
fun VerticalEqualizerFader(
    gain: Float,
    onGainChange: (Float) -> Unit,
    frequencyLabel: String,
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = -14.0f..14.0f
) {
    var lastTapTime by remember { mutableLongStateOf(0L) }

    Column(
        modifier = modifier
            .fillMaxHeight()
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Gain Readout Badge (Top)
        Box(
            modifier = Modifier
                .height(20.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    if (enabled && gain != 0.0f) DarkSurfaceElevated else Color.Transparent
                )
                .padding(horizontal = 2.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            val gainText = when {
                !enabled -> "--"
                gain > 0.05f -> "+${String.format("%.1f", gain)}"
                gain < -0.05f -> String.format("%.1f", gain)
                else -> "0.0"
            }
            Text(
                text = gainText,
                color = when {
                    !enabled -> TextDisabled
                    gain > 0.05f -> FxRedPrimary
                    gain < -0.05f -> FxRedDark
                    else -> TextTertiary
                },
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Vertical Fader Track & Thumb Canvas
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        down.consume()

                        val now = System.currentTimeMillis()
                        // Double tap detection (< 300ms) snaps band to 0.0 dB
                        if (now - lastTapTime < 300L) {
                            hapticsManager.performResetConfirmation()
                            onGainChange(0.0f)
                            lastTapTime = 0L
                            return@awaitEachGesture
                        } else {
                            lastTapTime = now
                        }

                        val h = size.height.toFloat()
                        if (h > 0) {
                            val fraction = 1.0f - (down.position.y / h).coerceIn(0.0f, 1.0f)
                            val newGain = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                            hapticsManager.performSliderTick(newGain, valueRange.start, valueRange.endInclusive)
                            onGainChange(newGain)
                        }

                        while (true) {
                            val event = awaitPointerEvent(pass = PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id }
                                ?: event.changes.firstOrNull()
                                ?: break
                            if (!change.pressed) break
                            change.consume()

                            if (h > 0) {
                                val fraction = 1.0f - (change.position.y / h).coerceIn(0.0f, 1.0f)
                                val newGain = valueRange.start + fraction * (valueRange.endInclusive - valueRange.start)
                                hapticsManager.performSliderTick(newGain, valueRange.start, valueRange.endInclusive)
                                onGainChange(newGain)
                            }
                        }
                    }
                }
        ) {
            Canvas(modifier = Modifier.fillMaxHeight().fillMaxWidth()) {
                val canvasWidth = size.width
                val canvasHeight = size.height
                val centerX = canvasWidth / 2.0f
                val centerY = canvasHeight / 2.0f

                val thumbHeight = 16.dp.toPx()
                val thumbWidth = (canvasWidth * 0.82f).coerceIn(20.dp.toPx(), 28.dp.toPx())

                // Track bounds (accounting for half-thumb margin at top and bottom)
                val trackTop = thumbHeight / 2.0f
                val trackBottom = canvasHeight - (thumbHeight / 2.0f)
                val trackHeight = trackBottom - trackTop

                val normalizedGain = ((gain - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0.0f, 1.0f)
                val thumbCenterY = trackBottom - (normalizedGain * trackHeight)

                // 1. Center Reference Guideline (0 dB)
                drawLine(
                    color = if (enabled) Color(0xFF383838) else Color(0xFF222222),
                    start = Offset(centerX - 8.dp.toPx(), centerY),
                    end = Offset(centerX + 8.dp.toPx(), centerY),
                    strokeWidth = 1.5.dp.toPx()
                )

                // 2. Track Channel
                val trackSlotWidth = 4.dp.toPx()
                drawRoundRect(
                    color = Color(0xFF101010),
                    topLeft = Offset(centerX - trackSlotWidth / 2f, trackTop),
                    size = Size(trackSlotWidth, trackHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                )

                // Track Slot Subtle Border
                drawRoundRect(
                    color = BorderSubtle.copy(alpha = 0.5f),
                    topLeft = Offset(centerX - trackSlotWidth / 2f, trackTop),
                    size = Size(trackSlotWidth, trackHeight),
                    cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )

                // 3. Dynamic Bidirectional Level Fill
                if (enabled && kotlin.math.abs(gain) > 0.05f) {
                    val fillTop = kotlin.math.min(centerY, thumbCenterY)
                    val fillHeight = kotlin.math.abs(thumbCenterY - centerY)
                    val fillColor = if (gain > 0f) FxRedPrimary else Color(0xFF8E2A3C)

                    drawRoundRect(
                        color = fillColor.copy(alpha = 0.85f),
                        topLeft = Offset(centerX - (trackSlotWidth - 1.dp.toPx()) / 2f, fillTop),
                        size = Size(trackSlotWidth - 1.dp.toPx(), fillHeight),
                        cornerRadius = CornerRadius(1.5.dp.toPx(), 1.5.dp.toPx())
                    )
                }

                // 4. Metallic Fader Thumb / Knob
                val thumbLeft = centerX - (thumbWidth / 2.0f)
                val thumbTopPos = thumbCenterY - (thumbHeight / 2.0f)

                // Thumb Body
                val thumbBrush = Brush.verticalGradient(
                    colors = if (enabled) {
                        listOf(DarkSurfaceElevated, DarkSurfaceVariant, DarkSurface)
                    } else {
                        listOf(DarkSurfaceVariant, DarkSurface, Color(0xFF121212))
                    },
                    startY = thumbTopPos,
                    endY = thumbTopPos + thumbHeight
                )

                drawRoundRect(
                    brush = thumbBrush,
                    topLeft = Offset(thumbLeft, thumbTopPos),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                )

                // Thumb Outline
                val thumbBorderColor = when {
                    !enabled -> TextDisabled.copy(alpha = 0.3f)
                    gain > 0.05f -> FxRedPrimary.copy(alpha = 0.8f)
                    gain < -0.05f -> FxRedDark.copy(alpha = 0.8f)
                    else -> BorderSubtle
                }

                drawRoundRect(
                    color = thumbBorderColor,
                    topLeft = Offset(thumbLeft, thumbTopPos),
                    size = Size(thumbWidth, thumbHeight),
                    cornerRadius = CornerRadius(4.dp.toPx(), 4.dp.toPx()),
                    style = Stroke(1.dp.toPx())
                )

                // Center Grip Line on Thumb
                val gripWidth = thumbWidth * 0.65f
                val gripColor = when {
                    !enabled -> TextDisabled
                    gain > 0.05f -> FxRedPrimary
                    gain < -0.05f -> FxRedDark
                    else -> TextSecondary
                }

                drawLine(
                    color = gripColor,
                    start = Offset(centerX - (gripWidth / 2.0f), thumbCenterY),
                    end = Offset(centerX + (gripWidth / 2.0f), thumbCenterY),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // Frequency Label (Bottom)
        Text(
            text = frequencyLabel,
            color = when {
                !enabled -> TextDisabled
                gain != 0.0f -> TextPrimary
                else -> TextSecondary
            },
            fontSize = 11.sp,
            fontWeight = if (gain != 0.0f) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
    }
}
