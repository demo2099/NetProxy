package com.interstellar.proxy.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.interstellar.proxy.utils.CommandClient
import com.interstellar.proxy.utils.CommandTarget
import io.nekohasekai.libbox.LogEntry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    private var buffer = ArrayDeque<LogEntry>()

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
                    buffer.addLast(entry)
                    while (buffer.size > 3000) buffer.removeFirst()
                }
                _logs.value = buffer.toList()
            }
        },
    )

    fun connect() = client.connect()

    fun disconnect() = client.disconnect()

    fun clearLogs() {
        viewModelScope.launch {
            runCatching { CommandTarget.standaloneClient().clearLogs() }
        }
    }
}
