package com.interstellar.proxy.data.config

import com.interstellar.proxy.data.model.NodeType
import com.interstellar.proxy.data.model.ProxyNode
import com.interstellar.proxy.data.subscription.UriParser
import com.interstellar.proxy.data.subscription.YamlToJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Test

/**
 * Certificate-verification flags.
 *
 * This file exists because of a real failure: a hysteria2 node on a
 * self-signed server worked in every other client and failed here with
 * `x509: certificate signed by unknown authority` on every request. Two
 * independent causes, both pinned below:
 *
 *  1. `insecure=1` (the spelling hysteria2 links actually use) was parsed with
 *     `String.toBoolean()`, which only accepts "true" — so `"1"` became `false`
 *     and verification was switched back on for the one node that needed it off.
 *  2. A node with no SNI on a bare IP had no path to `insecure` at all, even
 *     though no public CA can issue a certificate for a bare address.
 *
 * [ConfigBuilder] (sing-box) cannot be exercised from a plain JVM test — its
 * build path reaches the Android application singleton — so the emission half is
 * pinned through [MihomoConfigBuilder], which writes the same
 * [ProxyNode.effectiveInsecure] value under its own key name.
 */
class InsecureFlagTest {

    // ---- the flag parser ----

    @Test
    fun `insecure flag accepts the spellings that appear in the wild`() {
        // The regression: "1" used to parse as false.
        check(ProxyNode.parseBoolFlag("1") == true) { "1 must mean true" }
        check(ProxyNode.parseBoolFlag("0") == false) { "0 must mean false" }
        check(ProxyNode.parseBoolFlag("true") == true) { "true" }
        check(ProxyNode.parseBoolFlag("TRUE") == true) { "case insensitive" }
        check(ProxyNode.parseBoolFlag(" false ") == false) { "surrounding space" }
        check(ProxyNode.parseBoolFlag("yes") == true) { "yes" }
        check(ProxyNode.parseBoolFlag("no") == false) { "no" }
        // Unknown is null, not false: an unparseable value must fall through to
        // the protocol default instead of silently forcing verification on.
        check(ProxyNode.parseBoolFlag("maybe") == null) { "junk is unknown" }
        check(ProxyNode.parseBoolFlag("") == null) { "empty is unknown" }
        check(ProxyNode.parseBoolFlag(null) == null) { "absent is unknown" }
    }

    // ---- the fallback ----

    @Test
    fun `bare IP without SNI skips verification for TLS-only protocols`() {
        val hy2 = node(NodeType.HYSTERIA2, server = "47.236.153.15", sni = null)
        check(hy2.effectiveInsecure) { "hy2 on a bare IP has no verifiable certificate" }

        val tuic = node(NodeType.TUIC, server = "47.236.153.15", sni = null)
        check(tuic.effectiveInsecure) { "tuic behaves the same" }

        val v6 = node(NodeType.HYSTERIA2, server = "2607:8700:5500:b795::2", sni = null)
        check(v6.effectiveInsecure) { "IPv6 literals are addresses too" }
    }

    @Test
    fun `everything else keeps the historical default of verify`() {
        // A domain SNI can carry a valid certificate, so never assume.
        val withSni = node(NodeType.HYSTERIA2, server = "47.236.153.15", sni = "www.amd.com")
        check(!withSni.effectiveInsecure) { "SNI present ⇒ verify" }

        // A hostname is resolvable to a real certificate.
        val byName = node(NodeType.HYSTERIA2, server = "sg.example.net", sni = null)
        check(!byName.effectiveInsecure) { "hostname ⇒ verify" }

        // TLS is opt-in for these, so the fallback does not apply to them.
        val vless = node(NodeType.VLESS, server = "47.236.153.15", sni = null)
        check(!vless.effectiveInsecure) { "vless is not TLS-only" }
    }

    @Test
    fun `an explicit value always wins over the fallback`() {
        val explicitOff = node(NodeType.HYSTERIA2, server = "47.236.153.15", sni = null, insecure = false)
        check(!explicitOff.effectiveInsecure) { "explicit false must be honoured" }

        val explicitOn = node(NodeType.HYSTERIA2, server = "sg.example.net", sni = "sg.example.net", insecure = true)
        check(explicitOn.effectiveInsecure) { "explicit true must be honoured" }
    }

