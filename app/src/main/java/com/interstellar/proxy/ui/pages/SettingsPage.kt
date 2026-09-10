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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.interstellar.proxy.data.CustomRulesStore
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ui.components.IosCard
import com.interstellar.proxy.ui.components.IosHairline
import com.interstellar.proxy.ui.components.IosRow
import com.interstellar.proxy.ui.components.IosSectionFooter
import com.interstellar.proxy.ui.components.IosSectionLabel
import com.interstellar.proxy.ui.components.IosToggleRow
import com.interstellar.proxy.ui.components.SegmentedControl
import com.interstellar.proxy.ui.components.pressableClick
import com.interstellar.proxy.ui.theme.GlowPalette
import com.interstellar.proxy.ui.theme.LocalInterstellarColors

private var onThemeChanged: (() -> Unit)? = null

/** Registered by MainActivity so setting changes re-compose the theme. */
fun setThemeChangedListener(listener: () -> Unit) {
    onThemeChanged = listener
}

enum class SettingsSubPage { Settings, PerApp, Connections, Logs, Proxies, Rules, Dns }

/** hiddify-style: phone uses 2 tabs (Home/Settings); these pages push in. */
fun settingsSubPageTitle(page: SettingsSubPage): String = when (page) {
    SettingsSubPage.Settings -> "设置"
    SettingsSubPage.PerApp -> "分应用代理"
    SettingsSubPage.Connections -> "监控"
    SettingsSubPage.Logs -> "系统日志"
    SettingsSubPage.Proxies -> "节点"
    SettingsSubPage.Rules -> "分流规则"
    SettingsSubPage.Dns -> "DNS 解析"
}

