package com.interstellar.proxy.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * iOS system design language.
 * One accent (system green — "connected / go"), grouped-list surfaces,
 * label hierarchy via typography, no decoration.
 *
 * Field names kept from the previous interstellar system so existing
 * screens restyle automatically.
 */
@Immutable
data class InterstellarColors(
    val bg: Color,            // grouped background: #F2F2F7 / pure black
    val bgDeep: Color,        // track / chip gray: #E9E9EE / #2C2C2E
    val text: Color,          // label
    val textSecondary: Color, // secondary label #3C3C4399 / #EBEBF599
    val textTertiary: Color,  // tertiary label
    val panel: Color,         // inset group card: white / #1C1C1E
    val panelSolid: Color,
    val border: Color,        // hairline separator
    val primary: Color,       // system green
    val primaryHover: Color,
    val primaryMuted: Color,  // green 12%
    val primaryGlow: Color,
    val primaryBorder: Color,
    val onPrimary: Color,
    val danger: Color,        // system red
    val dangerMuted: Color,
    val warning: Color,       // system orange
    val warningMuted: Color,
    val success: Color,
    /** iOS blue — links & chevrons. */
    val accent: Color,
    /** colored icon squares in settings rows. */
    val iconBlue: Color,
    val iconGreen: Color,
    val iconGray: Color,
    val iconOrange: Color,
    val iconPurple: Color,
    val iconRed: Color,
)

fun interstellarColors(dark: Boolean, @Suppress("UNUSED_PARAMETER") accentId: String?): InterstellarColors {
    return if (dark) {
        val green = Color(0xFF30D158)
        InterstellarColors(
            bg = Color(0xFF000000),
            bgDeep = Color(0xFF2C2C2E),
            text = Color(0xFFFFFFFF),
            textSecondary = Color(0xFFD6D6DE),
            textTertiary = Color(0xFF9E9EA8),
            panel = Color(0xFF1C1C1E),
            panelSolid = Color(0xFF1C1C1E),
            border = Color(0xFF38383A),
            primary = green,
            primaryHover = Color(0xFF4ADE80),
            primaryMuted = green.copy(alpha = 0.14f),
            primaryGlow = green.copy(alpha = 0.22f),
            primaryBorder = green.copy(alpha = 0.35f),
            onPrimary = Color.White,
            danger = Color(0xFFFF453A),
            dangerMuted = Color(0x26FF453A),
            warning = Color(0xFFFFB340),
            warningMuted = Color(0x26FF9F0A),
            success = green,
            accent = Color(0xFF0A84FF),
            iconBlue = Color(0xFF0A84FF),
            iconGreen = Color(0xFF30D158),
            iconGray = Color(0xFF8E8E93),
            iconOrange = Color(0xFFFF9F0A),
            iconPurple = Color(0xFFBF5AF2),
            iconRed = Color(0xFFFF453A),
        )
    } else {
        val green = Color(0xFF34C759)
        InterstellarColors(
            bg = Color(0xFFF2F2F7),
            bgDeep = Color(0xFFE9E9EE),
            text = Color(0xFF000000),
            textSecondary = Color(0xFF44444B),
            textTertiary = Color(0xFF6D6D75),
            panel = Color(0xFFFFFFFF),
            panelSolid = Color(0xFFFFFFFF),
            border = Color(0xFFE5E5EA),
            primary = green,
            primaryHover = Color(0xFF35BA5D),
            primaryMuted = green.copy(alpha = 0.12f),
            primaryGlow = green.copy(alpha = 0.18f),
            primaryBorder = green.copy(alpha = 0.30f),
            onPrimary = Color.White,
            danger = Color(0xFFE5342A),
            dangerMuted = Color(0x1FFF3B30),
            warning = Color(0xFFFF9500),
            warningMuted = Color(0x1FFF9500),
            success = green,
            accent = Color(0xFF007AFF),
            iconBlue = Color(0xFF007AFF),
            iconGreen = Color(0xFF34C759),
            iconGray = Color(0xFF8E8E93),
            iconOrange = Color(0xFFFF9500),
            iconPurple = Color(0xFFAF52DE),
            iconRed = Color(0xFFFF3B30),
        )
    }
}

val LocalInterstellarColors = staticCompositionLocalOf { interstellarColors(dark = true, accentId = null) }

@Composable
fun InterstellarTheme(
    themeMode: String = "system", // system | light | dark (legacy: aerospace/day)
    accentId: String? = null,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        "dark", "aerospace" -> true
        "light", "day" -> false
        else -> isSystemInDarkTheme()
    }
    // system bars follow the theme: dark icons on light, white icons on dark
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        val controller = androidx.core.view.WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
        @Suppress("DEPRECATION")
        runCatching {
            window.statusBarColor = interstellarColors(dark, accentId).bg.toArgb()
            window.navigationBarColor = interstellarColors(dark, accentId).bg.toArgb()
        }
    }

    val target = interstellarColors(dark, accentId)
    val animated = InterstellarColors(
        bg = animateC(target.bg), bgDeep = animateC(target.bgDeep),
        text = animateC(target.text), textSecondary = animateC(target.textSecondary),
        textTertiary = animateC(target.textTertiary),
        panel = animateC(target.panel), panelSolid = animateC(target.panelSolid),
        border = animateC(target.border),
        primary = animateC(target.primary), primaryHover = animateC(target.primaryHover),
        primaryMuted = animateC(target.primaryMuted), primaryGlow = animateC(target.primaryGlow),
        primaryBorder = animateC(target.primaryBorder), onPrimary = animateC(target.onPrimary),
        danger = animateC(target.danger), dangerMuted = animateC(target.dangerMuted),
        warning = animateC(target.warning), warningMuted = animateC(target.warningMuted),
        success = animateC(target.success),
        accent = animateC(target.accent),
        iconBlue = target.iconBlue, iconGreen = target.iconGreen, iconGray = target.iconGray,
        iconOrange = target.iconOrange, iconPurple = target.iconPurple, iconRed = target.iconRed,
    )
    CompositionLocalProvider(
        LocalInterstellarColors provides animated,
        content = content,
    )
}

@Composable
private fun animateC(target: Color) = animateColorAsState(
    targetValue = target,
    animationSpec = androidx.compose.animation.core.tween(Motion.DURATION_MEDIUM, easing = Motion.Ease),
    label = "color",
).value
