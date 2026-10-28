package com.fxsound.audio.engine

import android.content.Context
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * Universal Android OEM identification and audio stack compatibility advice.
 * Identifies competing system-level audio post-processing suites (e.g. Dolby Atmos,
 * SoundAlive, Dirac, Moto Audio) that double-process, compress, or color audio streams.
 */
enum class OemBrand(
    val displayName: String,
    val conflictingEffectName: String = "Built-in Sound Enhancements"
) {
    SAMSUNG("Samsung", "Dolby Atmos / SoundAlive"),
    XIAOMI("Xiaomi / POCO / Redmi", "Dolby Atmos / Mi Sound"),
    ONEPLUS_OPPO_REALME("OnePlus / OPPO / Realme", "Dirac / Dolby Atmos"),
    MOTOROLA("Motorola", "Moto Audio / Dolby Atmos"),
    SONY("Sony", "DSEE / 360 Reality Audio"),
    HUAWEI_HONOR("Huawei / Honor", "Histen Sound Effects"),
    GOOGLE("Google Pixel", "Adaptive Sound"),
    GENERIC("Android Device", "OEM Audio Effects")
}

data class OemAudioAdvice(
    val brand: OemBrand,
    val manufacturerName: String,
    val modelName: String,
    val competingSuites: List<String>,
    val recommendation: String,
    val hasPotentialConflict: Boolean
) {
    val advice: String get() = recommendation
    val hasBuiltInEffects: Boolean get() = hasPotentialConflict
    val primaryConflictingEffect: String get() = competingSuites.firstOrNull() ?: "System Sound Suite"
}

object OemAudioCompatibility {
    private const val TAG = "OemAudioCompatibility"

    fun getDeviceAdvice(): OemAudioAdvice = resolveAdvice(
        manufacturer = Build.MANUFACTURER,
        brand = Build.BRAND,
        model = Build.MODEL
    )

    fun resolveAdvice(manufacturer: String, brand: String, model: String): OemAudioAdvice {
        val mfgLower = manufacturer.lowercase()
        val brandLower = brand.lowercase()

        return when {
            mfgLower.contains("samsung") || brandLower.contains("samsung") -> {
                OemAudioAdvice(
                    brand = OemBrand.SAMSUNG,
                    manufacturerName = "Samsung",
                    modelName = model,
                    competingSuites = listOf("Dolby Atmos", "SoundAlive", "Adapt Sound", "UHQ Upscaler"),
                    recommendation = "Disable Dolby Atmos and Adapt Sound in Samsung Settings for clean, uncompressed FxSound output without volume pumping.",
                    hasPotentialConflict = true
                )
            }
            mfgLower.contains("xiaomi") || brandLower.contains("xiaomi") ||
                brandLower.contains("redmi") || brandLower.contains("poco") -> {
                OemAudioAdvice(
                    brand = OemBrand.XIAOMI,
                    manufacturerName = "Xiaomi / POCO",
                    modelName = model,
                    competingSuites = listOf("Dolby Atmos", "Mi Sound Enhancer", "Hi-Fi Audio"),
                    recommendation = "Set Dolby Atmos to Off or Voice in System Sound Settings to avoid dual compression and phase cancellation.",
                    hasPotentialConflict = true
                )
            }
            mfgLower.contains("oneplus") || brandLower.contains("oneplus") ||
                mfgLower.contains("oppo") || brandLower.contains("oppo") ||
                mfgLower.contains("realme") || brandLower.contains("realme") -> {
                OemAudioAdvice(
                    brand = OemBrand.ONEPLUS_OPPO_REALME,
                    manufacturerName = "OnePlus / OPPO / Realme",
                    modelName = model,
                    competingSuites = listOf("Dirac Audio Tuner", "Dolby Atmos", "Real Sound Technology"),
                    recommendation = "Set Dirac or Dolby Atmos to 'None' or 'Music' without auto-scene switching to prevent treble harshness.",
                    hasPotentialConflict = true
                )
            }
            mfgLower.contains("motorola") || brandLower.contains("motorola") || brandLower.contains("moto") -> {
                OemAudioAdvice(
                    brand = OemBrand.MOTOROLA,
                    manufacturerName = "Motorola",
                    modelName = model,
                    competingSuites = listOf("Moto Audio", "Dolby Atmos"),
                    recommendation = "Turn off Moto Audio smart routing to ensure FxSound's 10-band equalizer takes direct effect.",
                    hasPotentialConflict = true
                )
            }
            mfgLower.contains("sony") || brandLower.contains("sony") -> {
                OemAudioAdvice(
                    brand = OemBrand.SONY,
                    manufacturerName = "Sony",
                    modelName = model,
                    competingSuites = listOf("DSEE Ultimate", "360 Reality Audio", "Dolby Sound"),
                    recommendation = "Disable DSEE or Dolby Sound in Sound settings to avoid dual-stage frequency reshaping.",
                    hasPotentialConflict = true
                )
            }
            mfgLower.contains("huawei") || brandLower.contains("huawei") ||
                mfgLower.contains("honor") || brandLower.contains("honor") -> {
                OemAudioAdvice(
                    brand = OemBrand.HUAWEI_HONOR,
                    manufacturerName = "Huawei / Honor",
                    modelName = model,
                    competingSuites = listOf("Histen Sound Effects"),
                    recommendation = "Set Histen to Standard (Normal) to avoid artificial reverberation.",
                    hasPotentialConflict = true
                )
            }
            mfgLower.contains("google") || brandLower.contains("google") -> {
                OemAudioAdvice(
                    brand = OemBrand.GOOGLE,
                    manufacturerName = "Google Pixel",
                    modelName = model,
                    competingSuites = listOf("Adaptive Sound"),
                    recommendation = "AOSP reference audio pipeline active. Adaptive Sound can be used alongside FxSound.",
                    hasPotentialConflict = false
                )
            }
            else -> {
                OemAudioAdvice(
                    brand = OemBrand.GENERIC,
                    manufacturerName = manufacturer.replaceFirstChar { it.uppercase() },
                    modelName = model,
                    competingSuites = listOf("OEM Sound Enhancement"),
                    recommendation = "If your phone has built-in audio enhancements (e.g., Dolby, Dirac, DTS), disabling them provides pure FxSound processing.",
                    hasPotentialConflict = false
                )
            }
        }
    }

    /**
     * Attempts to open the device's native sound or audio effects settings.
     */
    fun openSoundSettings(context: Context): Boolean {
        val intents = listOf(
            Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL),
            Intent(Settings.ACTION_SOUND_SETTINGS),
            Intent("android.settings.SOUND_SETTINGS")
        )

        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed launching intent ${intent.action}: ${e.message}")
            }
        }

        // Final fallback: generic settings
        return try {
            val fallback = Intent(Settings.ACTION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to launch settings: ${e.message}")
            false
        }
    }
}
