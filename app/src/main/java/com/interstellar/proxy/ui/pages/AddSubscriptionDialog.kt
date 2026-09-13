package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.window.Dialog
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

@Composable
fun AddSubscriptionDialog(
    onDismiss: () -> Unit,
    onAddUrl: (name: String, url: String) -> Unit,
    onAddText: (name: String, text: String) -> Unit,
    loading: Boolean = false,
    onCancel: () -> Unit = {},
    error: String? = null,
) {
    val colors = LocalInterstellarColors.current
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("url") } // url | text

    Dialog(onDismissRequest = { if (!loading) onDismiss() }) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelSolid)
                .padding(18.dp),
        ) {
            Text("添加订阅", color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)

            Spacer(Modifier.height(14.dp))

            Row {
                TabChip("链接导入", mode == "url") { mode = "url" }
                Spacer(Modifier.width(8.dp))
                TabChip("文本导入", mode == "text") { mode = "text" }
            }
            Spacer(Modifier.height(12.dp))

            Field(name, { name = it }, "名称(可选)")
            Spacer(Modifier.height(8.dp))
            if (mode == "url") {
                val context = androidx.compose.ui.platform.LocalContext.current
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://… 订阅链接", color = colors.textTertiary, fontSize = 12.sp) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    trailingIcon = {
                        Text(
                            "粘贴",
                            color = colors.accent,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable {
                                    runCatching {
                                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                                        val text = cm?.primaryClip?.getItemAt(0)?.text?.toString()?.trim()
                                        if (!text.isNullOrBlank()) url = text
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.text,
                        unfocusedTextColor = colors.text,
                        focusedBorderColor = colors.primaryBorder,
                        unfocusedBorderColor = colors.border,
                        cursorColor = colors.primary,
                    ),
                )
            } else {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    placeholder = {
                        Text(
                            "粘贴 Clash YAML / 分享链接",
                            color = colors.textTertiary,
                            fontSize = 12.sp,
                        )
                    },
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

            Spacer(Modifier.height(16.dp))

            // modal dialogs cover page-level toasts — import failures must
            // be readable in-place
            if (!loading && error != null) {
                Text(
                    error,
                    color = colors.danger,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 10.dp),
                )
            }

            val enabled = if (mode == "url") url.isNotBlank() else text.isNotBlank()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (loading) {
                    Text(
                        "正在导入…",
                        color = colors.textTertiary,
                        fontSize = 13.sp,
                    )
                    Spacer(Modifier.width(12.dp))
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    ActionChip(text = "取消", danger = true) { onCancel() }
                } else {
                    ActionChip(text = "取消") { onDismiss() }
                    Spacer(Modifier.width(10.dp))
                    ActionChip(text = "导入", primary = true) {
                        if (enabled) {
                            if (mode == "url") onAddUrl(name, url) else onAddText(name, text)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = LocalInterstellarColors.current
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) colors.primaryMuted else colors.bgDeep)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            color = if (selected) colors.primary else colors.textTertiary,
            fontSize = 12.sp,
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
        placeholder = { Text(placeholder, color = colors.textTertiary, fontSize = 12.sp) },
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
