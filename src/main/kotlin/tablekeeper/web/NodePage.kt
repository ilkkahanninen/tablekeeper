package tablekeeper.web

import tablekeeper.core.BadRequest
import tablekeeper.core.Campaign
import tablekeeper.core.GraphService
import tablekeeper.core.Mentions
import tablekeeper.core.Node
import tablekeeper.core.NodeRef
import tablekeeper.core.Notes
import tablekeeper.core.RandomTableService
import tablekeeper.modules.LinkOption
import tablekeeper.modules.Linker
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.print.Packet
import tablekeeper.print.PrintService
import java.util.UUID

// View models for the "Linked" sidebar
/** [edgeId] null: not an edge, e.g. a node whose notes mention this one ("Mentioned in"), so it can't be unlinked here. */
data class UiLink(val edgeId: UUID?, val otherId: UUID, val code: String, val title: String, val typeLabel: String, val visibility: String)
data class UiGroup(val label: String, val links: List<UiLink>)
/** A relation the Link box offers for a search result; [blocked] says why it's disabled (a link limit), if it is. */
data class RelationChoice(val option: LinkOption, val blocked: String?)
data class SearchResult(val id: UUID, val code: String, val title: String, val choices: List<RelationChoice>)
/** A checkbox list on a node page (module `checklists`): every node of some types, ticked when linked via [option]. */
data class ChecklistView(val label: String, val option: LinkOption, val items: List<ChecklistItem>)
data class ChecklistItem(val id: UUID, val code: String, val title: String, val checked: Boolean)

/** A create-row choice: [key] identifies the quick-create option, [label] is what the dropdown shows. */
data class CreateChoice(val key: String, val label: String)

/** One dropdown entry; [value] is "<rel>|<node id>". */
data class Choice(val value: String, val label: String, val selected: Boolean)
data class ChoiceGroup(val label: String, val choices: List<Choice>)
/** A node offered after typing `[[` in notes: [label] as in pickers, [title] what gets inserted. */
data class MentionChoice(val code: String, val label: String, val title: String)
/** Dropdown for a link slot, e.g. Location: places, then carriers. */
data class SlotView(val index: Int, val label: String, val groups: List<ChoiceGroup>)

/** The link option [key] ("located_at:in") of [n]'s type, or a 400. */
fun ResolvedModule.requireLinkOption(n: Node, key: String?): LinkOption =
    linkOption(n.type, key ?: throw BadRequest("Missing link type")) ?: throw BadRequest("Link type '$key' not allowed for ${n.type}")

