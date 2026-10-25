package com.interstellar.proxy.data

import android.content.Context
import com.interstellar.proxy.InterstellarApplication
import java.io.File
import java.util.Properties

/**
 * Simple synchronous key-value settings persisted to filesDir/settings.properties.
 * Mirrors the subset of SFA Settings used by the service layer.
 */
object Settings {
    private const val FILE_NAME = "settings.properties"

    const val PER_APP_PROXY_INCLUDE = 0
    const val PER_APP_PROXY_EXCLUDE = 1

    private val properties = Properties()
    private val file: File
        get() = File(InterstellarApplication.application.filesDir, FILE_NAME)

    init {
        load()
    }

    @Synchronized
    fun reload() {
        load()
    }

    private fun load() {
        properties.clear()
        runCatching {
            file.inputStream().use { properties.load(it) }
        }
    }

    @Synchronized
    private fun commit() {
        runCatching {
            file.outputStream().use { properties.store(it, null) }
        }
    }

    var serviceMode: String
        get() = properties.getProperty("serviceMode", "vpn")
        set(value) {
            properties.setProperty("serviceMode", value)
            commit()
        }

    /** Active proxy core. sing-box in-process; mihomo/Xray sidecars (multi-core). */
    var coreKind: com.interstellar.proxy.core.CoreKind
        get() = com.interstellar.proxy.core.CoreKind.from(properties.getProperty("coreKind", "singbox"))
        set(value) {
            properties.setProperty("coreKind", value.wire)
            commit()
        }

    var allowBypass: Boolean
        get() = properties.getProperty("allowBypass", "false").toBoolean()
        set(value) {
            properties.setProperty("allowBypass", value.toString())
            commit()
        }

    var autoRedirect: Boolean
        get() = properties.getProperty("autoRedirect", "false").toBoolean()
        set(value) {
            properties.setProperty("autoRedirect", value.toString())
            commit()
        }

    var dynamicNotification: Boolean
        get() = properties.getProperty("dynamicNotification", "true").toBoolean()
        set(value) {
            properties.setProperty("dynamicNotification", value.toString())
            commit()
        }

    var systemProxyEnabled: Boolean
        get() = properties.getProperty("systemProxyEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("systemProxyEnabled", value.toString())
            commit()
        }

    var perAppProxyEnabled: Boolean
        get() = properties.getProperty("perAppProxyEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("perAppProxyEnabled", value.toString())
            commit()
        }

    var perAppProxyMode: Int
        get() = properties.getProperty("perAppProxyMode", "$PER_APP_PROXY_INCLUDE")?.toIntOrNull()
            ?: PER_APP_PROXY_INCLUDE
        set(value) {
            properties.setProperty("perAppProxyMode", value.toString())
            commit()
        }

    var perAppProxyList: Set<String>
        get() = properties.getProperty("perAppProxyList", "")
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()
        set(value) {
            properties.setProperty("perAppProxyList", value.joinToString("\n"))
            commit()
        }

    var perAppProxyShowSystemApps: Boolean
        get() = properties.getProperty("perAppProxyShowSystemApps", "false").toBoolean()
        set(value) {
            properties.setProperty("perAppProxyShowSystemApps", value.toString())
            commit()
        }

    var activeSubscriptionId: String
        get() = properties.getProperty("activeSubscriptionId", "")
        set(value) {
            properties.setProperty("activeSubscriptionId", value)
            commit()
        }

    var selectedNodeId: String
        get() = properties.getProperty("selectedNodeId", "")
        set(value) {
            properties.setProperty("selectedNodeId", value)
            commit()
        }

    /**
     * Mix mode: the node pool is the union of all checked subscriptions
     * instead of the single active one. Traffic/expiry stay per-subscription.
     */
    var mixEnabled: Boolean
        get() = properties.getProperty("mixEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("mixEnabled", value.toString())
            commit()
        }

    var mixSubscriptionIds: Set<String>
        get() = properties.getProperty("mixSubscriptionIds", "")
            .split('\n')
            .filter { it.isNotBlank() }
            .toSet()
        set(value) {
            properties.setProperty("mixSubscriptionIds", value.joinToString("\n"))
            commit()
        }

    /**
     * When true, the config includes one urltest group per detected country
     * (香港 / 新加坡 / …). Off by default: only 自动 and manual node pick.
     */
    var regionGroupsEnabled: Boolean
        get() = properties.getProperty("regionGroupsEnabled", "false").toBoolean()
        set(value) {
            properties.setProperty("regionGroupsEnabled", value.toString())
            commit()
        }

