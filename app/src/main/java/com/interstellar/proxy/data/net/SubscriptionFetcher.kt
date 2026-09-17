package com.interstellar.proxy.data.net

import android.os.Build
import com.interstellar.proxy.InterstellarApplication
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Downloads subscription content, ported from interstellar-proxy's services/import.rs:
 * UA masquerades as clash-verge so panels return the subscription-userinfo header,
 * with the device model appended so the panel's subscribe log can tell devices apart.
 * See [USER_AGENT] — the clash-verge part must not change.
 *
 * 默认**直连**，不跟随内核状态。订阅更新是"代理坏了之后"的修复入口，走代理等于自锁
 * —— 节点全挂时更新订阅正是唯一出路。只有用户显式打开「更新走代理」才走
 * 127.0.0.1:2080 的混合入站（见 [fetch]）。
 */
object SubscriptionFetcher {
    private const val MIXED_PORT = 2080

    private fun directClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
        .build()

    /**
     * Per-core liveness (same split as NetProbe): sing-box owns the command
     * socket, sidecar cores expose their Holder handles.
     */
    private fun coreRunning(): Boolean = when (com.interstellar.proxy.data.Settings.coreKind) {
        com.interstellar.proxy.core.CoreKind.SINGBOX ->
            File(InterstellarApplication.application.filesDir, "command.sock").exists()

        com.interstellar.proxy.core.CoreKind.MIHOMO ->
            com.interstellar.proxy.core.MihomoCore.Holder.instance != null

        com.interstellar.proxy.core.CoreKind.XRAY ->
            com.interstellar.proxy.core.XrayCore.Holder.instance != null
    }

    data class FetchResult(
        val body: String,
        val uploadBytes: Long = 0,
        val downloadBytes: Long = 0,
        val totalBytes: Long = 0,
        val expireSeconds: Long = 0,
        val suggestedName: String? = null,
        val viaProxy: Boolean = false,
    )

    suspend fun fetch(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        // 默认直连，且**不做自动回退**：走哪条路由用户的设置唯一决定。
        //   - 关（默认）：只直连。订阅更新是修代理的入口，不能依赖代理本身；
        //     而且机场面板会记录订阅请求的来源 IP，经代理会把落地 IP 暴露给面板。
        //   - 开：只走代理（内核没跑就没代理可用，只能直连）。不回退直连是因为
        //     用户开这个开关通常正是不想让本机 IP 出现在订阅请求里。
        // 静默回退（无论哪个方向）都会让"实际走了哪条路"变得不可知，
        // 失败原因也就无从判断 —— 宁可明确报错。
        val viaProxy = com.interstellar.proxy.data.Settings.subscriptionViaProxy && coreRunning()
        return executeCancellable(
            client = if (viaProxy) proxiedClient() else directClient(),
            request = request,
            viaProxy = viaProxy,
        )
    }

    /** Enqueue + invokeOnCancellation so the dialog's 取消 aborts the socket. */
    private suspend fun executeCancellable(
        client: OkHttpClient,
        request: Request,
        viaProxy: Boolean,
    ): FetchResult = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                if (cont.isActive) cont.resumeWith(kotlin.Result.failure(e))
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val parsed = runCatching {
                    response.use {
                        if (!it.isSuccessful) error("HTTP ${it.code}")
                        val body = it.body!!.string()
                        val info = it.header("subscription-userinfo")
                            ?.let { h -> parseSubscriptionUserinfo(h) }
                        val name = it.header("Content-Disposition")
                            ?.let { h -> CONTENT_DISPOSITION.find(h)?.groupValues?.get(1) }
                        FetchResult(
                            body = body,
                            uploadBytes = info?.get("upload") ?: 0,
                            downloadBytes = info?.get("download") ?: 0,
                            totalBytes = info?.get("total") ?: 0,
                            expireSeconds = info?.get("expire") ?: 0,
                            suggestedName = name,
                            viaProxy = viaProxy,
                        )
                    }
                }
                if (cont.isActive) cont.resumeWith(parsed)
            }
        })
    }

    // upload=123; download=456; total=789; expire=1750000000
    private fun parseSubscriptionUserinfo(header: String): Map<String, Long> {
        return header.split(';')
            .mapNotNull { part ->
                val key = part.substringBefore('=').trim().lowercase()
                val value = part.substringAfter('=', "").trim().toLongOrNull() ?: return@mapNotNull null
                key to value
            }.toMap()
    }

    private val CONTENT_DISPOSITION = Regex("""filename\s*=\s*"?([^";]+)"?""")

    /**
     * 面板按 UA 决定返回哪种格式（含 `clash-verge` 才回 Clash YAML，否则可能是
     * sing-box JSON 或 base64 节点列表），所以 [BASE_UA] 这一段**一个字都不能改**。
     *
     * 后面追加机型：机场面板的订阅日志记录的就是这个 UA，带上机型才认得出是哪台设备。
     * 空格换成下划线，免得面板按空格切分时把机型拆成两个 token。
     */
    private val USER_AGENT: String by lazy {
        val label = deviceLabel()
        if (label.isEmpty()) BASE_UA else "$BASE_UA $label"
    }

    private const val BASE_UA = "Interstellar/0.1 clash-verge/1.7.7 Android"

    /**
     * `Xiaomi_14_Ultra` / `samsung_SM_G9910` / `Google_Pixel_7_Pro`。
     * 厂商名已含在机型里就不重复拼（小米/红米的 MODEL 常自带前缀）。取不到返回空串。
     */
    private fun deviceLabel(): String = runCatching {
        val manufacturer = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val label = when {
            model.isBlank() -> manufacturer
            manufacturer.isBlank() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        label.trim().replace(Regex("\\s+"), "_")
    }.getOrDefault("")
}
