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
import tablekeeper.modules.PrintSummaryDef
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.web.Templates
import java.io.ByteArrayOutputStream

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

class PrintService(private val graph: GraphService, private val templates: Templates) {

    /**
     * Everything a print template may use. Audience filtering happens HERE,
     * so templates can't accidentally leak GM-only material onto player handouts.
     */
    fun model(campaign: Campaign, module: ResolvedModule, node: Node, audience: Audience): Map<String, Any?> {
        if (!audience.canSee(node.visibility)) throw HiddenFromAudience()

        val fields = PropsForm.fields(module.propsSchema(node.type), node.props).filterNot { it.hidden }
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
            "linkedProps" to linkedProps(campaign, module, node, audience).filter { it.sources.isNotEmpty() },
            "groups" to groups,
            "facts" to facts,
            "audience" to audience.name.lowercase(),
        )
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

    /** HTML -> PDF. jsoup tidies Pebble output into a DOM, so templates don't need to be strict XHTML. */
    fun pdf(html: String): ByteArray {
        val doc = W3CDom().fromJsoup(Jsoup.parse(html))
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