    /**
     * Master switch for custom domain→node-filter rules. Independent of
     * auto vs a locked node: on = matching domains always use their own
     * urltest pool; off = all proxy traffic uses the current selection.
     */
    var splitRulesEnabled: Boolean
        get() = properties.getProperty("splitRulesEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("splitRulesEnabled", value.toString())
            commit()
        }

    /** Last selected outbound tag of the main selector (`auto`, `smart`, a region group, or a node). */
    var selectedOutboundTag: String
        get() = properties.getProperty("selectedOutboundTag", "")
        set(value) {
            properties.setProperty("selectedOutboundTag", value)
            commit()
        }

    /**
     * Smart mode: the node the engine last settled on (baked into configs as
     * the effective selection while selectedOutboundTag == "smart").
     */
    var smartActiveTag: String
        get() = properties.getProperty("smartActiveTag", "")
        set(value) {
            properties.setProperty("smartActiveTag", value)
            commit()
        }

    /** Nodes page layout: grid (default) or list. */
    var nodesGridView: Boolean
        get() = properties.getProperty("nodesGridView", "true").toBoolean()
        set(value) {
            properties.setProperty("nodesGridView", value.toString())
            commit()
        }

    var outboundMode: com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode
        get() = when (properties.getProperty("outboundMode", "rule")) {
            "global" -> com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.GLOBAL
            "direct" -> com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.DIRECT
            else -> com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.RULE
        }
        set(value) {
            properties.setProperty(
                "outboundMode",
                when (value) {
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.GLOBAL -> "global"
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.DIRECT -> "direct"
                    com.interstellar.proxy.data.config.ConfigBuilder.OutboundMode.RULE -> "rule"
                },
            )
            commit()
        }

    var apiSecret: String
        get() = properties.getProperty("apiSecret", "")
        set(value) {
            properties.setProperty("apiSecret", value)
            commit()
        }

    /**
     * 内核控制端口（Clash API）。默认 9090，被占用时由
     * [com.interstellar.proxy.core.ApiPort] 换成空闲端口并写回这里。
     *
     * 必须持久化：端口已经烘焙进 active.json / config.yaml 了，进程被系统杀掉后
     * 重启，[com.interstellar.proxy.core.ClashApiClient] 还得能读到同一个值。
     */
    var apiPort: Int
        get() = properties.getProperty("apiPort", "9090").toIntOrNull() ?: 9090
        set(value) {
            properties.setProperty("apiPort", value.toString())
            commit()
        }

    /**
     * 混合入站端口（2080 被占时会被 [com.interstellar.proxy.core.LocalPort] 换成别的）。
     *
     * 同样必须持久化：端口已经烘焙进配置，TUN 桥和探测客户端读的也是这个值；
     * 进程被系统杀掉后重启，配置里烘焙的端口和这里的值还得对得上。
     */
    var mixedPort: Int
        get() = properties.getProperty("mixedPort", "2080").toIntOrNull() ?: 2080
        set(value) {
            properties.setProperty("mixedPort", value.toString())
            commit()
        }

    /**
     * 日志过滤档位：
     *   0 = 全部（panic…trace）
     *   1 = 警告及以上（panic/fatal/error/warn）
     *   2 = 仅错误（panic/fatal/error）
     *   3 = 关闭 —— 连日志管道一起断掉：不订阅 libbox 的 Log 连接、不 tail
     *       mihomo 日志文件、不收集应用日志，不只是「界面上不显示」
     *
     * 默认关闭：日志是高频写入，挂机时既费电又占内存；要排障时在日志页
     * 点一下档位按钮即可打开，选择会被记住（这里只是「没存过」时的初值）。
     */
    var logFilter: Int
        get() = properties.getProperty("logFilter", "3").toIntOrNull()?.coerceIn(0, 3) ?: 3
        set(value) {
            properties.setProperty("logFilter", value.coerceIn(0, 3).toString())
            commit()
        }

    var themeMode: String
        get() = properties.getProperty("themeMode", "light")
        set(value) {
            properties.setProperty("themeMode", value)
            commit()
        }

    var accentId: String
        get() = properties.getProperty("accentId", "green")
        set(value) {
            properties.setProperty("accentId", value)
            commit()
        }

