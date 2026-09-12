package com.interstellar.proxy.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

/**
 * Ambient accent wash behind the whole app (satelite's `--glow-deep`):
 * one large radial gradient above center plus a faint second one near
 * the bottom, both driven by the accent so they re-skin with the theme.
 */
@Composable
fun AmbientGlow(modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    val glow = colors.primary
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                // primary wash above center
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.16f), Color.Transparent),
                        center = Offset(size.width * 0.5f, -size.height * 0.10f),
                        radius = size.maxDimension * 1.05f,
                    ),
                    radius = size.maxDimension * 1.05f,
                    center = Offset(size.width * 0.5f, -size.height * 0.10f),
                )
                // faint counterweight near the bottom
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(glow.copy(alpha = 0.06f), Color.Transparent),
                        center = Offset(size.width * 0.82f, size.height * 1.02f),
                        radius = size.minDimension * 0.85f,
                    ),
                    radius = size.minDimension * 0.85f,
                    center = Offset(size.width * 0.82f, size.height * 1.02f),
                )
            },
    )
}
