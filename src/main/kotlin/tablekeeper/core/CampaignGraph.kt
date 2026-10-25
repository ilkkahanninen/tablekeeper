package tablekeeper.core

import java.util.UUID

/**
 * A whole campaign's nodes and edges in memory, for pages and prints that walk the graph: the campaign home,
 * the map, module views and session packets. Pure, so it is testable without a database.
 */
class CampaignGraph(val nodes: List<Node>, val edges: List<EdgeRef>) {
    val byId: Map<UUID, Node> = nodes.associateBy { it.id }

    /** Ids at the far end of [id]'s [edgeType] edges leaving ([Direction.OUT]) or entering ([Direction.IN]) it. */
    fun linked(id: UUID, edgeType: String, direction: Direction): List<UUID> = edges.filter { it.type == edgeType }.mapNotNull { e ->
        if (direction == Direction.OUT) e.toId.takeIf { e.fromId == id } else e.fromId.takeIf { e.toId == id }
    }

    /** Like [linked], but the nodes, and only those in this graph (e.g. left after audience filtering). */
    fun linkedNodes(id: UUID, edgeType: String, direction: Direction): List<Node> = linked(id, edgeType, direction).mapNotNull { byId[it] }

    /** The same graph with only [keep] nodes; edges are kept, and [linkedNodes] skips the removed ends. */
    fun filter(keep: (Node) -> Boolean): CampaignGraph = CampaignGraph(nodes.filter(keep), edges)
}
