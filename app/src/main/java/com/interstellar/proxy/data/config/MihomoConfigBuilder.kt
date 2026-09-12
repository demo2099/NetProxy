package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.NodeMatcher
import com.interstellar.proxy.data.config.ConfigBuilder.DerivedGroup
import com.interstellar.proxy.data.model.DomainMatchType
import com.interstellar.proxy.data.model.NodeFilterMode
import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode

/**
 * Builds the mihomo (Clash Meta) YAML config from the unified node model.
 * Kotlin counterpart of satelite-proxy's config/mihomo.rs, aligned with
 * [ConfigBuilder]'s group/rule/DNS semantics so switching cores keeps
 * behavior identical.
 *
 * Tun runs on a VpnService-provided fd (tun.file-descriptor, auto-route
 * false — the app manages routes and excludes node server IPs to prevent
 * loops). Node selection is applied post-start via the Clash API
 * (select groups have no config-level default).
 */
object MihomoConfigBuilder {

    const val GROUP_TAG = ConfigBuilder.GROUP_TAG
    const val AUTO_TAG = ConfigBuilder.AUTO_TAG
    const val DIRECT = "DIRECT"
    const val REJECT = "REJECT"
    private const val TEST_URL = "https://www.gstatic.com/generate_204"

    fun build(nodes: List<ProxyNode>, options: ConfigBuilder.BuildOptions, tunFd: Int? = null): String {
        val usable = nodes.filter { it.type != NodeType.UNKNOWN }
        val tags = ConfigBuilder.tagsFor(usable)
        val used = tags.toMutableSet()
        val regionGroups = if (options.regionGroupsEnabled) deriveRegionGroups(tags, used) else emptyList()
        val customGroups = if (options.mode == ConfigBuilder.OutboundMode.RULE && options.applyNodeFilterRules) {
            deriveCustomGroups(tags, options.customRules, used)
        } else {
            emptyList()
        }

        val root = Yaml.map {
            put("mode", modeOf(options.mode))
            put("log-level", "info")
            put("ipv6", true)
            put("allow-lan", false)
            if (options.mixedPortEnabled) {
                put("mixed-port", options.mixedPort)
            }
            put("external-controller", "127.0.0.1:${options.apiPort}")
            if (options.apiSecret.isNotBlank()) put("secret", options.apiSecret)
            // keep manual node selection across process restarts
            put("profile", Yaml.map { put("store-selected", true) })
            if (options.includeTun) {
                // fd 0 is a placeholder — MihomoCore injects the VpnService fd
                // before spawning (or strips the block for proxy-only mode)
                put("tun", Yaml.map {
                    put("enable", true)
                    put("stack", "mixed")
                    put("device", "interstellar")
                    put("file-descriptor", tunFd ?: 0)
                    // NOTE: mihomo's parseTun derives the interface address from
                    // fake-ip-range (base/30) and IGNORES inet4-address — the
                    // VPN builder must use 198.18.0.1/30 + DNS 198.18.0.2
                    put("mtu", 9000)
                    put("auto-route", false)
                    put("auto-redirect", false)
                    put("auto-detect-interface", false)
                    put("dns-hijack", listOf("any:53"))
                })
            }
            put("dns", buildDns(options))
            put("proxies", buildProxies(usable, tags))
            put("proxy-groups", buildGroups(tags, regionGroups, customGroups))
            put("rules", buildRules(options, customGroups))
        }
        return Yaml.write(root)
    }

    private fun modeOf(mode: ConfigBuilder.OutboundMode) = when (mode) {
        ConfigBuilder.OutboundMode.RULE -> "rule"
        ConfigBuilder.OutboundMode.GLOBAL -> "global"
        ConfigBuilder.OutboundMode.DIRECT -> "direct"
    }

    // ---- dns ----

