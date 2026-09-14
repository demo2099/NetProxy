package com.interstellar.proxy.core

import com.interstellar.proxy.data.Settings
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 内核控制端口（Clash API，即配置里的 `external_controller`）。
 *
 * 为什么不能写死 9090：9090 是 Clash 系的事实标准端口，而这恰恰是它必然冲突的
 * 原因 —— 设备上任何一个 Clash 客户端（Clash Meta、FlClash、v2rayNG…）默认都用
 * 它，我们自己上一次没退干净的 mihomo 子进程也用它。而 sing-box 把"控制端口绑定
 * 失败"当成**致命错误**：
 *
 * ```
 * finish-start clash server: external controller listen error:
 * listen tcp 127.0.0.1:9090: bind: address already in use
 * ```
 *
 * 整个配置初始化直接失败 —— 不是某个节点连不上，是**一个节点都起不来**，
 * 而且日志里只有一句英文报错，看不出跟端口有关。
 *
 * 所以这里改成：启动前探一个当前真正空闲的端口，记在 [current]，配置生成器
 * （[com.interstellar.proxy.data.config.ConfigBuilder] /
 * [com.interstellar.proxy.data.config.MihomoConfigBuilder]）和 API 客户端
 * （[ClashApiClient]）都读它。同时落盘到 [Settings.apiPort]，这样进程被系统杀掉
 * 之后重启，已生成配置里烘焙的端口和客户端读到的端口仍然一致。
 *
 * 注意：Android 不允许 App 去 kill 别的进程占着的 socket，所以"把端口杀掉"这条
 * 路根本走不通（除非有 root）。绕开冲突才是正解 —— 9090 空着就还是用 9090，
 * 行为跟以前完全一样。
 */
object ApiPort {
    /** 首选端口。能用就用，跟其它 Clash 工具保持一致。 */
    const val PREFERRED = 9090

    /** 冲突时向后扫描的端口数（9090..9099）。 */
    private const val SCAN = 10

    private const val LOOPBACK = "127.0.0.1"

    @Volatile
    private var cached: Int = 0

    /**
     * 内核是否正在跑。跑着的时候端口**绝不能变** —— mihomo 的
     * `external-controller` 只在进程启动时读一次，热重载改不了；如果这时换了
     * 端口，[ClashApiClient] 会去打一个没人监听的端口，`api.reload()` 必然失败，
     * 于是每次改设置都退化成"整核重启"（掉线）。
     */
    @Volatile
    private var pinned: Boolean = false

    /** 内核与 API 客户端共同约定的端口。 */
    val current: Int
        get() = cached.takeIf { it != 0 } ?: Settings.apiPort

    /**
     * 探一个空闲端口并记住它。**配置生成前调用一次**，这样配置里烘焙的端口和
     * 客户端读到的 [current] 必然一致。
     *
     * 内核已经在跑时直接返回原端口（见 [pinned]）。
     */
    fun acquire(): Int {
        if (pinned && cached != 0) return cached
        // 探到哪个用哪个：9090 被占就 9091，依次往后；全被占就交给系统分配临时端口
        val picked = firstFree() ?: ephemeral() ?: PREFERRED
        remember(picked)
        return picked
    }

    /** 内核已成功起来：在它退出前 [current] 不再变。 */
    fun pin() {
        pinned = true
    }

    /** 内核已完全退出：下次 [acquire] 重新探测（[Settings.apiPort] 保留上次的值）。 */
    fun release() {
        pinned = false
        cached = 0
    }

    // ---- helpers ----

    private fun firstFree(): Int? {
        for (port in PREFERRED until PREFERRED + SCAN) {
            if (isFree(port)) return port
        }
        return null
    }

    /**
     * 绑定探测。探测用的 socket 立刻关掉 —— 这是"此刻谁占着"的判断，不是占位。
     * 内核真正的绑定发生在毫秒之后；而真正被占用的端口（别的 App、我们自己漏掉的
     * 子进程）会一直占着，不会被这两毫秒骗过去。
     *
     * SO_REUSEADDR（Java 默认开）只影响 TIME_WAIT，挡不住"已有 listener 正在
     * listen"，所以这个探测是准的。
     */
    private fun isFree(port: Int): Boolean = runCatching {
        ServerSocket(port, 1, InetAddress.getByName(LOOPBACK)).close()
        true
    }.getOrDefault(false)

    /** 9090..9099 全被占：让内核自己去挑一个临时端口。 */
    private fun ephemeral(): Int? = runCatching {
        ServerSocket(0, 1, InetAddress.getByName(LOOPBACK)).use { it.localPort }
    }.getOrNull()

    private fun remember(port: Int) {
        cached = port
        if (Settings.apiPort != port) Settings.apiPort = port
        if (port != PREFERRED) {
            AppLog.log("core", "$PREFERRED 已被占用，内核控制端口改用 $port")
        }
    }
}
