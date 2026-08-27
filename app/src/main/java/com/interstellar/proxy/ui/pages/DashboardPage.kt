package com.interstellar.proxy.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.ConnectionsViewModel
import com.interstellar.proxy.ui.components.FaceMark
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup

@Composable
fun DashboardPage(
    viewModel: AppViewModel,
    connectionsViewModel: ConnectionsViewModel,
    onStart: () -> Unit = { viewModel.startProxy() },
    onOpenSubPage: (SettingsSubPage) -> Unit = {},
) {
    val colors = LocalInterstellarColors.current
    val haptics = LocalHapticFeedback.current
    val status by viewModel.status.collectAsState()
    val speed by viewModel.speed.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val delays by viewModel.delays.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val message by viewModel.message.collectAsState()
    val storedSelected by viewModel.selectedOutboundTag.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val activeSubscriptionId by viewModel.activeSubscriptionId.collectAsState()
    val mixEnabled by viewModel.mixEnabled.collectAsState()
    val mixSubscriptionIds by viewModel.mixSubscriptionIds.collectAsState()
    val connectedAt by viewModel.connectedAt.collectAsState()
    val connections by connectionsViewModel.connections.collectAsState()
    val running = status == Status.Started
    val activeConnectionCount = connections.count { !it.closed }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    val mainGroup = groups.find { it.tag == ConfigBuilder.GROUP_TAG }
    // tag → protocol (VLESS / TROJAN / …) for the current node pool
    val protocolByTag = remember(subscriptions, activeSubscriptionId, mixEnabled, mixSubscriptionIds) {
        val pool = SubscriptionRepository.poolOf(
            subscriptions,
            activeSubscriptionId,
            mixEnabled,
            mixSubscriptionIds,
        )
        ConfigBuilder.tagsFor(pool).zip(pool)
            .associate { (tag, node) -> tag to node.type.wire.uppercase() }
    }

    // 左滑直接进设置页（设置页右滑返回主页，见 SubPageContainer）
    val openSubPage by rememberUpdatedState(onOpenSubPage)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var accum = 0f
                detectHorizontalDragGestures(
                    onDragStart = { accum = 0f },
                    onDragEnd = {
                        if (accum < -70.dp.toPx()) openSubPage(SettingsSubPage.Settings)
                    },
                ) { _, dragAmount -> accum += dragAmount }
            },
    ) {
        val viewportHeight = maxHeight
        val faceSize = 312.dp.coerceAtMost(maxWidth - 32.dp)
        Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = viewportHeight)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 28.dp, bottom = 8.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 104.dp),
                horizontalAlignment = Alignment.Start,
            ) {
                Text(
                    stringResource(R.string.app_name),
                    color = colors.text,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.app_tagline),
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.align(Alignment.TopEnd),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .pressableClick { onOpenSubPage(SettingsSubPage.Connections) },
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.TrendingUp,
                        contentDescription = "监控",
                        tint = colors.text,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .pressableClick { onOpenSubPage(SettingsSubPage.Settings) },
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Settings,
                        contentDescription = "设置",
                        tint = colors.text,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = (viewportHeight - 128.dp).coerceAtLeast(0.dp)),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            FaceButton(
                status = status,
                enabled = !busy,
                faceSize = faceSize,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (running) viewModel.stopProxy() else onStart()
                },
            )

        Spacer(Modifier.height(16.dp))

        // RUNNING / CONNECTING / OFFLINE 小标签
        Text(
            when (status) {
                Status.Started -> "RUNNING"
                Status.Starting -> "CONNECTING"
                Status.Stopping -> "DISCONNECTING"
                Status.Stopped -> "OFFLINE"
            },
            color = when (status) {
                Status.Started -> colors.primary
                Status.Starting, Status.Stopping -> colors.warning
                Status.Stopped -> colors.textTertiary
            },
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 2.sp,
        )
        Spacer(Modifier.height(2.dp))

        val statusWord = when (status) {
            Status.Starting -> "· 连接中"
            Status.Started -> "· 已连接"
            Status.Stopping -> "· 正在断开"
            Status.Stopped -> "· 未连接"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = statusWord,
                transitionSpec = {
                    (slideInVertically(tween(Motion.DURATION_MEDIUM, easing = Motion.Ease)) { it / 3 } + fadeIn()) togetherWith
                        (slideOutVertically(tween(Motion.DURATION_MEDIUM, easing = Motion.Ease)) { -it / 3 } + fadeOut())
                },
                label = "statusText",
            ) { text ->
                Text(
                    text,
                    color = if (running) colors.primary else colors.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (running && connectedAt > 0L) {
                Text(
                    " ${formatElapsed(now - connectedAt)}",
                    color = colors.primary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        // 节点名（大字）：未连接时变成「查看订阅」入口，均可点进节点/订阅页
        val connected = status == Status.Started || status == Status.Starting
        // connected but the groups snapshot has not arrived / resolved to a leaf
        // yet — the core is still picking, so show an animated 选择中 instead of
        // sitting on a bare "自动"
        val resolvedNode = if (connected) nodeRowValue(groups, delays, mainGroup, storedSelected) else null
        val picking = connected && (resolvedNode == "自动" || resolvedNode == "未选择")
        val pickPulse by rememberInfiniteTransition(label = "pickPulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(650, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "pickAlpha",
        )
        Text(
            when {
                picking -> "选择中…"
                connected -> resolvedNode ?: ""
                else -> "查看订阅"
            },
            color = when {
                picking -> colors.textTertiary
                connected -> colors.text
                else -> colors.accent
            },
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .then(if (picking) Modifier.graphicsLayer { alpha = pickPulse } else Modifier)
                .clip(RoundedCornerShape(8.dp))
                .pressableClick { onOpenSubPage(SettingsSubPage.Proxies) }
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )

        if (running && mainGroup != null) {
            val delay = delayOf(groups, delays)
            val delayText = when {
                delay <= 0 -> "测速中"
                delay > 65000 -> "超时"
                else -> "${delay} ms"
            }
            val up = Libbox.formatBytes(speed.uplinkPerSecond)
            val down = Libbox.formatBytes(speed.downlinkPerSecond)
            val total = Libbox.formatBytes(speed.uplinkTotal + speed.downlinkTotal)
            val protocol = currentLeafTag(groups, delays, mainGroup)?.let { protocolByTag[it] }

            // 协议 · 延迟，点击触发测速
            Spacer(Modifier.height(2.dp))
            Text(
                listOfNotNull(protocol, delayText).joinToString(" · "),
                color = colors.textTertiary,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { viewModel.urlTest(ConfigBuilder.GROUP_TAG) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            // 实时速率，大字
            Spacer(Modifier.height(8.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = colors.primary)) { append("↓ ") }
                    append("$down/s")
                    append("    ")
                    withStyle(SpanStyle(color = colors.danger)) { append("↑ ") }
                    append("$up/s")
                },
                color = colors.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // 本次会话累计流量 · 连接数
            Spacer(Modifier.height(4.dp))
            Text(
                "Σ $total · $activeConnectionCount 连接",
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
        } else {
            Spacer(Modifier.height(4.dp))
            Text(
                when (status) {
                    Status.Starting -> "正在建立隧道…"
                    Status.Stopping -> "正在断开…"
                    else -> "轻点表情以连接"
                },
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
        }

        if (message != null) {
            Spacer(Modifier.height(8.dp))
            Text(message!!, color = colors.warning, fontSize = 13.sp)
        }

        Spacer(Modifier.height(24.dp))
        }
        }
    }
}

@Composable
private fun FaceButton(
    status: Status,
    enabled: Boolean,
    faceSize: Dp,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.snappy(),
        label = "faceScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(faceSize)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        FaceMark(status = status, faceSize = faceSize)
    }
}

private fun formatElapsed(ms: Long): String {
    val total = (ms.coerceAtLeast(0L) / 1000L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

private fun nodeRowValue(
    groups: List<OutboundGroup>,
    delays: Map<String, Int>,
    mainGroup: OutboundGroup?,
    storedSelected: String,
): String {
    // live core selection first; when 未连接 fall back to the persisted tag
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() }
        ?: storedSelected.takeIf { it.isNotBlank() }
        ?: return "未选择"
    val leaf = resolveNow(groups, delays, selected)
    return if (leaf == ConfigBuilder.AUTO_TAG || leaf == ConfigBuilder.GROUP_TAG) "自动" else leaf
}

/** Leaf node tag currently in use (null when it stays on a group / auto itself). */
private fun currentLeafTag(
    groups: List<OutboundGroup>,
    delays: Map<String, Int>,
    mainGroup: OutboundGroup?,
): String? {
    val selected = mainGroup?.selected?.takeIf { it.isNotBlank() } ?: return null
    val leaf = resolveNow(groups, delays, selected)
    return leaf.takeIf { it != ConfigBuilder.AUTO_TAG && it != ConfigBuilder.GROUP_TAG }
}

/**
 * Walk selector / urltest until the leaf in use. Urltest's Now() is empty
 * until the first full test finishes — fall back to the current fastest
 * (or first) member so the home row does not sit on "自动" for seconds.
 */
private fun resolveNow(
    groups: List<OutboundGroup>,
    delays: Map<String, Int>,
    tag: String,
    depth: Int = 0,
): String {
    if (depth > 5) return tag
    val group = groups.find { it.tag == tag } ?: return tag
    val next = group.selected
    if (!next.isNullOrBlank() && next != tag) {
        return resolveNow(groups, delays, next, depth + 1)
    }
    val items = groupItems(group)
    if (items.isEmpty()) return tag
    val fastest = items.minByOrNull { delayOfItem(it, delays).takeIf { d -> d > 0 } ?: Int.MAX_VALUE }
    val candidate = when {
        fastest != null && delayOfItem(fastest, delays) > 0 -> fastest.tag
        else -> items.first().tag
    }
    return if (candidate == tag) tag else resolveNow(groups, delays, candidate, depth + 1)
}

private fun groupItems(group: OutboundGroup): List<io.nekohasekai.libbox.OutboundGroupItem> = buildList {
    val it = group.items
    while (it.hasNext()) add(it.next())
}

private fun delayOfItem(item: io.nekohasekai.libbox.OutboundGroupItem, delays: Map<String, Int>): Int =
    delays[item.tag]?.takeIf { it > 0 } ?: item.urlTestDelay

private fun delayOf(groups: List<OutboundGroup>, delays: Map<String, Int>): Int {
    val main = groups.find { it.tag == ConfigBuilder.GROUP_TAG } ?: return 0
    val selected = main.selected ?: return 0
    val leaf = resolveNow(groups, delays, selected)
    delays[leaf]?.let { if (it > 0) return it }
    delays[selected]?.let { if (it > 0) return it }
    return 0
}
