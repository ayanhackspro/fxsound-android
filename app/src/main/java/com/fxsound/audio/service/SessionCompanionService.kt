package com.fxsound.audio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.fxsound.audio.MainActivity
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.engine.DynamicsProcessingTranslator
import com.fxsound.audio.engine.FxSoundResponseTables
import com.fxsound.audio.model.AudioOutputDeviceType
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.FxSoundUiState
import com.fxsound.audio.model.RouteStatus
import java.util.concurrent.ConcurrentHashMap

/**
 * Foreground companion service managing active audio effect sessions for Spotify
 * and external music players.
 *
 * Implements ADR-007 (Session Companion Engine), ADR-011 (AudioDeviceCallback Routing),
 * ADR-012 (Spotify Ambience via PresetReverb), and ADR-014 (Interactive MediaStyle Notification).
 */
class SessionCompanionService : Service() {

    companion object {
        private const val TAG = "SessionCompanion"
        private const val CHANNEL_ID = "fxsound_companion_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_COMPANION = "com.fxsound.audio.action.START_COMPANION"
        const val ACTION_STOP_COMPANION = "com.fxsound.audio.action.STOP_COMPANION"
        const val ACTION_REAPPLY_EFFECTS = "com.fxsound.audio.action.REAPPLY_EFFECTS"
        const val ACTION_TOGGLE_POWER = "com.fxsound.audio.action.TOGGLE_POWER"
        const val ACTION_CYCLE_PRESET = "com.fxsound.audio.action.CYCLE_PRESET"

        private const val SESSION_TIMEOUT_MS = 15 * 60 * 1000L // 15 min idle watchdog
        private const val WATCHDOG_INTERVAL_MS = 2500L         // Fast 2.5s polling for immediate responsiveness

        fun start(context: Context) {
            val intent = Intent(context, SessionCompanionService::class.java).apply {
                action = ACTION_START_COMPANION
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SessionCompanionService::class.java).apply {
                action = ACTION_STOP_COMPANION
            }
            context.startService(intent)
        }
    }

    private data class ActiveSession(
        val sessionId: Int,
        val packageName: String?,
        var dynamicsProcessing: DynamicsProcessing? = null,
        var equalizer: Equalizer? = null,
        var bassBoost: BassBoost? = null,
        var virtualizer: Virtualizer? = null,
        /**
         * True when the session's DynamicsProcessing Pre-EQ carries the low-frequency
         * (HyperBass) contour. When true the standalone BassBoost must stay disabled, so the
         * two low-frequency boosts never run in series.
         * See DynamicsProcessingTranslator.resolveBassStrategy.
         */
        var usesPreEqHyperBass: Boolean = false,
        /** Band count currenly configured in the live Pre-EQ; a change forces effect recreation. */
        var preEqBandCount: Int = 10,
        var lastActiveTimeMs: Long = System.currentTimeMillis()
    )

    private val activeSessions = ConcurrentHashMap<Int, ActiveSession>()
    private var audioManager: AudioManager? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null

    private var currentWatchdogDelay = WATCHDOG_INTERVAL_MS
    private var lastMusicActiveTimeMs = System.currentTimeMillis()

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val watchdogRunnable = object : Runnable {
        override fun run() {
            checkSessionTimeouts()
            watchdogHandler.postDelayed(this, currentWatchdogDelay)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SessionCompanionService created.")
        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        createNotificationChannel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(RouteStatus.READY, null),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(NOTIFICATION_ID, buildNotification(RouteStatus.READY, null))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start typed foreground service: ${e.message}", e)
            try {
                startForeground(NOTIFICATION_ID, buildNotification(RouteStatus.READY, null))
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback startForeground failed: ${e2.message}", e2)
            }
        }

        // Initialize Global Session 0 immediately so music playback is enhanced from first beat
        ensureGlobalSessionActive()

        // Hook repository state changes to update effects and notification live
        FxSoundRepository.onStateChangedListener = { state ->
            updateAllActiveSessions(state)
            updateNotification(state.routeStatus, state.activePackageName)
        }

        // Register AudioDeviceCallback for dynamic physical output route tracking
        registerAudioDeviceCallback()

        // Start session expiration & music detection watchdog
        watchdogHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) return START_STICKY

