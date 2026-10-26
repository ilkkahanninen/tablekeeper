package tablekeeper.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic map layout. Start positions are seeded from node ids and the iteration count is fixed, so the same
 * places and links always give the same map; it only moves when links change.
 *
 * 1. The core places (landmarks) get a force layout (Fruchterman–Reingold). Delves are not in it: their landmarks are
 *    linked directly instead, on springs twice as long, since a delve will sit between them. Each connected group is
 *    laid out on its own and the groups are packed side by side, so an unconnected place can't squeeze the rest.
 *    A place with a [Item.depth] (Heart's tier) is fixed at that height, deeper lower; links only move it sideways.
 *    Groups without depths are turned so their longest spread runs horizontally.
 * 2. Delves go to the middle of their landmarks, and minor locations are fanned out above their landmark.
 * 3. The result is fitted to the page, and labels are pushed apart where they would overlap.
 */
object MapLayout {
    /**
     * [small] nodes (e.g. minor locations) sit on short springs, so they cluster around what they link to.
     * [depth]: vertical level, higher = lower on the map; null = free to go anywhere.
     * [labelWidth]: estimated width of its label, and [body] the half-size of its shape, in output units; labels
     * (centred below the shape) are kept clear of other places' labels and shapes.
     */
    data class Item(val id: String, val small: Boolean = false, val depth: Double? = null,
                    val labelWidth: Double = 0.0, val body: Double = 0.0)
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
    /** Layout units from a landmark to its minor locations. */
    private const val SATELLITE_DISTANCE = K * 0.55
    /** Output units: a label's height below its shape. */
    private const val LABEL_HEIGHT = 28.0
    /** Output units between neighbouring labels on a row. */
    private const val LABEL_PADDING = 16.0

    /** Positions inside a [width] × [height] box, keeping [margin] free on every side for labels. */
    /**
     * [between]: nodes drawn between others, e.g. a delve -> the landmarks it connects. They are left out of the force
     * layout, which instead links their anchors directly (on a spring twice as long), and are then put between them.
     * [satellites]: nodes drawn around a parent, e.g. a minor location -> its landmark, placed once the parent is.
     */
    fun layout(
        items: List<Item>, edges: List<Pair<String, String>>, width: Double, height: Double, margin: Double,
        between: Map<String, List<String>> = emptyMap(), satellites: Map<String, String> = emptyMap(),
    ): Map<String, Point> {
        if (items.isEmpty()) return emptyMap()
        val ids = items.map { it.id }.toSet()
        val small = items.associate { it.id to it.small }
        val depth = items.mapNotNull { i -> i.depth?.let { i.id to it } }.toMap()

        // Core = everything not hung off something else: landmarks (and anything without anchors or parent)
        val anchorsOf = between.filterKeys { it in ids }
            .mapValues { (_, a) -> a.filter { it in ids && it !in between && it !in satellites }.distinct() }
            .filterValues { it.isNotEmpty() }
        val parentOf = satellites.filter { (s, p) -> s in ids && s !in anchorsOf && p in ids && p !in satellites && p !in anchorsOf }
        val core = ids.filter { it !in anchorsOf && it !in parentOf }.toSet()
        val springs = edges.filter { (a, b) -> a in core && b in core && a != b }
            .map { (a, b) -> Spring(a, b, if (small.getValue(a) || small.getValue(b)) K * 0.3 else K) } +
            anchorsOf.values.flatMap { a -> a.indices.flatMap { i -> (i + 1 until a.size).map { j -> Spring(a[i], a[j], 2 * K) } } }

        val groups = components(items.map { it.id }.filter { it in core }, springs.map { it.a to it.b })
            .map { group -> place(group, springs.filter { it.a in group }, depth) }
            .sortedWith(compareByDescending<Map<String, Point>> { it.size }.thenBy { it.keys.min() })
        val (layered, free) = groups.partition { g -> g.keys.any { it in depth } }
        val points = pack(layered, free, width / height).toMutableMap()
        placeBetween(points, anchorsOf)
        clearSmallOfLabels(points, small.filterKeys { it !in parentOf })
        placeSatellites(points, parentOf, edges.filter { (a, b) -> a in points && b in points })
        val placed = fit(points, width, height, margin)
        return declutter(placed, items.associateBy { it.id }, width, margin)
    }

    /** A force-layout link: [a] and [b] pulled towards [length] apart. */
    private data class Spring(val a: String, val b: String, val length: Double)

    /**
     * Puts each between-node (delve) at the middle of its anchors (landmarks), so its height is their average.
     * Several between the same anchors fan out sideways; a dead end (one anchor) steps out from it, away from the map's middle.
     */
    private fun placeBetween(points: MutableMap<String, Point>, anchorsOf: Map<String, List<String>>) {
        val middle = Point(points.values.map { it.x }.average(), points.values.map { it.y }.average())
        anchorsOf.entries.groupBy { it.value.toSet() }.forEach { (anchorSet, entries) ->
            val members = entries.map { it.key }.sorted()
            val anchors = anchorSet.mapNotNull { points[it] }
            val cx = anchors.map { it.x }.average()
            val cy = anchors.map { it.y }.average()
            if (anchors.size >= 2) {
                // Fan out perpendicular to the first two anchors' line
                val vx = anchors[1].x - anchors[0].x; val vy = anchors[1].y - anchors[0].y
                val len = max(sqrt(vx * vx + vy * vy), 0.01)
                members.forEachIndexed { i, id ->
                    val off = (i - (members.size - 1) / 2.0) * K * 0.6
                    points[id] = Point(cx - vy / len * off, cy + vx / len * off)
                }
            } else {
                val side = if (cx >= middle.x) 1.0 else -1.0
                members.forEachIndexed { i, id -> points[id] = Point(cx + side * K * (i + 1), cy) }
            }
        }
    }

    /**
     * Puts each satellite (minor location) close to its parent (landmark), in the free-est of 12 directions: furthest
     * from other places and from the parent's lines, and never in the label area below the parent.
     */
    private fun placeSatellites(points: MutableMap<String, Point>, parentOf: Map<String, String>, edges: List<Pair<String, String>>) {
        parentOf.entries.groupBy({ it.value }, { it.key }).forEach { (parent, children) ->
            val p = points.getValue(parent)
            // Points along the parent's lines, so satellites keep off them
            val alongLines = edges.filter { parent in it.toList() }.map { (a, b) -> points.getValue(if (a == parent) b else a) }
                .flatMap { q -> listOf(0.25, 0.5, 0.75).map { t -> Point(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t) } }
            for (id in children.sorted()) {
                val others = points.filterKeys { it != parent && it != id && it !in children.subList(children.indexOf(id), children.size) }.values + alongLines
                val best = (0 until 12).map { Math.toRadians(it * 30.0) }
                    .filter { a -> sin(a) < 0.01 }  // level with or above the parent: its label hangs below
                    .map { a -> Point(p.x + cos(a) * SATELLITE_DISTANCE, p.y + sin(a) * SATELLITE_DISTANCE) }
                    .maxBy { c -> others.minOfOrNull { o -> (c.x - o.x) * (c.x - o.x) + (c.y - o.y) * (c.y - o.y) } ?: 0.0 }
                points[id] = best
            }
        }
    }

    private fun clearSmallOfLabels(points: MutableMap<String, Point>, small: Map<String, Boolean>) {
        val ids = points.keys.toList()
        val xs = DoubleArray(ids.size) { points.getValue(ids[it]).x }
        val ys = DoubleArray(ids.size) { points.getValue(ids[it]).y }
        for (i in ids.indices) if (small.getValue(ids[i])) clearOfLabels(i, xs, ys)
        ids.forEachIndexed { i, id -> points[id] = Point(xs[i], ys[i]) }
    }

    /**
     * Labels sit centred below places, so a long label can run into a neighbour's label or shape. Where a place's
     * label or shape overlaps another's, push the two apart sideways, then squeeze the map back into the box if it
     * grew; a few rounds settle it. Works in output units, where label widths and shape sizes are known.
     */
    private fun declutter(points: Map<String, Point>, items: Map<String, Item>, width: Double, margin: Double): Map<String, Point> {
        if (items.values.all { it.labelWidth <= 0.0 }) return points
        val ids = points.keys.toList()
        val xs = DoubleArray(ids.size) { points.getValue(ids[it]).x }
        val ys = DoubleArray(ids.size) { points.getValue(ids[it]).y }
        // Boxes around a place: its shape, and its label below it (left, top, right, bottom)
        fun boxes(i: Int): List<DoubleArray> {
            val item = items.getValue(ids[i])
            val b = item.body
            val half = item.labelWidth / 2
            return listOf(
                doubleArrayOf(xs[i] - b, ys[i] - b, xs[i] + b, ys[i] + b),
                doubleArrayOf(xs[i] - half, ys[i] + b + 6, xs[i] + half, ys[i] + b + LABEL_HEIGHT),
            )
        }
        repeat(12) {
            var moved = false
            for (i in ids.indices) for (j in ids.indices) {
                if (i == j || xs[i] > xs[j] || (xs[i] == xs[j] && i > j)) continue
                // i is the left one: how far its boxes reach into j's, horizontally, where they also overlap vertically
                val overlap = boxes(i).flatMap { a -> boxes(j).map { c -> a to c } }
                    .filter { (a, c) -> a[1] < c[3] && c[1] < a[3] }
                    .maxOfOrNull { (a, c) -> a[2] - c[0] + LABEL_PADDING } ?: 0.0
                if (overlap > 0.5) { xs[i] -= overlap / 2; xs[j] += overlap / 2; moved = true }
            }
            // Back inside the box, keeping order; only x was changed
            val minX = xs.min(); val maxX = xs.max()
            val room = width - 2 * margin
            val scale = if (maxX - minX > room) room / (maxX - minX) else 1.0
            val shift = if (scale < 1.0) margin - minX * scale else maxOf(0.0, margin - minX) - maxOf(0.0, maxX - (width - margin))
            for (i in ids.indices) xs[i] = xs[i] * scale + shift
            if (!moved) return ids.indices.associate { ids[it] to Point(xs[it], ys[it]) }
        }
        return ids.indices.associate { ids[it] to Point(xs[it], ys[it]) }
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
    private fun place(ids: List<String>, springs: List<Spring>, depth: Map<String, Double>): Map<String, Point> {
        val n = ids.size
        val target = DoubleArray(n) { depth[ids[it]]?.times(LEVEL) ?: Double.NaN }
        val layered = target.any { !it.isNaN() }
        if (n == 1) return mapOf(ids.single() to Point(0.0, if (layered) target[0] else 0.0))
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val pairs = springs.map { Triple(index.getValue(it.a), index.getValue(it.b), it.length) }
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
            // Links attract, each towards its spring's length
            for ((i, j, ideal) in pairs) {
                val ex = xs[i] - xs[j]
                val ey = ys[i] - ys[j]
                val d = max(sqrt(ex * ex + ey * ey), 0.01)
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
            untangleLevels(xs, ys, target, pairs)
            spreadLevels(xs, target)
            val mx = xs.average()
            for (i in 0 until n) xs[i] -= mx
        } else alignToWidth(xs, ys)
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
     * Places on one level only move sideways, and they push each other away, so in the force layout they can't pass one
     * another: wherever they start, they keep that order, e.g. a landmark stuck between two others its delves join, so
     * both delves bow over it. Swap neighbours on a level whenever that shortens their links, until no swap helps.
     */
    private fun untangleLevels(xs: DoubleArray, ys: DoubleArray, target: DoubleArray, springs: List<Triple<Int, Int, Double>>) {
        val touching = xs.indices.associateWith { i -> springs.filter { it.first == i || it.second == i } }
        fun length(i: Int) = touching.getValue(i).sumOf { (a, b, _) -> kotlin.math.hypot(xs[a] - xs[b], ys[a] - ys[b]) }
        val levels = target.indices.filter { !target[it].isNaN() }.groupBy { target[it] }.values
        repeat(xs.size * xs.size) {
            var swapped = false
            for (level in levels) {
                val order = level.sortedBy { xs[it] }
                for (k in 0 until order.size - 1) {
                    val a = order[k]; val b = order[k + 1]
                    val before = length(a) + length(b)
                    xs[a] = xs[b].also { xs[b] = xs[a] }
                    if (length(a) + length(b) < before - 0.01) { swapped = true; break }
                    xs[a] = xs[b].also { xs[b] = xs[a] }   // no better: swap back
                }
            }
            if (!swapped) return
        }
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