    private fun buildDns(options: ConfigBuilder.BuildOptions) = Yaml.map {
        put("enable", true)
        put("ipv6", false)
        put("enhanced-mode", "fake-ip")
        put("fake-ip-range", "198.18.0.1/16")
        put("fake-ip-filter", listOf("+.lan", "+.local", "dns.msftncsi.com", "www.msftncsi.com"))
        put("default-nameserver", listOf("223.5.5.5"))
        // node server domains MUST resolve directly (mihomo's escape hatch for
        // the "resolve-via-proxy to reach the proxy" loop)
        put("proxy-server-nameserver", listOf("223.5.5.5", "119.29.29.29"))
        // remote resolution rides the proxy (mirror of sing-box dns-remote
        // detouring the proxy group); DIRECT mode resolves everything local
        put(
            "nameserver",
            if (options.mode == ConfigBuilder.OutboundMode.DIRECT) {
                listOf("223.5.5.5")
            } else {
                listOf("https://1.1.1.1/dns-query#$GROUP_TAG")
            },
        )
        if (options.mode == ConfigBuilder.OutboundMode.RULE && options.bypassCn) {
            put("nameserver-policy", Yaml.map { put("geosite:cn", listOf("223.5.5.5", "119.29.29.29")) })
        }
        if (options.dnsOverrides.isNotEmpty()) {
            put(
                "hosts",
                Yaml.map {
                    for (entry in options.dnsOverrides) {
                        for (domain in entry.parsedDomains()) put(domain, entry.ip)
                    }
                },
            )
        }
    }

    // ---- proxies ----

    private fun buildProxies(nodes: List<ProxyNode>, tags: List<String>): List<Any> {
        val out = mutableListOf<Any>()
        val used = mutableSetOf<String>()
        nodes.zip(tags).forEach { (node, tag) ->
            // shadow-tls wrapper: separate proxy + dialer-proxy on the main node
            var dialerProxy: String? = null
            if (node.shadowTls != null && node.type == NodeType.VMESS) {
                var stTag = "$tag-shadowtls"
                var i = 2
                while (!used.add(stTag)) stTag = "$tag-shadowtls-${i++}"
                dialerProxy = stTag
                out.add(
                    Yaml.map {
                        put("name", stTag)
                        put("type", "shadow-tls")
                        put("server", node.server)
                        put("port", node.port)
                        put("version", node.shadowTls?.version ?: 3)
                        node.shadowTls?.password?.let { put("password", it) }
                        put("sni", node.shadowTls?.sni ?: node.sni ?: node.server)
                    },
                )
            }
            nodeToProxy(node, tag, dialerProxy)?.let { out.add(it) }
        }
        return out
    }

    private fun nodeToProxy(node: ProxyNode, tag: String, dialerProxy: String?): Any? = Yaml.map {
        put("name", tag)
        put("server", node.server)
        put("port", node.port)
        node.udp?.let { put("udp", it) }
        dialerProxy?.let { put("dialer-proxy", it) }

        when (node.type) {
            NodeType.SHADOWSOCKS -> {
                put("type", "ss")
                put("cipher", node.method ?: "aes-256-gcm")
                node.password?.let { put("password", it) }
                val plugin = node.plugin ?: return@map
                // sing-box plugin names → mihomo plugin names
                when {
                    plugin.contains("obfs") -> {
                        put("plugin", "obfs")
                        put("plugin-opts", optsMap(node.pluginOpts))
                    }

                    plugin.contains("v2ray-plugin") -> {
                        put("plugin", "v2ray-plugin")
                        put("plugin-opts", optsMap(node.pluginOpts))
                    }
                }
            }

            NodeType.VMESS -> {
                put("type", "vmess")
                put("uuid", node.uuid ?: "")
                put("alterId", node.alterId ?: 0)
                put("cipher", node.security ?: "auto")
                applyCommon(node)
                applyTransport(node)
            }

            NodeType.VLESS -> {
                put("type", "vless")
                put("uuid", node.uuid ?: "")
                node.flow?.let { put("flow", it) }
                applyCommon(node)
                applyTransport(node)
                node.reality?.let { reality ->
                    put(
                        "reality-opts",
                        Yaml.map {
                            put("public-key", reality.publicKey)
                            reality.shortId?.let { put("short-id", it) }
                        },
                    )
                }
            }

            NodeType.TROJAN -> {
                put("type", "trojan")
                put("password", node.password ?: "")
                applyCommon(node, tlsAlways = true)
                applyTransport(node)
            }

            NodeType.HYSTERIA2 -> {
                put("type", "hysteria2")
                node.password?.let { put("password", it) }
                node.hy2ObfsPassword?.let {
                    put("obfs", "salamander")
                    put("obfs-password", it)
                }
                node.upMbps?.let { put("up", "$it Mbps") }
                node.downMbps?.let { put("down", "$it Mbps") }
                node.sni?.let { put("sni", it) }
                node.insecure?.let { put("skip-cert-verify", it) }
                node.alpn?.let { put("alpn", it) }
            }

            NodeType.TUIC -> {
                put("type", "tuic")
                node.uuid?.let { put("uuid", it) }
                node.password?.let { put("password", it) }
                node.congestionControl?.let { put("congestion-controller", it) }
                node.udpRelayMode?.let { put("udp-relay-mode", it) }
                node.reduceRtt?.let { put("reduce-rtt", it) }
                node.sni?.let { put("sni", it) }
                node.insecure?.let { put("skip-cert-verify", it) }
                node.alpn?.let { put("alpn", it) }
            }

            NodeType.SOCKS -> {
                put("type", "socks5")
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                if (node.tls) {
                    put("tls", true)
                    node.sni?.let { put("servername", it) }
                }
            }

            NodeType.HTTP -> {
                put("type", "http")
                node.username?.let { put("username", it) }
                node.password?.let { put("password", it) }
                if (node.tls) put("tls", true)
            }

            NodeType.WIREGUARD -> {
                put("type", "wireguard")
                val wg = node.wireguard
                val v4 = wg?.localAddress?.firstOrNull { !it.contains(":") }
                val v6 = wg?.localAddress?.firstOrNull { it.contains(":") }
                put("ip", v4?.substringBefore("/") ?: "172.16.0.2")
                v6?.let { put("ipv6", it.substringBefore("/")) }
                put("private-key", wg?.privateKey ?: "")
                put(
                    "peers",
                    listOf(
                        Yaml.map {
                            put("server", node.server)
                            put("port", node.port)
                            wg?.peerPublicKey?.let { put("public-key", it) }
                            wg?.preSharedKey?.let { put("pre-shared-key", it) }
                            wg?.reserved?.let { put("reserved", it) }
                            put("allowed-ips", listOf("0.0.0.0/0", "::/0"))
                        },
                    ),
                )
                wg?.mtu?.let { put("mtu", it) }
                put("udp", true)
            }

            NodeType.ANYTLS -> {
                put("type", "anytls")
                node.password?.let { put("password", it) }
                node.sni?.let { put("sni", it) }
                node.insecure?.let { put("skip-cert-verify", it) }
            }

            NodeType.SSH -> {
                put("type", "ssh")
                node.sshUser?.let { put("username", it) }
                node.sshKey?.let { put("private-key", it) }
            }

            NodeType.UNKNOWN -> put("type", "direct")
        }
    }

