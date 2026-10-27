package com.fxsound.audio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.ui.theme.AmberAccent
import com.fxsound.audio.ui.theme.BorderSubtle
import com.fxsound.audio.ui.theme.DarkSurfaceCard
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary
import com.fxsound.audio.ui.theme.TextTertiary

/**
 * Explains, in the UI, exactly which audio is being enhanced and which is not.
 *
 * Android does not expose a public API that lets an ordinary app intercept another app's
 * playback. Only sources that publish an audio session (Spotify, Poweramp and similar music
 * players) can be enhanced, and the global mix is bypassed by browsers, video apps, games and
 * DRM content. AGENTS.md rule 11 requires this limitation to be a visible state rather than a
 * silently faked "Processing" indicator, and docs/UI_UX_SPEC.md requires the app to offer the
 * supported reference route instead of pretending otherwise.
 */
@Composable
fun RouteExplanationBanner(
    routeStatus: RouteStatus,
    modifier: Modifier = Modifier
) {
    val headline = when (routeStatus) {
        RouteStatus.PARTIAL -> "Music players are enhanced — other apps are not"
        else -> "Waiting for a supported audio session"
    }

    val body = when (routeStatus) {
        RouteStatus.PARTIAL ->
            "Audio is playing, but only the system mix is attached and this source may be " +
                "bypassing FxSound. Music players that publish an audio session (Spotify, " +
                "Poweramp) are enhanced. YouTube, browsers, games and DRM video cannot be " +
                "intercepted on Android, by any app, without root."
        else ->
            "FxSound enhances music players that publish an audio session, such as Spotify and " +
                "Poweramp. Browsers, video apps, games and DRM content cannot be intercepted on " +
                "Android, by any app, without root."
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(DarkSurfaceCard)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .padding(top = 5.dp)
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(AmberAccent)
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = headline,
                    color = TextPrimary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = body,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Reference player: Settings → Native C++ DSP Test Harness",
                    color = TextTertiary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
