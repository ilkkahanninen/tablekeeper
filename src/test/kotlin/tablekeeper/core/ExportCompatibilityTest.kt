package tablekeeper.core

import com.fasterxml.jackson.module.kotlin.readValue
import tablekeeper.modules.GameModules
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every export file version ever written still imports. `src/test/resources/exports` keeps an export made by each
 * version (`v<version>-<module>.json`): when [CampaignExport.VERSION] is raised, save one from the new version there
 * (Export on a campaign page) and leave the older ones as they are.
 */
class ExportCompatibilityTest {
    private val modules = GameModules.load(System.getProperty("modulesDir") ?: "modules")
    private val files = File(System.getProperty("exportsDir") ?: "src/test/resources/exports")
        .listFiles { f -> f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
    private fun versionOf(f: File) = f.name.removePrefix("v").substringBefore("-").toInt()
    private fun read(name: String) = CampaignImport.parse(files.single { it.name == name }.readBytes())

    /** The newest Flyway migration when the export version was last checked; see the test below. */
    private val checkedMigration = 7

    @Test
    fun `every version has a saved export and an upgrade step`() {
        (1..CampaignExport.VERSION).forEach { v -> assertTrue(files.any { versionOf(it) == v }, "No saved export of version $v in exports/") }
        assertEquals(emptyList(), ExportUpgrades.missingSteps(), "Versions without an upgrade step")
    }

    @Test
    fun `a new data migration is checked against the export version`() {
        val newest = File(javaClass.getResource("/db/migration")!!.toURI()).list()!!
            .maxOf { it.removePrefix("V").substringBefore("__").toInt() }
        assertEquals(checkedMigration, newest,
            "V$newest is new. If it rewrites node, edge or table data, raise CampaignExport.VERSION with a step in " +
                "ExportUpgrades and a saved export; then set checkedMigration to $newest.")
    }

    @Test
    fun `every saved export imports as the current version with valid props`() {
        files.forEach { f ->
            val export = CampaignImport.parse(f.readBytes())
            val m = modules.get(export.campaign.module)
            assertEquals(CampaignExport.VERSION, export.version, f.name)
            export.nodes.forEach { n ->
                assertTrue(m.type(n.type) != null, "${f.name}: unknown node type ${n.type}")
                assertEquals(emptyList(), m.validateProps(n.type, n.props ?: Json.mapper.createObjectNode()), "${f.name}: ${n.code} props")
            }
            export.edges.forEach { e -> assertTrue(m.edgeType(e.type) != null, "${f.name}: unknown edge type ${e.type}") }
        }
    }

    @Test
    fun `a current export imports unchanged`() {
        files.filter { versionOf(it) == CampaignExport.VERSION }.forEach { f ->
            assertEquals(Json.mapper.readValue<CampaignExport>(f.readBytes()), CampaignImport.parse(f.readBytes()), f.name)
        }
    }

    @Test
    fun `version 1 Heart adversaries get the fields of V6 and V7`() {
        val nodes = read("v1-heart.json").nodes.associateBy { it.code }
        // Before V6: names became the title of one still titled "New …"; resources and equipment went to the notes
        val hound = nodes.getValue("A1")
        assertEquals("Hound of the Derelict", hound.title)
        assertEquals("Hunts by sound.\n\nResources: Bottled Echo\n\nEquipment: Rust Knife", hound.body)
        assertEquals("Starved, many-eyed", hound.props!!["descriptor"].asText())
        assertEquals(listOf("descriptor", "difficulty", "domains", "motivation", "protection", "resistance"), hound.props!!.fieldNames().asSequence().sorted().toList())
        // A name next to a title of its own starts the notes; one the same as the title just goes
        assertEquals("The Warden" to "Name: Old Warden\n\nGuards the lift.", nodes.getValue("A2").let { it.title to it.body })
        assertEquals("Gutter Saint" to "", nodes.getValue("A3").let { it.title to it.body })
    }
}
