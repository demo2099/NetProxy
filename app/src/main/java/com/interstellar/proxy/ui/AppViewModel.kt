package com.interstellar.proxy.ui

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.CustomRulesStore
import com.interstellar.proxy.data.DnsOverridesStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.data.model.CustomRouteRule
import com.interstellar.proxy.data.model.DnsOverrideEntry
import com.interstellar.proxy.data.net.SubscriptionFetcher
import com.interstellar.proxy.data.subscription.SubscriptionParser
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OutboundGroup
import io.nekohasekai.libbox.StatusMessage
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.ClashApiClient
import com.interstellar.proxy.core.CoreGroup
import com.interstellar.proxy.core.CoreGroupItem
import com.interstellar.proxy.core.CoreKind
import com.interstellar.proxy.core.DirectPing
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.SecureRandom

data class SpeedState(
    val uplinkPerSecond: Long = 0,
    val downlinkPerSecond: Long = 0,
    val uplinkTotal: Long = 0,
    val downlinkTotal: Long = 0,
)

data class ClashModeState(
    val modes: List<String> = emptyList(),
    val current: String = "rule",
)

data class SplitRuleStatus(
    val hasEnabledRules: Boolean = false,
    /** Master switch on the nodes page. Independent of auto vs a locked node. */
    val masterEnabled: Boolean = true,
    /** Keyword-filter rules are actually in the running/generated config. */
    val active: Boolean = false,
)

/** Exit-IP probe lifecycle for the dashboard 网络探测 card. */
sealed interface ProbeState {
    data object Idle : ProbeState
    data object Running : ProbeState
    data class Done(val result: com.interstellar.proxy.data.net.NetProbe.Result) : ProbeState
    data class Failed(val message: String) : ProbeState
}

/** Transient feedback pill (subscription updates etc.). */
data class UiToast(val text: String, val kind: Kind) {
    enum class Kind { Success, Error }
}

/**
 * App-wide state holder: box status via the local command socket,
 * subscriptions, and start/stop orchestration.
 */
class AppViewModel(application: Application) : AndroidViewModel(application) {
    private val _status = MutableStateFlow(Status.Stopped)
    val status: StateFlow<Status> = _status

    private val _connectedAt = MutableStateFlow(0L)
    val connectedAt: StateFlow<Long> = _connectedAt

    private val _speed = MutableStateFlow(SpeedState())
    val speed: StateFlow<SpeedState> = _speed

    /** Per-second (down, up) samples for the live chart, newest last. */
    private val _history = MutableStateFlow<List<Pair<Long, Long>>>(emptyList())
    val history: StateFlow<List<Pair<Long, Long>>> = _history

    private val _clashMode = MutableStateFlow(ClashModeState())
    val clashMode: StateFlow<ClashModeState> = _clashMode

    /**
     * Routing mode as the UI sees it ("rule" | "global" | "direct"). The mode
     * is BAKED into the generated config (route.final / CN bypass rules), so
     * this flow is driven by the persisted setting plus optimistic updates —
     * the core's clash-mode callback alone can't reflect it.
     */
    private val _routingMode = MutableStateFlow(Settings.outboundMode.name.lowercase())
    val routingMode: StateFlow<String> = _routingMode

    private val _groups = MutableStateFlow<List<CoreGroup>>(emptyList())
    val groups: StateFlow<List<CoreGroup>> = _groups

    /** Observable engine kind — drives the dashboard core segmented control. */
    private val _coreKind = MutableStateFlow(Settings.coreKind)
    val coreKind: StateFlow<CoreKind> = _coreKind

    /** Active connection count from the mihomo API (dashboard core card). */
    private val _mihomoConnectionCount = MutableStateFlow(0)
    val mihomoConnectionCount: StateFlow<Int> = _mihomoConnectionCount

    /** App-proxy scope as the UI sees it (drives the dashboard status row). */
    data class ProxyScope(val whitelist: Boolean, val count: Int) {
        val label: String
            get() = when {
                !com.interstellar.proxy.data.Settings.perAppProxyEnabled -> "全部应用"
                whitelist -> "白名单 · $count"
                else -> "黑名单 · $count"
            }
    }

    private val _proxyScope = MutableStateFlow(readProxyScope())
    val proxyScope: StateFlow<ProxyScope> = _proxyScope

    private fun readProxyScope() = ProxyScope(
        whitelist = Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE,
        count = Settings.perAppProxyList.size,
    )

    fun setProxyScope(mode: String) {
        when (mode) {
            "all" -> Settings.perAppProxyEnabled = false
            "whitelist" -> {
                Settings.perAppProxyMode = Settings.PER_APP_PROXY_INCLUDE
                Settings.perAppProxyEnabled = true
            }

            "blacklist" -> {
                Settings.perAppProxyMode = Settings.PER_APP_PROXY_EXCLUDE
                Settings.perAppProxyEnabled = true
            }
        }
        _proxyScope.value = readProxyScope()
        refreshProxyConfig()
    }

    // ---- simple routing rules ----

    private val _simpleRules = MutableStateFlow(com.interstellar.proxy.data.SimpleRulesStore.rules.toList())
    val simpleRules: StateFlow<List<com.interstellar.proxy.data.SimpleRouteRule>> = _simpleRules

    fun upsertSimpleRule(rule: com.interstellar.proxy.data.SimpleRouteRule) {
        com.interstellar.proxy.data.SimpleRulesStore.upsert(rule)
        _simpleRules.value = com.interstellar.proxy.data.SimpleRulesStore.rules.toList()
        refreshProxyConfig()
    }

    fun removeSimpleRule(id: String) {
        com.interstellar.proxy.data.SimpleRulesStore.remove(id)
        _simpleRules.value = com.interstellar.proxy.data.SimpleRulesStore.rules.toList()
        refreshProxyConfig()
    }

    fun setSimpleRuleEnabled(id: String, enabled: Boolean) {
        com.interstellar.proxy.data.SimpleRulesStore.setEnabled(id, enabled)
        _simpleRules.value = com.interstellar.proxy.data.SimpleRulesStore.rules.toList()
        refreshProxyConfig()
    }

    /** id/tag/name triples of the current node pool for the rule picker. */
    fun nodePickerEntries(): List<Triple<String, String, String>> {
        val pool = SubscriptionRepository.poolOf(
            _subscriptions.value,
            _activeSubscriptionId.value,
            _mixEnabled.value,
            _mixSubscriptionIds.value,
        )
        return ConfigBuilder.tagsFor(pool).zip(pool) { tag, node -> Triple(node.id, tag, node.name) }
    }

    /** tag → latest url-test delay (pushed via the outbounds stream). */
    private val _delays = MutableStateFlow<Map<String, Int>>(emptyMap())
    val delays: StateFlow<Map<String, Int>> = _delays

    /** True while a group url-test is in flight (for UI spinners). */
    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing

    /** Batch test progress (done, total); null while idle. */
    private val _testProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val testProgress: StateFlow<Pair<Int, Int>?> = _testProgress

    private val _pingProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    val pingProgress: StateFlow<Pair<Int, Int>?> = _pingProgress

    /** Epoch seconds when the current url-test run started (0 = idle). */
    @Volatile private var testStartEpoch = 0L

    /** Serializes kernel url-test runs (manual button vs smart engine). */
    private val kernelUrlTestMutex = kotlinx.coroutines.sync.Mutex()

    /** True while the in-flight kernel url-test run belongs to the manual button. */
    @Volatile private var kernelTestManual = false

    /**
     * Results already reported in the current run, and when the newest one
     * landed. Drives the idle-settle: a run is done once results stop coming
     * in, not once every member has reported — the kernel silently skips some
     * members, and waiting for those costs the full 25s cap.
     */
    @Volatile private var testDone = 0
    @Volatile private var testLastResultAt = 0L

    /**
     * tag → kernel url-test stamp (epoch seconds), mirrored from the outbounds
     * stream. A single-node test can't use "did the delay value change" to
     * detect completion: retesting a dead node yields 65535 again, so the
     * value is identical and the caller would spin until the cap.
     */
    private val testStamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** sing-box's per-node url-test HTTP timeout (constant.TCPTimeout, hardcoded in the kernel). */
    private val PER_NODE_TEST_TIMEOUT_MS = 15_000L

    private val _subscriptions =
        MutableStateFlow(SubscriptionRepository.subscriptions.toList())
    val subscriptions: StateFlow<List<SubscriptionRepository.Subscription>> = _subscriptions

    private val _activeSubscriptionId =
        MutableStateFlow(SubscriptionRepository.activeSubscriptionId)
    val activeSubscriptionId: StateFlow<String> = _activeSubscriptionId

