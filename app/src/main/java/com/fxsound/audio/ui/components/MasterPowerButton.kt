package com.fxsound.audio.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import com.fxsound.audio.R
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.fxsound.audio.haptics.HapticsManager
import com.fxsound.audio.ui.theme.FxRedGlow
import com.fxsound.audio.ui.theme.FxRedPrimary

/**
 * Authentic FxSound PC Master Power Button.
 * Recreates Button_ON.svg and Button_OFF.svg from upstream FxSound PC:
 * - When ON: Rich crimson linear gradient (#FF6881 -> #D51535), 2dp stroke (#F4526C -> #780E1E), crisp white icon, ambient red glow
 * - When OFF: Matte dark slate gradient (#383838 -> #232323), subtle outline (#414141 -> #161616), muted icon
 */
@Composable
fun MasterPowerButton(
    isPowerOn: Boolean,
    onPowerToggle: (Boolean) -> Unit,
    hapticsManager: HapticsManager,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    val glowRadius by animateDpAsState(
        targetValue = if (isPowerOn) 24.dp else 0.dp,
        animationSpec = tween(300),
        label = "powerGlowRadius"
    )

    val buttonScale by animateFloatAsState(
        targetValue = if (isPowerOn) 1.0f else 0.96f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "powerButtonScale"
    )

    val iconColor by animateColorAsState(
        targetValue = if (isPowerOn) Color.White else Color(0xFF8E8E8E),
        animationSpec = tween(250),
        label = "powerIconColor"
    )

    val backgroundBrush = if (isPowerOn) {
        Brush.linearGradient(
            colors = listOf(Color(0xFFFF6881), Color(0xFFD51535))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFF383838), Color(0xFF232323))
        )
    }

    val borderBrush = if (isPowerOn) {
        Brush.linearGradient(
            colors = listOf(Color(0xFFF4526C), Color(0xFF780E1E))
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color(0xFF414141), Color(0xFF161616))
        )
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(96.dp)
            .scale(buttonScale)
            .shadow(glowRadius, shape = CircleShape, ambientColor = FxRedGlow, spotColor = FxRedPrimary)
            .clip(CircleShape)
            .background(backgroundBrush)
            .border(2.dp, borderBrush, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                val newState = !isPowerOn
                hapticsManager.performPowerToggle(newState)
                onPowerToggle(newState)
            }
    ) {
        // Inner depth circle
        Box(
            modifier = Modifier
                .size(76.dp)
                .clip(CircleShape)
                .border(
                    1.dp,
                    if (isPowerOn) Color.White.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f),
                    CircleShape
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_power),
                contentDescription = if (isPowerOn) "Bypass FxSound Enhancer" else "Activate FxSound Enhancer",
                tint = iconColor,
                modifier = Modifier.size(38.dp)
            )
        }
    }
}
