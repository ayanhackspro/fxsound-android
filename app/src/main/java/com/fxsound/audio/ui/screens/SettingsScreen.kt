package com.fxsound.audio.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import android.widget.Toast
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.R
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.engine.DynamicsProcessingTranslator
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.model.EqRendererKind
import com.fxsound.audio.model.FxSoundUiState
import com.fxsound.audio.model.HapticsMode
import com.fxsound.audio.model.LatencyMode
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.model.StageState
import com.fxsound.audio.nativebridge.FxSoundNativeBridge
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkBackground
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.PendingAmber
import com.fxsound.audio.ui.theme.TextDisabled
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

@Composable
fun SettingsScreen(
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier
) {
    val state by FxSoundRepository.uiState.collectAsState()
    val scrollState = rememberScrollState()
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = "SETTINGS & DIAGNOSTICS",
            color = TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Text(
            text = "Tactile haptics, native DSP diagnostics, and engine status",
            color = TextTertiary,
            fontSize = 12.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Haptic Feedback Settings
        Text(
            text = "HAPTIC FEEDBACK",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                HapticsOptionRow(
                    title = "System Default",
                    description = "Follows Android system touch-feedback preference",
                    selected = state.hapticsMode == HapticsMode.SYSTEM_DEFAULT,
                    onClick = {
                        FxSoundRepository.setHapticsMode(HapticsMode.SYSTEM_DEFAULT)
                        hapticsManager.performPresetSelected()
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                HapticsOptionRow(
                    title = "Enhanced (LRA Primitives)",
                    description = "Subtle tactile clicks via Android 11+ VibrationEffect.Composition",
                    selected = state.hapticsMode == HapticsMode.ENHANCED,
                    onClick = {
                        FxSoundRepository.setHapticsMode(HapticsMode.ENHANCED)
                        hapticsManager.performPresetSelected()
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                HapticsOptionRow(
                    title = "Off",
                    description = "Completely disable all tactile feedback",
                    selected = state.hapticsMode == HapticsMode.OFF,
                    onClick = {
                        FxSoundRepository.setHapticsMode(HapticsMode.OFF)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Rootless Enhanced Player Tracking (ADB / Shizuku)
        Text(
            text = "ROOTLESS ENHANCED TRACKING (ADB / SHIZUKU)",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "AudioFlinger Direct Tracking",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (state.isEnhancedTrackingGranted) Color(0xFF143820) else Color(0xFF2E2010))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (state.isEnhancedTrackingGranted) "TRACKING" else "STANDARD",
                            color = if (state.isEnhancedTrackingGranted) Color(0xFF4CAF50) else Color(0xFFFFB300),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = if (state.isEnhancedTrackingGranted) {
                        "Rootless AudioFlinger tracking is active. FxSound can discover playback " +
                            "sessions for apps that do not broadcast an audio session, such as YouTube, " +
                            "Chrome and games. Whether a discovered session can actually be attached " +
                            "depends on the device audio HAL, and DRM-protected audio is never captured."
                    } else {
                        "Apps like YouTube, Chrome, and games do not broadcast audio sessions. Grant rootless DUMP permission via ADB or Shizuku to let FxSound discover their playback sessions:"
                    },
                    color = TextSecondary,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )

                if (!state.isEnhancedTrackingGranted) {
                    Spacer(modifier = Modifier.height(10.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurfaceElevated)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = "adb shell pm grant com.fxsound.audio android.permission.DUMP",
                            color = FxRedPrimary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            hapticsManager.performPresetSelected()
                            clipboardManager.setText(AnnotatedString("adb shell pm grant com.fxsound.audio android.permission.DUMP"))
                            Toast.makeText(context, "Command copied to clipboard", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Copy ADB Command")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Acoustic Physics & Sound Processing
        Text(
            text = "ACOUSTIC PHYSICS & SOUND LATENCY",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Latency Buffer Schedule",
                            color = TextPrimary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Choose the audio buffering strategy the companion service negotiates.",
                            color = TextSecondary,
                            fontSize = 11.sp,
                            lineHeight = 15.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Latency & Pipeline Architecture",
                    color = TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Choose between high-precision Hi-Fi audio buffering and ultra-low latency response for mobile gaming.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                HapticsOptionRow(
                    title = "Hi-Fi Studio Buffer",
                    description = "Optimal DSP resolution & battery efficiency (1024-2048 frames)",
                    selected = state.latencyMode == LatencyMode.STUDIO,
                    onClick = {
                        hapticsManager.performPresetSelected()
                        FxSoundRepository.setLatencyMode(LatencyMode.STUDIO)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                HapticsOptionRow(
                    title = "Ultra-Low Latency (Gaming)",
                    description = "Fast real-time buffer (~256 frames, low latency audio route)",
                    selected = state.latencyMode == LatencyMode.LOW_LATENCY,
                    onClick = {
                        hapticsManager.performPresetSelected()
                        FxSoundRepository.setLatencyMode(LatencyMode.LOW_LATENCY)
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Engine Diagnostics
        Text(
            text = "AUDIO ENGINE DIAGNOSTICS",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                DiagnosticRow("Audio Route", state.routeStatus.label)
                DiagnosticRow("Active Package", state.activePackageName ?: "None (Awaiting Session)")
                DiagnosticRow("Session ID", if (state.activeAudioSessionId != 0) state.activeAudioSessionId.toString() else "None")
                DiagnosticRow("EQ Renderer", eqRendererLabel(state))
                DiagnosticRow("Bass Boost Stage", bassStageLabel(state))
                DiagnosticRow("Virtualizer Stage", virtualizerStageLabel(state))
                if (state.eqRenderer.isBandCollapsed) {
                    Text(
                        text = "This device's system equalizer exposes only " +
                            "${state.eqRenderer.bandCount} bands, so the 10 faders are combined onto " +
                            "them. Neighbouring high bands (8k, 16k) can share one band and may sound alike.",
                        color = PendingAmber,
                        fontSize = 10.sp,
                        lineHeight = 14.sp,
                        modifier = Modifier.padding(bottom = 6.dp)
                    )
                }
                DiagnosticRow("AudioFlinger DUMP", if (state.isEnhancedTrackingGranted) "Granted (Session Tracking)" else "Standard (Not Granted)")
                DiagnosticRow("Native C++ Library", if (FxSoundNativeBridge.isNativeAvailable()) "Loaded (arm64-v8a / x86_64)" else "Unavailable")
                DiagnosticRow("Latency Buffer", if (state.latencyMode == LatencyMode.LOW_LATENCY) "Low Latency (Fast)" else "Hi-Fi Studio")
                DiagnosticRow("Dynamics Mode", "Time Resolution (Low Latency)")
                DiagnosticRow(
                    "Peak Limiter",
                    "${DynamicsProcessingTranslator.LimiterSpec.RATIO.toInt()}:1 @ " +
                        "${DynamicsProcessingTranslator.LimiterSpec.THRESHOLD_DBFS} dBFS"
                )
                DiagnosticRow("Upstream Commit", "d8e7a23d37ed5939c2a3090a1c1756c7f2500b17")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Privacy & Open Source
        Text(
            text = "PRIVACY & LICENSE",
            color = TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkSurfaceCard),
            shape = RoundedCornerShape(14.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "AGPL-3.0 License Compliance",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "The audio DSP core incorporates source code from the official FxSound repository (fxsound2/fxsound-app), licensed under GNU Affero General Public License v3.0. Complete source code and modifications are available in this repository.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Zero Tracking & Zero Telemetry",
                    color = TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "This application operates 100% locally on your device. There are zero analytics, zero crash reporters, zero advertising SDKs, and zero cloud accounts.",
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun HapticsOptionRow(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = if (selected) FxRedPrimary else TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = TextSecondary,
                fontSize = 11.sp
            )
        }

        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_check),
                contentDescription = "Selected",
                tint = FxRedPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = TextPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.weight(1.4f, fill = false)
        )
    }
}

/** What the EQ is actually attached to, for the diagnostics card. */
private fun eqRendererLabel(state: FxSoundUiState): String = when (state.eqRenderer.kind) {
    EqRendererKind.DYNAMICS_PROCESSING -> "DynamicsProcessing (10 bands)"
    EqRendererKind.PLATFORM_EQUALIZER ->
        "Platform Equalizer (" + state.eqRenderer.bandCount + " bands)"
    EqRendererKind.NONE -> "None (no session attached)"
    EqRendererKind.UNKNOWN -> "Not attached yet"
}

/** Whether the standalone bass stage attached, or the Pre-EQ is carrying the low end. */
private fun bassStageLabel(state: FxSoundUiState): String = when (state.stageReport.bassBoost) {
    StageState.ACTIVE -> "Attached (standalone)"
    StageState.BYPASSED -> if (state.eqRenderer.kind == EqRendererKind.DYNAMICS_PROCESSING) {
        "Handled by Pre-EQ"
    } else {
        "Bypassed"
    }
    StageState.FIXED -> "Fixed strength"
    StageState.UNSUPPORTED -> "Unsupported by device"
    StageState.FAILED -> "Update failed"
    StageState.UNKNOWN -> "Not attached yet"
}

/** Whether the surround stage attached and whether its strength can actually be changed. */
private fun virtualizerStageLabel(state: FxSoundUiState): String =
    when (state.stageReport.virtualizer) {
        StageState.ACTIVE -> "Attached (strength adjustable)"
        StageState.FIXED -> "Attached (fixed strength)"
        StageState.BYPASSED -> "Bypassed"
        StageState.UNSUPPORTED -> "Unsupported by device/route"
        StageState.FAILED -> "Update failed"
        StageState.UNKNOWN -> "Not attached yet"
    }