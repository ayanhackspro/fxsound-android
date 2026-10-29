package com.fxsound.audio.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.model.StageState
import com.fxsound.audio.ui.components.AcousticMonitorView
import com.fxsound.audio.ui.components.EffectSlider
import com.fxsound.audio.ui.components.MasterPowerButton
import com.fxsound.audio.ui.components.RouteExplanationBanner
import com.fxsound.audio.ui.components.StatusBadge
import com.fxsound.audio.ui.theme.AmberAccent
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkBackground
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

/**
 * Authentic FxSound PC HomeScreen.
 * Recreates the desktop FxSound UI with Gilroy typography, signature crimson accents,
 * centered acoustic visualizer, quick preset switcher (< PRESET >), and 5 core audio effects.
 */
@Composable
fun HomeScreen(
    hapticsManager: HapticsManager,
    onNavigateToEqualizer: () -> Unit,
    onNavigateToPresets: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by FxSoundRepository.uiState.collectAsState()
    val metrics by FxSoundRepository.metricsState.collectAsState()
    val scrollState = rememberScrollState()

    val currentPreset = FxSoundPreset.findById(state.currentPresetId)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App Header & Route Status
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f, fill = false)) {
                Text(
                    text = "FXSOUND",
                    color = TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 2.sp
                )
                Text(
                    text = "Acoustic Clarity & Dynamics",
                    color = TextTertiary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            StatusBadge(
                status = state.routeStatus,
                activePackage = state.activePackageName,
                modifier = Modifier.weight(1.2f, fill = false)
            )
        }

        // Route capability explanation. Android does not let an app intercept other apps'
        // audio, so the app states plainly what is and is not being enhanced.
        if (state.routeStatus == RouteStatus.PARTIAL || state.routeStatus == RouteStatus.READY) {
            Spacer(modifier = Modifier.height(16.dp))
            RouteExplanationBanner(routeStatus = state.routeStatus)
        }

        Spacer(modifier = Modifier.height(28.dp))

        // Hero Master Power Button (FxSound PC Button_ON / Button_OFF vector aesthetic)
        MasterPowerButton(
            isPowerOn = state.isPowerOn,
            onPowerToggle = { FxSoundRepository.setPower(it) },
            hapticsManager = hapticsManager
        )

        Spacer(modifier = Modifier.height(14.dp))

        // Power Status Pill — reflects the actual route state, not just the master toggle.
        // "ENHANCEMENT ACTIVE" is only shown when audio is genuinely being processed.
        val isActivelyProcessing = state.isPowerOn && state.routeStatus.isProcessing
        val pillAccent = when {
            isActivelyProcessing -> FxRedPrimary
            state.isPowerOn && state.routeStatus == RouteStatus.PARTIAL -> AmberAccent
            else -> TextSecondary
        }
        val pillText = when {
            !state.isPowerOn -> "BYPASSED"
            isActivelyProcessing -> "ENHANCEMENT ACTIVE"
            state.routeStatus == RouteStatus.PARTIAL -> "PARTIAL ENHANCEMENT"
            else -> "ENHANCEMENT STANDBY"
        }
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(if (state.isPowerOn) pillAccent.copy(alpha = 0.12f) else Color(0xFF131313))
                .border(
                    1.dp,
                    if (state.isPowerOn) pillAccent.copy(alpha = 0.35f) else BorderSubtle,
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 14.dp, vertical = 5.dp)
        ) {
            Text(
                text = pillText,
                color = if (state.isPowerOn) pillAccent else TextDisabled,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Honest Acoustic Monitor & Spectrum
        AcousticMonitorView(
            routeStatus = state.routeStatus,
            activePackage = state.activePackageName,
            outputDevice = state.outputDevice,
            metrics = metrics,
            isPowerOn = state.isPowerOn
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Authentic FxSound PC Quick Preset Switcher Bar (< PRESET NAME >)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Preset Button
                IconButton(
                    onClick = {
                        hapticsManager.performPresetSelected()
                        FxSoundRepository.cyclePreviousPreset()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_left),
                        contentDescription = "Previous Preset",
                        tint = if (state.isPowerOn) FxRedPrimary else TextDisabled,
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Preset Center Selector
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            hapticsManager.performPresetSelected()
                            onNavigateToPresets()
                        }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "PRESET PROFILE",
                        color = TextTertiary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = if (state.currentPresetId == "custom") "CUSTOM TUNING" else currentPreset.name.uppercase(),
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_drop_down),
                            contentDescription = "Select Preset",
                            tint = FxRedPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                // Next Preset Button
                IconButton(
                    onClick = {
                        hapticsManager.performPresetSelected()
                        FxSoundRepository.cycleNextPreset()
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_chevron_right),
                        contentDescription = "Next Preset",
                        tint = if (state.isPowerOn) FxRedPrimary else TextDisabled,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Core Effects Section Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "EFFECTS",
                color = TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Text(
                text = "EQUALIZER",
                color = FxRedPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.clickable {
                    hapticsManager.performPresetSelected()
                    onNavigateToEqualizer()
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Effects Card (5 FxSound PC Controls in Upstream Order)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                // 1. Clarity / Fidelity
                EffectSlider(
                    name = "Clarity",
                    description = "High-frequency harmonic exciter and vocal definition",
                    value = state.effects[FxSoundEffectId.FIDELITY] ?: FxSoundEffectId.FIDELITY.defaultValue,
                    onValueChange = { FxSoundRepository.setEffect(FxSoundEffectId.FIDELITY, it) },
                    hapticsManager = hapticsManager,
                    enabled = state.isPowerOn
                )

                // 2. Ambience
                // Not displayed. Android's PresetReverb is an auxiliary effect: the source
                // app must create the aux send, which cannot be done on another app's track,
                // and the native DfxDsp core has no in-app player anymore. Showing a dead
                // slider would misrepresent the engagement state.


                // 3. Surround Sound / 3D Spatial
                // The surround control only moves when a Virtualizer is really attached and
                // its strength is adjustable. Many devices expose no Virtualizer on a given
                // route, or expose a fixed-strength one; a live-looking slider would then be
                // lying about what the audio is doing.
                val surroundUsable = state.stageReport.isSurroundAdjustable
                EffectSlider(
                    name = "Surround Sound",
                    description = "Stereo soundstage widening for expansive acoustic space",
                    value = state.effects[FxSoundEffectId.SURROUND] ?: 3.0f,
                    onValueChange = { FxSoundRepository.setEffect(FxSoundEffectId.SURROUND, it) },
                    hapticsManager = hapticsManager,
                    enabled = state.isPowerOn && surroundUsable,
                    note = state.stageReport.virtualizerUnavailableReason()
                )

                // 4. Dynamic Boost / Maximizer
                EffectSlider(
                    name = "Dynamic Boost",
                    description = "Multiband dynamics maximizer and transparent loudness leveler",
                    value = state.effects[FxSoundEffectId.DYNAMIC_BOOST] ?: 4.0f,
                    onValueChange = { FxSoundRepository.setEffect(FxSoundEffectId.DYNAMIC_BOOST, it) },
                    hapticsManager = hapticsManager,
                    enabled = state.isPowerOn
                )

                // 5. Bass Boost / HyperBass
                // An OPRA headphone plan owns the Pre-EQ stage, so HyperBass is skipped while
                // a profile is active (single-bass rule). The control must show that instead
                // of pretending to work (B1).
                val headphonePlanActive = state.headphonePlan != null
                val bassUnavailable = !headphonePlanActive && (
                    state.stageReport.bassBoost == StageState.UNSUPPORTED ||
                        state.stageReport.bassBoost == StageState.FAILED
                    )
                EffectSlider(
                    name = "Bass Boost",
                    description = if (headphonePlanActive)
                        "Low frequencies are shaped by the active headphone profile"
                    else
                        "HyperBass low-frequency acoustic depth and punch",
                    value = state.effects[FxSoundEffectId.BASS] ?: 4.0f,
                    onValueChange = { FxSoundRepository.setEffect(FxSoundEffectId.BASS, it) },
                    hapticsManager = hapticsManager,
                    enabled = state.isPowerOn && !headphonePlanActive && !bassUnavailable,
                    note = when {
                        headphonePlanActive ->
                            "Headphone profile active — this slider is not applied. Remove the profile in the Phones tab to use it."
                        bassUnavailable ->
                            "This device/audio route does not provide a BassBoost effect, so this slider is unavailable."
                        else -> null
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}
