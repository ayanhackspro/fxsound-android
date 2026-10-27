package com.fxsound.audio.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.FxSoundUiState
import com.fxsound.audio.model.HapticsMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "fxsound_settings")

/**
 * Reactive and asynchronous Jetpack DataStore persistence layer for FxSound.
 * Implements ADR-009 (DataStore Preferences for Application State Persistence).
 */
class FxSoundPreferencesDataStore(private val context: Context) {

    companion object {
        private val KEY_POWER_ON = booleanPreferencesKey("power_on")
        private val KEY_PRESET_ID = stringPreferencesKey("preset_id")
        private val KEY_EFFECT_FIDELITY = floatPreferencesKey("effect_fidelity")
        private val KEY_EFFECT_AMBIENCE = floatPreferencesKey("effect_ambience")
        private val KEY_EFFECT_SURROUND = floatPreferencesKey("effect_surround")
        private val KEY_EFFECT_DYNAMIC_BOOST = floatPreferencesKey("effect_dynamic_boost")
        private val KEY_EFFECT_BASS = floatPreferencesKey("effect_bass")
        private val KEY_EQ_GAINS = stringPreferencesKey("eq_gains_csv")
        private val KEY_MASTER_GAIN = floatPreferencesKey("master_gain_db")
        private val KEY_HAPTICS_MODE = stringPreferencesKey("haptics_mode")
        private val KEY_LATENCY_MODE = stringPreferencesKey("latency_mode")
        private val KEY_CUSTOM_PRESETS_JSON = stringPreferencesKey("custom_presets_json")
    }

    suspend fun loadState(): FxSoundUiState {
        return try {
            val prefs = context.dataStore.data.first()
            val isPowerOn = prefs[KEY_POWER_ON] ?: true
            val presetId = prefs[KEY_PRESET_ID] ?: FxSoundPreset.BALANCED.id

            val effectsMap = mapOf(
                FxSoundEffectId.FIDELITY to (prefs[KEY_EFFECT_FIDELITY] ?: 5.0f),
                FxSoundEffectId.AMBIENCE to (prefs[KEY_EFFECT_AMBIENCE] ?: 2.0f),
                FxSoundEffectId.SURROUND to (prefs[KEY_EFFECT_SURROUND] ?: 3.0f),
                FxSoundEffectId.DYNAMIC_BOOST to (prefs[KEY_EFFECT_DYNAMIC_BOOST] ?: 4.0f),
                FxSoundEffectId.BASS to (prefs[KEY_EFFECT_BASS] ?: 4.0f)
            )

            val eqCsv = prefs[KEY_EQ_GAINS]
            val eqGains = if (!eqCsv.isNullOrBlank()) {
                eqCsv.split(",").mapNotNull { it.trim().toFloatOrNull() }.takeIf { it.size == 10 }
                    ?: List(10) { 0.0f }
            } else {
                List(10) { 0.0f }
            }

            val masterGain = prefs[KEY_MASTER_GAIN] ?: 0.0f
            val hapticsStr = prefs[KEY_HAPTICS_MODE] ?: HapticsMode.SYSTEM_DEFAULT.name
            val hapticsMode = try {
                HapticsMode.valueOf(hapticsStr)
            } catch (e: Exception) {
                HapticsMode.SYSTEM_DEFAULT
            }
            val latencyStr = prefs[KEY_LATENCY_MODE] ?: com.fxsound.audio.model.LatencyMode.STUDIO.name
            val latencyMode = try {
                com.fxsound.audio.model.LatencyMode.valueOf(latencyStr)
            } catch (e: Exception) {
                com.fxsound.audio.model.LatencyMode.STUDIO
            }

            FxSoundUiState(
                isPowerOn = isPowerOn,
                currentPresetId = presetId,
                effects = effectsMap,
                eqGains = eqGains,
                masterGainDb = masterGain,
                hapticsMode = hapticsMode,
                latencyMode = latencyMode
            )
        } catch (e: Exception) {
            FxSoundUiState()
        }
    }

    suspend fun saveState(state: FxSoundUiState) {
        try {
            context.dataStore.edit { prefs ->
                prefs[KEY_POWER_ON] = state.isPowerOn
                prefs[KEY_PRESET_ID] = state.currentPresetId
                prefs[KEY_EFFECT_FIDELITY] = state.effects[FxSoundEffectId.FIDELITY] ?: 5.0f
                prefs[KEY_EFFECT_AMBIENCE] = state.effects[FxSoundEffectId.AMBIENCE] ?: 2.0f
                prefs[KEY_EFFECT_SURROUND] = state.effects[FxSoundEffectId.SURROUND] ?: 3.0f
                prefs[KEY_EFFECT_DYNAMIC_BOOST] = state.effects[FxSoundEffectId.DYNAMIC_BOOST] ?: 4.0f
                prefs[KEY_EFFECT_BASS] = state.effects[FxSoundEffectId.BASS] ?: 4.0f
                prefs[KEY_EQ_GAINS] = state.eqGains.joinToString(",") { String.format("%.2f", it) }
                prefs[KEY_MASTER_GAIN] = state.masterGainDb
                prefs[KEY_HAPTICS_MODE] = state.hapticsMode.name
                prefs[KEY_LATENCY_MODE] = state.latencyMode.name
            }
        } catch (e: Exception) {
            // Non-fatal persistence failure
        }
    }

    /**
     * Loads the user's custom presets from the single JSON payload. Malformed data
     * returns an empty list rather than throwing, so a corrupted entry cannot break app
     * startup (ADR-009).
     */
    suspend fun loadCustomPresets(): List<FxSoundPreset> {
        return try {
            val prefs = context.dataStore.data.first()
            PresetCodec.decode(prefs[KEY_CUSTOM_PRESETS_JSON])
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Replaces the whole custom preset list atomically; deletes and renames included. */
    suspend fun saveCustomPresets(presets: List<FxSoundPreset>) {
        try {
            context.dataStore.edit { prefs ->
                prefs[KEY_CUSTOM_PRESETS_JSON] = PresetCodec.encode(presets)
            }
        } catch (e: Exception) {
            // Non-fatal persistence failure
        }
    }
}
