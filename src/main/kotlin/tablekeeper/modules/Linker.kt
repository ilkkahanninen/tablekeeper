package tablekeeper.modules

import tablekeeper.core.BadRequest
import tablekeeper.core.Direction
import tablekeeper.core.GraphService
import tablekeeper.core.Node
import java.util.UUID

/** The module's link rules on top of the graph: `linkLimits`, and `linkSlots` that hold one link at a time. */
class Linker(private val graph: GraphService) {

    /** Message if the node already has its type's `linkLimits` maximum of [edgeType] links in [dir], else null. [name] names it in the message. */
    fun limitError(campaignId: UUID, m: ResolvedModule, nodeId: UUID, nodeType: String, name: String, edgeType: String, dir: Direction): String? {
        val max = m.linkLimit(nodeType, edgeType, dir) ?: return null
        if (graph.countLinks(campaignId, nodeId, edgeType, dir) < max) return null
        val e = m.edgeType(edgeType)
        val label = (if (dir == Direction.OUT) e?.label else e?.inverseLabel) ?: edgeType
        return "$name already has the maximum of $max “$label” links"
    }

    /** [limitError] for [n] itself, named by code and title. */
    fun limitError(campaignId: UUID, m: ResolvedModule, n: Node, option: LinkOption): String? =
        limitError(campaignId, m, n.id, n.type, "${n.code} ${n.title}", option.edgeType, option.direction)

    /**
     * Creates the edge for [option] between [n] and [other], respecting direction and limits at both ends.
     * If an end holds this link in a slot (an item's Location), the slot's old link is replaced, so the item moves.
     */
    fun link(campaignId: UUID, m: ResolvedModule, n: Node, option: LinkOption, other: Node) {
        val ends = listOf(n to option.direction, other to option.direction.opposite)
        ends.firstNotNullOfOrNull { (end, dir) -> limitError(campaignId, m, end.id, end.type, "${end.code} ${end.title}", option.edgeType, dir) }
            ?.let { throw BadRequest(it) }
        ends.forEach { (end, dir) -> m.slotFor(end.type, option.edgeType, dir)?.let { clear(campaignId, end, it) } }
        graph.createLink(campaignId, n.id, option.edgeType, option.direction, other.id)
    }

    fun unlink(campaignId: UUID, n: Node, option: LinkOption, other: Node) =
        graph.deleteLink(campaignId, n.id, option.edgeType, option.direction, other.id)

    /** Empties [slot] on [n], e.g. Location set to "— Pool —". */
    fun clear(campaignId: UUID, n: Node, slot: LinkSlot) = slot.options.forEach { graph.deleteLinks(campaignId, n.id, it.edgeType, it.direction) }

    /** "Add new…" choices, minus those whose link type is already full on this node. */
    fun quickCreateOptions(campaignId: UUID, m: ResolvedModule, n: Node): List<QuickCreateOption> =
        m.quickCreate(n.type).filter { limitError(campaignId, m, n, it.option) == null }
}
