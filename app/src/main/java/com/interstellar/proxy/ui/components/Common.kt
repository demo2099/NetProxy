package com.interstellar.proxy.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

/** Glass panel card, Compose counterpart of interstellar's .card. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(colors.panel)
            .border(1.dp, colors.border, RoundedCornerShape(cornerRadius)),
    ) {
        Column(
            modifier = Modifier.padding(contentPadding),
            content = content,
        )
    }
}

/** Small status dot with glow — mirrors the RUN/OFF indicator. */
@Composable
fun StatusDot(active: Boolean, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    val colors = LocalInterstellarColors.current
    val color = if (active) colors.primary else colors.textTertiary
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(if (active) colors.primaryGlow else color.copy(alpha = 0.2f))
            .padding(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(RoundedCornerShape(50))
                .background(color),
        )
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    Text(
        text = text,
        color = colors.textTertiary,
        style = MaterialTheme.typography.labelMedium,
        modifier = modifier,
    )
}
