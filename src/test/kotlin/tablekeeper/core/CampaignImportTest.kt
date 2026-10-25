package tablekeeper.core

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CampaignImportTest {
    private val a = UUID.randomUUID()
    private val b = UUID.randomUUID()
    private fun export(nodes: List<ExportedNode>, edges: List<ExportedEdge> = emptyList()) =
        CampaignExport(campaign = ExportedCampaign("Heart", "heart"), nodes = nodes, edges = edges)
    private fun node(id: UUID, code: String) = ExportedNode(id, "heart.landmark", code, "Place $code", props = Json.mapper.readTree("""{"tier": "Tier 1"}"""))

    @Test
    fun `an export reads back as the same campaign`() {
        val original = export(listOf(node(a, "L1"), node(b, "L2")), listOf(ExportedEdge(a, b, "relates_to", Json.mapper.createObjectNode())))
        val read = CampaignImport.parse(Json.mapper.writeValueAsBytes(original))
        assertEquals(original.copy(exportedAt = null), read.copy(exportedAt = null))
    }

    @Test
    fun `other files are refused with a reason`() {
        assertEquals("Not a Tablekeeper campaign file", assertFailsWith<BadRequest> { CampaignImport.parse("hello".toByteArray()) }.message)
        assertEquals("Not a Tablekeeper campaign file",
            assertFailsWith<BadRequest> { CampaignImport.parse("""{"format": "other", "campaign": {"name": "x", "module": "heart"}}""".toByteArray()) }.message)
        assertTrue("newer" in assertFailsWith<BadRequest> {
            CampaignImport.parse("""{"format": "tablekeeper-campaign", "version": 99, "campaign": {"name": "x", "module": "heart"}}""".toByteArray())
        }.message.orEmpty())
    }

    @Test
    fun `broken references and duplicate codes are problems`() {
        assertEquals(emptyList(), CampaignImport.problems(export(listOf(node(a, "L1"), node(b, "L2")), listOf(ExportedEdge(a, b, "x")))))
        assertEquals(listOf("Duplicate node codes: L1"), CampaignImport.problems(export(listOf(node(a, "L1"), node(b, "L1")))))
        assertEquals(listOf("1 links point to nodes not in the file"),
            CampaignImport.problems(export(listOf(node(a, "L1")), listOf(ExportedEdge(a, UUID.randomUUID(), "x")))))
    }

    @Test
    fun `an imported campaign gets a free name`() {
        assertEquals("Heart", CampaignImport.freeName("Heart", setOf("Other")))
        assertEquals("Heart (3)", CampaignImport.freeName("Heart", setOf("Heart", "Heart (2)")))
    }
}
