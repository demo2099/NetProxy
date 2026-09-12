package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** Mobile-simple routing rule: domain suffix → action. */
@Serializable
data class SimpleRouteRule(
    val id: String,
    /** Domain suffix match, e.g. "bilibili.com" covers all subdomains. */
    val domain: String,
    val action: Action,
    /** Node id when action == NODE (resolved to an outbound tag at build time). */
    val nodeId: String? = null,
    val enabled: Boolean = true,
) {
    enum class Action(val label: String) {
        @SerialName("direct")
        DIRECT("直连"),

        @SerialName("proxy")
        PROXY("代理"),

        @SerialName("node")
        NODE("指定节点"),
    }
}

/**
 * File-backed store for the simplified per-domain rules (replaces the
 * satelite-style keyword-filter pools on the phone UI).
 */
object SimpleRulesStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File
        get() = File(InterstellarApplication.application.filesDir, "simple_rules.json")

    var rules: MutableList<SimpleRouteRule> = load()
        private set

    fun enabled(): List<SimpleRouteRule> = rules.filter { it.enabled && it.domain.isNotBlank() }

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    fun upsert(rule: SimpleRouteRule) {
        val index = rules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            rules[index] = rule
        } else {
            rules.add(rule)
        }
        save()
    }

    @Synchronized
    fun remove(id: String) {
        rules.removeAll { it.id == id }
        save()
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val index = rules.indexOfFirst { it.id == id }
        if (index >= 0) {
            rules[index] = rules[index].copy(enabled = enabled)
            save()
        }
    }

    @Synchronized
    private fun save() {
        file.writeText(json.encodeToString(rules.toList()))
    }

    private fun load(): MutableList<SimpleRouteRule> = runCatching {
        if (file.isFile) json.decodeFromString<List<SimpleRouteRule>>(file.readText()).toMutableList()
        else mutableListOf()
    }.getOrDefault(mutableListOf())
}