/** Builds a node page's template model: form, Link box, slots, checklists and the Links sidebar. */
class NodePage(
    private val graph: GraphService,
    private val linker: Linker,
    private val tables: RandomTableService,
    private val print: PrintService,
) {
    fun model(c: Campaign, m: ResolvedModule, n: Node, errors: List<String> = emptyList()): Map<String, Any?> {
        val mentions = mentions(c, m)
        fun format(text: String) = Notes.html(text, mentions) { "/c/${c.id}/n/${it.id}" }
        return model(c, m, n, errors, ::format)
    }

    private fun model(c: Campaign, m: ResolvedModule, n: Node, errors: List<String>, format: (String) -> String) = mapOf(
        "campaign" to c,
        "module" to m,
        "node" to n,
        "typeLabel" to m.label(n.type),
        "displayTitle" to m.displayTitle(n),
        // Notes and long-text props as shown when not editing: formatting, and [[mentions]] as links to the nodes
        "notes" to format(n.body),
        "fieldViews" to PropsForm.fields(m.propsSchema(n.type), n.props).filter { it.kind == "textarea" || it.kind == "list" }
            .associate { it.name to format(it.value.toString()) },
        "rows" to PropsForm.rows(PropsForm.fields(m.propsSchema(n.type), n.props)),
        "linkOptions" to orderedLinkOptions(m, n),
        "slots" to slots(c, m, n),
        // Random buttons: field name -> its tables (each button asks the server for a roll)
        "random" to tables.forType(c.id, n.type),
        "checklists" to checklists(c, m, n),
        "quickCreate" to linker.quickCreateOptions(c.id, m, n).let { options ->
            // Just the type ("Delve"); the relation only where one type is offered through several
            val repeated = options.groupingBy { it.type.id }.eachCount().filterValues { it > 1 }.keys
            options.map { CreateChoice(it.key, if (it.type.id in repeated) "${it.type.label} · ${it.option.label}" else it.type.label) }
        },
        "linkedProps" to print.linkedProps(c, m, n),
        "groups" to linkGroups(c, m, n),
        "debrief" to debrief(c, m, n),
        "errors" to errors,
    )

    /**
     * The node's debrief (module `debrief`, e.g. a session's): its packet's characters, NPCs and items with the
     * listed fields, link slots and linked nodes, to update after play. Empty without a debrief or packet.
     */
    fun debrief(c: Campaign, m: ResolvedModule, n: Node): List<DebriefSection> {
        val defs = m.debrief(n.type)
        val packet = m.packet(n.type) ?: return emptyList()
        if (defs.isEmpty()) return emptyList()
        val graph = graph.snapshot(c.id)
        val selection = Packet.select(m, n, packet, graph)
        fun fields(node: Node, names: List<String>) = PropsForm.fields(m.propsSchema(node.type), node.props).filter { it.name in names }
        return defs.flatMap { def ->
            Debrief.members(m, def, selection, graph).groupBy { it.type }.entries
                .sortedBy { m.overviewRank(it.key) }
                .map { (type, nodes) ->
                    DebriefSection(m.plural(type), nodes.sortedBy { it.title }.map { node ->
                        val quick = m.quickCreate(node.type)
                        DebriefItem(
                            node, m.displayTitle(node), Debrief.rows(fields(node, def.fields), def.done),
                            slots = if (def.slots) slots(c, m, node) else emptyList(),
                            links = def.links.mapNotNull { l ->
                                val option = m.linkOption(node.type, l.rel) ?: return@mapNotNull null
                                val linked = graph.linkedNodes(node.id, option.edgeType, option.direction).sortedBy { it.title }
                                val labelType = l.create ?: linked.firstOrNull()?.type
                                DebriefLinks(
                                    labelType?.let { m.linkGroupLabel(option.edgeType, option.direction, it) } ?: option.label,
                                    option,
                                    quick.firstOrNull { it.type.id == l.create && it.option == option }?.key,
                                    linked.map { DebriefLinked(it, m.displayTitle(it), Debrief.rows(fields(it, l.fields), done = false)) },
                                    remove = l.remove,
                                )
                            },
                            muted = m.overviewMuted(node.type)?.let { node.props[it] == true } == true,
                        )
                    })
                }
        }
    }

    /**
     * Link box results for [q] through [rel] (empty = Any: every relation this node can take, most useful first),
     * filtered by the module's endpoint rules. A relation whose limit is full at either end is disabled, with the reason.
     */
    fun search(c: Campaign, m: ResolvedModule, n: Node, rel: String, q: String): List<SearchResult> {
        val options = if (rel.isEmpty()) orderedLinkOptions(m, n) else listOf(m.requireLinkOption(n, rel))
        // This node already full for an option (e.g. a delve with two landmarks): that option is disabled everywhere
        val ownFull = options.associateWith { linker.limitError(c.id, m, n, it) }
        val found = if (rel.isEmpty() && q.isEmpty()) emptyList() else graph.search(c.id, q, n.id, limit = 100)
            .filter { other -> options.any { m.acceptsOther(it, other.type) } }
            .take(20)
        val labels = pickerLabels(c, m, found)
        return found.map { other ->
            SearchResult(other.id, other.code, labels.getValue(other.id),
                options.filter { m.acceptsOther(it, other.type) }.take(3).map { o ->
                    // Far end already full (e.g. a delve with two landmarks) also disables the option
                    RelationChoice(o, ownFull[o] ?: linker.limitError(c.id, m, other.id, other.type, other.code, o.edgeType, o.direction.opposite))
                })
        }
    }

    /** Nodes for the `[[` picker in notes: title or code matches, labelled as in other pickers. */
    fun mentionChoices(c: Campaign, m: ResolvedModule, n: Node, q: String): List<MentionChoice> {
        val found = graph.search(c.id, q, n.id, limit = 8)
        val labels = pickerLabels(c, m, found)
        return found.map { MentionChoice(it.code, labels.getValue(it.id), it.title) }
    }

    /** The campaign's nodes by the names a `[[mention]]` may use. */
    private fun mentions(c: Campaign, m: ResolvedModule): Mentions = Mentions.of(graph.listNodesWithProps(c.id), m::displayTitle)

    /** Display titles of all the campaign's nodes, e.g. "Thonal (Jori)" (module `displayTitle`). */
    private fun displayTitles(c: Campaign, m: ResolvedModule): Map<UUID, String> = m.displayTitles(graph.listNodesWithProps(c.id))

    /**
     * Picker labels: "Butcher's Row (Red Market)": the title, plus the far end of the type's `context` link in
     * brackets if it has one. No type label: pickers show the code too, and its prefix already says the type.
     */
    private fun pickerLabels(c: Campaign, m: ResolvedModule, nodes: List<NodeRef>): Map<UUID, String> {
        val contexts = nodes.mapNotNull { m.context(it.type) }.toSet()
            .associateWith { graph.linkedTitles(c.id, it.edgeType, it.direction) }
        val names = displayTitles(c, m)
        return nodes.associate { n ->
            val context = m.context(n.type)?.let { contexts[it]?.get(n.id) }
            n.id to "${names[n.id] ?: n.title}${context?.let { " ($it)" }.orEmpty()}"
        }
    }

    /**
     * Link options for the Link box, most useful first: the type's quick-create relations, then other specific
     * ones, then catch-alls whose both ends accept anything ("relates to"). Slot relations have their own dropdown.
     */
    private fun orderedLinkOptions(m: ResolvedModule, n: Node): List<LinkOption> {
        val quick = m.quickCreate(n.type).map { it.option.key }.toSet()
        val inSlots = m.linkSlots(n.type).flatMap { it.options }.toSet()
        return (m.linkOptions(n.type) - inSlots).sortedBy { o ->
            val e = m.edgeType(o.edgeType)
            when {
                o.key in quick -> 0
                e != null && "*" in e.from && "*" in e.to -> 2
                else -> 1
            }
        }
    }

    /** Dropdowns for this node's link slots: one option group per link option, listing every allowed far end. */
    fun slots(c: Campaign, m: ResolvedModule, n: Node): List<SlotView> {
        val slots = m.linkSlots(n.type)
        if (slots.isEmpty()) return emptyList()
        val links = graph.links(c.id, n.id)
        val nodes = graph.listNodes(c.id)
        val labels = pickerLabels(c, m, nodes)
        return slots.mapIndexed { i, s ->
            SlotView(i, s.label, s.options.map { o ->
                val current = links.filter(o::matches).map { it.other.id }.toSet()
                ChoiceGroup(o.label.replaceFirstChar { it.uppercase() }, nodes
                    .filter { it.id != n.id && m.acceptsOther(o, it.type) }
                    .map { Choice("${o.key}|${it.id}", "${it.code} ${labels.getValue(it.id)}", it.id in current) })
            })
        }
    }

    /** The node's checklists, e.g. a session's landmarks and characters, ticked where already linked. */
    private fun checklists(c: Campaign, m: ResolvedModule, n: Node): List<ChecklistView> {
        val lists = m.checklists(n.type)
        if (lists.isEmpty()) return emptyList()
        val links = graph.links(c.id, n.id)
        val nodes = graph.listNodes(c.id)
        val names = displayTitles(c, m)
        return lists.map { (def, option) ->
            val linked = links.filter(option::matches).map { it.other.id }.toSet()
            ChecklistView(
                def.types.joinToString(", ") { m.label(it) }, option,
                nodes.filter { other -> def.types.any { m.isA(other.type, it) } }.sortedBy { it.title }
                    .map { ChecklistItem(it.id, it.code, names[it.id] ?: it.title, it.id in linked) },
            )
        }
    }

    /**
     * Links for the sidebar, slot links included so the place or carrier is one click away; then "Mentioned in":
     * nodes whose notes mention this one as `[[code]]`, `[[title]]` or `[[display title]]`.
     */
    private fun linkGroups(c: Campaign, m: ResolvedModule, n: Node): List<UiGroup> {
        val names = displayTitles(c, m)
        fun ui(edgeId: UUID?, other: NodeRef) = UiLink(edgeId, other.id, other.code, names[other.id] ?: other.title, m.label(other.type), other.visibility)
        val mentionedIn = graph.mentioning(c.id, n.id, listOf(n.code, n.title, m.displayTitle(n))).sortedBy { names[it.id] ?: it.title }
        return m.groupLinks(graph.links(c.id, n.id)).map { (label, links) -> UiGroup(label, links.map { ui(it.edgeId, it.other) }) } +
            listOfNotNull(mentionedIn.takeIf { it.isNotEmpty() }?.let { refs -> UiGroup("Mentioned in", refs.map { ui(null, it) }) })
    }
}
