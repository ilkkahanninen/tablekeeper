package tablekeeper.web

import tablekeeper.modules.GameModules
import tablekeeper.modules.PropsForm
import kotlin.test.Test
import kotlin.test.assertEquals

class OverviewTest {
    private val heart = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("heart")

    @Test
    fun `columns are the type's overview props and links, then its link slots`() {
        assertEquals(listOf("Tier", "Default stress", "Domains"), Overview.columns(heart, "heart.landmark").map { it.label })
        assertEquals(listOf("Connects", "Resistance"), Overview.columns(heart, "heart.delve").map { it.label })
        assertEquals(listOf("Die", "Tags", "Location / carried by"), Overview.columns(heart, "heart.resource").map { it.label })
        assertEquals(listOf("Voice", "Appearance", "Dead", "Location"), Overview.columns(heart, "core.npc").map { it.label })
    }

    @Test
    fun `cells show display text, linked titles, and nothing for hidden props`() {
        val cols = Overview.columns(heart, "heart.equipment")
        fun fields(props: Map<String, Any?>) = PropsForm.fields(heart.propsSchema("heart.equipment"), props).associateBy { it.name }
        val kill = Overview.cells(cols, fields(mapOf("category" to "Kill", "quality" to "d8", "tags" to listOf("Brutal", "Reload")))) {
            if (it.edgeType == "carries") listOf("Ash") else emptyList()
        }
        assertEquals(listOf("Kill", "d8 Professional", "Brutal, Reload", "Ash"), kill)
        // Quality is hidden for Miscellaneous, so even a stored value doesn't show
        val misc = Overview.cells(cols, fields(mapOf("category" to "Miscellaneous", "quality" to "d8"))) { emptyList() }
        assertEquals(listOf("Miscellaneous", "", "", ""), misc)
    }

    @Test
    fun `sessions are listed by date, undated last`() {
        data class S(val title: String, val date: String?)
        val sessions = listOf(S("Session 3", "2026-11-20"), S("Prep", null), S("Session 1", "2026-10-30"), S("Session 2", "2026-11-06"))
        val props = { s: S -> mapOf("date" to s.date) }
        assertEquals(listOf("Session 1", "Session 2", "Session 3", "Prep"),
            Overview.sorted(sessions, heart.overviewSort("core.session"), props) { it.title }.map { it.title })
        assertEquals(listOf("Session 3", "Session 2", "Session 1", "Prep"),
            Overview.sorted(sessions, "date" to true, props) { it.title }.map { it.title }, "-date: newest first")
    }
}
