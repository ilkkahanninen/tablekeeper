package tablekeeper.print

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class PacketTest {
    private val a = UUID.randomUUID(); private val b = UUID.randomUUID(); private val c = UUID.randomUUID(); private val d = UUID.randomUUID()
    private val ab = UUID.randomUUID(); private val bc = UUID.randomUUID(); private val cd = UUID.randomUUID()
    private val deadEnd = UUID.randomUUID(); private val unlinked = UUID.randomUUID()
    // Delve -> the landmarks it connects
    private val connects = mapOf(ab to listOf(a, b), bc to listOf(b, c), cd to listOf(c, d), deadEnd to listOf(a), unlinked to emptyList())

    @Test
    fun `delves between selected landmarks are in, ones leading out are not`() {
        val chosen = Packet.between(setOf(a, b, c), connects.keys.toList()) { connects.getValue(it) }
        assertEquals(setOf(ab, bc, deadEnd), chosen.toSet())
    }

    @Test
    fun `nothing selected means no delves`() {
        assertEquals(emptyList(), Packet.between(emptySet(), connects.keys.toList()) { connects.getValue(it) })
    }
}
