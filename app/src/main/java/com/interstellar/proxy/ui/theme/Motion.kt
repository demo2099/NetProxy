package com.interstellar.proxy.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring

/**
 * Motion spec — iOS-style physics:
 * smooth settle springs (no overshoot) for layout, gentle bounce for toggles.
 */
object Motion {
    /** Fast micro-interactions (press states, badges). */
    const val DURATION_FAST = 150

    /** Standard transitions. */
    const val DURATION_MEDIUM = 250

    /** Emphasized transitions (sheets, hero). */
    const val DURATION_SLOW = 350

    /** Page-enter reveal (satelite style: 240ms fade + slight slide). */
    const val DURATION_PAGE = 240
    const val PAGE_ENTER_SLIDE_DP = 6

    /** iOS default ease curve. */
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)

    /** Emphasized decelerate — page enters, glass reveals. */
    val EaseOutQuart = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)

    /** iOS ease-in-out. */
    val EaseInOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

    /** Smooth settle — layout, position, size. */
    fun <T> smooth() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = null,
    )

    /** Snappy settle — small components. */
    fun <T> snappy() = spring<T>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = 500f,
    )

    /** Gentle bounce — toggles, selection feedback. */
    fun <T> bouncy() = spring<T>(
        dampingRatio = 0.72f,
        stiffness = 380f,
    )
}
