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
        assertEquals(listOf("Die", "Tags", "Location"), Overview.columns(heart, "heart.resource").map { it.label })
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
}
