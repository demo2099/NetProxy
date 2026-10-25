package com.interstellar.proxy.core

import com.interstellar.proxy.data.Settings
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 本地混合入站端口（sing-box 的 `mixed` / mihomo 的 `mixed-port` / Xray 的 socks 入站）。
 *
 * 为什么不能写死 2080：它和 9090 一样是「大家都爱用」的端口 —— 设备上任何一个
 * Clash 客户端、甚至我们上一次没退干净的 sidecar 都可能占着。而内核把入站绑定
 * 失败当成**致命错误**，整个配置起不来（不是某个节点连不上，是一个节点都没有）：
 *
 * ```
 * start inbound/mixed[mixed-in]: listen tcp 127.0.0.1:2080:
 * bind: address already in use
 * ```
 *
 * [ApiPort] 早就处理了控制端口（9090 被占就换），但混合入站一直硬编码 2080 ——
 * 于是「9090 会自动让、2080 不会」，撞上就彻底起不来。这里补上同样的探测逻辑。
 *
 * 换端口时必须同时改到的三处（漏一个就全断）：
 *   1. 配置里烘焙的端口 —— [com.interstellar.proxy.data.config.ConfigBuilder] /
 *      [com.interstellar.proxy.data.config.MihomoConfigBuilder] /
 *      [com.interstellar.proxy.data.config.XrayConfigBuilder]（都读 BuildOptions）
 *   2. TUN → 本地代理的桥 —— [MihomoCore] / [XrayCore] 里的 hev-socks5-tunnel
 *   3. 所有直连这个端口的客户端 —— NetProbe / SubscriptionFetcher / SmartSwitchEngine
 * 所以统一从这里取，不再有第二处字面量。
 */
object LocalPort {
    /** 首选端口，能用就用（和主流 Clash 客户端保持一致，方便用户自己排查）。 */
    const val PREFERRED = 2080

    /** 冲突时向后扫描的端口数（2080..2089）。 */
    private const val SCAN = 10

    private const val LOOPBACK = "127.0.0.1"

    @Volatile
    private var cached: Int = 0

    /**
     * 内核跑着的时候端口**绝不能变**：配置已经烘焙好、TUN 桥也连着它。
     * 这时候换端口，桥会指向一个没人监听的端口 —— 表现是「连上了但所有网页都打不开」。
     */
    @Volatile
    private var pinned: Boolean = false

    /** 内核与所有客户端共同约定的混合端口。 */
    val current: Int
        get() = cached.takeIf { it != 0 }
            ?: Settings.mixedPort.takeIf { it > 0 }
            ?: PREFERRED

    /** 生成配置前调用一次：探一个空闲端口并记住它。 */
    fun acquire(): Int {
        if (pinned && cached != 0) return cached
        val picked = firstFree() ?: ephemeral() ?: PREFERRED
        cached = picked
        if (Settings.mixedPort != picked) Settings.mixedPort = picked
        if (picked != PREFERRED) {
            AppLog.log("core", "$PREFERRED 已被占用，混合入站改用 $picked")
        }
        return picked
    }

    /** 内核已成功起来：在它退出前 [current] 不再变。 */
    fun pin() {
        pinned = true
    }

    /** 内核已完全退出：下次 [acquire] 重新探测。 */
    fun release() {
        pinned = false
        cached = 0
    }

    // ---- helpers ----

    /**
     * 先试上次用的端口（避免每次启动都换），再按 2080..2089 顺序找。
     * 用 bind 探测而不是 `netstat`：Android 上拿不到别的进程的 socket 列表。
     */
    private fun firstFree(): Int? {
        val last = Settings.mixedPort.takeIf { it in 1024..65535 } ?: PREFERRED
        val order = buildList {
            add(last)
            for (port in PREFERRED until PREFERRED + SCAN) {
                if (port != last) add(port)
            }
        }
        return order.firstOrNull { isFree(it) }
    }

    private fun isFree(port: Int): Boolean = runCatching {
        ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).close()
        true
    }.getOrDefault(false)

    /** 2080..2089 全被占：让系统分配一个临时端口。 */
    private fun ephemeral(): Int? = runCatching {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
    }.getOrNull()
}
