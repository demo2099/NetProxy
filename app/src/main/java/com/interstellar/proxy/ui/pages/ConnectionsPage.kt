package com.interstellar.proxy.ui.pages

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.ui.ActiveConnection
import com.interstellar.proxy.ui.AppViewModel
import com.interstellar.proxy.ui.ConnectionsViewModel
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import io.nekohasekai.libbox.Libbox

@Composable
fun ConnectionsPage(viewModel: ConnectionsViewModel, appViewModel: AppViewModel) {
    val colors = LocalInterstellarColors.current
    val connections by viewModel.connections.collectAsState()
    val connected by viewModel.connected.collectAsState()
    val status by appViewModel.status.collectAsState()
    var search by rememberSaveable { mutableStateOf("") }
    val kernelUp = connected || status == Status.Started || status == Status.Starting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        // header stats
        val active = connections.filter { !it.closed }
        val totalUp = active.sumOf { it.uplink }
        val totalDown = active.sumOf { it.downlink }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "活跃 ${active.size}",
                    color = colors.text,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "↑ ${Libbox.formatBytes(totalUp)}  ↓ ${Libbox.formatBytes(totalDown)}",
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                )
            }
            if (active.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.dangerMuted)
                        .pressableClick { viewModel.closeAll() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text("全部断开", color = colors.danger, fontSize = 12.sp)
                }
            }
        }
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("搜索域名 / 规则", color = colors.textTertiary) },
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
        Spacer(Modifier.height(10.dp))

        when {
            !kernelUp -> EmptyHint(text = "内核未运行")
            connections.isEmpty() -> EmptyHint(text = if (connected) "暂无连接" else "正在同步连接…")
            else -> {
                val filtered = connections.filter {
                    search.isBlank() || it.domain.contains(search, true) ||
                        it.rule.contains(search, true)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(filtered, key = { it.id }) { conn ->
                        ConnectionRow(conn) { viewModel.closeConnection(conn.id) }
                    }
                    item { Spacer(Modifier.height(12.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ConnectionRow(conn: ActiveConnection, onClose: () -> Unit) {
    val colors = LocalInterstellarColors.current
    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize(),
        contentPadding = 12.dp,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (conn.closed) colors.textTertiary else colors.primary),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    conn.domain,
                    color = if (conn.closed) colors.textTertiary else colors.text,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(conn.rule)
                        if (conn.chains.isNotEmpty()) append(" → ")
                        conn.chains.take(2).forEachIndexed { i, tag ->
                            if (i > 0) append(" · ")
                            append(tag)
                        }
                    },
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "↑${Libbox.formatBytes(conn.uplink)}",
                    color = colors.textSecondary,
                    fontSize = 10.sp,
                )
                Text(
                    "↓${Libbox.formatBytes(conn.downlink)}",
                    color = colors.primary,
                    fontSize = 10.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "✕",
                color = colors.textTertiary,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .pressableClick { onClose() }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}
