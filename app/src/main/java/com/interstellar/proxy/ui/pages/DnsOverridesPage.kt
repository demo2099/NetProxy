package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
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
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.model.DnsOverrideEntry
import com.interstellar.proxy.data.model.isValidIpLiteral
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.components.IosCard
import com.interstellar.proxy.ui.components.IosHairline
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSectionLabel
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.iosPressable
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

@Composable
fun DnsOverridesPage(viewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val entries by viewModel.dnsOverrides.collectAsState()
    var editing by remember { mutableStateOf<DnsOverrideEntry?>(null) }
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
            IosSectionLabel("自定义域名解析")
            if (entries.isEmpty()) {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "还没有自定义解析。例如把 example.com 固定到 10.0.0.1，访问时不再走上游 DNS。",
                        color = colors.textTertiary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else {
                IosCard(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        entries.forEachIndexed { index, entry ->
                            DnsOverrideRow(
                                entry = entry,
                                onToggle = { viewModel.setDnsOverrideEnabled(entry.id, it) },
                                onClick = { editing = entry },
                            )
                            if (index != entries.lastIndex) IosHairline(startInset = 16.dp)
                        }
                    }
                }
            }
            IosSectionFooter(
                "匹配为精确域名（同系统 hosts），子域名需单独添加，如 example.com 和 www.example.com。修改后立即重新生成配置，内核运行中自动热重载。",
            )
            Spacer(Modifier.height(20.dp))
        }
    }

    if (creating || editing != null) {
        DnsOverrideEditorSheet(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { entry ->
                viewModel.upsertDnsOverride(entry)
                creating = false
                editing = null
            },
            onDelete = editing?.let { existing ->
                {
                    viewModel.removeDnsOverride(existing.id)
                    creating = false
                    editing = null
                }
            },
        )
    }
}

@Composable
private fun DnsOverrideRow(
    entry: DnsOverrideEntry,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .iosPressable(onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(entry.displayName(), color = colors.text, fontSize = 17.sp)
            Text(
                "${entry.parsedDomains().size} 个域名  ·  ${entry.ip}",
                color = colors.textTertiary,
                fontSize = 13.sp,
            )
        }
        IosSwitch(checked = entry.enabled, onChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsOverrideEditorSheet(
    initial: DnsOverrideEntry?,
    onDismiss: () -> Unit,
    onSave: (DnsOverrideEntry) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val colors = LocalInterstellarColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var domains by remember { mutableStateOf(initial?.domains ?: "") }
    var ip by remember { mutableStateOf(initial?.ip ?: "") }
    val entryId = remember { initial?.id ?: DnsOverridesStore.newId() }

    val draft = DnsOverrideEntry(
        id = entryId,
        enabled = initial?.enabled ?: true,
        domains = domains,
        ip = ip.trim(),
    )
    val domainsOk = draft.parsedDomains().isNotEmpty()
    val ipOk = isValidIpLiteral(ip)
    val canSave = domainsOk && ipOk

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
                if (initial == null) "添加自定义解析" else "编辑自定义解析",
                color = colors.text,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))

            Text("域名", color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Field(
                value = domains,
                onChange = { domains = it },
                placeholder = "example.com（多个用逗号分隔）",
            )
            Spacer(Modifier.height(14.dp))

            Text("IP 地址", color = colors.textTertiary, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Field(
                value = ip,
                onChange = { ip = it },
                placeholder = "1.2.3.4 或 2400:3200::1",
            )

            Spacer(Modifier.height(8.dp))
            val hint = when {
                !domainsOk -> "填写至少一个域名"
                !ipOk -> "IP 需为合法的 IPv4 或 IPv6 地址"
                else -> "共 ${draft.parsedDomains().size} 个域名将固定解析到 ${draft.ip}"
            }
            Text(hint, color = if (domainsOk && !ipOk) colors.warning else colors.textTertiary, fontSize = 12.sp)

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
                    Text("删除解析", color = colors.danger, fontSize = 16.sp)
                }
            }
        }
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
