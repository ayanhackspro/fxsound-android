package com.fxsound.audio.data

import android.content.Context
import com.fxsound.audio.model.AcousticMetrics
import com.fxsound.audio.model.AudioOutputDeviceType
import com.fxsound.audio.model.EqRendererInfo
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.FxSoundUiState
import com.fxsound.audio.model.FxStageReport
import com.fxsound.audio.model.HapticsMode
import com.fxsound.audio.model.LatencyMode
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.model.withHeadphoneAttachResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Central thread-safe State Repository for FxSound.
 *
 * Implements:
 * - Decoupled high-frequency AcousticMetrics (47 Hz) vs low-frequency FxSoundUiState.
 * - Jetpack DataStore asynchronous persistence with 200 ms write debouncing.
 * - Active audio route and output device tracking.
 */
object FxSoundRepository {

    private val _uiState = MutableStateFlow(FxSoundUiState())
    val uiState: StateFlow<FxSoundUiState> = _uiState.asStateFlow()

    // Isolated high-frequency telemetry state (eliminates UI recomposition cascade)
    private val _metricsState = MutableStateFlow(AcousticMetrics())
    val metricsState: StateFlow<AcousticMetrics> = _metricsState.asStateFlow()

    private var preferencesDataStore: FxSoundPreferencesDataStore? = null
    private val repoScope = CoroutineScope(Dispatchers.IO)
    private var saveDebounceJob: Job? = null

    // User custom presets (create/rename/delete), persisted via DataStore. The combined
    // list consumed by the UI is built-in presets first, then the user's own.
    private val _customPresets = MutableStateFlow<List<FxSoundPreset>>(emptyList())
    val customPresets: StateFlow<List<FxSoundPreset>> = _customPresets.asStateFlow()
    val allPresets: List<FxSoundPreset>
        get() = FxSoundPreset.ALL_PRESETS + _customPresets.value

    // Listener callback for live audio effect synchronization
    var onStateChangedListener: ((FxSoundUiState) -> Unit)? = null

    fun initPersistence(context: Context) {
        if (preferencesDataStore != null) return
        val dataStore = FxSoundPreferencesDataStore(context.applicationContext)
        preferencesDataStore = dataStore
        initOpra(context.applicationContext)
        repoScope.launch {
            val savedState = dataStore.loadState()
            _uiState.update { current ->
                current.copy(
                    isPowerOn = savedState.isPowerOn,
                    currentPresetId = savedState.currentPresetId,
                    effects = savedState.effects,
                    eqGains = savedState.eqGains,
                    masterGainDb = savedState.masterGainDb,
                    hapticsMode = savedState.hapticsMode,
                    latencyMode = savedState.latencyMode
                )
            }
            notifyStateChanged()

            // Custom presets load in the same bootstrap window as the saved UI state.
            _customPresets.value = dataStore.loadCustomPresets()

            // OPRA selection restore: the persisted profile is re-converted and re-set
            // through the same path a fresh selection takes.
            try {
                opraInstance?.restoreSelection(dataStore.loadOpraSelection())
            } catch (e: Exception) {
                // Headphone plan restore must never break app startup (ADR-009).
            }
        }
    }

    // OPRA headphone-tuning plan (companion path). The plan lives in uiState so the
    // existing state-change listener in SessionCompanionService rebuilds the
    // DynamicsProcessing config without any new plumbing.
    var opraInstance: com.fxsound.audio.opra.OpraRepository? = null
        private set

    private fun initOpra(context: Context) {
        if (opraInstance == null) {
            opraInstance = com.fxsound.audio.opra.OpraRepository(context)
        }
    }

    /** Called by the OPRA screen to attach a plan, persist it, and notify the service. */
    fun setHeadphonePlan(
        plan: com.fxsound.audio.opra.HeadphoneEqPlan?,
        profile: com.fxsound.audio.opra.OpraEqProfile?,
        adjustDb: Float,
        bandOffsets: List<Float> = emptyList()
    ) {
        _uiState.update {
            it.copy(headphonePlan = plan, isHeadphonePlanApplied = false)
        }
        notifyStateChanged()
        repoScope.launch {
            preferencesDataStore?.saveOpraSelection(
                if (profile != null) {
                    com.fxsound.audio.opra.OpraJson.encodeForPersistence(profile, adjustDb, bandOffsets)
                } else {
                    null
                }
            )
        }
    }

