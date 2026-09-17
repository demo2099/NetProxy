package com.interstellar.proxy.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.interstellar.proxy.core.AppLog
import com.interstellar.proxy.data.Settings

/**
 * 开机自启。
 *
 * **只在"上次关机时代理是开着的"才拉起来** —— [Settings.tileActive] 就是用户意图
 * 的持久记录（连接成功置 true，主动断开置 false，快捷开关也同步它）。所以不会出现
 * "我明明断开了，重启手机它自己又起来"。
 *
 * 后台启动前台服务在 Android 12+ 默认是被禁的，但 `BOOT_COMPLETED` 是官方豁免
 * 之一；Android 15 又额外禁掉了几个 FGS 类型（dataSync / mediaPlayback / camera /
 * phoneCall / mediaProjection / microphone），我们用的 `systemExempted`（VPNService）
 * 和 `specialUse`（ProxyService）都不在禁用名单里。
 *
 * 注意：`android:exported` 必须是 true，否则系统（uid 1000）发不进来；
 * BOOT_COMPLETED 是 protected broadcast，只有系统能发，所以不担心被别的 App 触发。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Settings.tileActive) return
        AppLog.log("service", "开机自启: 上次退出时代理处于连接状态, 正在恢复")
        runCatching { BoxService.start() }.onFailure {
            AppLog.log("service", "开机自启失败: ${it.message}")
        }
    }
}
