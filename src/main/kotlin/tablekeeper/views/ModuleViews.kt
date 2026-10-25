package tablekeeper.views

import tablekeeper.core.CampaignGraph
import tablekeeper.core.Mentions
import tablekeeper.core.Node
import tablekeeper.core.Notes
import tablekeeper.modules.FormField
import tablekeeper.modules.LinkOption
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.modules.ViewDef

/**
 * A node on a module view, with its props as form fields (display text, labels, hints) by name, its display title,
 * its [linked] nodes for the view's `links` (e.g. a character's fallouts), and its [notes] as HTML ([Notes.html], for print).
 */
data class ViewNode(val node: Node, val fields: Map<String, FormField>, val display: String = node.title,
                    val linked: List<ViewLinkGroup> = emptyList(), val notes: String = "")

/** Nodes linked one way, under a heading: the linked type's print-summary group ("Fallouts"), or the link label. */
data class ViewLinkGroup(val label: String, val items: List<ViewLinkItem>)
/** [summary]: the linked node's print summary as HTML, formatted like notes (e.g. a fallout's description). */
data class ViewLinkItem(val type: String, val code: String, val title: String, val summary: String)

/** View nodes sharing one value of a `groupBy` prop; [hint] is that value's "x-enumNotes" text, if any. */
data class ViewGroup(val value: String, val label: String, val hint: String?, val nodes: List<ViewNode>)

/** A module view's template model: its nodes, and for each `groupBy` prop those nodes grouped. */
data class ViewData(val view: ViewDef, val nodes: List<ViewNode>, val groups: Map<String, List<ViewGroup>>)

/**
 * Builds module views (e.g. Heart's Inspiration) from a list of nodes. Shared by the /v/{view} page (all of the
 * campaign's nodes) and session packets (only the selected ones), so both show the same thing.
 */
object ModuleViews {
    /**
     * [view] over those of [nodes] whose type it lists. [graph] (the campaign's) gives the view's `links`,
     * e.g. each character's fallouts with their print summary.
     */
    fun build(module: ResolvedModule, view: ViewDef, nodes: List<Node>, graph: CampaignGraph = CampaignGraph(nodes, emptyList())): ViewData {
        val mentions = Mentions.of(graph.nodes, module::displayTitle)
        val viewNodes = nodes.filter { n -> view.types.any { module.isA(n.type, it) } }.map { n ->
            ViewNode(
                n, PropsForm.fields(module.propsSchema(n.type), n.props).associateBy { it.name }, module.displayTitle(n),
                linked = view.links.mapNotNull { module.linkOption(n.type, it) }
                    .mapNotNull { option -> linkGroup(module, option, graph.linkedNodes(n.id, option.edgeType, option.direction), mentions) },
                notes = Notes.html(n.body, mentions),
            )
        }
        return ViewData(view, viewNodes, view.groupBy.associateWith { groups(viewNodes, it) })
    }

    private fun linkGroup(module: ResolvedModule, option: LinkOption, others: List<Node>, mentions: Mentions): ViewLinkGroup? {
        if (others.isEmpty()) return null
        val sorted = others.sortedBy { it.title }
        val label = module.printSummary(sorted.first().type)?.group ?: option.label.replaceFirstChar(Char::uppercase)
        return ViewLinkGroup(label, sorted.map { o -> ViewLinkItem(o.type, o.code, module.displayTitle(o), Notes.inline(module.summary(o.type, o.props), mentions)) })
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
            ViewGroup(value, field.labels[value] ?: value, field.notes[value], nodes.filter { value in values(it) })
        }
        val unset = nodes.filter { values(it).isEmpty() }
        return groups + listOfNotNull(unset.takeIf { it.isNotEmpty() }?.let { ViewGroup("", "Not set", null, it) })
    }
}
