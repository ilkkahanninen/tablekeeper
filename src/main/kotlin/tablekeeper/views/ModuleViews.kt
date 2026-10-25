package tablekeeper.views

import tablekeeper.core.Node
import tablekeeper.modules.FormField
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.modules.ViewDef

/** A node on a module view, with its props as form fields (display text, labels, hints) by name, and its display title. */
data class ViewNode(val node: Node, val fields: Map<String, FormField>, val display: String = node.title)

/** View nodes sharing one value of a `groupBy` prop; [hint] is that value's "x-enumDescriptions" text, if any. */
data class ViewGroup(val value: String, val label: String, val hint: String?, val nodes: List<ViewNode>)

/** A module view's template model: its nodes, and for each `groupBy` prop those nodes grouped. */
data class ViewData(val view: ViewDef, val nodes: List<ViewNode>, val groups: Map<String, List<ViewGroup>>)

/**
 * Builds module views (e.g. Heart's Inspiration) from a list of nodes. Shared by the /v/{view} page (all of the
 * campaign's nodes) and session packets (only the selected ones), so both show the same thing.
 */
object ModuleViews {
    /** [view] over those of [nodes] whose type it lists. */
    fun build(module: ResolvedModule, view: ViewDef, nodes: List<Node>): ViewData {
        val viewNodes = nodes.filter { n -> view.types.any { module.isA(n.type, it) } }
            .map { n -> ViewNode(n, PropsForm.fields(module.propsSchema(n.type), n.props).associateBy { it.name }, module.displayTitle(n.type, n.title, n.props)) }
        return ViewData(view, viewNodes, view.groupBy.associateWith { groups(viewNodes, it) })
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
