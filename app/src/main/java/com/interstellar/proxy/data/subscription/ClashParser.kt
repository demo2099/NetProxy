package com.interstellar.proxy.data.subscription

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Parses Clash / Clash.Meta YAML subscriptions into the unified node model.
 * Ported from interstellar-proxy's subscription/clash.rs.
 */
object ClashParser {

    fun isClashConfig(content: String): Boolean {
        val json = YamlToJson.convert(content) ?: return false
        val proxies = json.arr("proxies") ?: return false
        return proxies.isNotEmpty()
    }

    fun parseClashYaml(content: String, subscriptionId: String? = null): List<ProxyNode> {
        val json = YamlToJson.convert(content) ?: return emptyList()
        val proxies = json.arr("proxies") ?: return emptyList()
        return proxies.mapNotNull { proxyEl ->
            val proxy = proxyEl as? JsonObject ?: return@mapNotNull null
            runCatching { parseProxy(proxy, subscriptionId) }.getOrNull()
        }
    }

    private fun newId() = UUID.randomUUID().toString()

    private fun parseProxy(proxy: JsonObject, subId: String?): ProxyNode? {
        val name = proxy.str("name") ?: return null
        val server = proxy.str("server") ?: return null
        val port = proxy.num("port") ?: return null
        val type = proxy.str("type")?.lowercase() ?: return null

        val node = ProxyNode(
            id = newId(),
            name = name,
            type = NodeType.from(type),
            server = server,
            port = port,
            udp = proxy.bool("udp"),
            subscriptionId = subId,
        )
        if (node.type == NodeType.UNKNOWN) return null

        return when (node.type) {
            NodeType.SHADOWSOCKS -> parseShadowsocks(proxy, node)
            NodeType.VMESS -> parseVmess(proxy, node)
            NodeType.VLESS -> parseVless(proxy, node)
            NodeType.TROJAN -> parseTrojan(proxy, node)
            NodeType.HYSTERIA2 -> parseHysteria2(proxy, node)
            NodeType.TUIC -> parseTuic(proxy, node)
            NodeType.SOCKS -> node.copy(
                username = proxy.str("username"),
                password = proxy.str("password"),
                tls = proxy.bool("tls") ?: false,
            )

            NodeType.HTTP -> node.copy(
                username = proxy.str("username"),
                password = proxy.str("password"),
                tls = proxy.bool("tls") ?: false,
            )

            NodeType.WIREGUARD -> parseWireguard(proxy, node)
            NodeType.ANYTLS -> node.copy(
                password = proxy.str("password"),
                sni = proxy.str("sni"),
                insecure = proxy.bool("skip-cert-verify"),
            )

            NodeType.SSH -> node.copy(
                sshUser = proxy.str("username"),
                sshKey = proxy.str("private-key") ?: proxy.str("client-key"),
            )

            else -> null
        }
    }

    private fun commonTls(proxy: JsonObject, node: ProxyNode): ProxyNode {
        return node.copy(
            tls = proxy.bool("tls") ?: (node.type == NodeType.TROJAN),
            sni = proxy.str("servername") ?: proxy.str("sni"),
            alpn = proxy.strList("alpn"),
            insecure = proxy.bool("skip-cert-verify"),
            fingerprint = proxy.str("client-fingerprint"),
        )
    }

