package tablekeeper.map

import tablekeeper.core.CampaignGraph
import tablekeeper.core.EdgeRef
import tablekeeper.core.Node
import tablekeeper.modules.MapDepth
import tablekeeper.modules.ResolvedModule
import java.util.UUID
import kotlin.math.roundToInt

/** A place on the map; [highlight] marks it as selected, e.g. a landmark featured in a session packet. */
data class MapPlace(
    val id: UUID, val code: String, val title: String, val typeLabel: String,
    val shape: String, val x: Int, val y: Int, val highlight: Boolean = false,
)

/**
 * A link between two places; [minor] when it touches a small "dot" place, e.g. a minor location.
 * [cx]/[cy]: curve control point when a straight line would run through another place; null = straight.
 */
data class MapLine(val x1: Int, val y1: Int, val x2: Int, val y2: Int, val minor: Boolean,
                   val cx: Int? = null, val cy: Int? = null)

/** A horizontal guide for one depth, e.g. "Tier 2", at the height its places were drawn. */
data class MapLevel(val label: String, val y: Int)

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
        return draw(module, places, onMap.edges, depths, between, satellites, highlight)
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
    ): MapView {
        val shapes = places.associate { (n, shape) -> n.id to shape }
        // Undirected, deduplicated place-to-place links (delve–landmark, minor location–landmark, room–room, ...)
        val lines = edges.filter { it.fromId in shapes && it.toId in shapes }
            .map { setOf(it.fromId, it.toId) }.distinct().filter { it.size == 2 }.map { it.toList() }

        val pos = MapLayout.layout(
            places.map { (n, shape) ->
                MapLayout.Item(n.id.toString(), small = shape == "dot", depth = depths[n.id]?.depth,
                    labelWidth = labelWidth(n.code, module.displayTitle(n), shape), body = BODY.getValue(shape))
            },
            lines.map { (a, b) -> a.toString() to b.toString() },
            WIDTH.toDouble(), HEIGHT.toDouble(), MARGIN,
            between = between.entries.associate { (k, v) -> k.toString() to v.map { it.toString() } },
            satellites = satellites.entries.associate { (k, v) -> k.toString() to v.toString() },
        )
        fun at(id: UUID) = pos.getValue(id.toString())

        return MapView(
            WIDTH, HEIGHT,
            places = places.map { (n, shape) ->
                val p = at(n.id)
                MapPlace(n.id, n.code, module.displayTitle(n), module.label(n.type), shape, p.x.roundToInt(), p.y.roundToInt(), n.id in highlight)
            },
            lines = lines.map { (a, b) ->
                val p = at(a); val q = at(b)
                val c = MapLayout.bend(p, q, places.map { it.first.id }.filter { it != a && it != b }.map(::at), CLEARANCE)
                MapLine(p.x.roundToInt(), p.y.roundToInt(), q.x.roundToInt(), q.y.roundToInt(),
                    minor = shapes[a] == "dot" || shapes[b] == "dot", cx = c?.x?.roundToInt(), cy = c?.y?.roundToInt())
            },
            // One guide per depth value, labelled with the prop's title: "Tier 2"
            levels = depths.entries.groupBy({ it.value }, { it.key }).map { (depth, ids) ->
                MapLevel(depth.label, ids.map { at(it).y }.average().roundToInt())
            }.sortedBy { it.y },
        )
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
    /** Half-size of each shape as drawn in map/_svg.peb. */
    private val BODY = mapOf("circle" to 26.0, "diamond" to 17.0, "dot" to 7.0)
    /** How far below a place's centre its label ends, as drawn in map/_svg.peb. */
    private val LABEL_BELOW = mapOf("circle" to 56, "diamond" to 44, "dot" to 28)
}
