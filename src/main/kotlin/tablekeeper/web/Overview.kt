package tablekeeper.web

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
data class OverviewSection(val typeId: String, val label: String, val columns: List<OverviewColumn>, val rows: List<OverviewRow>)

/** The campaign home's tables. Pure: callers pass fields and linked titles, so it is testable without a database. */
object Overview {
    /** The type's `overview` columns, then one column per link slot (e.g. Location). Header = schema title / link label. */
    fun columns(m: ResolvedModule, typeId: String): List<OverviewColumn> {
        val fields = PropsForm.fields(m.propsSchema(typeId), emptyMap()).associateBy { it.name }
        val options = m.linkOptions(typeId)
        return m.overview(typeId).mapNotNull { spec ->
            if (spec.startsWith("@")) options.firstOrNull { it.key == spec.drop(1) }
                ?.let { LinkColumn(listOf(it), it.label.replaceFirstChar(Char::uppercase)) }
            else fields[spec]?.let { PropColumn(spec, it.label) }
        } + m.linkSlots(typeId).map { LinkColumn(it.options, it.label) }
    }

    /** Cell text per column: a prop's display text (empty while hidden), or the linked titles joined. */
    fun cells(columns: List<OverviewColumn>, fields: Map<String, FormField>, linked: (LinkOption) -> List<String>): List<String> =
        columns.map { col ->
            when (col) {
                is PropColumn -> fields[col.prop]?.takeUnless { it.hidden }?.text.orEmpty()
                is LinkColumn -> col.options.flatMap(linked).joinToString(", ")
            }
        }
}