    private fun applyTransport(proxy: JsonObject, node: ProxyNode): ProxyNode {
        val network = proxy.str("network")?.lowercase() ?: "tcp"
        var updated = node.copy(network = network)
        when (network) {
            "ws" -> {
                val opts = proxy.obj("ws-opts")
                val headers = opts?.get("headers")?.jsonObjectOrNull()?.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { k to it }
                }?.toMap()
                updated = updated.copy(
                    wsPath = opts?.str("path") ?: "/",
                    headers = headers,
                )
            }

            "grpc" -> {
                val opts = proxy.obj("grpc-opts")
                updated = updated.copy(grpcServiceName = opts?.str("grpc-service-name"))
            }

            "h2", "http" -> {
                val opts = proxy.obj("h2-opts") ?: proxy.obj("http-opts")
                updated = updated.copy(
                    httpPath = opts?.str("path"),
                    httpHost = opts?.strList("host"),
                )
            }
        }
        return updated
    }

    private fun parseShadowsocks(proxy: JsonObject, base: ProxyNode): ProxyNode {
        var node = base.copy(
            method = proxy.str("cipher") ?: proxy.str("method") ?: "aes-256-gcm",
            password = proxy.str("password") ?: "",
        )
        val plugin = proxy.str("plugin")
        if (plugin != null) {
            val opts = proxy.obj("plugin-opts")
            val optMap = mapOf(
                "mode" to opts?.str("mode"),
                "host" to opts?.str("host"),
                "path" to opts?.str("path"),
                "tls" to opts?.bool("tls")?.toString(),
            ).filterValues { it != null }.mapValues { it.value!! }
            // clash "obfs"/"simple-obfs" → sing-box "obfs-local"
            val mapped = when (plugin) {
                "obfs", "simple-obfs" -> "obfs-local"
                else -> plugin
            }
            node = node.copy(plugin = mapped, pluginOpts = optMap.ifEmpty { null })
        }
        return node
    }

    private fun parseVmess(proxy: JsonObject, base: ProxyNode): ProxyNode {
        var node = base.copy(
            uuid = proxy.str("uuid") ?: proxy.str("username"),
            alterId = proxy.num("alterId") ?: proxy.num("alter-id") ?: 0,
            security = proxy.str("cipher") ?: proxy.str("security") ?: "auto",
        )
        node = commonTls(proxy, node)
        node = applyTransport(proxy, node)
        return node
    }

    private fun parseVless(proxy: JsonObject, base: ProxyNode): ProxyNode {
        var node = base.copy(
            uuid = proxy.str("uuid"),
            flow = proxy.str("flow"),
        )
        val realityOpts = proxy.obj("reality-opts")
        if (realityOpts != null) {
            node = node.copy(
                tls = true,
                reality = ProxyNode.RealityParams(
                    publicKey = realityOpts.str("public-key") ?: realityOpts.str("pbk") ?: "",
                    shortId = realityOpts.str("short-id") ?: realityOpts.str("sid"),
                ),
            )
        }
        node = commonTls(proxy, node)
        node = applyTransport(proxy, node)
        return node
    }

    private fun parseTrojan(proxy: JsonObject, base: ProxyNode): ProxyNode {
        var node = base.copy(password = proxy.str("password"))
        node = commonTls(proxy, node)
        node = applyTransport(proxy, node)
        return node
    }

    private fun parseHysteria2(proxy: JsonObject, base: ProxyNode): ProxyNode {
        return base.copy(
            password = proxy.str("password") ?: proxy.str("auth"),
            sni = proxy.str("sni"),
            alpn = proxy.strList("alpn"),
            insecure = proxy.bool("skip-cert-verify"),
            hy2ObfsPassword = proxy.str("obfs-password"),
            upMbps = proxy.num("up")?.let { cleanSpeed(it) },
            downMbps = proxy.num("down")?.let { cleanSpeed(it) },
            tls = true,
        )
    }

    // clash expresses up/down like "30 Mbps" or plain numbers
    private fun cleanSpeed(value: Int): Int = value

    private fun parseTuic(proxy: JsonObject, base: ProxyNode): ProxyNode {
        return base.copy(
            uuid = proxy.str("uuid") ?: proxy.str("token")?.takeIf { !it.contains(':') },
            password = proxy.str("password"),
            sni = proxy.str("sni"),
            alpn = proxy.strList("alpn"),
            congestionControl = proxy.str("congestion-controller") ?: proxy.str("congestion-control"),
            udpRelayMode = proxy.str("udp-relay-mode"),
            reduceRtt = proxy.bool("reduce-rtt"),
            insecure = proxy.bool("skip-cert-verify"),
            tls = true,
        )
    }

    private fun parseWireguard(proxy: JsonObject, base: ProxyNode): ProxyNode? {
        val privateKey = proxy.str("private-key") ?: return null
        val localAddress = buildList {
            proxy.str("ip")?.let { addAll(it.split(',').map(String::trim)) }
            proxy.str("ipv6")?.let { addAll(it.split(',').map(String::trim)) }
            proxy.strList("local-address")?.let { addAll(it) }
            proxy.strList("allowed-ips")?.let { addAll(it) }
        }.filter { it.isNotBlank() }.ifEmpty { listOf("172.16.0.2/32") }
        val reserved = proxy.arr("reserved")?.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return base.copy(
            wireguard = ProxyNode.WireGuardParams(
                localAddress = localAddress,
                privateKey = privateKey,
                peerPublicKey = proxy.str("public-key") ?: proxy.str("peer"),
                preSharedKey = proxy.str("pre-shared-key") ?: proxy.str("preshared-key"),
                reserved = reserved,
                mtu = proxy.num("mtu"),
            ),
        )
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()
}
