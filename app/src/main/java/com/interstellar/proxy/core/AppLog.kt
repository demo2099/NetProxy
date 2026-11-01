package com.interstellar.proxy.core

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * In-process app log ring buffer (service lifecycle, core switching,
 * watchdog events) surfaced on the logs page alongside kernel output.
 *
 * 除了内存里的环形缓冲，还会落盘到 filesDir/app.log：
 * 进程被系统杀掉 / 崩溃时，内存缓冲随进程一起消失，日志页就只剩内核那半边，
 * 「App 自己关了」这种问题事后完全查不到。落盘 + 启动时回填最后 120 行，
 * 才能看到上一次运行是怎么结束的。
 */
object AppLog {
    private const val LIMIT = 400
    private const val FILE_NAME = "app.log"
    private const val FILE_MAX_BYTES = 512L * 1024
    private const val LOAD_TAIL_LINES = 120
    private const val LOAD_TAIL_BYTES = 16 * 1024
    private const val MARK_START = "—— 本次启动 ——"

    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val fileFormat = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
    private val buffer = ArrayDeque<String>()
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 128)
    val events: SharedFlow<String> = _events

    /** 落盘目标；install() 之前为 null（此时只留内存，不写文件）。 */
    @Volatile
    private var logFile: File? = null

    val snapshot: List<String>
        get() = synchronized(buffer) { buffer.toList() }

    /**
     * 绑定日志文件并回填上一次运行的最后几行。
     *
     * 同步读文件是刻意的：必须在任何一条新日志写进去之前把旧内容捞出来，
     * 否则「启动」那行会先落盘，回填时又读一遍，日志页里就重复一条。
     * 只读文件尾部 16KB，耗时可以忽略。
     */
    fun install(dir: File) {
        val target = File(dir, FILE_NAME)
        if (logFile?.absolutePath == target.absolutePath) return
        logFile = target

        val loaded = runCatching { readTail(target) }.getOrDefault(emptyList())
        synchronized(buffer) {
            // 先把分隔行插到最前，再把旧行逐条前插 —— 旧行就会落在分隔行之前，
            // 新日志（已经在 buffer 里的）留在后面。
            buffer.addFirst(MARK_START)
            loaded.asReversed().forEach { buffer.addFirst(it) }
            while (buffer.size > LIMIT) buffer.removeFirst()
        }
    }

    /**
     * 把未捕获异常写进日志。
     *
     * 之前完全没有这个钩子：Java/Kotlin 层崩溃只会进 logcat，日志页一行都不会有，
     * 表现就是「用着用着自己关了，日志里什么都没有」。装上之后至少能看到
     * 「crash · 未捕获异常 @ main: NullPointerException: ...」+ 堆栈。
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                log("crash", "未捕获异常 @ ${thread.name}: ${error.javaClass.name}: ${error.message}")
                log("crash", error.stackTraceToString().take(6000))
            }
            if (previous != null) {
                previous.uncaughtException(thread, error)
            } else {
                android.os.Process.killProcess(android.os.Process.myPid())
                kotlin.system.exitProcess(10)
            }
        }
    }

    fun log(tag: String, message: String) {
        val now = Date()
        val line = synchronized(buffer) {
            // SimpleDateFormat 不是线程安全的，而这个对象会被内核线程、服务线程、
            // 协程同时调用 —— 格式化必须在锁里做。
            val l = "${format.format(now)} · $tag · $message"
            buffer.addLast(l)
            while (buffer.size > LIMIT) buffer.removeFirst()
            appendToFile(now, tag, message)
            l
        }
        android.util.Log.i("AppLog", "[$tag] $message")
        _events.tryEmit(line)
    }

    /** 调用方已持有 buffer 锁。 */
    private fun appendToFile(now: Date, tag: String, message: String) {
        val file = logFile ?: return
        runCatching {
            if (file.length() > FILE_MAX_BYTES) {
                val rolled = File(file.parentFile, "$FILE_NAME.1")
                rolled.delete()
                file.renameTo(rolled)
            }
            file.appendText("${fileFormat.format(now)} · $tag · $message\n")
        }
    }

    /** 只读文件尾部，避免整份读进来。 */
    private fun readTail(file: File): List<String> {
        if (!file.isFile || file.length() == 0L) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val length = raf.length()
            val start = (length - LOAD_TAIL_BYTES).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((length - start).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            // 从中间截断时第一行多半是残缺的，丢掉
            val lines = text.split('\n').filter { it.isNotBlank() }
            val trimmed = if (start > 0) lines.drop(1) else lines
            return trimmed.takeLast(LOAD_TAIL_LINES)
        }
    }
}
