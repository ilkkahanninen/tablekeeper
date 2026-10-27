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

    @Test
    fun `npcs at a selected landmark or inside it are around, others are not`() {
        val market = UUID.randomUUID(); val alley = UUID.randomUUID(); val elsewhere = UUID.randomUUID()
        val silvia = UUID.randomUUID(); val grub = UUID.randomUUID(); val moth = UUID.randomUUID(); val pool = UUID.randomUUID()
        val at = mapOf(silvia to listOf(market), grub to listOf(alley), moth to listOf(elsewhere), pool to emptyList())
        val parent = mapOf(alley to market)
        assertEquals(setOf(silvia, grub), Packet.around(setOf(market), at.keys.toList(), { at.getValue(it) }, { parent[it] }).toSet())
    }

    @Test
    fun `npcs in a delve between selected landmarks are around too`() {
        val adversary = UUID.randomUUID(); val beyond = UUID.randomUUID()
        val at = mapOf(adversary to listOf(ab), beyond to listOf(cd))
        // The packet's anchors: the selected landmarks a and b, and the delve ab between them
        val anchors = setOf(a, b) + Packet.between(setOf(a, b), connects.keys.toList()) { connects.getValue(it) }
        assertEquals(listOf(adversary), Packet.around(anchors, at.keys.toList(), { at.getValue(it) }, { null }))
    }

    @Test
    fun `factions with a member in the packet are related, others are not`() {
        val silvia = UUID.randomUUID(); val moth = UUID.randomUUID()
        val hounds = UUID.randomUUID(); val church = UUID.randomUUID(); val empty = UUID.randomUUID()
        val members = mapOf(hounds to listOf(silvia), church to listOf(moth), empty to emptyList())
        assertEquals(listOf(hounds), Packet.related(setOf(silvia), members.keys.toList()) { members.getValue(it) })
    }
}
