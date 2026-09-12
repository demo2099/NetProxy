package com.interstellar.proxy.core

import android.content.Context
import android.util.Log
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
        // nothing to pre-create beyond the work dir; process spawns on first applyConfig
    }

    override suspend fun applyConfig(config: String, overrides: CoreOverrides) {
        lastOverrides = overrides
        if (sidecar?.running == true) {
            // hot reload: rewrite the file, then ask mihomo to re-read it
            configFile.writeText(materialize(config))
            if (!api.reload(configFile.absolutePath)) {
                // API unreachable → process died between checks; fall through to respawn
                sidecar?.destroy()
            } else {
                applySelection(overrides)
                return
            }
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
        activeTunFd = tunFd

        configFile.writeText(materialize(config))

        val process = SidecarProcess(context, "libmihomo.so", listOf("-d", workDir.absolutePath, "-f", configFile.absolutePath), workDir) { code ->
            Log.e(TAG, "mihomo exited unexpectedly: $code")
            activeTunFd = null
            Holder.instance = null
            host.onCoreRequestStop()
        }
        process.start()
        sidecar = process
        Holder.instance = this

        // wait for the REST API to come up (config parse + geodata init)
        var ready = false
        repeat(READY_POLLS) {
            if (api.version() != null) {
                ready = true
                return@repeat
            }
            delay(READY_INTERVAL_MS)
        }
        if (!ready) {
            Log.e(TAG, "mihomo API not ready after ${READY_POLLS * READY_INTERVAL_MS}ms")
            error("mihomo 启动超时(详见 ${configFile.parentFile}/libmihomo.so.log)")
        }
        applySelection(overrides)
    }

    override fun pause() {}
    override fun wake() {}
    override fun needWifiState() = false

    override suspend fun shutdown() {
        Holder.instance = null
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

    /** Bake the tun state (fd / strip) into the generated config text. */
    private fun materialize(config: String): String {
        val fd = activeTunFd ?: return stripTun(config)
        return config.replaceFirst(
            Regex("(file-descriptor:)\\s*0(\\s*)"),
            "$1 $fd$2",
        )
    }

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
        private const val READY_POLLS = 20
        private const val READY_INTERVAL_MS = 500L
    }

    /** Process-wide handle so the UI can hot-reload the running engine. */
    object Holder {
        @Volatile
        var instance: MihomoCore? = null
    }
}
