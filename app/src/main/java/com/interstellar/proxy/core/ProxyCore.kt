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
)

/** Neutral system-proxy state (BoxService ↔ core, decoupled from libbox types). */
data class SystemProxyState(val available: Boolean, val enabled: Boolean)

/** Core → service callbacks. */
interface CoreHost {
    /** Core dropped the tun / crashed and wants the Android service torn down. */
    fun onCoreRequestStop()

    /** Core wants the active config re-applied (e.g. system proxy toggled). */
    fun onCoreRequestReload()

    fun systemProxyState(): SystemProxyState?

    fun onSetSystemProxy(enabled: Boolean)
}

object CoreEngines {
    /**
     * Phase 1 only knows sing-box; unknown kinds fall back to it so a stale
     * settings file can never brick the service. Phase 2/3 add sidecars here.
     */
    fun create(
        kind: CoreKind,
        platformInterface: PlatformInterface,
        host: CoreHost,
    ): ProxyCore =
        when (kind) {
            CoreKind.SINGBOX -> SingBoxCore(platformInterface, host)
            CoreKind.MIHOMO,
            CoreKind.XRAY,
            -> {
                android.util.Log.w("CoreEngines", "core ${kind.wire} not available yet, using sing-box")
                SingBoxCore(platformInterface, host)
            }
        }
}
