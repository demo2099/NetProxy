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
        fun from(value: String?): CoreKind = entries.find { it.wire == value } ?: SINGBOX
    }
}
