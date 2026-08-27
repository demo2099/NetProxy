package com.interstellar.proxy.data.subscription

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull

/** Converts a kaml YAML tree into a kotlinx JsonElement tree for uniform handling. */
object YamlToJson {
    fun convert(yamlText: String): JsonElement? {
        val node = runCatching { Yaml.default.parseToYamlNode(yamlText) }.getOrNull() ?: return null
        return convertNode(node)
    }

    private fun convertNode(node: com.charleskorn.kaml.YamlNode): JsonElement = when (node) {
        is YamlMap -> JsonObject(node.entries.entries.associate { (k, v) ->
            (k.content as? String ?: k.content.toString()) to convertNode(v)
        })

        is YamlList -> JsonArray(node.items.map { convertNode(it) })

        is YamlScalar -> JsonPrimitive(node.content)

        is YamlNull -> JsonNull

        else -> JsonNull
    }
}

// ---- JsonElement field helpers ----

fun JsonElement?.obj(key: String): JsonObject? = this?.let { el ->
    (el as? JsonObject)?.get(key) as? JsonObject
}

fun JsonElement?.arr(key: String): JsonArray? = this?.let { el ->
    (el as? JsonObject)?.get(key) as? JsonArray
}

fun JsonElement?.str(key: String): String? {
    val el = (this as? JsonObject)?.get(key) ?: return null
    val primitive = el as? JsonPrimitive ?: return null
    if (primitive.isString) return primitive.content
    return primitive.content.ifBlank { null }
}

fun JsonElement?.num(key: String): Int? {
    val el = (this as? JsonObject)?.get(key) ?: return null
    val primitive = el as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.content.toIntOrNull()
}

fun JsonElement?.bool(key: String): Boolean? {
    val el = (this as? JsonObject)?.get(key) ?: return null
    val primitive = el as? JsonPrimitive ?: return null
    return primitive.booleanOrNull ?: primitive.content.lowercase().toBooleanStrictOrNull()
}

fun JsonElement?.strList(key: String): List<String>? {
    val el = (this as? JsonObject)?.get(key) ?: return null
    return when (el) {
        is JsonArray -> el.mapNotNull { (it as? JsonPrimitive)?.content?.ifBlank { null } }.takeIf { it.isNotEmpty() }
        is JsonPrimitive -> el.content.split(',').map { it.trim() }.filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() }
        else -> null
    }
}
