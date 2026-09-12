package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.core.CoreKind
import com.interstellar.proxy.data.Settings
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
    private var tailOffset = 0L

    fun connect() {
        startAppLogCollector()
        if (Settings.coreKind == CoreKind.MIHOMO) {
            startMihomoTail()
        } else {
            client.connect()
        }
    }

    fun disconnect() {
        client.disconnect()
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
                var line = raf.readLine()
                while (line != null) {
                    if (line.isNotBlank()) newLines.add(line)
                    line = raf.readLine()
                }
            }
            tailOffset = file.length()
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
    }
}
