package com.fxsound.audio.ui.components

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.DarkSurfaceVariant
import com.fxsound.audio.ui.theme.FxRedHighlight
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.SliderTrackInactive
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

/**
 * Precision FxSound PC Effect Slider.
 * Uppercase Gilroy Bold title, concise description, precision numeric badge, and FxRedPrimary active track.
 */
@Composable
fun EffectSlider(
    name: String,
    description: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..10f,
    /**
     * Optional scope note rendered under the description, e.g. to state that the effect is
     * only applied by the in-app reference player.
     */
    note: String? = null
) {
    val valueBadgeBg by animateColorAsState(
        targetValue = if (enabled) DarkSurfaceElevated else DarkSurfaceVariant,
        label = "valueBadgeBg"
    )

    val valueTextColor by animateColorAsState(
        targetValue = if (enabled) FxRedPrimary else TextDisabled,
        label = "valueTextColor"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name.uppercase(),
                    color = if (enabled) TextPrimary else TextDisabled,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = description,
                    color = if (enabled) TextSecondary else TextDisabled,
                    fontSize = 11.sp,
                    lineHeight = 14.sp
                )
                if (note != null) {
                    Text(
                        text = note,
                        color = TextTertiary,
                        fontSize = 10.sp,
                        lineHeight = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Precision Value Badge
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(valueBadgeBg)
                    .border(
                        1.dp,
                        if (enabled) FxRedPrimary.copy(alpha = 0.35f) else BorderSubtle,
                        RoundedCornerShape(6.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = String.format("%.1f", value),
                    color = valueTextColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(2.dp))

        Slider(
            value = value,
            onValueChange = { newVal ->
                hapticsManager.performSliderTick(newVal, valueRange.start, valueRange.endInclusive)
                onValueChange(newVal)
            },
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) Color.White else TextDisabled,
                activeTrackColor = if (enabled) FxRedPrimary else DarkSurfaceVariant,
                inactiveTrackColor = SliderTrackInactive,
                disabledThumbColor = TextDisabled,
                disabledActiveTrackColor = DarkSurfaceVariant,
                disabledInactiveTrackColor = DarkSurfaceVariant.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
