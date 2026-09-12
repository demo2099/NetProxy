package com.interstellar.proxy.core

import android.content.Context
import java.io.File

/**
 * In-process TUN→socks5 bridge (hev-socks5-tunnel JNI build). The VpnService
 * fd stays in the app process — Android's ProcessBuilder closes inherited
 * fds in exec'd children, so sidecar cores (mihomo / Xray) never get the fd
 * directly; they run proxy-only and this bridge feeds them the TUN traffic
 * over their local mixed/socks port.
 *
 * Native side is registered against THIS class (see PKGNAME in the build).
 */
object TProxyService {
    /**
     * hev's JNI_OnLoad does FindClass/RegisterNatives with the CALLING
     * thread's classloader — coroutine IO threads have none, which aborts
     * the whole VM. Must touch this object from the main thread first.
     */
    @Synchronized
    fun preload() {
        running // forces object init (System.loadLibrary) on the caller thread
    }

    val running: Boolean
        get() = runCatching { TProxyIsRunning() }.getOrDefault(false)

    fun start(context: Context, fd: Int, socksPort: Int, mtu: Int = 9000): Boolean {
        if (running) return true
        val dir = File(context.filesDir, "hev").apply { mkdirs() }
        val config = File(dir, "config.yml")
        config.writeText(
            """
            tunnel:
              name: interstellar
              mtu: $mtu
            socks5:
              address: 127.0.0.1
              port: $socksPort
              udp: 'udp'
            misc:
              log-level: warn
            """.trimIndent(),
        )
        return TProxyStartService(config.absolutePath, fd)
    }

    fun stop(): Boolean = runCatching { TProxyStopService() }.getOrDefault(false)

    /** Cumulative [txPackets, txBytes, rxPackets, rxBytes] through the bridge. */
    fun stats(): LongArray? = runCatching { TProxyGetStats() }.getOrNull()

    @JvmStatic
    private external fun TProxyStartService(configPath: String, fd: Int): Boolean

    @JvmStatic
    private external fun TProxyStopService(): Boolean

    @JvmStatic
    private external fun TProxyIsRunning(): Boolean

    @JvmStatic
    private external fun TProxyGetStats(): LongArray?

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
