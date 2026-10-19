package com.fxsound.audio.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import com.fxsound.audio.ui.components.EqualizerCurveView
import com.fxsound.audio.ui.components.VerticalEqualizerFader
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkBackground
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.DarkSurfaceVariant
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

@Composable
fun EqualizerScreen(
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier
) {
    val state by FxSoundRepository.uiState.collectAsState()
    val scrollState = rememberScrollState()

    val freqShortLabels = listOf("32", "64", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "GRAPHIC EQUALIZER",
                    color = TextPrimary,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "10 ISO Center Bands (-14 dB to +14 dB)",
                    color = TextTertiary,
                    fontSize = 12.sp
                )
            }

            OutlinedButton(
                onClick = {
                    hapticsManager.performResetConfirmation()
                    FxSoundRepository.resetEq()
                },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_refresh),
                    contentDescription = "Reset EQ to Flat",
                    tint = FxRedPrimary,
                    modifier = Modifier.padding(end = 4.dp).size(16.dp)
                )
                Text(text = "Flat", fontSize = 12.sp, color = TextPrimary)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        // Visual EQ Response Curve
        EqualizerCurveView(
            eqGains = state.eqGains,
            isPowerOn = state.isPowerOn
        )

        Spacer(modifier = Modifier.height(20.dp))

        // 10 Frequency Vertical Fader Console Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(
                modifier = Modifier.padding(top = 16.dp, bottom = 14.dp, start = 4.dp, end = 4.dp)
            ) {
                // Console Header Hint
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "10-BAND STUDIO CONSOLE",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Double-tap fader to zero",
                        color = TextTertiary,
                        fontSize = 10.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Row of 10 Vertical Faders with dB Calibration Axis
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left dB Calibration Scale
                    Column(
                        modifier = Modifier
                            .fillMaxHeight()
                            .padding(top = 26.dp, bottom = 26.dp, end = 4.dp),
                        verticalArrangement = Arrangement.SpaceBetween,
                        horizontalAlignment = Alignment.End
                    ) {
                        Text("+14", color = TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("+7", color = TextTertiary, fontSize = 9.sp)
                        Text("0 dB", color = if (state.isPowerOn) FxRedPrimary else TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("-7", color = TextTertiary, fontSize = 9.sp)
                        Text("-14", color = TextTertiary, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }

                    // 10 Vertical Frequency Faders
                    for (i in 0 until 10) {
                        val gain = state.eqGains.getOrElse(i) { 0.0f }
                        VerticalEqualizerFader(
                            gain = gain,
                            onGainChange = { newVal ->
                                FxSoundRepository.setEqBand(i, newVal)
                            },
                            frequencyLabel = freqShortLabels[i],
                            hapticsManager = hapticsManager,
                            enabled = state.isPowerOn,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
