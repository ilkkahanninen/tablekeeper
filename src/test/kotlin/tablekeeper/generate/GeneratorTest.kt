package tablekeeper.generate

import tablekeeper.modules.GameModules
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GeneratorTest {
    private val morkborg = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("morkborg")
    private val dungeon = morkborg.generator("dungeon")!!

    /**
     * Every field rolls "<field> of <type>" (not an enum value, so a state falls back to a random one); "title" rolls only
     * for dungeons. The bestiary has one row; [bestiary] false: the campaign has no such table.
     */
    private fun rolls(bestiary: Boolean = true) = object : Rolls {
        override fun field(type: String, field: String) = if (field == "title" && type != "mb.dungeon") null else "$field of $type"
        override fun table(name: String) = if (bestiary && name == "Bestiary") "Wyvern | 25 | 10 | Thick hide -d4 | Bite/sting d6 | | Corpse 100s" else null
    }

    @Test
    fun `a dungeon has ten rooms from an entrance, and two to four creatures in rooms`() {
        repeat(30) { seed ->
            val plan = Generator.plan(dungeon, morkborg, rolls(), Random(seed))
            val byGroup = plan.nodes.withIndex().groupBy({ it.value.group }, { it.index })
            assertEquals(1, byGroup.getValue("dungeon").size)
            val rooms = byGroup.getValue("rooms")
            assertEquals(10, rooms.size)
            // Creatures come from the creature generator: its group, each a whole bestiary row
            val creatures = byGroup.getValue("creature")
            assertTrue(creatures.size in 2..4)

            val d = plan.nodes[byGroup.getValue("dungeon").single()]
            assertEquals("title of mb.dungeon", d.title)
            assertTrue(d.props["state"] in morkborg.propSchema("mb.dungeon", "state")!!["enum"].map { it.asText() }, "state from the enum")
            assertEquals("lurker of mb.dungeon", d.props["lurker"])

            val entrance = plan.nodes[rooms.first()]
            assertEquals("Entrance", entrance.title)
            assertEquals(true, entrance.props["entrance"])
            assertEquals((1..9).map { "Room $it" }, rooms.drop(1).map { plan.nodes[it].title })
            assertTrue(rooms.all { plan.nodes[it].props["description"] == "description of mb.room" })

            // Every room is in the dungeon; creatures are in rooms other than the entrance
            assertEquals(rooms.toSet(), plan.links.filter { it.rel == "room_in:out" }.map { it.from }.toSet())
            assertTrue(plan.links.filter { it.rel == "room_in:out" }.all { it.to == byGroup.getValue("dungeon").single() })
            val placed = plan.links.filter { it.rel == "located_at:out" }
            assertEquals(creatures.toSet(), placed.map { it.from }.toSet())
            assertTrue(placed.all { it.to in rooms.drop(1) }, "not at the entrance")
            assertTrue(creatures.all { plan.nodes[it].title == "Wyvern" && plan.nodes[it].props["hp"] == 25 })

            assertTrue(plan.links.filter { it.rel == "connects:out" }.size in 10..11)
        }
    }

    @Test
    fun `a chance field is filled now and then`() {
        val traps = (0 until 50).sumOf { seed ->
            Generator.plan(dungeon, morkborg, rolls(), Random(seed)).nodes.count { it.group == "rooms" && "trap" in it.props }
        }
        // 500 rooms at 0.3
        assertTrue(traps in 100..200, "$traps traps")
    }

    @Test
    fun `every planned node passes its props schema`() {
        val plan = Generator.plan(dungeon, morkborg, rolls(), Random(3))
        plan.nodes.forEach { n ->
            assertEquals(emptyList(), morkborg.validateProps(n.type, tablekeeper.core.Json.mapper.valueToTree(n.props)), n.toString())
        }
    }

    @Test
    fun `a creature fills its stat block from one bestiary row`() {
        val creature = morkborg.generator("creature")!!
        val c = Generator.plan(creature, morkborg, rolls(), Random(1)).nodes.single()
        assertEquals("core.npc", c.type)
        assertEquals("Wyvern", c.title)
        // HP typed as a number; the empty special column is left out
        assertEquals(mapOf("hp" to 25, "morale" to "10", "armor" to "Thick hide -d4", "attack" to "Bite/sting d6", "value" to "Corpse 100s"), c.props)
        // No Bestiary table (renamed or deleted): a plain new creature
        assertEquals("New Creature", Generator.plan(creature, morkborg, rolls(bestiary = false), Random(1)).nodes.single().title)
    }

    @Test
    fun `row columns are typed by the props schema`() {
        assertEquals(mapOf("title" to "Troll", "morale" to "special"),
            Generator.columns(morkborg, "core.npc", listOf("title", "hp", "morale"), "Troll | lots | special"), "HP that isn't a number is dropped")
    }

    @Test
    fun `new campaigns start with a first place and a session featuring it`() {
        val heart = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("heart")
        val h = Generator.plan(heart.starter!!, heart, rolls(), Random(1))
        assertEquals(listOf("Derelict", "First Session"), h.nodes.map { it.title })
        assertEquals(mapOf("tier" to "Tier 0", "stress" to "d4", "domains" to listOf("Haven"), "haunts" to "D10 Any"), h.nodes[0].props)
        assertEquals(listOf(PlannedLink(1, "features:out", 0)), h.links)
        h.nodes.forEach { assertEquals(emptyList(), heart.validateProps(it.type, tablekeeper.core.Json.mapper.valueToTree(it.props))) }

        val m = Generator.plan(morkborg.starter!!, morkborg, rolls(), Random(1))
        assertEquals(listOf("Galgenbeck", "First Session"), m.nodes.map { it.title })
        assertEquals("The greatest city that ever was.", m.nodes[0].body)
        assertEquals(emptyMap(), m.nodes[0].props, "the description is its notes")
        assertEquals(listOf(PlannedLink(1, "features:out", 0)), m.links)
    }
}
