package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.R
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.ConnectionsViewModel
import com.interstellar.proxy.ui.ProbeState
import com.interstellar.proxy.ui.components.FaceMark
import com.interstellar.proxy.ui.components.GlassButton
import com.interstellar.proxy.ui.components.GlassButtonStyle
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.OrbitHero
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.StatusPill
import com.interstellar.proxy.ui.components.TrafficSparkline
import com.interstellar.proxy.ui.components.glassSurface
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import com.interstellar.proxy.ui.theme.Motion
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup

private const val CORE_VERSION = "sing-box 1.14.0"

@Composable
fun DashboardPage(
    viewModel: AppViewModel,
    connectionsViewModel: ConnectionsViewModel,
    onStart: () -> Unit = { viewModel.startProxy() },
    onOpenSubPage: (SettingsSubPage) -> Unit = {},
    onOpenTab: (MainTab) -> Unit = {},
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
    val history by viewModel.history.collectAsState()
    val clashMode by viewModel.clashMode.collectAsState()
    val probe by viewModel.probe.collectAsState()
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

    // 左右滑动切换 dock tab 的手势由 MainActivity 在 tab 根页统一挂载
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val viewportHeight = maxHeight
        val heroSize = 196.dp.coerceAtMost(maxWidth - 140.dp)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = viewportHeight)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── 顶栏：品牌 + 监控/设置 玻璃圆钮
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 22.dp, bottom = 4.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.app_name),
                        color = colors.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.app_tagline),
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp,
                    )
                }
                GlassIconButton(
                    icon = Icons.AutoMirrored.Outlined.TrendingUp,
                    contentDescription = "监控",
                ) { onOpenSubPage(SettingsSubPage.Connections) }
            }

            Spacer(Modifier.height(8.dp))

            // ── Hero：状态驱动的主视觉，点击连接/断开
            val heroStyle = Settings.heroStyle
            HeroButton(
                status = status,
                enabled = !busy,
                heroSize = heroSize,
                style = heroStyle,
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    if (running) viewModel.stopProxy() else onStart()
                },
            )

            Spacer(Modifier.height(10.dp))

            // ── Kicker：状态胶囊 + 运行时长
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusPill(
                    text = when (status) {
                        Status.Started -> "RUN"
                        Status.Starting -> "CONNECTING"
                        Status.Stopping -> "STOPPING"
                        Status.Stopped -> "OFF"
                    },
                    color = when (status) {
                        Status.Started -> colors.primary
                        Status.Starting, Status.Stopping -> colors.warning
                        Status.Stopped -> colors.textTertiary
                    },
                    active = running || status == Status.Starting,
                )
                if (running && connectedAt > 0L) {
                    Text(
                        formatElapsed(now - connectedAt),
                        color = colors.textTertiary,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // ── 节点名（大字，自动缩放）：点击进入节点页
            val connected = status == Status.Started || status == Status.Starting
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
            val nodeTitle = when {
                picking -> "选择中…"
                connected -> resolvedNode ?: ""
                else -> "查看订阅"
            }
            Text(
                nodeTitle,
                color = when {
                    picking -> colors.textTertiary
                    connected -> colors.text
                    else -> colors.accent
                },
                fontSize = when {
                    nodeTitle.length > 18 -> 18.sp
                    nodeTitle.length > 12 -> 21.sp
                    else -> 25.sp
                },
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .then(if (picking) Modifier.graphicsLayer { alpha = pickPulse } else Modifier)
                    .clip(RoundedCornerShape(8.dp))
                    .pressableClick { onOpenTab(MainTab.Nodes) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )

            // ── 协议 · 延迟（点击测速）
            if (running && mainGroup != null) {
                val delay = delayOf(groups, delays)
                val delayText = when {
                    delay <= 0 -> "测速中"
                    delay > 65000 -> "超时"
                    else -> "${delay} ms"
                }
                val protocol = currentLeafTag(groups, delays, mainGroup)?.let { protocolByTag[it] }
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
            } else {
                Spacer(Modifier.height(2.dp))
                Text(
                    when (status) {
                        Status.Starting -> "正在建立隧道…"
                        Status.Stopping -> "正在断开…"
                        else -> "轻点图标以连接"
                    },
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 快速控制：路由模式 + 自动选择
            val routingMode = if (running) clashMode.current else Settings.outboundMode.name.lowercase()
            val routingIndex = when (routingMode) {
                "global" -> 1
                "direct" -> 2
                else -> 0
            }
            val currentSel = mainGroup?.selected?.takeIf { it.isNotBlank() } ?: storedSelected
            val autoIndex = if (currentSel == ConfigBuilder.AUTO_TAG || currentSel.isBlank()) 1 else 0
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1.15f)) {
                    InstrumentCaption("路由")
                    SegmentedControl(
                        items = listOf("规则", "全局", "直连"),
                        selected = routingIndex,
                        onSelect = { i ->
                            viewModel.setClashMode(listOf("rule", "global", "direct")[i])
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    InstrumentCaption("选择")
                    SegmentedControl(
                        items = listOf("手动", "内核"),
                        selected = autoIndex,
                        onSelect = { i ->
                            if (i == 1) {
                                viewModel.selectNode(ConfigBuilder.GROUP_TAG, ConfigBuilder.AUTO_TAG)
                            } else {
                                pickConcreteNode(
                                    viewModel = viewModel,
                                    groups = groups,
                                    storedAuto = currentSel == ConfigBuilder.AUTO_TAG,
                                    subscriptions = subscriptions,
                                    activeSubscriptionId = activeSubscriptionId,
                                    mixEnabled = mixEnabled,
                                    mixSubscriptionIds = mixSubscriptionIds,
                                    noCandidates = { onOpenTab(MainTab.Nodes) },
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── 主操作：连接/断开 + 切换节点
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                GlassButton(
                    text = when {
                        running -> "断开连接"
                        status == Status.Starting -> "连接中…"
                        else -> "连接"
                    },
                    style = if (running) GlassButtonStyle.Danger else GlassButtonStyle.Primary,
                    enabled = !busy && status != Status.Starting,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        if (running) viewModel.stopProxy() else onStart()
                    },
                    modifier = Modifier.weight(1f),
                )
                GlassButton(
                    text = "切换节点",
                    style = GlassButtonStyle.Secondary,
                    onClick = { onOpenTab(MainTab.Nodes) },
                    modifier = Modifier.weight(1f),
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── 仪表网格：核心 / 流量曲线 / 网络探测 / 订阅
            val down = Libbox.formatBytes(speed.downlinkPerSecond)
            val up = Libbox.formatBytes(speed.uplinkPerSecond)
            val total = Libbox.formatBytes(speed.uplinkTotal + speed.downlinkTotal)

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InstrumentCard(
                    caption = "核心",
                    onClick = { onOpenSubPage(SettingsSubPage.Logs) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        if (running && connectedAt > 0L) formatElapsed(now - connectedAt) else "—",
                        color = colors.text,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "$CORE_VERSION · $activeConnectionCount 连接",
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                InstrumentCard(
                    caption = "流量",
                    onClick = { onOpenSubPage(SettingsSubPage.Connections) },
                    modifier = Modifier.weight(1f),
                ) {
                    TrafficSparkline(
                        history = history,
                        downColor = colors.success,
                        upColor = colors.danger,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(42.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "↓ $down/s  ↑ $up/s",
                        color = colors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                    Text(
                        "Σ $total",
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                InstrumentCard(
                    caption = "出口网络",
                    onClick = { viewModel.probeNetwork() },
                    modifier = Modifier.weight(1f),
                ) {
                    when (val p = probe) {
                        ProbeState.Running -> Text(
                            "探测中…",
                            color = colors.textTertiary,
                            fontSize = 15.sp,
                            fontFamily = FontFamily.Monospace,
                        )

                        is ProbeState.Done -> Column {
                            Text(
                                p.result.ip,
                                color = colors.text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                listOfNotNull(
                                    p.result.country,
                                    "${p.result.latencyMs} ms",
                                    if (p.result.viaProxy) "经代理" else "直连",
                                ).joinToString(" · "),
                                color = colors.textTertiary,
                                fontSize = 11.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }

                        is ProbeState.Failed -> Text(
                            "探测失败 · 点击重试",
                            color = colors.warning,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )

                        ProbeState.Idle -> Text(
                            "点击检测出口 IP",
                            color = colors.textTertiary,
                            fontSize = 13.sp,
                        )
                    }
                }
                InstrumentCard(
                    caption = "订阅",
                    onClick = { onOpenTab(MainTab.Subscriptions) },
                    modifier = Modifier.weight(1f),
                ) {
                    val activeSub = subscriptions.find { it.id == activeSubscriptionId }
                    val quotaSubs = if (mixEnabled) {
                        subscriptions.filter { it.id in mixSubscriptionIds }
                    } else {
                        listOfNotNull(activeSub)
                    }
                    val pool = remember(quotaSubs) { quotaSubs.sumOf { it.nodes.size } }
                    val used = quotaSubs.sumOf { it.uploadBytes + it.downloadBytes }
                    val totalBytes = quotaSubs.sumOf { it.totalBytes }
                    val label = when {
                        mixEnabled && quotaSubs.isNotEmpty() -> "Mix · ${quotaSubs.size} 订阅"
                        activeSub != null -> activeSub.name
                        else -> "未添加"
                    }
                    Text(
                        label,
                        color = colors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    if (totalBytes > 0) {
                        val fraction = (used.toFloat() / totalBytes).coerceIn(0f, 1f)
                        val barColor = when {
                            fraction >= 0.9f -> colors.danger
                            fraction >= 0.7f -> colors.warning
                            else -> colors.primary
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(50))
                                .background(colors.bgDeep),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(5.dp)
                                    .clip(RoundedCornerShape(50))
                                    .background(barColor),
                            )
                        }
                        Spacer(Modifier.height(5.dp))
                    }
                    Text(
                        "$pool 节点" + if (totalBytes > 0) {
                            " · ${Libbox.formatBytes(used)} / ${Libbox.formatBytes(totalBytes)}"
                        } else {
                            ""
                        },
                        color = colors.textTertiary,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (message != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    message!!,
                    color = colors.warning,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/** 玻璃圆角图标按钮（顶栏）。 */
@Composable
private fun GlassIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .glassSurface(50.dp, light, colors.panelTop, colors.panelBottom, colors.border)
            .pressableClick(onClick),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.textSecondary,
            modifier = Modifier.size(19.dp),
        )
    }
}

@Composable
private fun InstrumentCaption(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 6.dp),
    )
}

/** 遥测卡：玻璃卡 + 左上小标签，所有卡统一尺寸。 */
@Composable
private fun InstrumentCard(
    caption: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    GlassCard(
        modifier = modifier
            .height(InstrumentCardHeight)
            .fillMaxWidth(),
        onClick = onClick,
        contentPadding = 12.dp,
    ) {
        Text(
            caption,
            color = colors.textTertiary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.sp,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

/** All four dashboard instruments share one exact height so the grid stays uniform. */
private val InstrumentCardHeight = 132.dp

/** Hero：笑脸或轨道样式，按压缩放。 */
@Composable
private fun HeroButton(
    status: Status,
    enabled: Boolean,
    heroSize: Dp,
    style: String,
    onClick: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = Motion.snappy(),
        label = "heroScale",
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(heroSize)
            .scale(scale)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            ),
    ) {
        if (style == "orbit") {
            OrbitHero(status = status, heroSize = heroSize)
        } else {
            FaceMark(status = status, faceSize = heroSize)
        }
    }
}

/** 切回手动时挑一个具体节点：优先当前解析的叶子，其次池内第一个。 */
private fun pickConcreteNode(
    viewModel: AppViewModel,
    groups: List<OutboundGroup>,
    storedAuto: Boolean,
    subscriptions: List<SubscriptionRepository.Subscription>,
    activeSubscriptionId: String,
    mixEnabled: Boolean,
    mixSubscriptionIds: Set<String>,
    noCandidates: () -> Unit,
) {
    if (!storedAuto) return // 已经是手动
    val mainGroup = groups.find { it.tag == ConfigBuilder.GROUP_TAG }
    val candidate = mainGroup?.let { groupItems(it) }
        ?.firstOrNull { it.tag != ConfigBuilder.AUTO_TAG && it.tag != ConfigBuilder.GROUP_TAG }?.tag
        ?: run {
            val pool = SubscriptionRepository.poolOf(
                subscriptions,
                activeSubscriptionId,
                mixEnabled,
                mixSubscriptionIds,
            )
            ConfigBuilder.tagsFor(pool).firstOrNull { it != ConfigBuilder.AUTO_TAG }
        }
    if (candidate != null) {
        viewModel.selectNode(ConfigBuilder.GROUP_TAG, candidate)
    } else {
        noCandidates()
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
