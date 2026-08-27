package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.data.NodeMatcher
import com.interstellar.proxy.data.model.CustomRouteRule
import com.interstellar.proxy.data.model.DomainMatchType
import com.interstellar.proxy.data.model.NodeFilterMode
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.IosCard
import com.interstellar.proxy.ui.components.IosHairline
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSectionLabel
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.iosPressable
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

private val PRESET_KEYWORDS = listOf(
    "香港", "台湾", "日本", "新加坡", "美国", "韩国", "英国", "德国",
)

@Composable
fun CustomRulesPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val rules by viewModel.customRules.collectAsState()
    val splitRules by viewModel.splitRuleStatus.collectAsState()
    val subscriptions by viewModel.subscriptions.collectAsState()
    val activeId by viewModel.activeSubscriptionId.collectAsState()
    val nodeNames = remember(subscriptions, activeId) {
        subscriptions.find { it.id == activeId }?.nodes?.map { it.name } ?: emptyList()
    }
    var editing by remember { mutableStateOf<CustomRouteRule?>(null) }
    var creating by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            Text(
                "添加",
                color = colors.accent,
                fontSize = 17.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .iosPressable { creating = true }
                    .padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IosSectionLabel("按域名走指定节点")
            if (rules.any { it.enabled }) {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (splitRules.active) {
                            "当前：分流规则启用中。匹配域名走各自节点组，与自动或手动选节点无关。"
                        } else {
                            "当前：分流规则已关闭。匹配域名也走当前选中的自动/节点。绕过大陆、去广告不受影响。"
                        },
                        color = if (splitRules.active) colors.primary else colors.textSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            if (rules.isEmpty()) {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "还没有分流规则。例如 chatgpt.com 排除香港，或 openai.com 只用新加坡。",
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        rules.forEachIndexed { index, rule ->
                            RuleRow(
                                rule = rule,
                                nodeNames = nodeNames,
                                onToggle = { viewModel.setCustomRuleEnabled(rule.id, it) },
                                onClick = { editing = rule },
                            )
                            if (index != rules.lastIndex) IosHairline(startInset = 16.dp)
                        }
                    }
                }
            }
            IosSectionFooter(
                "在节点页打开「分流规则启用」后，匹配域名始终走过滤后的节点组，不受自动/手动影响。关掉开关则全部走当前选中的节点，方便排查。绕过大陆 / 去广告 / 直连始终有效。",
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (creating || editing != null) {
        RuleEditorSheet(
            initial = editing,
            nodeNames = nodeNames,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { rule ->
                viewModel.upsertCustomRule(rule)
                creating = false
                editing = null
            },
            onDelete = editing?.let { existing ->
                {
                    viewModel.removeCustomRule(existing.id)
                    creating = false
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun RuleRow(
    rule: CustomRouteRule,
    nodeNames: List<String>,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    val matchLabel = when (rule.matchType) {
        DomainMatchType.DOMAIN -> "域名"
        DomainMatchType.DOMAIN_SUFFIX -> "后缀"
        DomainMatchType.DOMAIN_KEYWORD -> "关键字"
    }
    val actionLabel = if (rule.filterMode == NodeFilterMode.INCLUDE) "只用" else "排除"
    val keywords = rule.nodeKeywords.joinToString("、")
    val hit = previewCount(rule, nodeNames)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(rule.displayName(), color = colors.text, fontSize = 17.sp)
            Text(
                "$matchLabel ${rule.matchValue.trim()}  ·  $actionLabel $keywords" +
                    if (nodeNames.isNotEmpty()) "  ·  ${hit} 个节点" else "",
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
        }
        IosSwitch(checked = rule.enabled, onChange = onToggle)
    }
}

private fun previewCount(rule: CustomRouteRule, nodeNames: List<String>): Int {
    val keywords = rule.nodeKeywords.map { it.trim() }.filter { it.isNotEmpty() }
    if (keywords.isEmpty()) return 0
    return NodeMatcher.filterTags(nodeNames, keywords, rule.filterMode == NodeFilterMode.INCLUDE).size
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RuleEditorSheet(
    initial: CustomRouteRule?,
    nodeNames: List<String>,
    onDismiss: () -> Unit,
    onSave: (CustomRouteRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalInterstellarColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var matchType by remember { mutableStateOf(initial?.matchType ?: DomainMatchType.DOMAIN_SUFFIX) }
    var matchValue by remember { mutableStateOf(initial?.matchValue ?: "") }
    var filterMode by remember { mutableStateOf(initial?.filterMode ?: NodeFilterMode.EXCLUDE) }
    var keywords by remember { mutableStateOf(initial?.nodeKeywords ?: emptyList()) }
    var customKeyword by remember { mutableStateOf("") }
    val ruleId = remember { initial?.id ?: com.interstellar.proxy.data.CustomRulesStore.newId() }

    val draft = CustomRouteRule(
        id = ruleId,
        enabled = initial?.enabled ?: true,
        name = name,
        matchType = matchType,
        matchValue = matchValue,
        filterMode = filterMode,
        nodeKeywords = keywords,
    )
    val matchOk = draft.parsedMatchValues().isNotEmpty()
    val keywordOk = keywords.any { it.isNotBlank() }
    val hit = previewCount(draft, nodeNames)
    val canSave = matchOk && keywordOk

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.panelSolid,
        shape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                if (initial == null) "添加分流规则" else "编辑分流规则",
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            Field(
                value = name,
                onChange = { name = it },
                placeholder = "名称（可选，默认用匹配值）",
            )
            Spacer(Modifier.height(14.dp))

            Text("匹配方式", color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                items = listOf("域名", "域名后缀", "关键字"),
                selected = when (matchType) {
                    DomainMatchType.DOMAIN -> 0
                    DomainMatchType.DOMAIN_SUFFIX -> 1
                    DomainMatchType.DOMAIN_KEYWORD -> 2
                },
                onSelect = {
                    matchType = when (it) {
                        0 -> DomainMatchType.DOMAIN
                        1 -> DomainMatchType.DOMAIN_SUFFIX
                        else -> DomainMatchType.DOMAIN_KEYWORD
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Field(
                value = matchValue,
                onChange = { matchValue = it },
                placeholder = when (matchType) {
                    DomainMatchType.DOMAIN -> "chatgpt.com（精确匹配，多个用逗号分隔）"
                    DomainMatchType.DOMAIN_SUFFIX -> "chatgpt.com（含子域名，多个用逗号分隔）"
                    DomainMatchType.DOMAIN_KEYWORD -> "openai（域名包含此关键字）"
                },
            )
            Spacer(Modifier.height(16.dp))

            Text("节点过滤", color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            SegmentedControl(
                items = listOf("排除这些节点", "只用这些节点"),
                selected = if (filterMode == NodeFilterMode.EXCLUDE) 0 else 1,
                onSelect = {
                    filterMode = if (it == 0) NodeFilterMode.EXCLUDE else NodeFilterMode.INCLUDE
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PRESET_KEYWORDS.forEach { kw ->
                    val on = keywords.any { it.equals(kw, true) }
                    KeywordChip(label = kw, selected = on) {
                        keywords = if (on) keywords.filterNot { it.equals(kw, true) } else keywords + kw
                    }
                }
                keywords.filter { preset -> PRESET_KEYWORDS.none { it.equals(preset, true) } }.forEach { kw ->
                    KeywordChip(label = kw, selected = true) {
                        keywords = keywords.filterNot { it == kw }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    Field(
                        value = customKeyword,
                        onChange = { customKeyword = it },
                        placeholder = "自定义关键字，如 IEPL、流媒体",
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "添加",
                    color = colors.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .pressableClick {
                            val kw = customKeyword.trim()
                            if (kw.isNotEmpty() && keywords.none { it.equals(kw, true) }) {
                                keywords = keywords + kw
                            }
                            customKeyword = ""
                        }
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }

            Spacer(Modifier.height(8.dp))
            val hint = when {
                !matchOk -> "填写至少一个域名或关键字"
                !keywordOk -> "选择或添加至少一个节点关键字"
                nodeNames.isEmpty() -> "保存后将按当前订阅生成对应的自动测速组"
                hit == 0 -> "当前订阅没有匹配节点，规则不会生效"
                filterMode == NodeFilterMode.EXCLUDE -> "将从 ${nodeNames.size} 个节点中排除后走 $hit 个节点的自动测速"
                else -> "将只用匹配到的 $hit 个节点自动测速"
            }
            Text(hint, color = if (hit == 0 && matchOk && keywordOk) colors.warning else colors.textTertiary, fontSize = 12.sp)

            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (canSave) colors.primary else colors.bgDeep)
                    .then(if (canSave) Modifier.pressableClick { onSave(draft) } else Modifier)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("保存", color = if (canSave) colors.onPrimary else colors.textTertiary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (onDelete != null) {
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .pressableClick { onDelete() }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("删除规则", color = colors.danger, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun KeywordChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalInterstellarColors.current
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) colors.primaryMuted else colors.bgDeep)
            .pressableClick(onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            color = if (selected) colors.primary else colors.textSecondary,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, placeholder: String) {
    val colors = LocalInterstellarColors.current
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = colors.textTertiary, fontSize = 13.sp) },
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
}
