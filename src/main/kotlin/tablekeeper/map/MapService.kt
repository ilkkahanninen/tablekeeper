package tablekeeper.map

import tablekeeper.core.Campaign
import tablekeeper.core.Direction
import tablekeeper.core.GraphService
import tablekeeper.modules.LinkOption
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

data class MapView(val width: Int, val height: Int, val places: List<MapPlace>, val lines: List<MapLine>,
                   val levels: List<MapLevel> = emptyList())

/**
 * The campaign map: node types with a module `mapShape` are places, and links between places are lines.
 * Places whose type has a `mapDepth` prop (Heart's tier) are drawn lower the higher it is; `mapBetween` types (delves)
 * take the average depth of what they link to. GM view.
 */
class MapService(private val graph: GraphService) {

    fun view(campaign: Campaign, module: ResolvedModule, highlight: Set<UUID> = emptySet()): MapView {
        val places = graph.listNodesWithProps(campaign.id).mapNotNull { n -> module.mapShape(n.type)?.let { n to it } }
        val shapes = places.associate { (n, shape) -> n.id to shape }
        val edges = graph.listEdges(campaign.id)

        // Undirected, deduplicated place-to-place links (delve–landmark, minor location–landmark, ...)
        val lines = edges.filter { it.fromId in shapes && it.toId in shapes }
            .map { setOf(it.fromId, it.toId) }.distinct().filter { it.size == 2 }.map { it.toList() }

        // Depth = the type's mapDepth prop, e.g. a landmark's tier; unset tiers (and ones like Rogue) leave the place free
        val depths = places.mapNotNull { (n, _) -> module.depthOf(n.type, n.props)?.let { n.id to it } }.toMap()
        fun linked(id: UUID, via: LinkOption): List<UUID> = edges.filter { it.type == via.edgeType }.mapNotNull { e ->
            when (via.direction) {
                Direction.OUT -> e.toId.takeIf { e.fromId == id }
                Direction.IN -> e.fromId.takeIf { e.toId == id }
            }
        }.filter { it in shapes }
        // Delves are drawn between their landmarks (mapBetween); minor locations around theirs (context)
        val between = places.mapNotNull { (n, _) -> module.mapBetween(n.type)?.let { n.id to linked(n.id, it) } }.toMap()
        val satellites = places.mapNotNull { (n, _) -> module.context(n.type)?.let { c -> linked(n.id, c).firstOrNull()?.let { n.id to it } } }.toMap()

        val pos = MapLayout.layout(
            places.map { (n, shape) ->
                MapLayout.Item(n.id.toString(), small = shape == "dot", depth = depths[n.id]?.depth,
                    labelWidth = labelWidth(n.code, n.title, shape), body = BODY.getValue(shape))
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
                MapPlace(n.id, n.code, n.title, module.label(n.type), shape, p.x.roundToInt(), p.y.roundToInt(), n.id in highlight)
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

    companion object {
        // A4 landscape proportions, so the same drawing fills the screen and the printed page.
        const val WIDTH = 1400
        const val HEIGHT = 990
        private const val MARGIN = 130.0
        /** How close a line may pass another place's centre before it's curved around it (a landmark's radius is 26). */
        private const val CLEARANCE = 40.0
        /** Half-size of each shape as drawn in map/_svg.peb. */
        private val BODY = mapOf("circle" to 26.0, "diamond" to 17.0, "dot" to 7.0)
    }
}
