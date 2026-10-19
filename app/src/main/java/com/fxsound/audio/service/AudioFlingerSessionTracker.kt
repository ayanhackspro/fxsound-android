package com.fxsound.audio.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Rootless AudioFlinger Session Tracker.
 *
 * Implements ADB DUMP / Shizuku enhanced player tracking (Wavelet / Poweramp Equalizer style).
 * Queries `dumpsys media.audio_flinger` to identify active audio sessions created by apps
 * that do not broadcast `ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION` (e.g., YouTube, Chrome, TikTok, games).
 */
object AudioFlingerSessionTracker {

    private const val TAG = "AudioFlingerTracker"

    private val SESSION_REGEX = Regex("""(?i)(?:session(?:\s*id)?|sess)[\s:=]+(\d+)""")
    private val TRACK_LINE_REGEX = Regex("""(?i)\b(\d+)\s+(?:active|yes|playing)\b""")

    /**
     * Checks whether android.permission.DUMP has been granted to this package via ADB or Shizuku.
     */
    fun isDumpPermissionGranted(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.DUMP) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Queries active audio session IDs by parsing `dumpsys media.audio_flinger`.
     * Returns an empty set if DUMP permission is not granted or if the command fails.
     */
    fun queryActiveSessions(): Set<Int> {
        val detectedSessions = mutableSetOf<Int>()

        try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "media.audio_flinger"))
            BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                var line: String? = reader.readLine()
                var inPlaybackSection = false

                while (line != null) {
                    val trimmed = line.trim()

                    if (trimmed.contains("Playback tracks:", ignoreCase = true) ||
                        trimmed.contains("Output thread", ignoreCase = true)) {
                        inPlaybackSection = true
                    } else if (trimmed.contains("Record tracks:", ignoreCase = true) ||
                               trimmed.contains("Input thread", ignoreCase = true)) {
                        inPlaybackSection = false
                    }

                    if (inPlaybackSection) {
                        // Check for standard session ID markers
                        val match = SESSION_REGEX.find(trimmed)
                        if (match != null) {
                            val id = match.groupValues[1].toIntOrNull()
                            if (id != null && id > 0) {
                                detectedSessions.add(id)
                            }
                        } else {
                            // Alternate format parsing for condensed dumpsys tables
                            val trackMatch = TRACK_LINE_REGEX.find(trimmed)
                            if (trackMatch != null) {
                                val id = trackMatch.groupValues[1].toIntOrNull()
                                if (id != null && id > 0) {
                                    detectedSessions.add(id)
                                }
                            }
                        }
                    }

                    line = reader.readLine()
                }
            }
            process.waitFor()
        } catch (e: Exception) {
            Log.d(TAG, "dumpsys media.audio_flinger execution unavailable: ${e.message}")
        }

        return detectedSessions
    }
}
