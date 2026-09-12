package com.interstellar.proxy.ui.pages

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.data.CustomRulesStore
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ui.components.GlassCard
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSwitch
import com.interstellar.proxy.ui.components.PageHeader
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.Accents
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

private var onThemeChanged: (() -> Unit)? = null

/** Registered by MainActivity so setting changes re-compose the theme. */
fun setThemeChangedListener(listener: () -> Unit) {
    onThemeChanged = listener
}

enum class SettingsSubPage { Settings, PerApp, Connections, Logs, Rules, Dns }

/** Bottom-dock root tabs (satelite's navbar, phone layout). */
enum class MainTab { Home, Nodes, Subscriptions, Settings }

/** hiddify-style: phone uses 2 tabs (Home/Settings); these pages push in. */
fun settingsSubPageTitle(page: SettingsSubPage): String = when (page) {
    SettingsSubPage.Settings -> "设置"
    SettingsSubPage.PerApp -> "分应用代理"
    SettingsSubPage.Connections -> "监控"
    SettingsSubPage.Logs -> "系统日志"
    SettingsSubPage.Rules -> "分流规则"
    SettingsSubPage.Dns -> "DNS 解析"
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

/**
 * Settings per the reference design: uppercase kicker + big title,
 * sectioned glass cards with plain title/description rows (no icon
 * squares, no separators) and right-aligned controls.
 */
@Composable
fun SettingsPage(onOpen: (SettingsSubPage) -> Unit, onProxyChanged: () -> Unit = {}) {
    val colors = LocalInterstellarColors.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var themeMode by remember { mutableStateOf(Settings.themeMode) }

    fun normalizedTheme(): String = when (themeMode) {
        "aerospace" -> "dark"
        "day" -> "light"
        else -> themeMode
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        PageHeader(kicker = "PREFERENCES", title = "设置")

        // ---- 外观 ----
        PrefSectionLabel("外观")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var heroStyle by remember { mutableStateOf(Settings.heroStyle) }
            PrefSegRow(
                title = "主题",
                desc = "深浅色跟随系统或锁定",
                items = listOf("跟随系统", "浅色", "深色"),
                selected = listOf("system", "light", "dark").indexOf(normalizedTheme()),
                layout = SegLayout.Below,
                onSelect = { index ->
                    val mode = listOf("system", "light", "dark")[index]
                    themeMode = mode
                    Settings.themeMode = mode
                    onThemeChanged?.invoke()
                },
            )
            PrefSegRow(
                title = "主视觉",
                desc = "首页连接图标的样式",
                items = listOf("笑脸", "轨道"),
                selected = if (heroStyle == "orbit") 1 else 0,
                layout = SegLayout.Trailing,
                onSelect = { index ->
                    heroStyle = if (index == 1) "orbit" else "smiley"
                    Settings.heroStyle = heroStyle
                },
            )
            PrefSwatchRow(
                title = "主题色",
                desc = "马卡龙色板,整套界面随之换肤",
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 内核 ----
        PrefSectionLabel("内核")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val coreOrder = listOf(
                com.interstellar.proxy.core.CoreKind.SINGBOX,
                com.interstellar.proxy.core.CoreKind.MIHOMO,
                com.interstellar.proxy.core.CoreKind.XRAY,
            )
            PrefSegRow(
                title = "代理内核",
                desc = "订阅、规则与 DNS 配置随内核切换",
                items = coreOrder.map { it.displayName },
                selected = coreOrder.indexOf(Settings.coreKind).coerceAtLeast(0),
                layout = SegLayout.Below,
                onSelect = { index ->
                    val picked = coreOrder[index]
                    if (picked == com.interstellar.proxy.core.CoreKind.SINGBOX) {
                        if (Settings.coreKind != picked) {
                            Settings.coreKind = picked
                            onProxyChanged()
                        }
                    } else {
                        android.widget.Toast.makeText(
                            context,
                            "${picked.displayName} 内核即将上线",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }
        IosSectionFooter("多内核施工中:mihomo 与 Xray 将以独立子进程接入,与 sing-box 一键切换。")

        Spacer(Modifier.height(22.dp))

        // ---- 连接 ----
        PrefSectionLabel("连接")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            var bypassLan by remember { mutableStateOf(Settings.bypassLanEnabled) }
            var bypassCn by remember { mutableStateOf(Settings.bypassCnEnabled) }
            var adBlock by remember { mutableStateOf(Settings.adBlockEnabled) }
            var regionGroups by remember { mutableStateOf(Settings.regionGroupsEnabled) }
            PrefToggleRow(
                title = "绕过局域网",
                desc = "访问 NAS、打印机、路由器不走代理",
                checked = bypassLan,
                onChange = {
                    bypassLan = it
                    Settings.bypassLanEnabled = it
                    onProxyChanged()
                },
            )
            PrefToggleRow(
                title = "绕过大陆网站",
                desc = "大陆域名与 IP 直连不走代理",
                checked = bypassCn,
                onChange = {
                    bypassCn = it
                    Settings.bypassCnEnabled = it
                    onProxyChanged()
                },
            )
            PrefToggleRow(
                title = "去广告",
                desc = "拦截广告与跟踪域名",
                checked = adBlock,
                onChange = {
                    adBlock = it
                    Settings.adBlockEnabled = it
                    onProxyChanged()
                },
            )
            PrefToggleRow(
                title = "按国家分组",
                desc = "节点页提供香港、新加坡等国家测速组",
                checked = regionGroups,
                onChange = {
                    regionGroups = it
                    Settings.regionGroupsEnabled = it
                    onProxyChanged()
                },
            )
        }
        IosSectionFooter("修改后立即重新生成配置,内核运行中自动热重载。")

        Spacer(Modifier.height(22.dp))

        // ---- 分流 ----
        PrefSectionLabel("分流")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            val ruleTotal = CustomRulesStore.rules.size
            val ruleOn = CustomRulesStore.rules.count { it.enabled }
            PrefNavRow(
                title = "分流规则",
                desc = "域名 → 节点关键词独立测速池",
                value = when {
                    ruleTotal == 0 -> "未设置"
                    else -> "$ruleOn 条启用"
                },
                onClick = { onOpen(SettingsSubPage.Rules) },
            )
            val dnsTotal = DnsOverridesStore.entries.size
            val dnsOn = DnsOverridesStore.entries.count { it.enabled }
            PrefNavRow(
                title = "DNS 解析",
                desc = "域名 → IP 手动解析覆盖",
                value = when {
                    dnsTotal == 0 -> "未设置"
                    else -> "$dnsOn 条启用"
                },
                onClick = { onOpen(SettingsSubPage.Dns) },
            )
            PrefNavRow(
                title = "分应用代理",
                desc = "白名单 / 黑名单控制哪些应用走代理",
                value = if (Settings.perAppProxyEnabled) {
                    if (Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE) "白名单" else "黑名单"
                } else {
                    "关闭"
                },
                onClick = { onOpen(SettingsSubPage.PerApp) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 诊断 ----
        PrefSectionLabel("诊断")
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = "系统日志",
                desc = "内核实时日志流",
                onClick = { onOpen(SettingsSubPage.Logs) },
            )
        }

        Spacer(Modifier.height(22.dp))

        // ---- 关于 ----
        PrefSectionLabel("关于")
        // battery-exemption state refreshes when the system dialog / settings round-trips back
        var batteryIgnored by remember {
            mutableStateOf(isIgnoringBatteryOptimizations(context))
        }
        val lifecycleOwner = context as? androidx.activity.ComponentActivity
        DisposableEffect(lifecycleOwner) {
            val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    batteryIgnored = isIgnoringBatteryOptimizations(context)
                }
            }
            lifecycleOwner?.lifecycle?.addObserver(observer)
            onDispose { lifecycleOwner?.lifecycle?.removeObserver(observer) }
        }
        GlassCard(modifier = Modifier.fillMaxWidth(), contentPadding = 6.dp) {
            PrefNavRow(
                title = "电池优化豁免",
                desc = if (batteryIgnored) {
                    "已加入系统白名单;厂商省电策略可点进应用设置的电池选项改为无限制"
                } else {
                    "点击在系统弹窗中允许后台运行"
                },
                value = if (batteryIgnored) "已豁免" else null,
                onClick = {
                    runCatching {
                        val packageUri = android.net.Uri.parse("package:" + context.packageName)
                        context.startActivity(
                            // already whitelisted → the request intent is a no-op on
                            // most ROMs, so route to the app details page instead
                            if (batteryIgnored) {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    packageUri,
                                )
                            } else {
                                android.content.Intent(
                                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                    packageUri,
                                )
                            },
                        )
                    }
                },
            )
            PrefNavRow(title = "版本", value = BuildConfig.VERSION_NAME)
            PrefNavRow(title = "内核", value = "sing-box 1.14.0")
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** Uppercase wide-tracked section label, reference style. */
@Composable
private fun PrefSectionLabel(text: String) {
    val colors = LocalInterstellarColors.current
    Text(
        text,
        color = colors.textTertiary,
        fontSize = 11.sp,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
        letterSpacing = 2.sp,
        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
    )
}

@Composable
private fun PrefRowShell(
    title: String,
    desc: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val colors = LocalInterstellarColors.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressableClick(onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                color = colors.text,
                fontSize = 15.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (!desc.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    desc,
                    color = colors.textTertiary,
                    fontSize = 12.sp,
                    lineHeight = 15.sp,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

@Composable
private fun PrefToggleRow(
    title: String,
    desc: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    PrefRowShell(title = title, desc = desc) {
        IosSwitch(checked = checked, onChange = onChange)
    }
}

private enum class SegLayout { Trailing, Below }

@Composable
private fun PrefSegRow(
    title: String,
    desc: String? = null,
    items: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    layout: SegLayout = SegLayout.Trailing,
) {
    when (layout) {
        SegLayout.Trailing -> PrefRowShell(title = title, desc = desc) {
            SegmentedControl(
                items = items,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.width(if (items.size >= 3) 190.dp else 128.dp),
            )
        }

        SegLayout.Below -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        color = LocalInterstellarColors.current.text,
                        fontSize = 15.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            SegmentedControl(
                items = items,
                selected = selected,
                onSelect = onSelect,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrefNavRow(
    title: String,
    desc: String? = null,
    value: String? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalInterstellarColors.current
    PrefRowShell(title = title, desc = desc, onClick = onClick) {
        if (value != null) {
            Text(
                value,
                color = colors.textTertiary,
                fontSize = 14.sp,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
        }
        Text("›", color = colors.textTertiary, fontSize = 20.sp)
    }
}

/** Macaron accent dots; the selected one grows and gains a ring. */
@Composable
private fun PrefSwatchRow(title: String, desc: String) {
    val colors = LocalInterstellarColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            title,
            color = colors.text,
            fontSize = 15.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(desc, color = colors.textTertiary, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Accents.presets.forEach { preset ->
                AccentDot(
                    preset = preset,
                    selected = Accents.selectedId == preset.id,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun AccentDot(preset: Accents.Preset, selected: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalInterstellarColors.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 1f else 0.94f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "accentDotScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(160),
        label = "accentDotRing",
    )
    val light = 0.2126f * colors.bg.red + 0.7152f * colors.bg.green + 0.0722f * colors.bg.blue > 0.5f
    Box(
        modifier = modifier
            .pressableClick { Accents.select(preset.id) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .graphicsLayer { alpha = ringAlpha }
                        .clip(CircleShape)
                        .border(1.6.dp, colors.textTertiary, CircleShape),
                )
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        }
                        .clip(CircleShape)
                        .background(if (light) preset.light else preset.dark),
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                preset.label,
                color = if (selected) colors.text else colors.textTertiary,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}
