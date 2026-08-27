package com.interstellar.proxy.data.net

import com.interstellar.proxy.InterstellarApplication
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Downloads subscription content, ported from interstellar-proxy's services/import.rs:
 * UA masquerades as clash-verge so panels return the subscription-userinfo header.
 *
 * When the core is running, requests go through the local mixed inbound
 * (127.0.0.1:2080) so they ride the selected node; falls back to direct.
 */
object SubscriptionFetcher {
    private const val MIXED_PORT = 2080

    private fun directClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun proxiedClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .proxy(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PORT)))
        .build()

    /** The command socket exists exactly while the core runs. */
    private fun coreRunning(): Boolean =
        File(InterstellarApplication.application.filesDir, "command.sock").exists()

    data class FetchResult(
        val body: String,
        val uploadBytes: Long = 0,
        val downloadBytes: Long = 0,
        val totalBytes: Long = 0,
        val expireSeconds: Long = 0,
        val suggestedName: String? = null,
        val viaProxy: Boolean = false,
    )

    fun fetch(url: String): FetchResult {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        val throughProxy = coreRunning()
        if (throughProxy) {
            try {
                return execute(proxiedClient(), request, viaProxy = true)
            } catch (e: Exception) {
                // proxy path failed (node down / core stopping) — retry direct
            }
        }
        return execute(directClient(), request, viaProxy = false)
    }

    private fun execute(client: OkHttpClient, request: Request, viaProxy: Boolean): FetchResult {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body!!.string()
            val info = response.header("subscription-userinfo")
                ?.let { parseSubscriptionUserinfo(it) }
            val name = response.header("Content-Disposition")
                ?.let { CONTENT_DISPOSITION.find(it)?.groupValues?.get(1) }
            return FetchResult(
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

    private const val USER_AGENT = "Interstellar/0.1 clash-verge/1.7.7 Android"
}
