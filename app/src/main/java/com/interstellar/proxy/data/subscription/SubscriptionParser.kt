package com.interstellar.proxy.data.subscription

import android.util.Base64
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Subscription sniffer: node lists only (Clash YAML, share URIs, Base64).
 * JSON with outbounds is extracted as nodes, never run as a raw config.
 */
object SubscriptionParser {
    private const val MAX_NODES = 10000

    sealed class Result {
        data class Nodes(val nodes: List<ProxyNode>) : Result()

        data object Empty : Result()
    }

    fun parse(content: String, subscriptionId: String? = null): Result {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) return Result.Empty

        if (trimmed.startsWith("{")) {
            extractNodesFromSingbox(trimmed, subscriptionId)?.let {
                if (it.isNotEmpty()) return Result.Nodes(it.filterNot(::isInfoNode))
            }
        }

        if (isClashLike(trimmed)) {
            val nodes = ClashParser.parseClashYaml(trimmed, subscriptionId)
            if (nodes.isNotEmpty()) return Result.Nodes(nodes.filterNot(::isInfoNode))
        }

        val direct = UriParser.parseUriList(trimmed, subscriptionId)
        if (direct.isNotEmpty()) return Result.Nodes(direct.filterNot(::isInfoNode))

        val decoded = decodeBase64(trimmed)
        if (decoded != null) {
            if (decoded.trimStart().startsWith("{")) {
                extractNodesFromSingbox(decoded, subscriptionId)?.let {
                    if (it.isNotEmpty()) return Result.Nodes(it.filterNot(::isInfoNode))
                }
            }
            val nodes = UriParser.parseUriList(decoded, subscriptionId)
            if (nodes.isNotEmpty()) return Result.Nodes(nodes.filterNot(::isInfoNode))
            if (isClashLike(decoded)) {
                val clashNodes = ClashParser.parseClashYaml(decoded, subscriptionId)
                if (clashNodes.isNotEmpty()) return Result.Nodes(clashNodes.filterNot(::isInfoNode))
            }
        }

        return Result.Empty
    }

    /** Airports smuggle quota/expiry pseudo-nodes into the list — drop them. */
    private fun isInfoNode(node: com.interstellar.proxy.data.model.ProxyNode): Boolean {
        val n = node.name
        return n.contains("剩余") || n.contains("到期") || n.contains("过期") ||
            n.contains("官网") || n.contains("重置") || n.contains("公告") ||
            n.contains("套餐") || n.contains("订阅") && n.length < 16
    }

    /** Pull proxy outbounds out of a JSON object; inbounds/route are ignored. */
    private fun extractNodesFromSingbox(text: String, subId: String?): List<ProxyNode>? = runCatching {
        val json = Json.parseToJsonElement(text).jsonObject
        val outbounds = json["outbounds"]?.jsonArray ?: return@runCatching null
        outbounds.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val type = obj["type"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (type in PROXY_OUTBOUND_TYPES) {
                SingboxOutboundConverter.toNode(obj, subId)
            } else {
                null
            }
        }
    }.getOrNull()

    private fun isClashLike(text: String): Boolean = runCatching {
        ClashParser.isClashConfig(text) || text.contains("proxies:")
    }.getOrDefault(false)

    private fun decodeBase64(text: String): String? {
        val cleaned = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("")
        if (cleaned.isEmpty()) return null
        val normalized = cleaned.replace("-", "+").replace("_", "/")
        val padded = when (normalized.length % 4) {
            2 -> "$normalized=="
            3 -> "$normalized="
            else -> normalized
        }
        return runCatching {
            val bytes = Base64.decode(padded, Base64.NO_WRAP)
            val decoded = String(bytes, Charsets.UTF_8)
            // sanity: decoded content should be printable
            if (decoded.count { it.code in 32..126 || it == '\n' || it == '\r' } < decoded.length * 9 / 10) {
                null
            } else {
                decoded
            }
        }.getOrNull()
    }

    private val PROXY_OUTBOUND_TYPES = setOf(
        "shadowsocks", "vmess", "vless", "trojan", "hysteria2", "hysteria",
        "tuic", "socks", "http", "wireguard", "anytls", "ssh", "shadowtls",
    )
}
