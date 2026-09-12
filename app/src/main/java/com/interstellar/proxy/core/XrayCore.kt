package com.interstellar.proxy.core

import android.content.Context
import android.util.Log
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Xray-core sidecar engine. Runs the official android binary from
 * nativeLibraryDir with `run -c config.json`; geodata lives in the work dir
 * (XRAY_LOCATION_ASSET). Xray has no control API — node switches and config
 * changes regenerate the file and respawn the process ("重启生效").
 *
 * VPN mode is the same hev-socks5-tunnel bridge as mihomo: Xray only runs a
 * local socks inbound and the TUN is fed to it over 127.0.0.1:<port>.
 * Traffic for the notification comes from the hev bridge's own counters.
 */
class XrayCore(
    private val context: Context,
    private val host: CoreHost,
) : ProxyCore {
    override val kind = CoreKind.XRAY

    private val workDir = File(context.filesDir, "xray").apply { mkdirs() }
    private val configFile = File(workDir, "config.json")

    private var sidecar: SidecarProcess? = null
    private var lastOverrides: CoreOverrides? = null

    private val scope = CoroutineScope(Dispatchers.IO + kotlinx.coroutines.SupervisorJob())
    private var trafficJob: Job? = null

    override suspend fun startup() {
        ensureGeodata()
    }

    /**
     * Xray needs geosite.dat + geoip.dat in the v2fly format next to its
     * config (no download path on a cold start — ship them in assets).
     * geosite.dat is shared with mihomo; geoip.dat is Xray-only (mihomo uses
     * geoip.metadb).
     */
    private fun ensureGeodata() {
        val marker = File(workDir, "geodata.extracted")
        val geosite = File(workDir, "geosite.dat")
        val geoip = File(workDir, "geoip.dat")
        if (marker.isFile && geosite.isFile && geoip.isFile) return
        context.assets.open("geodata/geosite.dat").use { input ->
            geosite.outputStream().use { input.copyTo(it) }
        }
        context.assets.open("geodata/geoip.dat").use { input ->
            geoip.outputStream().use { input.copyTo(it) }
        }
        marker.writeText("1")
        Log.i(TAG, "geodata extracted to $workDir")
    }

    override suspend fun applyConfig(config: String, overrides: CoreOverrides) {
        lastOverrides = overrides
        // Xray has no hot reload — any change means a respawn. The hev bridge
        // (if the VPN is up) keeps running on the same tun fd.
        if (sidecar?.running == true) {
            sidecar?.destroy()
            sidecar = null
        }

        val tunFd = host.openSidecarTun(
            SidecarTunSpec(
                exclusions = routeExclusions(),
                perAppEnabled = overrides.perAppEnabled,
                perAppInclude = overrides.perAppInclude,
                perAppPackages = overrides.perAppPackages,
                allowBypass = Settings.allowBypass,
            ),
        )
        if (tunFd != null && !TProxyService.running) {
            val ok = runCatching { TProxyService.start(context, tunFd, SOCKS_PORT) }.getOrDefault(false)
            if (!ok) {
                Log.e(TAG, "hev tun bridge failed to start")
                AppLog.log("vpn", "hev TUN 桥启动失败")
                error("TUN 桥接启动失败")
            }
            AppLog.log("vpn", "hev TUN 桥已启动 (fd=$tunFd → 127.0.0.1:$SOCKS_PORT)")
        }

        configFile.writeText(config)

        val process = SidecarProcess(
            context,
            "libxray.so",
            listOf("run", "-c", configFile.absolutePath),
            workDir,
            onExit = { code ->
                Log.e(TAG, "xray exited unexpectedly: $code")
                AppLog.log("xray", "进程异常退出 code=$code")
                Holder.instance = null
                host.onCoreRequestStop()
            },
            env = mapOf("XRAY_LOCATION_ASSET" to workDir.absolutePath),
        )
        val spawnAtMs = System.currentTimeMillis()
        process.start()
        sidecar = process
        Holder.instance = this
        AppLog.log("xray", "进程已启动, 等待入站端口就绪…")
        com.interstellar.proxy.data.config.XrayConfigBuilder.skippedReport?.let { AppLog.log("xray", it) }

        // readiness = the socks inbound accepting connections; a config Xray
        // rejects exits the process instead, caught by the exit watcher
        var ready = false
        var socketErr: String? = null
        repeat(READY_POLLS) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", SOCKS_PORT), 600)
                }
                ready = true
                return@repeat
            } catch (e: Exception) {
                socketErr = e.message
            }
            delay(READY_INTERVAL_MS)
        }
        if (!ready) {
            Log.e(TAG, "xray socks inbound unreachable: $socketErr")
            AppLog.log("xray", "入站端口不可达: $socketErr")
            error("Xray 启动超时(详见 ${configFile.parentFile}/libxray.so.log)")
        }
        AppLog.log("xray", "入站就绪 (${"%.1f".format((System.currentTimeMillis() - spawnAtMs) / 1000.0)}s)")
        startTrafficPoller()
    }

    /**
     * hev bridge counters (TUN-level tx/rx) feed the notification; in
     * headless/proxy mode there is no VPN and no traffic to report.
     */
    private fun startTrafficPoller() {
        if (trafficJob?.isActive == true) return
        var lastTx = -1L
        var lastRx = -1L
        trafficJob = scope.launch {
            while (isActive) {
                delay(2000)
                if (!TProxyService.running) continue
                // hev counters: [txPackets, txBytes, rxPackets, rxBytes]
                val stats = runCatching { TProxyService.stats() }.getOrNull() ?: continue
                val tx = stats.getOrElse(1) { 0L }
                val rx = stats.getOrElse(3) { 0L }
                if (lastTx >= 0 && tx >= lastTx && rx >= lastRx) {
                    host.onCoreTraffic((tx - lastTx) / 2, (rx - lastRx) / 2)
                }
                lastTx = tx
                lastRx = rx
            }
        }
    }

    override fun pause() {}
    override fun wake() {}
    override fun needWifiState() = false

    override suspend fun shutdown() {
        Holder.instance = null
        trafficJob?.cancel()
        trafficJob = null
        TProxyService.stop()
        sidecar?.destroy()
        sidecar = null
    }

    /** UI-driven: re-apply whatever ConfigStore holds (node switch etc.). */
    suspend fun restartFromConfigStore() {
        val content = com.interstellar.proxy.data.ConfigStore.readActiveConfig() ?: return
        applyConfig(content, lastOverrides ?: CoreOverrides(false, false, true, emptySet()))
    }

    private fun routeExclusions(): List<String> =
        com.interstellar.proxy.data.config.XrayConfigBuilder.routeExclusions(SubscriptionRepository.activeNodes())

    companion object {
        private const val TAG = "XrayCore"
        /** must match ConfigBuilder.BuildOptions.mixedPort default */
        const val SOCKS_PORT = 2080
        private const val READY_POLLS = 20
        private const val READY_INTERVAL_MS = 500L
    }

    /** Process-wide handle so the UI can restart the running engine. */
    object Holder {
        @Volatile
        var instance: XrayCore? = null
    }
}
