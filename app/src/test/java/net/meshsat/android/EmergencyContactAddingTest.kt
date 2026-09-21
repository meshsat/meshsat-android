package net.meshsat.android

import net.meshsat.android.data.EmergencyContact
import net.meshsat.android.data.EmergencyContact.Companion.Added
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Emergency contacts come from the phone's own contacts (owner, 21 Sep 2026), and a contacts app
 * hands numbers over as people wrote them.
 */
class EmergencyContactAddingTest {

    private val anna = EmergencyContact("Anna", "+31612345678")

    @Test
    fun `a number as a contacts app writes it is taken`() {
        val r = EmergencyContact.adding(emptyList(), "Anna de Vries", "+31 6 1234-5678") as Added.Ok
        assertEquals(listOf(EmergencyContact("Anna de Vries", "+31612345678")), r.list)
        val national = EmergencyContact.adding(emptyList(), "Huisarts", "(020) 555 01 00") as Added.Ok
        assertEquals("0205550100", national.list.single().phone)
    }

    @Test
    fun `the same person picked twice is said, not doubled`() {
        val r = EmergencyContact.adding(listOf(anna), "Anna again", "+31 6 12345678")
        assertEquals(Added.No("That number is already on the list."), r)
    }

    @Test
    fun `a contact with no number, or not a number, is refused in words`() {
        assertEquals(Added.No("That contact has no phone number."), EmergencyContact.adding(emptyList(), "Bo", ""))
        assertEquals(Added.No("That is not a phone number."), EmergencyContact.adding(emptyList(), "Bo", "bo@example.org"))
    }

    @Test
    fun `the list stops at its limit`() {
        val full = (1..EmergencyContact.MAX).map { EmergencyContact("c$it", "+3161000000$it".take(12) + it) }
        assertTrue(EmergencyContact.adding(full, "One more", "+31699999999") is Added.No)
    }

    @Test
    fun `a name cannot break the stored list`() {
        val r = EmergencyContact.adding(emptyList(), "Anna\tde\nVries", "+31612345678") as Added.Ok
        assertEquals(r.list, EmergencyContact.decode(EmergencyContact.encode(r.list)))
        assertEquals("Anna de Vries", r.list.single().name)
    }
}
