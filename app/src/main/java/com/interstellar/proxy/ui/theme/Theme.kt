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
 * "Aerospace glass + mission console" design language, ported from
 * satelite-proxy: a deep-space blue base with translucent glass surfaces,
 * one macaron accent re-skinning the whole UI, and semantic colors that
 * never follow the accent (down=green, up=red, warning gold, danger orange).
 *
 * Field names kept from the previous interstellar system so existing
 * screens restyle automatically; new glass tokens extend it.
 */
@Immutable
data class InterstellarColors(
    val bg: Color,            // aerospace #11141C / day #EEF0F4
    val bgDeep: Color,        // segment track / chip well
    val text: Color,          // label
    val textSecondary: Color, // secondary label
    val textTertiary: Color,  // tertiary label
    val panel: Color,         // glass surface base (translucent)
    val panelTop: Color,      // glass fill gradient stop, lit upper-left
    val panelBottom: Color,   // glass fill gradient stop
    val surfaceHigh: Color,   // elevated glass: segment thumb, pressed states
    val panelSolid: Color,    // opaque panel for modals & menus
    val border: Color,        // hairline glass border
    val primary: Color,       // accent (macaron preset)
    val primaryHover: Color,
    val primaryMuted: Color,
    val primaryGlow: Color,
    val primaryBorder: Color,
    val onPrimary: Color,
    val danger: Color,        // fixed orange — stop / upload direction
    val dangerMuted: Color,
    val warning: Color,       // fixed gold
    val warningMuted: Color,
    val success: Color,       // fixed green — direct / download direction
    /** Fixed info blue — links, chevrons, connecting scan. */
    val accent: Color,
    /** colored icon squares in settings rows. */
    val iconBlue: Color,
    val iconGreen: Color,
    val iconGray: Color,
    val iconOrange: Color,
    val iconPurple: Color,
    val iconRed: Color,
)

fun interstellarColors(dark: Boolean, accentId: String?): InterstellarColors {
    val primary = Accents.normalizeForTheme(Accents.current(dark), dark)
    val onPrimary = if (0.2126f * primary.red + 0.7152f * primary.green + 0.0722f * primary.blue >= 0.45f) {
        Color(0xFF0A1210)
    } else {
        Color.White
    }
    return if (dark) {
        InterstellarColors(
            bg = Color(0xFF11141C),
            bgDeep = Color(0xFF1B2130),
            text = Color(0xFFF2F4F8),
            textSecondary = Color(0xFFC3C9D9),
            textTertiary = Color(0xFF8A93A8),
            panel = Color(0x09FFFFFF),
            panelTop = Color(0x17FFFFFF),
            panelBottom = Color(0x08FFFFFF),
            surfaceHigh = Color(0x24FFFFFF),
            panelSolid = Color(0xFF1A1F2C),
            border = Color(0x16FFFFFF),
            primary = primary,
            primaryHover = primary.copy(alpha = 0.82f),
            primaryMuted = primary.copy(alpha = 0.14f),
            primaryGlow = primary.copy(alpha = 0.30f),
            primaryBorder = primary.copy(alpha = 0.35f),
            onPrimary = onPrimary,
            danger = Color(0xFFD68B58),
            dangerMuted = Color(0x2ED68B58),
            warning = Color(0xFFC6A25F),
            warningMuted = Color(0x29C6A25F),
            success = Color(0xFF55C89A),
            accent = Color(0xFF5FA8F5),
            iconBlue = Color(0xFF5FA8F5),
            iconGreen = Color(0xFF55C89A),
            iconGray = Color(0xFF8A93A8),
            iconOrange = Color(0xFFF2B063),
            iconPurple = Color(0xFFB49AF0),
            iconRed = Color(0xFFE8836F),
        )
    } else {
        InterstellarColors(
            bg = Color(0xFFEEF0F4),
            bgDeep = Color(0xFFDFE3EC),
            text = Color(0xFF171B26),
            textSecondary = Color(0xFF3D4454),
            textTertiary = Color(0xFF6E7687),
            panel = Color(0x8CFFFFFF),
            panelTop = Color(0xC7FFFFFF),
            panelBottom = Color(0x66FFFFFF),
            surfaceHigh = Color(0xEBFFFFFF),
            panelSolid = Color(0xFFFBFCFE),
            border = Color(0xA6FFFFFF),
            primary = primary,
            primaryHover = primary.copy(alpha = 0.86f),
            primaryMuted = primary.copy(alpha = 0.12f),
            primaryGlow = primary.copy(alpha = 0.20f),
            primaryBorder = primary.copy(alpha = 0.30f),
            onPrimary = onPrimary,
            danger = Color(0xFFC2552E),
            dangerMuted = Color(0x24C2552E),
            warning = Color(0xFF8F6D2A),
            warningMuted = Color(0x1F8F6D2A),
            success = Color(0xFF1F9A72),
            accent = Color(0xFF2C6FAE),
            iconBlue = Color(0xFF3D7DC8),
            iconGreen = Color(0xFF1F9A72),
            iconGray = Color(0xFF6E7687),
            iconOrange = Color(0xFFC98A3D),
            iconPurple = Color(0xFF7E5CD6),
            iconRed = Color(0xFFC2552E),
        )
    }
}

val LocalInterstellarColors = staticCompositionLocalOf { interstellarColors(dark = true, accentId = null) }

@Composable
fun InterstellarTheme(
    themeMode: String = "system", // system | light | dark
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
            interstellarColors(dark, accentId).bg.toArgb().let {
                window.statusBarColor = it
                window.navigationBarColor = it
            }
        }
    }

    val target = interstellarColors(dark, accentId)
    val animated = InterstellarColors(
        bg = animateC(target.bg), bgDeep = animateC(target.bgDeep),
        text = animateC(target.text), textSecondary = animateC(target.textSecondary),
        textTertiary = animateC(target.textTertiary),
        panel = animateC(target.panel), panelTop = animateC(target.panelTop),
        panelBottom = animateC(target.panelBottom), surfaceHigh = animateC(target.surfaceHigh),
        panelSolid = animateC(target.panelSolid),
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
