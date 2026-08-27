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
    @Serializable
    data class RealityParams(
        val publicKey: String,
        val shortId: String? = null,
    )

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
