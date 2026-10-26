package tablekeeper.print

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import tablekeeper.core.Audience
import tablekeeper.core.Campaign
import tablekeeper.core.CampaignGraph
import tablekeeper.core.GraphService
import tablekeeper.core.HiddenFromAudience
import tablekeeper.core.Link
import tablekeeper.core.Mentions
import tablekeeper.core.Node
import tablekeeper.core.NodeRef
import tablekeeper.core.Notes
import tablekeeper.map.MapService
import tablekeeper.map.MapView
import tablekeeper.modules.FormField
import tablekeeper.modules.PacketDef
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.modules.sortedByProp
import tablekeeper.templates.Templates
import tablekeeper.views.ModuleViews
import tablekeeper.views.ViewData
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * [details]: chosen options with their "x-enumDescriptions" text, e.g. Fragile → "The resource will be destroyed…".
 * [multi]: several options can be chosen (checkboxes), so cards list them in their own section.
 * [html]: a long-text ("textarea") field as HTML, formatted like notes ([Notes.html]).
 * [items]: a "list" field's items (e.g. a delve's events) as HTML, formatted like notes ([Notes.html]); printed with a tick box each.
 * [track]: an "x-track" field's number (e.g. resistance 12), printed as that many boxes to tick off.
 */
data class PropLine(val name: String, val label: String, val value: String,
                    val details: List<PropDetail> = emptyList(), val multi: Boolean = false,
                    val items: List<String> = emptyList(), val track: Int? = null, val html: String = "")
data class PropDetail(val option: String, val text: String)
/**
 * [summary]: the type's `printSummary` as HTML (formatted like notes), e.g. "Kill d8 · Brutal, Reload"; empty if it has none.
 * [children]: what this node has, when the card's type nests it (`printNested`), e.g. a minor location's contents.
 */
data class PrintRef(val code: String, val title: String, val typeLabel: String, val summary: String = "",
                    val children: List<PrintRef> = emptyList())
data class LinkGroup(val label: String, val items: List<PrintRef>)
/** One linked node's values for a linked prop, e.g. Red Market (L1): Desolate, Occult. */
data class LinkedPropSource(val id: UUID, val type: String, val code: String, val title: String, val values: List<String>)
/** A linked prop, kept per linked node: a delve's domains shift from one landmark's to the other's. */
data class LinkedPropValue(val name: String, val label: String, val via: String, val sources: List<LinkedPropSource>)
/** [notes]: the fact's text as HTML (formatting and `[[mentions]]`, see [Notes.inline]). */
/** Values a node takes from the nodes it links one way (module `linkedProps`): a column per prop, a row per linked node. */
data class LinkedPropTable(val columns: List<String>, val rows: List<LinkedPropRow>)
data class LinkedPropRow(val id: UUID, val type: String, val code: String, val title: String, val cells: List<String>)

/**
 * Linked props through the same link as one table each, e.g. a delve's Tier and Domains, one row per landmark.
 * A node without values for a prop gets an empty cell; a link with no linked nodes gives no table.
 */
fun linkedPropTables(linkedProps: List<LinkedPropValue>): List<LinkedPropTable> =
    linkedProps.groupBy { it.via }.values.mapNotNull { props ->
        val sources = props.flatMap { it.sources }.distinctBy { it.id }
        if (sources.isEmpty()) return@mapNotNull null
        LinkedPropTable(props.map { it.label }, sources.map { s ->
            LinkedPropRow(s.id, s.type, s.code, s.title,
                props.map { p -> p.sources.firstOrNull { it.id == s.id }?.values.orEmpty().joinToString(", ") })
        })
    }

data class PrintFact(val code: String, val title: String, val body: String, val visibility: String, val notes: String = "")

/** The previous node's recap on a packet cover ("Last time"), e.g. S1's "What happened". */
data class PacketRecap(val code: String, val title: String, val text: String, val notes: String)

/** A session packet's extra template variables (the type's `packet` setting). */
data class PacketModel(val map: MapView, val views: List<ViewData>, val sections: List<PacketSection>, val previous: PacketRecap? = null)

/**
 * Everything a print template may use, already filtered for the audience. Section items in a packet are these too,
 * so templates read `item.props`, `item.groups`…; a node's own print gets them as top-level variables ([asMap]).
 * [flags]: ticked checkboxes with an "x-trueLabel" (e.g. "dead"), shown next to the title instead of as rows.
 * [notes]: the node's notes as HTML: formatting, and `[[mentions]]` of nodes the audience may see in bold ([Notes.html]).
 */
