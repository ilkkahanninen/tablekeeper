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

    private val human = listOf("Aaron Idle", "Alys Pascoe", "Beatrice Flint", "Cecil Broderick", "Daphne Penrose", "Dolwyn Harris")

    @Test
    fun `mix joins one line's first name with another's surname`() {
        assertEquals("Alys Flint", RandomTables.mixWords("Alys Pascoe", "Beatrice Flint"))
        assertEquals("Mary Ann Flint", RandomTables.mixWords("Mary Ann Smith", "Beatrice Flint"))
        assertEquals("Char", RandomTables.mixWords("Char", "Brakesh"), "one-word lines are used whole")
        val random = kotlin.random.Random(1)
        val firsts = human.map { it.substringBefore(" ") }.toSet()
        val lasts = human.map { it.substringAfterLast(" ") }.toSet()
        val rolls = (1..300).map { RandomTables.roll(human, mix = true, random = random)!! }
        assertTrue(rolls.all { it.substringBefore(" ") in firsts && it.substringAfterLast(" ") in lasts })
        assertTrue(rolls.toSet().size > human.size, "more names than lines: ${rolls.toSet().size}")
    }

    @Test
    fun `rolls avoid the current value and handle tiny tables`() {
        val random = kotlin.random.Random(2)
        assertTrue((1..100).none { RandomTables.roll(listOf("A", "B"), mix = false, avoid = "A", random = random) == "A" })
        assertEquals("Only", RandomTables.roll(listOf("Only"), mix = true, avoid = "Only"))
        assertEquals(null, RandomTables.roll(emptyList(), mix = false))
        assertTrue((1..50).all { RandomTables.roll(human, mix = false, random = random) in human }, "no mix: whole lines")
    }
}
