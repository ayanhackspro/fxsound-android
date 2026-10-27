package com.fxsound.audio.model

/**
 * FxSound Core Effect Identifiers matching upstream DfxDsp and FxSound Engine.
 */
enum class FxSoundEffectId(
    val id: Int,
    val displayName: String,
    val description: String,
    val defaultValue: Float = 5.0f
) {
    FIDELITY(0, "Clarity", "Aural exciter for crisp highs and harmonic definition", 5.0f),
    AMBIENCE(1, "Ambience", "Harmonic stereo space, depth, and reflections", 2.0f),
    SURROUND(2, "Surround", "3D soundstage widening for immersive listening", 3.0f),
    DYNAMIC_BOOST(3, "Dynamic Boost", "Intelligent multi-band maximizer and volume leveler", 4.0f),
    BASS(4, "Bass Boost", "HyperBass low-frequency acoustic depth", 4.0f);

    companion object {
        val CLARITY: FxSoundEffectId get() = FIDELITY
        fun fromId(id: Int): FxSoundEffectId = entries.firstOrNull { it.id == id } ?: FIDELITY
    }
}

/**
 * Represents the active audio route and processing status.
 * In strict compliance with AGENTS.md rule 11:
 * "Treat failure to obtain the requested audio route as a visible state, not as a fake active toggle."
 */
enum class RouteStatus(val label: String, val isProcessing: Boolean) {
    OFF("Power Off", false),
    READY("Ready • Awaiting Audio Session", false),
    CONNECTED_SPOTIFY("Active: Spotify (Dynamics Engine)", true),
    CONNECTED_PLAYER("Active: Music Player (Dynamics Engine)", true),
    PLAYING_HIFI("Active: Hi-Fi Player (Native C++ DfxDsp)", true),

    /**
     * Audio is playing but only the global mix (session 0) is attached, which many source
     * apps bypass. This is deliberately not a processing state: the app cannot know that the
     * playing source is being enhanced.
     */
    PARTIAL("System Mix Only — Source May Bypass FxSound", false),
    UNAVAILABLE("No Supported Route Active", false),
    ERROR("Audio Route Error", false)
}

/**
 * 10-Band Graphic Equalizer standard ISO center frequencies (Hz).
 */
val EQ_FREQUENCIES = listOf(32, 64, 125, 250, 500, 1000, 2000, 4000, 8000, 16000)

/**
 * Preset configuration definition.
 */
data class FxSoundPreset(
    val id: String,
    val name: String,
    val description: String,
    val clarity: Float,
    val ambience: Float,
    val surround: Float,
    val dynamicBoost: Float,
    val bass: Float,
    val eqGains: List<Float>
) {
    companion object {
        val BALANCED = FxSoundPreset(
            id = "balanced",
            name = "Balanced",
            description = "Natural acoustic balance suitable for all genres",
            clarity = 5.0f,
            ambience = 2.0f,
            surround = 3.0f,
            dynamicBoost = 4.0f,
            bass = 4.0f,
            eqGains = listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        )

        val MUSIC = FxSoundPreset(
            id = "music",
            name = "Music",
            description = "Enhanced definition, expansive soundstage, and warm low end",
            clarity = 6.5f,
            ambience = 3.5f,
            surround = 5.0f,
            dynamicBoost = 5.5f,
            bass = 6.0f,
            eqGains = listOf(2.5f, 2.0f, 1.0f, 0.0f, 0.0f, 0.5f, 1.0f, 1.5f, 2.5f, 3.0f)
        )

        val VOICE = FxSoundPreset(
            id = "voice",
            name = "Voice & Podcasts",
            description = "Crisp speech articulation and dialogue presence",
            clarity = 8.0f,
            ambience = 1.0f,
            surround = 1.5f,
            dynamicBoost = 6.0f,
            bass = 2.0f,
            eqGains = listOf(-2.0f, -1.0f, 0.0f, 1.5f, 3.0f, 3.5f, 3.0f, 2.0f, 1.0f, 0.0f)
        )

        val BASS_BOOST = FxSoundPreset(
            id = "bass_boost",
            name = "Bass Boost",
            description = "Deep, punchy low frequencies without muddying vocals",
            clarity = 5.0f,
            ambience = 2.0f,
            surround = 4.0f,
            dynamicBoost = 6.0f,
            bass = 8.5f,
            eqGains = listOf(6.0f, 5.0f, 3.5f, 1.5f, 0.0f, 0.0f, 0.0f, 0.5f, 1.0f, 1.0f)
        )

        val GAMING = FxSoundPreset(
            id = "gaming",
            name = "Gaming",
            description = "Spatial cues, footsteps, and explosive dynamics",
            clarity = 7.0f,
            ambience = 4.5f,
            surround = 7.5f,
            dynamicBoost = 6.5f,
            bass = 5.5f,
            eqGains = listOf(3.0f, 2.0f, 1.0f, 0.0f, 1.0f, 2.0f, 3.0f, 4.0f, 4.5f, 5.0f)
        )

        val ALL_PRESETS = listOf(BALANCED, MUSIC, VOICE, BASS_BOOST, GAMING)

        fun findById(id: String): FxSoundPreset = ALL_PRESETS.firstOrNull { it.id == id } ?: BALANCED
    }
}

