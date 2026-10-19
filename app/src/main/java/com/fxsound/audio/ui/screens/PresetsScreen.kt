package com.fxsound.audio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.R
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.ui.theme.BorderStrong
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkBackground
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.DarkSurfaceVariant
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PresetsScreen(
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier
) {
    val state by FxSoundRepository.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "ACOUSTIC PRESETS",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "Pre-calibrated acoustic balances and dynamic processing signatures",
            color = TextTertiary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(18.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(FxSoundPreset.ALL_PRESETS) { preset ->
                val isSelected = state.currentPresetId == preset.id

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            hapticsManager.performPresetSelected()
                            FxSoundRepository.applyPreset(preset)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (isSelected) DarkSurfaceElevated else DarkSurfaceCard
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(
                        if (isSelected) 1.5.dp else 1.dp,
                        if (isSelected) FxRedPrimary else BorderSubtle
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = preset.name.uppercase(),
                                color = if (isSelected) FxRedPrimary else TextPrimary,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )

                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .border(
                                        2.dp,
                                        if (isSelected) FxRedPrimary else BorderStrong,
                                        CircleShape
                                    )
                                    .background(if (isSelected) FxRedPrimary else Color.Transparent),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isSelected) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_check),
                                        contentDescription = "Selected",
                                        tint = Color.White,
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = preset.description,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            lineHeight = 16.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        // Calibrated Parameter Chips with responsive multi-line wrapping
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            ParameterBadge("CLARITY", preset.clarity)
                            ParameterBadge("AMBIENCE", preset.ambience)
                            ParameterBadge("SURROUND", preset.surround)
                            ParameterBadge("DYNAMIC", preset.dynamicBoost)
                            ParameterBadge("BASS", preset.bass)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterBadge(label: String, value: Float) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(DarkSurfaceVariant)
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 3.dp)
    ) {
        Text(
            text = "$label: ${String.format("%.1f", value)}",
            color = TextSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}
