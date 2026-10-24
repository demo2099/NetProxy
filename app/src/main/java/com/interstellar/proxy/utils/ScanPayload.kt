package com.interstellar.proxy.utils

import android.util.Base64
import java.net.URLDecoder

/**
 * 把扫到的二维码内容翻译成「能直接喂给导入流程」的东西。
 *
 * 对齐小火箭的扫码行为：扫到订阅地址 / 节点链接 / `shadowrocket://add/sub://…`
 * 都直接加，不给用户再来一遍「复制粘贴」。
 *
 * 之所以要专门做这一层，是因为二维码里装的东西五花八门：
 *   纯订阅地址、节点分享链接、小火箭伪协议、Clash 系伪协议、
 *   甚至整段 base64 的订阅正文。任何一条认不出来，用户看到的就是「扫了没反应」。
 */
sealed class ScanPayload {

    /** 订阅地址，走 URL 导入（会真的去下载）。 */
    data class Url(val url: String) : ScanPayload()

    /** 节点链接 / 配置文件正文，走文本导入（本地解析，不下载）。 */
    data class Text(val text: String, val kind: String) : ScanPayload()

    /** 认不出来的内容 —— 必须明确告诉用户，而不是静默什么都不做。 */
    data class Unsupported(val raw: String, val reason: String) : ScanPayload()

    companion object {

        private val NODE_SCHEMES = listOf(
            "ss://", "ssr://", "vmess://", "vless://", "trojan://",
            "hysteria://", "hysteria2://", "hy2://", "tuic://", "anytls://",
            "socks://", "socks5://",
        )

        /** 「安装配置」类伪协议：真正的内容在 url= 查询参数里。 */
        private val INSTALL_SCHEMES = listOf(
            "clash://", "stash://", "surge://", "loon://", "quantumult://",
            "shadowrocket://", "hiddify://", "sing-box://",
        )

        fun parse(rawInput: String): ScanPayload {
            val raw = rawInput.trim()
            if (raw.isEmpty()) return Unsupported(raw, "二维码里没有内容")

            // 1) 小火箭：shadowrocket://add/sub://<base64>
            //    ★ base64 后面可能还挂着 ?remark=xxx（旧版二维码），必须先切掉；
            //      不切的话整串当 base64 解会得到 nil —— 就是「扫完什么都不发生」。
            if (raw.startsWith("shadowrocket://add/sub://", true)) {
                val b64 = raw.substring("shadowrocket://add/sub://".length).substringBefore("?")
                decodeUrl(b64)?.let { return Url(it) }
            }

            // 2) 裸 sub://<base64>（Quantumult X / 部分机场用这个）
            if (raw.startsWith("sub://", true)) {
                val b64 = raw.substring("sub://".length).substringBefore("?")
                decodeUrl(b64)?.let { return Url(it) }
            }

            // 3) 各家「一键导入」伪协议：内容在 url= 参数里
            val lower = raw.lowercase()
            if (INSTALL_SCHEMES.any { lower.startsWith(it) }) {
                extractUrlParam(raw)?.let { return Url(it) }
            }

            // 4) 普通订阅地址
            if (raw.startsWith("http://", true) || raw.startsWith("https://", true)) {
                return Url(raw)
            }

            // 5) 节点分享链接（单条或多行）
            if (NODE_SCHEMES.any { raw.startsWith(it, true) }) {
                return Text(raw, "节点链接")
            }

            // 6) 整段 base64：可能是订阅正文（节点列表）被塞进了二维码
            decodeBase64(raw)?.let { decoded ->
                val text = decoded.trim()
                if (text.startsWith("http://", true) || text.startsWith("https://", true)) {
                    return Url(text)
                }
                if (NODE_SCHEMES.any { text.startsWith(it, true) } ||
                    text.contains("proxies:") || text.startsWith("{")
                ) {
                    return Text(text, "订阅内容")
                }
            }

            return Unsupported(raw, "这不是订阅地址或节点链接")
        }

        /** base64 → URL。标准字母表和 url-safe 都试一遍，缺 padding 也补上。 */
        private fun decodeUrl(b64: String): String? {
            val text = decodeBase64(b64)?.trim() ?: return null
            return if (text.startsWith("http://", true) || text.startsWith("https://", true)) text else null
        }

        private fun decodeBase64(value: String): String? {
            val cleaned = value.trim().replace("\n", "").replace("\r", "").replace(" ", "")
            if (cleaned.isEmpty()) return null
            // 补齐 padding：有些生成端会去掉结尾的 '='
            val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
            for (flags in intArrayOf(Base64.DEFAULT, Base64.URL_SAFE, Base64.NO_WRAP or Base64.URL_SAFE)) {
                try {
                    val bytes = Base64.decode(padded, flags)
                    if (bytes.isEmpty()) continue
                    val text = String(bytes, Charsets.UTF_8)
                    // 解出二进制垃圾时 String 里会出现大量替换符，直接丢弃
                    if (text.count { it == '\uFFFD' } > text.length / 10) continue
                    return text
                } catch (e: IllegalArgumentException) {
                    // 换下一种字母表
                }
            }
            return null
        }

        /**
         * 从 `clash://install-config?url=https%3A%2F%2F…` 这类链接里取出真正的订阅地址。
         * 参数名各家用得不一样：url / sub / subscribe / config。
         */
        private fun extractUrlParam(raw: String): String? {
            val query = raw.substringAfter('?', "")
            if (query.isEmpty()) return null
            for (pair in query.split('&')) {
                val key = pair.substringBefore('=', "").lowercase()
                if (key !in setOf("url", "sub", "subscribe", "config", "link")) continue
                val value = pair.substringAfter('=', "")
                if (value.isEmpty()) continue
                val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrNull() ?: value
                val trimmed = decoded.trim()
                if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
                    return trimmed
                }
                // 有些客户端把 url 参数本身又 base64 了一遍
                decodeUrl(trimmed)?.let { return it }
            }
            return null
        }
    }
}