    /** Mix: node pool = union of the checked subscriptions. */
    private val _mixEnabled = MutableStateFlow(Settings.mixEnabled)
    val mixEnabled: StateFlow<Boolean> = _mixEnabled

    private val _mixSubscriptionIds = MutableStateFlow(Settings.mixSubscriptionIds)
    val mixSubscriptionIds: StateFlow<Set<String>> = _mixSubscriptionIds

    private val _selectedOutboundTag = MutableStateFlow(Settings.selectedOutboundTag)
    val selectedOutboundTag: StateFlow<String> = _selectedOutboundTag

    /** Nodes page layout: grid (default) or list; persisted. */
    private val _nodesGridView = MutableStateFlow(Settings.nodesGridView)
    val nodesGridView: StateFlow<Boolean> = _nodesGridView

    fun setNodesGridView(value: Boolean) {
        if (value == _nodesGridView.value) return
        Settings.nodesGridView = value
        _nodesGridView.value = value
    }

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** True while a subscription URL/text import is in flight (dialog spinner). */
    private val _addingSub = MutableStateFlow(false)
    val addingSub: StateFlow<Boolean> = _addingSub

    private var addSubJob: kotlinx.coroutines.Job? = null

    /** Last import failure, surfaced INSIDE the add dialog (modal covers page toasts). */
    private val _addSubError = MutableStateFlow<String?>(null)
    val addSubError: StateFlow<String?> = _addSubError

    // ---- smart switch (智能模式) ----

    private val _smartState = MutableStateFlow(SmartSwitchEngine.SmartState())
    val smartState: StateFlow<SmartSwitchEngine.SmartState> = _smartState

    val smartEngine: SmartSwitchEngine by lazy {
        SmartSwitchEngine(
            isActive = {
                _status.value == Status.Started &&
                    Settings.selectedOutboundTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG
            },
            currentTag = { smartCurrentTag() },
            socksPort = { com.interstellar.proxy.core.LocalPort.current },
            useSocksProxy = { Settings.coreKind == CoreKind.XRAY },
            requestKernelDelays = { requestKernelGroupDelays() },
            applySwitch = { tag -> applySmartSwitch(tag) },
            pool = {
                SubscriptionRepository.poolOf(
                    _subscriptions.value,
                    _activeSubscriptionId.value,
                    _mixEnabled.value,
                    _mixSubscriptionIds.value,
                )
            },
            // 智能行为细节走 AppLog("smart") 在日志页呈现; 严重状态由首页 SmartStatusLine(state.alert) 呈现
            onStateChanged = { _smartState.value = it },
        )
    }

    /** The node smart mode currently rides on (falls back to the live group selection). */
    private fun smartCurrentTag(): String? {
        Settings.smartActiveTag.takeIf { it.isNotBlank() }?.let { return it }
        return _groups.value.find { it.tag == GROUP_TAG }?.selected?.takeIf { it.isNotBlank() }
    }

    /** sing-box/mihomo: group url-test + settle; Xray has no API → null. */
    private suspend fun requestKernelGroupDelays(): Map<String, Int>? {
        return when (Settings.coreKind) {
            CoreKind.SINGBOX -> {
                if (!runKernelUrlTest(manual = false)) return null
                _delays.value
            }

            CoreKind.MIHOMO -> {
                clashApi.groupDelay(ConfigBuilder.AUTO_TAG) ?: return null
                _delays.value
            }

            CoreKind.XRAY -> null
        }
    }

    /** Hot-switch the running core; persists smartActiveTag + drops old connections on success. */
    private suspend fun applySmartSwitch(tag: String): Boolean {
        val ok = when (Settings.coreKind) {
            CoreKind.MIHOMO -> runCatching { clashApi.select(ConfigBuilder.GROUP_TAG, tag) }.isSuccess

            CoreKind.SINGBOX -> runCatching {
                CommandTarget.standaloneClient().selectOutbound(ConfigBuilder.GROUP_TAG, tag)
            }.isSuccess

            CoreKind.XRAY -> {
                Settings.smartActiveTag = tag
                SubscriptionRepository.regenerateActiveConfig()
                runCatching {
                    com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                }.isSuccess
            }
        }
        if (ok) {
            Settings.smartActiveTag = tag
            // Same reasoning as the manual switch in selectNode(): a selector
            // change only affects connections created *after* it. Established
            // TCP sessions (browser keep-alive, HTTP2, IM long-polls) stay
            // pinned to the old node, so an auto-switch that doesn't drop them
            // looks like it never happened — which is the whole point of the
            // feature.
            //
            // Safe to do on the auto path because the engine only reselects
            // when the current exit is *already* unhealthy (patrol latency >
            // GOOD_MS, or the exit is dead): those connections weren't working
            // anyway, so interrupting them costs nothing real.
            //
            // Xray's switch-verify loop calls this up to VERIFY_ATTEMPTS times,
            // but there dropConnections() is a no-op (no control API) and every
            // attempt already respawns the core.
            dropConnections()
        }
        return ok
    }

