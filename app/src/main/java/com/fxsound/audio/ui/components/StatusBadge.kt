package com.fxsound.audio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fxsound.audio.model.RouteStatus
import com.fxsound.audio.ui.theme.AmberAccent
import com.fxsound.audio.ui.theme.DangerRed
import com.fxsound.audio.ui.theme.DarkSurfaceElevated
import com.fxsound.audio.ui.theme.FxRedPrimary
import com.fxsound.audio.ui.theme.TextPrimary
import com.fxsound.audio.ui.theme.TextSecondary

/**
 * Honest Audio Route Status Badge.
 * In compliance with AGENTS.md rule 11:
 * "Treat failure to obtain the requested audio route as a visible state, not as a fake active toggle."
 */
@Composable
fun StatusBadge(
    status: RouteStatus,
    activePackage: String?,
    modifier: Modifier = Modifier
) {
    val indicatorColor by animateColorAsState(
        targetValue = when (status) {
            RouteStatus.CONNECTED_SPOTIFY,
            RouteStatus.CONNECTED_PLAYER,
            RouteStatus.PLAYING_HIFI -> FxRedPrimary
            RouteStatus.READY,
            RouteStatus.PARTIAL -> AmberAccent
            RouteStatus.OFF,
            RouteStatus.UNAVAILABLE -> TextSecondary
            RouteStatus.ERROR -> DangerRed
        },
        label = "statusIndicatorColor"
    )

    val labelText = when (status) {
        RouteStatus.CONNECTED_SPOTIFY -> "Connected: Spotify"
        RouteStatus.CONNECTED_PLAYER -> "Connected: ${activePackage ?: "Music Player"}"
        RouteStatus.PLAYING_HIFI -> "Playing: Hi-Fi DfxDsp"
        RouteStatus.READY -> "Ready • Awaiting Audio Session"
        RouteStatus.PARTIAL -> "System Mix Only"
        RouteStatus.OFF -> "FxSound Bypassed"
        RouteStatus.UNAVAILABLE -> "No Compatible Route"
        RouteStatus.ERROR -> "Route Error"
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(DarkSurfaceElevated)
            .border(1.dp, indicatorColor.copy(alpha = 0.35f), RoundedCornerShape(20.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(indicatorColor)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = labelText,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
        }
    }
}