    // ---- end to end: link → node → emitted config ----

    @Test
    fun `hysteria2 link with insecure=1 reaches the kernel config`() {
        val link = "hysteria2://pw@47.236.153.15:8443/?insecure=1" +
            "&obfs=gecko&obfs-password=gecko&mport=20000-30000&hop-interval=30#SG"
        val parsed = UriParser.parseUriLine(link) ?: error("link did not parse")

        check(parsed.insecure == true) { "insecure=1 must parse as true, got ${parsed.insecure}" }
        check(emittedSkipCertVerify(parsed)) { "mihomo must be told to skip verification" }
    }

    @Test
    fun `hysteria2 link without any flag still works on a bare IP`() {
        val link = "hysteria2://pw@47.236.153.15:8443/?obfs=gecko&obfs-password=gecko#SG"
        val parsed = UriParser.parseUriLine(link) ?: error("link did not parse")

        check(parsed.insecure == null) { "no flag ⇒ unknown, not false" }
        check(emittedSkipCertVerify(parsed)) { "the bare-IP fallback must kick in" }
    }

    @Test
    fun `allowInsecure spelling is understood on vless`() {
        val link = "vless://11111111-2222-3333-4444-555555555555@1.2.3.4:443" +
            "?security=tls&sni=a.example.com&allowInsecure=1#V"
        val parsed = UriParser.parseUriLine(link) ?: error("link did not parse")
        check(parsed.insecure == true) { "allowInsecure=1 must parse as true" }
    }

    @Test
    fun `the standard trailing-slash form parses`() {
        // Most generators write `...:8443/?query`; the slash used to land in
        // the port, fail toIntOrNull(), and make the whole line disappear.
        val link = "hysteria2://pw@47.236.153.15:8443/?insecure=1&obfs=gecko&obfs-password=g#SG"
        val parsed = UriParser.parseUriLine(link) ?: error("trailing-slash link did not parse")
        check(parsed.server == "47.236.153.15") { "host" }
        check(parsed.port == 8443) { "port must not swallow the slash" }
        check(parsed.insecure == true) { "flag" }
    }

    // ---- the cached-node repair ----

    @Test
    fun `a node cached by the old parser is repaired on load`() {
        // Exactly what 0.8.4 stored for a link that said insecure=1.
        val stale = node(NodeType.HYSTERIA2, "47.236.153.15", sni = null, insecure = false)
        check(!stale.effectiveInsecure) { "precondition: the stale explicit false blocks the fallback" }

        val fixed = stale.withRepairedInsecure()
        check(fixed.insecure == null) { "the stale false must be dropped" }
        check(fixed.effectiveInsecure) { "the repaired node skips verification" }
        check(fixed.withRepairedInsecure() == fixed) { "the repair is idempotent" }

        // A node that can actually verify keeps whatever it was told.
        val honest = node(NodeType.HYSTERIA2, "sg.example.net", sni = "sg.example.net", insecure = false)
        check(honest.withRepairedInsecure() == honest) { "an explicit false on a real host is honoured" }
    }

    // ---- helpers ----

    private fun node(
        type: NodeType,
        server: String,
        sni: String?,
        insecure: Boolean? = null,
    ) = ProxyNode(
        id = "n", name = "n", type = type, server = server, port = 8443,
        password = "pw", sni = sni, insecure = insecure,
    )

    /** Read back what mihomo is actually told, rather than trusting the model. */
    private fun emittedSkipCertVerify(node: ProxyNode): Boolean {
        val yaml = MihomoConfigBuilder.build(
            listOf(node),
            ConfigBuilder.BuildOptions(selectedNodeTag = node.name),
        )
        val doc = YamlToJson.convert(yaml)?.jsonObject ?: error("generated yaml failed to parse")
        val proxy = doc["proxies"]!!.jsonArray.first().jsonObject
        // YamlToJson keeps scalars as text, so compare the content — reading
        // `.boolean` here would throw on a perfectly valid config.
        val flag = proxy["skip-cert-verify"] as? JsonPrimitive ?: return false
        return flag.content.equals("true", ignoreCase = true)
    }
}
