package tablekeeper.generate

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DungeonLayoutTest {
    private fun degrees(n: Int, links: List<Pair<Int, Int>>) = (0 until n).map { i -> links.count { it.first == i || it.second == i } }

    private fun connected(n: Int, links: List<Pair<Int, Int>>): Boolean {
        val seen = mutableSetOf(0)
        var grew = true
        while (grew) grew = links.filter { (a, b) -> (a in seen) != (b in seen) }.onEach { (a, b) -> seen += a; seen += b }.isNotEmpty()
        return seen.size == n
    }

    @Test
    fun `ten rooms are all reachable from the entrance, with a loop or two`() {
        repeat(200) { seed ->
            val links = DungeonLayout.connect(10, Random(seed))
            assertTrue(connected(10, links), "seed $seed: $links")
            assertTrue(links.size in 10..11, "9 tree passages plus 1-2 loops, seed $seed: ${links.size}")
            assertEquals(links.size, links.map { setOf(it.first, it.second) }.toSet().size, "no passage twice")
            assertTrue(links.none { it.first == it.second })
            val deg = degrees(10, links)
            assertTrue(deg[0] in 1..DungeonLayout.ENTRANCE_MAX, "entrance: ${deg[0]}")
            assertTrue(deg.all { it <= DungeonLayout.MAX }, "seed $seed: $deg")
        }
    }

    @Test
    fun `the same seed gives the same dungeon`() {
        assertEquals(DungeonLayout.connect(10, Random(7)), DungeonLayout.connect(10, Random(7)))
    }

    @Test
    fun `dungeons run in corridors rather than a star`() {
        // Over many dungeons, the longest way from the entrance is usually several rooms deep
        val depths = (0 until 200).map { seed ->
            val links = DungeonLayout.connect(10, Random(seed))
            val dist = mutableMapOf(0 to 0)
            var grew = true
            while (grew) grew = links.flatMap { listOf(it, it.second to it.first) }
                .filter { (a, b) -> a in dist && b !in dist }.onEach { (a, b) -> dist[b] = dist.getValue(a) + 1 }.isNotEmpty()
            dist.values.max()
        }
        assertTrue(depths.average() >= 3.5, "average depth ${depths.average()}")
    }

    @Test
    fun `tiny dungeons`() {
        assertEquals(emptyList(), DungeonLayout.connect(1, Random(1)))
        assertEquals(listOf(0 to 1), DungeonLayout.connect(2, Random(1)))
    }
}
