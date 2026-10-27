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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val customPresets by FxSoundRepository.customPresets.collectAsState()

    // Dialog state: which action is pending on which preset.
    var showSaveDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<FxSoundPreset?>(null) }
    var deleteTarget by remember { mutableStateOf<FxSoundPreset?>(null) }

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
            // ---- User presets -------------------------------------------------------
            if (customPresets.isNotEmpty()) {
                item {
                    Text(
                        text = "MY PRESETS",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
                items(customPresets, key = { it.id }) { preset ->
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
                                    letterSpacing = 0.5.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(
                                    onClick = {
                                        hapticsManager.performPresetSelected()
                                        renameTarget = preset
                                    }
                                ) {
                                    Text("RENAME", color = TextSecondary, fontSize = 11.sp)
                                }
                                TextButton(
                                    onClick = {
                                        hapticsManager.performResetConfirmation()
                                        deleteTarget = preset
                                    }
                                ) {
                                    Text("DELETE", color = FxRedPrimary, fontSize = 11.sp)
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            if (preset.description.isNotBlank()) {
                                Text(
                                    text = preset.description,
                                    color = TextSecondary,
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

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

            // ---- Save the current tuning -------------------------------------------
            item {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .clickable {
                            hapticsManager.performPresetSelected()
                            showSaveDialog = true
                        },
                    colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, BorderStrong)
                ) {
                    Text(
                        text = "+ SAVE CURRENT TUNING AS PRESET",
                        color = TextPrimary,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            // ---- Factory presets ----------------------------------------------------
            item {
                Text(
                    text = "FACTORY PRESETS",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
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

    if (showSaveDialog) {
        NamePresetDialog(
            title = "Save current tuning",
            confirmLabel = "SAVE",
            initialName = "",
            onConfirm = { name ->
                hapticsManager.performPresetSelected()
                FxSoundRepository.saveCurrentAsPreset(name)
            },
            onDismiss = { showSaveDialog = false }
        )
    }
    renameTarget?.let { preset ->
        NamePresetDialog(
            title = "Rename \u201C${preset.name}\u201D",
            confirmLabel = "RENAME",
            initialName = preset.name,
            onConfirm = { name ->
                hapticsManager.performPresetSelected()
                FxSoundRepository.renamePreset(preset.id, name)
            },
            onDismiss = { renameTarget = null }
        )
    }
    deleteTarget?.let { preset ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete preset", color = TextPrimary) },
            text = {
                Text(
                    "\u201C${preset.name}\u201D will be removed permanently. The current sliders are not changed.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    hapticsManager.performResetConfirmation()
                    FxSoundRepository.deletePreset(preset.id)
                    deleteTarget = null
                }) { Text("DELETE", color = FxRedPrimary) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("CANCEL", color = TextSecondary) }
            },
            containerColor = DarkSurfaceElevated
        )
    }
}

/**
 * Shared text-entry dialog for saving a new preset and renaming an existing one. The
 * confirm action is gated on a non-blank name, matching the repository's validation.
 */
@Composable
private fun NamePresetDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = TextPrimary) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(32) },
                singleLine = true,
                label = { Text("Preset name") }
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.trim().isNotEmpty(),
                onClick = {
                    onConfirm(name)
                    onDismiss()
                }
            ) { Text(confirmLabel, color = FxRedPrimary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL", color = TextSecondary) }
        },
        containerColor = DarkSurfaceElevated
    )
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
