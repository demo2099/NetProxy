package com.interstellar.proxy.data.subscription

import android.util.Base64
import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLDecoder
import java.util.UUID

/**
 * Parses plaintext share links (ss:// vmess:// vless:// trojan:// hysteria2:// tuic:// anytls://),
 * ported from interstellar-proxy's subscription/uri.rs.
 */
object UriParser {

    fun parseUriList(content: String, subscriptionId: String? = null): List<ProxyNode> {
        return content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            .mapNotNull { line -> runCatching { parseUriLine(line, subscriptionId) }.getOrNull() }
            .toList()
    }

    fun parseUriLine(line: String, subscriptionId: String? = null): ProxyNode? {
        val scheme = line.substringBefore("://").lowercase()
        val body = line.substringAfter("://", "")
        if (body.isEmpty()) return null
        return when (scheme) {
            "ss" -> parseShadowsocks(line, subscriptionId)
            "vmess" -> parseVmess(line, subscriptionId)
            "vless" -> parseVless(body, subscriptionId)
            "trojan" -> parseTrojan(body, subscriptionId)
            "hysteria2", "hy2" -> parseHysteria2(body, subscriptionId)
            "tuic" -> parseTuic(body, subscriptionId)
            "anytls" -> parseAnytls(body, subscriptionId)
            "socks", "socks5" -> parseSocks(body, subscriptionId)
            "http", "https" -> parseHttp(body, subscriptionId)
            else -> null
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun decodeBase64(text: String): String {
        val normalized = text.replace("-", "+").replace("_", "/")
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        val bytes = runCatching {
            Base64.decode(padded, Base64.NO_WRAP)
        }.getOrNull() ?: return ""
        return String(bytes, Charsets.UTF_8)
    }

    private fun Map<String, List<String>>.firstOrNull(key: String): String? =
        this[key]?.firstOrNull()

    // ss://base64(method:password)@host:port?plugin=...#name   (SIP002)
    // ss://base64(method:password@host:port)#name              (legacy)
    private fun parseShadowsocks(line: String, subId: String?): ProxyNode? {
        val hashIndex = line.indexOf('#')
        val name = if (hashIndex >= 0) {
            URLDecoder.decode(line.substring(hashIndex + 1), "UTF-8")
        } else ""
        val body = line.substringAfter("://").substringBefore("#").substringBefore("?")
        val query = parseQuery(line.substringAfter("://").substringAfter("?", "").substringBefore("#"))
        val plugin = query.firstOrNull("plugin")

        val userInfo: String
        val hostPort: String
        val at = body.lastIndexOf('@')
        if (at >= 0) {
            val userPart = body.substring(0, at)
            hostPort = body.substring(at + 1)
            userInfo = if (userPart.contains(':')) userPart else decodeBase64(userPart)
        } else {
            val decoded = decodeBase64(body)
            val decodedAt = decoded.lastIndexOf('@')
            if (decodedAt < 0) return null
            userInfo = decoded.substring(0, decodedAt)
            hostPort = decoded.substring(decodedAt + 1)
        }

        val method = userInfo.substringBefore(':')
        val password = userInfo.substringAfter(':', "")
        val host = hostPort.substringBeforeLast(':')
        val port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        if (host.isBlank() || method.isBlank()) return null

        var node = ProxyNode(
            id = newId(),
            name = name.ifBlank { "$host:$port" },
            type = NodeType.SHADOWSOCKS,
            server = host,
            port = port,
            method = method,
            password = password,
            subscriptionId = subId,
        )
        if (plugin != null) {
            val pluginName = plugin.substringBefore(';')
            val opts = plugin.substringAfter(';', "").split(';')
                .mapNotNull { opt ->
                    val k = opt.substringBefore('=')
                    val v = opt.substringAfter('=', "")
                    if (k.isBlank()) null else k to v
                }.toMap()
            // clash-style "obfs" maps to sing-box's "obfs-local"
            val mapped = if (pluginName == "obfs") "obfs-local" else pluginName
            node = node.copy(plugin = mapped, pluginOpts = opts.ifEmpty { null })
        }
        return node
    }

    // vmess://base64(json)
    private fun parseVmess(line: String, subId: String?): ProxyNode? {
        val json = runCatching {
            Json.parseToJsonElement(decodeBase64(line.substringAfter("://"))).jsonObject
        }.getOrNull() ?: return null
        val host = json["add"]?.jsonPrimitive?.content ?: return null
        val port = json["port"]?.jsonPrimitive?.content?.toIntOrNull() ?: return null
        val uuid = json["id"]?.jsonPrimitive?.content ?: return null
        val net = json["net"]?.jsonPrimitive?.content ?: "tcp"
        val path = json["path"]?.jsonPrimitive?.content?.ifBlank { null }
        val hostHeader = json["host"]?.jsonPrimitive?.content?.ifBlank { null }
        return ProxyNode(
            id = newId(),
            name = json["ps"]?.jsonPrimitive?.content?.ifBlank { null } ?: "$host:$port",
            type = NodeType.VMESS,
            server = host,
            port = port,
            uuid = uuid,
            alterId = json["aid"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0,
            security = json["scy"]?.jsonPrimitive?.content?.ifBlank { null } ?: "auto",
            network = net,
            tls = json["tls"]?.jsonPrimitive?.content == "tls",
            sni = json["sni"]?.jsonPrimitive?.content?.ifBlank { null },
            fingerprint = json["fp"]?.jsonPrimitive?.content?.ifBlank { null },
            alpn = json["alpn"]?.jsonPrimitive?.content?.split(',')?.map { it.trim() }?.takeIf { it.isNotEmpty() },
            wsPath = path?.takeIf { net == "ws" },
            grpcServiceName = path?.takeIf { net == "grpc" },
            httpPath = path?.takeIf { net == "h2" || net == "http" },
            httpHost = hostHeader?.takeIf { net == "h2" || net == "http" }?.split(',')?.map { it.trim() },
            headers = hostHeader?.takeIf { net == "ws" }?.let { mapOf("Host" to it) },
            subscriptionId = subId,
        )
    }

    // vless://uuid@host:port?type=ws&security=reality&pbk=...&sni=...#name
    private fun parseVless(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.VLESS, subId) { uuid, query ->
            copy(
                uuid = uuid,
                flow = query.firstOrNull("flow"),
                network = query.firstOrNull("type") ?: "tcp",
                tls = query.firstOrNull("security") in setOf("tls", "reality"),
                sni = query.firstOrNull("sni") ?: query.firstOrNull("peer"),
                alpn = query.firstOrNull("alpn")?.split(',')?.map { it.trim() },
                fingerprint = query.firstOrNull("fp"),
                reality = query.firstOrNull("pbk")?.let {
                    ProxyNode.RealityParams(it, query.firstOrNull("sid"))
                },
                insecure = query.firstOrNull("allowInsecure")?.toBoolean()
                    ?: query.firstOrNull("insecure")?.toBoolean(),
                wsPath = query.firstOrNull("path")?.let { URLDecoder.decode(it, "UTF-8") },
                grpcServiceName = query.firstOrNull("serviceName"),
                headers = query.firstOrNull("host")?.takeIf { it.isNotBlank() }?.let { mapOf("Host" to it) },
            )
        }

    private fun parseTrojan(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.TROJAN, subId) { password, query ->
            copy(
                password = URLDecoder.decode(password, "UTF-8"),
                network = query.firstOrNull("type") ?: "tcp",
                sni = query.firstOrNull("sni") ?: query.firstOrNull("peer"),
                alpn = query.firstOrNull("alpn")?.split(',')?.map { it.trim() },
                fingerprint = query.firstOrNull("fp"),
                insecure = query.firstOrNull("allowInsecure")?.toBoolean()
                    ?: query.firstOrNull("allowinsecure")?.toBoolean(),
                wsPath = query.firstOrNull("path")?.let { URLDecoder.decode(it, "UTF-8") },
                grpcServiceName = query.firstOrNull("serviceName"),
                headers = query.firstOrNull("host")?.takeIf { it.isNotBlank() }?.let { mapOf("Host" to it) },
            )
        }

    private fun parseHysteria2(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.HYSTERIA2, subId) { password, query ->
            copy(
                password = URLDecoder.decode(password, "UTF-8").ifBlank { null },
                sni = query.firstOrNull("sni") ?: query.firstOrNull("peer"),
                insecure = (query.firstOrNull("insecure") ?: query.firstOrNull("allowInsecure"))?.toBoolean(),
                hy2ObfsPassword = query.firstOrNull("obfs-password"),
                upMbps = query.firstOrNull("up")?.toIntOrNull(),
                downMbps = query.firstOrNull("down")?.toIntOrNull(),
                alpn = query.firstOrNull("alpn")?.split(',')?.map { it.trim() },
            )
        }

    private fun parseTuic(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.TUIC, subId) { userinfo, query ->
            copy(
                uuid = userinfo.substringBefore(':').takeIf { it.isNotBlank() },
                password = userinfo.substringAfter(':').takeIf { userinfo.contains(':') && it.isNotBlank() },
                sni = query.firstOrNull("sni"),
                alpn = query.firstOrNull("alpn")?.split(',')?.map { it.trim() },
                congestionControl = query.firstOrNull("congestion_control"),
                udpRelayMode = query.firstOrNull("udp_relay_mode"),
                reduceRtt = query.firstOrNull("reduce_rtt")?.toBoolean(),
                insecure = query.firstOrNull("allow_insecure")?.toBoolean(),
            )
        }

    private fun parseAnytls(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.ANYTLS, subId) { password, query ->
            copy(
                password = URLDecoder.decode(password, "UTF-8").ifBlank { null },
                sni = query.firstOrNull("sni"),
                insecure = query.firstOrNull("insecure")?.toBoolean(),
            )
        }

    private fun parseSocks(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.SOCKS, subId) { userinfo, query ->
            copy(
                tls = false,
                username = userinfo.substringBefore(':').takeIf { it.isNotBlank() },
                password = userinfo.substringAfter(':').takeIf { userinfo.contains(':') && it.isNotBlank() },
                sni = query.firstOrNull("sni"),
            )
        }

    private fun parseHttp(body: String, subId: String?): ProxyNode? =
        parseGenericUserinfoUri(body, NodeType.HTTP, subId) { userinfo, query ->
            copy(
                tls = false,
                username = userinfo.substringBefore(':').takeIf { it.isNotBlank() },
                password = userinfo.substringAfter(':').takeIf { userinfo.contains(':') && it.isNotBlank() },
                sni = query.firstOrNull("sni"),
            )
        }

    /**
     * Common shape: scheme://userinfo@host:port?query#fragment
     */
    private inline fun parseGenericUserinfoUri(
        body: String,
        type: NodeType,
        subId: String?,
        transform: ProxyNode.(userinfo: String, query: Map<String, List<String>>) -> ProxyNode,
    ): ProxyNode? {
        val fragmentStart = body.indexOf('#')
        val name = if (fragmentStart >= 0) {
            URLDecoder.decode(body.substring(fragmentStart + 1), "UTF-8")
        } else ""
        val main0 = if (fragmentStart >= 0) body.substring(0, fragmentStart) else body
        val queryStr = main0.substringAfter('?', "")
        val main = main0.substringBefore('?')
        val query = parseQuery(queryStr)

        val at = main.lastIndexOf('@')
        if (at < 0) return null
        val userinfo = main.substring(0, at)
        val hostPort = main.substring(at + 1)
        val host: String
        val port: Int
        if (hostPort.startsWith("[")) {
            host = hostPort.substringAfter('[').substringBefore(']')
            port = hostPort.substringAfter("]:", "").toIntOrNull() ?: return null
        } else {
            host = hostPort.substringBeforeLast(':')
            port = hostPort.substringAfterLast(':').toIntOrNull() ?: return null
        }
        if (host.isBlank()) return null

        val template = ProxyNode(
            id = newId(),
            name = name.ifBlank { "$host:$port" },
            type = type,
            server = host,
            port = port,
            tls = true, // trojan/vless/hy2/tuic default to tls; transforms override
            subscriptionId = subId,
        )
        return template.transform(userinfo, query)
    }

    fun parseQuery(query: String): Map<String, List<String>> {
        if (query.isBlank()) return emptyMap()
        return query.split('&')
            .filter { it.contains('=') }
            .groupBy(
                keySelector = { URLDecoder.decode(it.substringBefore('='), "UTF-8") },
            ) {
                URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
    }
}