    /** The companion service confirms (or rejects) a DynamicsProcessing attach using the plan. */
    fun onHeadphoneAttachResult(success: Boolean) {
        _uiState.update { it.withHeadphoneAttachResult(success) }
        opraInstance?.confirmApplied(success)
    }

    /**
     * Creates a named custom preset from the current tuning (the five effect values and
     * the 10-band EQ). Uniqueness of the id comes from the timestamp; names are free text
     * and are only expected to be readable.
     */
    fun saveCurrentAsPreset(name: String, description: String = "") {
        val trimmedName = name.trim()
        if (trimmedName.isEmpty()) return

        val current = _uiState.value
        val preset = FxSoundPreset(
            id = "user_" + System.currentTimeMillis(),
            name = trimmedName,
            description = description.trim(),
            clarity = current.effects[FxSoundEffectId.FIDELITY] ?: 5.0f,
            ambience = current.effects[FxSoundEffectId.AMBIENCE] ?: 2.0f,
            surround = current.effects[FxSoundEffectId.SURROUND] ?: 3.0f,
            dynamicBoost = current.effects[FxSoundEffectId.DYNAMIC_BOOST] ?: 4.0f,
            bass = current.effects[FxSoundEffectId.BASS] ?: 4.0f,
            eqGains = current.eqGains
        )
        _customPresets.value = _customPresets.value + preset
        persistCustomPresets()
    }

    fun renamePreset(presetId: String, newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        _customPresets.update { list ->
            list.map { if (it.id == presetId) it.copy(name = trimmed) else it }
        }
        persistCustomPresets()
    }

    fun deletePreset(presetId: String) {
        val before = _customPresets.value
        val after = before.filter { it.id != presetId }
        if (after.size == before.size) return
        _customPresets.value = after
        persistCustomPresets()

        // If the deleted preset was active, the tuning on the sliders is kept (it is not
        // lost) but the selection must stop claiming a preset that no longer exists.
        _uiState.update { current ->
            if (current.currentPresetId == presetId) {
                current.copy(currentPresetId = "custom")
            } else {
                current
            }
        }
        notifyStateChanged()
        scheduleSave()
    }

    private fun persistCustomPresets() {
        val dataStore = preferencesDataStore ?: return
        val snapshot = _customPresets.value
        repoScope.launch { dataStore.saveCustomPresets(snapshot) }
    }

    fun setPower(enabled: Boolean) {
        _uiState.update { current ->
            val newRouteStatus = if (!enabled) {
                RouteStatus.OFF
            } else {
                if (current.activeAudioSessionId != 0) {
                    if (current.activePackageName?.contains("spotify", ignoreCase = true) == true) {
                        RouteStatus.CONNECTED_SPOTIFY
                    } else {
                        RouteStatus.CONNECTED_PLAYER
                    }
                } else {
                    RouteStatus.READY
                }
            }
            current.copy(isPowerOn = enabled, routeStatus = newRouteStatus)
        }
        notifyStateChanged()
        scheduleSave()
    }

    fun setEffect(effectId: FxSoundEffectId, value: Float) {
        _uiState.update { current ->
            val updated = current.effects.toMutableMap()
            updated[effectId] = value.coerceIn(0.0f, 10.0f)
            current.copy(effects = updated, currentPresetId = "custom")
        }
        notifyStateChanged()
        scheduleSave()
    }

    fun setEqBand(bandIndex: Int, gainDb: Float) {
        if (bandIndex !in 0..9) return
        _uiState.update { current ->
            val updated = current.eqGains.toMutableList()
            updated[bandIndex] = gainDb.coerceIn(-14.0f, 14.0f)
            current.copy(eqGains = updated, currentPresetId = "custom")
        }
        notifyStateChanged()
        scheduleSave()
    }

    fun resetEq() {
        _uiState.update { current ->
            current.copy(eqGains = List(10) { 0.0f })
        }
        notifyStateChanged()
        scheduleSave()
    }

