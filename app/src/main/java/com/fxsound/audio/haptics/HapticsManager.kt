package com.fxsound.audio.haptics

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.fxsound.audio.data.FxSoundRepository
import com.fxsound.audio.model.HapticsMode

/**
 * Manages tactile haptic feedback in strict compliance with docs/HAPTICS_SPEC.md.
 *
 * Requirements:
 * - Rate limited slider bucket ticks (no continuous vibrations).
 * - Master toggle crisp confirmation.
 * - Respects system touch feedback settings.
 * - Supports System Default / Enhanced / Off modes.
 * - Never invoked from the audio callback thread.
 * - Uses modern VibrationEffect.Composition primitives on API 30+ (Xiaomi 17 LRA motor).
 */
class HapticsManager(private val context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private var lastSliderTickTime = 0L
    private var lastSliderBucket = -1
    private val MIN_TICK_INTERVAL_MS = 50L

    private fun isHapticsAllowed(): Boolean {
        val mode = FxSoundRepository.uiState.value.hapticsMode
        if (mode == HapticsMode.OFF || vibrator == null || !vibrator.hasVibrator()) return false
        if (mode == HapticsMode.SYSTEM_DEFAULT && !isSystemHapticsEnabled()) return false
        return true
    }

    private fun isSystemHapticsEnabled(): Boolean {
        return try {
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.HAPTIC_FEEDBACK_ENABLED,
                1
            ) == 1
        } catch (e: Exception) {
            true
        }
    }

    /**
     * Master Power switch toggle confirmation.
     */
    fun performPowerToggle(isOn: Boolean) {
        if (!isHapticsAllowed() || vibrator == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.Composition.PRIMITIVE_TICK)
        ) {
            val scale = if (isOn) 0.85f else 0.55f
            val primitive = if (isOn) VibrationEffect.Composition.PRIMITIVE_CLICK else VibrationEffect.Composition.PRIMITIVE_TICK
            try {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(primitive, scale)
                        .compose()
                )
                return
            } catch (e: Exception) {
                // fallback below
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val effectId = if (isOn) VibrationEffect.EFFECT_CLICK else VibrationEffect.EFFECT_TICK
            try {
                vibrator.vibrate(VibrationEffect.createPredefined(effectId))
                return
            } catch (e: Exception) {
                // fallback below
            }
        }

        vibrateFallback(if (isOn) 18 else 10, if (isOn) 180 else 100)
    }

    /**
     * Slider change tick. Rate-limited and bucket-checked to avoid buzzing during dragging.
     */
    fun performSliderTick(value: Float, min: Float = 0f, max: Float = 10f) {
        if (!isHapticsAllowed() || vibrator == null) return

        val bucket = (value * 2f).toInt() // 0.5 resolution bucket
        val now = SystemClock.uptimeMillis()

        if (bucket != lastSliderBucket && (now - lastSliderTickTime) >= MIN_TICK_INTERVAL_MS) {
            lastSliderBucket = bucket
            lastSliderTickTime = now

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
            ) {
                try {
                    vibrator.vibrate(
                        VibrationEffect.startComposition()
                            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 0.45f)
                            .compose()
                    )
                    return
                } catch (e: Exception) {
                    // fallback below
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK))
                    return
                } catch (e: Exception) {
                    // fallback below
                }
            }

            vibrateFallback(6, 60)
        }
    }

    /**
     * Preset selection tick.
     */
    fun performPresetSelected() {
        if (!isHapticsAllowed() || vibrator == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_CLICK)
        ) {
            try {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.65f)
                        .compose()
                )
                return
            } catch (e: Exception) {
                // fallback below
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                return
            } catch (e: Exception) {
                // fallback below
            }
        }

        vibrateFallback(12, 100)
    }

    /**
     * Reset confirmation.
     */
    fun performResetConfirmation() {
        if (!isHapticsAllowed() || vibrator == null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            vibrator.areAllPrimitivesSupported(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE)
        ) {
            try {
                vibrator.vibrate(
                    VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.7f)
                        .compose()
                )
                return
            } catch (e: Exception) {
                // fallback below
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK))
                return
            } catch (e: Exception) {
                // fallback below
            }
        }

        vibrateFallback(25, 200)
    }

    private fun vibrateFallback(durationMs: Long, amplitude: Int) {
        if (vibrator == null || !vibrator.hasVibrator()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(durationMs, amplitude.coerceIn(1, 255))
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(durationMs)
        }
    }
}
