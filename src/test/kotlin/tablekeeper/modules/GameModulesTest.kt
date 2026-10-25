package tablekeeper.modules

import tablekeeper.core.Direction
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GameModulesTest {
    private val heart = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("heart")

    @Test
    fun `landmark is a place through inheritance`() {
        assertTrue(heart.isA("heart.landmark", "core.place"))
        assertFalse(heart.isA("heart.landmark", "core.npc"))
    }

    @Test
    fun `heart hides generic place but keeps npc`() {
        val ids = heart.nodeTypes.map { it.id }
        assertTrue("heart.landmark" in ids)
        assertTrue("core.npc" in ids)
        assertFalse("core.place" in ids)
    }

    @Test
    fun `landmark offers inverse located_at as 'has here'`() {
        val opt = heart.linkOptions("heart.landmark").single { it.edgeType == "located_at" }
        assertEquals(Direction.IN, opt.direction)
        assertEquals("has here", opt.label)
        assertTrue(heart.acceptsOther(opt, "core.npc"))
        assertFalse(heart.acceptsOther(opt, "core.fact"))
    }

    @Test
    fun `props schema rejects unknown tier and domain`() {
        val bad = GameModules.mapper.readTree("""{"tier": 1, "domains": ["Moon"]}""")
        assertTrue(heart.validateProps("heart.landmark", bad).size >= 2)
        val ok = GameModules.mapper.readTree("""{"tier": "Tier 1", "domains": ["Occult", "Desolate"]}""")
        assertTrue(heart.validateProps("heart.landmark", ok).isEmpty())
    }

    @Test
    fun `form params become typed props`() {
        val (props, errors) = PropsForm.parse(
            heart.propsSchema("heart.landmark"),
            mapOf("prop.stress" to listOf("d6"), "prop.domains" to listOf("Occult", "Wild")),
        )
        assertTrue(errors.isEmpty())
        assertEquals("d6", props.get("stress").asText())
        assertEquals(2, props.get("domains").size())
    }

    @Test
    fun `landmark quick-creates delve, minor location, npc, resource, equipment and fact`() {
        val opts = heart.quickCreate("heart.landmark")
        assertEquals(
            listOf("heart.delve" to "reached via", "heart.minor_location" to "minor locations", "core.npc" to "has here", "heart.resource" to "has here", "heart.equipment" to "has here", "core.fact" to "facts"),
            opts.map { it.type.id to it.option.label },
        )
        assertTrue(opts.all { it.option.direction == Direction.IN })
    }

    @Test
    fun `delve quick-creates the landmark it connects to, npc, resource, equipment and fact`() {
        val opts = heart.quickCreate("heart.delve")
        assertEquals(
            listOf("heart.landmark" to "connects", "core.npc" to "has here", "heart.resource" to "has here", "heart.equipment" to "has here", "core.fact" to "facts"),
            opts.map { it.type.id to it.option.label },
        )
        assertEquals(Direction.OUT, opts.first().option.direction)
    }

    @Test
    fun `delve takes domains from connected landmarks instead of its own`() {
        val (tier, rule) = heart.linkedProps("heart.delve")
        assertEquals("tier", tier.prop)
        assertEquals("domains", rule.prop)
        assertEquals(LinkOption("connects", Direction.OUT, "connects"), rule.option)
        assertTrue(PropsForm.fields(heart.propsSchema("heart.delve"), emptyMap()).none { it.name == "domains" })
        assertTrue(heart.linkedProps("heart.landmark").isEmpty())
    }

    @Test
    fun `a delve connects at most two landmarks`() {
        assertEquals(2, heart.linkLimit("heart.delve", "connects", Direction.OUT))
        assertEquals(null, heart.linkLimit("heart.landmark", "connects", Direction.IN))
        assertTrue(heart.linkSlots("heart.delve").isEmpty())
    }

    @Test
    fun `characters and npcs have a Location slot, factions don't`() {
        for (type in listOf("core.npc", "heart.pc")) {
            val slot = heart.linkSlots(type).single()
            assertEquals("Location", slot.label)
            assertEquals(listOf("located_at:out"), slot.options.map { it.key }, type)
        }
        assertTrue(heart.linkSlots("core.faction").isEmpty())
        assertEquals(null, heart.slotFor("heart.landmark", "located_at", Direction.IN))
    }

    @Test
    fun `a resource is either at a place or carried, in one Location slot`() {
        val slot = heart.linkSlots("heart.resource").single()
        assertEquals(listOf("located_at:out" to "is located at", "carries:in" to "carried by"), slot.options.map { it.key to it.label })
        assertEquals(slot, heart.slotFor("heart.resource", "carries", Direction.IN))
        // Carrying is not exclusive for the carrier
        assertEquals(null, heart.slotFor("core.npc", "carries", Direction.OUT))
    }

    @Test
    fun `resource die values carry the rule book's descriptions`() {
        val die = PropsForm.fields(heart.propsSchema("heart.resource"), mapOf("die" to "d8")).single { it.name == "die" }
        assertEquals(listOf("d4", "d6", "d8", "d10", "d12"), die.options)
        assertEquals("d8 Valuable or rare item", die.text)
        assertEquals("d8", die.storedText)
        assertEquals(null, die.labels["d6"], "no rule-book text for d6")
    }

    @Test
    fun `resource tags are checkboxes with rule-book hints`() {
        val tags = PropsForm.fields(heart.propsSchema("heart.resource"), mapOf("tags" to listOf("Fragile"))).single { it.name == "tags" }
        assertEquals("multi", tags.kind)
        assertEquals(9, tags.options.size)
        assertEquals("The resource may explode if mistreated.", tags.hints["Volatile"])
        assertTrue(heart.validateProps("heart.resource", GameModules.mapper.readTree("""{"tags": ["Fragile", "Taboo"]}""")).isEmpty())
        assertFalse(heart.validateProps("heart.resource", GameModules.mapper.readTree("""{"tags": ["Shiny"]}""")).isEmpty())
    }

    @Test
    fun `equipment is an item, placed at a landmark or delve, or carried`() {
        assertTrue(heart.isA("heart.equipment", "core.item"))
        assertTrue("heart.equipment" in heart.nodeTypes.map { it.id })
        val slot = heart.linkSlots("heart.equipment").single()
        assertEquals("Location", slot.label)
        val (at, carried) = slot.options
        for (place in listOf("heart.landmark", "heart.delve")) assertTrue(heart.acceptsOther(at, place), place)
        for (carrier in listOf("core.npc", "heart.pc")) assertTrue(heart.acceptsOther(carried, carrier), carrier)
        val fields = PropsForm.fields(heart.propsSchema("heart.equipment"), emptyMap()).associateBy { it.name }
        assertEquals(listOf("d4", "d6", "d8", "d10", "d12"), fields.getValue("quality").options)
        assertEquals("d8 Professional", fields.getValue("quality").labels["d8"])
        assertEquals("d8 Professional", PropsForm.fields(heart.propsSchema("heart.equipment"), mapOf("quality" to "d8"))
            .single { it.name == "quality" }.text)
        val summary = heart.printSummary("heart.equipment")!!
        assertEquals("Equipment", summary.group)
        assertEquals(setOf("category", "quality", "tags", "limitedUses"), summary.propNames)
        val quality = PropsForm.fields(heart.propsSchema("heart.equipment"), mapOf("quality" to "d8")).single { it.name == "quality" }
        assertEquals("d8", quality.storedText)
        val tags = fields.getValue("tags")
        assertEquals("multi", tags.kind)
        assertEquals(27, tags.options.size)
        assertTrue(tags.options.all { it in tags.hints }, "every equipment tag has a description")
        assertTrue(heart.validateProps("heart.equipment", GameModules.mapper.readTree("""{"tags": ["Brutal", "Limited X"]}""")).isEmpty())
        assertEquals(listOf("{die.value}", "{tags}"), heart.printSummary("heart.resource")?.parts)
        assertTrue(heart.validateProps("heart.equipment", GameModules.mapper.readTree("""{"category": "Kill", "quality": "d10"}""")).isEmpty())
        assertFalse(heart.validateProps("heart.equipment", GameModules.mapper.readTree("""{"quality": "Exotic"}""")).isEmpty())
    }

    @Test
    fun `equipment quality is hidden and dropped for Miscellaneous`() {
        val schema = heart.propsSchema("heart.equipment")
        fun quality(props: Map<String, Any?>) = PropsForm.fields(schema, props).single { it.name == "quality" }
        assertTrue(quality(mapOf("category" to "Miscellaneous")).hidden)
        assertFalse(quality(mapOf("category" to "Kill")).hidden)
        assertFalse(quality(emptyMap()).hidden)

        val (misc, _) = PropsForm.parse(schema, mapOf("prop.category" to listOf("Miscellaneous"), "prop.quality" to listOf("d10")))
        assertFalse(misc.has("quality"))
        val (kill, _) = PropsForm.parse(schema, mapOf("prop.category" to listOf("Kill"), "prop.quality" to listOf("d10")))
        assertEquals("d10", kill.get("quality").asText())
    }

    @Test
    fun `limited uses shows and saves only with the Limited X tag`() {
        val schema = heart.propsSchema("heart.equipment")
        fun uses(props: Map<String, Any?>) = PropsForm.fields(schema, props).single { it.name == "limitedUses" }
        assertEquals("int", uses(emptyMap()).kind)
        assertTrue(uses(emptyMap()).hidden)
        assertTrue(uses(mapOf("tags" to listOf("Brutal"))).hidden)
        assertFalse(uses(mapOf("tags" to listOf("Brutal", "Limited X"))).hidden)

        val (with, _) = PropsForm.parse(schema, mapOf("prop.tags" to listOf("Limited X"), "prop.limitedUses" to listOf("3")))
        assertEquals(3, with.get("limitedUses").asInt())
        val (without, _) = PropsForm.parse(schema, mapOf("prop.tags" to listOf("Brutal"), "prop.limitedUses" to listOf("3")))
        assertFalse(without.has("limitedUses"))
        assertFalse(heart.validateProps("heart.equipment", GameModules.mapper.readTree("""{"limitedUses": 0}""")).isEmpty())
    }

    @Test
    fun `characters and npcs quick-create carried resources and equipment`() {
        for (type in listOf("core.npc", "heart.pc")) {
            assertEquals(
                listOf("heart.resource" to "carries", "heart.equipment" to "carries"),
                heart.quickCreate(type).map { it.type.id to it.option.label }, type,
            )
        }
        // The patch is Heart's: plain core campaigns are unchanged
        val core = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("core")
        assertTrue(core.quickCreate("core.npc").isEmpty())
    }

    @Test
    fun `patching an unknown type fails module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText("""{"id": "bad", "name": "Bad", "patchTypes": [{"id": "nope.npc"}]}""")
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("nope.npc" in e.message.orEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `characters have two beats, each a line of text and minor, major or zenith`() {
        val fields = PropsForm.fields(heart.propsSchema("heart.pc"), emptyMap()).associateBy { it.name }
        for (n in 1..2) {
            assertEquals("text", fields.getValue("beat$n").kind)
            assertEquals(listOf("Minor", "Major", "Zenith"), fields.getValue("beat${n}Type").options)
        }
        assertFalse("beat3" in fields)
        assertTrue(listOf("Blightborn", "Crawler", "Snail Pilgrim", "Witness").all { it in fields.getValue("class").options })
        assertTrue(listOf("Artist", "Containment", "Demagogue", "Protector").all { it in fields.getValue("calling").options })
        val beatRows = PropsForm.rows(fields.values.toList()).filter { it.label != null }
        assertEquals(listOf("Beat 1", "Beat 2"), beatRows.map { it.label })
        assertEquals(listOf("beat1Type", "beat1"), beatRows.first().fields.map { it.name })
        val ok = """{"beat1": "Find the ledger", "beat1Type": "Major", "beat2": "Pay Silvia back", "beat2Type": "Minor"}"""
        assertTrue(heart.validateProps("heart.pc", GameModules.mapper.readTree(ok)).isEmpty())
        assertFalse(heart.validateProps("heart.pc", GameModules.mapper.readTree("""{"beat3": "x"}""")).isEmpty())
        assertFalse(heart.validateProps("heart.pc", GameModules.mapper.readTree("""{"beat1Type": "Huge"}""")).isEmpty())
    }

    @Test
    fun `a minor location belongs to one landmark and things can be placed there`() {
        assertTrue(heart.isA("heart.minor_location", "core.place"))
        assertFalse(heart.isA("heart.minor_location", "heart.landmark"))  // delves don't connect to it
        val slot = heart.linkSlots("heart.minor_location").single()
        assertEquals("Landmark", slot.label)
        assertTrue(heart.acceptsOther(slot.options.single(), "heart.landmark"))
        assertFalse(heart.acceptsOther(slot.options.single(), "heart.delve"))
        // Items, NPCs and characters can be located there
        val at = heart.linkSlots("heart.resource").single().options.first { it.edgeType == "located_at" }
        assertTrue(heart.acceptsOther(at, "heart.minor_location"))
        assertEquals(
            listOf("core.npc", "heart.resource", "heart.equipment", "core.fact"),
            heart.quickCreate("heart.minor_location").map { it.type.id },
        )
        assertEquals(listOf(PrintNestedDef("part_of:in", "located_at:in")), heart.printNested("heart.landmark"))
        assertTrue(heart.printNested("heart.delve").isEmpty())
        assertEquals("part_of:out", heart.context("heart.minor_location")?.key)
        assertEquals(null, heart.context("heart.landmark"))
    }

    @Test
    fun `places are drawn on the map with their shape and depth`() {
        assertEquals("circle", heart.mapShape("heart.landmark"))
        assertEquals("diamond", heart.mapShape("heart.delve"))
        assertEquals("dot", heart.mapShape("heart.minor_location"))
        assertEquals(null, heart.mapShape("heart.resource"))
        assertEquals("tier", heart.mapDepth("heart.landmark"))
        assertEquals(MapDepth(2.0, "Tier 2"), heart.depthOf("heart.landmark", mapOf("tier" to "Tier 2")))
        assertEquals(null, heart.depthOf("heart.landmark", mapOf("tier" to "Rogue")), "placed freely")
        assertEquals(MapDepth(1.0, "Tier 1"), heart.depthOf("heart.landmark", mapOf("tier" to 1)), "numbers still work for other modules")
        assertEquals(null, heart.mapDepth("heart.delve"))
        assertEquals("connects:out", heart.mapBetween("heart.delve")?.key)
        assertTrue(PropsForm.fields(heart.propsSchema("heart.delve"), emptyMap()).none { it.name == "tier" }, "delves have no tier")
        assertEquals(null, heart.mapDepth("heart.minor_location"))
    }

    @Test
    fun `unknown map shape fails module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", "mapShape": "star"}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("star" in e.message.orEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `landmarks have a default stress die`() {
        val stress = PropsForm.fields(heart.propsSchema("heart.landmark"), emptyMap()).single { it.name == "stress" }
        assertEquals("select", stress.kind)
        assertEquals(listOf("d4", "d6", "d8", "d10", "d12"), stress.options)
        assertTrue(heart.validateProps("heart.landmark", GameModules.mapper.readTree("""{"stress": "d6"}""")).isEmpty())
        assertFalse(heart.validateProps("heart.landmark", GameModules.mapper.readTree("""{"stress": "6"}""")).isEmpty())
    }

    @Test
    fun `heart has a GM inspiration view over characters, by calling and class`() {
        val view = heart.view("inspiration")!!
        assertEquals("heart/views/inspiration.peb", view.template)
        assertEquals(listOf("heart.pc"), view.types)
        assertEquals(listOf("calling", "class"), view.groupBy)
        val classes = PropsForm.fields(heart.propsSchema("heart.pc"), emptyMap()).single { it.name == "class" }
        assertTrue(classes.hints.getValue("Cleaver").startsWith("Opportunities to hunt"))
        assertTrue(classes.hints.keys.all { it in classes.options }, "every class text belongs to a class")
        assertEquals(null, classes.hints["Witness"])
    }

    @Test
    fun `campaign home lists heart types in game order`() {
        val order = listOf("heart.landmark", "heart.delve", "heart.pc", "core.npc", "heart.equipment", "heart.resource")
        assertEquals(order, order.sortedBy { heart.overviewRank(it) })
        assertTrue(heart.overviewRank("heart.minor_location") > heart.overviewRank("core.session"), "unlisted types come last")
        assertEquals(listOf("tier", "stress", "domains"), heart.overview("heart.landmark"))
    }

    @Test
    fun `bad overview columns or order fail module load`() {
        for ((settings, expected) in listOf(
            """"overview": ["nope"]""" to "nope",
            """"overview": ["@nope:out"]""" to "@nope:out",
        )) {
            val dir = Files.createTempDirectory("modules").toFile()
            File(dir, "bad").mkdirs()
            File(dir, "bad/module.json").writeText(
                """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", $settings}]}"""
            )
            val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
            assertTrue(expected in e.message.orEmpty(), e.message)
            dir.deleteRecursively()
        }
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText("""{"id": "bad", "name": "Bad", "overviewOrder": ["nope.place"]}""")
        assertTrue("nope.place" in assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `a view over an unknown type fails module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "views": [{"id": "v", "label": "V", "template": "x.peb", "types": ["nope.pc"]}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("nope.pc" in e.message.orEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `npcs have voice and appearance text fields`() {
        val fields = PropsForm.fields(heart.propsSchema("core.npc"), emptyMap())
        assertEquals(listOf("voice" to "text", "appearance" to "text", "dead" to "bool"), fields.map { it.name to it.kind })
        assertTrue(fields.filter { it.kind == "text" }.all { it.wide }, "each on its own full-width row")
        assertEquals("dead", heart.overviewMuted("core.npc"), "dead NPCs are greyed out on the campaign home")
        assertTrue(heart.validateProps("core.npc", GameModules.mapper.readTree("""{"voice": "Hoarse whisper", "appearance": "Ash-grey robes"}""")).isEmpty())
    }

    @Test
    fun `heart sessions pick landmarks and characters, and print a packet`() {
        val lists = heart.checklists("core.session")
        assertEquals(listOf(listOf("heart.landmark"), listOf("heart.pc")), lists.map { it.first.types })
        assertTrue(lists.all { it.second.key == "features:out" })
        val packet = heart.packet("core.session")!!
        assertEquals("features:out", packet.rel)
        assertEquals(listOf(BetweenDef("heart.delve", "connects:out")), packet.between)
        assertEquals(listOf(BetweenDef("core.npc", "located_at:out"), BetweenDef("core.faction", "located_at:out")), packet.around)
        assertEquals(listOf(BetweenDef("core.faction", "member_of:in")), packet.related)
        assertEquals("heart/print/session.peb", heart.printTemplate("core.session"))
        // A plain core campaign keeps a plain session
        val core = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("core")
        assertEquals(null, core.packet("core.session"))
    }

    @Test
    fun `npc lines on cards carry voice, appearance and death`() {
        val dead = PropsForm.fields(heart.propsSchema("core.npc"), mapOf("dead" to true)).single { it.name == "dead" }
        assertEquals("dead", dead.text)
        assertEquals(listOf("{voice}", "{appearance}", "{dead}"), heart.printSummary("core.npc")?.parts)
    }

    @Test
    fun `bad checklist or packet settings fail module load`() {
        for ((settings, expected) in listOf(
            """"checklists": [{"rel": "nope:out", "types": ["bad.place"]}]""" to "nope:out",
            """"checklists": [{"rel": "near:out", "types": ["nope.type"]}]""" to "nope.type",
            """"packet": {"rel": "near:out", "between": [{"type": "nope.type", "rel": "near:out"}]}""" to "nope.type",
        )) {
            val dir = Files.createTempDirectory("modules").toFile()
            File(dir, "bad").mkdirs()
            File(dir, "bad/module.json").writeText(
                """{"id": "bad", "name": "Bad",
                    "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", $settings}],
                    "edgeTypes": [{"id": "near", "label": "near", "inverseLabel": "near of", "from": ["bad.place"], "to": ["bad.place"]}]}"""
            )
            val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
            assertTrue(expected in e.message.orEmpty(), e.message)
            dir.deleteRecursively()
        }
    }

    @Test
    fun `plural labels default to label plus s`() {
        assertEquals(listOf("Landmarks", "Delves", "Characters", "Minor locations", "NPCs", "Equipment"),
            listOf("heart.landmark", "heart.delve", "heart.pc", "heart.minor_location", "core.npc", "heart.equipment").map { heart.plural(it) })
    }

    @Test
    fun `what a place has here is listed by type`() {
        assertEquals("NPCs", heart.linkGroupLabel("located_at", Direction.IN, "core.npc"))
        assertEquals("Resources", heart.linkGroupLabel("located_at", Direction.IN, "heart.resource"))
        assertEquals("Equipment", heart.linkGroupLabel("located_at", Direction.IN, "heart.equipment"))
        assertEquals("is located at", heart.linkGroupLabel("located_at", Direction.OUT, "heart.landmark"), "the NPC's own side keeps its label")
        assertEquals("reached via", heart.linkGroupLabel("connects", Direction.IN, "heart.delve"), "other links keep their label")
        // Characters before NPCs before equipment before resources, as on the campaign home
        val order = listOf("heart.resource", "core.npc", "heart.equipment", "heart.pc").sortedBy { heart.linkGroupRank("located_at", it).second }
        assertEquals(listOf("heart.pc", "core.npc", "heart.equipment", "heart.resource"), order)
    }

    @Test
    fun `invalid quick-create rel fails module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "nodeTypes": [
                 {"id": "bad.place", "label": "Place", "codePrefix": "P",
                  "quickCreate": [{"type": "bad.place", "rel": "nope:in"}]}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("nope:in" in e.message.orEmpty())
        dir.deleteRecursively()
    }
}