data class PrintModel(
    val campaign: Campaign,
    val node: Node,
    val typeLabel: String,
    val displayTitle: String,
    val props: List<PropLine>,
    val flags: List<String>,
    val linkedProps: List<LinkedPropValue>,
    val groups: List<LinkGroup>,
    val facts: List<PrintFact>,
    val audience: String,
    val notes: String = "",
    val packet: PacketModel? = null,
) {
    /** [linkedProps] as tables: a row per linked node, a column per prop (e.g. a delve's landmarks with Tier and Domains). */
    val linkedPropTables: List<LinkedPropTable> get() = linkedPropTables(linkedProps)

    fun asMap(): Map<String, Any?> = mapOf(
        "campaign" to campaign, "node" to node, "typeLabel" to typeLabel, "displayTitle" to displayTitle,
        "props" to props, "flags" to flags, "linkedProps" to linkedProps, "linkedPropTables" to linkedPropTables,
        "groups" to groups, "facts" to facts,
        "audience" to audience, "notes" to notes,
    ) + packet?.let { mapOf("map" to it.map, "views" to it.views, "sections" to it.sections, "previous" to it.previous) }.orEmpty()
}

/** Larger "x-track" values print as a plain number: a row that long wouldn't fit a card. */
private const val MAX_TRACK = 40

class PrintService(private val graph: GraphService, private val templates: Templates) {

    /**
     * Everything a print template may use. Audience filtering happens HERE,
     * so templates can't accidentally leak GM-only material onto player handouts.
     */
    fun model(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): PrintModel =
        model(campaign, module, node, audience, graph.snapshot(campaign.id), withPacket = true)

    private fun model(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience,
                      all: CampaignGraph, withPacket: Boolean): PrintModel {
        if (!audience.canSee(node.visibility)) throw HiddenFromAudience()
        // Names as shown everywhere, e.g. "Thonal (Jori)"
        val names = module.displayTitles(all.nodes)
        val mentions = Mentions.of(all.nodes.filter { audience.canSee(it.visibility) }, module::displayTitle)

        val fields = PropsForm.fields(module.propsSchema(node.type), node.props).filterNot { it.hidden }
        val (flagFields, rowFields) = fields.partition { it.kind == "bool" && it.trueLabel != null }
        val props = PropsForm.rows(rowFields).flatMap { row ->
            if (row.label == null) row.fields.mapNotNull { propLine(it, mentions) }
            // "x-row" fields print as one line, e.g. "Beat 1: Major · Find the ledger"
            else listOfNotNull(row.fields.map { it.text }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { PropLine(row.fields.first().name, row.label, it.joinToString(" · ")) })
        }

        val links = graph.links(campaign.id, node.id).filter { audience.canSee(it.other.visibility) }
        val (factLinks, otherLinks) = links.partition { module.isA(it.other.type, "core.fact") }
        val groups = module.groupLinks(node.type, otherLinks).map { (label, ls) ->
            LinkGroup(label, ls.map { link ->
                ref(module, all, link.other, names, mentions).copy(children = nested(campaign, module, all, node, link, audience, names, mentions))
            }.distinctBy { it.code })
        }
        val facts = factLinks.map { it.other }.distinctBy { it.id }.mapNotNull { all.byId[it.id] }
            .map { PrintFact(it.code, names[it.id] ?: it.title, it.body, it.visibility, Notes.inline(it.body, mentions)) }

        return PrintModel(
            campaign, node, module.label(node.type), module.displayTitle(node),
            props = props,
            flags = flagFields.filter { it.value == true }.map { it.text },
            linkedProps = linkedProps(module, node, links, all, names).filter { it.sources.isNotEmpty() },
            groups = groups,
            facts = facts,
            audience = audience.name.lowercase(),
            notes = Notes.html(node.body, mentions),
            packet = module.packet(node.type)?.takeIf { withPacket }?.let { packet(campaign, module, node, it, audience, all) },
        )
    }

