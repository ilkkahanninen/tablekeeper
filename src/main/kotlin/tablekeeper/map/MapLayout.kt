package tablekeeper.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic force-directed layout (Fruchterman–Reingold) for the campaign map.
 * Start positions are seeded from node ids and the iteration count is fixed, so the same
 * places and links always give the same map; it only moves when links change.
 *
 * Each connected group of places is laid out on its own and the groups are then packed side by
 * side. Without that, an unconnected place gets pushed far away and squeezes everything else
 * into a corner.
 *
 * A place with a [Item.depth] (Heart's tier) is fixed at a height set by it, deeper lower, the
 * same scale for every group; links only move it sideways. Groups without depths are instead turned so their longest spread
 * runs horizontally, and packed above the others.
 */
object MapLayout {
    /**
     * [small] nodes (e.g. minor locations) sit on short springs, so they cluster around what they link to.
     * [depth]: vertical level, higher = lower on the map; null = free to go anywhere.
     */
    data class Item(val id: String, val small: Boolean = false, val depth: Double? = null)
    data class Point(val x: Double, val y: Double)

    private const val ITERATIONS = 400
    /** Ideal distance between linked places, in layout units (only the proportions matter; [fit] scales). */
    private const val K = 100.0
    /** Vertical distance between consecutive depths. */
    private const val LEVEL = K * 1.3
    /** Minimum sideways distance between places on the same level, so their labels don't collide. */
    private const val MIN_GAP = K * 1.2
    /** Roughly half a place label's width, for keeping minor locations off other places' labels. */
    private const val LABEL_HALF_WIDTH = K * 0.75

    /** Positions inside a [width] × [height] box, keeping [margin] free on every side for labels. */
    fun layout(items: List<Item>, edges: List<Pair<String, String>>, width: Double, height: Double, margin: Double): Map<String, Point> {
        if (items.isEmpty()) return emptyMap()
        val ids = items.map { it.id }.toSet()
        val links = edges.filter { (a, b) -> a in ids && b in ids && a != b }
        val small = items.associate { it.id to it.small }
        val depth = items.mapNotNull { i -> i.depth?.let { i.id to it } }.toMap()

        val groups = components(items.map { it.id }, links)
            .map { group -> place(group, links.filter { it.first in group }, small, depth) }
            .sortedWith(compareByDescending<Map<String, Point>> { it.size }.thenBy { it.keys.min() })
        val (layered, free) = groups.partition { g -> g.keys.any { it in depth } }
        return fit(pack(layered, free, width / height), width, height, margin)
    }

    /** Connected groups, each in the input order, so results don't depend on hash ordering. */
    private fun components(ids: List<String>, links: List<Pair<String, String>>): List<List<String>> {
        val parent = ids.associateWith { it }.toMutableMap()
        fun root(x: String): String = if (parent.getValue(x) == x) x else root(parent.getValue(x)).also { parent[x] = it }
        links.forEach { (a, b) -> parent[root(a)] = root(b) }
        return ids.groupBy(::root).values.toList()
    }

    /**
     * Force layout of one connected group, centred horizontally. With depths, y is absolute (depth × [LEVEL]);
     * without, the group is centred and turned to lie horizontally.
     */
    private fun place(
        ids: List<String>, links: List<Pair<String, String>>, small: Map<String, Boolean>, depth: Map<String, Double>,
    ): Map<String, Point> {
        val n = ids.size
        val target = DoubleArray(n) { depth[ids[it]]?.times(LEVEL) ?: Double.NaN }
        val layered = target.any { !it.isNaN() }
        if (n == 1) return mapOf(ids.single() to Point(0.0, if (layered) target[0] else 0.0))
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val pairs = links.map { (a, b) -> index.getValue(a) to index.getValue(b) }
        val spread = K * sqrt(n.toDouble())
        val xs = DoubleArray(n) { Random(ids[it].hashCode()).nextDouble() * spread }
        val ys = DoubleArray(n) {
            if (!target[it].isNaN()) target[it] else Random(ids[it].hashCode() * 31 + 7).nextDouble() * spread
        }

        repeat(ITERATIONS) { step ->
            val dx = DoubleArray(n)
            val dy = DoubleArray(n)
            // Every pair repels
            for (i in 0 until n) for (j in i + 1 until n) {
                val ex = xs[i] - xs[j]
                val ey = ys[i] - ys[j]
                val d = max(sqrt(ex * ex + ey * ey), 0.01)
                val f = K * K / d / d
                dx[i] += ex * f; dy[i] += ey * f
                dx[j] -= ex * f; dy[j] -= ey * f
            }
            // Links attract; links to small nodes want to be much shorter
            for ((i, j) in pairs) {
                val ex = xs[i] - xs[j]
                val ey = ys[i] - ys[j]
                val d = max(sqrt(ex * ex + ey * ey), 0.01)
                val ideal = if (small.getValue(ids[i]) || small.getValue(ids[j])) K * 0.3 else K
                val f = d / ideal
                dx[i] -= ex * f; dy[i] -= ey * f
                dx[j] += ex * f; dy[j] += ey * f
            }
            val temperature = spread / 5 * (1 - step.toDouble() / ITERATIONS) + 0.5
            for (i in 0 until n) {
                // A place with a depth only moves sideways, so only its sideways force counts toward the step
                if (!target[i].isNaN()) dy[i] = 0.0
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01)
                val move = min(d, temperature)
                xs[i] += dx[i] / d * move
                ys[i] += dy[i] / d * move
            }
        }
        if (layered) {
            spreadLevels(xs, target)
            val mx = xs.average()
            for (i in 0 until n) xs[i] -= mx
        } else alignToWidth(xs, ys)
        for (i in 0 until n) if (small.getValue(ids[i])) clearOfLabels(i, xs, ys)
        return ids.indices.associate { ids[it] to Point(xs[it], ys[it]) }
    }

    /**
     * Small places (minor locations) sit on short springs right next to their landmark, often on its
     * label, which hangs below it. Step such a place up (or, failing that, down) until it is clear of
     * every other place and its label.
     */
    private fun clearOfLabels(i: Int, xs: DoubleArray, ys: DoubleArray) {
        fun clear(y: Double) = xs.indices.none { j ->
            j != i && kotlin.math.abs(xs[j] - xs[i]) < LABEL_HALF_WIDTH && (y - ys[j]) in -K * 0.3..K * 0.45
        }
        val steps = (1..12).map { it * K * 0.1 }
        val y = (listOf(ys[i]) + steps.map { ys[i] - it } + steps.map { ys[i] + it }).firstOrNull(::clear) ?: return
        ys[i] = y
    }

    /**
     * Places on the same level can be pulled on top of each other by links pointing up and down
     * (a delve between its two landmarks). Push them apart left to right to at least [MIN_GAP],
     * keeping their order and the level's centre.
     */
    private fun spreadLevels(xs: DoubleArray, target: DoubleArray) {
        target.indices.filter { !target[it].isNaN() }.groupBy { target[it] }.values.forEach { level ->
            val order = level.sortedBy { xs[it] }
            val centre = order.map { xs[it] }.average()
            for (k in 1 until order.size) xs[order[k]] = max(xs[order[k]], xs[order[k - 1]] + MIN_GAP)
            val shift = centre - order.map { xs[it] }.average()
            order.forEach { xs[it] += shift }
        }
    }

    /** Centres the points and rotates them so their longest spread runs horizontally. */
    private fun alignToWidth(xs: DoubleArray, ys: DoubleArray) {
        val mx = xs.average()
        val my = ys.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) {
            val x = xs[i] - mx; val y = ys[i] - my
            sxx += x * x; syy += y * y; sxy += x * y
        }
        val angle = -0.5 * atan2(2 * sxy, sxx - syy)  // principal axis -> x axis
        val c = cos(angle); val s = sin(angle)
        for (i in xs.indices) {
            val x = xs[i] - mx; val y = ys[i] - my
            xs[i] = x * c - y * s
            ys[i] = x * s + y * c
        }
    }

    /**
     * [layered] groups go side by side in one band, keeping their y (so depths line up across groups);
     * [free] groups are packed in rows above that band.
     */
    private fun pack(layered: List<Map<String, Point>>, free: List<Map<String, Point>>, aspect: Double): Map<String, Point> {
        val gap = K
        val out = mutableMapOf<String, Point>()
        var x = 0.0
        for (g in layered) {
            val minX = g.values.minOf { it.x }
            g.forEach { (id, p) -> out[id] = Point(p.x - minX + x, p.y) }
            x += g.values.maxOf { it.x } - minX + gap
        }
        if (free.isEmpty()) return out
        val rows = packRows(free, aspect)
        if (out.isEmpty()) return rows
        val top = out.values.minOf { it.y }
        val rowsHeight = rows.values.maxOf { it.y }
        rows.forEach { (id, p) -> out[id] = Point(p.x, p.y + top - gap - rowsHeight) }
        return out
    }

    /** Lays groups out in rows (largest first), aiming for the page's [aspect] ratio. */
    private fun packRows(groups: List<Map<String, Point>>, aspect: Double): Map<String, Point> {
        val gap = K
        val boxes = groups.map { g ->
            val minX = g.values.minOf { it.x }; val minY = g.values.minOf { it.y }
            Triple(g, g.values.maxOf { it.x } - minX, g.values.maxOf { it.y } - minY).let { (pts, w, h) ->
                Triple(pts.mapValues { Point(it.value.x - minX, it.value.y - minY) }, w, h)
            }
        }
        val area = boxes.sumOf { (_, w, h) -> (w + gap) * (h + gap) }
        val rowWidth = max(boxes.maxOf { it.second }, sqrt(area * aspect))
        val out = mutableMapOf<String, Point>()
        var x = 0.0; var y = 0.0; var rowHeight = 0.0
        for ((pts, w, h) in boxes) {
            if (x > 0 && x + w > rowWidth) { x = 0.0; y += rowHeight + gap; rowHeight = 0.0 }
            pts.forEach { (id, p) -> out[id] = Point(p.x + x, p.y + y) }
            x += w + gap
            rowHeight = max(rowHeight, h)
        }
        return out
    }

    /**
     * Control point for drawing the link [a]–[b] as a curve, or null if a straight line is clear.
     * A straight line passing within [clearance] of another place (e.g. two places further along the
     * same tier) would look like it connects to it, so it bows away: from the place it would hit, or
     * upwards when the place is right on the line, since labels hang below places.
     */
    fun bend(a: Point, b: Point, others: Collection<Point>, clearance: Double): Point? {
        val vx = b.x - a.x; val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        if (len2 < 1.0) return null
        val len = sqrt(len2)
        // The nearest place sitting on the segment (away from its ends), with its signed distance from the line
        val hit = others.mapNotNull { p ->
            val t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / len2
            val side = ((p.x - a.x) * vy - (p.y - a.y) * vx) / len
            if (t > 0.02 && t < 0.98 && kotlin.math.abs(side) < clearance) side else null
        }.minByOrNull { kotlin.math.abs(it) } ?: return null
        // Unit normal pointing upwards (screen y grows down); flip it to bow away from a place off to one side
        var nx = vy / len; var ny = -vx / len
        if (ny > 0 || (ny == 0.0 && nx > 0)) { nx = -nx; ny = -ny }
        if (kotlin.math.abs(hit) > 1 && (hit > 0) == (nx * vy - ny * vx > 0)) { nx = -nx; ny = -ny }
        // A quadratic curve's middle sits halfway to its control point, so go twice the clearance out
        val offset = max(2.2 * clearance, 0.22 * len)
        return Point((a.x + b.x) / 2 + nx * offset, (a.y + b.y) / 2 + ny * offset)
    }

    /** Scales and centres the layout into the box, same scale on both axes so distances stay true. */
    private fun fit(points: Map<String, Point>, width: Double, height: Double, margin: Double): Map<String, Point> {
        val minX = points.values.minOf { it.x }; val maxX = points.values.maxOf { it.x }
        val minY = points.values.minOf { it.y }; val maxY = points.values.maxOf { it.y }
        val spanX = maxX - minX
        val spanY = maxY - minY
        // A lone place (or one tiny group) shouldn't be blown up to fill the page
        val scale = min(min((width - 2 * margin) / max(spanX, 1.0), (height - 2 * margin) / max(spanY, 1.0)), 3.0)
        val offX = (width - spanX * scale) / 2
        val offY = (height - spanY * scale) / 2
        return points.mapValues { (_, p) -> Point(offX + (p.x - minX) * scale, offY + (p.y - minY) * scale) }
    }
}
