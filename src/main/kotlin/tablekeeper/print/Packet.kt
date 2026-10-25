package tablekeeper.print

import tablekeeper.core.CampaignGraph
import tablekeeper.core.Node
import tablekeeper.modules.PacketDef
import tablekeeper.modules.ResolvedModule
import java.util.UUID

/** One part of a session packet: the selected (or in-between) nodes of one type, each as its print model. */
data class PacketSection(val typeId: String, val label: String, val items: List<PrintModel>)

/**
 * What a packet covers: the nodes its node links via the packet's `rel` ([selected], e.g. a session's landmarks and
 * characters), and the [between], [around] and [related] nodes its rules add.
 */
data class PacketSelection(val selected: List<Node>, val between: List<Node>, val around: List<Node>, val related: List<Node>) {
    val all: List<Node> get() = (selected + between + around + related).distinctBy { it.id }
}

/** Pure selection rules for session packets. */
object Packet {
    /** [node]'s packet per [def], over [graph] (already filtered for the audience). */
    fun select(module: ResolvedModule, node: Node, def: PacketDef, graph: CampaignGraph): PacketSelection {
        fun linked(id: UUID, typeId: String, rel: String): List<UUID> =
            module.linkOption(typeId, rel)!!.let { o -> graph.linkedNodes(id, o.edgeType, o.direction).map { it.id } }
        fun ofType(type: String) = graph.nodes.filter { module.isA(it.type, type) }.map { it.id }
        fun nodes(ids: List<UUID>) = ids.mapNotNull { graph.byId[it] }

        val selected = nodes(linked(node.id, node.type, def.rel))
        val selectedIds = selected.map { it.id }.toSet()
        val between = def.between.flatMap { b -> between(selectedIds, ofType(b.type)) { linked(it, b.type, b.rel) } }
        // A node "inside" another via its type's context link, e.g. a minor location inside its landmark
        val around = def.around.flatMap { a ->
            around(selectedIds, ofType(a.type), { linked(it, a.type, a.rel) }, { id -> graph.byId[id]?.let { module.parent(graph, it)?.id } })
        }
        val included = selectedIds + between + around
        val related = def.related.flatMap { r -> related(included, ofType(r.type)) { linked(it, r.type, r.rel) } }
        return PacketSelection(selected, nodes(between), nodes(around), nodes(related))
    }

    /**
     * [candidates] (e.g. delves) whose links (given by [linksOf], e.g. to their landmarks) all end at [selected]
     * nodes, with at least one such link: the delves between the session's landmarks, dead ends included.
     */
    fun between(selected: Set<UUID>, candidates: List<UUID>, linksOf: (UUID) -> List<UUID>): List<UUID> =
        candidates.filter { c -> linksOf(c).let { links -> links.isNotEmpty() && links.all { it in selected } } }

    /**
     * [candidates] (e.g. NPCs) with a link (given by [linksOf], e.g. their location) to a [selected] node, or to a node
     * whose [parentOf] is selected: NPCs at a selected landmark or at one of its minor locations.
     */
    fun around(selected: Set<UUID>, candidates: List<UUID>, linksOf: (UUID) -> List<UUID>, parentOf: (UUID) -> UUID?): List<UUID> =
        candidates.filter { c -> linksOf(c).any { it in selected || parentOf(it) in selected } }

    /** [candidates] (e.g. factions) with a link (given by [linksOf], e.g. their members) to anything [included] in the packet. */
    fun related(included: Set<UUID>, candidates: List<UUID>, linksOf: (UUID) -> List<UUID>): List<UUID> =
        candidates.filter { c -> c !in included && linksOf(c).any { it in included } }
}
