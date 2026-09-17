package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.CoreKind
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
    private var wiringJob: Job? = null
    private var wiredKind: CoreKind? = null
    private var tailOffset = 0L

    fun connect() {
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
                SubscriptionRepository.lastConfigError?.let { appendLine("上次生成配置报错: $it") }
                appendLine()
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
        buffer.addLast(line)
        while (buffer.size > 3000) buffer.removeFirst()
        _logs.value = buffer.toList()
    }

    private fun startAppLogCollector() {
        if (appLogJob?.isActive == true) return
        AppLog.snapshot.forEach { append(LogLine(LEVEL_INFO, "[app] $it", System.currentTimeMillis())) }
        appLogJob = viewModelScope.launch {
            AppLog.events.collect { line ->
                append(LogLine(LEVEL_INFO, "[app] $line", System.currentTimeMillis()))
            }
        }
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

        /**
         * How often to retry the libbox command connection while the core is down.
         * Also the core-kind poll interval, so switching engines is picked up
         * within a couple of seconds.
         */
        private const val RETRY_INTERVAL_MS = 2_000L
    }
}
