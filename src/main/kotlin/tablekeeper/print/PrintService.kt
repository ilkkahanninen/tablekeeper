package tablekeeper.print

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import com.openhtmltopdf.svgsupport.BatikSVGDrawer
import org.jsoup.Jsoup
import org.jsoup.helper.W3CDom
import tablekeeper.core.Audience
import tablekeeper.core.Campaign
import tablekeeper.core.Direction
import tablekeeper.core.GraphService
import tablekeeper.core.Link
import tablekeeper.core.Node
import tablekeeper.core.NodeRef
import tablekeeper.modules.FormField
import tablekeeper.map.MapService
import tablekeeper.modules.LinkOption
import tablekeeper.modules.PacketDef
import tablekeeper.modules.PrintSummaryDef
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.views.ModuleViews
import tablekeeper.web.Templates
import java.io.ByteArrayOutputStream
import java.util.UUID

class HiddenFromAudience : RuntimeException("This node is GM-only and can't be printed for players")

/**
 * [details]: chosen options with their "x-enumDescriptions" text, e.g. Fragile → "The resource will be destroyed…".
 * [multi]: several options can be chosen (checkboxes), so cards list them in their own section.
 */
data class PropLine(val name: String, val label: String, val value: String,
                    val details: List<PropDetail> = emptyList(), val multi: Boolean = false)
data class PropDetail(val option: String, val text: String)
/** [summary]: the type's `printSummary`, e.g. "Kill d8 · Brutal, Reload"; empty if it has none. */
/** [children]: what this node has, when the card's type nests it (`printNested`), e.g. a minor location's contents. */
data class PrintRef(val code: String, val title: String, val typeLabel: String, val summary: String = "",
                    val children: List<PrintRef> = emptyList())
data class LinkGroup(val label: String, val items: List<PrintRef>)
/** One linked node's values for a linked prop, e.g. Red Market (L1): Desolate, Occult. */
data class LinkedPropSource(val id: java.util.UUID, val code: String, val title: String, val values: List<String>)
/** A linked prop, kept per linked node: a delve's domains shift from one landmark's to the other's. */
data class LinkedPropValue(val name: String, val label: String, val via: String, val sources: List<LinkedPropSource>)
data class PrintFact(val code: String, val title: String, val body: String, val visibility: String)

class PrintService(private val graph: GraphService, private val templates: Templates, private val maps: MapService) {

    /**
     * Everything a print template may use. Audience filtering happens HERE,
     * so templates can't accidentally leak GM-only material onto player handouts.
     */
    fun model(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience, withPacket: Boolean = true): Map<String, Any?> {
        if (!audience.canSee(node.visibility)) throw HiddenFromAudience()

        val all = PropsForm.fields(module.propsSchema(node.type), node.props).filterNot { it.hidden }
        // A ticked checkbox with an "x-trueLabel" (e.g. "dead") is a flag shown next to the title, not a row
        val flags = all.filter { it.kind == "bool" && it.trueLabel != null && it.value == true }.map { it.text }
        val fields = all.filterNot { it.kind == "bool" && it.trueLabel != null }
        val props = PropsForm.rows(fields).flatMap { row ->
            if (row.label == null) row.fields.mapNotNull(::propLine)
            // "x-row" fields print as one line, e.g. "Beat 1: Major · Find the ledger"
            else listOfNotNull(row.fields.map { it.text }.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                ?.let { PropLine(row.fields.first().name, row.label, it.joinToString(" · ")) })
        }

        val visible = graph.links(campaign.id, node.id).filter { audience.canSee(it.other.visibility) }
        val (factLinks, otherLinks) = visible.partition { module.isA(it.other.type, "core.fact") }

        val groups = otherLinks
            .groupBy { link ->
                val e = module.edgeType(link.type)
                module.printSummary(link.other.type)?.group ?: when {
                    e == null -> link.type
                    link.direction == Direction.OUT -> e.label
                    else -> e.inverseLabel
                }
            }
            .map { (label, links) ->
                LinkGroup(label, links.map { link ->
                    ref(campaign, module, link.other).copy(children = nested(campaign, module, node, link, audience))
                }.distinctBy { it.code })
            }

        val facts = factLinks.map { it.other }.distinctBy { it.id }.mapNotNull { graph.getNode(campaign.id, it.id) }
            .map { PrintFact(it.code, it.title, it.body, it.visibility) }

        return mapOf(
            "campaign" to campaign,
            "node" to node,
            "typeLabel" to module.label(node.type),
            "props" to props,
            "flags" to flags,
            "linkedProps" to linkedProps(campaign, module, node, audience).filter { it.sources.isNotEmpty() },
            "groups" to groups,
            "facts" to facts,
            "audience" to audience.name.lowercase(),
        ) + (module.packet(node.type)?.takeIf { withPacket }?.let { packet(campaign, module, node, it, audience) } ?: emptyMap())
    }

