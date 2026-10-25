package tablekeeper.views

import tablekeeper.core.Direction
import tablekeeper.core.EdgeRef
import tablekeeper.core.Node
import tablekeeper.modules.LinkOption
import tablekeeper.modules.Parts
import tablekeeper.modules.FormField
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.modules.ViewDef
import java.util.UUID

/**
 * A node on a module view, with its props as form fields (display text, labels, hints) by name, its display title,
 * and its [linked] nodes for the view's `links` (e.g. a character's fallouts).
 */
data class ViewNode(val node: Node, val fields: Map<String, FormField>, val display: String = node.title,
                    val linked: List<ViewLinkGroup> = emptyList())

/** Nodes linked one way, under a heading: the linked type's print-summary group ("Fallouts"), or the link label. */
data class ViewLinkGroup(val label: String, val items: List<ViewLinkItem>)
data class ViewLinkItem(val code: String, val title: String, val summary: String)

/** View nodes sharing one value of a `groupBy` prop; [hint] is that value's "x-enumDescriptions" text, if any. */
data class ViewGroup(val value: String, val label: String, val hint: String?, val nodes: List<ViewNode>)

/** A module view's template model: its nodes, and for each `groupBy` prop those nodes grouped. */
data class ViewData(val view: ViewDef, val nodes: List<ViewNode>, val groups: Map<String, List<ViewGroup>>)

/**
 * Builds module views (e.g. Heart's Inspiration) from a list of nodes. Shared by the /v/{view} page (all of the
 * campaign's nodes) and session packets (only the selected ones), so both show the same thing.
 */
object ModuleViews {
    /**
     * [view] over those of [nodes] whose type it lists. [edges] and [allNodes] (the campaign's) give the view's `links`,
     * e.g. each character's fallouts with their print summary.
     */
    fun build(module: ResolvedModule, view: ViewDef, nodes: List<Node>, edges: List<EdgeRef> = emptyList(), allNodes: List<Node> = nodes): ViewData {
        val byId = allNodes.associateBy { it.id }
        val viewNodes = nodes.filter { n -> view.types.any { module.isA(n.type, it) } }.map { n ->
            ViewNode(
                n, PropsForm.fields(module.propsSchema(n.type), n.props).associateBy { it.name }, module.displayTitle(n.type, n.title, n.props),
                linked = view.links.mapNotNull { key -> module.linkOptions(n.type).firstOrNull { it.key == key } }
                    .mapNotNull { option -> linkGroup(module, option, linkedTo(n.id, option, edges).mapNotNull { byId[it] }) },
            )
        }
        return ViewData(view, viewNodes, view.groupBy.associateWith { groups(viewNodes, it) })
    }

    private fun linkedTo(id: UUID, option: LinkOption, edges: List<EdgeRef>): List<UUID> = edges.filter { it.type == option.edgeType }.mapNotNull { e ->
        if (option.direction == Direction.OUT) e.toId.takeIf { e.fromId == id } else e.fromId.takeIf { e.toId == id }
    }

    private fun linkGroup(module: ResolvedModule, option: LinkOption, others: List<Node>): ViewLinkGroup? {
        if (others.isEmpty()) return null
        val sorted = others.sortedBy { it.title }
        val label = module.printSummary(sorted.first().type)?.group ?: option.label.replaceFirstChar(Char::uppercase)
        return ViewLinkGroup(label, sorted.map { o ->
            val summary = module.printSummary(o.type)?.parts?.let { parts ->
                Parts.fill(parts, PropsForm.fields(module.propsSchema(o.type), o.props).filterNot { it.hidden }.associateBy { it.name }, " · ")
            }.orEmpty()
            ViewLinkItem(o.code, module.displayTitle(o.type, o.title, o.props), summary)
        })
    }

    /** [nodes] grouped by their value of [prop], in the prop's option order; nodes without one last, as "Not set". */
    fun groups(nodes: List<ViewNode>, prop: String): List<ViewGroup> {
        fun values(n: ViewNode): List<String> = when (val v = n.fields[prop]?.value) {
            is List<*> -> v.map { it.toString() }
            else -> listOfNotNull(v?.toString()?.takeIf { it.isNotBlank() })
        }
        val field = nodes.firstNotNullOfOrNull { it.fields[prop] } ?: return emptyList()
        val order = field.options.withIndex().associate { (i, o) -> o to i }
        val groups = nodes.flatMap(::values).distinct().sortedBy { order[it] ?: Int.MAX_VALUE }.map { value ->
            ViewGroup(value, field.labels[value] ?: value, field.hints[value], nodes.filter { value in values(it) })
        }
        val unset = nodes.filter { values(it).isEmpty() }
        return groups + listOfNotNull(unset.takeIf { it.isNotEmpty() }?.let { ViewGroup("", "Not set", null, it) })
    }
}
