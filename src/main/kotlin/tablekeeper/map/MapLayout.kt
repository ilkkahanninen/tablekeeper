package tablekeeper.map

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic map layout. Start positions are seeded from node ids and the iteration count is fixed, so the same
 * places and links always give the same map; it only moves when links change.
 *
 * 1. The core places (landmarks) get a force layout (Fruchterman–Reingold), tried from several start positions, keeping
 *    the one with the fewest crossing links and links running through places. Delves are not in it: their landmarks are
 *    linked directly instead, on springs twice as long, since a delve will sit between them. Each connected group is
 *    laid out on its own and the groups are packed side by side, so an unconnected place can't squeeze the rest.
 *    A place with a [Item.depth] (Heart's tier) stays in that depth's band, deeper lower: links move it sideways, and up
 *    or down within the band, so a tier needn't be a straight row.
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
    /** Force layouts tried per group, from different start positions; the least tangled wins ([tangle]). */
    private const val ATTEMPTS = 8
    /** As MapService draws: a line passing this close to another place (in output units) is curved around it. */
    private const val DRAWN_CLEARANCE = 40.0
    /** Ideal distance between linked places, in layout units (only the proportions matter; [fit] scales). */
    private const val K = 100.0
    /** Vertical distance between consecutive depths. */
    private const val LEVEL = K * 1.3
    /** How far up or down a place may move from its depth's height: its band, with a gap left between bands. */
    const val BAND_HALF = LEVEL * 0.4
    /** How strongly a place is drawn back to its band's middle (force per layout unit off it). */
    private const val BAND_PULL = 1.5
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

        val components = components(items.map { it.id }.filter { it in core }, springs.map { it.a to it.b })
        // The whole layout from one set of start positions; tried again from others while the drawing is tangled
        fun attempt(n: Int): Map<String, Point> {
            val groups = components.map { group -> placeOnce(group, springs.filter { it.a in group }, depth, n) }
                .sortedWith(compareByDescending<Map<String, Point>> { it.size }.thenBy { it.keys.min() })
            val (layered, free) = groups.partition { g -> g.keys.any { it in depth } }
            val points = pack(layered, free, width / height).toMutableMap()
            placeBetween(points, anchorsOf)
            clearSmallOfLabels(points, small.filterKeys { it !in parentOf })
            placeSatellites(points, parentOf, edges.filter { (a, b) -> a in points && b in points })
            val placed = fit(points, width, height, margin, stretchY = depth.isNotEmpty())
            return declutter(placed, items.associateBy { it.id }, width, margin)
        }
        val drawn = edges.filter { (a, b) -> a in ids && b in ids && a != b }.distinctBy { setOf(it.first, it.second) }
        var best = attempt(0)
        var bestScore = drawnTangles(best, drawn)
        for (n in 1 until ATTEMPTS) {
            if (bestScore == 0) break
            val next = attempt(n)
            val score = drawnTangles(next, drawn)
            if (score < bestScore) { best = next; bestScore = score }
        }
        return best
    }

    /**
     * How tangled a finished drawing is: lines that must curve around a place they would run through ([bend]), and lines
     * crossing each other. Untangled maps keep their first layout; tangled ones are tried from other start positions.
     */
    private fun drawnTangles(points: Map<String, Point>, lines: List<Pair<String, String>>): Int {
        val bent = lines.count { (a, b) -> bend(points.getValue(a), points.getValue(b), points.filterKeys { it != a && it != b }.values, DRAWN_CLEARANCE) != null }
        val crossings = lines.indices.sumOf { i ->
            (i + 1 until lines.size).count { j ->
                val (a, b) = lines[i]; val (c, d) = lines[j]
                a != c && a != d && b != c && b != d && crosses(points.getValue(a), points.getValue(b), points.getValue(c), points.getValue(d))
            }
        }
        return bent + crossings
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
    /**
     * How tangled a layout looks: links crossing each other, and links running (nearly) through another place, which then
     * have to be drawn curving around it; then, between equally tangled ones, shorter links.
     */
    private fun tangle(xs: DoubleArray, ys: DoubleArray, springs: List<Triple<Int, Int, Double>>): Double {
        fun p(i: Int) = Point(xs[i], ys[i])
        val crossings = springs.indices.sumOf { i ->
            (i + 1 until springs.size).count { j ->
                val (a, b) = springs[i]; val (c, d) = springs[j]
                a != c && a != d && b != c && b != d && crosses(p(a), p(b), p(c), p(d))
            }
        }
        val throughPlaces = springs.sumOf { (a, b) -> xs.indices.count { it != a && it != b && passesNear(p(a), p(b), p(it), K * 0.35) } }
        val length = springs.sumOf { (a, b) -> hypot(xs[a] - xs[b], ys[a] - ys[b]) }
        return (crossings + throughPlaces) * 10.0 + length / (K * max(springs.size, 1))
    }

    private fun crosses(p1: Point, p2: Point, p3: Point, p4: Point): Boolean {
        fun side(a: Point, b: Point, c: Point) = (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)
        return side(p1, p2, p3) * side(p1, p2, p4) < 0 && side(p3, p4, p1) * side(p3, p4, p2) < 0
    }

    /** Does the segment [a]–[b] pass within [clearance] of [p], away from its ends? */
    private fun passesNear(a: Point, b: Point, p: Point, clearance: Double): Boolean {
        val vx = b.x - a.x; val vy = b.y - a.y
        val len2 = vx * vx + vy * vy
        if (len2 < 1.0) return false
        val t = ((p.x - a.x) * vx + (p.y - a.y) * vy) / len2
        return t > 0.05 && t < 0.95 && kotlin.math.abs((p.x - a.x) * vy - (p.y - a.y) * vx) / sqrt(len2) < clearance
    }

    /** One force layout of a group from start positions seeded by the ids and [attempt]. */
    private fun placeOnce(ids: List<String>, springs: List<Spring>, depth: Map<String, Double>, attempt: Int): Map<String, Point> {
        val n = ids.size
        val target = DoubleArray(n) { depth[ids[it]]?.times(LEVEL) ?: Double.NaN }
        val layered = target.any { !it.isNaN() }
        if (n == 1) return mapOf(ids.single() to Point(0.0, if (layered) target[0] else 0.0))
        val index = ids.withIndex().associate { (i, id) -> id to i }
        val pairs = springs.map { Triple(index.getValue(it.a), index.getValue(it.b), it.length) }
        val spread = K * sqrt(n.toDouble())
        val xs = DoubleArray(n) { Random(ids[it].hashCode() + attempt * 7919).nextDouble() * spread }
        val ys = DoubleArray(n) {
            if (!target[it].isNaN()) target[it] else Random(ids[it].hashCode() * 31 + 7 + attempt * 7919).nextDouble() * spread
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
            // A place with a depth is drawn back to its band's middle, so it leaves it only where that helps
            for (i in 0 until n) if (!target[i].isNaN()) dy[i] += (target[i] - ys[i]) * BAND_PULL
            val temperature = spread / 5 * (1 - step.toDouble() / ITERATIONS) + 0.5
            for (i in 0 until n) {
                val d = max(sqrt(dx[i] * dx[i] + dy[i] * dy[i]), 0.01)
                val move = min(d, temperature)
                xs[i] += dx[i] / d * move
                ys[i] += dy[i] / d * move
                // A place with a depth stays in its band
                if (!target[i].isNaN()) ys[i] = ys[i].coerceIn(target[i] - BAND_HALF, target[i] + BAND_HALF)
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
     * Places on one level hardly move vertically (their band is narrow), and they push each other away, so in the force
     * layout they can't pass one another: wherever they start, they keep that order, e.g. a landmark stuck between two
     * others its delves join, so both delves bow over it. Fix the order afterwards: swap any two places on a level, or move
     * one up or down its band, whenever that makes the map less tangled ([tangle]), until nothing helps.
     */
    private fun untangleLevels(xs: DoubleArray, ys: DoubleArray, target: DoubleArray, springs: List<Triple<Int, Int, Double>>) {
        val levels = target.indices.filter { !target[it].isNaN() }.groupBy { target[it] }.values
        fun score() = tangle(xs, ys, springs)
        var best = score()
        repeat(4 * xs.size) {
            var improved = false
            for (level in levels) {
                for (a in level) for (b in level) if (a < b) {
                    xs[a] = xs[b].also { xs[b] = xs[a] }; ys[a] = ys[b].also { ys[b] = ys[a] }
                    // Each keeps to its own band's height range (same band: the swap keeps both inside it)
                    val s = score()
                    if (s < best - 1e-6) { best = s; improved = true }
                    else { xs[a] = xs[b].also { xs[b] = xs[a] }; ys[a] = ys[b].also { ys[b] = ys[a] } }
                }
                for (a in level) for (y in listOf(target[a] - BAND_HALF, target[a], target[a] + BAND_HALF)) {
                    val old = ys[a]
                    ys[a] = y
                    val s = score()
                    if (s < best - 1e-6) { best = s; improved = true } else ys[a] = old
                }
            }
            if (!improved) return
        }
    }

    /**
     * Places on the same level can be pulled on top of each other by links pointing up and down
     * (a delve between its two landmarks). Push them apart left to right to at least [MIN_GAP],
     * keeping their order and the level's centre. Their band is too narrow for labels to pass above or below.
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

    /**
     * Scales and centres the layout into the box, same scale on both axes so distances stay true. With [stretchY] (a map
     * with depths), heights are stretched up to three times as much, so the depth bands fill the page even when the map is
     * wide (or the page is portrait).
     */
    private fun fit(points: Map<String, Point>, width: Double, height: Double, margin: Double, stretchY: Boolean = false): Map<String, Point> {
        val minX = points.values.minOf { it.x }; val maxX = points.values.maxOf { it.x }
        val minY = points.values.minOf { it.y }; val maxY = points.values.maxOf { it.y }
        val spanX = maxX - minX
        val spanY = maxY - minY
        // A lone place (or one tiny group) shouldn't be blown up to fill the page
        val scale = min(min((width - 2 * margin) / max(spanX, 1.0), (height - 2 * margin) / max(spanY, 1.0)), 3.0)
        val scaleY = if (stretchY) min((height - 2 * margin) / max(spanY, 1.0), 3 * scale).coerceAtLeast(scale) else scale
        val offX = (width - spanX * scale) / 2
        val offY = (height - spanY * scaleY) / 2
        return points.mapValues { (_, p) -> Point(offX + (p.x - minX) * scale, offY + (p.y - minY) * scaleY) }
    }
}