    /**
     * A session packet (the type's `packet` setting): the whole map with the selected places highlighted, module views
     * over the selected nodes (e.g. Inspiration for the selected characters), and print models of the other selected
     * nodes plus the `between` ones (delves between selected landmarks), grouped by type in the campaign-home order.
     */
    private fun packet(campaign: Campaign, module: ResolvedModule, node: Node, def: PacketDef, audience: Audience): Map<String, Any?> {
        val nodes = graph.listNodesWithProps(campaign.id).filter { audience.canSee(it.visibility) }
        val byId = nodes.associateBy { it.id }
        val edges = graph.listEdges(campaign.id)
        fun linked(id: UUID, option: LinkOption): List<UUID> = edges.filter { it.type == option.edgeType }.mapNotNull { e ->
            if (option.direction == Direction.OUT) e.toId.takeIf { e.fromId == id } else e.fromId.takeIf { e.toId == id }
        }.filter { it in byId }

        val rel = module.linkOptions(node.type).first { it.key == def.rel }
        val selected = linked(node.id, rel).mapNotNull { byId[it] }
        val selectedIds = selected.map { it.id }.toSet()
        val between = def.between.flatMap { b ->
            val via = module.linkOptions(b.type).first { it.key == b.rel }
            Packet.between(selectedIds, nodes.filter { module.isA(it.type, b.type) }.map { it.id }) { linked(it, via) }
        }.mapNotNull { byId[it] }
        // A node "inside" another via its type's context link, e.g. a minor location inside its landmark
        fun parentOf(id: UUID): UUID? = byId[id]?.let { n -> module.context(n.type)?.let { linked(id, it).firstOrNull() } }
        val around = def.around.flatMap { a ->
            val via = module.linkOptions(a.type).first { it.key == a.rel }
            Packet.around(selectedIds, nodes.filter { module.isA(it.type, a.type) }.map { it.id }, { linked(it, via) }, ::parentOf)
        }.mapNotNull { byId[it] }

        val views = module.views.map { ModuleViews.build(module, it, selected) }.filter { it.nodes.isNotEmpty() }
        val inViews = views.flatMap { v -> v.nodes.map { it.node.id } }.toSet()
        val sections = (selected.filter { it.id !in inViews } + between + around).distinctBy { it.id }
            .groupBy { it.type }.entries
            .sortedWith(compareBy({ module.overviewRank(it.key) }, { module.label(it.key) }))
            .map { (type, ofType) ->
                // Deepest last, e.g. landmarks by tier (the way down to the Heart); ones without a depth (Rogue) after; then by title
                val ordered = ofType.sortedWith(compareBy({ module.depthOf(type, it.props)?.depth ?: Double.MAX_VALUE }, { it.title }))
                PacketSection(type, module.plural(type), ordered.map { place ->
                    val m = model(campaign, module, place, audience, withPacket = false)
                    // Links back to this session ("featured in S1") say nothing inside its own packet, and a list whose
                    // nodes the linked props already name (a delve's landmarks, in its Tier and Domains rows) repeats them
                    @Suppress("UNCHECKED_CAST")
                    val shown = (m["linkedProps"] as List<LinkedPropValue>).flatMap { lp -> lp.sources.map { it.code } }.toSet()
                    @Suppress("UNCHECKED_CAST")
                    val groups = (m["groups"] as List<LinkGroup>)
                        .map { g -> g.copy(items = g.items.filter { it.code != node.code }) }
                        .filter { g -> g.items.isNotEmpty() && !g.items.all { it.code in shown } }
                    m + ("groups" to groups)
                })
            }
        return mapOf("map" to maps.view(campaign, module, highlight = selectedIds), "views" to views, "sections" to sections)
    }