        when (intent.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> {
                val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                val packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME)
                if (sessionId != 0) {
                    onSessionOpened(sessionId, packageName)
                }
            }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> {
                val sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, 0)
                if (sessionId != 0) {
                    onSessionClosed(sessionId)
                }
            }
            ACTION_TOGGLE_POWER -> {
                FxSoundRepository.togglePower()
            }
            ACTION_CYCLE_PRESET -> {
                FxSoundRepository.cycleNextPreset()
            }
            ACTION_REAPPLY_EFFECTS -> {
                updateAllActiveSessions(FxSoundRepository.uiState.value)
            }
            ACTION_STOP_COMPANION -> {
                releaseAllSessions()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun registerAudioDeviceCallback() {
        val am = audioManager ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                updateAudioOutputDevice()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                updateAudioOutputDevice()
            }
        }
        audioDeviceCallback = callback
        am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        updateAudioOutputDevice()
    }

    private fun updateAudioOutputDevice() {
        val am = audioManager ?: return
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        var detectedType = AudioOutputDeviceType.BUILTIN_SPEAKER

        for (device in devices) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID -> {
                    detectedType = AudioOutputDeviceType.BLUETOOTH_A2DP
                    break
                }
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> {
                    detectedType = AudioOutputDeviceType.USB_HEADSET
                }
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL -> {
                    if (detectedType != AudioOutputDeviceType.BLUETOOTH_A2DP &&
                        detectedType != AudioOutputDeviceType.USB_HEADSET
                    ) {
                        detectedType = AudioOutputDeviceType.WIRED_HEADPHONES
                    }
                }
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> {
                    // Fallback baseline
                }
            }
        }

        FxSoundRepository.setOutputDevice(detectedType)
    }

    private fun checkSessionTimeouts() {
        val am = audioManager
        val isMusicPlaying = am?.isMusicActive ?: false
        val now = System.currentTimeMillis()

        // Check rootless AudioFlinger DUMP permission state
        val hasDump = AudioFlingerSessionTracker.isDumpPermissionGranted(this)
        FxSoundRepository.setEnhancedTrackingGranted(hasDump)

        // Guarantee Global Session 0 is continuously available
        ensureGlobalSessionActive()

        val explicitSessions = activeSessions.filterKeys { it != 0 }
        val currentUiState = FxSoundRepository.uiState.value

        if (isMusicPlaying) {
            lastMusicActiveTimeMs = now
            currentWatchdogDelay = WATCHDOG_INTERVAL_MS

            // Rootless Wavelet/Poweramp tracker: Discover active playback sessions from AudioFlinger
            if (hasDump) {
                val detected = AudioFlingerSessionTracker.queryActiveSessions()
                for (sessId in detected) {
                    if (!activeSessions.containsKey(sessId)) {
                        Log.i(TAG, "AudioFlinger tracker found active session $sessId. Attaching...")
                        onSessionOpened(sessId, "Media Player #$sessId")
                    }
                }
            }

            for ((_, session) in activeSessions) {
                session.lastActiveTimeMs = now
            }

            // Only the global mix (session 0) is attached. Many source apps bypass session 0,
            // so this is reported as a partial route rather than as an active processing route.
            if (explicitSessions.isEmpty() && currentUiState.routeStatus != RouteStatus.PLAYING_HIFI) {
                if (currentUiState.routeStatus != RouteStatus.PARTIAL) {
                    FxSoundRepository.setRouteStatus(RouteStatus.PARTIAL, "System Audio", 0)
                    updateNotification(RouteStatus.PARTIAL, "System Audio")
                }
            }
        } else {
            // Zero-wakelock battery conservation: Throttle polling to 10s if audio has been idle for > 15 seconds
            if (now - lastMusicActiveTimeMs > 15000L) {
                currentWatchdogDelay = 10000L
            }

            val expiredSessions = mutableListOf<Int>()
            for ((id, session) in explicitSessions) {
                if (now - session.lastActiveTimeMs > SESSION_TIMEOUT_MS) {
                    Log.i(TAG, "Audio session $id timed out after 15 minutes of inactivity; cleaning up effects.")
                    expiredSessions.add(id)
                }
            }
            for (id in expiredSessions) {
                onSessionClosed(id)
            }

            if (explicitSessions.isEmpty() && currentUiState.routeStatus != RouteStatus.PLAYING_HIFI) {
                if (currentUiState.routeStatus != RouteStatus.READY) {
                    FxSoundRepository.setRouteStatus(RouteStatus.READY, null, 0)
                    updateNotification(RouteStatus.READY, null)
                }
            }
        }
    }

    private fun ensureGlobalSessionActive() {
        if (activeSessions.containsKey(0)) return

        Log.i(TAG, "Attaching FxSound pipeline to Global Audio Session 0 (System Mix)...")
        val state = FxSoundRepository.uiState.value
        val session = ActiveSession(0, "System Audio")

        // 1. DynamicsProcessing on Session 0 (Multi-band compressor, pre/post EQ, peak limiter)
        try {
            val config = DynamicsProcessingTranslator.buildDynamicsConfig(
                isPowerOn = state.isPowerOn,
                effects = state.effects,
                eqGains = state.eqGains,
                masterGainDb = state.masterGainDb,
                headphonePlan = state.headphonePlan
            )
            val dp = DynamicsProcessing(0, 0, config).apply {
                enabled = state.isPowerOn
            }
            session.dynamicsProcessing = dp
            session.usesPreEqHyperBass = state.headphonePlan == null
            session.preEqBandCount = state.headphonePlan?.bands?.size ?: 10
            FxSoundRepository.onHeadphoneAttachResult(true)
            Log.i(TAG, "DynamicsProcessing attached to Session 0 successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing on Session 0 unavailable: ${e.message}. Using standard Equalizer fallback.")
            try {
                val eq = Equalizer(0, 0).apply {
                    applyEqualizerBands(this, state.eqGains, state.effects[FxSoundEffectId.FIDELITY] ?: 5.0f)
                    enabled = state.isPowerOn
                }
                session.equalizer = eq
                Log.i(TAG, "Standard Equalizer attached to Session 0 successfully.")
            } catch (eqEx: Exception) {
                Log.w(TAG, "Standard Equalizer also unavailable on Session 0: ${eqEx.message}")
            }
        }

        // 2. BassBoost on Session 0 — only when DynamicsProcessing is absent.
        // When the Pre-EQ HyperBass contour is active, a second low-frequency boost would
        // compound the gain and drive the limiter into continuous reduction.
        val bassStrategy = DynamicsProcessingTranslator.resolveBassStrategy(
            hasDynamicsProcessing = session.dynamicsProcessing != null,
            bassEffectValue = state.effects[FxSoundEffectId.BASS] ?: 4.0f,
            headphonePlanActive = state.headphonePlan != null
        )
        session.usesPreEqHyperBass = bassStrategy.usesPreEqHyperBass
        if (bassStrategy.standaloneBassBoostStrength > 0) {
            try {
                val bass = BassBoost(0, 0).apply {
                    setStrength(bassStrategy.standaloneBassBoostStrength)
                    enabled = state.isPowerOn
                }
                session.bassBoost = bass
            } catch (e: Exception) {
                Log.w(TAG, "BassBoost on Session 0 failed: ${e.message}")
            }
        }

        // 3. Virtualizer on Session 0
        try {
            val virt = Virtualizer(0, 0).apply {
                val strength = DynamicsProcessingTranslator.translateVirtualizer(
                    state.effects[FxSoundEffectId.SURROUND] ?: 3.0f
                )
                setStrength(strength)
                enabled = state.isPowerOn
            }
            session.virtualizer = virt
        } catch (e: Exception) {
            Log.w(TAG, "Virtualizer on Session 0 failed: ${e.message}")
        }

        activeSessions[0] = session
    }

    private fun applyEqualizerBands(eq: Equalizer, eqGains: List<Float>, clarity: Float) {
        val numBands = eq.numberOfBands.toInt()
        val minLevel = eq.bandLevelRange[0] // millibels
        val maxLevel = eq.bandLevelRange[1] // millibels
        val clarityNorm = (clarity / 10.0f).coerceIn(0.0f, 1.0f)

        for (band in 0 until numBands) {
            val centerFreqHz = eq.getCenterFreq(band.toShort()) / 1000 // mHz to Hz
            val userGain = interpolateEqGainForFreq(centerFreqHz, eqGains)
            // The measured upstream clarity response, evaluated at this band centre. The
            // platform Equalizer's bands sit at their own centres, so the table is
            // interpolated in log frequency rather than read band for band.
            val clarityBoost = FxSoundResponseTables.fidelityGainDbAt(
                clarityNorm * 10.0f,
                centerFreqHz.toFloat()
            )
            val totalDb = (userGain + clarityBoost).coerceIn(-14.0f, 14.0f)
            val millibels = (totalDb * 100).toInt().coerceIn(minLevel.toInt(), maxLevel.toInt()).toShort()
            eq.setBandLevel(band.toShort(), millibels)
        }
    }

    private fun interpolateEqGainForFreq(freqHz: Int, eqGains: List<Float>): Float {
        val freqs = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)
        if (freqHz <= freqs.first()) return eqGains.firstOrNull() ?: 0.0f
        if (freqHz >= freqs.last()) return eqGains.lastOrNull() ?: 0.0f
        for (i in 0 until freqs.size - 1) {
            if (freqHz >= freqs[i] && freqHz <= freqs[i + 1]) {
                val f1 = freqs[i].toFloat()
                val f2 = freqs[i + 1].toFloat()
                val t = (freqHz - f1) / (f2 - f1)
                val g1 = eqGains.getOrElse(i) { 0.0f }
                val g2 = eqGains.getOrElse(i + 1) { 0.0f }
                return g1 + t * (g2 - g1)
            }
        }
        return 0.0f
    }

    private fun onSessionOpened(sessionId: Int, packageName: String?) {
        Log.i(TAG, "Audio session opened: id=$sessionId, package=$packageName")

        val state = FxSoundRepository.uiState.value
        val session = ActiveSession(sessionId, packageName)

        try {
            // 1. DynamicsProcessing (Pre-EQ, Multi-band Compressor, Post-EQ, Peak Limiter)
            val config = DynamicsProcessingTranslator.buildDynamicsConfig(
                isPowerOn = state.isPowerOn,
                effects = state.effects,
                eqGains = state.eqGains,
                masterGainDb = state.masterGainDb,
                headphonePlan = state.headphonePlan
            )
            try {
                val dp = DynamicsProcessing(0, sessionId, config).apply {
                    enabled = state.isPowerOn
                }
                session.dynamicsProcessing = dp
                session.preEqBandCount = state.headphonePlan?.bands?.size ?: 10
                if (state.headphonePlan != null) FxSoundRepository.onHeadphoneAttachResult(true)
            } catch (dpEx: Exception) {
                Log.w(TAG, "DynamicsProcessing failed on session $sessionId, falling back to Equalizer: ${dpEx.message}")
                try {
                    val eq = Equalizer(0, sessionId).apply {
                        applyEqualizerBands(this, state.eqGains, state.effects[FxSoundEffectId.FIDELITY] ?: 5.0f)
                        enabled = state.isPowerOn
                    }
                    session.equalizer = eq
                } catch (eqEx: Exception) {
                    Log.w(TAG, "Equalizer also failed on session $sessionId: ${eqEx.message}")
                }
            }

            // 2. BassBoost (Low-end resonance) — only when DynamicsProcessing is absent, so the
            // Pre-EQ HyperBass contour and the standalone boost never run in series.
            val bassStrategy = DynamicsProcessingTranslator.resolveBassStrategy(
                hasDynamicsProcessing = session.dynamicsProcessing != null,
                bassEffectValue = state.effects[FxSoundEffectId.BASS] ?: 4.0f
            )
            session.usesPreEqHyperBass = bassStrategy.usesPreEqHyperBass
            if (bassStrategy.standaloneBassBoostStrength > 0) {
                try {
                    val bass = BassBoost(0, sessionId).apply {
                        setStrength(bassStrategy.standaloneBassBoostStrength)
                        enabled = state.isPowerOn
                    }
                    session.bassBoost = bass
                } catch (e: Exception) {
                    Log.w(TAG, "BassBoost failed on session $sessionId: ${e.message}")
                }
            }

            // 3. Virtualizer (3D Surround soundstage widening)
            try {
                val virt = Virtualizer(0, sessionId).apply {
                    val strength = DynamicsProcessingTranslator.translateVirtualizer(
                        state.effects[FxSoundEffectId.SURROUND] ?: 3.0f
                    )
                    setStrength(strength)
                    enabled = state.isPowerOn
                }
                session.virtualizer = virt
            } catch (e: Exception) {
                Log.w(TAG, "Virtualizer failed on session $sessionId: ${e.message}")
            }

            // 4. PresetReverb is intentionally not attached. It is an auxiliary effect: the
            // source track must create the aux send, which an app cannot do on another app's
            // track, so it is a no-op for external sessions. There is no in-app player
            // anymore (ADR-027), so the Ambience control has no reachable path at all and is
            // not shown in the UI.

            activeSessions[sessionId] = session

            // Determine route status
            val routeStatus = if (packageName?.contains("spotify", ignoreCase = true) == true) {
                RouteStatus.CONNECTED_SPOTIFY
            } else {
                RouteStatus.CONNECTED_PLAYER
            }

            FxSoundRepository.setRouteStatus(routeStatus, packageName, sessionId)
            updateNotification(routeStatus, packageName)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to attach audio effects to session $sessionId: ${e.message}", e)
            FxSoundRepository.setRouteStatus(RouteStatus.ERROR, packageName, sessionId)
            updateNotification(RouteStatus.ERROR, packageName)
        }
    }

    private fun onSessionClosed(sessionId: Int) {
        if (sessionId == 0) return // Preserve global mix session 0
        Log.i(TAG, "Audio session closed: id=$sessionId")
        val session = activeSessions.remove(sessionId)
        session?.let {
            releaseSessionEffects(it)
        }

        val remaining = activeSessions.values.firstOrNull { it.sessionId != 0 }
        if (remaining != null) {
            val status = if (remaining.packageName?.contains("spotify", ignoreCase = true) == true) {
                RouteStatus.CONNECTED_SPOTIFY
            } else {
                RouteStatus.CONNECTED_PLAYER
            }
            FxSoundRepository.setRouteStatus(status, remaining.packageName, remaining.sessionId)
            updateNotification(status, remaining.packageName)
        } else {
            // No supported session left: the plan is no longer attached to any renderer, so
            // it must fall back to PENDING instead of claiming APPLIED forever (B2).
            FxSoundRepository.onHeadphoneAttachResult(false)
            val isPlaying = audioManager?.isMusicActive ?: false
            val fallbackStatus = if (isPlaying) RouteStatus.PARTIAL else RouteStatus.READY
            val fallbackPkg = if (isPlaying) "System Audio" else null
            FxSoundRepository.setRouteStatus(fallbackStatus, fallbackPkg, 0)
            updateNotification(fallbackStatus, fallbackPkg)
        }
    }

    /** Re-creates the DynamicsProcessing effect on a live session with a fresh config. */
    private fun attachDynamicsProcessing(
        session: ActiveSession,
        config: DynamicsProcessing.Config,
        state: FxSoundUiState
    ): Boolean {
        return try {
            session.dynamicsProcessing?.release()
            val dp = DynamicsProcessing(0, session.sessionId, config).apply {
                enabled = state.isPowerOn
            }
            session.dynamicsProcessing = dp
            session.usesPreEqHyperBass = state.headphonePlan == null
            session.preEqBandCount = state.headphonePlan?.bands?.size ?: 10
            Log.i(TAG, "DynamicsProcessing recreated on session ${session.sessionId} (Pre-EQ band count changed).")
            true
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing recreation failed on session ${session.sessionId}: ${e.message}")
            // The previous instance was already released; never leave a released effect in use.
            session.dynamicsProcessing = null
            FxSoundRepository.onHeadphoneAttachResult(false)
            false
        }
    }

    private fun updateAllActiveSessions(state: FxSoundUiState) {
        val config = DynamicsProcessingTranslator.buildDynamicsConfig(
            isPowerOn = state.isPowerOn,
            effects = state.effects,
            eqGains = state.eqGains,
            masterGainDb = state.masterGainDb,
            headphonePlan = state.headphonePlan
        )
        val bassEffectValue = state.effects[FxSoundEffectId.BASS] ?: 4.0f
        val virtStrength = DynamicsProcessingTranslator.translateVirtualizer(
            state.effects[FxSoundEffectId.SURROUND] ?: 3.0f
        )
        val clarity = state.effects[FxSoundEffectId.FIDELITY] ?: 5.0f

        for ((id, session) in activeSessions) {
            // 1. DynamicsProcessing (Pre-EQ, MBC, Post-EQ, Limiter)
            // If the OPRA plan changed the Pre-EQ band count, the live effect must be
            // recreated — stage setters reject a band-count mismatch.
            val planBandCount = state.headphonePlan?.bands?.size ?: 10
            if (state.headphonePlan != null && session.preEqBandCount != planBandCount) {
                val attachOk = attachDynamicsProcessing(session, config, state)
                session.preEqBandCount = if (attachOk) planBandCount else session.preEqBandCount
                continue
            }
            try {
                session.dynamicsProcessing?.apply {
                    try {
                        setInputGainAllChannelsTo(config.getInputGainByChannelIndex(0))
                        setPreEqAllChannelsTo(config.getPreEqByChannelIndex(0))
                        setMbcAllChannelsTo(config.getMbcByChannelIndex(0))
                        setPostEqAllChannelsTo(config.getPostEqByChannelIndex(0))
                        setLimiterAllChannelsTo(config.getLimiterByChannelIndex(0))
                    } catch (stageEx: Exception) {
                        // Fallback to monolithic channel copy if stage setters encounter OEM HAL restrictions
                        setAllChannelsTo(config.getChannelByChannelIndex(0))
                    }
                    enabled = state.isPowerOn
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating DynamicsProcessing on session $id: ${e.message}")
            }

            // 2. Equalizer fallback (active when DynamicsProcessing is unsupported)
            try {
                session.equalizer?.apply {
                    applyEqualizerBands(this, state.eqGains, clarity)
                    enabled = state.isPowerOn
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating Equalizer on session $id: ${e.message}")
            }

            // 3. BassBoost (Low-end resonance)
            // Only sessions without DynamicsProcessing carry a standalone BassBoost, so the
            // Pre-EQ HyperBass contour and this boost are never active at the same time.
            try {
                session.bassBoost?.apply {
                    val strategy = DynamicsProcessingTranslator.resolveBassStrategy(
                        hasDynamicsProcessing = session.dynamicsProcessing != null,
                        bassEffectValue = bassEffectValue,
                        headphonePlanActive = state.headphonePlan != null
                    )
                    setStrength(strategy.standaloneBassBoostStrength)
                    enabled = state.isPowerOn && !strategy.usesPreEqHyperBass
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating BassBoost on session $id: ${e.message}")
            }

            // 4. Virtualizer (3D Surround soundstage widening)
            try {
                session.virtualizer?.apply {
                    setStrength(virtStrength)
                    enabled = state.isPowerOn
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed updating Virtualizer on session $id: ${e.message}")
            }
        }
    }

    private fun releaseSessionEffects(session: ActiveSession) {
        try {
            session.dynamicsProcessing?.enabled = false
            session.dynamicsProcessing?.release()
            session.dynamicsProcessing = null

            session.equalizer?.enabled = false
            session.equalizer?.release()
            session.equalizer = null

            session.bassBoost?.enabled = false
            session.bassBoost?.release()
            session.bassBoost = null

            session.virtualizer?.enabled = false
            session.virtualizer?.release()
            session.virtualizer = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing session effects: ${e.message}")
        }
    }

    private fun releaseAllSessions() {
        for ((_, session) in activeSessions) {
            releaseSessionEffects(session)
        }
        activeSessions.clear()
        // No session can still have the plan attached (B2).
        FxSoundRepository.onHeadphoneAttachResult(false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FxSound Audio Companion",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors and enhances audio playback from Spotify and music players"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(routeStatus: RouteStatus, packageName: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(routeStatus, packageName))
    }

    private fun buildNotification(routeStatus: RouteStatus, packageName: String?): Notification {
        val state = FxSoundRepository.uiState.value
        val launchIntent = (if (!packageName.isNullOrBlank()) packageManager.getLaunchIntentForPackage(packageName) else null)
            ?: Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val powerIntent = Intent(this, SessionCompanionService::class.java).apply {
            action = ACTION_TOGGLE_POWER
        }
        val powerPendingIntent = PendingIntent.getService(
            this, 1, powerIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val powerTitle = if (state.isPowerOn) "Power: ON" else "Power: OFF"
        val powerIcon = if (state.isPowerOn) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        val presetIntent = Intent(this, SessionCompanionService::class.java).apply {
            action = ACTION_CYCLE_PRESET
        }
        val presetPendingIntent = PendingIntent.getService(
            this, 2, presetIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val currentPreset = FxSoundPreset.findById(state.currentPresetId)
        val presetTitle = "Preset: ${currentPreset.name}"

        val title = "FxSound Audio Enhancer"
        val text = when (routeStatus) {
            RouteStatus.OFF -> "Enhanced audio is bypassed"
            RouteStatus.READY -> "Ready • Waiting for Spotify or music playback"
            RouteStatus.CONNECTED_SPOTIFY -> "Active: Enhancing Spotify with FxSound Dynamics"
            RouteStatus.CONNECTED_PLAYER -> "Active: Enhancing ${packageName ?: "Player"}"
            RouteStatus.PLAYING_HIFI -> "Active: Hi-Fi Player (Authentic C++ DfxDsp)"
            RouteStatus.PARTIAL -> "System mix only — this source may bypass FxSound"
            RouteStatus.UNAVAILABLE -> "Audio session unavailable"
            RouteStatus.ERROR -> "Error attaching audio effects"
        }

        val powerAction = Notification.Action.Builder(
            Icon.createWithResource(this, powerIcon),
            powerTitle,
            powerPendingIntent
        ).build()

        val presetAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_rotate),
            presetTitle,
            presetPendingIntent
        ).build()

        val style = Notification.MediaStyle()
            .setShowActionsInCompactView(0, 1)

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        return builder
            .setContentTitle(title)
            .setContentText(text)
            .setSubText(state.outputDevice.displayName)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .addAction(powerAction)
            .addAction(presetAction)
            .setStyle(style)
            .build()
    }

    override fun onDestroy() {
        Log.i(TAG, "SessionCompanionService destroyed.")
        watchdogHandler.removeCallbacks(watchdogRunnable)
        audioDeviceCallback?.let {
            audioManager?.unregisterAudioDeviceCallback(it)
            audioDeviceCallback = null
        }
        FxSoundRepository.onStateChangedListener = null
        releaseAllSessions()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
