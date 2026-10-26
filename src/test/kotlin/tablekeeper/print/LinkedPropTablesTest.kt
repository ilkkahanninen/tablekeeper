package tablekeeper.print

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class LinkedPropTablesTest {
    private val ghorryn = UUID.randomUUID()
    private val terminus = UUID.randomUUID()
    private fun source(id: UUID, title: String, vararg values: String) = LinkedPropSource(id, "heart.landmark", "L1", title, values.toList())

    @Test
    fun `props through the same link become one table, a row per linked node`() {
        val tables = linkedPropTables(listOf(
            LinkedPropValue("tier", "Tier", "connects", listOf(source(ghorryn, "Ghorryn", "Tier 2"), source(terminus, "Päätepysäkki", "Tier 3"))),
            LinkedPropValue("domains", "Domains", "connects", listOf(source(ghorryn, "Ghorryn", "Occult", "Warren"), source(terminus, "Päätepysäkki"))),
        ))
        val table = tables.single()
        assertEquals(listOf("Tier", "Domains"), table.columns)
        assertEquals(listOf("Ghorryn" to listOf("Tier 2", "Occult, Warren"), "Päätepysäkki" to listOf("Tier 3", "")),
            table.rows.map { it.title to it.cells }, "a landmark without domains gets an empty cell")
    }

    @Test
    fun `no linked nodes, no table`() {
        assertEquals(emptyList(), linkedPropTables(listOf(LinkedPropValue("tier", "Tier", "connects", emptyList()))))
    }
}
