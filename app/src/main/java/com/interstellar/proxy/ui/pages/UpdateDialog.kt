package com.interstellar.proxy.ui.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.data.AppUpdater
import com.interstellar.proxy.data.UpdateInfo
import com.interstellar.proxy.data.UpdateState
import com.interstellar.proxy.ui.theme.LocalInterstellarColors
import java.util.Locale

/**
 * 应用内更新的唯一 UI 入口：检查中 / 有新版 / 下载中 / 待安装 / 失败 都在这里展示。
 * 所有耗时动作都交给 [AppUpdater] 的后台 scope —— 关掉这个对话框不会中断下载。
 */
@Composable
fun UpdateDialog(onDismiss: () -> Unit) {
    val colors = LocalInterstellarColors.current
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(colors.panelSolid)
                .padding(18.dp),
        ) {
            when (val s = state) {
                is UpdateState.Available -> {
                    DialogTitle("发现新版本 v${s.info.version}")
                    Spacer(Modifier.height(8.dp))
                    VersionLine(s.info)
                    Spacer(Modifier.height(10.dp))
                    NotesBlock(s.info.notes)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionChip("以后再说") {
                            AppUpdater.skipVersion(s.info.version)
                            onDismiss()
                        }
                        Spacer(Modifier.width(10.dp))
                        ActionChip("下载并安装", primary = true) { AppUpdater.startDownload(s.info) }
                    }
                }

                is UpdateState.Downloading -> {
                    DialogTitle("正在下载 v${s.info.version}")
                    Spacer(Modifier.height(12.dp))
                    val total = if (s.total > 0) s.total else s.info.size
                    val fraction =
                        if (total > 0) (s.received.toFloat() / total).coerceIn(0f, 1f) else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = colors.primary,
                        trackColor = colors.primaryMuted,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatSize(s.received)} / ${formatSize(total)}  ·  ${(fraction * 100).toInt()}%",
                        color = colors.textTertiary,
                        fontSize = 12.sp,
                    )
                    if (s.info.fromMirror) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "GitHub 直连失败，正在从镜像下载",
                            color = colors.textTertiary,
                            fontSize = 11.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        // 关掉对话框不中断下载，下次从「版本」行还能进来
                        ActionChip("后台下载") { onDismiss() }
                    }
                }

                is UpdateState.Ready -> {
                    // cacheDir 会被系统清理，进程重启后文件可能已经没了
                    val alive = s.file.exists()
                    DialogTitle(if (alive) "安装包已就绪" else "安装包已被系统清理")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (alive) {
                            "v${s.info.version}  ·  ${formatSize(s.file.length())}"
                        } else {
                            "v${s.info.version} 的安装包不在缓存里了，需要重新下载。"
                        },
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    val allowed = AppUpdater.canInstall(context)
                    if (alive && !allowed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "系统还没允许本应用安装 APK。点下面的按钮去授权，回来再点一次「安装」。",
                            color = colors.warning,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionChip("稍后") { onDismiss() }
                        Spacer(Modifier.width(10.dp))
                        if (alive) {
                            ActionChip(
                                text = if (allowed) "安装" else "去允许安装",
                                primary = true,
                            ) {
                                AppUpdater.install(context, s.file)
                            }
                        } else {
                            ActionChip("重新下载", primary = true) {
                                AppUpdater.startDownload(s.info)
                            }
                        }
                    }
                }

                is UpdateState.Failed -> {
                    DialogTitle("检查更新失败")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        s.message,
                        color = colors.danger,
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionChip("关闭") { onDismiss() }
                        Spacer(Modifier.width(10.dp))
                        ActionChip("重试", primary = true) { AppUpdater.startCheck() }
                    }
                }

                UpdateState.Checking -> {
                    DialogTitle("正在检查更新")
                    Spacer(Modifier.height(8.dp))
                    Text("正在查询最新版本…", color = colors.textSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionChip("关闭") { onDismiss() }
                    }
                }

                else -> {
                    DialogTitle("已是最新版本")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "当前 v${BuildConfig.VERSION_NAME}，没有更新的版本。",
                        color = colors.textSecondary,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        ActionChip("关闭") { onDismiss() }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) {
    val colors = LocalInterstellarColors.current
    Text(text, color = colors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun VersionLine(info: UpdateInfo) {
    val colors = LocalInterstellarColors.current
    Column {
        Text(
            "当前 ${BuildConfig.VERSION_NAME}  →  最新 ${info.version}",
            color = colors.textSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            "${formatSize(info.size)}  ·  ${if (info.fromMirror) "镜像源" else "GitHub"}",
            color = colors.textTertiary,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun NotesBlock(notes: String) {
    val colors = LocalInterstellarColors.current
    val text = notes.trim()
    if (text.isNotBlank()) {
        Text(
            text,
            color = colors.textSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 170.dp)
                .verticalScroll(rememberScrollState()),
        )
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "大小未知"
    bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / 1073741824.0)
    bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / 1048576.0)
    else -> String.format(Locale.US, "%d KB", bytes / 1024)
}
