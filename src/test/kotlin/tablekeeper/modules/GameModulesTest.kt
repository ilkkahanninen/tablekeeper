package tablekeeper.modules

import com.fasterxml.jackson.databind.node.ObjectNode
import tablekeeper.core.CampaignGraph
import tablekeeper.core.Direction
import tablekeeper.core.EdgeRef
import tablekeeper.core.Json
import tablekeeper.core.Link
import tablekeeper.core.Node
import tablekeeper.core.NodeRef
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GameModulesTest {
    private val modulesDir = System.getProperty("modulesDir") ?: "modules"
    private val heart = GameModules.load(modulesDir).get("heart")

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
        val bad = Json.mapper.readTree("""{"tier": 1, "domains": ["Moon"]}""")
        assertTrue(heart.validateProps("heart.landmark", bad).size >= 2)
        val ok = Json.mapper.readTree("""{"tier": "Tier 1", "domains": ["Occult", "Desolate"]}""")
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
            listOf("heart.delve" to "reached via", "heart.minor_location" to "minor locations", "core.npc" to "has here", "heart.adversary" to "has here", "heart.resource" to "has here", "heart.equipment" to "has here", "core.fact" to "facts"),
            opts.map { it.type.id to it.option.label },
        )
        assertTrue(opts.all { it.option.direction == Direction.IN })
    }

    @Test
    fun `delve quick-creates the landmark it connects to, npc, resource, equipment and fact`() {
        val opts = heart.quickCreate("heart.delve")
        assertEquals(
            listOf("heart.landmark" to "connects", "core.npc" to "has here", "heart.adversary" to "has here", "heart.resource" to "has here", "heart.equipment" to "has here", "core.fact" to "facts"),
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
        assertTrue(heart.validateProps("heart.resource", Json.mapper.readTree("""{"tags": ["Fragile", "Taboo"]}""")).isEmpty())
        assertFalse(heart.validateProps("heart.resource", Json.mapper.readTree("""{"tags": ["Shiny"]}""")).isEmpty())
    }

    @Test
    fun `equipment is an item, placed at a landmark or delve, or carried`() {
        assertTrue(heart.isA("heart.equipment", "core.item"))
        assertTrue("heart.equipment" in heart.nodeTypes.map { it.id })
        val slot = heart.linkSlots("heart.equipment").single()
        assertEquals("Location / carried by", slot.label)
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
        assertTrue(heart.validateProps("heart.equipment", Json.mapper.readTree("""{"tags": ["Brutal", "Limited X"]}""")).isEmpty())
        assertEquals(listOf("{die.value}", "{tags}"), heart.printSummary("heart.resource")?.parts)
        assertTrue(heart.validateProps("heart.equipment", Json.mapper.readTree("""{"category": "Kill", "quality": "d10"}""")).isEmpty())
        assertFalse(heart.validateProps("heart.equipment", Json.mapper.readTree("""{"quality": "Exotic"}""")).isEmpty())
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
        assertFalse(heart.validateProps("heart.equipment", Json.mapper.readTree("""{"limitedUses": 0}""")).isEmpty())
    }

    @Test
    fun `characters and npcs quick-create carried resources, equipment and fallouts`() {
        for (type in listOf("core.npc", "heart.pc")) {
            assertEquals(
                listOf("heart.resource" to "carries", "heart.equipment" to "carries", "heart.fallout" to "suffers"),
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
        assertTrue(heart.validateProps("heart.pc", Json.mapper.readTree(ok)).isEmpty())
        assertFalse(heart.validateProps("heart.pc", Json.mapper.readTree("""{"beat3": "x"}""")).isEmpty())
        assertFalse(heart.validateProps("heart.pc", Json.mapper.readTree("""{"beat1Type": "Huge"}""")).isEmpty())
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
            listOf("core.npc", "heart.adversary", "heart.resource", "heart.equipment", "core.fact"),
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
        assertEquals(MapDepth(4.0, "Tier 4"), heart.depthOf("heart.landmark", mapOf("tier" to "Tier 4")), "deepest of the numbered tiers")
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
            """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", "campaignMap": {"shape": "star"}}]}"""
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
        assertTrue(heart.validateProps("heart.landmark", Json.mapper.readTree("""{"stress": "d6"}""")).isEmpty())
        assertFalse(heart.validateProps("heart.landmark", Json.mapper.readTree("""{"stress": "6"}""")).isEmpty())
    }

    @Test
    fun `heart has a GM inspiration view over characters, by calling and class`() {
        val view = heart.view("inspiration")!!
        assertEquals("heart/views/inspiration.peb", view.template)
        assertEquals(listOf("heart.pc"), view.types)
        assertEquals(listOf("calling", "class"), view.groupBy)
        val classes = PropsForm.fields(heart.propsSchema("heart.pc"), emptyMap()).single { it.name == "class" }
        assertTrue(classes.notes.getValue("Cleaver").startsWith("Opportunities to hunt"))
        assertTrue(classes.notes.keys.all { it in classes.options }, "every class text belongs to a class")
        assertEquals(null, classes.notes["Witness"])
        assertTrue(classes.hints.isEmpty(), "inspiration texts aren't class descriptions: no hover hints, nothing on cards")
    }

    @Test
    fun `campaign home lists heart types in game order`() {
        val order = listOf("core.session", "heart.landmark", "heart.delve", "heart.pc", "core.npc", "heart.equipment", "heart.resource")
        assertEquals(order, order.sortedBy { heart.overviewRank(it) })
        assertTrue(heart.overviewRank("heart.minor_location") > heart.overviewRank("core.fact"), "unlisted types come last")
        assertEquals(listOf("tier", "stress", "domains"), heart.overview("heart.landmark"))
    }

    @Test
    fun `bad overview columns or order fail module load`() {
        for ((settings, expected) in listOf(
            """"overview": {"columns": ["nope"]}""" to "nope",
            """"overview": {"columns": ["@nope:out"]}""" to "@nope:out",
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
        assertTrue(heart.validateProps("core.npc", Json.mapper.readTree("""{"voice": "Hoarse whisper", "appearance": "Ash-grey robes"}""")).isEmpty())
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
        assertEquals(null, heart.printTemplate("core.session"), "the shared session packet")
        assertEquals("heart/print/_inspiration_packet.peb", heart.view("inspiration")!!.print)
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
        assertEquals("Is located at", heart.linkGroupLabel("located_at", Direction.OUT, "heart.landmark"), "the NPC's own side keeps its label, as a heading")
        assertEquals("Reached via", heart.linkGroupLabel("connects", Direction.IN, "heart.delve"), "other links keep their label")
        // Characters before NPCs before equipment before resources, as on the campaign home
        val order = listOf("heart.resource", "core.npc", "heart.equipment", "heart.pc").sortedBy { heart.linkGroupRank("located_at", it).second }
        assertEquals(listOf("heart.pc", "core.npc", "heart.equipment", "heart.resource"), order)
    }

    @Test
    fun `characters are shown with their player`() {
        assertEquals("Thonal (Jori)", heart.displayTitle("heart.pc", "Thonal", mapOf("player" to "Jori")))
        assertEquals("Thonal", heart.displayTitle("heart.pc", "Thonal", emptyMap()), "no player, no brackets")
        assertEquals("Silvia", heart.displayTitle("core.npc", "Silvia", mapOf("voice" to "Hoarse")), "other types show their title")
    }

    @Test
    fun `characters and npcs suffer fallouts`() {
        val fields = PropsForm.fields(heart.propsSchema("heart.fallout"), emptyMap()).associateBy { it.name }
        assertEquals(listOf("Minor", "Major", "Zenith"), fields.getValue("type").options)
        assertEquals(listOf("Blood", "Echo", "Mind", "Fortune", "Supplies"), fields.getValue("resistance").options)
        assertEquals("textarea", fields.getValue("description").kind)
        // Shared by several: characters ticked on the fallout's page; NPCs (or anyone) through the Link box
        assertTrue(heart.linkSlots("heart.fallout").isEmpty(), "not one-of-many")
        val (list, option) = heart.checklists("heart.fallout").single()
        assertEquals(listOf("heart.pc") to "suffers:in", list.types to option.key)
        assertTrue(listOf("heart.pc", "core.npc").all { t -> heart.acceptsOther(option, t) })
        for (type in listOf("heart.pc", "core.npc")) assertTrue(heart.quickCreate(type).any { it.type.id == "heart.fallout" }, type)
        assertEquals(listOf("suffers:out"), heart.view("inspiration")!!.links)
        assertEquals("Fallouts", heart.plural("heart.fallout"))
        assertEquals("Fallouts", heart.linkGroupLabel("suffers", Direction.OUT, "heart.fallout"), "the character's list heading")
    }

    @Test
    fun `a view link that isn't a link option fails module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.pc", "label": "PC", "codePrefix": "P"}],
                "views": [{"id": "v", "label": "V", "template": "x.peb", "types": ["bad.pc"], "links": ["nope:out"]}]}"""
        )
        assertTrue("nope:out" in assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty())
        dir.deleteRecursively()
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

    @Test
    fun `fields a random table can fill are titles and text fields`() {
        val options = heart.fillableFields().associate { it.key to it.label }
        assertEquals("NPC: Title", options["core.npc:title"])
        assertEquals("NPC: Voice", options["core.npc:voice"])
        assertEquals("NPC: Appearance", options["core.npc:appearance"])
        assertEquals("Landmark: Title", options["heart.landmark:title"])
        assertEquals("Landmark: Haunts", options["heart.landmark:haunts"])
        assertTrue("heart.pc:beat1" in options, "one-line fields inside rows count too")
        // Dropdowns, checkboxes and numbers can't take free text
        assertFalse("heart.landmark:tier" in options)
        assertFalse("heart.landmark:domains" in options)
        assertFalse("core.npc:dead" in options)
        assertFalse("heart.delve:resistance" in options)
    }

    @Test
    fun `a type patch replaces only the settings it gives`() {
        val npc = heart.type("core.npc")!!
        val patched = Overlay.patch(npc, Json.mapper.readTree("""{"id": "other", "plural": "Folk", "overview": {"sort": "-name"}}""") as ObjectNode)
        assertEquals("Folk", patched.plural)
        assertEquals("-name", patched.overview?.sort)
        assertEquals(npc.overview?.columns, patched.overview?.columns, "a patch of one overview setting keeps the others")
        assertEquals(npc.copy(plural = "Folk", overview = npc.overview!!.copy(sort = "-name")), patched, "id, props and every other setting stay")
    }

    @Test
    fun `summary fills the type's print summary from visible fields`() {
        assertEquals("Kill d8 · Brutal", heart.summary("heart.equipment", mapOf("category" to "Kill", "quality" to "d8", "tags" to listOf("Brutal"))))
        // Quality is hidden on Miscellaneous equipment, so it drops out
        assertEquals("Miscellaneous", heart.summary("heart.equipment", mapOf("category" to "Miscellaneous", "quality" to "d8")))
        assertEquals("", heart.summary("heart.landmark", mapOf("tier" to "Tier 1")), "no printSummary")
    }

    @Test
    fun `a minor location's parent is its landmark`() {
        val c = UUID.randomUUID()
        fun node(type: String, title: String) = Node(UUID.randomUUID(), c, type, title, title, "", emptyMap(), "gm")
        val market = node("heart.landmark", "Red Market")
        val row = node("heart.minor_location", "Butcher's Row")
        val graph = CampaignGraph(listOf(market, row), listOf(EdgeRef(row.id, market.id, "part_of")))
        assertEquals(market, heart.parent(graph, row))
        assertNull(heart.parent(graph, market), "landmarks have no context link")
    }

    @Test
    fun `links are grouped under headings in module order`() {
        fun ref(type: String, title: String) = NodeRef(UUID.randomUUID(), type, title, title, "gm")
        fun link(type: String, dir: Direction, other: NodeRef) = Link(UUID.randomUUID(), type, dir, other)
        val links = listOf(
            link("located_at", Direction.IN, ref("heart.resource", "Lamp")),
            link("located_at", Direction.IN, ref("core.npc", "Silvia")),
            link("connects", Direction.IN, ref("heart.delve", "Tunnel")),
        )
        val groups = heart.groupLinks("core.npc", links)
        assertEquals(listOf("Silvia"), groups["NPCs"]?.map { it.other.title })
        assertEquals(listOf("Lamp"), groups["Resources"]?.map { it.other.title })
        assertTrue(groups.keys.indexOf("NPCs") < groups.keys.indexOf("Resources"), "types in campaign-home order")
    }

    @Test
    fun `sessions debrief characters, npcs and items, and recap the previous session`() {
        val (pcs, npcs, items) = heart.debrief("core.session")
        assertEquals(listOf("heart.pc"), pcs.types)
        assertTrue(pcs.done && "beat1" in pcs.fields)
        assertEquals("heart.fallout", pcs.links.single().create)
        assertTrue(pcs.links.single().remove, "healed fallouts can be removed")
        assertEquals(listOf("dead"), npcs.fields)
        assertTrue(npcs.slots && items.slots)
        assertEquals("recap", heart.packet("core.session")?.recap)
    }

    @Test
    fun `a debrief naming a missing field or an impossible create fails to load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "core").mkdirs()
        File(modulesDir, "core/module.json").copyTo(File(dir, "core/module.json"))
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText("""{"id": "bad", "name": "Bad", "extends": ["core"], "patchTypes": [{"id": "core.session",
            "debrief": [{"types": ["core.npc"], "fields": ["mood"], "links": [{"rel": "carries:out", "create": "core.item"}]}]}]}""")
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("debrief field 'mood'" in e.message.orEmpty(), e.message)
        assertTrue("debrief create 'core.item'" in e.message.orEmpty(), e.message)
        dir.deleteRecursively()
    }

    @Test
    fun `a partial save changes only the posted fields`() {
        val stored = mapOf("player" to "Mia", "beat1" to "Find the ledger", "beat2" to "Burn it")
        val (props, errors) = PropsForm.parse(heart.propsSchema("heart.pc"), mapOf("prop.beat1" to listOf("Sell the ledger")), setOf("beat1"), stored)
        assertTrue(errors.isEmpty())
        assertEquals(mapOf("player" to "Mia", "beat1" to "Sell the ledger", "beat2" to "Burn it"), Json.mapper.convertValue(props, Map::class.java))
        val (cleared) = PropsForm.parse(heart.propsSchema("heart.pc"), emptyMap(), setOf("beat1Type", "beat1"), stored)
        assertEquals(mapOf("player" to "Mia", "beat2" to "Burn it"), Json.mapper.convertValue(cleared, Map::class.java), "Done empties a beat")
    }

    @Test
    fun `delves have a resistance track, a default stress die and a list of events`() {
        val fields = PropsForm.fields(heart.propsSchema("heart.delve"), mapOf("resistance" to 8, "events" to "Floor gives way,\nwater rushes in\n\n \n  The Hunger notices  \n")).associateBy { it.name }
        assertTrue(fields.getValue("resistance").track)
        assertEquals("list", fields.getValue("events").kind)
        assertEquals(listOf("Floor gives way,\nwater rushes in", "The Hunger notices"), fields.getValue("events").items, "items split at blank lines")
        assertEquals(listOf("d4", "d6", "d8", "d10", "d12"), fields.getValue("stress").options)
        assertEquals(mapOf("d4" to "Tier 0-1", "d6" to "Tier 2-3", "d8" to "Tier 4"), fields.getValue("stress").hints)
        assertEquals(fields.getValue("stress").hints, PropsForm.fields(heart.propsSchema("heart.landmark"), emptyMap()).single { it.name == "stress" }.hints)
        assertEquals(listOf("tier", "domains"), heart.linkedProps("heart.delve").map { it.prop }, "stress is the delve's own")
        assertTrue(heart.fillableFields().any { it.key == "heart.delve:events" }, "random tables can add events")
    }

    @Test
    fun `every type has an icon that is in the bundled sprite`() {
        val sprite = File("src/main/resources/static/icons.svg").readText()
        assertEquals("castle", heart.icon("heart.landmark"))
        assertEquals("map-pin", heart.icon("heart.minor_location"), "inherited from core.place")
        assertEquals("user", heart.icon("heart.pc"), "inherited from core.pc")
        for (icon in ICONS) assertTrue("<symbol id=\"$icon\"" in sprite, "$icon is in icons.svg")
        for (t in heart.nodeTypes) assertTrue(heart.icon(t.id) in ICONS, "${t.id}: ${heart.icon(t.id)}")
    }

    @Test
    fun `an unknown icon fails to load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText("""{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.x", "label": "X", "codePrefix": "X", "icon": "unicorn"}]}""")
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("icon 'unicorn'" in e.message.orEmpty(), e.message)
        dir.deleteRecursively()
    }

    @Test
    fun `a landmark lists Reached via, Resources and Equipment first and Featured in last`() {
        fun ref(type: String, title: String) = NodeRef(UUID.randomUUID(), type, title, title, "gm")
        fun link(type: String, other: NodeRef) = Link(UUID.randomUUID(), type, Direction.IN, other)
        val links = listOf(
            link("features", ref("core.session", "Night")),
            link("located_at", ref("core.npc", "Silvia")),
            link("located_at", ref("heart.equipment", "Saw")),
            link("part_of", ref("heart.minor_location", "Alley")),
            link("located_at", ref("heart.resource", "Lamp")),
            link("connects", ref("heart.delve", "Tunnel")),
        )
        assertEquals(listOf("Reached via", "Resources", "Equipment", "NPCs", "Minor locations", "Featured in"),
            heart.groupLinks("heart.landmark", links).keys.toList())
    }

    @Test
    fun `a linkOrder heading the type can't have fails to load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "core").mkdirs()
        File(modulesDir, "core/module.json").copyTo(File(dir, "core/module.json"))
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText("""{"id": "bad", "name": "Bad", "extends": ["core"], "patchTypes": [{"id": "core.place", "linkOrder": ["Reched via"]}]}""")
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("linkOrder 'Reched via'" in e.message.orEmpty(), e.message)
        dir.deleteRecursively()
    }

    private val morkborg = GameModules.load(modulesDir).get("morkborg")

    @Test
    fun `morkborg has locations, dungeons with rooms, characters, creatures, items and miseries`() {
        val ids = morkborg.nodeTypes.map { it.id }.toSet()
        assertEquals(setOf("mb.location", "mb.dungeon", "mb.room", "mb.pc", "core.npc", "mb.item", "mb.misery", "core.faction", "core.fact", "core.session"), ids)
        assertTrue(morkborg.isA("mb.dungeon", "core.place"))
        assertTrue(morkborg.isA("mb.room", "core.place"))
        // The rules don't tell NPCs from creatures: core's NPC is the Creature, with a stat block
        assertEquals("Creature", morkborg.label("core.npc"))
        val stats = PropsForm.fields(morkborg.propsSchema("core.npc"), emptyMap()).map { it.name }
        assertTrue(listOf("hp", "morale", "armor", "attack", "dead").all { it in stats }, stats.toString())
        assertEquals("HP 7 · Morale 8 · No armor · Poisoned knife d4",
            morkborg.summary("core.npc", mapOf("hp" to 7, "morale" to "8", "armor" to "No armor", "attack" to "Poisoned knife d4")))
    }

    @Test
    fun `a morkborg room's parent is its dungeon`() {
        val c = UUID.randomUUID()
        fun node(type: String, title: String) = Node(UUID.randomUUID(), c, type, title, title, "", emptyMap(), "gm")
        val tomb = node("mb.dungeon", "Plague crypt")
        val hall = node("mb.room", "Hall of bones")
        val graph = CampaignGraph(listOf(tomb, hall), listOf(EdgeRef(hall.id, tomb.id, "room_in")))
        assertEquals(tomb, morkborg.parent(graph, hall))
    }

    @Test
    fun `morkborg sessions print a packet, debrief and record miseries`() {
        assertEquals(listOf(listOf("mb.location", "mb.dungeon"), listOf("mb.pc")), morkborg.checklists("core.session").map { it.first.types })
        assertNull(morkborg.printTemplate("core.session"), "the shared session packet")
        assertEquals(3, morkborg.packet("core.session")!!.columns)
        assertEquals(listOf(listOf("mb.pc"), listOf("core.npc"), listOf("core.item")), morkborg.debrief("core.session").map { it.types })
        val verse = morkborg.propsSchema("mb.misery")!!["properties"]["verse"]
        assertEquals(37, verse["enum"].size(), "1:1 to 6:6, then 7:7")
        assertEquals(37, verse["x-enumDescriptions"].size())
    }

    @Test
    fun `a morkborg dungeon has a map of its rooms, heart types have none`() {
        val (option, shape) = morkborg.nodeMap("mb.dungeon")!!
        assertEquals("room_in:in", option.key)
        assertEquals("circle", shape)
        assertNull(morkborg.nodeMap("mb.location"))
        assertTrue(heart.nodeTypes.all { heart.nodeMap(it.id) == null })
        assertNull(morkborg.mapShape("mb.room"), "rooms stay off the campaign map")
    }

    @Test
    fun `a dungeon map draws its rooms and the passages between them`() {
        val c = UUID.randomUUID()
        fun node(type: String, title: String) = Node(UUID.randomUUID(), c, type, title, title, "", emptyMap(), "gm")
        val crypt = node("mb.dungeon", "Plague crypt")
        val other = node("mb.dungeon", "Death church")
        val hall = node("mb.room", "Hall"); val ossuary = node("mb.room", "Ossuary"); val nave = node("mb.room", "Nave")
        val altar = node("mb.room", "Altar")   // in the other dungeon
        val graph = CampaignGraph(listOf(crypt, other, hall, ossuary, nave, altar), listOf(
            EdgeRef(hall.id, crypt.id, "room_in"), EdgeRef(ossuary.id, crypt.id, "room_in"), EdgeRef(nave.id, crypt.id, "room_in"),
            EdgeRef(altar.id, other.id, "room_in"),
            EdgeRef(hall.id, ossuary.id, "connects"), EdgeRef(nave.id, ossuary.id, "connects"), EdgeRef(nave.id, altar.id, "connects"),
        ))
        val map = tablekeeper.map.MapService.nodeView(graph, morkborg, crypt)!!
        assertEquals(setOf("Hall", "Ossuary", "Nave"), map.places.map { it.title }.toSet())
        assertTrue(map.places.all { it.shape == "circle" })
        assertEquals(2, map.lines.size, "Hall–Ossuary and Ossuary–Nave; the passage out to another dungeon's room isn't drawn")
        assertNull(tablekeeper.map.MapService.nodeView(graph, morkborg, hall), "rooms have no map of their own")
        // One two-way link: a room lists the rooms it connects to whichever end it was made from
        assertEquals(listOf("connects:out"), morkborg.linkOptions("mb.room").filter { it.edgeType == "connects" }.map { it.key })
    }

    @Test
    fun `a map over an unknown rel or with no shape fails module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", "map": {"places": "rooms:in", "shape": "none"}}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }
        assertTrue("map places 'rooms:in' is not a link option" in e.message.orEmpty(), e.message)
        assertTrue("map shape 'none'" in e.message.orEmpty(), e.message)
        dir.deleteRecursively()
    }

    @Test
    fun `morkborg ships its dungeon tables and a dungeon generator`() {
        assertEquals(listOf("Dungeon names", "Who lurks", "Room features", "Traps", "Bestiary"), morkborg.tables.map { it.name })
        val bestiary = morkborg.tables.single { it.name == "Bestiary" }
        assertEquals(12, bestiary.items.size)
        assertTrue(bestiary.items.all { it.split("|").size == 7 }, "name | HP | morale | armor | attack | special | worth")
        assertTrue(morkborg.tables.first().mix, "names mix the book's two columns")
        assertEquals(listOf("dungeon", "rooms", "creatures"), morkborg.generator("dungeon")!!.nodes.map { it.id })
        assertEquals("creature", morkborg.generator("dungeon")!!.nodes.last().generator)
        assertEquals("entrance", morkborg.nodeMap("mb.dungeon")!!.highlight)
        assertTrue(heart.generators.isEmpty())
    }

    @Test
    fun `bad tables and generators fail module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad",
                "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P"}],
                "tables": [{"name": "Names", "items": ["A"], "fields": ["bad.place:smell"]}],
                "generators": [{"id": "g", "label": "G", "nodes": [
                  {"id": "a", "type": "bad.place", "fill": ["size"], "link": {"rel": "relates_to:out", "to": "b"}},
                  {"id": "b", "type": "bad.nope"}],
                  "connect": {"nodes": "a", "rel": "relates_to:out", "layout": "spiral"}}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty()
        listOf("field 'bad.place:smell'", "field 'size' is not a prop", "link to 'b' is not an earlier group", "type 'bad.nope' is unknown", "layout 'spiral'")
            .forEach { assertTrue(it in e, "$it in: $e") }
        dir.deleteRecursively()
    }

    @Test
    fun `bad nested generators and row columns fail module load`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad",
                "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P"}],
                "generators": [
                  {"id": "g", "label": "G", "nodes": [
                    {"id": "both", "type": "bad.place", "generator": "h"},
                    {"id": "ghost", "generator": "nope"},
                    {"id": "loop", "generator": "h"},
                    {"id": "row", "type": "bad.place", "row": {"table": "T", "columns": ["title", "hp"]}}]},
                  {"id": "h", "label": "H", "nodes": [{"id": "back", "generator": "g"}]}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty()
        listOf("both: give either a type or a generator", "ghost: generator 'nope' is unknown", "loop: generator 'h' uses 'g' in turn",
            "row: field 'hp' is not a prop").forEach { assertTrue(it in e, "$it in: $e") }
        dir.deleteRecursively()
    }

    @Test
    fun `a type inherits its parent's settings, merged, but not its own name`() {
        // Minor locations extend core.place: its map shape is replaced, its icon inherited
        assertEquals("map-pin", heart.icon("heart.minor_location"))
        assertEquals("dot", heart.mapShape("heart.minor_location"))
        assertEquals("Minor location", heart.label("heart.minor_location"))
        val base = Json.mapper.readTree("""{"overview": {"columns": ["a"], "sort": "a"}, "props": {"type": "object", "properties": {"a": {}}}, "label": "A"}""") as ObjectNode
        val over = Json.mapper.readTree("""{"overview": {"sort": "-b"}, "props": {"type": "object", "properties": {"b": {}}}}""") as ObjectNode
        val merged = Overlay.merge(base, over)
        assertEquals("""{"columns":["a"],"sort":"-b"}""", merged["overview"].toString(), "objects merge key by key")
        assertEquals(listOf("b"), merged["props"]["properties"].fieldNames().asSequence().toList(), "props replace whole")
    }

    @Test
    fun `old flat settings fail module load with where they moved`() {
        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", "mapShape": "circle", "overview": ["x"]}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty()
        assertTrue("'mapShape' is now 'campaignMap.shape'" in e, e)
        assertTrue("its list is 'overview.columns'" in e, e)
        dir.deleteRecursively()
    }

    @Test
    fun `props can be just the fields, and shared fields are used by name`() {
        // Heart's stress die and domains are shared by landmarks and delves / resources; beats and fallouts share a severity
        assertEquals(heart.propSchema("heart.landmark", "stress"), heart.propSchema("heart.delve", "stress"))
        assertEquals(heart.propSchema("heart.landmark", "domains"), heart.propSchema("heart.resource", "domains"))
        assertEquals("Beat 1", heart.propSchema("heart.pc", "beat1Type")!!["x-row"].asText(), "a use with changes")
        assertEquals(listOf("Minor", "Major", "Zenith"), heart.propSchema("heart.fallout", "type")!!["enum"].map { it.asText() })
        // The short form is wrapped as a closed object schema
        val schema = heart.propsSchema("heart.landmark")!!
        assertEquals("object", schema["type"].asText())
        assertEquals(false, schema["additionalProperties"].asBoolean())
        assertTrue(heart.validateProps("heart.landmark", Json.mapper.readTree("""{"smell": "bad"}""")).isNotEmpty())

        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText(
            """{"id": "bad", "name": "Bad", "nodeTypes": [{"id": "bad.place", "label": "Place", "codePrefix": "P", "props": {"a": "@nope", "b": {"use": "gone"}}}]}"""
        )
        val e = assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty()
        assertTrue("bad.place a: shared field 'nope' is unknown" in e && "bad.place b: shared field 'gone' is unknown" in e, e)
        dir.deleteRecursively()
    }

    @Test
    fun `heart maps are portrait, other games landscape, and only those two`() {
        assertEquals("portrait", heart.mapOrientation)
        assertEquals("landscape", morkborg.mapOrientation)
        val c = UUID.randomUUID()
        val market = Node(UUID.randomUUID(), c, "heart.landmark", "L1", "Red Market", "", mapOf("tier" to "Tier 1"), "gm")
        val view = tablekeeper.map.MapService.view(CampaignGraph(listOf(market), emptyList()), heart)
        assertTrue(view.height > view.width, "a portrait drawing: ${view.width}×${view.height}")

        val dir = Files.createTempDirectory("modules").toFile()
        File(dir, "bad").mkdirs()
        File(dir, "bad/module.json").writeText("""{"id": "bad", "name": "Bad", "mapOrientation": "diagonal"}""")
        assertTrue("mapOrientation 'diagonal'" in assertFailsWith<IllegalArgumentException> { GameModules.load(dir.path) }.message.orEmpty())
        dir.deleteRecursively()
    }

    @Test
    fun `adversaries are npcs with a stat block`() {
        assertTrue(heart.isA("heart.adversary", "core.npc"), "placed, carried things, fallouts, packets and debriefs as NPCs")
        assertEquals(listOf("descriptor", "motivation", "difficulty", "resistance", "protection", "domains", "dead"),
            PropsForm.fields(heart.propsSchema("heart.adversary"), emptyMap()).map { it.name })
        assertEquals(heart.propSchema("heart.landmark", "domains"), heart.propSchema("heart.adversary", "domains"))
        assertEquals("Risky · Resistance 12 · Protection 2",
            heart.summary("heart.adversary", mapOf("difficulty" to "Risky", "resistance" to 12, "protection" to 2)))
        assertTrue(heart.validateProps("heart.adversary", Json.mapper.readTree("""{"difficulty": "Deadly"}""")).isNotEmpty())
    }
}