    fun applyPreset(preset: FxSoundPreset) {
        _uiState.update { current ->
            val effectsMap = mapOf(
                FxSoundEffectId.FIDELITY to preset.clarity,
                FxSoundEffectId.AMBIENCE to preset.ambience,
                FxSoundEffectId.SURROUND to preset.surround,
                FxSoundEffectId.DYNAMIC_BOOST to preset.dynamicBoost,
                FxSoundEffectId.BASS to preset.bass
            )
            current.copy(
                currentPresetId = preset.id,
                effects = effectsMap,
                eqGains = preset.eqGains
            )
        }
        notifyStateChanged()
        scheduleSave()
    }

    fun setMasterGain(gainDb: Float) {
        _uiState.update { current ->
            current.copy(masterGainDb = gainDb.coerceIn(-12.0f, 6.0f))
        }
        notifyStateChanged()
        scheduleSave()
    }

    fun setRouteStatus(status: RouteStatus, packageName: String? = null, sessionId: Int = 0) {
        _uiState.update { current ->
            val resolvedStatus = if (!current.isPowerOn) RouteStatus.OFF else status
            current.copy(
                routeStatus = resolvedStatus,
                activePackageName = packageName,
                activeAudioSessionId = sessionId
            )
        }
        notifyStateChanged()
    }

    fun setOutputDevice(deviceType: AudioOutputDeviceType) {
        _uiState.update { it.copy(outputDevice = deviceType) }
        notifyStateChanged()
    }

    /**
     * Records which renderer the companion service attached. Deliberately does NOT call
     * [notifyStateChanged]: this is reported *by* the service, so echoing it back would
     * re-enter the update loop that produced it. The UI collects [uiState] directly.
     */
    fun setEqRenderer(info: EqRendererInfo) {
        _uiState.update { it.copy(eqRenderer = info) }
    }

    /**
     * Records which optional DSP stages are really attached. Like [setEqRenderer] this is
     * reported *by* the service, so it deliberately does not echo state back into it.
     */
    fun setStageReport(report: FxStageReport) {
        _uiState.update { it.copy(stageReport = report) }
    }

    /**
     * Fast-rate audio telemetry update (invoked up to 47 times per second).
     * Mutates ONLY _metricsState; does NOT trigger recomposition on FxSoundUiState.
     */
    fun updateMetrics(
        inputPeak: Float,
        outputPeak: Float,
        inputRms: Float,
        outputRms: Float,
        isClipping: Boolean,
        spectrum: FloatArray = FloatArray(16)
    ) {
        _metricsState.update {
            it.copy(
                inputPeak = inputPeak,
                outputPeak = outputPeak,
                inputRms = inputRms,
                outputRms = outputRms,
                isClipping = isClipping,
                spectrumBands = spectrum
            )
        }
    }

    fun setHapticsMode(mode: HapticsMode) {
        _uiState.update { it.copy(hapticsMode = mode) }
        scheduleSave()
    }

    fun setLatencyMode(mode: LatencyMode) {
        _uiState.update { it.copy(latencyMode = mode) }
        notifyStateChanged()
        scheduleSave()
    }

    fun setEnhancedTrackingGranted(granted: Boolean) {
        _uiState.update { it.copy(isEnhancedTrackingGranted = granted) }
    }

    fun togglePower(): Boolean {
        val newPower = !_uiState.value.isPowerOn
        setPower(newPower)
        return newPower
    }

    fun cycleNextPreset() {
        val presets = FxSoundPreset.ALL_PRESETS
        val currentIndex = presets.indexOfFirst { it.id == _uiState.value.currentPresetId }
        val nextIndex = if (currentIndex == -1 || currentIndex == presets.lastIndex) 0 else currentIndex + 1
        applyPreset(presets[nextIndex])
    }

    fun cyclePreviousPreset() {
        val presets = FxSoundPreset.ALL_PRESETS
        val currentIndex = presets.indexOfFirst { it.id == _uiState.value.currentPresetId }
        val prevIndex = if (currentIndex <= 0) presets.lastIndex else currentIndex - 1
        applyPreset(presets[prevIndex])
    }

    private fun notifyStateChanged() {
        onStateChangedListener?.invoke(_uiState.value)
    }

    private fun scheduleSave() {
        val ds = preferencesDataStore ?: return
        saveDebounceJob?.cancel()
        saveDebounceJob = repoScope.launch {
            delay(200)
            ds.saveState(_uiState.value)
        }
    }
}
