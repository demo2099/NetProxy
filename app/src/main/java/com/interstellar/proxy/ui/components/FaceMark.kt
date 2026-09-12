package com.interstellar.proxy.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

/**
 * Exact Face ID glyph from `face-id-svgrepo-com.svg` (24×24 filled path).
 * Corners and nose stay as in the SVG. The mouth interpolates frown ↔ smile,
 * and while connected it eases the smile back, blinks, and breathes.
 */
@Composable
fun FaceMark(
    status: Status,
    modifier: Modifier = Modifier,
    faceSize: Dp = 148.dp,
) {
    val colors = LocalInterstellarColors.current
    val scanning = status == Status.Starting
    val connected = status == Status.Started
    val targetMood = when (status) {
        Status.Started -> 1f
        Status.Starting -> 1f
        Status.Stopping -> -0.25f
        Status.Stopped -> -0.9f
    }
    val mood by animateFloatAsState(
        targetValue = targetMood,
        animationSpec = spring(dampingRatio = 0.84f, stiffness = 170f),
        label = "faceMood",
    )
    // accent-driven halo: the macaron preset re-skins the whole UI including this glow
    val glowBase = colors.primary
    val strokeColor by animateColorAsState(
        targetValue = when (status) {
            Status.Started -> glowBase
            Status.Starting -> colors.accent
            else -> colors.text
        },
        animationSpec = tween(360),
        label = "faceColor",
    )
    val glowColor by animateColorAsState(
        targetValue = glowBase,
        animationSpec = tween(360),
        label = "glowColor",
    )
    // idle animations only tick while the face is alive (starting/started/stopping);
    // a stopped face is fully static so the pager swipe never fights per-frame redraws
    val animateIdle = status != Status.Stopped
    val pulse: Float
    val breathe: Float
    val relaxPhase: Float
    val blinkPhase: Float
    if (animateIdle) {
        val idle = rememberInfiniteTransition(label = "faceIdle")
        pulse = idle.animateFloat(
            initialValue = 1f,
            targetValue = 1.045f,
            animationSpec = infiniteRepeatable(
                tween(900, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "pulse",
        ).value
        breathe = idle.animateFloat(
            initialValue = 1f,
            targetValue = 1.018f,
            animationSpec = infiniteRepeatable(
                tween(2400, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "breathe",
        ).value
        relaxPhase = idle.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(6400, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "relaxPhase",
        ).value
        blinkPhase = idle.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(4400, easing = LinearEasing),
                RepeatMode.Restart,
            ),
            label = "blinkPhase",
        ).value
    } else {
        pulse = 1f
        breathe = 1f
        relaxPhase = 0f
        blinkPhase = 1f
    }
    val relaxActive by animateFloatAsState(
        targetValue = if (connected) 1f else 0f,
        animationSpec = tween(420),
        label = "relaxActive",
    )
    val relax = relaxAt(relaxPhase) * relaxActive
    val blink = if (connected) blinkAt(blinkPhase) else 1f
    val liveMood = when {
        scanning -> mood - 0.10f + ((pulse - 1f) / 0.045f) * 0.22f
        else -> mood
    }
    val eyeScaleY = blink
    val frame = remember { PathParser().parsePathString(FRAME_PATH).toPath() }
    val leftEye = remember { PathParser().parsePathString(LEFT_EYE_PATH).toPath() }
    val rightEye = remember { PathParser().parsePathString(RIGHT_EYE_PATH).toPath() }

    Canvas(modifier = modifier.size(faceSize)) {
        val s = this.size.minDimension / 24f
        val dx = (this.size.width - 24f * s) / 2f
        val dy = (this.size.height - 24f * s) / 2f
        // static background glow (from the particle-sphere design): always-on
        // radial halo, independent of status; color from the macaron palette
        // setting in 外观 (default matcha = theme green)
        val glowR = this.size.minDimension * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(glowColor.copy(alpha = 0.35f), Color.Transparent),
                center = center,
                radius = glowR,
            ),
            radius = glowR,
            center = center,
        )
        val faceScale = when {
            scanning -> pulse
            connected -> breathe
            else -> 1f
        }
        scale(faceScale, faceScale, pivot = center) {
            translate(dx, dy) {
                scale(s, s, pivot = Offset.Zero) {
                    drawPath(frame, color = strokeColor)
                    scale(1f, eyeScaleY, pivot = Offset(8.5f, 9f)) {
                        drawPath(leftEye, color = strokeColor)
                    }
                    scale(1f, eyeScaleY, pivot = Offset(16.5f, 9f)) {
                        drawPath(rightEye, color = strokeColor)
                    }
                    drawPath(mouthPath(liveMood, relax), color = strokeColor)
                }
            }
        }
    }
}

/** Rest on the native smile → soften corners → hold → ease back. */
private fun relaxAt(t: Float): Float = when {
    t < 0.22f -> 0f
    t < 0.40f -> smootherstep((t - 0.22f) / 0.18f)
    t < 0.50f -> 1f
    t < 0.76f -> lerp(1f, 0f, smootherstep((t - 0.50f) / 0.26f))
    else -> 0f
}

/** Quick lid close/open around 68% of the blink cycle. */
private fun blinkAt(t: Float): Float {
    val start = 0.68f
    val dur = 0.085f
    val u = (t - start) / dur
    if (u !in 0f..1f) return 1f
    return if (u < 0.42f) {
        lerp(1f, 0.08f, smootherstep(u / 0.42f))
    } else {
        lerp(0.08f, 1f, smootherstep((u - 0.42f) / 0.58f))
    }
}

