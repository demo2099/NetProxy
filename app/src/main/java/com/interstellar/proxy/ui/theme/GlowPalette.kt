package com.interstellar.proxy.ui.theme

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.interstellar.proxy.data.Settings

/**
 * Macaron palette for the homepage face halo. "matcha" mirrors the theme
 * system green (the original fixed glow) and stays the default. The
 * selection is snapshot state so picking a color updates the dashboard
 * instantly without recreating the theme.
 */
object GlowPalette {

    data class Option(val id: String, val label: String, val light: Color, val dark: Color)

    val options = listOf(
        Option("matcha", "抹茶", Color(0xFF34C759), Color(0xFF30D158)),
        Option("peach", "蜜桃", Color(0xFFFF9FB0), Color(0xFFFFAFC2)),
        Option("taro", "香芋", Color(0xFFB8A6F0), Color(0xFFC6B6FF)),
        Option("lake", "湖蓝", Color(0xFF7CC5F2), Color(0xFF8DD3FF)),
        Option("lemon", "柠檬", Color(0xFFFFD84D), Color(0xFFFFE066)),
        Option("coral", "晚霞", Color(0xFFFF9E7A), Color(0xFFFFAB8A)),
    )

    /** Reactive selection, seeded from persisted settings. */
    var selectedId: String by mutableStateOf(Settings.glowColorId)
        private set

    fun select(id: String) {
        selectedId = id
        Settings.glowColorId = id
    }

    /** Resolve the halo color against the current background brightness. */
    fun current(bg: Color, themePrimary: Color): Color {
        val option = options.firstOrNull { it.id == selectedId } ?: return themePrimary
        return preview(option, bg)
    }

    fun preview(option: Option, bg: Color): Color =
        if (isDarkBg(bg)) option.dark else option.light

    private fun isDarkBg(bg: Color): Boolean =
        0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue < 0.5f
}