    /** User picked 智能 in the node page: mark mode, bake config, kick the engine. */
    fun selectSmartMode() {
        if (Settings.selectedOutboundTag == ConfigBuilder.SMART_TAG) return
        Settings.selectedOutboundTag = ConfigBuilder.SMART_TAG
        _selectedOutboundTag.value = ConfigBuilder.SMART_TAG
        refreshSplitRuleStatus()
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                // hot-apply the effective selection (smartActiveTag / auto)
                Settings.smartActiveTag.takeIf { it.isNotBlank() }?.let { applySmartSwitch(it) }
            } else {
                SubscriptionRepository.regenerateActiveConfig()
            }
            smartEngine.start()
        }
    }

    /** Abort an in-flight subscription import (dialog 取消). */
    fun cancelAddSubscription() {
        addSubJob?.cancel()
        addSubJob = null
        _addingSub.value = false
        _addSubError.value = null
        _message.value = "已取消导入"
    }

    /** True while any subscription refresh triggered by pull-to-refresh runs. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _probe = MutableStateFlow<ProbeState>(ProbeState.Idle)
    val probe: StateFlow<ProbeState> = _probe

    private val _toast = MutableStateFlow<UiToast?>(null)
    val toast: StateFlow<UiToast?> = _toast

    private var toastClearJob: Job? = null

    /** True while the concurrent TCP ping sweep runs. */
    private val _pinging = MutableStateFlow(false)
    val pinging: StateFlow<Boolean> = _pinging

    /**
     * Tag of the node a single-node test is currently running on, null otherwise.
     * 单节点测速只锁自己那一个节点，不占用整池的 [testing]/[pinging]，
     * 否则在详情页点一下就会把列表顶部的批量进度条也点着。
     */
    private val _nodeTesting = MutableStateFlow<String?>(null)
    val nodeTesting: StateFlow<String?> = _nodeTesting

    private val delaysMutex = kotlinx.coroutines.sync.Mutex()

    fun showToast(text: String, kind: UiToast.Kind) {
        _toast.value = UiToast(text, kind)
        toastClearJob?.cancel()
        toastClearJob = viewModelScope.launch {
            delay(2800)
            _toast.value = null
        }
    }

    private val _customRules = MutableStateFlow(CustomRulesStore.rules.toList())
    val customRules: StateFlow<List<CustomRouteRule>> = _customRules

    private val _dnsOverrides = MutableStateFlow(DnsOverridesStore.entries.toList())
    val dnsOverrides: StateFlow<List<DnsOverrideEntry>> = _dnsOverrides

    private val _splitRuleStatus = MutableStateFlow(computeSplitRuleStatus())
    val splitRuleStatus: StateFlow<SplitRuleStatus> = _splitRuleStatus

    private var pollJob: Job? = null
    private var startingWatchdog: Job? = null
    private var stoppedReceiver: BroadcastReceiver? = null
    private var autoTested = false
    @Volatile private var probing = false

    /** Set by onConnected while probing: the headless command socket is up. */
    @Volatile private var probeSocketUp = false

    private companion object {
        const val GROUP_TAG = "proxy"
        const val STARTING_TIMEOUT_MS = 15_000L
        const val SOCKET_DROP_TIMEOUT_MS = 400L

        /** Sentinel delay for timed-out / unreachable nodes (ping & url-test). */
        const val TIMEOUT_DELAY = 65535

        /**
         * No new url-test result for this long ⇒ the run is over. Results
         * stream in concurrently within a few hundred ms of each other, so a
         * quiet gap this wide only happens when the kernel has nothing left
         * to report (dead nodes report at the 15s kernel timeout instead).
         */
        const val SETTLE_QUIET_MS = 2_500L
    }

    private val commandClient = CommandClient(
        viewModelScope,
        listOf(
            CommandClient.ConnectionType.Status,
            CommandClient.ConnectionType.Groups,
            CommandClient.ConnectionType.ClashMode,
            // delay updates stream through outbounds, not the groups snapshot
            CommandClient.ConnectionType.Outbounds,
        ),
        object : CommandClient.Handler {
            override fun onConnected() {
                if (probing) {
                    // headless probe: don't promote the UI to connected, just
                    // flag that the command socket is reachable
                    probeSocketUp = true
                    return
                }
                // App relaunch while the core is already up.
                if (_status.value == Status.Stopped) {
                    markStarted()
                }
            }

            override fun onDisconnected() {
                autoTested = false
                if (!probing) applySocketDrop()
            }

            override fun onConnectionError(kind: CommandClient.ConnectionErrorKind, message: String) {
                if (!probing) applySocketDrop()
            }

            override fun updateStatus(status: StatusMessage) {
                if (probing) return
                // Status ticks only after the tun is actually running — this is
                // what promotes Starting → Started, so a socket bounce during
                // boot cannot flash Stopped.
                if (_status.value == Status.Starting) {
                    markStarted()
                }
                _speed.value = SpeedState(
                    uplinkPerSecond = status.uplink,
                    downlinkPerSecond = status.downlink,
                    uplinkTotal = status.uplinkTotal,
                    downlinkTotal = status.downlinkTotal,
                )
                val sample = status.downlink to status.uplink
                _history.value = (_history.value + sample).takeLast(60)
            }

            override fun updateGroups(newGroups: MutableList<OutboundGroup>) {
                _groups.value = newGroups.map(::convertGroup)
                refreshSplitRuleStatus()
            }

            override fun updateOutbounds(outbounds: List<io.nekohasekai.libbox.OutboundGroupItem>) {
                val map = _delays.value.toMutableMap()
                for (item in outbounds) {
                    val prevStamp = testStamps[item.tag] ?: 0L
                    val incoming = item.urlTestTime
                    // ★ 这个回调里混着 selector 的成员快照：它们的
                    //   urlTestTime / urlTestDelay 永远是 0。无条件写 map 会把
                    //   「单节点测速刚测出来的延迟」和「批量测速回填的超时」
                    //   一起冲掉 —— 用户看到的就是「测了另一个，前一个结果
                    //   就消失」「批量测速还是有未测」。
                    //   只有内核真的产生了新结果（时间戳前进）才覆盖。
                    if (incoming > prevStamp) {
                        map[item.tag] = item.urlTestDelay
                        testStamps[item.tag] = incoming
                    } else if (!map.containsKey(item.tag) && item.urlTestDelay > 0) {
                        // 首次见到且内核已有有效值（连接时自动测过），收下
                        map[item.tag] = item.urlTestDelay
                    }
                }
                _delays.value = map
                // url-test progress: count results stamped after this run
                // started. Only the manual run owns the UI state — smart
                // runs settle silently underneath.
                if (testStartEpoch > 0) {
                    val done = outbounds.count { it.urlTestTime >= testStartEpoch }
                    if (done > testDone) {
                        testDone = done
                        testLastResultAt = System.currentTimeMillis()
                    }
                    val prevTotal = _testProgress.value?.second ?: 0
                    val total = maxOf(prevTotal, outbounds.size)
                    if (total > 0) {
                        if (done >= total) {
                            testStartEpoch = 0
                            if (kernelTestManual) {
                                _testProgress.value = null
                                _testing.value = false
                            }
                        } else if (kernelTestManual) {
                            _testProgress.value = done.coerceAtMost(total) to total
                        }
                    }
                }
                // NB: snapshots can arrive at any moment (smart rounds,
                // delayed pushes) and must never kill an in-flight run —
                // the settle branch above and the callers' finally own the
                // testing state.
            }

            override fun initializeClashMode(modeList: List<String>, currentMode: String) {
                _clashMode.value = ClashModeState(modeList, currentMode)
                normalizeRoutingMode(currentMode)?.let { _routingMode.value = it }
            }

            override fun updateClashMode(newMode: String) {
                _clashMode.value = _clashMode.value.copy(current = newMode)
                normalizeRoutingMode(newMode)?.let { _routingMode.value = it }
            }
        },
    )

    private fun normalizeRoutingMode(mode: String): String? =
        mode.lowercase().takeIf { it == "rule" || it == "global" || it == "direct" }

    // ---- mihomo live bridge (Clash REST → same state the libbox client feeds) ----

    private val clashApi by lazy { ClashApiClient(Settings.apiSecret) }
    private var mihomoJob: Job? = null
    private var mihomoLastDown = -1L
    private var mihomoLastUp = -1L

    /** libbox group snapshot → neutral CoreGroup. */
    private fun convertGroup(group: OutboundGroup): CoreGroup {
        val iterator = group.items
        val items = mutableListOf<CoreGroupItem>()
        while (iterator.hasNext()) {
            val item = iterator.next()
            items.add(CoreGroupItem(item.tag, item.type, item.urlTestDelay, item.urlTestTime))
        }
        return CoreGroup(
            tag = group.tag,
            type = group.type,
            selected = group.selected?.takeIf { it.isNotBlank() },
            items = items,
        )
    }

    private fun startMihomoBridge() {
        if (mihomoJob?.isActive == true) return
        mihomoJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> runCatching { pollMihomoOnce() }
                    CoreKind.XRAY -> runCatching { pollXrayOnce() }
                    CoreKind.SINGBOX -> Unit
                }
                delay(2000)
            }
        }
    }

    /**
     * Xray has no control API — the live bridge only carries the hev TUN
     * counters (traffic card) and promotes Starting → Started once the
     * inbound is up; node/delay state stays pool-driven.
     */
    private fun pollXrayOnce() {
        if (probing) return
        if (_status.value == Status.Starting) markStarted()
        if (!com.interstellar.proxy.core.TProxyService.running) return
        // hev counters: [txPackets, txBytes, rxPackets, rxBytes]
        val stats = com.interstellar.proxy.core.TProxyService.stats() ?: return
        val tx = stats.getOrElse(1) { 0L }
        val rx = stats.getOrElse(3) { 0L }
        if (mihomoLastDown >= 0 && rx >= mihomoLastDown && tx >= mihomoLastUp) {
            _speed.value = SpeedState(
                uplinkPerSecond = (tx - mihomoLastUp) / 2,
                downlinkPerSecond = (rx - mihomoLastDown) / 2,
                uplinkTotal = tx,
                downlinkTotal = rx,
            )
            _history.value =
                (_history.value + ((rx - mihomoLastDown) / 2 to (tx - mihomoLastUp) / 2)).takeLast(60)
        }
        mihomoLastDown = rx
        mihomoLastUp = tx
    }

    private suspend fun pollMihomoOnce() {
        if (clashApi.version() == null) return
        if (probing) return
        if (_status.value == Status.Starting) markStarted()

        clashApi.proxies()?.let { proxies ->
            // ★ 合并而不是整体替换：proxies 里只有「有 history」的节点才带
            //   delay，没测过的节点会整条缺席。直接 `_delays = delays` 会把
            //   单节点刚测出来的结果一起抹掉。
            val delays = _delays.value.toMutableMap()
            for ((name, v) in proxies) {
                val history = v.jsonObject["history"]?.let { h ->
                    (h as? kotlinx.serialization.json.JsonArray)?.lastOrNull()?.jsonObject
                }
                history?.get("delay")?.jsonPrimitive?.content?.toIntOrNull()?.let { delays[name] = it }
            }
            _delays.value = delays
            val groups = proxies.values.mapNotNull { entry ->
                val obj = entry.jsonObject
                val tag = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                if (tag in setOf("GLOBAL", "DIRECT", "REJECT", "COMPATIBLE", "PASS")) return@mapNotNull null
                val all = obj["all"] as? kotlinx.serialization.json.JsonArray ?: return@mapNotNull null
                if (all.isEmpty()) return@mapNotNull null
                CoreGroup(
                    tag = tag,
                    type = obj["type"]?.jsonPrimitive?.content ?: "Selector",
                    selected = obj["now"]?.jsonPrimitive?.content,
                    items = all.map { nameEl ->
                        val name = nameEl.jsonPrimitive.content
                        CoreGroupItem(name, proxies[name]?.jsonObject?.get("type")?.jsonPrimitive?.content ?: "", delays[name] ?: 0, 0L)
                    },
                )
            }
            _groups.value = groups
            refreshSplitRuleStatus()
        }

        clashApi.connections()?.let { conn ->
            (conn["connections"] as? kotlinx.serialization.json.JsonArray)?.let {
                _mihomoConnectionCount.value = it.size
            }
            val down = conn["downloadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let
            val up = conn["uploadTotal"]?.jsonPrimitive?.content?.toLongOrNull() ?: return@let
            if (mihomoLastDown >= 0 && down >= mihomoLastDown && up >= mihomoLastUp) {
                _speed.value = SpeedState(
                    uplinkPerSecond = (up - mihomoLastUp) / 2,
                    downlinkPerSecond = (down - mihomoLastDown) / 2,
                    uplinkTotal = up,
                    downlinkTotal = down,
                )
                _history.value = (_history.value + ((down - mihomoLastDown) / 2 to (up - mihomoLastUp) / 2)).takeLast(60)
            }
            mihomoLastDown = down
            mihomoLastUp = up
        }
    }

    init {
        ensureApiSecret()
    }

    fun connect() {
        // libbox command socket only exists for the sing-box engine
        if (Settings.coreKind == CoreKind.SINGBOX) commandClient.connect()
        startMihomoBridge()
        registerStoppedReceiver()
        if (Settings.selectedOutboundTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
            smartEngine.start()
        }
        // poll-reconnect: the box may start/stop at any time, and a failed
        // connect (server not up yet) must be retried to reflect the state
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(2000)
                if (_status.value == Status.Starting || _status.value == Status.Stopped) {
                    if (Settings.coreKind == CoreKind.SINGBOX) commandClient.connect()
                }
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        pollJob = null
        mihomoJob?.cancel()
        mihomoJob = null
        mihomoLastDown = -1L
        mihomoLastUp = -1L
        startingWatchdog?.cancel()
        startingWatchdog = null
        unregisterStoppedReceiver()
        commandClient.disconnect()
    }

    private fun registerStoppedReceiver() {
        if (stoppedReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == Action.SERVICE_STOPPED) markStopped()
            }
        }
        stoppedReceiver = receiver
        ContextCompat.registerReceiver(
            getApplication(),
            receiver,
            IntentFilter(Action.SERVICE_STOPPED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun unregisterStoppedReceiver() {
        val receiver = stoppedReceiver ?: return
        stoppedReceiver = null
        runCatching { getApplication<Application>().unregisterReceiver(receiver) }
    }

    private fun ensureApiSecret() {
        if (Settings.apiSecret.isBlank()) {
            val bytes = ByteArray(16)
            SecureRandom().nextBytes(bytes)
            Settings.apiSecret = android.util.Base64.encodeToString(
                bytes,
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP,
            )
        }
    }

    private fun markStarted() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        if (_status.value == Status.Started) return
        _status.value = Status.Started
        _connectedAt.value = System.currentTimeMillis()
        _probe.value = ProbeState.Idle
        Settings.tileActive = true
        if (!autoTested) {
            autoTested = true
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { CommandTarget.standaloneClient().urlTest(ConfigBuilder.AUTO_TAG) }
            }
            // once the url-test settles, refresh the exit-IP card
            viewModelScope.launch {
                delay(6_000)
                if (_status.value == Status.Started && _probe.value == ProbeState.Idle) {
                    probeNetwork()
                }
            }
        }
    }

    private fun markStopped() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        autoTested = false
        if (_status.value != Status.Stopped) {
            _status.value = Status.Stopped
        }
        _connectedAt.value = 0L
        Settings.tileActive = false
    }

    private fun armStartingWatchdog(timeoutMs: Long) {
        startingWatchdog?.cancel()
        startingWatchdog = viewModelScope.launch {
            delay(timeoutMs)
            if (_status.value == Status.Starting || _status.value == Status.Started) {
                markStopped()
                com.interstellar.proxy.bg.BoxService.stop()
            }
        }
    }

    /**
     * Command socket drops during core start/reload. Stay on the current
     * face until SERVICE_STOPPED arrives, or a short grace expires.
     * Do not flip Started → Starting — that is the "连接中" hang.
     */
    private fun applySocketDrop() {
        // connection errors from the (sing-box only) command client must not
        // tear down a healthy sidecar engine
        if (Settings.coreKind != CoreKind.SINGBOX) return
        when (_status.value) {
            Status.Stopping -> markStopped()
            Status.Started -> armStartingWatchdog(SOCKET_DROP_TIMEOUT_MS)
            else -> Unit
        }
    }

    fun startProxy() {
        android.util.Log.d("InterstellarUI", "startProxy invoked")
        if (probing) {
            probing = false
            com.interstellar.proxy.bg.BoxService.stop()
        }
        _status.value = Status.Starting
        armStartingWatchdog(STARTING_TIMEOUT_MS)
        _message.value = null
        viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            try {
                delay(250)
                android.util.Log.d("InterstellarUI", "activeSub=" + (SubscriptionRepository.activeSubscription()?.name ?: "null"))
                if (SubscriptionRepository.subscriptions.isEmpty()) {
                    _message.value = "请先添加一个订阅"
                    markStopped()
                    return@launch
                }
                val config = SubscriptionRepository.regenerateActiveConfig()
                if (config == null) {
                    _message.value = SubscriptionRepository.lastConfigError
                        ?: "配置生成失败:订阅中没有可用节点"
                    markStopped()
                    return@launch
                }
                android.util.Log.d("InterstellarUI", "config ok length=" + config.length)
                com.interstellar.proxy.bg.BoxService.start()
                commandClient.connect()
            } catch (e: Exception) {
                _message.value = "启动失败: ${e.message}"
                markStopped()
            } finally {
                _busy.value = false
            }
        }
    }

    fun stopProxy() {
        startingWatchdog?.cancel()
        startingWatchdog = null
        _status.value = Status.Stopping
        com.interstellar.proxy.bg.BoxService.stop()
    }

    /**
     * Regenerates the active config from current settings (bypass-LAN,
     * bypass-CN, ad-block, mode…) and hot-reloads the running core.
     */
    /**
     * Turn certificate verification on/off for one node and re-apply it.
     *
     * Stored per node (see [Settings.nodeInsecureOverride]) so it survives
     * subscription refreshes, then the config is regenerated and the running
     * kernel reloaded — otherwise the user would flip a switch that only takes
     * effect on the next manual reconnect.
     */
    fun setNodeCertVerify(node: com.interstellar.proxy.data.model.ProxyNode, verify: Boolean) {
        Settings.setNodeInsecureOverride(node.nodeKey, !verify)
        refreshProxyConfig()
    }

    fun refreshProxyConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config == null) {
                _message.value = SubscriptionRepository.lastConfigError ?: "配置更新失败"
                return@launch
            }
            if (_status.value == Status.Started) {
                when (Settings.coreKind) {
                    CoreKind.MIHOMO -> runCatching {
                        com.interstellar.proxy.core.MihomoCore.Holder.instance?.refreshFromConfigStore()
                    }

                    CoreKind.XRAY -> runCatching {
                        com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                    }

                    CoreKind.SINGBOX -> runCatching { CommandTarget.standaloneClient().serviceReload() }
                }
            }
        }
    }

    /**
     * Switch the active engine: stop the running service (an in-flight core
     * can't morph into another), regenerate the config for the new core and
     * start it again.
     */
    fun switchCore(kind: CoreKind) {
        if (Settings.coreKind == kind) return
        viewModelScope.launch(Dispatchers.IO) {
            val wasRunning = _status.value == Status.Started || _status.value == Status.Starting
            if (wasRunning) {
                com.interstellar.proxy.bg.BoxService.stop()
                var waited = 0
                while (_status.value != Status.Stopped && waited < 10_000) {
                    delay(200)
                    waited += 200
                }
            }
            com.interstellar.proxy.core.AppLog.log("core", "切换内核 → ${kind.displayName}")
            smartEngine.stop() // probe client type follows the new core
            Settings.coreKind = kind
            _coreKind.value = kind
            _groups.value = emptyList()
            _delays.value = emptyMap()
            mihomoLastDown = -1L
            mihomoLastUp = -1L
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config == null) {
                _message.value = SubscriptionRepository.lastConfigError ?: "配置更新失败"
                return@launch
            }
            if (wasRunning) {
                com.interstellar.proxy.bg.BoxService.start()
                if (Settings.selectedOutboundTag == com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
                    smartEngine.start()
                }
            }
        }
    }

    fun setClashMode(mode: String) {
        val normalized = normalizeRoutingMode(mode) ?: return
        // optimistic: the seg moves immediately, the reload below confirms it
        _routingMode.value = normalized
        viewModelScope.launch(Dispatchers.IO) {
            // keep the core's clash API state in sync (the actual routing is
            // baked into the regenerated config below)
            runCatching {
                CommandTarget.standaloneClient().setClashMode(normalized)
            }
            Settings.outboundMode = when (normalized) {
                "global" -> ConfigBuilder.OutboundMode.GLOBAL
                "direct" -> ConfigBuilder.OutboundMode.DIRECT
                else -> ConfigBuilder.OutboundMode.RULE
            }
            refreshSplitRuleStatus()
            // regenerate with the new mode, then hot-reload so a running core
            // picks up the new route.final / CN bypass rules immediately
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config != null && _status.value == Status.Started) {
                runCatching { CommandTarget.standaloneClient().serviceReload() }
            }
        }
    }

    fun selectNode(groupTag: String, itemTag: String) {
        Settings.selectedOutboundTag = itemTag
        _selectedOutboundTag.value = itemTag
        refreshSplitRuleStatus()
        // picking a concrete node exits smart mode
        if (itemTag != com.interstellar.proxy.data.config.ConfigBuilder.SMART_TAG) {
            smartEngine.stop()
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (_status.value == Status.Started) {
                val startedAt = System.currentTimeMillis()
                val switched = when (Settings.coreKind) {
                    CoreKind.MIHOMO -> {
                        val ok = runCatching { clashApi.select(groupTag, itemTag) }.getOrDefault(false)
                        if (!ok) _message.value = "切换失败: 内核 API 不可达"
                        runCatching { pollMihomoOnce() }
                        ok
                    }

                    CoreKind.XRAY -> {
                        // Xray has no selector — regenerate + respawn ("重启生效")
                        SubscriptionRepository.regenerateActiveConfig()
                        runCatching {
                            com.interstellar.proxy.core.XrayCore.Holder.instance?.restartFromConfigStore()
                        }.onSuccess {
                            _message.value = "已重启 Xray 生效"
                        }.isSuccess
                    }

                    CoreKind.SINGBOX -> runCatching {
                        CommandTarget.standaloneClient().selectOutbound(groupTag, itemTag)
                    }.onFailure {
                        _message.value = "切换失败: ${it.message}"
                    }.isSuccess
                }
                val cost = System.currentTimeMillis() - startedAt
                AppLog.log(
                    "core",
                    if (switched) "已切换 → $itemTag (${cost}ms)，并断开旧连接使其立即生效"
                    else "切换失败 → $itemTag (${cost}ms)",
                )
                // 换 selector 只影响**之后新建**的连接，旧连接还钉在旧节点上
                if (switched) dropConnections()
            } else {
                SubscriptionRepository.regenerateActiveConfig()
                _selectedOutboundTag.value = Settings.selectedOutboundTag
            }
        }
    }

    /**
     * 掐掉内核里所有已建立的连接。
     *
     * selector 只对**之后新建**的连接生效：已经在跑的 TCP 会话仍然钉在旧节点上
     * （浏览器 keep-alive / HTTP2 会话能挂几分钟，IM 那种长连接更久），所以不断开
     * 旧连接的话，用户点完节点会觉得"根本没切过去"。Clash 系客户端都是靠"切换即
     * 关闭连接"让切换立刻可见的。
     *
     * 代价：正在进行的下载/上传会被打断（下次请求会走新节点重连）。
     * Xray 没有控制 API，关不掉，只能等它自然断开。
     */
    private suspend fun dropConnections() {
        runCatching {
            when (Settings.coreKind) {
                CoreKind.MIHOMO -> clashApi.closeAllConnections()
                CoreKind.SINGBOX -> CommandTarget.standaloneClient().closeConnections()
                CoreKind.XRAY -> Unit
            }
            Unit
        }
    }

    fun setSplitRulesEnabled(enabled: Boolean) {
        Settings.splitRulesEnabled = enabled
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun upsertCustomRule(rule: CustomRouteRule) {
        CustomRulesStore.upsert(rule)
        _customRules.value = CustomRulesStore.rules.toList()
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun removeCustomRule(id: String) {
        CustomRulesStore.remove(id)
        _customRules.value = CustomRulesStore.rules.toList()
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun setCustomRuleEnabled(id: String, enabled: Boolean) {
        CustomRulesStore.setEnabled(id, enabled)
        _customRules.value = CustomRulesStore.rules.toList()
        refreshSplitRuleStatus()
        refreshProxyConfig()
    }

    fun upsertDnsOverride(entry: DnsOverrideEntry) {
        DnsOverridesStore.upsert(entry)
        _dnsOverrides.value = DnsOverridesStore.entries.toList()
        refreshProxyConfig()
    }

    fun removeDnsOverride(id: String) {
        DnsOverridesStore.remove(id)
        _dnsOverrides.value = DnsOverridesStore.entries.toList()
        refreshProxyConfig()
    }

    fun setDnsOverrideEnabled(id: String, enabled: Boolean) {
        DnsOverridesStore.setEnabled(id, enabled)
        _dnsOverrides.value = DnsOverridesStore.entries.toList()
        refreshProxyConfig()
    }

    private fun refreshSplitRuleStatus() {
        _splitRuleStatus.value = computeSplitRuleStatus()
    }

    private fun computeSplitRuleStatus(): SplitRuleStatus {
        val has = CustomRulesStore.rules.any { it.enabled }
        val master = Settings.splitRulesEnabled
        val clashRule = Settings.outboundMode == ConfigBuilder.OutboundMode.RULE
        return SplitRuleStatus(
            hasEnabledRules = has,
            masterEnabled = master,
            active = has && master && clashRule,
        )
    }

    fun urlTest(groupTag: String) {
        if (_testing.value) return
        _testing.value = true
        _testProgress.value = 0 to urlTestTotal()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (_status.value == Status.Started) {
                    when (Settings.coreKind) {
                        CoreKind.MIHOMO -> {
                            val result = clashApi.groupDelay(groupTag)
                            if (result != null) {
                                _delays.value = _delays.value.toMutableMap().also { map ->
                                    result.forEach { (name, delay) ->
                                        // mihomo 对失败节点返回 <=0；如果直接写 0，
                                        // 节点卡片会因为 selector item 的 urlTestTime
                                        // 仍为 0 而显示「未测」。统一用超时哨兵值。
                                        map[name] = if (delay <= 0) TIMEOUT_DELAY else delay
                                    }
                                }
                                _testProgress.value = result.size to result.size
                            } else {
                                _message.value = "测速失败: 内核 API 不可达"
                            }
                        }

                        // no API — TCP ping the pool directly, no core involved
                        CoreKind.XRAY -> tcpPingPool()

                        CoreKind.SINGBOX -> {
                            // test the AUTO urltest group (members = every node
                            // tag), not the selector: the selector's mixed
                            // group+node items get skipped wholesale by the
                            // kernel's per-item pass — that's the "大量未测"
                            // at the bottom. Mirrors the mihomo path
                            // (groupDelay(AUTO_TAG)) and the disconnected path.
                            if (!runKernelUrlTest(manual = true)) {
                                _message.value = "测速失败: 命令发送失败"
                            } else {
                                // runKernelUrlTest 的 settle 结束后，失败的节点可能
                                // 只拿到 0 或根本不在 _delays 里；selector 的 items
                                // 又不会同步 urlTestTime，导致卡片显示「未测」。
                                // 把 AUTO 组成员中无有效延迟的 tag 统一标为超时。
                                // AUTO 组还没推上来时用订阅池兜底，否则整段会被跳过。
                                val members = _groups.value
                                    .find { it.tag == ConfigBuilder.AUTO_TAG }
                                    ?.items?.map { it.tag }
                                    ?.takeIf { it.isNotEmpty() }
                                    ?: ConfigBuilder.tagsFor(
                                        SubscriptionRepository.poolOf(
                                            _subscriptions.value,
                                            _activeSubscriptionId.value,
                                            _mixEnabled.value,
                                            _mixSubscriptionIds.value,
                                        ),
                                    )
                                if (members.isNotEmpty()) {
                                    _delays.value = _delays.value.toMutableMap().also { map ->
                                        for (tag in members) {
                                            val v = map[tag]
                                            if (v == null || v <= 0 || v >= TIMEOUT_DELAY) {
                                                map[tag] = TIMEOUT_DELAY
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // No settle delay here: groupDelay is a blocking call that
                    // already returns the complete map, and runKernelUrlTest
                    // owns its own settle. A fixed 12s sleep used to sit right
                    // here doing nothing but keeping the spinner up.
                } else {
                    runDisconnectedUrlTest()
                }
            } catch (e: Exception) {
                _message.value = "测速失败: ${e.message}"
            } finally {
                _testing.value = false
                _testProgress.value = null
                testStartEpoch = 0
            }
        }
    }

    /** Denominator for the batch progress: live group size → known delays → pool size. */
    private fun urlTestTotal(): Int {
        _groups.value.find { it.tag == GROUP_TAG }?.let { group ->
            if (group.items.isNotEmpty()) return group.items.size
        }
        if (_delays.value.isNotEmpty()) return _delays.value.size
        return SubscriptionRepository.poolOf(
            _subscriptions.value,
            _activeSubscriptionId.value,
            _mixEnabled.value,
            _mixSubscriptionIds.value,
        ).size
    }

    /**
     * Wait for the kernel url-test to finish. Nodes test concurrently, each
     * with sing-box's own per-node HTTP timeout (constant.TCPTimeout = 15s,
     * not configurable), so the run is bounded by that plus scheduling
     * margin. Settles on the first of: every member reported, no new result
     * for [SETTLE_QUIET_MS], or the hard cap.
     */
    private suspend fun awaitUrlTestSettled(capMs: Long = PER_NODE_TEST_TIMEOUT_MS + 10_000) {
        val deadline = System.currentTimeMillis() + capMs
        while (System.currentTimeMillis() < deadline && testStartEpoch > 0) {
            if (settledByQuiet()) break
            delay(250)
        }
    }

    /**
     * True once results have stopped arriving. The kernel skips members it
     * can't test (unsupported protocol, disabled entry) and never stamps
     * them, so "done >= total" may never be reached — waiting for it means
     * staring at a spinner for the whole 25s cap while the numbers are
     * already on screen.
     */
    private fun settledByQuiet(): Boolean =
        testDone > 0 && System.currentTimeMillis() - testLastResultAt >= SETTLE_QUIET_MS

    /**
     * One kernel url-test run over the AUTO group, serialized between the
     * manual button and the smart engine: both send the same command, and
     * without the mutex one run's stream pushes would settle the other's
     * wait (or let a stray push kill the manual spinner mid-run). Marks the
     * run epoch, sends the command, then waits for the outbounds stream to
     * report every member stamped after the epoch — bounded by the kernel's
     * per-node timeout plus margin. False = the command could not be sent.
     */
    private suspend fun runKernelUrlTest(manual: Boolean): Boolean =
        kernelUrlTestMutex.withLock {
            kernelTestManual = manual
            testDone = 0
            testLastResultAt = 0L
            testStartEpoch = System.currentTimeMillis() / 1000
            if (manual) {
                // a colliding run's settle may have flashed these off while
                // this caller was waiting on the mutex
                _testing.value = true
                _testProgress.value = 0 to urlTestTotal()
            }
            val ok = runCatching {
                CommandTarget.standaloneClient().urlTest(ConfigBuilder.AUTO_TAG)
            }.isSuccess
            if (!ok) {
                testStartEpoch = 0
                return@withLock false
            }
            val deadline = System.currentTimeMillis() + PER_NODE_TEST_TIMEOUT_MS + 10_000
            while (testStartEpoch > 0 && System.currentTimeMillis() < deadline) {
                // settle as soon as results stop flowing; late arrivals still
                // land in _delays because that write sits outside the epoch
                // guard, so the list keeps filling in behind the closed spinner
                if (settledByQuiet()) break
                delay(250)
            }
            testStartEpoch = 0 // cap fallback; callers' finally owns the UI state
            true
        }

    /**
     * Spin up the core without TUN so url-test can run while the UI stays
     * 未连接. Restores the previous config afterwards.
     */
    private suspend fun runDisconnectedUrlTest() {
        // Xray delay = direct TCP pings; spinning a headless core buys nothing
        if (Settings.coreKind == CoreKind.XRAY) {
            tcpPingPool()
            return
        }
        probing = true
        probeSocketUp = false
        val previous = ConfigStore.readActiveConfig()
        val isMihomo = Settings.coreKind == CoreKind.MIHOMO
        try {
            val probe = SubscriptionRepository.regenerateActiveConfig(includeTun = false)
                ?: throw IllegalStateException(
                    SubscriptionRepository.lastConfigError ?: "无法生成测速配置",
                )
            com.interstellar.proxy.bg.BoxService.startHeadless()
            if (isMihomo) {
                // wait for the Clash REST API instead of the libbox socket
                var connected = false
                for (i in 0 until 150) {
                    delay(200)
                    if (_status.value == Status.Starting || _status.value == Status.Started) return
                    if (runCatching { clashApi.version() }.getOrNull() != null) {
                        connected = true
                        break
                    }
                }
                if (!connected) throw IllegalStateException("测速服务启动超时")
                val result = clashApi.groupDelay(ConfigBuilder.AUTO_TAG)
                    ?: throw IllegalStateException("测速命令发送失败")
                _delays.value = _delays.value.toMutableMap().also { map ->
                    result.forEach { (name, delay) -> map[name] = delay }
                }
                delay(1_000)
            } else {
                commandClient.connect()
                // Wait for the command socket. Cold boot (FGS scheduling, rule-set
                // init) can take well over 6s — probing a raw command client is a
                // slow-failing gRPC dial, so key off our own connection instead.
                var connected = false
                for (i in 0 until 150) { // 150 × 200ms = 30s budget
                    delay(200)
                    if (_status.value == Status.Starting || _status.value == Status.Started) return
                    if (probeSocketUp) {
                        connected = true
                        break
                    }
                    // a failed dial is not retried inside CommandClient — re-kick it
                    if (i > 0 && i % 10 == 0) commandClient.connect()
                }
                if (!connected) throw IllegalStateException("测速服务启动超时")
                kernelTestManual = true // manual run: progress + settle own the UI state
                testDone = 0
                testLastResultAt = 0L
                testStartEpoch = System.currentTimeMillis() / 1000
                val ok = runCatching {
                    CommandTarget.standaloneClient().urlTest(ConfigBuilder.AUTO_TAG)
                }.isSuccess
                if (!ok) throw IllegalStateException("测速命令发送失败")
                // bounded by the kernel's per-node timeout (15s) + margin —
                // a fixed 10s here used to cut the headless core mid-run and
                // leave the bottom nodes 未测
                awaitUrlTestSettled()
            }
        } finally {
            probeSocketUp = false
            val takenOver = _status.value == Status.Starting || _status.value == Status.Started
            if (!takenOver) {
                com.interstellar.proxy.bg.BoxService.stop()
                delay(250)
                if (previous != null) ConfigStore.writeActiveConfig(previous)
                else SubscriptionRepository.regenerateActiveConfig()
            }
            probing = false
        }
    }

    /** Aggregated per-run ping outcome for the summary bar. */
    data class PingReport(
        val failed: Int,
        val skippedUdp: Int,
        val reasons: Map<String, Int>,
    )

    @Volatile
    var lastPingReport: PingReport? = null
        private set

    /**
     * Concurrent direct TCP ping over the current pool (satelite-style):
     * no core required, results stream into [delays] one by one so a
     * delay-sorted list re-orders immediately. Failed connects are marked
     * with the TIMEOUT sentinel and classified into [lastPingReport].
     * UDP-only protocols (hysteria2/tuic/wireguard/quic) can't be TCP-pinged
     * and are skipped with a note. Sockets bypass our own tun via
     * [DirectPing] — a plain connect would handshake with the local tun
     * stack (~3-4ms) while the VPN is up.
     */
    fun tcpPingPool() {
        if (_pinging.value) return
        _pinging.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // while connected, the tracker decides between a real
                // physical-network ping and a meaningless tun-loop ping
                DirectPing.warmup(if (_status.value == Status.Started) 1_500 else 0)
                val pool = SubscriptionRepository.poolOf(
                    _subscriptions.value,
                    _activeSubscriptionId.value,
                    _mixEnabled.value,
                    _mixSubscriptionIds.value,
                )
                if (pool.isEmpty()) {
                    showToast("没有可测的节点", UiToast.Kind.Error)
                    return@launch
                }
                val udpOnly = setOf(
                    com.interstellar.proxy.data.model.NodeType.HYSTERIA2,
                    com.interstellar.proxy.data.model.NodeType.TUIC,
                    com.interstellar.proxy.data.model.NodeType.WIREGUARD,
                )
                val pairs = pool.zip(ConfigBuilder.tagsFor(pool))
                    .filter { (node, _) -> node.type !in udpOnly && node.network != "quic" }
                val skippedUdp = pool.size - pairs.size
                if (pairs.isEmpty()) {
                    lastPingReport = PingReport(0, skippedUdp, emptyMap())
                    showToast("全部节点为 UDP 协议, 不支持 TCP Ping, 请用测速", UiToast.Kind.Error)
                    return@launch
                }
                _pingProgress.value = 0 to pairs.size
                val done = java.util.concurrent.atomic.AtomicInteger()
                val failed = java.util.concurrent.atomic.AtomicInteger()
                val reasons = java.util.concurrent.ConcurrentHashMap<String, Int>()
                val semaphore = kotlinx.coroutines.sync.Semaphore(12)
                // one probe for the whole sweep: an IPv6-literal node on a
                // v4-only network fails in single-digit ms with ENETUNREACH,
                // which used to surface as a bogus "超时" on every IPv6 node
                val localIpv6 = DirectPing.hasGlobalIpv6()
                kotlinx.coroutines.coroutineScope {
                    pairs.forEach { (node, tag) ->
                        launch {
                            semaphore.withPermit {
                                val outcome = runCatching {
                                    // peak-hour international paths drop 30-45% of
                                    // packets; a single SYN flickers between a delay
                                    // and 超时, so retry before declaring failure
                                    DirectPing.tcpConnectRetry(node.server, node.port, 3000, 3)
                                }
                                val value = when {
                                    outcome.exceptionOrNull() != null -> TIMEOUT_DELAY
                                    else -> outcome.getOrDefault(0).coerceAtLeast(1)
                                }
                                if (value == TIMEOUT_DELAY) {
                                    failed.incrementAndGet()
                                    val e = outcome.exceptionOrNull()
                                    val reason = when {
                                        DirectPing.isIpv6Literal(node.server) && !localIpv6 ->
                                            "本机无 IPv6"
                                        e == null -> "超时"
                                        e is java.net.SocketTimeoutException -> "超时"
                                        e is java.net.ConnectException -> "连接被拒"
                                        e is java.net.UnknownHostException -> "域名解析失败"
                                        else -> e.message?.take(18)?.takeIf { it.isNotBlank() }
                                            ?: e.javaClass.simpleName
                                    }
                                    reasons.merge(reason, 1, Int::plus)
                                }
                                delaysMutex.withLock {
                                    _delays.value = _delays.value.toMutableMap().also { it[tag] = value }
                                }
                                _pingProgress.value = done.incrementAndGet() to pairs.size
                            }
                        }
                    }
                }
                lastPingReport = PingReport(failed.get(), skippedUdp, reasons.toMap())
            } finally {
                _pinging.value = false
                _pingProgress.value = null
            }
        }
    }

    /**
     * 单节点测速（节点详情页顶部按钮 / 长按菜单用）。
     *
     * 三条路的「延迟」含义不同，混用会误导，所以按当前状态选路：
     *  - 已连接 + mihomo：Clash API 的 per-proxy delay，真实走代理的耗时。
     *  - 已连接 + sing-box：对该 outbound 单独发 urlTest，等内核把延迟推回来。
     *  - 未连接 / xray：直接 TCP 握手（[DirectPing]，绕过本地 tun 栈）。
     *    hysteria2/tuic/wireguard 是 UDP，TCP 握手测不到 —— 说明原因，
     *    而不是让它显示成「超时」（那是假故障）。
     *
     * @param tcpOnly 强制 TCP 握手（「Ping」按钮）。已连接时它测的是物理链路，
     *                不经过代理，不能和「测速」混为一谈。
     */
    fun testSingleNode(
        tag: String,
        node: com.interstellar.proxy.data.model.ProxyNode?,
        tcpOnly: Boolean = false,
    ) {
        if (_nodeTesting.value != null) {
            // 静默 return 会让人以为"点了没反应" —— 明确说一句
            showToast("正在测速，请稍候", UiToast.Kind.Error)
            return
        }
        if (node == null) {
            showToast("节点信息缺失，无法测速", UiToast.Kind.Error)
            return
        }
        _nodeTesting.value = tag
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val connected = _status.value == Status.Started
                val udpOnly = node.type in setOf(
                    com.interstellar.proxy.data.model.NodeType.HYSTERIA2,
                    com.interstellar.proxy.data.model.NodeType.TUIC,
                    com.interstellar.proxy.data.model.NodeType.WIREGUARD,
                ) || node.network == "quic"

                var value = TIMEOUT_DELAY
                var note: String? = null
                // UDP 节点连不上不代表"超时"，不能写回哨兵值 —— 那会把一个
                // 根本没测的节点标成故障。
                var writeBack = true

                when {
                    !tcpOnly && connected && Settings.coreKind == CoreKind.MIHOMO -> {
                        val d = runCatching { clashApi.proxyDelay(tag) }.getOrNull()
                        if (d != null && d > 0) {
                            value = d
                        } else {
                            value = TIMEOUT_DELAY
                            note = "超时：5 秒内没有握手成功"
                        }
                    }

                    !tcpOnly && connected && Settings.coreKind == CoreKind.SINGBOX -> {
                        val beforeStamp = testStamps[tag] ?: 0L
                        val beforeDelay = _delays.value[tag]
                        val sent = runCatching {
                            CommandTarget.standaloneClient().urlTest(tag)
                        }.isSuccess
                        if (!sent) {
                            value = TIMEOUT_DELAY
                            note = "测速命令发送失败"
                        } else {
                            // 完成判定看内核盖的时间戳，不能只看「值变没变」：
                            // 对一个本来就超时的节点重测，新值还是 65535，
                            // 值不变 ⇒ 白等到超时上限，还什么都没反馈。
                            var got = false
                            val deadline =
                                System.currentTimeMillis() + PER_NODE_TEST_TIMEOUT_MS + 3_000
                            while (System.currentTimeMillis() < deadline) {
                                val stamp = testStamps[tag] ?: 0L
                                val now = _delays.value[tag]
                                if (stamp > beforeStamp || (now != null && now != beforeDelay)) {
                                    got = true
                                    break
                                }
                                delay(200)
                            }
                            value = _delays.value[tag] ?: TIMEOUT_DELAY
                            if (!got) {
                                value = TIMEOUT_DELAY
                                note = "超时：15 秒内没有握手成功"
                            } else if (value <= 0 || value >= TIMEOUT_DELAY) {
                                // 内核测了但失败（urlTestDelay=0）——写回哨兵值，
                                // 徽章才会显示「超时」而不是「未测」。
                                value = TIMEOUT_DELAY
                                note = "超时：节点没有响应"
                            }
                        }
                    }

                    udpOnly -> {
                        writeBack = false
                        note = "UDP 协议（hysteria2/tuic/wireguard）测不到 TCP Ping，请连接后用「测速」"
                    }

                    else -> {
                        DirectPing.warmup(if (connected) 1_500 else 0)
                        val outcome = runCatching {
                            DirectPing.tcpConnectRetry(node.server, node.port, 3000, 3)
                        }
                        val err = outcome.exceptionOrNull()
                        value = if (err != null) {
                            TIMEOUT_DELAY
                        } else {
                            outcome.getOrDefault(0).coerceAtLeast(1)
                        }
                        if (err != null) {
                            note = when (err) {
                                is java.net.SocketTimeoutException -> "Ping 超时：3 秒内没有完成握手"
                                is java.net.UnknownHostException -> "Ping 失败：域名解析失败"
                                is java.net.ConnectException -> "Ping 失败：连接被拒"
                                else -> "Ping 失败：" + (err.message ?: err.javaClass.simpleName)
                            }
                        }
                    }
                }

                // 成功和超时都要写回：徽章是唯一一直在屏幕上的反馈，
                // 只在成功时写，失败就变成"点了没反应"。
                if (writeBack) {
                    delaysMutex.withLock {
                        _delays.value = _delays.value.toMutableMap().also { it[tag] = value }
                    }
                }
                note?.let { showToast(it, UiToast.Kind.Error) }
            } finally {
                _nodeTesting.value = null
            }
        }
    }

    fun addSubscriptionFromUrl(name: String, url: String) {
        addSubJob = viewModelScope.launch(Dispatchers.IO) {
            _busy.value = true
            _addingSub.value = true
            _addSubError.value = null
            try {
                val result = SubscriptionFetcher.fetch(url)
                importContent(
                    name.ifBlank { result.suggestedName ?: urlHost(url) },
                    url,
                    result.body,
                    result,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 分门别类，否则所有失败都显示"域名无法直连"，用户根本查不出原因
                val detail = e.message ?: e.javaClass.simpleName
                val msg = when {
                    e is IllegalArgumentException ->
                        "链接格式不对：$detail · 需要以 http:// 或 https:// 开头"
                    detail.contains("CLEARTEXT", ignoreCase = true) ->
                        "明文 HTTP 被系统拦截：$detail"
                    detail.contains("代理未运行") ->
                        "「更新走代理」已开启，但代理没有在运行 · 请先连接代理，或关掉这个开关"
                    Settings.subscriptionViaProxy ->
                        "下载失败: $detail · 已开启「更新走代理」, 请确认代理已连接且节点可用"
                    else ->
                        "下载失败: $detail · 订阅域名可能无法直连, 可在订阅页打开「更新走代理」"
                }
                _message.value = msg
                _addSubError.value = msg
            } finally {
                addSubJob = null
                _busy.value = false
                _addingSub.value = false
            }
        }
    }

    fun addSubscriptionFromText(name: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _addingSub.value = true
            _addSubError.value = null
            try {
                importContent(name.ifBlank { "本地订阅" }, null, text, null)
            } finally {
                _addingSub.value = false
            }
        }
    }

    private fun importContent(
        name: String,
        url: String?,
        body: String,
        traffic: SubscriptionFetcher.FetchResult?,
    ) {
        // stamp the subscription id on nodes up front so mix source labels work
        val subId = SubscriptionRepository.newSubscriptionId()
        when (val parsed = SubscriptionParser.parse(body, subId)) {
            is SubscriptionParser.Result.Nodes -> {
                val sub = SubscriptionRepository.Subscription(
                    id = subId,
                    name = name,
                    url = url,
                    nodes = parsed.nodes,
                    uploadBytes = traffic?.uploadBytes ?: 0,
                    downloadBytes = traffic?.downloadBytes ?: 0,
                    totalBytes = traffic?.totalBytes ?: 0,
                    expireSeconds = traffic?.expireSeconds ?: 0,
                    lastUpdated = System.currentTimeMillis(),
                )
                SubscriptionRepository.upsert(sub)
                if (Settings.selectedOutboundTag.isBlank()) {
                    Settings.selectedOutboundTag = com.interstellar.proxy.data.config.ConfigBuilder.AUTO_TAG
                }
                // NOT auto-checked into the mix pool: the user ticks it in the
                // subscription list (toggleMixSubscription → applyPoolChange
                // regenerates and the node list syncs from the state flow)
                _message.value = "已导入 ${parsed.nodes.size} 个节点"
            }

            SubscriptionParser.Result.Empty -> {
                _message.value = "无法识别的订阅内容"
                _addSubError.value = "无法识别的订阅内容"
            }
        }
        _subscriptions.value = SubscriptionRepository.subscriptions.toList()
        _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        _mixSubscriptionIds.value = Settings.mixSubscriptionIds
    }

    fun refreshSubscription(id: String, fromPull: Boolean = false) {
        val sub = SubscriptionRepository.get(id) ?: return
        val url = sub.url ?: run {
            showToast("本地订阅不支持刷新", UiToast.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            if (fromPull) _refreshing.value = true
            _busy.value = true
            try {
                val result = SubscriptionFetcher.fetch(url)
                when (val parsed = SubscriptionParser.parse(result.body, id)) {
                    is SubscriptionParser.Result.Nodes -> {
                        SubscriptionRepository.upsert(
                            sub.copy(
                                nodes = parsed.nodes,
                                uploadBytes = result.uploadBytes,
                                downloadBytes = result.downloadBytes,
                                totalBytes = result.totalBytes,
                                expireSeconds = result.expireSeconds,
                                lastUpdated = System.currentTimeMillis(),
                            ),
                        )
                        showToast("「${sub.name}」已刷新 ${parsed.nodes.size} 个节点", UiToast.Kind.Success)
                    }

                    SubscriptionParser.Result.Empty ->
                        showToast("「${sub.name}」刷新后内容无法解析", UiToast.Kind.Error)
                }
            } catch (e: Exception) {
                showToast("「${sub.name}」刷新失败: ${e.message}", UiToast.Kind.Error)
            } finally {
                _busy.value = false
                _refreshing.value = false
                _subscriptions.value = SubscriptionRepository.subscriptions.toList()
                _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
            }
        }
    }

    /** Refresh every URL subscription in parallel, then one aggregate toast. */
    fun refreshAll() {
        val subs = SubscriptionRepository.subscriptions.filter { it.url != null }
        if (subs.isEmpty()) {
            showToast("没有可更新的订阅", UiToast.Kind.Error)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _refreshing.value = true
            _busy.value = true
            val semaphore = kotlinx.coroutines.sync.Semaphore(3)
            var ok = 0
            try {
                kotlinx.coroutines.coroutineScope {
                    subs.forEach { sub ->
                        launch {
                            semaphore.withPermit {
                                val success = runCatching {
                                    val result = SubscriptionFetcher.fetch(sub.url!!)
                                    when (val parsed = SubscriptionParser.parse(result.body, sub.id)) {
                                        is SubscriptionParser.Result.Nodes -> {
                                            SubscriptionRepository.upsert(
                                                sub.copy(
                                                    nodes = parsed.nodes,
                                                    uploadBytes = result.uploadBytes,
                                                    downloadBytes = result.downloadBytes,
                                                    totalBytes = result.totalBytes,
                                                    expireSeconds = result.expireSeconds,
                                                    lastUpdated = System.currentTimeMillis(),
                                                ),
                                            )
                                            true
                                        }

                                        SubscriptionParser.Result.Empty -> false
                                    }
                                }.getOrDefault(false)
                                if (success) ok++
                            }
                        }
                    }
                }
                when {
                    ok == subs.size -> showToast("已更新全部 $ok 个订阅", UiToast.Kind.Success)
                    ok > 0 -> showToast("更新完成 $ok/${subs.size} 个订阅", UiToast.Kind.Success)
                    // 全军覆没 —— 这是用户最需要知道「更新走代理」存在的时刻，按当前走法给不同提示
                    Settings.subscriptionViaProxy ->
                        showToast("订阅全部更新失败 · 已开启「更新走代理」, 请确认代理已连接", UiToast.Kind.Error)
                    else ->
                        showToast("订阅全部更新失败 · 域名可能无法直连, 可在本页打开「更新走代理」", UiToast.Kind.Error)
                }
            } finally {
                _busy.value = false
                _refreshing.value = false
                _subscriptions.value = SubscriptionRepository.subscriptions.toList()
                _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
            }
        }
    }

    /** Edits an existing subscription's name / url. */
    fun updateSubscription(id: String, name: String, url: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val sub = SubscriptionRepository.get(id) ?: return@launch
            SubscriptionRepository.upsert(
                sub.copy(
                    name = name.trim().ifBlank { sub.name },
                    url = url?.trim()?.takeIf { it.isNotBlank() } ?: sub.url,
                ),
            )
            _subscriptions.value = SubscriptionRepository.subscriptions.toList()
            _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        }
    }

    fun removeSubscription(id: String) {
        SubscriptionRepository.remove(id)
        _subscriptions.value = SubscriptionRepository.subscriptions.toList()
        _activeSubscriptionId.value = SubscriptionRepository.activeSubscriptionId
        _mixSubscriptionIds.value = Settings.mixSubscriptionIds
    }

    fun activateSubscription(id: String) {
        if (id == _activeSubscriptionId.value) return
        SubscriptionRepository.activeSubscriptionId = id
        _activeSubscriptionId.value = id
        refreshSplitRuleStatus()
        viewModelScope.launch(Dispatchers.IO) {
            SubscriptionRepository.regenerateActiveConfig()
            if (_status.value == Status.Started) {
                runCatching { CommandTarget.standaloneClient().serviceReload() }
            }
        }
    }

    /**
     * Toggles mix mode. Enabling with no (valid) checks defaults to every
     * subscription, so the pool is never silently empty on first use.
     */
    fun setMixEnabled(enabled: Boolean) {
        if (enabled == _mixEnabled.value) return
        if (enabled &&
            Settings.mixSubscriptionIds.none { id -> SubscriptionRepository.get(id) != null }
        ) {
            Settings.mixSubscriptionIds =
                SubscriptionRepository.subscriptions.map { it.id }.toSet()
            _mixSubscriptionIds.value = Settings.mixSubscriptionIds
        }
        Settings.mixEnabled = enabled
        _mixEnabled.value = enabled
        applyPoolChange()
    }

    /** Checks / unchecks one subscription in the mix pool; at least one stays checked. */
    fun toggleMixSubscription(id: String) {
        if (!_mixEnabled.value) return
        val current = _mixSubscriptionIds.value
        if (id in current && current.size <= 1) {
            _message.value = "至少保留一个订阅"
            return
        }
        val next = if (id in current) current - id else current + id
        Settings.mixSubscriptionIds = next
        _mixSubscriptionIds.value = next
        applyPoolChange()
    }

    /** Rebuilds the config from the new pool and hot-reloads the running core. */
    private fun applyPoolChange() {
        viewModelScope.launch(Dispatchers.IO) {
            val config = SubscriptionRepository.regenerateActiveConfig()
            if (config == null) {
                _message.value = SubscriptionRepository.lastConfigError ?: "配置生成失败"
                return@launch
            }
            if (_status.value == Status.Started) {
                runCatching { CommandTarget.standaloneClient().serviceReload() }
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** One-tap exit-IP probe; races public IP APIs through the running node. */
    fun probeNetwork() {
        if (_probe.value == ProbeState.Running) return
        _probe.value = ProbeState.Running
        viewModelScope.launch(Dispatchers.IO) {
            _probe.value = try {
                ProbeState.Done(com.interstellar.proxy.data.net.NetProbe.probe())
            } catch (e: Exception) {
                ProbeState.Failed(e.message ?: "探测失败")
            }
        }
    }

    private fun urlHost(url: String): String = runCatching {
        java.net.URI(url).host ?: url
    }.getOrDefault(url)
}