/**
 * Fast-updating real-time telemetry (True Peak, RMS, FFT spectrum, clipping flag).
 * Separated from FxSoundUiState to prevent 47 Hz full-screen UI recomposition cascades.
 */
data class AcousticMetrics(
    val inputPeak: Float = 0.0f,
    val outputPeak: Float = 0.0f,
    val inputRms: Float = 0.0f,
    val outputRms: Float = 0.0f,
    val isClipping: Boolean = false,
    val spectrumBands: FloatArray = FloatArray(16) { 0.0f }
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AcousticMetrics
        return inputPeak == other.inputPeak &&
                outputPeak == other.outputPeak &&
                inputRms == other.inputRms &&
                outputRms == other.outputRms &&
                isClipping == other.isClipping &&
                spectrumBands.contentEquals(other.spectrumBands)
    }

    override fun hashCode(): Int {
        var result = inputPeak.hashCode()
        result = 31 * result + outputPeak.hashCode()
        result = 31 * result + inputRms.hashCode()
        result = 31 * result + outputRms.hashCode()
        result = 31 * result + isClipping.hashCode()
        result = 31 * result + spectrumBands.contentHashCode()
        return result
    }
}

/**
 * Represents the active physical or wireless audio output route.
 */
enum class AudioOutputDeviceType(val displayName: String) {
    BUILTIN_SPEAKER("Phone Speaker"),
    WIRED_HEADPHONES("Wired Headphones"),
    BLUETOOTH_A2DP("Bluetooth Audio"),
    USB_HEADSET("USB-C DAC / Headset"),
    UNKNOWN("Audio Device")
}

/**
 * Full immutable UI & Audio engine state snapshot.
 * Contains only user configuration and route state (low-frequency updates).
 */
data class FxSoundUiState(
    val isPowerOn: Boolean = true,
    val routeStatus: RouteStatus = RouteStatus.READY,
    val activePackageName: String? = null,
    val activeAudioSessionId: Int = 0,
    val currentPresetId: String = FxSoundPreset.BALANCED.id,
    val effects: Map<FxSoundEffectId, Float> = mapOf(
        FxSoundEffectId.FIDELITY to 5.0f,
        FxSoundEffectId.AMBIENCE to 2.0f,
        FxSoundEffectId.SURROUND to 3.0f,
        FxSoundEffectId.DYNAMIC_BOOST to 4.0f,
        FxSoundEffectId.BASS to 4.0f
    ),
    val eqGains: List<Float> = List(10) { 0.0f },
    val masterGainDb: Float = 0.0f,
    val hapticsMode: HapticsMode = HapticsMode.SYSTEM_DEFAULT,
    val outputDevice: AudioOutputDeviceType = AudioOutputDeviceType.BUILTIN_SPEAKER,
    val latencyMode: LatencyMode = LatencyMode.STUDIO,
    val isEnhancedTrackingGranted: Boolean = false,
    /** Transient: the OPRA headphone plan for the companion path; restored via its own persistence. */
    val headphonePlan: com.fxsound.audio.opra.HeadphoneEqPlan? = null,
    /** Transient: true only after the companion service confirms the DSP attach. */
    val isHeadphonePlanApplied: Boolean = false
)

/**
 * Attach-state transition for the OPRA headphone plan (B2): APPLIED is only ever true
 * while a renderer has confirmed the attach. A teardown clears it but keeps the plan
 * selected, so the UI falls back to PENDING rather than claiming stale success.
 */
fun FxSoundUiState.withHeadphoneAttachResult(success: Boolean): FxSoundUiState =
    if (isHeadphonePlanApplied == success) this else copy(isHeadphonePlanApplied = success)

enum class LatencyMode {
    STUDIO,       // High-fidelity standard buffer (1024-2048 frames) with maximum DSP resolution
    LOW_LATENCY   // Ultra-fast low latency buffer (256 frames, FAST flag) optimized for mobile gaming
}

enum class HapticsMode {
    SYSTEM_DEFAULT,
    ENHANCED,
    OFF
}