    private fun optsMap(opts: Map<String, String>?): Any =
        Yaml.map { opts?.forEach { (k, v) -> put(k, parseOptVal(v)) } }

    private fun parseOptVal(v: String): Any = when (v.lowercase()) {
        "true" -> true
        "false" -> false
        else -> v.toIntOrNull() ?: v
    }

    /** TLS + fingerprint block shared by vmess/vless/trojan. */
    private fun Yaml.Node.applyCommon(node: ProxyNode, tlsAlways: Boolean = false) {
        val hasTls = node.tls || node.reality != null || tlsAlways
        if (hasTls) put("tls", true)
        val sni = node.sni ?: node.server
        if (hasTls) {
            put("servername", sni)
            put("skip-cert-verify", node.insecure ?: false)
            node.alpn?.let { put("alpn", it) }
            node.fingerprint?.let { put("client-fingerprint", it) }
        }
    }

    private fun Yaml.Node.applyTransport(node: ProxyNode) {
        when (node.network) {
            "ws" -> {
                put("network", "ws")
                put(
                    "ws-opts",
                    Yaml.map {
                        put("path", node.wsPath ?: "/")
                        node.headers?.let { headers ->
                            put("headers", Yaml.map { headers.forEach { (k, v) -> put(k, v) } })
                        }
                    },
                )
            }

            "grpc" -> {
                put("network", "grpc")
                put(
                    "grpc-opts",
                    Yaml.map { node.grpcServiceName?.let { put("grpc-service-name", it) } },
                )
            }

            "h2", "http" -> {
                put("network", "h2")
                put(
                    "h2-opts",
                    Yaml.map {
                        node.httpPath?.let { put("path", it) }
                        node.httpHost?.let { put("host", it) }
                    },
                )
            }
        }
    }

    // ---- groups ----

    private fun buildGroups(
        tags: List<String>,
        regionGroups: List<DerivedGroup>,
        customGroups: List<DerivedGroup>,
    ): List<Any> = buildList {
        if (tags.isEmpty()) return emptyList()
        add(
            Yaml.map {
                put("name", GROUP_TAG)
                put("type", "select")
                put(
                    "proxies",
                    buildList {
                        add(AUTO_TAG)
                        addAll(regionGroups.map { it.tag })
                        addAll(tags)
                    },
                )
            },
        )
        add(urlTestGroup(AUTO_TAG, tags))
        regionGroups.forEach { add(urlTestGroup(it.tag, it.members)) }
        customGroups.forEach { add(urlTestGroup(it.tag, it.members)) }
    }

