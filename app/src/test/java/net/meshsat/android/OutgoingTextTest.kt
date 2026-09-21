package net.meshsat.android

import net.meshsat.android.engine.OutgoingText
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A mesh channel is shared with radios that are not MeshSat (MESHSAT-1286). Whatever is typed is
 * what goes on air: not a coded frame, not a version byte, not base64. If this ever needs to
 * change, it needs a way of knowing the far end is MeshSat first.
 */
class OutgoingTextTest {

    @Test
    fun `what is typed is what goes on the mesh`() {
        listOf(
            "on my way",
            "Need water and a medic at the north gate",
            "Καλημέρα, όλα καλά",
            "ATEPAE4AAAA=",
            "",
        ).forEach { assertEquals(it, OutgoingText.onMesh(it)) }
    }
}
