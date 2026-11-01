package com.interstellar.proxy

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.data.RulesStore
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.util.Locale

class InterstellarApplication : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()

        // 日志落盘 + 崩溃捕获要在最前面：这两样一旦晚装，之前发生的崩溃
        // 就彻底没痕迹了。日志页因此能看到「上次运行是怎么结束的」。
        runCatching {
            AppLog.install(filesDir)
            AppLog.installCrashHandler()
            AppLog.log(
                "app",
                "启动 v${BuildConfig.VERSION_NAME} · pid=${android.os.Process.myPid()}",
            )
        }

        // hev JNI bridge must register on a thread WITH a classloader (main);
        // a first touch from a coroutine IO thread aborts the whole VM
        runCatching { com.interstellar.proxy.core.TProxyService.preload() }

        runCatching {
            Libbox.setLocale(Locale.getDefault().toLanguageTag())
        }

        val baseDir = filesDir
        baseDir.mkdirs()
        val workingDir = getExternalFilesDir(null)
        val tempDir = cacheDir
        tempDir.mkdirs()
        workingDir?.mkdirs()

        if (workingDir != null) {
            setupLibbox(baseDir, workingDir, tempDir)
        }

        // warm the built-in rule sets copy in the background
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.IO) {
            runCatching { RulesStore.ensureRules(this@InterstellarApplication) }
            // regenerate the active config if missing (e.g. after a failed
            // generation in a previous run)
            if (com.interstellar.proxy.data.ConfigStore.readActiveConfig() == null) {
                runCatching { com.interstellar.proxy.data.SubscriptionRepository.regenerateActiveConfig() }
            }
        }

        // subscription auto-update schedule
        scheduleAutoUpdate()
    }

    private fun scheduleAutoUpdate() {
        runCatching {
            com.interstellar.proxy.data.UpdateWorker.reschedule(this)
        }
    }

    private fun setupLibbox(baseDir: java.io.File, workingDir: java.io.File, tempDir: java.io.File) {
        Libbox.setup(
            SetupOptions().also {
                it.basePath = baseDir.path
                it.workingPath = workingDir.path
                it.tempPath = tempDir.path
                it.logMaxLines = 3000
            },
        )
    }

    companion object {
        lateinit var application: InterstellarApplication
            private set
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
