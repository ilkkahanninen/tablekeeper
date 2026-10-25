package tablekeeper.web

import tablekeeper.core.CampaignGraph
import tablekeeper.core.Node
import tablekeeper.modules.FormField
import tablekeeper.modules.LinkOption
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import java.util.UUID

/** A column of a type's table on the campaign home: a prop, or links (an "@rel" column or a link slot). */
sealed interface OverviewColumn { val label: String }
data class PropColumn(val prop: String, override val label: String) : OverviewColumn
data class LinkColumn(val options: List<LinkOption>, override val label: String) : OverviewColumn

/** One node's row; [children] are nodes listed under it, e.g. a landmark's minor locations; [muted] greys it out (a dead NPC). */
data class OverviewRow(val id: UUID, val code: String, val title: String, val cells: List<String>,
                       val children: List<OverviewRow> = emptyList(), val muted: Boolean = false)
/** [creatable]: the type can be created (it isn't hidden by the game), so the table gets a Create button. */
data class OverviewSection(val typeId: String, val label: String, val columns: List<OverviewColumn>, val rows: List<OverviewRow>,
                           val creatable: Boolean = false)

/** The campaign home's tables. Pure: callers pass fields and linked titles, so it is testable without a database. */
object Overview {
    /**
     * The campaign home: a table per type, in the module's `overviewOrder`. A node whose type has a `context` link is
     * listed under that node instead, e.g. a minor location under its landmark.
     */
    fun sections(m: ResolvedModule, graph: CampaignGraph): List<OverviewSection> {
        val parent = graph.nodes.mapNotNull { n -> m.parent(graph, n)?.let { n.id to it.id } }.toMap()
        val columns = graph.nodes.map { it.type }.distinct().associateWith { columns(m, it) }
        fun row(n: Node): OverviewRow = OverviewRow(
            n.id, n.code, m.displayTitle(n),
            cells(columns.getValue(n.type), m.fields(n.type, n.props)) { o -> graph.linkedNodes(n.id, o.edgeType, o.direction).map(m::displayTitle) },
            children = graph.nodes.filter { parent[it.id] == n.id }.map(::row),
            muted = m.overviewMuted(n.type)?.let { n.props[it] == true } == true,
        )
        return graph.nodes.filter { it.id !in parent }.groupBy { it.type }
            .map { (type, ofType) ->
                val ordered = sorted(ofType, m.overviewSort(type), { it.props }, { it.title })
                OverviewSection(type, m.plural(type), columns.getValue(type), ordered.map(::row), creatable = m.nodeTypes.any { it.id == type })
            }
            .sortedWith(compareBy({ m.overviewRank(it.typeId) }, { it.label }))
    }

    /** The type's `overview` columns, then one column per link slot (e.g. Location). Header = schema title / link label. */
    fun columns(m: ResolvedModule, typeId: String): List<OverviewColumn> {
        val fields = PropsForm.fields(m.propsSchema(typeId), emptyMap()).associateBy { it.name }
        return m.overview(typeId).mapNotNull { spec ->
            if (spec.startsWith("@")) m.linkOption(typeId, spec.drop(1))
                ?.let { LinkColumn(listOf(it), it.label.replaceFirstChar(Char::uppercase)) }
            else fields[spec]?.let { PropColumn(spec, it.label) }
        } + m.linkSlots(typeId).map { LinkColumn(it.options, it.label) }
    }

    /**
     * Orders a type's rows by its `overviewSort` prop: stored values compared as numbers when both are, else as text
     * (ISO dates sort right), rows without a value last; ties and unsorted types by title.
     */
    fun <T> sorted(items: List<T>, sort: Pair<String, Boolean>?, props: (T) -> Map<String, Any?>, title: (T) -> String): List<T> {
        if (sort == null) return items.sortedBy(title)
        val (prop, descending) = sort
        val byValue = Comparator<Any> { a, b ->
            if (a is Number && b is Number) a.toDouble().compareTo(b.toDouble()) else a.toString().compareTo(b.toString())
        }.let { if (descending) it.reversed() else it }
        return items.sortedWith(compareBy<T, Any?>(nullsLast(byValue)) { props(it)[prop]?.takeIf { v -> v.toString().isNotBlank() } }.thenBy(title))
    }

    /** Cell text per column: a prop's display text (empty while hidden or not in [fields]), or the linked titles joined. */
    fun cells(columns: List<OverviewColumn>, fields: Map<String, FormField>, linked: (LinkOption) -> List<String>): List<String> =
        columns.map { col ->
            when (col) {
                is PropColumn -> fields[col.prop]?.takeUnless { it.hidden }?.text.orEmpty()
                is LinkColumn -> col.options.flatMap(linked).joinToString(", ")
            }
        }
}
