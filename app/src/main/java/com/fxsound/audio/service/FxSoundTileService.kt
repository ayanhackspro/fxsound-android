package com.fxsound.audio.service

import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fxsound.audio.R
import com.fxsound.audio.data.FxSoundRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Android Quick Settings (QS) Tile Service for one-tap FxSound Power toggle.
 * Provides instant status indication in the notification shade and quick toggle.
 */
class FxSoundTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main)
    private var stateObserverJob: Job? = null

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()

        // Observe repository state changes to keep tile synchronized in real-time
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            FxSoundRepository.uiState.collect {
                updateTileState()
            }
        }
    }

    override fun onStopListening() {
        stateObserverJob?.cancel()
        stateObserverJob = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        // Toggle power on tap
        val isPowerOn = FxSoundRepository.togglePower()

        // Ensure background companion service is alive if user turned power ON
        if (isPowerOn) {
            SessionCompanionService.start(this)
        }

        updateTileState()
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        val state = FxSoundRepository.uiState.value

        tile.state = if (state.isPowerOn) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(this, R.drawable.ic_power)
        tile.label = "FxSound"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (state.isPowerOn) {
                if (state.currentPresetId == "custom") "Custom" else state.currentPresetId.replaceFirstChar { it.uppercase() }
            } else {
                "Bypassed"
            }
        }

        tile.updateTile()
    }
}