    /** Module `linkedProps` for [node]: a prop's values per linked node the audience can see. */
    fun linkedProps(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): List<LinkedPropValue> {
        val rules = module.linkedProps(node.type)
        if (rules.isEmpty()) return emptyList()
        val links = graph.links(campaign.id, node.id).filter { audience.canSee(it.other.visibility) }
        return rules.map { rule ->
            val sources = links
                .filter { it.type == rule.option.edgeType && it.direction == rule.option.direction }
                .distinctBy { it.other.id }
                .map { link ->
                    val raw = graph.getNode(campaign.id, link.other.id)?.props?.get(rule.prop)
                    val values = (if (raw is List<*>) raw else listOf(raw)).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
                    LinkedPropSource(link.other.id, link.other.code, link.other.title, values)
                }
            LinkedPropValue(rule.prop, rule.label, rule.option.label, sources)
        }
    }

    fun html(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): String =
        templates.render(module.printTemplate(node.type) ?: "print/generic.peb", model(campaign, module, node, audience))

    /**
     * A node's print as PDF. A packet with `columns` > 1 renders its template twice: `part = "cover"` as is
     * (e.g. the map page), and `part = "columns"` as one long column on narrow pages, which [Imposition] then
     * places side by side on landscape sheets.
     */
    fun pdf(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): ByteArray {
        val columns = module.packet(node.type)?.columns ?: 1
        if (columns <= 1) return pdf(html(campaign, module, node, audience))
        val template = module.printTemplate(node.type) ?: "print/generic.peb"
        val model = model(campaign, module, node, audience)
        val cover = pdf(templates.render(template, model + ("part" to "cover")))
        val hasText = listOf("views", "sections").any { (model[it] as? List<*>)?.isNotEmpty() == true }
        val text = if (!hasText) null else pdf(
            templates.render(template, model + ("part" to "columns")),
            pageCss = "@page { size: ${Imposition.columnWidthMm(columns)}mm ${Imposition.columnHeightMm()}mm; margin: 0; }",
        )
        return Imposition.columns(cover, text, columns)
    }

    /**
     * HTML -> PDF. jsoup tidies Pebble output into a DOM, so templates don't need to be strict XHTML.
     * [pageCss] is appended last, so it overrides the template's own `@page` (used for narrow column pages).
     */
    fun pdf(html: String, pageCss: String? = null): ByteArray {
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
    private fun nested(campaign: Campaign, module: ResolvedModule, node: Node, link: Link, audience: Audience): List<PrintRef> {
        val key = "${link.type}:${link.direction.name.lowercase()}"
        val shows = module.printNested(node.type).filter { it.rel == key }.map { it.show }
        if (shows.isEmpty()) return emptyList()
        return graph.links(campaign.id, link.other.id)
            .filter { "${it.type}:${it.direction.name.lowercase()}" in shows && it.other.id != node.id && audience.canSee(it.other.visibility) }
            .map { ref(campaign, module, it.other) }
            .distinctBy { it.code }
    }

    private fun propLine(f: FormField): PropLine? {
        val chosen = (f.value as? List<*>)?.map { it.toString() } ?: listOf(f.value.toString())
        val details = chosen.mapNotNull { o -> f.hints[o]?.let { PropDetail(o, it) } }
        return f.text.takeIf { it.isNotBlank() }?.let { PropLine(f.name, f.label, it, details, multi = f.kind == "multi") }
    }

    private fun ref(campaign: Campaign, module: ResolvedModule, n: NodeRef): PrintRef {
        val parts = module.printSummary(n.type)?.parts.orEmpty()
        val summary = if (parts.isEmpty()) "" else graph.getNode(campaign.id, n.id)?.let { other ->
            val fields = PropsForm.fields(module.propsSchema(n.type), other.props).filterNot { it.hidden }.associateBy { it.name }
            parts.mapNotNull { part ->
                val values = PrintSummaryDef.PLACEHOLDER.findAll(part).associate { m ->
                    val f = fields[m.groupValues[1]]
                    m.value to (if (m.groupValues[2].isEmpty()) f?.text else f?.storedText).orEmpty()
                }
                // A part with nothing filled in is dropped, literal text included ("uses: {limitedUses}").
                if (values.values.all { it.isEmpty() }) null
                else PrintSummaryDef.PLACEHOLDER.replace(part) { values.getValue(it.value) }.replace(Regex("\\s+"), " ").trim()
            }.joinToString(" · ")
        }.orEmpty()
        return PrintRef(n.code, n.title, module.label(n.type), summary)
    }
}
