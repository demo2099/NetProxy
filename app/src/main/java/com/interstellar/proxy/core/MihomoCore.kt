package com.interstellar.proxy.core

import android.content.Context
import android.util.Log
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.data.config.MihomoConfigBuilder
import kotlinx.coroutines.delay
import java.io.File

/**
 * mihomo (Clash Meta) sidecar engine. Runs the official android binary from
 * nativeLibraryDir with `-d <home> -f config.yaml`; control (hot node
 * switch, delay tests, config reload) goes through the Clash REST API.
 *
 * VPN mode: the config embeds tun.file-descriptor (auto-route false) — the
 * app owns the VpnService routes and excludes node server IPs to prevent
 * loops (Mishka-proven pattern).
 */
class MihomoCore(
    private val context: Context,
    private val host: CoreHost,
) : ProxyCore {
    override val kind = CoreKind.MIHOMO

    private val workDir = File(context.filesDir, "mihomo").apply { mkdirs() }
    private val configFile = File(workDir, "config.yaml")
    val api = ClashApiClient(API_PORT, Settings.apiSecret)

    private var sidecar: SidecarProcess? = null
    private var lastOverrides: CoreOverrides? = null

    /** fd of the tun handed over at spawn; re-injected on hot reloads. */
    private var activeTunFd: Int? = null

    override suspend fun startup() {
        ensureGeodata()
    }

    /**
     * mihomo fatals when GEOSITE/GEOIP rules can't resolve their databases,
     * and its built-in download needs a working network (chicken-and-egg on
     * a fresh start) — ship the databases in assets and extract once.
     */
    private fun ensureGeodata() {
        val marker = File(workDir, "geodata.extracted")
        val geosite = File(workDir, "geosite.dat")
        val geoip = File(workDir, "geoip.metadb")
        if (marker.isFile && geosite.isFile && geoip.isFile) return
        context.assets.open("geodata/geosite.dat").use { input ->
            geosite.outputStream().use { input.copyTo(it) }
        }
        context.assets.open("geodata/geoip.metadb").use { input ->
            geoip.outputStream().use { input.copyTo(it) }
        }
        marker.writeText("1")
        Log.i(TAG, "geodata extracted to $workDir")
    }

    override suspend fun applyConfig(config: String, overrides: CoreOverrides) {
        lastOverrides = overrides
        if (sidecar?.running == true) {
            // hot reload: rewrite the file, then ask mihomo to re-read it
            configFile.writeText(stripTun(config))
            if (api.reload(configFile.absolutePath)) {
                AppLog.log("mihomo", "配置已热重载")
                applySelection(overrides)
                return
            }
            // API unreachable → process died between checks; fall through to respawn
            sidecar?.destroy()
        }

        // VPN mode: the fd stays in-process — ProcessBuilder closes inherited
        // fds, so the TUN is bridged by hev-socks5-tunnel (JNI) to mihomo's
        // mixed port and the core always runs proxy-only
        val tunFd = host.openSidecarTun(
            SidecarTunSpec(
                exclusions = routeExclusions(),
                perAppEnabled = overrides.perAppEnabled,
                perAppInclude = overrides.perAppInclude,
                perAppPackages = overrides.perAppPackages,
                allowBypass = Settings.allowBypass,
            ),
        )
        if (tunFd != null) {
            val ok = runCatching { TProxyService.start(context, tunFd, MIXED_PORT) }.getOrDefault(false)
            if (!ok) {
                Log.e(TAG, "hev tun bridge failed to start")
                AppLog.log("vpn", "hev TUN 桥启动失败")
                error("TUN 桥接启动失败")
            }
            AppLog.log("vpn", "hev TUN 桥已启动 (fd=$tunFd → 127.0.0.1:$MIXED_PORT)")
        }
        activeTunFd = tunFd

        // stale stored configs may still carry a tun block — strip it
        configFile.writeText(stripTun(config))

        val process = SidecarProcess(context, "libmihomo.so", listOf("-d", workDir.absolutePath, "-f", configFile.absolutePath), workDir) { code ->
            Log.e(TAG, "mihomo exited unexpectedly: $code")
            AppLog.log("mihomo", "进程异常退出 code=$code")
            activeTunFd = null
            Holder.instance = null
            host.onCoreRequestStop()
        }
        process.start()
        sidecar = process
        Holder.instance = this
        com.interstellar.proxy.core.AppLog.log("mihomo", "进程已启动, 等待 API 就绪…")

        // wait for the REST API to come up: raw TCP reachability first (auth /
        // HTTP failures must not be mistaken for "not ready"), then probe the
        // real endpoint once and surface its error for diagnosis
        var ready = false
        var socketErr: String? = null
        repeat(READY_POLLS) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", API_PORT), 600)
                }
                ready = true
                return@repeat
            } catch (e: Exception) {
                socketErr = e.message
            }
            delay(READY_INTERVAL_MS)
        }
        if (!ready) {
            Log.e(TAG, "mihomo API socket unreachable: $socketErr")
            AppLog.log("mihomo", "API 端口不可达: $socketErr")
            error("mihomo 启动超时(详见 ${configFile.parentFile}/libmihomo.so.log)")
        }
        runCatching { api.version() }.onFailure {
            AppLog.log("mihomo", "API 已连通但 /version 失败: ${it.message}")
            Log.w(TAG, "version probe failed", it)
        }
        applySelection(overrides)
    }

    override fun pause() {}
    override fun wake() {}
    override fun needWifiState() = false

    override suspend fun shutdown() {
        Holder.instance = null
        TProxyService.stop()
        activeTunFd = null
        sidecar?.destroy()
        sidecar = null
    }

    /** Re-apply whatever ConfigStore currently holds (UI-driven refresh). */
    suspend fun refreshFromConfigStore() {
        val content = com.interstellar.proxy.data.ConfigStore.readActiveConfig() ?: return
        applyConfig(content, lastOverrides ?: CoreOverrides(false, false, true, emptySet()))
    }

    // ---- helpers ----

    /** mihomo select groups have no config default — apply via API (store-selected persists it). */
    private suspend fun applySelection(overrides: CoreOverrides) {
        val tag = overrides.selectedTag?.takeIf { it.isNotBlank() } ?: return
        runCatching { api.select(ConfigBuilder.GROUP_TAG, tag) }
    }

    private fun routeExclusions(): List<String> =
        MihomoConfigBuilder.routeExclusions(SubscriptionRepository.activeNodes())

    private fun stripTun(config: String): String {
        // headless / proxy mode: drop the tun block entirely
        val lines = config.split('\n').toMutableList()
        val start = lines.indexOfFirst { it.startsWith("tun:") }
        if (start >= 0) {
            var end = lines.size
            for (i in start + 1 until lines.size) {
                if (lines[i].isNotBlank() && !lines[i].startsWith("  ")) {
                    end = i
                    break
                }
            }
            lines.subList(start, end).clear()
        }
        return lines.joinToString("\n")
    }

    companion object {
        private const val TAG = "MihomoCore"
        const val API_PORT = 9090
        /** must match ConfigBuilder.BuildOptions.mixedPort default */
        const val MIXED_PORT = 2080
        private const val READY_POLLS = 20
        private const val READY_INTERVAL_MS = 500L
    }

    /** Process-wide handle so the UI can hot-reload the running engine. */
    object Holder {
        @Volatile
        var instance: MihomoCore? = null
    }
}