private fun isIgnoringBatteryOptimizations(context: android.content.Context): Boolean =
    (context.getSystemService(android.content.Context.POWER_SERVICE) as? android.os.PowerManager)
        ?.isIgnoringBatteryOptimizations(context.packageName) ?: false

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
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        Column {
            IosSectionLabel("外观")
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    SegmentedControl(
                        items = listOf("跟随系统", "浅色", "深色"),
                        selected = listOf("system", "light", "dark").indexOf(normalizedTheme()),
                        onSelect = { index ->
                            val mode = listOf("system", "light", "dark")[index]
                            themeMode = mode
                            Settings.themeMode = mode
                            onThemeChanged?.invoke()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    IosHairline(startInset = 0.dp)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    ) {
                        Column {
                            Text("背景光晕", color = colors.text, fontSize = 17.sp)
                            Text(
                                "光晕与已连接笑脸线条颜色",
                                color = colors.textTertiary,
                                fontSize = 13.sp,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            GlowPalette.options.forEach { option ->
                                GlowSwatch(
                                    option = option,
                                    selected = GlowPalette.selectedId == option.id,
                                )
                            }
                        }
                    }
                }
            }
        }

        Column {
            IosSectionLabel("代理")
            var bypassLan by remember { mutableStateOf(Settings.bypassLanEnabled) }
            var bypassCn by remember { mutableStateOf(Settings.bypassCnEnabled) }
            var adBlock by remember { mutableStateOf(Settings.adBlockEnabled) }
            var regionGroups by remember { mutableStateOf(Settings.regionGroupsEnabled) }
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    IosToggleRow(
                        title = "绕过局域网",
                        subtitle = "访问 NAS、打印机、路由器不走代理",
                        checked = bypassLan,
                        onChange = {
                            bypassLan = it
                            Settings.bypassLanEnabled = it
                            onProxyChanged()
                        },
                    )
                    IosHairline(startInset = 16.dp)
                    IosToggleRow(
                        title = "绕过大陆网站",
                        subtitle = "大陆域名与 IP 直连不走代理",
                        checked = bypassCn,
                        onChange = {
                            bypassCn = it
                            Settings.bypassCnEnabled = it
                            onProxyChanged()
                        },
                    )
                    IosHairline(startInset = 16.dp)
                    IosToggleRow(
                        title = "去广告",
                        subtitle = "拦截广告与跟踪域名",
                        checked = adBlock,
                        onChange = {
                            adBlock = it
                            Settings.adBlockEnabled = it
                            onProxyChanged()
                        },
                    )
                    IosHairline(startInset = 16.dp)
                    IosToggleRow(
                        title = "按国家分组",
                        subtitle = "节点页提供香港、新加坡等国家测速组；关闭后只保留自动和手动选节点",
                        checked = regionGroups,
                        onChange = {
                            regionGroups = it
                            Settings.regionGroupsEnabled = it
                            onProxyChanged()
                        },
                    )
                    IosHairline(startInset = 16.dp)
                    val ruleTotal = CustomRulesStore.rules.size
                    val ruleOn = CustomRulesStore.rules.count { it.enabled }
                    IosRow(
                        icon = Icons.Filled.AltRoute,
                        iconBg = colors.iconOrange,
                        title = "分流规则",
                        value = when {
                            ruleTotal == 0 -> "未设置"
                            else -> "$ruleOn 条启用"
                        },
                        onClick = { onOpen(SettingsSubPage.Rules) },
                    )
                    IosHairline(startInset = 16.dp)
                    val dnsTotal = DnsOverridesStore.entries.size
                    val dnsOn = DnsOverridesStore.entries.count { it.enabled }
                    IosRow(
                        icon = Icons.Filled.Dns,
                        iconBg = colors.iconBlue,
                        title = "DNS 解析",
                        value = when {
                            dnsTotal == 0 -> "未设置"
                            else -> "$dnsOn 条启用"
                        },
                        onClick = { onOpen(SettingsSubPage.Dns) },
                    )
                }
            }
            IosSectionFooter("修改后立即重新生成配置,内核运行中自动热重载。")
        }

        Column {
            IosSectionLabel("应用")
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    IosRow(
                        icon = Icons.Filled.Apps,
                        iconBg = colors.iconGreen,
                        title = "分应用代理",
                        value = if (Settings.perAppProxyEnabled) {
                            if (Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE) "白名单" else "黑名单"
                        } else {
                            "关闭"
                        },
                        onClick = { onOpen(SettingsSubPage.PerApp) },
                    )
                }
            }
        }

        // ---- diagnostics ----
        Column {
            IosSectionLabel("诊断")
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    IosRow(
                        icon = Icons.Filled.Terminal,
                        iconBg = colors.iconOrange,
                        title = "系统日志",
                        onClick = { onOpen(SettingsSubPage.Logs) },
                    )
                }
            }
        }

        // ---- about ----
        Column {
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
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    IosRow(
                        icon = Icons.Filled.BatteryFull,
                        iconBg = colors.iconGreen,
                        title = "电池优化豁免",
                        subtitle = if (batteryIgnored) {
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
                }
            }
            IosCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    IosRow(icon = Icons.Filled.Info, iconBg = colors.iconGray, title = "版本", value = "0.2.0", showChevron = false)
                    IosHairline(startInset = 57.dp)
                    IosRow(icon = Icons.Filled.Repeat, iconBg = colors.iconPurple, title = "内核", value = "sing-box 1.14.0", showChevron = false)
                }
            }
        }

        Spacer(Modifier.height(20.dp))
    }
}

/** Macaron color dot for the glow picker; selected one grows and gains a ring. */
@Composable
private fun GlowSwatch(option: GlowPalette.Option, selected: Boolean) {
    val colors = LocalInterstellarColors.current
    val scale by animateFloatAsState(
        targetValue = if (selected) 1.2f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 420f),
        label = "glowSwatchScale",
    )
    val ringAlpha by animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = tween(160),
        label = "glowSwatchRing",
    )
    Box(
        modifier = Modifier
            .size(32.dp)
            .pressableClick { GlowPalette.select(option.id) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = ringAlpha }
                .clip(CircleShape)
                .border(1.8.dp, colors.textTertiary, CircleShape),
        )
        Box(
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .clip(CircleShape)
                .background(GlowPalette.preview(option, colors.bg)),
        )
    }
}
