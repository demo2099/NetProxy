package com.interstellar.proxy.data.model

import kotlinx.serialization.Serializable

/**
 * Unified proxy node model — the Kotlin counterpart of interstellar-proxy's domain/node.rs.
 * One flat structure covers all supported protocols; builders map it onto
 * sing-box outbounds.
 */
@Serializable
data class ProxyNode(
    val id: String,
    val name: String,
    val type: NodeType,
    val server: String,
    val port: Int,
    val udp: Boolean? = null,

    // credentials
    val uuid: String? = null,          // vmess / vless
    val password: String? = null,      // trojan / hy2 / ss-2022 / anytls
    val method: String? = null,        // ss cipher
    val alterId: Int? = null,          // vmess
    val security: String? = null,      // vmess encryption, default auto
    val flow: String? = null,          // vless flow

    // tls
    val tls: Boolean = false,
    val sni: String? = null,
    val alpn: List<String>? = null,
    val insecure: Boolean? = null,
    val fingerprint: String? = null,   // uTLS fingerprint
    val reality: RealityParams? = null,
    val ech: EchParams? = null,        // see EchParams — parsed, not yet emitted

    // transport
    val network: String = "tcp",       // tcp | ws | http | grpc | h2 | quic
    val wsPath: String? = null,
    val grpcServiceName: String? = null,
    val httpPath: String? = null,
    val httpHost: List<String>? = null,
    val headers: Map<String, String>? = null,

    // ss plugin (obfs / v2ray-plugin)
    val plugin: String? = null,
    val pluginOpts: Map<String, String>? = null,

    // shadow-tls wrapper (becomes a separate detour outbound)
    val shadowTls: ShadowTlsParams? = null,

    // hysteria2
    /**
     * Obfs password. Non-null means the node has obfs enabled; the *kind* of
     * obfs lives in [hy2ObfsType] — they are two fields because the password
     * alone cannot tell salamander from gecko, and emitting the wrong type
     * makes the server silently drop the handshake.
     */
    val hy2ObfsPassword: String? = null,
    /**
     * `salamander` (the original, all hysteria2 builds) or `gecko`
     * (hysteria ≥2.9.2 / sing-box ≥1.14 / mihomo ≥1.19.26).
     *
     * null means "legacy node, obfs type was never recorded" — the builders
     * treat it as salamander, which is what every pre-gecko client emitted.
     */
    val hy2ObfsType: String? = null,
    /** gecko-only: split the QUIC handshake into datagrams of this size range. */
    val hy2GeckoMinPacketSize: Int? = null,
    val hy2GeckoMaxPacketSize: Int? = null,
    /**
     * Port hopping ranges, normalised to sing-box's `lo:hi` form
     * (`["20000:30000"]`). Clash/hysteria spell these `20000-30000`.
     */
    val hy2ServerPorts: List<String>? = null,
    /** Port-hopping interval as a sing-box duration string, e.g. `"30s"`. */
    val hy2HopInterval: String? = null,
    val upMbps: Int? = null,
    val downMbps: Int? = null,

    // tuic
    val congestionControl: String? = null,
    val udpRelayMode: String? = null,
    val reduceRtt: Boolean? = null,

    // wireguard
    val wireguard: WireGuardParams? = null,

    // ssh
    val sshUser: String? = null,
    val sshKey: String? = null,

    // socks / http
    val username: String? = null,

    // original subscription id this node came from
    val subscriptionId: String? = null,
) {
    /**
     * The ALPN list actually handed to the kernel.
     *
     * AnyTLS is the one protocol where the two subscription formats disagree.
     * The panel's sing-box JSON carries `tls.alpn = ["h3"]` on every AnyTLS node,
     * but the Clash YAML — the format this app consumes, see SubscriptionFetcher
     * — has no `alpn` key for anytls at all (verified: the string "alpn" does not
     * occur even once in the whole document). Clash Meta has no such field for
     * anytls, so the value cannot survive the conversion, no matter how the
     * parser is written.
     *
     * The consequence is that a client reading the sing-box JSON (Hiddify, the
     * official sing-box clients) sends `h3` for these nodes while this app sent
     * nothing at all. AnyTLS is TLS-only and these nodes are all REALITY, where
     * the ClientHello is what the server authenticates and what the fallback
     * path forwards to the cover site; a node whose server only completes the
     * handshake for its advertised ALPN then looks "dead" here while working
     * everywhere else.
     *
     * So: fill in the provider's own value for AnyTLS when the subscription said
     * nothing, and leave every other protocol at `null`. Not defaulting globally
     * matters — for vmess/vless/trojan the correct behaviour is to send no ALPN
     * and let sing-box's uTLS fingerprint supply its own, so a blanket default
     * would break working nodes.
     */
    val effectiveAlpn: List<String>?
        get() = alpn ?: ANYTLS_DEFAULT_ALPN.takeIf { type == NodeType.ANYTLS }

    /**
     * True when this node needs a gecko-capable kernel
     * (sing-box ≥1.14, mihomo ≥1.19.26). Xray cannot express it at all.
     */
    val isGeckoObfs: Boolean
        get() = type == NodeType.HYSTERIA2 && hy2ObfsType == ObfsType.GECKO

    /**
     * `tls.insecure` (sing-box) / `skip-cert-verify` (mihomo) as actually emitted.
     *
     * An explicit value always wins — a panel that means "verify this" keeps
     * verification. Only when the subscription says nothing do we fall back,
     * and the fallback is `true` in exactly one situation: a TLS-only protocol
     * (hysteria2 / tuic) pointed at a **bare IP with no SNI**.
     *
     * Why that case is special: public CAs do not issue certificates for bare
     * addresses (Let's Encrypt's IP certificates are 6-day enterprise-only),
     * so such a server either serves a self-signed certificate or none at all.
     * Verifying there cannot succeed — it only produces
     * `x509: certificate signed by unknown authority` on every connection,
     * which is precisely how these nodes die. Nothing is lost by skipping the
     * check: the peer identity is already pinned by the address we dial, and
     * there is no name to bind the certificate to in the first place.
     *
     * Every other shape (a domain SNI, or vmess/vless/trojan where TLS is
     * opt-in) keeps the historical default of `false`.
     */
    val effectiveInsecure: Boolean
        get() = insecure ?: (
            type in TLS_ONLY_TYPES &&
                sni.isNullOrBlank() &&
                isValidIpLiteral(server)
            )

    /**
     * Repair a node that an older build persisted with the wrong flag.
     *
     * Nodes are cached on disk, so fixing the parser alone is not enough: a
     * link that said `insecure=1` was stored as `insecure: false`, and that
     * stale explicit `false` would keep overriding [effectiveInsecure] forever
     * — the user would have to delete and re-add the subscription to recover.
     *
     * The repair is deliberately narrow: only the exact shape the old parser
     * could produce (TLS-only protocol, bare-IP host, no SNI) and only when the
     * stored value is `false`. Such a node cannot verify its peer under any
     * configuration, so nothing real is being discarded. `null` (never stated)
     * and `true` pass through untouched, and a node that later gains an SNI
     * stops matching, so the repair cannot keep firing.
     */
    fun withRepairedInsecure(): ProxyNode =
        if (insecure == false && type in TLS_ONLY_TYPES && sni.isNullOrBlank() && isValidIpLiteral(server)) {
            copy(insecure = null)
        } else {
            this
        }

    /**
     * Compact protocol line for node cards, e.g. "vless·grpc·tls",
     * "trojan·ws·tls", "hysteria2·obfs".
     */
    fun protocolSummary(): String {
        val parts = mutableListOf(type.wire)
        val quicBased = type == NodeType.HYSTERIA2 || type == NodeType.TUIC
        if (!quicBased && network != "tcp") {
            parts += when (network) {
                "http" -> "h2"
                else -> network
            }
        }
        when {
            reality != null -> parts += "reality"
            tls -> parts += "tls"
        }
        if (shadowTls != null) parts += "stls"
        // Name the obfs kind: "hysteria2·obfs" cannot distinguish a node that
        // works from one that will never handshake, and gecko is the case
        // where that difference is user-visible (older clients reject it).
        if (type == NodeType.HYSTERIA2 && !hy2ObfsPassword.isNullOrBlank()) {
            parts += if (hy2ObfsType == ObfsType.GECKO) ObfsType.GECKO else "obfs"
        }
        if (!hy2ServerPorts.isNullOrEmpty()) parts += "hop"
        if (!plugin.isNullOrBlank()) parts += "plugin"
        return parts.joinToString("·")
    }

    @Serializable
    data class RealityParams(
        val publicKey: String,
        val shortId: String? = null,
    )

    /**
     * Encrypted Client Hello, as advertised by the subscription.
     *
     * Non-null means "the subscription asked for ECH on this node" — a node whose
     * panel explicitly sets `enable: false` / `enabled: false` gets `null`, so the
     * invariant `ech != null` ⟺ intent is preserved. That matters because both
     * consumers (the node sheet, the copy-to-clipboard dump) phrase themselves off
     * it and would otherwise report a disabled feature as requested.
     *
     * It is carried here but deliberately NOT written into any kernel config yet —
     * [com.interstellar.proxy.data.config.ConfigBuilder.EMITS_ECH] is false and the
     * UI reads it, so the "not in effect" wording cannot rot into a lie.
     *
     * The decisive field is [configs]. ECH is only usable with an ECHConfigList,
     * which the subscription either inlines or leaves to a runtime lookup of the
     * **HTTPS RR (type 65) of the SNI domain** — not of the server domain, and a
     * query this app has no path for at all today (it only ever resolves `server`).
     * That lookup is the real work in enabling ECH; the handshake change is easy,
     * and ECH fails *hard* (handshake failure, not a silent downgrade), so turning
     * it on without a working lookup would break nodes that work today.
     */
    @Serializable
    data class EchParams(
        /** Inline ECHConfigList entries, base64. Usually absent — see class docs. */
        val configs: List<String>? = null,
        val pqSignatureSchemesEnabled: Boolean? = null,
        val dynamicRecordSizingDisabled: Boolean? = null,
    ) {
        /** True when the key material came with the subscription instead of a DNS lookup. */
        val hasInlineConfig: Boolean get() = !configs.isNullOrEmpty()
    }

    @Serializable
    data class ShadowTlsParams(
        val version: Int = 3,
        val password: String? = null,
        val sni: String? = null,
    )

    @Serializable
    data class WireGuardParams(
        val localAddress: List<String>,
        val privateKey: String,
        val peerPublicKey: String? = null,
        val preSharedKey: String? = null,
        val reserved: List<Int>? = null,
        val mtu: Int? = null,
    )

    companion object {
        /** See [effectiveAlpn]. Shared so both config builders agree on one value. */
        val ANYTLS_DEFAULT_ALPN: List<String> = listOf("h3")

        /**
         * Protocols that have no non-TLS mode at all: the handshake *is* the
         * connection, so `tls.insecure` is never a "downgrade" choice but the
         * only way to talk to a server whose certificate cannot be validated.
         * See [effectiveInsecure].
         */
        val TLS_ONLY_TYPES: Set<NodeType> = setOf(NodeType.HYSTERIA2, NodeType.TUIC)

        /**
         * Parse a share-link boolean flag.
         *
         * `String.toBoolean()` is **not** enough here and using it was a real
         * bug: hysteria2 / tuic links spell this `insecure=1` far more often
         * than `insecure=true`, and `"1".toBoolean()` is `false`. The node then
         * went out with `"insecure": false`, verification came back on for a
         * self-signed server, and every request failed with
         * `x509: certificate signed by unknown authority` — while the same link
         * worked in every other client.
         *
         * Unrecognised values return `null` (unknown), not `false`, so they can
         * never override [effectiveInsecure]'s fallback with a wrong "verify".
         */
        fun parseBoolFlag(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            null, "" -> null
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off" -> false
            else -> null
        }

        /**
         * Normalise a port-hopping range to sing-box's `server_ports` spelling.
         *
         * Every producer writes `lo-hi` (share links: `mport=20000-30000`;
         * Clash: `ports: 20000-30000`), while sing-box demands `lo:hi`. Bare
         * ports are legal in both and pass through untouched.
         *
         * Unparseable segments are dropped rather than forwarded: one malformed
         * range makes sing-box reject the entire config, which would take down
         * a node that works fine without hopping.
         */
        fun normalizeServerPorts(raw: String?): List<String>? {
            val ports = raw?.split(',')?.mapNotNull { segment ->
                val part = segment.trim()
                if (part.isEmpty()) return@mapNotNull null
                // Already in sing-box form ("20000:30000") — imported from a
                // sing-box subscription. Keep it, but only if both ends parse.
                if (part.contains(':')) {
                    val lo = part.substringBefore(':').trim().toIntOrNull()
                    val hi = part.substringAfter(':').trim().toIntOrNull()
                    return@mapNotNull if (lo != null && hi != null) "$lo:$hi" else null
                }
                if (!part.contains('-')) return@mapNotNull part.takeIf { it.toIntOrNull() != null }
                val lo = part.substringBefore('-').trim()
                val hi = part.substringAfter('-').trim()
                if (lo.toIntOrNull() != null && hi.toIntOrNull() != null) "$lo:$hi" else null
            }.orEmpty()
            return ports.takeIf { it.isNotEmpty() }
        }

        /** hysteria spells the hop interval in seconds; sing-box wants a duration. */
        fun normalizeHopInterval(raw: String?): String? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return value.toIntOrNull()?.let { "${it}s" } ?: value
        }
    }
}

/**
 * hysteria2 obfs kinds.
 *
 * Kept as plain strings (not an enum) because [ProxyNode] is `@Serializable`
 * and subscriptions may name types this build has never heard of — an unknown
 * value should round-trip untouched rather than fail deserialisation.
 */
object ObfsType {
    const val SALAMANDER = "salamander"
    const val GECKO = "gecko"

    /** Normalise whatever the subscription said; unknown ⇒ null (caller falls back). */
    fun parse(raw: String?): String? = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
}

enum class NodeType(val wire: String) {
    SHADOWSOCKS("shadowsocks"),
    VMESS("vmess"),
    VLESS("vless"),
    TROJAN("trojan"),
    HYSTERIA2("hysteria2"),
    TUIC("tuic"),
    SOCKS("socks"),
    HTTP("http"),
    WIREGUARD("wireguard"),
    ANYTLS("anytls"),
    SSH("ssh"),
    UNKNOWN("unknown");

    companion object {
        fun from(value: String): NodeType = entries.find { it.wire == value || it.name.equals(value, true) }
            ?: UNKNOWN
    }
}
