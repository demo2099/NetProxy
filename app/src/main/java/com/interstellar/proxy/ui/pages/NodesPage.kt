package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.glassSurface
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

private data class NodeEntry(
    val tag: String,
    val type: String,
    val delay: Int = 0,
    val testedAt: Long = 0,
    val title: String? = null,
    /** Source subscription name in mix mode; null otherwise. */
    val source: String? = null,
) {
    val label: String get() = title ?: tag
}

private fun isGroupItem(item: NodeEntry): Boolean =
    item.tag == ConfigBuilder.AUTO_TAG || item.type == "urltest" || item.type == "selector"

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun NodesPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val groups by viewModel.groups.collectAsState()
    val delays by viewModel.delays.collectAsState()
    val testing by viewModel.testing.collectAsState()
    val message by viewModel.message.collectAsState()
    val splitRules by viewModel.splitRuleStatus.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val activeId by viewModel.activeSubscriptionId.collectAsState()
    val mixEnabled by viewModel.mixEnabled.collectAsState()
    val mixIds by viewModel.mixSubscriptionIds.collectAsState()
    val storedSelected by viewModel.selectedOutboundTag.collectAsState()
    val gridView by viewModel.nodesGridView.collectAsState()
    var sortMode by rememberSaveable { mutableStateOf(0) } // 0 延迟 1 名称
    var detailItem by remember { mutableStateOf<NodeEntry?>(null) }

    val mainGroup = groups.find { it.tag == ConfigBuilder.GROUP_TAG }
    // the pool the generated config runs on: active sub, or the mix union
    val storedNodes = remember(subscriptions, activeId, mixEnabled, mixIds) {
        SubscriptionRepository.poolOf(subscriptions, activeId, mixEnabled, mixIds)
    }
    // mix source labels: tags follow pool order, so segment by subscription
    val sourceByTag = remember(subscriptions, storedNodes, mixEnabled, mixIds) {
        if (!mixEnabled) {
            emptyMap()
        } else {
            val names = buildList {
                subscriptions.filter { it.id in mixIds }.forEach { sub ->
                    repeat(sub.nodes.size) { add(sub.name) }
                }
            }
            ConfigBuilder.tagsFor(storedNodes).zip(names).toMap()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        PageHeader(kicker = "NODES", title = "节点")

        val liveItems = remember(mainGroup) {
            if (mainGroup == null) emptyList()
            else buildList {
                val iterator = mainGroup.items
                while (iterator.hasNext()) add(iterator.next())
            }
        }
        val allItems = remember(liveItems, storedNodes, delays, sourceByTag) {
            if (liveItems.isNotEmpty()) {
                liveItems.map { item ->
                    NodeEntry(
                        tag = item.tag,
                        type = item.type,
                        delay = delays[item.tag] ?: item.urlTestDelay,
                        testedAt = item.urlTestTime,
                        source = sourceByTag[item.tag],
                    )
                }
            } else {
                val tags = ConfigBuilder.tagsFor(storedNodes)
                storedNodes.zip(tags).map { (node, tag) ->
                    NodeEntry(tag, node.type.wire, delays[tag] ?: 0, source = sourceByTag[tag])
                }
            }
        }
        val nodeItems = remember(allItems) { allItems.filterNot(::isGroupItem) }
        val selectedTag = mainGroup?.selected?.takeIf { it.isNotBlank() }
            ?: storedSelected.takeIf { it.isNotBlank() }
            ?: ConfigBuilder.AUTO_TAG

        if (nodeItems.isEmpty()) {
            EmptyHint(
                text = if (mixEnabled) {
                    "Mix 未勾选订阅,或所选订阅中没有可用节点"
                } else if (storedNodes.isEmpty()) {
                    "请先添加并激活一个订阅"
                } else {
                    "订阅中没有可用节点"
                },
            )
            return@Column
        }

        if (mixEnabled) {
            Text(
                "共 ${nodeItems.size} 个节点 · 来自 ${sourceByTag.values.distinct().size} 个订阅",
                color = colors.textTertiary,
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                Text("分流规则启用", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    when {
                        !splitRules.hasEnabledRules -> "未设置规则，可在设置里添加"
                        splitRules.active -> "指定域名走过滤后的节点组"
                        else -> "已关闭，全部走当前节点"
                    },
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                )
            }
            IosSwitch(
                checked = splitRules.masterEnabled,
                onChange = { viewModel.setSplitRulesEnabled(it) },
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SegmentedControl(
                items = listOf("延迟", "名称"),
                selected = sortMode,
                onSelect = { sortMode = it },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (gridView) colors.primaryMuted else colors.panel)
                    .pressableClick { viewModel.setNodesGridView(!gridView) },
            ) {
                Icon(
                    imageVector = if (gridView) Icons.Filled.ViewAgenda else Icons.Filled.GridView,
                    contentDescription = if (gridView) "列表" else "网格",
                    tint = if (gridView) colors.primary else colors.text,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.primaryMuted)
                    .pressableClick { viewModel.urlTest(ConfigBuilder.GROUP_TAG) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                if (testing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val transition = androidx.compose.animation.core.rememberInfiniteTransition(label = "spin")
                        val sweep by transition.animateFloat(
                            initialValue = 0f,
                            targetValue = 360f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                androidx.compose.animation.core.tween(
                                    900,
                                    easing = androidx.compose.animation.core.LinearEasing,
                                ),
                            ),
                            label = "spinA",
                        )
                        androidx.compose.foundation.Canvas(modifier = Modifier.size(14.dp)) {
                            drawArc(
                                color = colors.primary,
                                startAngle = sweep,
                                sweepAngle = 270f,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    2.dp.toPx(),
                                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                                ),
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                        Text("测速中", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                } else {
                    Text("测速", color = colors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        message?.let {
            Text(
                it,
                color = colors.warning,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }

        val sorted = remember(nodeItems, sortMode, delays) {
            when (sortMode) {
                1 -> nodeItems.sortedBy { it.tag.lowercase() }
                else -> nodeItems.sortedBy { item ->
                    val d = delays[item.tag]?.takeIf { it > 0 } ?: item.delay
                    if (d > 0) d else Int.MAX_VALUE
                }
            }
        }
        val autoNow = if (selectedTag == ConfigBuilder.AUTO_TAG) {
            autoNowTag(groups, delays)
        } else {
            null
        }
        val autoCard = NodeEntry(
            tag = ConfigBuilder.AUTO_TAG,
            type = "urltest",
            delay = autoDelay(groups, delays),
            title = "自动",
            // auto mode active → show the node the urltest group is on right now
            source = autoNow,
        )
        val displayed = listOf(autoCard) + sorted

        if (gridView) {
            BoxWithConstraints(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                // adaptive by width, capped at 3 columns per row
                val columns = ((maxWidth / 148.dp).toInt() + 1).coerceIn(1, 3)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                items(displayed, key = { it.tag }) { item ->
                    NodeGridCell(
                        item = item,
                        delay = delays[item.tag] ?: item.delay,
                        selected = item.tag == selectedTag,
                        onClick = { viewModel.selectNode(ConfigBuilder.GROUP_TAG, item.tag) },
                        onLongPress = { if (item.tag != ConfigBuilder.AUTO_TAG) detailItem = item },
                    )
                }
                }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(bottom = 12.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(displayed, key = { it.tag }) { item ->
                    NodeRow(
                        item = item,
                        delay = delays[item.tag] ?: item.delay,
                        selected = item.tag == selectedTag,
                        onClick = { viewModel.selectNode(ConfigBuilder.GROUP_TAG, item.tag) },
                        onLongPress = { if (item.tag != ConfigBuilder.AUTO_TAG) detailItem = item },
                    )
                }
            }
        }
    }

    detailItem?.let { item ->
        NodeDetailSheet(item = item, onDismiss = { detailItem = null })
    }
}

/**
 * The node the auto (urltest) group is on. Now() is empty until the first
 * full test finishes — mirror the home row and preview the fastest known
 * member (or the first one) instead of sitting blank.
 */
private fun autoNowTag(
    groups: List<io.nekohasekai.libbox.OutboundGroup>,
    delays: Map<String, Int>,
): String? {
    val auto = groups.find { it.tag == ConfigBuilder.AUTO_TAG } ?: return null
    val now = auto.selected?.takeIf { it.isNotBlank() && it != ConfigBuilder.AUTO_TAG }
    if (now != null) return now
    val items = buildList {
        val iterator = auto.items
        while (iterator.hasNext()) add(iterator.next())
    }
    if (items.isEmpty()) return null
    return items.minByOrNull { item ->
        val d = delays[item.tag]?.takeIf { it > 0 }
            ?: item.urlTestDelay.takeIf { it > 0 }
        d ?: Int.MAX_VALUE
    }?.tag
}

private fun autoDelay(groups: List<io.nekohasekai.libbox.OutboundGroup>, delays: Map<String, Int>): Int {
    val now = autoNowTag(groups, delays) ?: return 0
    delays[now]?.takeIf { it > 0 }?.let { return it }
    delays[ConfigBuilder.AUTO_TAG]?.takeIf { it > 0 }?.let { return it }
    return 0
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    item: NodeEntry,
    delay: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier
                        .background(colors.primaryMuted)
                        .border(1.dp, colors.primaryBorder, RoundedCornerShape(14.dp))
                } else {
                    Modifier.glassSurface(14.dp, light, colors.panelTop, colors.panelBottom, colors.border)
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) colors.primary else colors.border),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                item.label,
                color = if (selected) colors.text else colors.textSecondary,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.source?.let { source ->
                Spacer(Modifier.width(8.dp))
                Text(
                    source,
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 96.dp),
                )
            }
            DelayBadge(delay)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeGridCell(
    item: NodeEntry,
    delay: Int,
    selected: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(92.dp)
            .clip(RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier
                        .background(colors.primaryMuted)
                        .border(1.dp, colors.primaryBorder, RoundedCornerShape(14.dp))
                } else {
                    Modifier.glassSurface(14.dp, light, colors.panelTop, colors.panelBottom, colors.border)
                },
            )
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
            )
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(end = 4.dp, bottom = 22.dp),
        ) {
            Text(
                item.label,
                color = if (selected) colors.text else colors.textSecondary,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            item.source?.let { source ->
                Spacer(Modifier.height(2.dp))
                Text(
                    source,
                    color = colors.textTertiary,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        DelayBadge(
            delay = delay,
            modifier = Modifier.align(Alignment.BottomEnd),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeDetailSheet(item: NodeEntry, onDismiss: () -> Unit) {
    val colors = LocalInterstellarColors.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = colors.panelSolid,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 20.dp)
                .padding(bottom = 26.dp),
        ) {
            Text("节点信息", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(14.dp))
            DetailRow("名称", item.tag)
            item.source?.let { DetailRow("来源", it) }
            DetailRow("类型", item.type)
            DetailRow("延迟", if (item.delay > 0) "${item.delay} ms" else "未测速")
            DetailRow(
                "最近测速",
                if (item.testedAt > 0) {
                    java.text.DateFormat.getDateTimeInstance()
                        .format(java.util.Date(item.testedAt * 1000))
                } else {
                    "—"
                },
            )
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val colors = LocalInterstellarColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = colors.textTertiary, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = colors.text, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun DelayBadge(delay: Int, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    val (text, color) = when {
        delay <= 0 -> "未测" to colors.textTertiary
        delay < 200 -> "${delay}ms" to colors.success
        delay < 300 -> "${delay}ms" to colors.warning
        else -> "${delay}ms" to colors.danger
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp)
    }
}

@Composable
fun EmptyHint(text: String) {
    val colors = LocalInterstellarColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 60.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Text(text, color = colors.textTertiary, fontSize = 13.sp)
    }
}
