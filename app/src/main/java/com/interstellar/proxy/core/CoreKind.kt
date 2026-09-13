package com.interstellar.proxy.core

import com.interstellar.proxy.BuildConfig

/**
 * Switchable proxy cores. sing-box runs in-process via libbox; mihomo / Xray
 * run as sidecar processes from nativeLibraryDir (see SidecarProcess).
 */
enum class CoreKind(val wire: String, val displayName: String) {
    SINGBOX("singbox", "sing-box"),
    MIHOMO("mihomo", "mihomo"),
    XRAY("xray", "Xray");

    companion object {
        /**
         * Cores packaged in *this* flavor. app/build.gradle.kts injects the list
         * as BuildConfig.CORE_KINDS:
         *
         *   slim -> "singbox"              no sidecar binaries at all
         *   full -> "singbox,mihomo,xray"  app/src/full/ ships the .so + geodata
         *
         * Both the picker and [from] are restricted to this list, so a settings
         * value saved by a `full` build (e.g. "mihomo") degrades to sing-box
         * when the same user runs a `slim` build, instead of trying to launch a
         * sidecar that was never packaged.
         *
         * `by lazy` so it is never evaluated while the enum constants are still
         * being initialised.
         */
        val available: List<CoreKind> by lazy {
            BuildConfig.CORE_KINDS
                .split(',')
                .mapNotNull { wire -> entries.find { it.wire == wire.trim() } }
                .ifEmpty { listOf(SINGBOX) }
        }

        fun from(value: String?): CoreKind = available.find { it.wire == value } ?: SINGBOX
    }
}
