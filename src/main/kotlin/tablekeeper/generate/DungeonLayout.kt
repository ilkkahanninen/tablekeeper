package tablekeeper.generate

import kotlin.random.Random

/**
 * Passages between a dungeon's rooms (generator layout "dungeon"). Room 0 is the entrance. The rooms grow as a tree from
 * it, each joining a room with fewer than three passages, usually one of the last few added, so the map runs in
 * corridors and side branches rather than a star; then one or two loops join rooms a few steps apart, so not every
 * way ends in a dead end. Always connected. Pure: the same [random] gives the same dungeon.
 */
object DungeonLayout {
    /** The entrance's most passages: a way in, and maybe a second. */
    const val ENTRANCE_MAX = 2
    /** Most passages a room gets while the tree grows; a loop may add one more. */
    const val TREE_MAX = 3
    const val MAX = 4

    /** Pairs of room indices (0 until [n]) to link. */
    fun connect(n: Int, random: Random): List<Pair<Int, Int>> {
        if (n < 2) return emptyList()
        val adj = List(n) { mutableSetOf<Int>() }
        val links = mutableListOf<Pair<Int, Int>>()
        fun join(a: Int, b: Int) { adj[a] += b; adj[b] += a; links += a to b }
        fun room(i: Int) = if (i == 0) ENTRANCE_MAX else MAX

        for (i in 1 until n) {
            val open = (0 until i).filter { adj[it].size < minOf(room(it), TREE_MAX) }.ifEmpty { (0 until i).toList() }
            // Usually continue from one of the last three rooms: corridors, with a branch now and then
            val recent = open.filter { it >= i - 3 }
            join(if (recent.isNotEmpty() && random.nextDouble() < 0.6) recent.random(random) else open.random(random), i)
        }

        repeat(if (n >= 5) 1 + random.nextInt(2) else 0) {
            val candidates = (0 until n).flatMap { a -> (a + 1 until n).map { a to it } }.filter { (a, b) ->
                b !in adj[a] && adj[a].size < room(a) && adj[b].size < room(b) && steps(adj, a, b) in 3..5
            }
            candidates.randomOrNull(random)?.let { (a, b) -> join(a, b) }
        }
        return links
    }

    /** Fewest passages from [a] to [b]. */
    private fun steps(adj: List<Set<Int>>, a: Int, b: Int): Int {
        val dist = mutableMapOf(a to 0)
        val queue = ArrayDeque(listOf(a))
        while (queue.isNotEmpty()) {
            val at = queue.removeFirst()
            if (at == b) return dist.getValue(at)
            adj[at].filter { it !in dist }.forEach { dist[it] = dist.getValue(at) + 1; queue += it }
        }
        return Int.MAX_VALUE
    }
}
