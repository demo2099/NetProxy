package com.interstellar.proxy.core

/**
 * Neutral outbound-group DTOs consumed by the UI (nodes page, dashboard).
 * sing-box fills them from libbox's CommandClient snapshots; mihomo from the
 * Clash REST API — the UI never touches engine types.
 */
data class CoreGroup(
    val tag: String,
    val type: String,
    val selected: String?,
    val items: List<CoreGroupItem>,
)

data class CoreGroupItem(
    val tag: String,
    val type: String,
    val urlTestDelay: Int = 0,
    val urlTestTime: Long = 0L,
)

/** Live traffic sample for the dashboard (speed + totals). */
data class CoreTraffic(
    val uplinkPerSecond: Long,
    val downlinkPerSecond: Long,
    val uplinkTotal: Long,
    val downlinkTotal: Long,
)

/** Which live features an engine provides to the UI. */
enum class CoreCapability { HOT_SWITCH, URLTEST, CONNECTIONS, LIVE_GROUPS }

val CoreKind.capabilities: Set<CoreCapability>
    get() = when (this) {
        CoreKind.SINGBOX -> setOf(CoreCapability.HOT_SWITCH, CoreCapability.URLTEST, CoreCapability.CONNECTIONS, CoreCapability.LIVE_GROUPS)
        CoreKind.MIHOMO -> setOf(CoreCapability.HOT_SWITCH, CoreCapability.URLTEST, CoreCapability.CONNECTIONS, CoreCapability.LIVE_GROUPS)
        CoreKind.XRAY -> emptySet()
    }
