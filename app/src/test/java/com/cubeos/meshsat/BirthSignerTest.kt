package com.cubeos.meshsat

import com.cubeos.meshsat.hub.BirthSigner
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests canonical JSON serialization using Map input.
 * (JSONObject doesn't work in JVM unit tests — Android stubs return null)
 */
class BirthSignerTest {
    @Test
    fun `canonical JSON sorts keys`() {
        val map = mapOf("z_last" to "z", "a_first" to "a", "m_middle" to "m")
        assertEquals(
            "{\"a_first\":\"a\",\"m_middle\":\"m\",\"z_last\":\"z\"}",
            BirthSigner.toCanonicalJson(map)
        )
    }

    @Test
    fun `canonical JSON no whitespace`() {
        val map = mapOf("key" to "value", "num" to 42)
        val canonical = BirthSigner.toCanonicalJson(map)
        assertFalse(canonical.contains(": "))
        assertFalse(canonical.contains(", "))
    }

    @Test
    fun `canonical JSON handles nested objects`() {
        val inner = mapOf("b" to 2, "a" to 1)
        val outer = mapOf("nested" to inner, "aaa" to "first")
        assertEquals(
            "{\"aaa\":\"first\",\"nested\":{\"a\":1,\"b\":2}}",
            BirthSigner.toCanonicalJson(outer)
        )
    }

    @Test
    fun `canonical JSON handles arrays`() {
        val map = mapOf("list" to listOf("a", "b", "c"))
        assertEquals(
            "{\"list\":[\"a\",\"b\",\"c\"]}",
            BirthSigner.toCanonicalJson(map)
        )
    }

    @Test
    fun `canonical JSON handles booleans and nulls`() {
        val map = mapOf<String, Any?>("flag" to true, "nil" to null, "aaa" to false)
        assertEquals(
            "{\"aaa\":false,\"flag\":true,\"nil\":null}",
            BirthSigner.toCanonicalJson(map)
        )
    }

    @Test
    fun `canonical JSON escapes special chars`() {
        val map = mapOf("msg" to "hello\nworld\"test")
        val canonical = BirthSigner.toCanonicalJson(map)
        assertTrue(canonical.contains("\\n"))
        assertTrue(canonical.contains("\\\""))
    }

    @Test
    fun `canonical JSON matches Go format for birth-like payload`() {
        val map = mapOf(
            "protocol" to "meshsat-uplink/v1",
            "bridge_id" to "android-01",
            "version" to "2.3.0",
            "capabilities" to emptyList<String>(),
            "timestamp" to "2026-03-29T10:00:00Z",
        )
        val canonical = BirthSigner.toCanonicalJson(map)
        // Keys must be alphabetically sorted
        assertTrue(canonical.indexOf("\"bridge_id\"") < canonical.indexOf("\"capabilities\""))
        assertTrue(canonical.indexOf("\"capabilities\"") < canonical.indexOf("\"protocol\""))
        assertTrue(canonical.indexOf("\"protocol\"") < canonical.indexOf("\"timestamp\""))
        assertTrue(canonical.indexOf("\"timestamp\"") < canonical.indexOf("\"version\""))
        // Empty array
        assertTrue(canonical.contains("\"capabilities\":[]"))
    }

    @Test
    fun `integers not rendered as doubles`() {
        val map = mapOf("count" to 42, "zero" to 0)
        val canonical = BirthSigner.toCanonicalJson(map)
        assertTrue(canonical.contains("\"count\":42"))
        assertTrue(canonical.contains("\"zero\":0"))
        assertFalse(canonical.contains(".0"))
    }

    @Test
    fun `doubles rendered correctly`() {
        val map = mapOf<String, Any>("lat" to 39.237, "whole" to 42.0)
        val canonical = BirthSigner.toCanonicalJson(map)
        assertTrue(canonical.contains("\"lat\":39.237"))
        assertTrue(canonical.contains("\"whole\":42")) // 42.0 → 42
    }

    @Test
    fun `empty object`() {
        assertEquals("{}", BirthSigner.toCanonicalJson(emptyMap()))
    }

    @Test
    fun `nested arrays and objects`() {
        val map = mapOf(
            "interfaces" to listOf(
                mapOf("name" to "ble", "online" to true),
                mapOf("name" to "iridium", "online" to false),
            )
        )
        val canonical = BirthSigner.toCanonicalJson(map)
        assertEquals(
            "{\"interfaces\":[{\"name\":\"ble\",\"online\":true},{\"name\":\"iridium\",\"online\":false}]}",
            canonical
        )
    }
}
