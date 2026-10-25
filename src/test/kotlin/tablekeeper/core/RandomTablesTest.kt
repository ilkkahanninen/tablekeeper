package tablekeeper.core

import tablekeeper.modules.GameModules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RandomTablesTest {
    private val heart = GameModules.load(System.getProperty("modulesDir") ?: "modules").get("heart")

    @Test
    fun `one item per line, blank lines and surrounding spaces dropped`() {
        assertEquals(listOf("Silvia", "Old Grub", "Moth"), RandomTables.items("Silvia\n\n  Old Grub  \n\r\nMoth\n   \n"))
        assertEquals(emptyList(), RandomTables.items(""))
    }

    @Test
    fun `fields a table can fill are titles and text fields`() {
        val options = RandomTables.fieldOptions(heart).associate { it.key to it.label }
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
}
