package com.interstellar.proxy.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.core.content.FileProvider
import com.interstellar.proxy.BuildConfig
import com.interstellar.proxy.InterstellarApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** 一次可用的更新（远端版本比本机新）。 */
data class UpdateInfo(
    val version: String,
    val notes: String,
    val url: String,
    val size: Long,
    val sha256: String? = null,
    val fromMirror: Boolean = false,
    val assetName: String = "",
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val received: Long, val total: Long) : UpdateState
    data class Ready(val info: UpdateInfo, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * 应用内更新：查最新 Release → 下载对应 ABI 的 APK → 交给系统安装器。
 *
 * ## 走哪条路由
 * 不显式设置代理（和 [com.interstellar.proxy.data.net.SubscriptionFetcher] 一致）。
 * 注意 VPN 开着时本应用的请求会被自己的隧道接管，走的是当前节点：
 *   - 节点正常 → GitHub 反而更容易连通，但下载会消耗节点流量；
 *   - 节点全挂 → 连镜像也拿不到，需要先关掉代理再检查。
 * 想完全不消耗节点流量，就关掉 VPN 再更新。
 *
 * ## 两个源，先 GitHub 后自建镜像
 * 1. `api.github.com` —— 能直连时最快最准，资产按 ABI + flavor 精确匹配（约 30 MB）。
 * 2. `https://cctyt.de5.net/dl/netproxy-update.json` —— 国内直连 GitHub 基本不通，
 *    这时退到自己的机器上取包（universal，约 113 MB，慢但一定下得到）。
 * 两个都失败才报错，并给出「浏览器打开下载页」的出口。
 *
 * ## 安装
 * Android 8 起装 APK 需要「允许安装未知应用」。没授权时**不静默失败**，直接跳到
 * 该应用的授权页（`ACTION_MANAGE_UNKNOWN_APP_SOURCES`），用户回来再点一次即可。
 */
object AppUpdater {
    private const val REPO = "demo2099/NetProxy"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private const val MIRROR_BASE = "https://cctyt.de5.net"
    // ★ 文件名必须带 `-latest`：sub-edge 的 /dl/ 路由对「非 latest」文件发一年
    //   immutable 缓存，改名后 App 会一直读到旧 manifest。
    private const val MIRROR_MANIFEST = "$MIRROR_BASE/dl/netproxy-latest.json"

    /** 下载页兜底（浏览器打开）。 */
    const val DOWNLOAD_PAGE = "$MIRROR_BASE/"

    private const val MAX_NOTES_CHARS = 4000

    private val ua: String
        get() = "Interstellar/${BuildConfig.VERSION_NAME} (Android ${Build.VERSION.RELEASE})"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /**
     * 下载/检查跑在这里而不是 UI 的 rememberCoroutineScope：
     * 用户把对话框关掉，下载不能跟着一起死 —— 否则「点了下载又滑走」就白下。
     */
    private val workScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** 后台启动一次检查；失败只体现在 [state] 里，不抛给调用方。 */
    fun startCheck() {
        if (_state.value is UpdateState.Checking) return
        workScope.launch { runCatching { check() } }
    }

    /** 后台启动下载（同一时刻只允许一个）。 */
    fun startDownload(info: UpdateInfo) {
        if (_state.value is UpdateState.Downloading) return
        workScope.launch { download(info) }
    }

    // ---------------------------------------------------------------- 检查

    /**
     * 查一次更新。返回 null 表示已是最新；失败抛异常（state 会变成 Failed）。
     * 调用方通常是 UI 的 scope，异常要自己 catch。
     */
    suspend fun check(): UpdateInfo? {
        _state.value = UpdateState.Checking
        var info: UpdateInfo? = null
        var failure: Exception? = null

        try {
            info = fetchFromGithub()
        } catch (e: Exception) {
            failure = e
            try {
                info = fetchFromMirror()
            } catch (e2: Exception) {
                failure = e2
            }
        }

        return when {
            info != null -> {
                Settings.updateLatestVersion = info.version
                _state.value = UpdateState.Available(info)
                info
            }

            failure != null -> {
                val msg = friendly(failure)
                _state.value = UpdateState.Failed(msg)
                throw failure
            }

            else -> {
                Settings.updateLatestVersion = ""
                _state.value = UpdateState.UpToDate
                null
            }
        }
    }

    /**
     * 启动时静默检查：失败不打扰（不改 state），成功且有新版才置 Available。
     * 设置里关掉自动检查就完全不动网络。
     */
    suspend fun autoCheck() {
        if (!Settings.autoCheckUpdate) return
        when (_state.value) {
            is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Ready -> return
            else -> Unit
        }
        runCatching { check() }
    }

    private suspend fun fetchFromGithub(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_URL)
            .header("User-Agent", ua)
            .header("Accept", "application/vnd.github+json")
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub 返回 HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("GitHub 返回空内容")
            val json = JSONObject(body)
            // 草稿/预发布不上正式更新通道
            if (json.optBoolean("draft") || json.optBoolean("prerelease")) return@withContext null

            val tag = json.optString("tag_name")
            if (tag.isBlank() || !isNewer(tag, BuildConfig.VERSION_NAME)) return@withContext null

            val asset = pickAsset(json.optJSONArray("assets") ?: JSONArray())
                ?: throw IOException("Release 里没有匹配本机架构的安装包")

            UpdateInfo(
                version = tag.removePrefix("v"),
                notes = json.optString("body", "").take(MAX_NOTES_CHARS),
                url = asset.url,
                size = asset.size,
                sha256 = null,
                fromMirror = false,
                assetName = asset.name,
            )
        }
    }

    /**
     * 镜像 manifest 结构（自己维护，见 sub-edge `dl/netproxy-latest.json`）：
     * ```json
     * { "version": "0.8.0", "notes": "...",
     *   "assets": [ { "name": "...apk", "abi": "universal", "flavor": "slim",
     *                 "size": 113000000, "sha256": "...", "url": "/dl/xxx.apk" } ] }
     * ```
     */
    private suspend fun fetchFromMirror(): UpdateInfo? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(MIRROR_MANIFEST)
            .header("User-Agent", ua)
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("镜像返回 HTTP ${response.code}")
            val body = response.body?.string() ?: throw IOException("镜像返回空内容")
            val json = JSONObject(body)
            val version = json.optString("version").ifBlank { json.optString("tag_name") }
            if (version.isBlank() || !isNewer(version, BuildConfig.VERSION_NAME)) {
                return@withContext null
            }

            val abi = currentAbi()
            val flavor = BuildConfig.CORE_FLAVOR
            val assets = json.optJSONArray("assets") ?: JSONArray()
            var best: Asset? = null
            var bestSha: String? = null

            for (i in 0 until assets.length()) {
                val o = assets.optJSONObject(i) ?: continue
                val url = absoluteUrl(o.optString("url"))
                if (url.isBlank()) continue
                val a = o.optString("abi")
                val f = o.optString("flavor")
                // abi 留空 = 通用包；flavor 留空 = 两套都能用（实际只有 slim 放镜像）
                if (a.isNotBlank() && a != abi && a != "universal") continue
                if (f.isNotBlank() && f != flavor && f != "slim") continue
                if (best != null) continue
                best = Asset(
                    name = o.optString("name").ifBlank { url.substringAfterLast('/') },
                    url = url,
                    size = o.optLong("size", 0),
                )
                bestSha = o.optString("sha256").ifBlank { null }
            }

            val asset = best ?: throw IOException("镜像里没有可用的安装包")
            UpdateInfo(
                version = version.removePrefix("v"),
                notes = json.optString("notes", "").take(MAX_NOTES_CHARS),
                url = asset.url,
                size = asset.size,
                sha256 = bestSha,
                fromMirror = true,
                assetName = asset.name,
            )
        }
    }

    // ---------------------------------------------------------------- 下载

    /** 下载并落到 cacheDir/update/app.apk；进度通过 [state] 广播。 */
    suspend fun download(info: UpdateInfo) {
        if (info.url.isBlank()) {
            _state.value = UpdateState.Failed("下载地址为空，请重新检查更新")
            return
        }
        _state.value = UpdateState.Downloading(info, 0, info.size)
        try {
            val file = withContext(Dispatchers.IO) {
                val dir = File(InterstellarApplication.application.cacheDir, "update")
                if (!dir.exists() && !dir.mkdirs()) throw IOException("无法创建下载目录")
                dir.listFiles()?.forEach { it.delete() }

                val tmp = File(dir, "app.apk.part")
                val dst = File(dir, "app.apk")

                val request = Request.Builder()
                    .url(info.url)
                    .header("User-Agent", ua)
                    .build()

                http.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("响应为空")
                    val total = body.contentLength().let { if (it > 0) it else info.size }
                    body.byteStream().use { input ->
                        tmp.outputStream().use { output ->
                            val buf = ByteArray(128 * 1024)
                            var received = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n <= 0) break
                                output.write(buf, 0, n)
                                received += n
                                _state.value = UpdateState.Downloading(info, received, total)
                            }
                        }
                    }
                }

                if (tmp.length() < 64 * 1024) {
                    tmp.delete()
                    throw IOException("安装包不完整（${tmp.length()} B）")
                }
                info.sha256?.let { expect ->
                    val actual = sha256(tmp)
                    if (!actual.equals(expect, ignoreCase = true)) {
                        tmp.delete()
                        throw IOException("安装包校验失败（sha256 不匹配）")
                    }
                }
                if (dst.exists()) dst.delete()
                if (!tmp.renameTo(dst)) throw IOException("无法保存安装包")
                dst
            }
            _state.value = UpdateState.Ready(info, file)
        } catch (e: Exception) {
            _state.value = UpdateState.Failed("下载失败：${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ---------------------------------------------------------------- 安装

    /** 有没有「安装未知应用」的授权。Android 8 以下不需要。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** 跳到本应用的「安装未知应用」授权页。 */
    fun openInstallPermission(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    SystemSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 拉起系统安装器。返回 false 表示卡在授权（已经跳到设置页），调用方应提示用户回来再点。
     */
    fun install(context: Context, file: File): Boolean {
        if (!file.exists()) return false
        if (!canInstall(context)) {
            openInstallPermission(context)
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    /** 用户点了「以后再说」：记住这个版本，不再主动弹。 */
    fun skipVersion(version: String) {
        Settings.updateSkippedVersion = version
    }

    fun resetToIdle() {
        when (_state.value) {
            is UpdateState.Failed, is UpdateState.UpToDate -> _state.value = UpdateState.Idle
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- 工具

    private data class Asset(val name: String, val url: String, val size: Long)

    /** 本机实际能跑的 ABI，按优先级取第一个（64 位优先）。 */
    private fun currentAbi(): String {
        val supported = Build.SUPPORTED_ABIS
        for (abi in listOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")) {
            if (supported.contains(abi)) return abi
        }
        return "arm64-v8a"
    }

    /**
     * 资产候选名，按优先级排列。CI 的命名规则见 app/build.gradle.kts：
     * `interstellar-<flavor>-<abi>-release.apk`。
     */
    private fun assetCandidates(): List<String> {
        val flavor = BuildConfig.CORE_FLAVOR
        val abi = currentAbi()
        return listOf(
            "interstellar-$flavor-$abi-release.apk",
            "interstellar-$flavor-universal-release.apk",
            "interstellar-slim-$abi-release.apk",
            "interstellar-slim-universal-release.apk",
        ).distinct()
    }

    private fun pickAsset(assets: JSONArray): Asset? {
        val byName = HashMap<String, Asset>()
        for (i in 0 until assets.length()) {
            val o = assets.optJSONObject(i) ?: continue
            val name = o.optString("name")
            val url = o.optString("browser_download_url")
            if (name.isBlank() || url.isBlank()) continue
            byName[name] = Asset(name, url, o.optLong("size", 0))
        }
        for (candidate in assetCandidates()) {
            byName[candidate]?.let { return it }
        }
        return null
    }

    private fun absoluteUrl(url: String): String = when {
        url.isBlank() -> ""
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("/") -> MIRROR_BASE + url
        else -> "$MIRROR_BASE/$url"
    }

    private fun parseVersion(v: String): List<Int> =
        v.trim().removePrefix("v").substringBefore('-').split('.')
            .map { part -> part.filter { it.isDigit() }.toIntOrNull() ?: 0 }

    private fun isNewer(remote: String, local: String): Boolean {
        val a = parseVersion(remote)
        val b = parseVersion(local)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(128 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun friendly(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "连不上 GitHub（域名解析失败），镜像也不可达"
        is java.net.SocketTimeoutException -> "连接超时，换网络后重试"
        is javax.net.ssl.SSLException -> "TLS 握手失败：${e.message}"
        else -> e.message ?: e.javaClass.simpleName
    }
}
