package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ObfsType
import com.interstellar.proxy.data.model.ProxyNode
import com.interstellar.proxy.data.subscription.YamlToJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/**
 * hysteria2 obfs/port-hopping emission.
 *
 * These two fields are the ones a wrong guess silently breaks: an obfs kind
 * that disagrees with the server produces a config that loads and then never
 * handshakes, and a malformed port range makes the whole config invalid. Both
 * are emitted differently per kernel, so each builder is pinned separately.
 */
class Hy2ObfsConfigTest {

    private fun geckoNode(ports: List<String>? = listOf("20000:30000")) = ProxyNode(
        id = "g", name = "SG-gecko", type = NodeType.HYSTERIA2,
        server = "sg.example.net", port = 8443, password = "pw",
        sni = "sg.example.net", insecure = true,
        hy2ObfsType = ObfsType.GECKO,
        hy2ObfsPassword = "geckopw",
        hy2GeckoMinPacketSize = 512,
        hy2GeckoMaxPacketSize = 1200,
        hy2ServerPorts = ports,
        hy2HopInterval = "30s",
    )

    /** Finds the hy2 outbound, failing with the actual type list when absent. */
    private fun hy2Outbound(json: String) =
        Json.parseToJsonElement(json).jsonObject["outbounds"]!!.jsonArray
            .map { it.jsonObject }
            .let { all ->
                val types = all.map { it["type"]?.jsonPrimitive?.content }
                all.firstOrNull { it["type"]?.jsonPrimitive?.content == "hysteria2" }
                    ?: error("no hysteria2 outbound emitted; outbound types were $types")
            }

    @Test
    fun `sing-box emits gecko with packet sizing and server_ports`() {
        val json = ConfigBuilder.build(
            listOf(geckoNode()),
            ConfigBuilder.BuildOptions(selectedNodeTag = "SG-gecko"),
        )
        val hy2 = hy2Outbound(json)

        val obfs = hy2["obfs"]?.jsonObject ?: error("hy2 outbound has no obfs: $hy2")
        check(obfs["type"]!!.jsonPrimitive.content == ObfsType.GECKO) { "obfs type must stay gecko" }
        check(obfs["password"]!!.jsonPrimitive.content == "geckopw") { "obfs password" }
        check(obfs["min_packet_size"]!!.jsonPrimitive.content == "512") { "gecko min_packet_size" }
        check(obfs["max_packet_size"]!!.jsonPrimitive.content == "1200") { "gecko max_packet_size" }

        val ports = hy2["server_ports"]!!.jsonArray.map { it.jsonPrimitive.content }
        check(ports == listOf("20000:30000")) { "server_ports must use colon form, got $ports" }
        check(hy2["hop_interval"]!!.jsonPrimitive.content == "30s") { "hop_interval is a duration" }
    }

    @Test
    fun `sing-box falls back to salamander when the kind is unknown`() {
        // Legacy nodes carry a password but no type. Guessing gecko here would
        // break every one of them, so the builder must pick salamander.
        val legacy = geckoNode(ports = null).copy(hy2ObfsType = null)
        val json = ConfigBuilder.build(
            listOf(legacy),
            ConfigBuilder.BuildOptions(selectedNodeTag = "SG-gecko"),
        )
        val hy2 = hy2Outbound(json)

        val obfs = hy2["obfs"]?.jsonObject ?: error("hy2 outbound has no obfs: $hy2")
        check(obfs["type"]!!.jsonPrimitive.content == ObfsType.SALAMANDER) { "must default to salamander" }
        check("min_packet_size" !in obfs) { "packet sizing is gecko-only" }
        check("server_ports" !in hy2) { "no ports requested, none emitted" }
    }

    @Test
    fun `mihomo emits gecko with hyphenated ports and integer seconds`() {
        val yaml = MihomoConfigBuilder.build(
            listOf(geckoNode()),
            ConfigBuilder.BuildOptions(selectedNodeTag = "SG-gecko"),
        )
        val doc = YamlToJson.convert(yaml)?.jsonObject ?: error("generated yaml failed to parse")
        val hy2 = doc["proxies"]!!.jsonArray.first().jsonObject

        check(hy2["obfs"]!!.jsonPrimitive.content == ObfsType.GECKO) { "mihomo obfs kind" }
        check(hy2["obfs-password"]!!.jsonPrimitive.content == "geckopw") { "mihomo obfs password" }
        check(hy2["ports"]!!.jsonPrimitive.content == "20000-30000") {
            "mihomo wants hyphen ranges, got ${hy2["ports"]}"
        }
        check(hy2["hop-interval"]!!.jsonPrimitive.content == "30") { "mihomo hop-interval is seconds" }
    }

    @Test
    fun `port range normaliser accepts both spellings and drops junk`() {
        check(ProxyNode.normalizeServerPorts("20000-30000") == listOf("20000:30000")) { "hyphen form" }
        check(ProxyNode.normalizeServerPorts("20000:30000") == listOf("20000:30000")) { "colon form" }
        check(
            ProxyNode.normalizeServerPorts("20000-30000,40000-50000") ==
                listOf("20000:30000", "40000:50000"),
        ) { "comma separated" }
        check(ProxyNode.normalizeServerPorts("20000") == listOf("20000")) { "bare port" }
        // A broken segment must not poison the whole list: sing-box rejects the
        // entire config over one bad range.
        check(ProxyNode.normalizeServerPorts("abc-30000,20000-30000") == listOf("20000:30000")) { "junk dropped" }
        check(ProxyNode.normalizeServerPorts("") == null) { "empty" }
        check(ProxyNode.normalizeServerPorts(null) == null) { "null" }

        check(ProxyNode.normalizeHopInterval("30") == "30s") { "seconds get a unit" }
        check(ProxyNode.normalizeHopInterval("30s") == "30s") { "duration passes through" }
        check(ProxyNode.normalizeHopInterval(null) == null) { "null" }
    }
}
