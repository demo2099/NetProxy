package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.CoreKind
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.data.SubscriptionRepository
import com.interstellar.proxy.data.config.ConfigBuilder
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.LogEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

/** Neutral log line (libbox LogEntry or mihomo file tail both map onto it). */
data class LogLine(val level: Int, val message: String, val timestamp: Long)

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogLine>>(emptyList())
    val logs: StateFlow<List<LogLine>> = _logs

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** 当前过滤档位（见 [Settings.logFilter]），界面上的切换按钮读它。 */
    private val _filter = MutableStateFlow(Settings.logFilter)
    val filter: StateFlow<Int> = _filter

    private var buffer = ArrayDeque<LogLine>()

    private val client = CommandClient(
        viewModelScope,
        CommandClient.ConnectionType.Log,
        object : CommandClient.Handler {
            override fun onConnected() {
                _connected.value = true
            }

            override fun onDisconnected() {
                _connected.value = false
            }

            override fun clearLogs() {
                buffer.clear()
                _logs.value = emptyList()
            }

            override fun appendLogs(message: List<LogEntry>) {
                for (entry in message) {
                    append(LogLine(entry.level, entry.message, System.currentTimeMillis()))
                }
            }
        },
    )

    private var tailJob: Job? = null
    private var appLogJob: Job? = null

    /** 最后一条已收下的 AppLog 行 —— 用来补历史时只补「新」的，不重复。 */
    private var lastAppLine: String? = null
    private var wiringJob: Job? = null
    private var wiredKind: CoreKind? = null
    private var tailOffset = 0L

    fun connect() {
        // 关闭档位：连日志管道都不建 —— 不订阅 libbox 的 Log 连接、不 tail
        // mihomo 日志文件、不收集 AppLog，而不是「收下来再扔掉」
        if (Settings.logFilter == FILTER_OFF) return
        startAppLogCollector()
        if (wiringJob?.isActive == true) return
        wiringJob = viewModelScope.launch {
            while (isActive) {
                val kind = Settings.coreKind
                if (kind != wiredKind) {
                    // Core switched under us (the user can hot-swap engines from the
                    // dashboard): rewire, or the page keeps showing the old core's
                    // stream — or nothing at all.
                    wiredKind = kind
                    client.disconnect()
                    tailJob?.cancel()
                    tailJob = null
                    if (kind == CoreKind.MIHOMO) startMihomoTail() else client.connect()
                } else if (kind != CoreKind.MIHOMO && !_connected.value) {
                    // The libbox command socket only exists while the core runs, and
                    // connect() used to be called exactly once per app launch
                    // (DisposableEffect(Unit) in AppRoot). So opening 日志 before
                    // starting the proxy left the page permanently empty — it said
                    // "启动内核后查看日志" even once the core was up, because nothing
                    // ever retried. Poll until it takes; onConnected() flips
                    // _connected and this branch stops firing.
                    client.connect()
                }
                delay(RETRY_INTERVAL_MS)
            }
        }
    }

    fun disconnect() {
        client.disconnect()
        wiringJob?.cancel()
        wiringJob = null
        wiredKind = null
        tailJob?.cancel()
        tailJob = null
        appLogJob?.cancel()
        appLogJob = null
    }

    fun clearLogs() {
        buffer.clear()
        _logs.value = emptyList()
        if (Settings.coreKind != CoreKind.MIHOMO) {
            viewModelScope.launch {
                runCatching { CommandTarget.standaloneClient().clearLogs() }
            }
        }
    }

    /**
     * 切换过滤档位。关闭时把收集管道一起停掉（真省 CPU/内存），恢复时重建。
     * 注意：档位存进 Settings，切走页面再回来仍然生效。
     */
    fun setFilter(value: Int) {
        val next = value.coerceIn(FILTER_ALL, FILTER_OFF)
        if (next == Settings.logFilter) return
        Settings.logFilter = next
        _filter.value = next
        if (next == FILTER_OFF) {
            // 缓冲一起清掉：留着既白占内存，恢复时又会把「关闭期间之前」的
            // 老日志突然吐回来 —— 界面上「已关闭」就该是真的什么都没在记。
            buffer.clear()
            _logs.value = emptyList()
            stopCollectors()
        } else {
            // 已经攒下的历史按新档位重算，不用等新日志
            _logs.value = buffer.filter { passes(it.level, next) }
            stopCollectors()
            connect()
        }
    }

    /** 停掉所有日志来源：Log 连接、mihomo tail、AppLog 收集、内核类型轮询。 */
    private fun stopCollectors() {
        client.disconnect()
        wiringJob?.cancel()
        wiringJob = null
        wiredKind = null
        tailJob?.cancel()
        tailJob = null
        appLogJob?.cancel()
        appLogJob = null
        _connected.value = false
    }

    /**
     * Self-contained diagnostic dump: what this install is running, and — for
     * every node in the pool — the exact outbound the core would be handed.
     *
     * Exists because the kernel log is not always obtainable: it needs a running
     * core, and the command socket only appears while that core is up. The
     * generated outbound is the one artefact that answers "is the config we build
     * for this node correct?" without the core, the network, or a live log stream
     * — e.g. whether an anytls node carries the ALPN its server expects
     * (see ProxyNode.effectiveAlpn), which is exactly the field the Clash
     * subscription format cannot express.
     */
    suspend fun diagnosticsText(): String = withContext(Dispatchers.IO) {
        runCatching {
            val nodes = SubscriptionRepository.activeNodes()
            val tags = ConfigBuilder.tagsFor(nodes)
            buildString {
                appendLine("=== 诊断 ===")
                appendLine("版本 ${BuildConfig.VERSION_NAME}")
                appendLine("内核 ${Settings.coreKind.displayName}")
                appendLine("节点数 ${nodes.size}")
                appendLine("IPv6 策略 ${ConfigBuilder.ipv6PolicySummary(Settings.outboundMode)}")
                // ECH is parsed and shown but not emitted — list the nodes whose
                // subscription asks for it, so "the panel advertises X and we
                // silently ignore it" is visible from the dump alone.
                ConfigBuilder.echSummary(nodes)?.let { appendLine("ECH 节点 $it") }
                SubscriptionRepository.lastConfigError?.let { appendLine("上次生成配置报错: $it") }
                appendLine()
                // The config the kernel is actually running. The 0.6.3 dump
                // carried every node's outbound JSON but neither the DNS policy
                // nor the route rules — and the IPv6 bug lived in exactly the
                // part that was missing.
                ConfigStore.readActiveConfig()?.let { config ->
                    appendLine("--- 当前内核配置 ---")
                    appendLine(config.take(120_000))
                    appendLine()
                }
                nodes.forEachIndexed { index, node ->
                    val tag = tags.getOrNull(index) ?: node.name
                    appendLine("--- ${node.name} · ${node.type.wire} · tag=$tag ---")
                    appendLine(
                        // sing-box shape; effectiveAlpn is shared by all three
                        // builders, so the ALPN question is answered either way.
                        runCatching { ConfigBuilder.nodeToOutbound(node, tag).toString() }
                            .getOrElse { "生成失败: ${it.message}" },
                    )
                    appendLine()
                }
            }
        }.getOrElse { "诊断生成失败: ${it.message}" }
    }

    // ---- shared ----

    private fun append(line: LogLine) {
        // 用 _filter 而不是每次读 Settings：内核日志是高频写入，
        // 每行都去 properties 里 parse 一遍没必要（setFilter 会同步两者）。
        val filter = _filter.value
        if (filter == FILTER_OFF) return
        buffer.addLast(line)
        while (buffer.size > LIMIT) buffer.removeFirst()
        // 只有通过过滤的行才重建展示列表；被过滤掉的行连列表都不碰。
        // （原来是每条日志都 `buffer.toList()`，连「仅错误」档位也在拷 3000 行）
        if (passes(line.level, filter)) {
            val next = _logs.value + line
            _logs.value = if (next.size > LIMIT) next.takeLast(LIMIT) else next
        }
    }

    /** 当前档位下这一行要不要显示。级别数值越小越严重（0=panic … 6=trace）。 */
    private fun passes(level: Int, filter: Int = _filter.value): Boolean = when (filter) {
        FILTER_OFF -> false
        FILTER_WARN -> level <= LEVEL_WARN
        FILTER_ERROR -> level <= LEVEL_ERROR
        else -> true
    }

    private fun startAppLogCollector() {
        if (appLogJob?.isActive == true) return
        // 补历史时只补「上次收到之后」的那一段：切档位会走
        // stopCollectors + connect，每次都灌一遍 snapshot 的话，
        // 每切一次档位日志就整体重复一份。
        val snap = AppLog.snapshot
        val from = lastAppLine
            ?.let { last -> snap.indexOf(last).let { if (it >= 0) it + 1 else 0 } }
            ?: 0
        snap.drop(from).forEach { ingestAppLine(it) }
        appLogJob = viewModelScope.launch {
            AppLog.events.collect { ingestAppLine(it) }
        }
    }

    private fun ingestAppLine(line: String) {
        lastAppLine = line
        append(LogLine(LEVEL_INFO, "[app] $line", System.currentTimeMillis()))
    }

    // ---- mihomo: tail the sidecar log file ----

    private fun startMihomoTail() {
        if (tailJob?.isActive == true) return
        val logFile = File(getApplication<Application>().filesDir, "mihomo/libmihomo.so.log")
        // follow from the tail of whatever exists
        tailOffset = if (logFile.isFile) logFile.length().coerceAtLeast(0L) else 0L
        tailJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (logFile.isFile) {
                    val grew = readNewLines(logFile)
                    if (grew) _connected.value = true
                }
                delay(800)
            }
        }
    }

    /** Reads lines appended since the last poll; true when anything was read. */
    private fun readNewLines(file: File): Boolean {
        if (file.length() < tailOffset) tailOffset = 0L // rotated
        if (file.length() == tailOffset) return false
        val newLines = mutableListOf<String>()
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(tailOffset)
                // readLine() decodes ISO-8859-1 (mangles CJK) — read raw bytes
                // and decode UTF-8; a trailing partial line waits for the next
                // poll (offset only advances past the last complete \n)
                val buf = ByteArray((file.length() - tailOffset).toInt())
                var read = 0
                while (read < buf.size) {
                    val n = raf.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                var lastNewline = -1
                for (i in 0 until read) {
                    if (buf[i] == '\n'.code.toByte()) lastNewline = i
                }
                if (lastNewline >= 0) {
                    val text = String(buf, 0, lastNewline + 1, Charsets.UTF_8)
                    text.split('\n').forEach { line ->
                        if (line.isNotBlank()) newLines.add(line.removeSuffix("\r"))
                    }
                    tailOffset += lastNewline + 1
                }
            }
        }
        val stamp = System.currentTimeMillis()
        newLines.forEach { raw ->
            append(LogLine(levelOf(raw), formatMihomoLine(raw), stamp))
        }
        return newLines.isNotEmpty()
    }

    /** `time="..." level=info msg="..."` → `18:15:21 [mihomo] message`. */
    private fun formatMihomoLine(raw: String): String {
        val time = Regex("time=\"?[0-9T:+\\-\\.]+").find(raw)?.value?.removePrefix("time=\"")?.let {
            it.substringAfter('T').take(8)
        }
        val msg = Regex("msg=\"(.*)\"\\s*$").find(raw)?.groupValues?.get(1) ?: raw
        return "${time ?: ""} [mihomo] $msg".trim()
    }

    private fun levelOf(raw: String): Int = when {
        raw.contains("level=panic") -> 0
        raw.contains("level=fatal") -> 1
        raw.contains("level=error") -> 2
        raw.contains("level=warn") -> 3
        raw.contains("level=debug") -> 5
        else -> LEVEL_INFO
    }

    companion object {
        private const val LEVEL_INFO = 4

        /** 级别数值越小越严重：sing-box 与 mihomo 都用 logrus 那套 0..6。 */
        private const val LEVEL_ERROR = 2
        private const val LEVEL_WARN = 3

        /** 与 [Settings.logFilter] 一一对应。 */
        const val FILTER_ALL = 0
        const val FILTER_WARN = 1
        const val FILTER_ERROR = 2
        const val FILTER_OFF = 3

        private const val LIMIT = 3000

        /**
         * How often to retry the libbox command connection while the core is down.
         * Also the core-kind poll interval, so switching engines is picked up
         * within a couple of seconds.
         */
        private const val RETRY_INTERVAL_MS = 2_000L
    }
}
