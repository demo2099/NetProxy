package com.interstellar.proxy.core

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exec'd sidecar core process (mihomo / Xray) from nativeLibraryDir — the
 * only W^X-legal exec path on API 29+. Output goes to a rotating log file;
 * unexpected exits surface via [onExit] for the watchdog.
 */
class SidecarProcess(
    context: Context,
    private val soname: String,
    private val args: List<String>,
    private val workDir: File,
    private val onExit: (Int) -> Unit,
) {
    private val executable = File(context.applicationInfo.nativeLibraryDir, soname)
    @Volatile
    var process: Process? = null
        private set

    val running: Boolean get() = process?.isAlive == true

    private val stopped = AtomicBoolean(false)

    fun start(): Process {
        check(process?.isAlive != true) { "sidecar already running" }
        check(executable.isFile) { "sidecar binary missing: $executable" }
        stopped.set(false)
        workDir.mkdirs()
        val proc = ProcessBuilder(buildList {
            add(executable.absolutePath)
            addAll(args)
        })
            .directory(workDir)
            .redirectErrorStream(true)
            .start()
        process = proc
        // pump output to the rotating log; thread death == process exit
        Thread({
            runCatching {
                val log = logFile()
                val input = proc.inputStream.bufferedReader()
                while (true) {
                    val line = input.readLine() ?: break
                    appendLog(log, line)
                }
            }
            val code = runCatching { proc.waitFor() }.getOrDefault(-1)
            if (!stopped.get()) onExit(code)
        }, "sidecar-$soname").apply { isDaemon = true }.start()
        return proc
    }

    fun destroy() {
        stopped.set(true)
        process?.let { proc ->
            runCatching {
                proc.destroy()
                if (!proc.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    proc.destroyForcibly()
                }
            }
        }
        process = null
    }

    private fun logFile(): File {
        val active = File(workDir, "$soname.log")
        if (active.length() > LOG_LIMIT) {
            val rotated = File(workDir, "$soname.log.1")
            rotated.delete()
            active.renameTo(rotated)
        }
        return active
    }

    private fun appendLog(file: File, line: String) {
        file.appendText(line + "\n")
    }

    companion object {
        private const val LOG_LIMIT = 2L * 1024 * 1024
    }
}
