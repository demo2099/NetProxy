package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.model.CustomRouteRule
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-backed store for user-defined domain → node-filter routing rules.
 */
object CustomRulesStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File
        get() = File(InterstellarApplication.application.filesDir, "custom_rules.json")

    var rules: MutableList<CustomRouteRule> = load()
        private set

    fun enabled(): List<CustomRouteRule> = rules.filter { it.enabled && it.parsedMatchValues().isNotEmpty() && it.nodeKeywords.any { kw -> kw.isNotBlank() } }

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    fun upsert(rule: CustomRouteRule) {
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
        if (index < 0) return
        rules[index] = rules[index].copy(enabled = enabled)
        save()
    }

    @Synchronized
    fun replaceAll(next: List<CustomRouteRule>) {
        rules = next.toMutableList()
        save()
    }

    private fun load(): MutableList<CustomRouteRule> = runCatching {
        if (file.exists()) {
            json.decodeFromString<List<CustomRouteRule>>(file.readText()).toMutableList()
        } else {
            mutableListOf()
        }
    }.getOrDefault(mutableListOf())

    @Synchronized
    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(rules.toList()))
        }
    }
}
