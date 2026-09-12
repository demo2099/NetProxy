package com.interstellar.proxy.core

import io.nekohasekai.libbox.PlatformInterface

/**
 * Engine-agnostic operations the service layer (BoxService) invokes on the
 * active core. Implementations: SingBoxCore (in-process libbox), mihomo /
 * Xray sidecars (Phase 2/3).
 */
interface ProxyCore {
    val kind: CoreKind

    /** Create and start the engine itself (CommandServer / sidecar process). */
    suspend fun startup()

    /** Apply (first start or hot-reload) a generated config. */
    suspend fun applyConfig(config: String, overrides: CoreOverrides)

    /** Doze pause / resume. No-op for engines without the concept. */
    fun pause()

    fun wake()

    /** Tear the engine down completely before the Android service stops. */
    suspend fun shutdown()

    /** Whether the core needs WIFI-state location permission. */
    fun needWifiState(): Boolean
}

/**
 * Neutral start-time overrides; each core maps them onto its own mechanism
 * (sing-box: OverrideOptions; sidecars: applied by the VPN builder instead).
 */
data class CoreOverrides(
    val autoRedirect: Boolean,
    val perAppEnabled: Boolean,
    val perAppInclude: Boolean,
    val perAppPackages: Set<String>,
    /** Tag the UI wants selected (mihomo applies it via Clash API post-start). */
    val selectedTag: String? = null,
)

/** Neutral system-proxy state (BoxService ↔ core, decoupled from libbox types). */
data class SystemProxyState(val available: Boolean, val enabled: Boolean)

/** Tun spec a sidecar core needs before spawning (fd inheritance). */
data class SidecarTunSpec(
    /** Hosts/IPs to exclude from VPN routes (node servers + DNS upstreams). */
    val exclusions: List<String>,
    val perAppEnabled: Boolean,
    val perAppInclude: Boolean,
    val perAppPackages: Set<String>,
    val allowBypass: Boolean,
    val mtu: Int = 9000,
)

/** Core → service callbacks. */
interface CoreHost {
    /** Core dropped the tun / crashed and wants the Android service torn down. */
    fun onCoreRequestStop()

    /** Core wants the active config re-applied (e.g. system proxy toggled). */
    fun onCoreRequestReload()

    fun systemProxyState(): SystemProxyState?

    fun onSetSystemProxy(enabled: Boolean)

    /**
     * Establish a VPN tun for a sidecar core and return its (CLOEXEC-cleared)
     * fd for config embedding; null when not in VPN mode.
     */
    fun openSidecarTun(spec: SidecarTunSpec): Int? = null

    /** Per-second traffic sample for the persistent notification. */
    fun onCoreTraffic(upPerSecond: Long, downPerSecond: Long) {}
}

object CoreEngines {
    /**
     * Unknown / not-yet-shipped kinds fall back to sing-box so a stale
     * settings file can never brick the service.
     */
    fun create(
        kind: CoreKind,
        platformInterface: PlatformInterface,
        host: CoreHost,
    ): ProxyCore =
        when (kind) {
            CoreKind.SINGBOX -> SingBoxCore(platformInterface, host)
            CoreKind.MIHOMO -> MihomoCore(com.interstellar.proxy.InterstellarApplication.application, host)
            CoreKind.XRAY -> {
                android.util.Log.w("CoreEngines", "core xray not available yet, using sing-box")
                SingBoxCore(platformInterface, host)
            }
        }
}