private fun smootherstep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
}

/** SVG mouth. Relax drops the corners together so stroke width stays 1. */
private fun mouthPath(mood: Float, relax: Float = 0f): Path {
    val cx = 12f
    val halfW = 3.9f
    fun dip(px: Float): Float {
        val u = ((px - cx) / halfW).let { it * it }.coerceAtMost(1f)
        return relax * lerp(-0.04f, 0.36f, u)
    }
    fun y(px: Float, py: Float) = 16.05f + (py - 16.05f) * mood + dip(px)
    return Path().apply {
        moveTo(8.1f, y(8.1f, 15.8f))
        cubicTo(
            7.93431458f, y(7.93431458f, 15.5790861f),
            7.9790861f, y(7.9790861f, 15.2656854f),
            8.2f, y(8.2f, 15.1f),
        )
        cubicTo(
            8.4209139f, y(8.4209139f, 14.9343146f),
            8.73431458f, y(8.73431458f, 14.9790861f),
            8.9f, y(8.9f, 15.2f),
        )
        cubicTo(
            9.81096778f, y(9.81096778f, 16.4146237f),
            10.8353763f, y(10.8353763f, 17f),
            12f, y(12f, 17f),
        )
        cubicTo(
            13.1646237f, y(13.1646237f, 17f),
            14.1890322f, y(14.1890322f, 16.4146237f),
            15.1f, y(15.1f, 15.2f),
        )
        cubicTo(
            15.2656854f, y(15.2656854f, 14.9790861f),
            15.5790861f, y(15.5790861f, 14.9343146f),
            15.8f, y(15.8f, 15.1f),
        )
        cubicTo(
            16.0209139f, y(16.0209139f, 15.2656854f),
            16.0656854f, y(16.0656854f, 15.5790861f),
            15.9f, y(15.9f, 15.8f),
        )
        cubicTo(
            14.8109678f, y(14.8109678f, 17.252043f),
            13.502043f, y(13.502043f, 18f),
            12f, y(12f, 18f),
        )
        cubicTo(
            10.497957f, y(10.497957f, 18f),
            9.18903222f, y(9.18903222f, 17.252043f),
            8.1f, y(8.1f, 15.8f),
        )
        close()
    }
}

/** Corners + nose from face-id-svgrepo-com.svg. */
private const val FRAME_PATH =
    "M7.5,3 C7.77614237,3 8,3.22385763 8,3.5 C8,3.77614237 7.77614237,4 7.5,4 L5.5,4 C4.67157288,4 4,4.67157288 4,5.5 L4,7.53112887 C4,7.80727125 3.77614237,8.03112887 3.5,8.03112887 C3.22385763,8.03112887 3,7.80727125 3,7.53112887 L3,5.5 C3,4.11928813 4.11928813,3 5.5,3 L7.5,3 Z " +
        "M16.5,4 C16.2238576,4 16,3.77614237 16,3.5 C16,3.22385763 16.2238576,3 16.5,3 L18.5,3 C19.8807119,3 21,4.11928813 21,5.5 L21,7.5 C21,7.77614237 20.7761424,8 20.5,8 C20.2238576,8 20,7.77614237 20,7.5 L20,5.5 C20,4.67157288 19.3284271,4 18.5,4 L16.5,4 Z " +
        "M20,16.5 C20,16.2238576 20.2238576,16 20.5,16 C20.7761424,16 21,16.2238576 21,16.5 L21,18.5 C21,19.8807119 19.8807119,21 18.5,21 L16.5,21 C16.2238576,21 16,20.7761424 16,20.5 C16,20.2238576 16.2238576,20 16.5,20 L18.5,20 C19.3284271,20 20,19.3284271 20,18.5 L20,16.5 Z " +
        "M3,16.5 C3,16.2238576 3.22385763,16 3.5,16 C3.77614237,16 4,16.2238576 4,16.5 L4,18.5 C4,19.3284271 4.67157288,20 5.5,20 L7.5,20 C7.77614237,20 8,20.2238576 8,20.5 C8,20.7761424 7.77614237,21 7.5,21 L5.5,21 C4.11928813,21 3,19.8807119 3,18.5 L3,16.5 Z " +
        "M12,8.5 C12,8.22385763 12.2238576,8 12.5,8 C12.7761424,8 13,8.22385763 13,8.5 L13,12.5 C13,13.3284271 12.3284271,14 11.5,14 C11.2238576,14 11,13.7761424 11,13.5 C11,13.2238576 11.2238576,13 11.5,13 C11.7761424,13 12,12.7761424 12,12.5 L12,8.5 Z"

private const val LEFT_EYE_PATH =
    "M8,8.5 C8,8.22385763 8.22385763,8 8.5,8 C8.77614237,8 9,8.22385763 9,8.5 L9,9.5 C9,9.77614237 8.77614237,10 8.5,10 C8.22385763,10 8,9.77614237 8,9.5 L8,8.5 Z"

private const val RIGHT_EYE_PATH =
    "M16,8.5 C16,8.22385763 16.2238576,8 16.5,8 C16.7761424,8 17,8.22385763 17,8.5 L17,9.5 C17,9.77614237 16.7761424,10 16.5,10 C16.2238576,10 16,9.77614237 16,9.5 L16,8.5 Z"
