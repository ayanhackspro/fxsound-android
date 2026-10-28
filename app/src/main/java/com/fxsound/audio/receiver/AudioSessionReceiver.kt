package com.fxsound.audio.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.util.Log
import com.fxsound.audio.service.SessionCompanionService

/**
 * BroadcastReceiver listening for global AudioEffect control session lifecycle broadcasts.
 * Broadcast actions:
 * - android.media.action.OPEN_AUDIO_EFFECT_CONTROL_SESSION
 * - android.media.action.CLOSE_AUDIO_EFFECT_CONTROL_SESSION
 */
class AudioSessionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AudioSessionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent == null) return

        val action = intent.action ?: return
        if (action != AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION &&
            action != AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION
        ) {
            Log.w(TAG, "Ignored unrecognized action: $action")
            return
        }

        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        if (sessionId <= 0 || sessionId > 10_000_000) {
            Log.w(TAG, "Ignored broadcast with invalid sessionId: $sessionId")
            return
        }

        val rawPkg = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
        val sanitizedPkg = if (!rawPkg.isNullOrBlank() && rawPkg.length <= 256 && rawPkg.matches(Regex("^[a-zA-Z0-9_.]+$"))) {
            rawPkg
        } else {
            null
        }

        Log.d(TAG, "Received validated audio session broadcast: action=$action, sessionId=$sessionId, pkg=$sanitizedPkg")

        val serviceIntent = Intent(context, SessionCompanionService::class.java).apply {
            this.action = action
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, sanitizedPkg)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to forward session intent to service: ${e.message}")
        }
    }
}
