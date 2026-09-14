package com.interstellar.proxy.bg

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.PlatformInterface
import com.interstellar.proxy.MainActivity
import com.interstellar.proxy.R
import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.constant.Action
import com.interstellar.proxy.constant.Alert
import com.interstellar.proxy.constant.Status
import com.interstellar.proxy.core.ApiPort
import com.interstellar.proxy.core.CoreEngines
import com.interstellar.proxy.core.CoreHost
import com.interstellar.proxy.core.CoreOverrides
import com.interstellar.proxy.core.ProxyCore
import com.interstellar.proxy.core.SystemProxyState
import com.interstellar.proxy.data.ConfigStore
import com.interstellar.proxy.data.Settings
import com.interstellar.proxy.ktx.hasPermission
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class BoxService(private val service: Service, private val platformInterface: PlatformInterface) :
    CoreHost {
    companion object {
        private const val TAG = "BoxService"

        fun start() {
            start(Settings.serviceClass())
        }

        /** Core without VPN — used to url-test while the UI stays 未连接. */
        fun startHeadless() {
            start(ProxyService::class.java)
        }

        private fun start(clazz: Class<*>) {
            ContextCompat.startForegroundService(
                InterstellarApplication.application,
                Intent(InterstellarApplication.application, clazz),
            )
        }

        fun stop() {
            InterstellarApplication.application.sendBroadcast(
                Intent(Action.SERVICE_CLOSE).setPackage(InterstellarApplication.application.packageName),
            )
        }

        fun notifyStopped() {
            InterstellarApplication.application.sendBroadcast(
                Intent(Action.SERVICE_STOPPED).setPackage(InterstellarApplication.application.packageName),
            )
        }
    }

    var fileDescriptor: ParcelFileDescriptor? = null

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val notification = ServiceNotification(status, service)
    private var core: ProxyCore? = null

    private var receiverRegistered = false

    /**
     * A start intent that arrived while the previous run was still tearing
     * down (core switching stops-then-starts): honor it by restarting in
     * place once the shutdown finishes, instead of dropping it.
     */
    @Volatile
    private var pendingRestart = false
    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Action.SERVICE_CLOSE -> {
                        stopService()
                    }

                    PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            serviceUpdateIdleMode()
                        }
                    }
                }
            }
        }

    private fun buildOverrides() =
        CoreOverrides(
            autoRedirect = Settings.autoRedirect,
            perAppEnabled = Settings.perAppProxyEnabled,
            perAppInclude = Settings.perAppProxyMode == Settings.PER_APP_PROXY_INCLUDE,
            perAppPackages = Settings.perAppProxyList,
            selectedTag = Settings.selectedOutboundTag.takeIf { it.isNotBlank() },
        )

    private suspend fun startCore() {
        com.interstellar.proxy.core.AppLog.log("service", "启动内核 ${Settings.coreKind.displayName}")
        core = CoreEngines.create(Settings.coreKind, platformInterface, this).also { it.startup() }
    }

    private suspend fun startService() {
        try {
            if (status.value != Status.Starting) return
            withContext(Dispatchers.Main) {
                notification.show(service.getString(R.string.app_tagline), R.string.status_starting)
            }

            val content = ConfigStore.readActiveConfig()
            if (content == null) {
                stopAndAlert(Alert.EmptyConfiguration)
                return
            }

            DefaultNetworkMonitor.start()

            try {
                core?.applyConfig(content, buildOverrides())
            } catch (e: Exception) {
                stopAndAlert(Alert.CreateService, e.message)
                return
            }
            // 内核活着 = 控制端口被它占着，在它退出前不能再换端口
            // （换了下一次热重载就会打到没人监听的端口上）
            ApiPort.pin()

            if (core?.needWifiState() == true) {
                val wifiPermission =
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        android.Manifest.permission.ACCESS_FINE_LOCATION
                    } else {
                        android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                    }
                if (!service.hasPermission(wifiPermission)) {
                    stopAndAlert(Alert.RequestLocationPermission)
                    return
                }
            }

            if (status.value != Status.Starting) return
            android.util.Log.d("InterstellarUI", "core STARTED")
            status.postValue(Status.Started)
            withContext(Dispatchers.Main) {
                notification.show(service.getString(R.string.app_tagline), R.string.status_started)
            }
            notification.start()
        } catch (e: Exception) {
            stopAndAlert(Alert.StartService, e.message)
            return
        }
    }

    // ---- CoreHost: callbacks from the active engine ----

    @OptIn(DelicateCoroutinesApi::class)
    override fun onCoreRequestStop() {
        // Core dropped the tun (VPN revoked, another app took the
        // system proxy, crash). Tear the Android service down so a
        // later start isn't blocked on Status.Starting.
        GlobalScope.launch(Dispatchers.Main) {
            stopService()
        }
    }

    override fun onCoreRequestReload() {
        serviceReload()
    }

    override fun systemProxyState(): SystemProxyState? {
        val vpn = service as? VPNService ?: return null
        return SystemProxyState(vpn.systemProxyAvailable, vpn.systemProxyEnabled)
    }

    override fun onSetSystemProxy(enabled: Boolean) {
        serviceReload()
    }

    override fun openSidecarTun(spec: com.interstellar.proxy.core.SidecarTunSpec): Int? =
        (service as? VPNService)?.establishSidecarTun(spec)

    override fun onCoreTraffic(upPerSecond: Long, downPerSecond: Long) {
        notification.updateTraffic(upPerSecond, downPerSecond)
    }

    fun serviceReload() {
        runBlocking {
            serviceReload0()
        }
    }

    suspend fun serviceReload0() {
        val content = ConfigStore.readActiveConfig()
        if (content == null) {
            stopAndAlert(Alert.EmptyConfiguration)
            return
        }
        try {
            core?.applyConfig(content, buildOverrides())
        } catch (e: Exception) {
            stopAndAlert(Alert.CreateService, e.message)
            return
        }

        if (core?.needWifiState() == true) {
            val wifiPermission =
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                } else {
                    android.Manifest.permission.ACCESS_BACKGROUND_LOCATION
                }
            if (!service.hasPermission(wifiPermission)) {
                stopAndAlert(Alert.RequestLocationPermission)
                return
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun serviceUpdateIdleMode() {
        if (InterstellarApplication.powerManager.isDeviceIdleMode) {
            core?.pause()
        } else {
            core?.wake()
        }
    }

    @OptIn(DelicateCoroutinesApi::class)
    private fun stopService() {
        val current = status.value
        if (current == Status.Stopped || current == Status.Stopping) return
        status.value = Status.Stopping
        notifyStopped()
        if (receiverRegistered) {
            service.unregisterReceiver(receiver)
            receiverRegistered = false
        }
        notification.close()
        GlobalScope.launch(Dispatchers.IO) {
            val pfd = fileDescriptor
            if (pfd != null) {
                pfd.close()
                fileDescriptor = null
            }
            DefaultNetworkMonitor.stop()
            core?.shutdown()
            core = null
            // 内核真的没了才放开端口；否则"刚停就起"会误判成冲突而白换端口
            ApiPort.release()
            withContext(Dispatchers.Main) {
                status.value = Status.Stopped
                if (pendingRestart) {
                    pendingRestart = false
                    onStartCommand()
                } else {
                    service.stopSelf()
                }
            }
        }
    }

    private suspend fun stopAndAlert(type: Alert, message: String? = null) {
        val detail = humanize(message)
        android.util.Log.e("InterstellarUI", "service stopped: $type msg=$detail", Throwable("trace"))
        com.interstellar.proxy.core.AppLog.log("service", "已停止: $type${detail?.let { " · $it" } ?: ""}")
        val pfd = fileDescriptor
        if (pfd != null) {
            pfd.close()
            fileDescriptor = null
        }
        DefaultNetworkMonitor.stop()
        core?.shutdown()
        core = null
        ApiPort.release()
        withContext(Dispatchers.Main) {
            if (receiverRegistered) {
                service.unregisterReceiver(receiver)
                receiverRegistered = false
            }
            notification.close()
            binder.broadcast { callback ->
                callback.onServiceAlert(type.ordinal, detail)
            }
            status.value = Status.Stopped
            notifyStopped()
            service.stopSelf()
        }
    }

    /**
     * 内核启动失败信息里最容易被误读的一条：控制端口被占。sing-box 和 mihomo 都
     * 把它当**致命错误**（整个配置起不来，不是某个节点的问题），而原文只有一句
     * 英文，看不出跟端口有关：
     *
     *   finish-start clash server: external controller listen error:
     *   listen tcp 127.0.0.1:9090: bind: address already in use
     *
     * 注意 Android 不允许 App 去 kill 别的进程占着的 socket，所以这里给不出"杀掉
     * 端口"的办法 —— 能做的只有绕开，而绕开在 [ApiPort.acquire] 里已经自动做了。
     */
    private fun humanize(message: String?): String? {
        if (message == null) return null
        if (!message.contains("address already in use", ignoreCase = true)) return message
        return "$message · 内核控制端口被别的程序占着（多半是另一个 Clash 客户端，" +
            "或者上次没退干净的内核）。Android 不允许 App 结束别的进程，杀不掉这个端口 —— " +
            "已自动改用空闲端口，重试即可"
    }

    @OptIn(DelicateCoroutinesApi::class)
    @Suppress("SameReturnValue")
    internal fun onStartCommand(): Int {
        when (status.value) {
            Status.Starting, Status.Started -> return Service.START_NOT_STICKY

            // still tearing down the previous run — run again right after
            Status.Stopping -> {
                pendingRestart = true
                return Service.START_NOT_STICKY
            }

            null, Status.Stopped -> Unit
        }
        status.value = Status.Starting

        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                service,
                receiver,
                IntentFilter().apply {
                    addAction(Action.SERVICE_CLOSE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }

        GlobalScope.launch(Dispatchers.IO) {
            try {
                startCore()
            } catch (e: Exception) {
                stopAndAlert(Alert.StartCommandServer, e.message)
                return@launch
            }
            if (status.value != Status.Starting) return@launch
            startService()
        }
        return Service.START_NOT_STICKY
    }

    internal fun onBind(): IBinder = binder

    internal fun onDestroy() {
        binder.close()
    }

    internal fun onRevoke() {
        stopService()
    }

    internal fun sendNotification(notification: Notification) {
        val channel = "notification-${notification.typeID}"
        val builder =
            NotificationCompat.Builder(service, channel).setShowWhen(false)
                .setContentTitle(notification.title).setContentText(notification.body)
                .setOnlyAlertOnce(true).setSmallIcon(R.drawable.ic_stat)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setPriority(NotificationCompat.PRIORITY_HIGH).setAutoCancel(true)
        if (!notification.subtitle.isNullOrBlank()) {
            builder.setContentInfo(notification.subtitle)
        }
        if (!notification.openURL.isNullOrBlank()) {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    service,
                    0,
                    Intent(service, MainActivity::class.java).apply {
                        setFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                        data = Uri.parse(notification.openURL)
                    },
                    ServiceNotification.flags,
                ),
            )
        }
        GlobalScope.launch(Dispatchers.Main) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                InterstellarApplication.notification.createNotificationChannel(
                    NotificationChannel(
                        channel,
                        notification.typeName,
                        NotificationManager.IMPORTANCE_HIGH,
                    ),
                )
            }
            InterstellarApplication.notification.notify(notification.identifier, notification.typeID, builder.build())
        }
    }

    internal fun cancelNotification(identifier: String, typeID: Int) {
        GlobalScope.launch(Dispatchers.Main) {
            InterstellarApplication.notification.cancel(identifier, typeID)
        }
    }
}
