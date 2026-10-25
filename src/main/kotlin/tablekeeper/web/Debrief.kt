package tablekeeper.web

import tablekeeper.core.CampaignGraph
import tablekeeper.core.Node
import tablekeeper.modules.DebriefDef
import tablekeeper.modules.FormField
import tablekeeper.modules.LinkOption
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.print.PacketSelection

/** One line of a debrief form: [fields] under [label] (an "x-row", e.g. "Beat 1") or a single field; [done] = offer Done (clear it). */
data class DebriefRow(val label: String?, val fields: List<FormField>, val done: Boolean)
/** A linked node on a debrief, e.g. a fallout, with its fields editable in place. */
data class DebriefLinked(val node: Node, val display: String, val rows: List<DebriefRow>)
/** A debrief node's links of one kind; [createKey] is the quick-create choice for adding one, if the module offers it; [remove] = items can be deleted. */
data class DebriefLinks(val label: String, val option: LinkOption, val createKey: String?, val items: List<DebriefLinked>, val remove: Boolean = false)
data class DebriefItem(val node: Node, val display: String, val rows: List<DebriefRow>, val slots: List<SlotView>,
                       val links: List<DebriefLinks>, val muted: Boolean)
/** A debrief list's nodes of one type, under its plural label ("NPCs"). */
data class DebriefSection(val label: String, val items: List<DebriefItem>)

/** Pure rules for a node's debrief (module `debrief`). */
object Debrief {
    /**
     * Nodes of [def]'s types in the packet ([selection]), or with [DebriefDef.slots] also those whose slot (Location)
     * points into the packet or at a node inside one: resources at a selected landmark, carried by an NPC there.
     */
    fun members(module: ResolvedModule, def: DebriefDef, selection: PacketSelection, graph: CampaignGraph): List<Node> {
        val inPacket = selection.all.map { it.id }.toSet()
        fun inside(n: Node) = n.id in inPacket || module.parent(graph, n)?.id in inPacket
        return graph.nodes.filter { n -> def.types.any { module.isA(n.type, it) } }.filter { n ->
            n.id in inPacket || def.slots && module.linkSlots(n.type).flatMap { it.options }
                .any { o -> graph.linkedNodes(n.id, o.edgeType, o.direction).any(::inside) }
        }
    }

    /** [fields] (schema order) as rows; labelled rows get Done when [done] and something is filled in. */
    fun rows(fields: List<FormField>, done: Boolean): List<DebriefRow> =
        PropsForm.rows(fields).map { r ->
            DebriefRow(r.label, r.fields, done && r.label != null && r.fields.any { it.text.isNotBlank() })
        }
}
