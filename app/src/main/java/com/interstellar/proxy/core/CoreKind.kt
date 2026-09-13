package com.interstellar.proxy.core

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
         * Cores actually packaged in this build.
         *
         * This fork ships sing-box only: the mihomo / Xray sidecars
         * (libmihomo.so, libxray.so, libhev.so) and their v2fly geodata are no
         * longer built or bundled, so both the picker and [from] are limited to
         * sing-box. That also means a stale saved value like "mihomo" degrades
         * to sing-box instead of trying to launch a sidecar that isn't there.
         *
         * To bring a sidecar back: add it here, run
         * `INTERSTELLAR_WITH_SIDECARS=1 python tools/fetch_cores.py`, and
         * restore app/src/main/assets/geodata (git history has it).
         */
        val available: List<CoreKind> = listOf(SINGBOX)

        fun from(value: String?): CoreKind = available.find { it.wire == value } ?: SINGBOX
    }
}