    /** Homepage hero visual: "smiley" (FaceMark, default) or "orbit". */
    var heroStyle: String
        get() = properties.getProperty("heroStyle", "smiley")
        set(value) {
            properties.setProperty("heroStyle", value)
            commit()
        }

    /** Legacy key from the pre-glow era; the macaron accent (see ui.theme.Accents) replaced it. */
    var glowColorId: String
        get() = properties.getProperty("glowColorId", "matcha")
        set(value) {
            properties.setProperty("glowColorId", value)
            commit()
        }

    /** Last-known core state, keeps the quick-settings tile truthful. */
    var tileActive: Boolean
        get() = properties.getProperty("tileActive", "false").toBoolean()
        set(value) {
            properties.setProperty("tileActive", value.toString())
            commit()
        }

    /** Route RFC1918 / ULA / link-local directly (and exclude them from TUN). */
    var bypassLanEnabled: Boolean
        get() = properties.getProperty("bypassLanEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("bypassLanEnabled", value.toString())
            commit()
        }

    /** Route CN domains/IPs direct (built-in geo rule sets). */
    var bypassCnEnabled: Boolean
        get() = properties.getProperty("bypassCnEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("bypassCnEnabled", value.toString())
            commit()
        }

    /** Block ad/tracker domains (built-in category-ads-all rule set). */
    var adBlockEnabled: Boolean
        get() = properties.getProperty("adBlockEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("adBlockEnabled", value.toString())
            commit()
        }

    /** Subscription auto-update. */
    var autoUpdateEnabled: Boolean
        get() = properties.getProperty("autoUpdateEnabled", "true").toBoolean()
        set(value) {
            properties.setProperty("autoUpdateEnabled", value.toString())
            commit()
        }

    var autoUpdateIntervalHours: Int
        get() = properties.getProperty("autoUpdateIntervalHours", "6")?.toIntOrNull() ?: 6
        set(value) {
            properties.setProperty("autoUpdateIntervalHours", value.toString())
            commit()
        }

    /**
     * 订阅更新是否走代理。**默认关（直连）**，两个原因：
     *
     * 1. 订阅更新是"代理坏了之后"的修复入口 —— 节点全挂时，更新订阅正是唯一出路，
     *    这条路不能依赖节点本身，否则自锁（走代理更新 → 代理不通 → 更新不了 → 换不了节点）。
     * 2. 机场面板会记录订阅请求的来源 IP。经代理更新等于把落地 IP 写进面板的订阅日志，
     *    部分面板会据此判定多设备/共享。
     *
     * 只有订阅域名被墙、直连拿不到时才需要打开。打开后**严格走代理，不再回退直连**
     * —— 用户开它通常就是为了不暴露本机 IP。
     */
    var subscriptionViaProxy: Boolean
        get() = properties.getProperty("subscriptionViaProxy", "false").toBoolean()
        set(value) {
            properties.setProperty("subscriptionViaProxy", value.toString())
            commit()
        }

    /** Fingerprint of the last clipboard text the import banner was shown for; the same clip won't re-prompt. */
    var lastImportPromptClip: String
        get() = properties.getProperty("lastImportPromptClip", "")
        set(value) {
            properties.setProperty("lastImportPromptClip", value)
            commit()
        }

    /**
     * 启动时自动检查新版本。默认开 —— 更新检查只是一个几十 KB 的 JSON 请求，
     * 代价极低；关掉它意味着用户永远不知道自己落后了。
     */
    var autoCheckUpdate: Boolean
        get() = properties.getProperty("autoCheckUpdate", "true").toBoolean()
        set(value) {
            properties.setProperty("autoCheckUpdate", value.toString())
            commit()
        }

    /** 最近一次检查发现的远端版本（仅用于设置页展示，不参与下载地址）。 */
    var updateLatestVersion: String
        get() = properties.getProperty("updateLatestVersion", "")
        set(value) {
            properties.setProperty("updateLatestVersion", value)
            commit()
        }

    /** 用户点过「以后再说」的版本：同一个版本不再主动弹窗。 */
    var updateSkippedVersion: String
        get() = properties.getProperty("updateSkippedVersion", "")
        set(value) {
            properties.setProperty("updateSkippedVersion", value)
            commit()
        }

    fun serviceClass(): Class<*> =
        when (serviceMode) {
            "proxy" -> com.interstellar.proxy.bg.ProxyService::class.java
            else -> com.interstellar.proxy.bg.VPNService::class.java
        }

}
