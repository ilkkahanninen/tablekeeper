package tablekeeper.map

import tablekeeper.core.CampaignGraph
import tablekeeper.core.EdgeRef
import tablekeeper.core.Node
import tablekeeper.modules.MapDepth
import tablekeeper.modules.ResolvedModule
import java.util.UUID
import kotlin.math.roundToInt

/** A place on the map; [highlight] marks it as selected, e.g. a landmark featured in a session packet; [label] false: no name drawn. */
data class MapPlace(
    val id: UUID, val code: String, val title: String, val typeLabel: String,
    val shape: String, val x: Int, val y: Int, val highlight: Boolean = false, val label: Boolean = true,
)

/**
 * A link between two places; [minor] when it touches a small "dot" place, e.g. a minor location.
 * [cx]/[cy]: curve control point when a straight line would run through another place; null = straight.
 */
data class MapLine(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val minor: Boolean,
                   val cx: Int? = null, val cy: Int? = null)

/** One depth's band, e.g. "Tier 2": from [top] to [bottom], around the places drawn at that depth. */
data class MapLevel(val label: String, val top: Int, val bottom: Int)

/** A drawing: the part [x], [y], [width] × [height] of the 1400×990 layout (a node's map is cropped to its places). */
data class MapView(val width: Int, val height: Int, val places: List<MapPlace>, val lines: List<MapLine>,
                   val levels: List<MapLevel> = emptyList(), val x: Int = 0, val y: Int = 0)

/**
 * Maps. The campaign map: node types with a module `mapShape` are places, and links between places are lines.
 * Places whose type has a `mapDepth` prop (Heart's tier) are drawn lower the higher it is; `mapBetween` types (delves)
 * take the average depth of what they link to. A node's own map ([nodeView], the type's `map`, e.g. a dungeon's rooms):
 * the nodes it links that way, and the links between them. GM view.
 */
object MapService {

    fun view(graph: CampaignGraph, module: ResolvedModule, highlight: Set<UUID> = emptySet()): MapView {
        val onMap = graph.filter { module.mapShape(it.type) != null }
        val places = onMap.nodes.map { n -> n to module.mapShape(n.type)!! }
        // Depth = the type's mapDepth prop, e.g. a landmark's tier; unset tiers (and ones like Rogue) leave the place free
        val depths = places.mapNotNull { (n, _) -> module.depthOf(n.type, n.props)?.let { n.id to it } }.toMap()
        // Delves are drawn between their landmarks (mapBetween); minor locations around theirs (context)
        val between = places.mapNotNull { (n, _) -> module.mapBetween(n.type)?.let { n.id to onMap.linkedNodes(n.id, it.edgeType, it.direction).map { it.id } } }.toMap()
        val satellites = places.mapNotNull { (n, _) -> module.parent(onMap, n)?.let { n.id to it.id } }.toMap()
        val unlabelled = places.filter { (n, _) -> !module.mapLabel(n.type) }.map { it.first.id }.toSet()
        // A portrait map (Heart's tiers going down) is the same drawing turned upright: A4 portrait proportions
        val (w, h) = if (module.mapOrientation == "portrait") HEIGHT to WIDTH else WIDTH to HEIGHT
        return draw(module, places, onMap.edges, depths, between, satellites, highlight, w, h, unlabelled)
    }

    /** [node]'s own map (its type's `map`): the nodes it links via the map's rel, in its shape; null if the type has none. */
    fun nodeView(graph: CampaignGraph, module: ResolvedModule, node: Node): MapView? {
        val (option, shape, highlight) = module.nodeMap(node.type) ?: return null
        val places = graph.linkedNodes(node.id, option.edgeType, option.direction).map { it to shape }
        val lit = places.filter { (n, _) -> highlight != null && n.props[highlight] == true }.map { it.first.id }.toSet()
        return crop(draw(module, places, graph.edges, emptyMap(), emptyMap(), emptyMap(), lit))
    }

    /** [map] cut down to its places and their labels (a few rooms would otherwise sit small in a page-sized drawing). */
    private fun crop(map: MapView): MapView {
        if (map.places.isEmpty()) return map
        val pad = 40
        val left = map.places.minOf { it.x - maxOf(BODY.getValue(it.shape), labelWidth(it.code, it.title, it.shape) / 2).roundToInt() } - pad
        val right = map.places.maxOf { it.x + maxOf(BODY.getValue(it.shape), labelWidth(it.code, it.title, it.shape) / 2).roundToInt() } + pad
        val top = map.places.minOf { it.y - BODY.getValue(it.shape).roundToInt() } - pad
        val bottom = map.places.maxOf { it.y + LABEL_BELOW.getValue(it.shape) } + pad
        // Not flatter than 2:1, so a row of rooms keeps some height around it
        val height = maxOf(bottom - top, (right - left) / 2)
        return map.copy(x = left, y = (top + bottom - height) / 2, width = right - left, height = height)
    }

