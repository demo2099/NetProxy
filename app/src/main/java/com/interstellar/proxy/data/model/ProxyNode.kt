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
    val hy2ObfsPassword: String? = null,
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
        if (type == NodeType.HYSTERIA2 && !hy2ObfsPassword.isNullOrBlank()) parts += "obfs"
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
    }
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
