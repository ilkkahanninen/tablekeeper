package tablekeeper.modules

import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import tablekeeper.core.CampaignGraph
import tablekeeper.core.Direction
import tablekeeper.core.Link
import tablekeeper.core.Node
import tablekeeper.core.TableSeed
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A type's own map, resolved: the [option] to its places, their [shape], and the boolean prop that [highlight]s one. */
data class NodeMap(val option: LinkOption, val shape: String, val highlight: String?)

/** A field a random table can fill: [key] "core.npc:voice", [label] "NPC: Voice". */
data class FieldOption(val key: String, val label: String)

/** A game module with everything it extends: its own types and settings, and the inherited ones. */
class ResolvedModule(
    val id: String,
    val name: String,
    /** Every type with what it inherits already resolved (see [Overlay.resolve]). */
    internal val types: Map<String, NodeTypeDef>,
    private val creatable: List<NodeTypeDef>,
    val edgeTypes: List<EdgeTypeDef>,
    /** Module views, inherited ones included; a game overrides an inherited view by reusing its id. */
    val views: List<ViewDef> = emptyList(),
    /** Type ids first on the campaign home; the nearest module that declares an order wins. */
    val overviewOrder: List<String> = emptyList(),
    /** Starter random tables for new campaigns, inherited ones included. */
    val tables: List<TableSeed> = emptyList(),
    /** One-click generators (Create ▾), inherited ones included. */
    val generators: List<GeneratorDef> = emptyList(),
    /** Run once when a campaign of this game is created; the game's own, not inherited. */
    val starter: GeneratorDef? = null,
    /** The campaign map's page: "landscape" or "portrait" (the nearest module that sets it). */
    val mapOrientation: String = "landscape",
) {
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val schemaCache = ConcurrentHashMap<String, JsonSchema>()

    /** Types offered in "New…" menus, by label. */
    val nodeTypes: List<NodeTypeDef> = creatable.sortedBy { it.label.lowercase() }

    fun type(id: String): NodeTypeDef? = types[id]

    fun label(typeId: String): String = types[typeId]?.label ?: typeId

    fun plural(typeId: String): String = types[typeId]?.let { it.plural ?: it.label + "s" } ?: typeId

    /** A node's props as form fields by name, leaving out those hidden by "x-hideIf" / "x-showIf". */
    fun fields(typeId: String, props: Map<String, Any?>): Map<String, FormField> =
        PropsForm.fields(propsSchema(typeId), props).filterNot { it.hidden }.associateBy { it.name }

    /** A node's name as shown everywhere but its own title field: the type's `displayTitle`, else the title. */
    fun displayTitle(typeId: String, title: String, props: Map<String, Any?>): String {
        val parts = types[typeId]?.displayTitle ?: return title
        return Parts.fill(parts, fields(typeId, props), " ", extra = mapOf("title" to title)).ifEmpty { title }
    }

    fun displayTitle(node: Node): String = displayTitle(node.type, node.title, node.props)

    /** Props a type's `displayTitle` shows, e.g. a character's "player" in "Karg (Mira)": prints don't repeat them as rows. */
    fun displayTitleProps(typeId: String): Set<String> = types[typeId]?.displayTitle?.let { Parts.names(it) }.orEmpty() - "title"

    /** Display titles of [nodes] by id, for pages and prints that list many nodes. */
    fun displayTitles(nodes: List<Node>): Map<UUID, String> = nodes.associate { it.id to displayTitle(it) }

    /**
     * How a node is described where it's listed on others: its type's `printSummary` parts, e.g. "Kill d8 · Brutal"; "" if
     * none. [linked]: the nodes linked via a rel, as text (e.g. "Red Market (L4)"), for `{@rel}` parts; none by default.
     */
    fun summary(typeId: String, props: Map<String, Any?>, linked: (String) -> List<String> = { emptyList() }): String =
        fill(printSummary(typeId)?.parts.orEmpty(), typeId, props, linked)

    /** The type's `print.subtitle`, shown with a node's title on prints, e.g. where an NPC is; "" if none. */
    fun subtitle(typeId: String, props: Map<String, Any?>, linked: (String) -> List<String>): String =
        fill(types[typeId]?.print?.subtitle.orEmpty(), typeId, props, linked)

    /** The rels a type's subtitle shows, e.g. "located_at:out": their links aren't listed again on its prints. */
    fun subtitleRels(typeId: String): Set<String> = Parts.rels(types[typeId]?.print?.subtitle.orEmpty())

    private fun fill(parts: List<String>, typeId: String, props: Map<String, Any?>, linked: (String) -> List<String>): String =
        if (parts.isEmpty()) ""
        else Parts.fill(parts, fields(typeId, props), " · ", Parts.rels(parts).associate { "@$it" to linked(it).joinToString(", ") })

    fun edgeType(id: String): EdgeTypeDef? = edgeTypes.firstOrNull { it.id == id }

    /**
     * Heading for a link in a node's lists, on screen and in print: the linked type's print-summary group ("Fallouts",
     * "Equipment"), or on the `to` side of a `groupByType` edge its plural type label ("NPCs"), else the edge's label
     * seen from that node ("Is located at", "Has here"); always capitalised, as a heading.
     */
    fun linkGroupLabel(edgeTypeId: String, direction: Direction, otherType: String): String {
        printSummary(otherType)?.group?.let { return it }
        val e = edgeType(edgeTypeId) ?: return edgeTypeId
        val label = when {
            e.groupByType && direction == Direction.IN -> plural(otherType)
            direction == Direction.OUT -> e.label
            else -> e.inverseLabel
        }
        return label.replaceFirstChar { it.uppercase() }   // a heading: "Is located at" like "NPCs"
    }


    /** Sort key for those lists: edges in module order, and within a type-grouped edge, types in campaign-home order. */
    fun linkGroupRank(edgeTypeId: String, otherType: String): Pair<Int, Int> {
        val e = edgeTypes.indexOfFirst { it.id == edgeTypeId }.let { if (it < 0) edgeTypes.size else it }
        return e to if (edgeType(edgeTypeId)?.groupByType == true) overviewRank(otherType) else 0  // only matters where grouped
    }

    /**
     * A [typeId] node's [links] under their headings ([linkGroupLabel]): the page's Links box and print lists. Ordered by
     * the type's `linkOrder` where it names a heading, else in [linkGroupRank] order (at its "*").
     */
    fun groupLinks(typeId: String, links: List<Link>): Map<String, List<Link>> {
        val grouped = links
            .sortedWith(compareBy({ linkGroupRank(it.type, it.other.type).first }, { linkGroupRank(it.type, it.other.type).second }))
            .groupBy { linkGroupLabel(it.type, it.direction, it.other.type) }
        val order = linkOrder(typeId)
        if (order.isEmpty()) return grouped
        val rest = order.indexOf("*").let { if (it < 0) order.size else it }
        return grouped.entries.sortedBy { (label, _) -> order.indexOf(label).let { if (it < 0) rest else it } }.associate { it.key to it.value }
    }

    fun linkOrder(typeId: String): List<String> = types[typeId]?.linkOrder.orEmpty()

    /** Every heading a [typeId] node's links can be listed under, for checking `linkOrder`. */
    internal fun possibleLinkGroups(typeId: String): Set<String> = linkOptions(typeId).flatMap { o ->
        types.keys.filter { acceptsOther(o, it) }.map { linkGroupLabel(o.edgeType, o.direction, it) }
    }.toSet()

    /** Type inheritance check: isA("heart.landmark", "core.place") == true. "*" matches anything. */
    fun isA(typeId: String, target: String): Boolean {
        if (target == "*") return true
        var t: String? = typeId
        val seen = mutableSetOf<String>()
        while (t != null && seen.add(t)) {
            if (t == target) return true
            t = types[t]?.extends
        }
        return false
    }

    private fun matchesAny(typeId: String, targets: List<String>) = targets.any { isA(typeId, it) }

    /** Link options available on a node of [typeId], in both directions. */
    fun linkOptions(typeId: String): List<LinkOption> = edgeTypes.flatMap { e ->
        val symmetric = e.label == e.inverseLabel && e.from == e.to
        buildList {
            if (matchesAny(typeId, e.from)) add(LinkOption(e.id, Direction.OUT, e.label))
            if (!symmetric && matchesAny(typeId, e.to)) add(LinkOption(e.id, Direction.IN, e.inverseLabel))
        }
    }

    /** The link option [key] ("located_at:in") on a node of [typeId]; null if the type can't link that way. */
    fun linkOption(typeId: String, key: String?): LinkOption? = linkOptions(typeId).firstOrNull { it.key == key }

    /** Can a node of [otherType] sit at the far end of this link option? */
    fun acceptsOther(option: LinkOption, otherType: String): Boolean {
        val e = edgeType(option.edgeType) ?: return false
        val targets = if (option.direction == Direction.OUT) e.to else e.from
        return matchesAny(otherType, targets)
    }

    /** Props schema for a type; inherited if the type declares none. */
    fun propsSchema(typeId: String): JsonNode? = types[typeId]?.props

    /** The schema of one prop, e.g. "tier"; null if the type has no such prop. */
    internal fun propSchema(typeId: String, prop: String): JsonNode? = propsSchema(typeId)?.get("properties")?.get(prop)

    fun printTemplate(typeId: String): String? = types[typeId]?.print?.template

    /** "Add new…" choices on a node of [typeId]. Types this game hides are left out. */
    fun quickCreate(typeId: String): List<QuickCreateOption> = types[typeId]?.quickCreate.orEmpty().mapNotNull { q ->
        val type = creatable.firstOrNull { it.id == q.type } ?: return@mapNotNull null
        val option = linkOption(typeId, q.rel) ?: return@mapNotNull null
        QuickCreateOption(type, option).takeIf { acceptsOther(option, type.id) }
    }

    /** `linkLimits` for [edgeType] in [dir] on a node of [typeId]; null = unlimited. */
    fun linkLimit(typeId: String, edgeType: String, dir: Direction): Int? = types[typeId]?.linkLimits?.get(dir.key(edgeType))

    fun printSummary(typeId: String): PrintSummaryDef? = types[typeId]?.print?.summary

    fun printNested(typeId: String): List<PrintNestedDef> = types[typeId]?.print?.nested.orEmpty()

    /** The type's shape on the map; null = not drawn (no shape, or "none" to leave out an inherited one). */
    fun mapShape(typeId: String): String? = types[typeId]?.campaignMap?.shape?.takeIf { it != "none" }

    /** The type's icon on screen, e.g. "castle" for a landmark (an id in static/icons.svg); "circle" if none is set. */
    fun icon(typeId: String): String = types[typeId]?.icon ?: "circle"

    fun mapDepth(typeId: String): String? = types[typeId]?.campaignMap?.depth

    /** Is the type's name drawn on the campaign map? (Heart's delves aren't.) */
    fun mapLabel(typeId: String): Boolean = types[typeId]?.campaignMap?.label != false

    /**
     * A node's map depth from its type's `mapDepth` prop, with its guide label: an integer as itself ("Tier 2" from
     * the prop title), or an enum value through the prop's "x-depth" ("Tier 2" -> 2). No depth (e.g. "Rogue") = null.
     */
    fun depthOf(typeId: String, props: Map<String, Any?>): MapDepth? {
        val prop = mapDepth(typeId) ?: return null
        val schema = propSchema(typeId, prop)
        return when (val v = props[prop]) {
            is Number -> MapDepth(v.toDouble(), "${schema?.get("title")?.asText() ?: prop} ${v.toInt()}")
            is String -> schema?.get("x-depth")?.get(v)?.takeIf { it.isNumber }?.let { MapDepth(it.asDouble(), v) }
            else -> null
        }
    }

    /** The type's `mapBetween` link option, e.g. a delve's "connects" (to the landmarks it's drawn between). */
    fun mapBetween(typeId: String): LinkOption? = types[typeId]?.campaignMap?.between?.let { linkOption(typeId, it) }

    /** The type's own map (`map`): the link option to its places, their shape and highlight prop; null = its nodes have no map. */
    fun nodeMap(typeId: String): NodeMap? =
        types[typeId]?.map?.let { d -> linkOption(typeId, d.places)?.let { NodeMap(it, d.shape, d.highlight) } }

    fun generator(id: String): GeneratorDef? = generators.firstOrNull { it.id == id }

    /** The type's `context` link option, e.g. a minor location's "is part of" (its landmark). */
    fun context(typeId: String): LinkOption? = types[typeId]?.context?.let { linkOption(typeId, it) }

    /** The node [node] belongs to through its type's `context` link, e.g. a minor location's landmark; null if none in [graph]. */
    fun parent(graph: CampaignGraph, node: Node): Node? =
        context(node.type)?.let { graph.linkedNodes(node.id, it.edgeType, it.direction).firstOrNull() }

    /** Link slots (one-of-many dropdowns) on a node of [typeId]. */
    fun linkSlots(typeId: String): List<LinkSlot> = types[typeId]?.linkSlots.orEmpty().map { s ->
        LinkSlot(s.label, s.rels.mapNotNull { linkOption(typeId, it) })
    }

    /** The slot on a [typeId] node that holds [edgeType] links in [dir], if any. */
    fun slotFor(typeId: String, edgeType: String, dir: Direction): LinkSlot? =
        linkSlots(typeId).firstOrNull { s -> s.options.any { it.edgeType == edgeType && it.direction == dir } }

    /** Linked-prop rules for a node of [typeId]; label defaults to the prop name. */
    fun linkedProps(typeId: String): List<LinkedPropRule> = types[typeId]?.linkedProps.orEmpty().mapNotNull { l ->
        val option = linkOption(typeId, l.rel) ?: return@mapNotNull null
        LinkedPropRule(l.prop, l.label ?: l.prop.replaceFirstChar { it.uppercase() }, option)
    }

    fun view(id: String): ViewDef? = views.firstOrNull { it.id == id }

    /** Overview column specs for a type: prop names and "@rel" link columns. */
    fun overview(typeId: String): List<String> = types[typeId]?.overview?.columns.orEmpty()

    /** Checkbox lists on a type's page, with their link option resolved. */
    fun checklists(typeId: String): List<Pair<ChecklistDef, LinkOption>> = types[typeId]?.checklists.orEmpty()
        .mapNotNull { c -> linkOption(typeId, c.rel)?.let { c to it } }

    fun packet(typeId: String): PacketDef? = types[typeId]?.packet

    fun debrief(typeId: String): List<DebriefDef> = types[typeId]?.debrief.orEmpty()

    /** The type's `overviewSort`: the prop, and whether newest/largest first ("-date"). */
    fun overviewSort(typeId: String): Pair<String, Boolean>? =
        types[typeId]?.overview?.sort?.let { if (it.startsWith("-")) it.drop(1) to true else it to false }

    /** The boolean prop that greys out a type's overview rows, if any. */
    fun overviewMuted(typeId: String): String? = types[typeId]?.overview?.muted

    fun overviewHidden(typeId: String): Boolean = types[typeId]?.overview?.hidden == true

    /** Sort key for campaign-home sections: position in [overviewOrder], unlisted types after. */
    fun overviewRank(typeId: String): Int = overviewOrder.indexOf(typeId).let { if (it < 0) overviewOrder.size else it }

    /** Fields a random table can fill: each creatable type's title, then its text fields (one-line or long), in schema order. */
    fun fillableFields(): List<FieldOption> = nodeTypes.flatMap { t ->
        listOf(FieldOption("${t.id}:title", "${t.label}: Title")) +
            PropsForm.fields(propsSchema(t.id), emptyMap()).filter { it.kind in setOf("text", "textarea", "list") }
                .map { FieldOption("${t.id}:${it.name}", "${t.label}: ${it.label}") }
    }

    /** Returns human-readable validation errors; empty list = valid. */
    fun validateProps(typeId: String, props: JsonNode): List<String> {
        val def = propsSchema(typeId) ?: return emptyList()
        val schema = schemaCache.computeIfAbsent(typeId) { schemaFactory.getSchema(def) }
        return schema.validate(props).map { it.message }
    }
}

/**
 * Orders items by a prop, e.g. a type's `overviewSort` ("date", true = descending): stored values compared as numbers
 * when both are, else as text (ISO dates sort right), items without a value last; ties and unsorted ([sort] null) by title.
 */
fun <T> sortedByProp(items: List<T>, sort: Pair<String, Boolean>?, props: (T) -> Map<String, Any?>, title: (T) -> String): List<T> {
    if (sort == null) return items.sortedBy(title)
    val (prop, descending) = sort
    val byValue = Comparator<Any> { a, b ->
        if (a is Number && b is Number) a.toDouble().compareTo(b.toDouble()) else a.toString().compareTo(b.toString())
    }.let { if (descending) it.reversed() else it }
    return items.sortedWith(compareBy<T, Any?>(nullsLast(byValue)) { props(it)[prop]?.takeIf { v -> v.toString().isNotBlank() } }.thenBy(title))
}
