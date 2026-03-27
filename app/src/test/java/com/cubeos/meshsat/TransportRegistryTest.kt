package com.cubeos.meshsat

import com.cubeos.meshsat.service.TransportRegistry
import org.junit.Assert.*
import org.junit.Test

class TransportRegistryTest {

    @Test
    fun `register and get by id`() {
        val reg = TransportRegistry()
        reg.register("test_0", "hello")
        assertEquals("hello", reg.get<String>("test_0"))
    }

    @Test
    fun `get returns null for missing id`() {
        val reg = TransportRegistry()
        assertNull(reg.get<String>("missing"))
    }

    @Test
    fun `getAll filters by type`() {
        val reg = TransportRegistry()
        reg.register("str_0", "hello")
        reg.register("str_1", "world")
        reg.register("int_0", 42)
        val strings = reg.getAll(String::class.java)
        assertEquals(2, strings.size)
        assertTrue(strings.containsKey("str_0"))
        assertTrue(strings.containsKey("str_1"))
    }

    @Test
    fun `unregister removes transport`() {
        val reg = TransportRegistry()
        reg.register("a", "x")
        reg.unregister("a")
        assertNull(reg.get<String>("a"))
        assertEquals(0, reg.size())
    }

    @Test
    fun `clear removes all`() {
        val reg = TransportRegistry()
        reg.register("a", "x")
        reg.register("b", "y")
        reg.clear()
        assertEquals(0, reg.size())
    }
}