    private fun draw(
        module: ResolvedModule, places: List<Pair<Node, String>>, edges: List<EdgeRef>, depths: Map<UUID, MapDepth>,
        between: Map<UUID, List<UUID>>, satellites: Map<UUID, UUID>, highlight: Set<UUID>,
        width: Int = WIDTH, height: Int = HEIGHT, unlabelled: Set<UUID> = emptySet(),
    ): MapView {
        val shapes = places.associate { (n, shape) -> n.id to shape }
        // Undirected, deduplicated place-to-place links (delve–landmark, minor location–landmark, room–room, ...)
        val lines = edges.filter { it.fromId in shapes && it.toId in shapes }
            .map { setOf(it.fromId, it.toId) }.distinct().filter { it.size == 2 }.map { it.toList() }

        val pos = MapLayout.layout(
            places.map { (n, shape) ->
                MapLayout.Item(n.id.toString(), small = shape == "dot", depth = depths[n.id]?.depth,
                    labelWidth = if (n.id in unlabelled) 0.0 else labelWidth(n.code, module.displayTitle(n), shape), body = BODY.getValue(shape))
            },
            lines.map { (a, b) -> a.toString() to b.toString() },
            width.toDouble(), height.toDouble(), MARGIN,
            between = between.entries.associate { (k, v) -> k.toString() to v.map { it.toString() } },
            satellites = satellites.entries.associate { (k, v) -> k.toString() to v.toString() },
        )
        fun at(id: UUID) = pos.getValue(id.toString())

        return MapView(
            width, height,
            places = places.map { (n, shape) ->
                val p = at(n.id)
                MapPlace(n.id, n.code, module.displayTitle(n), module.label(n.type), shape, p.x.roundToInt(), p.y.roundToInt(), n.id in highlight,
                    label = n.id !in unlabelled)
            },
            lines = lines.map { (a, b) ->
                val p = at(a); val q = at(b)
                val c = MapLayout.bend(p, q, places.map { it.first.id }.filter { it != a && it != b }.map(::at), CLEARANCE)
                MapLine(p.x.roundToInt(), p.y.roundToInt(), q.x.roundToInt(), q.y.roundToInt(),
                    minor = shapes[a] == "dot" || shapes[b] == "dot", cx = c?.x?.roundToInt(), cy = c?.y?.roundToInt())
            },
            levels = bands(depths.entries.groupBy({ it.value }, { it.key }).mapValues { (_, ids) -> ids.map { at(it).y } }),
        )
    }

    /**
     * A band per depth, labelled with the prop's title ("Tier 2"), down the page in depth order: each from halfway
     * between its places and the depth above's to halfway to the depth below's; the first and last reach a little past
     * their places (and labels).
     */
    private fun bands(ys: Map<MapDepth, List<Double>>): List<MapLevel> {
        val levels = ys.entries.sortedBy { it.key.depth }.map { Triple(it.key.label, it.value.min(), it.value.max()) }
        return levels.mapIndexed { i, (label, min, max) ->
            val top = levels.getOrNull(i - 1)?.let { (it.third + min) / 2 } ?: (min - BAND_PAD)
            val bottom = levels.getOrNull(i + 1)?.let { (max + it.second) / 2 } ?: (max + BAND_PAD + 20)
            MapLevel(label, top.roundToInt(), bottom.roundToInt())
        }
    }

    /** Rough label width in drawing units, matching map/_svg.peb's font sizes (code in a smaller monospace, then the title). */
    private fun labelWidth(code: String, title: String, shape: String): Double {
        val (titleSize, codeSize) = if (shape == "dot") 15.0 to 12.0 else 19.0 to 15.0
        return title.length * titleSize * 0.5 + (code.length + 1) * codeSize * 0.6
    }

    // A4 landscape proportions, so the same drawing fills the screen and the printed page.
    const val WIDTH = 1400
    const val HEIGHT = 990
    private const val MARGIN = 130.0
    /** How close a line may pass another place's centre before it's curved around it (a landmark's radius is 26). */
    private const val CLEARANCE = 40.0
    /** Drawing units the first and last bands reach past their places. */
    private const val BAND_PAD = 50.0
    /** Half-size of each shape as drawn in map/_svg.peb. */
    private val BODY = mapOf("circle" to 26.0, "diamond" to 17.0, "dot" to 7.0)
    /** How far below a place's centre its label ends, as drawn in map/_svg.peb. */
    private val LABEL_BELOW = mapOf("circle" to 56, "diamond" to 44, "dot" to 28)
}
