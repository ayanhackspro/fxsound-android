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
import com.fxsound.audio.engine.PlatformEqMapper
import com.fxsound.audio.model.AudioOutputDeviceType
import com.fxsound.audio.model.EqRendererInfo
import com.fxsound.audio.model.EqRendererKind
import com.fxsound.audio.model.FxSoundEffectId
import com.fxsound.audio.model.FxSoundPreset
import com.fxsound.audio.model.FxSoundUiState
import com.fxsound.audio.model.FxStageReport
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.model.StageState
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
        /** What actually attached on this session, for honest UI reporting. */
        var eqStage: StageState = StageState.UNKNOWN,
        var bassStage: StageState = StageState.UNKNOWN,
        var virtualizerStage: StageState = StageState.UNKNOWN,
        var virtualizerStrengthSupported: Boolean = false,
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
        // The global mix is only a fallback for apps whose audio does not route through an
        // explicit session. When one exists, session 0 stays attached but disabled so the
        // same audio can never pass through two chains (ADR-030).
        val onlyGlobalChain = activeSessions.keys.none { it != 0 }

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
                enabled = state.isPowerOn && onlyGlobalChain
            }
            session.dynamicsProcessing = dp
            session.eqStage = StageState.ACTIVE
            session.usesPreEqHyperBass = state.headphonePlan == null
            session.preEqBandCount = state.headphonePlan?.bands?.size ?: 10
            FxSoundRepository.onHeadphoneAttachResult(true)
            Log.i(TAG, "DynamicsProcessing attached to Session 0 successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing on Session 0 unavailable: ${e.message}. Using standard Equalizer fallback.")
            try {
                val eq = Equalizer(0, 0).apply {
                    applyEqualizerBands(this, state.eqGains, state.effects[FxSoundEffectId.FIDELITY] ?: 5.0f)
                    enabled = state.isPowerOn && onlyGlobalChain
                }
                session.equalizer = eq
                session.eqStage = StageState.ACTIVE
                Log.i(TAG, "Standard Equalizer attached to Session 0 successfully.")
            } catch (eqEx: Exception) {
                session.eqStage = StageState.UNSUPPORTED
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
        if (session.dynamicsProcessing == null && state.headphonePlan == null) {
            try {
                val bass = BassBoost(0, 0).apply {
                    setStrength(bassStrategy.standaloneBassBoostStrength)
                    enabled = state.isPowerOn && onlyGlobalChain
                }
                session.bassBoost = bass
                session.bassStage = StageState.ACTIVE
            } catch (e: Exception) {
                session.bassStage = StageState.UNSUPPORTED
                Log.w(TAG, "BassBoost on Session 0 failed: ${e.message}")
            }
        } else {
            session.bassStage = StageState.BYPASSED
        }

        // 3. Virtualizer on Session 0
        session.virtualizer = attachVirtualizer(session, state, state.isPowerOn && onlyGlobalChain)

        activeSessions[0] = session
        reportEqRenderer(session)
        reportStages()
    }

    private fun applyEqualizerBands(eq: Equalizer, eqGains: List<Float>, clarity: Float) {
        val numBands = eq.numberOfBands.toInt()
        val minLevel = eq.bandLevelRange[0] // millibels
        val maxLevel = eq.bandLevelRange[1] // millibels
        val clarityNorm = (clarity / 10.0f).coerceIn(0.0f, 1.0f)

        val gainsDb = FloatArray(numBands)
        for (band in 0 until numBands) {
            val centerFreqHz = eq.getCenterFreq(band.toShort()) / 1000 // mHz to Hz
            val userGain = PlatformEqMapper.gainAt(centerFreqHz, eqGains)
            // The measured upstream clarity response, evaluated at this band centre. The
            // platform Equalizer's bands sit at their own centres, so the table is
            // interpolated in log frequency rather than read band for band.
            val clarityBoost = FxSoundResponseTables.fidelityGainDbAt(
                clarityNorm * 10.0f,
                centerFreqHz.toFloat()
            )
            gainsDb[band] = (userGain + clarityBoost).coerceIn(-14.0f, 14.0f)
        }

        // This path has no preamp or limiter, so a positive boost that survives to the
        // output clips at high volume — the same level-dependence as the DynamicsProcessing
        // chain, but with no headroom control at all. Subtracting the largest boost from
        // every band keeps the relative curve (the audible EQ shape) while preventing the
        // boost from adding peak gain. A flatter, quieter result is honest; a clipping
        // "louder" result is not.
        val peakBoostDb = gainsDb.maxOrNull()?.coerceAtLeast(0.0f) ?: 0.0f
        for (band in 0 until numBands) {
            val levelDb = gainsDb[band] - peakBoostDb
            val millibels = PlatformEqMapper.millibels(levelDb, minLevel, maxLevel)
            eq.setBandLevel(band.toShort(), millibels)
        }
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
                session.eqStage = StageState.ACTIVE
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
                    session.eqStage = StageState.ACTIVE
                } catch (eqEx: Exception) {
                    session.eqStage = StageState.UNSUPPORTED
                    Log.w(TAG, "Equalizer also failed on session $sessionId: ${eqEx.message}")
                }
            }

            // 2. BassBoost (Low-end resonance) — only when DynamicsProcessing is absent, so the
            // Pre-EQ HyperBass contour and the standalone boost never run in series.
            val bassStrategy = DynamicsProcessingTranslator.resolveBassStrategy(
                hasDynamicsProcessing = session.dynamicsProcessing != null,
                bassEffectValue = state.effects[FxSoundEffectId.BASS] ?: 4.0f,
                headphonePlanActive = state.headphonePlan != null
            )
            session.usesPreEqHyperBass = bassStrategy.usesPreEqHyperBass
            if (session.dynamicsProcessing == null && state.headphonePlan == null) {
                try {
                    val bass = BassBoost(0, sessionId).apply {
                        setStrength(bassStrategy.standaloneBassBoostStrength)
                        enabled = state.isPowerOn
                    }
                    session.bassBoost = bass
                    session.bassStage = StageState.ACTIVE
                } catch (e: Exception) {
                    session.bassStage = StageState.UNSUPPORTED
                    Log.w(TAG, "BassBoost failed on session $sessionId: ${e.message}")
                }
            } else {
                session.bassStage = StageState.BYPASSED
            }

            // 3. Virtualizer (3D Surround soundstage widening)
            session.virtualizer = attachVirtualizer(session, state)

            // 4. PresetReverb is intentionally not attached. It is an auxiliary effect: the
            // source track must create the aux send, which an app cannot do on another app's
            // track, so it is a no-op for external sessions. There is no in-app player
            // anymore (ADR-027), so the Ambience control has no reachable path at all and is
            // not shown in the UI.

            activeSessions[sessionId] = session
            reportEqRenderer(session)
            reportStages()
            // Determine route status
            val routeStatus = if (packageName?.contains("spotify", ignoreCase = true) == true) {
                RouteStatus.CONNECTED_SPOTIFY
            } else {
                RouteStatus.CONNECTED_PLAYER
            }

            FxSoundRepository.setRouteStatus(routeStatus, packageName, sessionId)
            updateNotification(routeStatus, packageName)

            // Enforce the single-chain rule now that an explicit session exists: session 0
            // stays attached but is disabled so the audio is not processed twice.
            enforceSingleChain()

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
        // Re-arm the remaining chain: if that was the last explicit session, the global mix
        // becomes the processing session again.
        enforceSingleChain()
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
            session.eqStage = StageState.ACTIVE
            Log.i(TAG, "DynamicsProcessing recreated on session ${session.sessionId} (Pre-EQ band count changed).")
            true
        } catch (e: Exception) {
            Log.w(TAG, "DynamicsProcessing recreation failed on session ${session.sessionId}: ${e.message}")
            // The previous instance was already released; never leave a released effect in use.
            session.dynamicsProcessing = null
            session.eqStage = StageState.FAILED
            FxSoundRepository.onHeadphoneAttachResult(false)
            false
        }
    }

    /**
     * Enforces "exactly one processing chain" without rebuilding any configs.
     *
     * Attaching an explicit session must silence the global mix, and closing the last
     * explicit session must re-arm it. Only the `enabled` flags change here; the configs are
     * already current because every session is updated from the same state snapshot.
     */
    private fun enforceSingleChain() {
        val hasExplicitSessions = activeSessions.keys.any { it != 0 }
        val powerOn = FxSoundRepository.uiState.value.isPowerOn
        for ((id, session) in activeSessions) {
            val chainEnabled = powerOn && (id != 0 || !hasExplicitSessions)
            session.dynamicsProcessing?.enabled = chainEnabled
            session.equalizer?.enabled = chainEnabled
            session.bassBoost?.enabled =
                chainEnabled && !session.usesPreEqHyperBass
            session.virtualizer?.enabled =
                chainEnabled && session.virtualizerStrengthSupported
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

        // Single processing chain (ADR-030): when any explicit app session is live, the
        // global mix stage must be silent. Otherwise the same audio can pass through both
        // chains, doubling every static boost and every limiter.
        val hasExplicitSessions = activeSessions.keys.any { it != 0 }
        if (hasExplicitSessions) {
            activeSessions[0]?.let { global ->
                global.dynamicsProcessing?.enabled = false
                global.equalizer?.enabled = false
                global.bassBoost?.enabled = false
                global.virtualizer?.enabled = false
            }
        }

        for ((id, session) in activeSessions) {
            val processThisSession = id != 0 || !hasExplicitSessions
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
                    enabled = state.isPowerOn && processThisSession
                }
            } catch (e: Exception) {
                session.eqStage = StageState.FAILED
                Log.w(TAG, "Failed updating DynamicsProcessing on session $id: ${e.message}")
            }

            // 2. Equalizer fallback (active when DynamicsProcessing is unsupported)
            try {
                session.equalizer?.apply {
                    applyEqualizerBands(this, state.eqGains, clarity)
                    enabled = state.isPowerOn && processThisSession
                }
            } catch (e: Exception) {
                session.eqStage = StageState.FAILED
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
                    enabled = state.isPowerOn && !strategy.usesPreEqHyperBass && processThisSession
                    session.bassStage = if (strategy.usesPreEqHyperBass ||
                        strategy.standaloneBassBoostStrength == 0.toShort()
                    ) {
                        StageState.BYPASSED
                    } else {
                        StageState.ACTIVE
                    }
                }
            } catch (e: Exception) {
                session.bassStage = StageState.FAILED
                Log.w(TAG, "Failed updating BassBoost on session $id: ${e.message}")
            }

            // 4. Virtualizer (3D Surround soundstage widening)
            try {
                session.virtualizer?.apply {
                    if (session.virtualizerStrengthSupported) {
                        setStrength(virtStrength)
                        enabled = state.isPowerOn && processThisSession
                        session.virtualizerStage = StageState.ACTIVE
                    } else {
                        enabled = false
                        session.virtualizerStage = StageState.FIXED
                    }
                }
            } catch (e: Exception) {
                session.virtualizerStage = StageState.FAILED
                Log.w(TAG, "Failed updating Virtualizer on session $id: ${e.message}")
            }
        }
        reportStages()
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

            session.eqStage = StageState.UNKNOWN
            session.bassStage = StageState.UNKNOWN
            session.virtualizerStage = StageState.UNKNOWN
            session.virtualizerStrengthSupported = false
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
        FxSoundRepository.setEqRenderer(
            EqRendererInfo(
                EqRendererKind.NONE,
                0
            )
        )
        FxSoundRepository.setStageReport(FxStageReport())
    }

    /**
     * Creates the Virtualizer for a session and records what the platform really allows.
     *
     * `strengthSupported` is checked before the control is claimed to work: Virtualizer is
     * an optional effect, and many devices either reject it on a given session or expose a
     * fixed (non-adjustable) strength. In both cases the Surround slider must show why it
     * cannot do anything instead of looking alive.
     */
    private fun attachVirtualizer(
        session: ActiveSession,
        state: FxSoundUiState,
        enabled: Boolean = state.isPowerOn
    ): Virtualizer? {
        val sessionId = session.sessionId
        return try {
            val virt = Virtualizer(0, sessionId)
            val strengthSupported = runCatching { virt.strengthSupported }.getOrDefault(false)
            session.virtualizerStrengthSupported = strengthSupported
            if (strengthSupported) {
                virt.setStrength(
                    DynamicsProcessingTranslator.translateVirtualizer(
                        state.effects[FxSoundEffectId.SURROUND] ?: 3.0f
                    )
                )
            }
            // A fixed-strength virtualizer is left disabled: enabling it would change the
            // sound through a control the user cannot adjust, which is worse than honest off.
            virt.enabled = enabled && strengthSupported
            session.virtualizerStage = if (strengthSupported) StageState.ACTIVE else StageState.FIXED
            Log.i(TAG, "Virtualizer attached to session $sessionId (strengthSupported=$strengthSupported).")
            virt
        } catch (e: Exception) {
            session.virtualizerStage = StageState.UNSUPPORTED
            session.virtualizerStrengthSupported = false
            Log.w(TAG, "Virtualizer failed on session $sessionId: ${e.message}")
            null
        }
    }

    /**
     * Publishes the attach report for the session the console is actually driving: the
     * foreground app's explicit session when there is one, otherwise the global mix.
     */
    private fun reportStages() {
        val session = activeSessions.values.firstOrNull { it.sessionId != 0 } ?: activeSessions[0]
        val report = if (session == null) {
            FxStageReport()
        } else {
            FxStageReport(
                eq = session.eqStage,
                bassBoost = session.bassStage,
                virtualizer = session.virtualizerStage,
                virtualizerStrengthSupported = session.virtualizerStrengthSupported
            )
        }
        FxSoundRepository.setStageReport(report)
    }

    /**
     * Reports what the EQ is really running on. This is diagnostic, not a claim: a slider
     * that "does nothing" can then be traced to either no attached renderer or a platform
     * equalizer with fewer bands than the 10-band console, which is common on OEM builds.
     */
    private fun reportEqRenderer(session: ActiveSession) {
        val info = when {
            session.dynamicsProcessing != null -> EqRendererInfo(
                EqRendererKind.DYNAMICS_PROCESSING,
                10
            )
            session.equalizer != null -> EqRendererInfo(
                EqRendererKind.PLATFORM_EQUALIZER,
                runCatching { session.equalizer!!.numberOfBands.toInt() }.getOrDefault(0)
            )
            else -> EqRendererInfo(
                EqRendererKind.NONE,
                0
            )
        }
        Log.i(TAG, "EQ renderer for session ${session.sessionId}: ${info.kind} (${info.bandCount} bands)")
        FxSoundRepository.setEqRenderer(info)
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
