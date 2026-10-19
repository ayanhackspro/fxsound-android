package com.fxsound.audio.engine

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.util.Log
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.nativebridge.FxSoundNativeBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.sin

/**
 * In-App Hi-Fi Reference Player Engine.
 *
 * Plays audio directly through the authentic ported FxSound C++ DSP Engine via JNI.
 * Satisfies AGENTS.md requirements:
 * - Native C++ DfxDsp pipeline running the upstream core in 32-bit float, with no
 *   redundant PCM16 round trip on the float path.
 * - Zero allocations in real-time audio loop (preallocated double-buffered spectrum and audio buffers).
 * - Dynamic native hardware sample rate querying via AudioManager.
 * - Comprehensive Audio Focus lifecycle management with call ducking support.
 * - Accurate real-time peak, RMS, and spectrum measurements.
 */
class HiFiPlayerEngine(
    private val context: Context? = null,
    private val nativeBridge: FxSoundNativeBridge = FxSoundNativeBridge()
) {

    companion object {
        private const val TAG = "HiFiPlayerEngine"
        private const val DEFAULT_SAMPLE_RATE = 48000
        private const val CHANNELS = 2
        private const val DEFAULT_BUFFER_FRAMES = 1024
    }

    private var audioTrack: AudioTrack? = null
    private var playbackJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var stateObserverJob: Job? = null

    private var sampleRate: Int = DEFAULT_SAMPLE_RATE
    private var bufferFrames: Int = DEFAULT_BUFFER_FRAMES

    // Preallocated 32-bit float audio buffers (Zero heap allocation in real-time loop)
    private var inFloat = FloatArray(DEFAULT_BUFFER_FRAMES * CHANNELS)
    private var outFloat = FloatArray(DEFAULT_BUFFER_FRAMES * CHANNELS)
    private val metricsBuffer = FloatArray(5)
    private val spectrumBuffer = FloatArray(16)

    private var inPcm16 = ShortArray(DEFAULT_BUFFER_FRAMES * CHANNELS)
    private var outPcm16 = ShortArray(DEFAULT_BUFFER_FRAMES * CHANNELS)
    private var isFloatMode = true

    // Preallocated double-buffer for spectrum telemetry to eliminate array cloning
    private val spectrumBufA = FloatArray(16)
    private val spectrumBufB = FloatArray(16)
    private var useBufA = true

    private var audioFocusRequest: AudioFocusRequest? = null
    private val audioManager: AudioManager? =
        context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    @Volatile
    private var isPlaying = false

    init {
        determineHardwareAudioParameters()
        // Continuously synchronize UI sliders to native C++ DfxDsp during playback
        stateObserverJob = scope.launch {
            FxSoundRepository.uiState.collect { state ->
                if (isPlaying) {
                    syncParametersToNative(state)
                }
            }
        }
    }

    private fun determineHardwareAudioParameters() {
        val am = audioManager
        if (am != null) {
            val hwSampleRateStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
            val hwRate = hwSampleRateStr?.toIntOrNull()
            if (hwRate != null && hwRate > 0) {
                sampleRate = hwRate
            }

            val hwBufferSizeStr = am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)
            val hwFrames = hwBufferSizeStr?.toIntOrNull()
            val isLowLatency = FxSoundRepository.uiState.value.latencyMode == com.fxsound.audio.model.LatencyMode.LOW_LATENCY
            if (isLowLatency) {
                bufferFrames = (hwFrames ?: 256).coerceIn(128, 512)
            } else if (hwFrames != null && hwFrames > 0) {
                bufferFrames = hwFrames.coerceIn(512, 4096)
            }
        }
        Log.i(TAG, "Hardware audio configuration: sampleRate=$sampleRate Hz, bufferFrames=$bufferFrames")

        val totalSamples = bufferFrames * CHANNELS
        inFloat = FloatArray(totalSamples)
        outFloat = FloatArray(totalSamples)
        inPcm16 = ShortArray(totalSamples)
        outPcm16 = ShortArray(totalSamples)
    }

    fun init(): Boolean {
        // The native engine always runs the upstream DfxDsp core in 32-bit float, so the
        // AudioTrack encoding is the only thing that varies between float and 16-bit.
        return nativeBridge.init(sampleRate, CHANNELS)
    }

    /**
     * Starts playback of a reference test signal (warm acoustic multitone)
     * using direct 32-bit floating point AudioTrack and C++ DfxDsp pipeline,
     * with resilient automatic fallback to PCM 16-bit if device HAL does not support float.
     */
    fun startReferencePlayback(trackName: String = "Hi-Fi Reference Multitone") {
        if (isPlaying) return

        try {
            determineHardwareAudioParameters()

            // Request Audio Focus before initiating hardware audio stream
            if (!requestAudioFocus()) {
                Log.w(TAG, "Audio focus not granted; continuing playback request.")
            }

            // Attempt 32-bit Float AudioTrack first
            var createdTrack: AudioTrack? = null
            var useFloat = true

            val floatMinBuf = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_FLOAT
            )

            if (floatMinBuf > 0) {
                try {
                    val trackBufferSize = (bufferFrames * CHANNELS * 4 * 4).coerceAtLeast(floatMinBuf)
                    createdTrack = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                                .build()
                        )
                        .setBufferSizeInBytes(trackBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    useFloat = true
                } catch (e: Exception) {
                    Log.w(TAG, "Float AudioTrack creation failed (${e.message}); falling back to PCM 16-bit.")
                    createdTrack = null
                }
            }

            // Fallback to PCM 16-bit if float AudioTrack is unsupported
            if (createdTrack == null) {
                useFloat = false
                val pcm16MinBuf = AudioTrack.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                val trackBufferSize = (bufferFrames * CHANNELS * 2 * 4).coerceAtLeast(if (pcm16MinBuf > 0) pcm16MinBuf else 4096)
                createdTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .build()
                    )
                    .setBufferSizeInBytes(trackBufferSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            }

            isFloatMode = useFloat
            init()
            syncParametersToNative()

            audioTrack = createdTrack
            audioTrack?.play()
            isPlaying = true

            FxSoundRepository.setRouteStatus(
                status = RouteStatus.PLAYING_HIFI,
                packageName = trackName,
                sessionId = audioTrack?.audioSessionId ?: 0
            )

        playbackJob = scope.launch(Dispatchers.IO) {
            var phase1 = 0.0
            var phase2 = 0.0
            var phase3 = 0.0
            val f1 = 220.0 // A3 warm low-mid
            val f2 = 440.0 // A4 center harmonic
            val f3 = 880.0 // A5 high harmonic

            val twoPi = 2.0 * Math.PI
            val rate = sampleRate.toDouble()

            while (isActive && isPlaying) {
                // Generate test tone samples in preallocated 32-bit float buffer (zero heap allocation)
                for (i in 0 until bufferFrames) {
                    val s1 = sin(phase1) * 0.25
                    val s2 = sin(phase2) * 0.15
                    val s3 = sin(phase3) * 0.10
                    val mixed = (s1 + s2 + s3).coerceIn(-1.0, 1.0).toFloat()

                    inFloat[i * 2] = mixed       // Left channel (32-bit float)
                    inFloat[i * 2 + 1] = mixed   // Right channel (32-bit float)

                    phase1 += twoPi * f1 / rate
                    if (phase1 >= twoPi) phase1 -= twoPi

                    phase2 += twoPi * f2 / rate
                    if (phase2 >= twoPi) phase2 -= twoPi

                    phase3 += twoPi * f3 / rate
                    if (phase3 >= twoPi) phase3 -= twoPi
                }

                // Process through the authentic FxSound C++ DSP Engine
                if (isFloatMode) {
                    nativeBridge.processFloat(inFloat, outFloat, bufferFrames)
                    audioTrack?.write(outFloat, 0, outFloat.size, AudioTrack.WRITE_BLOCKING)
                } else {
                    val totalSamples = bufferFrames * CHANNELS
                    for (s in 0 until totalSamples) {
                        inPcm16[s] = (inFloat[s] * 32767.0f).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    nativeBridge.processPcm16(inPcm16, outPcm16, bufferFrames)
                    audioTrack?.write(outPcm16, 0, outPcm16.size, AudioTrack.WRITE_BLOCKING)
                }

                // Read native metrics and spectrum into preallocated buffers
                nativeBridge.getMetrics(metricsBuffer)
                nativeBridge.getSpectrum(spectrumBuffer, 16)

                // Double-buffered telemetry dispatch to eliminate array allocations in loop
                val targetBuf = if (useBufA) spectrumBufA else spectrumBufB
                useBufA = !useBufA
                System.arraycopy(spectrumBuffer, 0, targetBuf, 0, 16)

                FxSoundRepository.updateMetrics(
                    inputPeak = metricsBuffer[0],
                    outputPeak = metricsBuffer[1],
                    inputRms = metricsBuffer[2],
                    outputRms = metricsBuffer[3],
                    isClipping = metricsBuffer[4] > 0.5f,
                    spectrum = targetBuf
                )
            }
        }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start reference multitone playback: ${e.message}", e)
            stopPlayback()
        }
    }

    private fun requestAudioFocus(): Boolean {
        val am = audioManager ?: return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

            val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Log.i(TAG, "Audio focus lost permanently. Stopping reference playback.")
                        stopPlayback()
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Log.i(TAG, "Audio focus lost transiently. Pausing AudioTrack.")
                        try { audioTrack?.pause() } catch (_: Exception) {}
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.i(TAG, "Audio focus ducking. Attenuating volume.")
                        try { audioTrack?.setVolume(0.2f) } catch (_: Exception) {}
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.i(TAG, "Audio focus regained. Restoring volume.")
                        try {
                            audioTrack?.setVolume(1.0f)
                            if (isPlaying) audioTrack?.play()
                        } catch (_: Exception) {}
                    }
                }
            }

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()

            audioFocusRequest = request
            val res = am.requestAudioFocus(request)
            return res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }
        return true
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                am.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        }
    }

    fun stopPlayback() {
        isPlaying = false
        playbackJob?.cancel()
        playbackJob = null

        try {
            audioTrack?.stop()
            audioTrack?.release()
            audioTrack = null
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping AudioTrack: ${e.message}")
        }

        abandonAudioFocus()

        FxSoundRepository.setRouteStatus(RouteStatus.READY, null, 0)
        FxSoundRepository.updateMetrics(0f, 0f, 0f, 0f, false)
    }

    fun isPlaying(): Boolean = isPlaying

    fun syncParametersToNative(state: com.fxsound.audio.model.FxSoundUiState = FxSoundRepository.uiState.value) {
        nativeBridge.setPower(state.isPowerOn)
        nativeBridge.setCrossfeed(state.isCrossfeedEnabled)
        state.effects.forEach { (effectId, value) ->
            nativeBridge.setEffect(effectId.id, value)
        }
        state.eqGains.forEachIndexed { index, gain ->
            nativeBridge.setEqBand(index, gain)
        }
        nativeBridge.setMasterGain(state.masterGainDb)
    }

    fun release() {
        stopPlayback()
        stateObserverJob?.cancel()
        scope.cancel()
        nativeBridge.release()
    }
}
