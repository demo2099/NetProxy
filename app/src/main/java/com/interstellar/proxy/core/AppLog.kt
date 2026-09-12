package com.interstellar.proxy.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process app log ring buffer (service lifecycle, core switching,
 * watchdog events) surfaced on the logs page alongside kernel output.
 */
object AppLog {
    private const val LIMIT = 400
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val events: SharedFlow<String> = _events

    val snapshot: List<String>
        get() = synchronized(buffer) { buffer.toList() }

    fun log(tag: String, message: String) {
        val line = "${format.format(Date())} · $tag · $message"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > LIMIT) buffer.removeFirst()
        }
        _events.tryEmit(line)
    }
}