    /**
     * A session packet (the type's `packet` setting): the whole map with the selected places highlighted, module views
     * over the selected nodes (e.g. Inspiration for the selected characters), and print models of the other selected
     * nodes plus the `between` ones (delves between selected landmarks), grouped by type in the campaign-home order.
     */
    private fun packet(campaign: Campaign, module: ResolvedModule, node: Node, def: PacketDef, audience: Audience, all: CampaignGraph): PacketModel {
        val g = all.filter { audience.canSee(it.visibility) }
        val selection = Packet.select(module, node, def, g)
        val selected = selection.selected
        val views = module.views.map { ModuleViews.build(module, it, selected, g) }.filter { it.nodes.isNotEmpty() }
        val inViews = views.flatMap { v -> v.nodes.map { it.node.id } }.toSet()
        val sections = selection.all.filter { it.id !in inViews }
            .groupBy { it.type }.entries
            .sortedWith(compareBy({ module.overviewRank(it.key) }, { module.label(it.key) }))
            .map { (type, ofType) ->
                // Deepest last, e.g. landmarks by tier (the way down to the Heart); ones without a depth (Rogue) after; then by title
                val ordered = ofType.sortedWith(compareBy({ module.depthOf(type, it.props)?.depth ?: Double.MAX_VALUE }, { it.title }))
                PacketSection(type, module.plural(type), ordered.map { place ->
                    val m = model(campaign, module, place, audience, all, withPacket = false)
                    // Links back to this session ("featured in S1") say nothing inside its own packet, and a list whose
                    // nodes the linked props already name (a delve's landmarks, in its Tier and Domains rows) repeats them
                    val shown = m.linkedProps.flatMap { lp -> lp.sources.map { it.code } }.toSet()
                    m.copy(groups = m.groups
                        .map { grp -> grp.copy(items = grp.items.filter { it.code != node.code }) }
                        .filter { grp -> grp.items.isNotEmpty() && !grp.items.all { it.code in shown } })
                })
            }
        return PacketModel(MapService.view(all, module, highlight = selected.map { it.id }.toSet()), views, sections,
            previous = def.recap?.let { recap(module, node, it, g) })
    }

    /**
     * The [prop] text (e.g. "recap") of the node of [node]'s type just before it, in its `overviewSort` order read
     * oldest first (sessions by date); null if there's none or it's empty.
     */
    private fun recap(module: ResolvedModule, node: Node, prop: String, g: CampaignGraph): PacketRecap? {
        val sort = module.overviewSort(node.type)?.let { it.first to false }
        val ordered = sortedByProp(g.nodes.filter { it.type == node.type }, sort, { it.props }, { it.title })
        val previous = ordered.getOrNull(ordered.indexOfFirst { it.id == node.id } - 1) ?: return null
        val text = previous.props[prop]?.toString()?.trim().orEmpty().ifEmpty { return null }
        return PacketRecap(previous.code, module.displayTitle(previous), text, Notes.html(text, Mentions.of(g.nodes, module::displayTitle)))
    }

    /** Module `linkedProps` for [node], for its page: a prop's values per linked node, as the GM sees them. */
    fun linkedProps(campaign: Campaign, module: ResolvedModule, node: Node): List<LinkedPropValue> {
        if (module.linkedProps(node.type).isEmpty()) return emptyList()
        val all = graph.snapshot(campaign.id)
        return linkedProps(module, node, graph.links(campaign.id, node.id), all, module.displayTitles(all.nodes))
    }

    /** Module `linkedProps` for [node] from its (audience-filtered) [links]: a prop's values per linked node. */
    private fun linkedProps(module: ResolvedModule, node: Node, links: List<Link>, all: CampaignGraph, names: Map<UUID, String>): List<LinkedPropValue> =
        module.linkedProps(node.type).map { rule ->
            val sources = links.filter { rule.option.matches(it) }.distinctBy { it.other.id }.map { link ->
                val raw = all.byId[link.other.id]?.props?.get(rule.prop)
                val values = (if (raw is List<*>) raw else listOf(raw)).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                LinkedPropSource(link.other.id, link.other.type, link.other.code, names[link.other.id] ?: link.other.title, values)
            }
            LinkedPropValue(rule.prop, rule.label, rule.option.label, sources)
        }

    fun html(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): String =
        templates.render(template(module, node), model(campaign, module, node, audience).asMap())

