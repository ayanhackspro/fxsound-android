package com.fxsound.audio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextDisabled

/**
 * 10-Band Equalizer Frequency Response Curve.
 * Visualizes filter gains across the 32 Hz to 16 kHz spectrum with smooth spline interpolation.
 */
@Composable
fun EqualizerCurveView(
    eqGains: List<Float>,
    isPowerOn: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(130.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(10.dp)
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp)
        ) {
            val width = size.width
            val height = size.height
            val centerY = height / 2.0f
            val maxGain = 14.0f
            val marginX = 8.dp.toPx()
            val usableWidth = width - (marginX * 2)

            // Grid guidelines: +14 dB, +7 dB, 0 dB, -7 dB, -14 dB
            val yPlus14 = centerY - (14.0f / maxGain) * (centerY * 0.88f)
            val yPlus7 = centerY - (7.0f / maxGain) * (centerY * 0.88f)
            val yMinus7 = centerY + (7.0f / maxGain) * (centerY * 0.88f)
            val yMinus14 = centerY + (14.0f / maxGain) * (centerY * 0.88f)

            // Faint secondary guidelines
            val gridColor = Color(0xFF222222)
            drawLine(gridColor, Offset(0f, yPlus14), Offset(width, yPlus14), 1.dp.toPx())
            drawLine(gridColor, Offset(0f, yPlus7), Offset(width, yPlus7), 1.dp.toPx())
            drawLine(gridColor, Offset(0f, yMinus7), Offset(width, yMinus7), 1.dp.toPx())
            drawLine(gridColor, Offset(0f, yMinus14), Offset(width, yMinus14), 1.dp.toPx())

            // Prominent 0 dB center baseline
            drawLine(
                color = Color(0xFF333333),
                start = Offset(0f, centerY),
                end = Offset(width, centerY),
                strokeWidth = 1.5.dp.toPx()
            )

            val count = eqGains.size
            if (count < 2) return@Canvas

            val stepX = usableWidth / (count - 1)
            val points = eqGains.mapIndexed { i, gain ->
                val x = marginX + i * stepX
                val effectiveGain = if (isPowerOn) gain else 0f
                val y = centerY - (effectiveGain / maxGain) * (centerY * 0.88f)
                Offset(x, y)
            }

            // Draw vertical band ticks
            points.forEach { pt ->
                drawLine(
                    color = Color(0xFF1C1C1C),
                    start = Offset(pt.x, 0f),
                    end = Offset(pt.x, height),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Construct smooth spline path
            val path = Path().apply {
                moveTo(points[0].x, points[0].y)
                for (i in 0 until points.size - 1) {
                    val p0 = points[i]
                    val p1 = points[i + 1]
                    val cx = (p0.x + p1.x) / 2f
                    cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                }
            }

            // Fill area under curve towards baseline
            val fillPath = Path().apply {
                addPath(path)
                lineTo(points.last().x, centerY)
                lineTo(points.first().x, centerY)
                close()
            }

            val curveColor = if (isPowerOn) FxRedPrimary else TextDisabled
            val fillBrush = Brush.verticalGradient(
                colors = if (isPowerOn) {
                    listOf(
                        Color(0xFFEF4B65).copy(alpha = 0.35f),
                        Color(0xFF742834).copy(alpha = 0.15f),
                        Color.Transparent
                    )
                } else {
                    listOf(TextDisabled.copy(alpha = 0.08f), Color.Transparent)
                },
                startY = centerY - (centerY * 0.88f),
                endY = centerY + (centerY * 0.88f)
            )

            drawPath(fillPath, brush = fillBrush)

            // Draw curve line
            drawPath(
                path = path,
                color = curveColor,
                style = Stroke(
                    width = 2.5.dp.toPx(),
                    cap = StrokeCap.Round
                )
            )

            // Draw point nodes
            points.forEach { pt ->
                drawCircle(
                    color = DarkSurfaceElevated,
                    radius = 4.dp.toPx(),
                    center = pt
                )
                drawCircle(
                    color = curveColor,
                    radius = 2.5.dp.toPx(),
                    center = pt
                )
            }
        }
    }
}
