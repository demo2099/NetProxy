package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.data.SimpleRouteRule
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.GlassButton
import com.interstellar.proxy.ui.components.GlassButtonStyle
import com.interstellar.proxy.ui.components.IosHairline
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSectionLabel
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

/**
 * 手动分流规则(手机简版): 域名 → 直连 / 代理 / 指定节点。
 * 规则优先级最高,先于大陆绕过等内置规则。
 */
@Composable
fun CustomRulesPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val rules by viewModel.simpleRules.collectAsState()
    var editing by remember { mutableStateOf<SimpleRouteRule?>(null) }
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        RuleEditorSheet(
            initial = editing,
            viewModel = viewModel,
            onDismiss = { showEditor = false },
            onSave = { rule ->
                viewModel.upsertSimpleRule(rule)
                showEditor = false
                editing = null
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                IosSectionLabel("分流规则")
            }
            GlassButton(
                text = "添加",
                style = GlassButtonStyle.Primary,
                onClick = {
                    editing = null
                    showEditor = true
                },
            )
        }

        Spacer(Modifier.height(8.dp))

        if (rules.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "暂无规则 · 点「添加」创建\n例如 bilibili.com → 直连",
                    color = colors.textTertiary,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(rules, key = { it.id }) { rule ->
                    SimpleRuleRow(
                        rule = rule,
                        onToggle = { viewModel.setSimpleRuleEnabled(rule.id, it) },
                        onEdit = {
                            editing = rule
                            showEditor = true
                        },
                        onDelete = { viewModel.removeSimpleRule(rule.id) },
                    )
                    IosHairline()
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }

        IosSectionFooter("后缀匹配:填写 example.com 覆盖其全部子域名;规则先于内置规则生效。")
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SimpleRuleRow(
    rule: SimpleRouteRule,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressableClick(onEdit)
            .padding(vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                rule.domain,
                color = if (rule.enabled) colors.text else colors.textTertiary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                rule.action.label,
                color = when (rule.action) {
                    SimpleRouteRule.Action.DIRECT -> colors.success
                    SimpleRouteRule.Action.PROXY -> colors.textSecondary
                    SimpleRouteRule.Action.NODE -> colors.primary
                },
                fontSize = 12.sp,
            )
        }
        IosSwitch(checked = rule.enabled, onChange = onToggle)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(onClick = onDelete)
                .background(colors.bgDeep),
            contentAlignment = Alignment.Center,
        ) {
            Text("✕", color = colors.textTertiary, fontSize = 13.sp)
        }
    }
}

@Composable
private fun RuleEditorSheet(
    initial: SimpleRouteRule?,
    viewModel: AppViewModel,
    onDismiss: () -> Unit,
    onSave: (SimpleRouteRule) -> Unit,
) {
    val colors = LocalInterstellarColors.current
    var domain by remember { mutableStateOf(initial?.domain ?: "") }
    var actionIndex by remember {
        mutableStateOf(
            when (initial?.action) {
                SimpleRouteRule.Action.DIRECT -> 0
                SimpleRouteRule.Action.NODE -> 2
                else -> 1
            },
        )
    }
    var nodeId by remember { mutableStateOf(initial?.nodeId ?: "") }
    var nodes by remember { mutableStateOf<List<Triple<String, String, String>>>(emptyList()) }
    LaunchedEffect(Unit) { nodes = viewModel.nodePickerEntries() }
    val actions = SimpleRouteRule.Action.entries

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .imePadding(),
    ) {
        Spacer(Modifier.height(10.dp))
        IosSectionLabel(if (initial == null) "添加规则" else "编辑规则")
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = domain,
            onValueChange = { domain = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("example.com", color = colors.textTertiary, fontSize = 13.sp) },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text,
                unfocusedTextColor = colors.text,
                focusedBorderColor = colors.primaryBorder,
                unfocusedBorderColor = colors.border,
                cursorColor = colors.primary,
            ),
        )

        Spacer(Modifier.height(14.dp))

        SegmentedControl(
            items = actions.map { it.label },
            selected = actionIndex,
            onSelect = { actionIndex = it },
            modifier = Modifier.fillMaxWidth(),
            controlHeight = 40.dp,
        )

        if (actions[actionIndex] == SimpleRouteRule.Action.NODE) {
            Spacer(Modifier.height(10.dp))
            if (nodes.isEmpty()) {
                Text("当前节点池为空", color = colors.textTertiary, fontSize = 12.sp)
            } else {
                LazyColumn(modifier = Modifier.height(220.dp)) {
                    items(nodes, key = { it.first }) { (id, tag, _) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .pressableClick { nodeId = id }
                                .padding(vertical = 8.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (nodeId == id) colors.primary else colors.bgDeep,
                                    ),
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                tag,
                                color = colors.text,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                text = "取消",
                style = GlassButtonStyle.Secondary,
                onClick = onDismiss,
                modifier = Modifier.weight(1f),
            )
            GlassButton(
                text = "保存",
                style = GlassButtonStyle.Primary,
                enabled = domain.isNotBlank() &&
                    (actions[actionIndex] != SimpleRouteRule.Action.NODE || nodeId.isNotBlank()),
                onClick = {
                    onSave(
                        SimpleRouteRule(
                            id = initial?.id ?: com.interstellar.proxy.data.SimpleRulesStore.newId(),
                            domain = domain,
                            action = actions[actionIndex],
                            nodeId = nodeId.takeIf { it.isNotBlank() },
                            enabled = initial?.enabled ?: true,
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
