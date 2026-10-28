package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ObfsType
import com.interstellar.proxy.data.model.ProxyNode
import com.interstellar.proxy.data.subscription.YamlToJson
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
 *
 * NOTE: [ConfigBuilder] (sing-box) is deliberately not exercised here. Its
 * build path reaches Android-only state (the `lateinit` application singleton),
 * so a plain JVM test dies with UninitializedPropertyAccessException — that
 * predates this feature and would need Robolectric or an instrumented test.
 * The sing-box side is covered indirectly: the `lo:hi` form it emits comes
 * straight out of [ProxyNode.normalizeServerPorts], which is tested below, and
 * ConfigBuilder writes those strings through verbatim.
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