    /**
     * A node's print as PDF. A packet with `columns` > 1 renders its template in parts: `part = "cover"` as is
     * (e.g. the map page), then `part = "columns"` once per section (`only` = "view:<id>" or "section:<type>", whose
     * heading the template leaves out) as one long column on narrow pages. [Imposition] places those side by side on
     * landscape sheets, each section from a new column, and draws the headings across their columns.
     */
    fun pdf(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): ByteArray {
        val columns = module.packet(node.type)?.columns ?: 1
        if (columns <= 1) return pdf(html(campaign, module, node, audience))
        val model = model(campaign, module, node, audience)
        val vars = model.asMap()
        val template = template(module, node)
        val cover = pdf(templates.render(template, vars + ("part" to "cover")))
        val pageCss = "@page { size: ${Imposition.columnWidthMm(columns)}mm ${Imposition.columnHeightMm()}mm; margin: 0; }"
        // Views are headed by their type's plural ("Characters" for Inspiration), sections by theirs
        val sections = model.packet?.let { p ->
            p.views.map { "view:${it.view.id}" to module.plural(it.view.types.first()) } + p.sections.map { "section:${it.typeId}" to it.label }
        }.orEmpty()
        val parts = sections.map { (key, heading) ->
            Imposition.Part(heading, pdf(templates.render(template, vars + ("part" to "columns") + ("only" to key)), pageCss))
        }
        return Imposition.columns(cover, parts, columns)
    }

    /** The campaign map as a one-page PDF. */
    fun mapPdf(campaign: Campaign, module: ResolvedModule): ByteArray =
        pdf(templates.render("print/map.peb", mapOf("campaign" to campaign, "map" to MapService.view(graph.snapshot(campaign.id), module))))

    private fun template(module: ResolvedModule, node: Node) = module.printTemplate(node.type) ?: "print/generic.peb"

    /**
     * HTML -> PDF. jsoup tidies Pebble output into a DOM, so templates don't need to be strict XHTML.
     * [pageCss] is appended last, so it overrides the template's own `@page` (used for narrow column pages).
     */
    private fun pdf(html: String, pageCss: String? = null): ByteArray {
        val parsed = Jsoup.parse(html)
        pageCss?.let { parsed.head().appendElement("style").appendText(it) }
        val doc = W3CDom().fromJsoup(parsed)
        return ByteArrayOutputStream().use { out ->
            PdfRendererBuilder()
                .useFastMode()
                .useSVGDrawer(BatikSVGDrawer())
                .withW3cDocument(doc, "/")
                .toStream(out)
                .run()
            out.toByteArray()
        }
    }

    /** Nodes linked to [link]'s far end via the card type's `printNested` "show" rel, e.g. a minor location's contents. */
    private fun nested(campaign: Campaign, module: ResolvedModule, all: CampaignGraph, node: Node, link: Link,
                       audience: Audience, names: Map<UUID, String>, mentions: Mentions): List<PrintRef> {
        val shows = module.printNested(node.type).filter { it.rel == link.key }.map { it.show }
        if (shows.isEmpty()) return emptyList()
        return graph.links(campaign.id, link.other.id)
            .filter { it.key in shows && it.other.id != node.id && audience.canSee(it.other.visibility) }
            .map { ref(module, all, it.other, names, mentions) }
            .distinctBy { it.code }
    }

    private fun propLine(f: FormField, mentions: Mentions): PropLine? {
        val chosen = (f.value as? List<*>)?.map { it.toString() } ?: listOf(f.value.toString())
        val details = chosen.mapNotNull { o -> f.hints[o]?.let { PropDetail(o, it) } }
        return f.text.takeIf { it.isNotBlank() }?.let {
            PropLine(f.name, f.label, it, details, multi = f.kind == "multi", items = f.items.map { item -> Notes.html(item, mentions) },
                html = if (f.kind == "textarea") Notes.html(f.value.toString(), mentions) else "",
                track = if (f.track) it.toIntOrNull()?.takeIf { n -> n in 1..MAX_TRACK } else null)
        }
    }

    /** How a linked node is listed; its summary as HTML, formatted like notes (e.g. a fallout's description). */
    private fun ref(module: ResolvedModule, all: CampaignGraph, n: NodeRef, names: Map<UUID, String>, mentions: Mentions): PrintRef =
        PrintRef(n.code, names[n.id] ?: n.title, module.label(n.type),
            all.byId[n.id]?.let { Notes.inline(module.summary(n.type, it.props), mentions) }.orEmpty())
}
