package com.cubeos.meshsat.engine

import org.json.JSONArray

/**
 * A single transform step in a pipeline.
 * Port of meshsat/internal/engine/transform.go TransformSpec.
 */
data class TransformSpec(
    val type: String,
    val params: Map<String, String> = emptyMap(),
) {
    companion object {
        /** Parse a JSON array of transform specs. Returns empty list for null/empty input. */
        fun parseList(json: String?): List<TransformSpec> {
            if (json.isNullOrBlank() || json == "[]") return emptyList()
            val array = JSONArray(json)
            return (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                val params = mutableMapOf<String, String>()
                obj.optJSONObject("params")?.let { p ->
                    p.keys().forEach { key -> params[key] = p.getString(key) }
                }
                TransformSpec(type = obj.getString("type"), params = params)
            }
        }
    }
}
