package com.interstellar.proxy.data

import com.interstellar.proxy.InterstellarApplication
import com.interstellar.proxy.data.model.DnsOverrideEntry
import com.interstellar.proxy.data.model.isValidIpLiteral
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * File-backed store for user-defined domain→IP DNS overrides
 * (injected into the generated config as a hosts DNS server).
 */
object DnsOverridesStore {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val file: File
        get() = File(InterstellarApplication.application.filesDir, "dns_overrides.json")

    var entries: MutableList<DnsOverrideEntry> = load()
        private set

    fun enabled(): List<DnsOverrideEntry> =
        entries.filter { it.enabled && it.parsedDomains().isNotEmpty() && isValidIpLiteral(it.ip) }

    fun newId(): String = UUID.randomUUID().toString()

    @Synchronized
    fun upsert(entry: DnsOverrideEntry) {
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            entries[index] = entry
        } else {
            entries.add(entry)
        }
        save()
    }

    @Synchronized
    fun remove(id: String) {
        entries.removeAll { it.id == id }
        save()
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return
        entries[index] = entries[index].copy(enabled = enabled)
        save()
    }

    private fun load(): MutableList<DnsOverrideEntry> = runCatching {
        if (file.exists()) {
            json.decodeFromString<List<DnsOverrideEntry>>(file.readText()).toMutableList()
        } else {
            mutableListOf()
        }
    }.getOrDefault(mutableListOf())

    @Synchronized
    private fun save() {
        runCatching {
            file.writeText(json.encodeToString(entries.toList()))
        }
    }
}
