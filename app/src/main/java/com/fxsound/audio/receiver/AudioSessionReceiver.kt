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

        val action = intent.action
        val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
        val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)

        Log.d(TAG, "Received audio session broadcast: action=$action, sessionId=$sessionId, pkg=$packageName")

        if (sessionId == 0) return

        val serviceIntent = Intent(context, SessionCompanionService::class.java).apply {
            this.action = action
            putExtra(AudioEffect.EXTRA_AUDIO_SESSION, sessionId)
            putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