    private fun urlTestGroup(tag: String, members: List<String>) = Yaml.map {
        put("name", tag)
        put("type", "url-test")
        put("proxies", members)
        put("url", TEST_URL)
        put("interval", 300)
        put("tolerance", 50)
        put("lazy", true)
    }

    private fun deriveRegionGroups(tags: List<String>, used: MutableSet<String>): List<DerivedGroup> {
        val buckets = linkedMapOf<NodeMatcher.Region, MutableList<String>>()
        for (tag in tags) {
            val region = NodeMatcher.regionOf(tag) ?: continue
            buckets.getOrPut(region) { mutableListOf() }.add(tag)
        }
        return buckets.map { (region, members) ->
            val tag = uniqueTag("${region.flag} ${region.name}", used)
            DerivedGroup(tag, members)
        }
    }

    private fun deriveCustomGroups(
        tags: List<String>,
        rules: List<com.interstellar.proxy.data.model.CustomRouteRule>,
        used: MutableSet<String>,
    ): List<DerivedGroup> =
        rules.mapNotNull { rule ->
            if (!rule.enabled) return@mapNotNull null
            if (rule.filterMode == NodeFilterMode.DIRECT) return@mapNotNull null
            if (rule.parsedMatchValues().isEmpty()) return@mapNotNull null
            val keywords = rule.nodeKeywords.map { it.trim() }.filter { it.isNotEmpty() }
            if (keywords.isEmpty()) return@mapNotNull null
            val members = NodeMatcher.filterTags(
                tags,
                keywords,
                include = rule.filterMode == NodeFilterMode.INCLUDE,
            )
            if (members.isEmpty()) return@mapNotNull null
            DerivedGroup(uniqueTag(ConfigBuilder.customRuleTag(rule.id), used), members, ruleId = rule.id)
        }

    private fun uniqueTag(preferred: String, used: MutableSet<String>): String {
        if (used.add(preferred)) return preferred
        var i = 2
        var candidate = "$preferred · 自动"
        if (used.add(candidate)) return candidate
        while (!used.add(candidate)) {
            candidate = "$preferred · 自动 ($i)"
            i++
        }
        return candidate
    }

    // ---- rules ----

    private fun buildRules(options: ConfigBuilder.BuildOptions, customGroups: List<DerivedGroup>) = buildList {
        if (options.bypassLan) {
            for (cidr in listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16")) add("IP-CIDR,$cidr,$DIRECT,no-resolve")
            add("IP-CIDR6,fc00::/7,$DIRECT,no-resolve")
        }
        if (options.adBlock) add("GEOSITE,category-ads-all,$REJECT")
        if (options.mode == ConfigBuilder.OutboundMode.RULE && options.applyNodeFilterRules) {
            val byId = customGroups.associateBy { it.ruleId }
            for (rule in options.customRules) {
                if (!rule.enabled) continue
                val values = rule.parsedMatchValues()
                if (values.isEmpty()) continue
                val outbound = when (rule.filterMode) {
                    NodeFilterMode.DIRECT -> DIRECT
                    else -> byId[rule.id]?.tag ?: continue
                }
                val prefix = when (rule.matchType) {
                    DomainMatchType.DOMAIN -> "DOMAIN,"
                    DomainMatchType.DOMAIN_SUFFIX -> "DOMAIN-SUFFIX,"
                    DomainMatchType.DOMAIN_KEYWORD -> "DOMAIN-KEYWORD,"
                }
                values.forEach { value -> add("$prefix$value,$outbound") }
            }
        }
        if (options.mode == ConfigBuilder.OutboundMode.RULE && options.bypassCn) {
            add("GEOSITE,cn,$DIRECT")
            add("GEOIP,cn,$DIRECT,no-resolve")
        }
        add("MATCH,$GROUP_TAG")
    }

    /** Route exclusions for the sidecar VPN (node servers + DNS upstreams). */
    fun routeExclusions(nodes: List<ProxyNode>): List<String> = buildList {
        for (node in nodes) {
            val host = node.server.trim()
            if (host.isNotEmpty()) add(host)
        }
        addAll(listOf("223.5.5.5", "119.29.29.29", "1.1.1.1"))
    }
}
