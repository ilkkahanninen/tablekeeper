package tablekeeper.modules

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import tablekeeper.core.Direction
import java.io.File

// ---------- module.json shape ----------

@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeTypeDef(
    val id: String,
    val label: String,
    /** Plural label for headings, e.g. "Landmarks". Missing = [label] + "s". */
    val plural: String? = null,
    val codePrefix: String,
    val extends: String? = null,
    val printTemplate: String? = null,
    /** JSON Schema for `props`. Missing = inherit from parent type, or no props. */
    val props: JsonNode? = null,
    /** "Add new…" choices on this type's page. Missing = inherit from parent type. */
    val quickCreate: List<QuickCreateDef>? = null,
    /** Read-only values collected from linked nodes. Missing = inherit from parent type. */
    val linkedProps: List<LinkedPropDef>? = null,
    /** Max links per link option, e.g. {"connects:out": 2}; refused when full. Inherited per key. */
    val linkLimits: Map<String, Int>? = null,
    /** One-of-many links shown as a dropdown, e.g. an item's Location. Missing = inherit from parent type. */
    val linkSlots: List<LinkSlotDef>? = null,
    /** How this type is listed on other nodes' print cards. Missing = inherit; none = just title and code. */
    val printSummary: PrintSummaryDef? = null,
    /** On this type's card, also list what linked nodes have, e.g. each minor location's contents. Missing = inherit. */
    val printNested: List<PrintNestedDef>? = null,
    /** Link whose far end is shown in brackets in pickers, e.g. "part_of:out" = a minor location's landmark. */
    val context: String? = null,
    /** Drawn on the campaign map with this shape: one of [MAP_SHAPES]. Missing = inherit; none = not on the map. */
    val mapShape: String? = null,
    /** Integer prop placing this type vertically on the map, higher values lower, e.g. Heart's "tier". Missing = inherit. */
    val mapDepth: String? = null,
    /** Link whose far ends' map depths are averaged for this type, e.g. a delve between its landmarks. Missing = inherit. */
    val mapDepthFrom: String? = null,
    /**
     * Columns of this type's table on the campaign home: prop names, or "@rel" for linked titles
     * (e.g. "@connects:out"). Link slots are added automatically. Missing = inherit.
     */
    val overview: List<String>? = null,
    /** Boolean prop that greys out this type's rows on the campaign home when true, e.g. an NPC's "dead". Missing = inherit. */
    val overviewMuted: String? = null,
    /** Checkbox lists on this type's page; ticking links the node via the list's rel. Missing = inherit. */
    val checklists: List<ChecklistDef>? = null,
    /** Printing a node of this type builds a session packet from what it links. Missing = inherit. */
    val packet: PacketDef? = null,
)

/** A checkbox list of every node of [types]; ticked = linked via [rel], e.g. a session's featured landmarks. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChecklistDef(val rel: String, val types: List<String>)

/**
 * A printed packet of the nodes linked via [rel] (e.g. a session's "features"), plus [between] nodes:
 * those whose every link of a kind points at a selected node, e.g. delves between selected landmarks.
 * [around] adds nodes whose link of a kind ends at a selected node, or at a node inside one (via its `context`),
 * e.g. NPCs located at a selected landmark or its minor locations.
 * [columns] > 1 prints the text on landscape A4 in that many columns (after the template's cover part).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PacketDef(val rel: String, val between: List<BetweenDef> = emptyList(), val around: List<BetweenDef> = emptyList(), val columns: Int = 1)

/** Nodes of [type] included when each of their [rel] links ends at a selected node (and there is at least one). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BetweenDef(val type: String, val rel: String)

val MAP_SHAPES = setOf("circle", "diamond", "dot")

/**
 * On the card, under each node linked via [rel], list the nodes linked to *it* via [show].
 * Landmark {"rel": "part_of:in", "show": "located_at:in"} lists what each minor location has here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrintNestedDef(val rel: String, val show: String)

/**
 * When a node of this type is listed on another node's card: list it under [group] instead of the
 * link label, followed by [parts] joined with " · ". A part is a small template: `{prop}` is the
 * display text, `{prop.value}` the stored value; empty values drop out, and a part with every value empty
 * is dropped whole, literal text included.
 * Equipment ["{category} {quality.value}", "{tags}"] prints "Bone Saw (E1): Kill d8 · Brutal, Reload".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrintSummaryDef(val group: String? = null, val parts: List<String> = emptyList()) {
    /** Prop names used in [parts]. */
    val propNames: Set<String> get() = parts.flatMap { p -> PLACEHOLDER.findAll(p).map { it.groupValues[1] } }.toSet()

    companion object {
        /** `{name}` or `{name.value}`. */
        val PLACEHOLDER = Regex("""\{(\w+)(\.value)?}""")
    }
}

/**
 * A dropdown holding at most one link across [rels], e.g. Location = at a place or carried by someone.
 * Linking via any of them, from either end, replaces the slot's current link.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LinkSlotDef(val label: String, val rels: List<String>)

/** Create a node of [type] and link it to the current node via [rel] (a [LinkOption.key], e.g. "located_at:in"). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class QuickCreateDef(val type: String, val rel: String)

/** Show the union of prop [prop] of all nodes linked via [rel], e.g. a delve's domains from its landmarks. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LinkedPropDef(val prop: String, val rel: String, val label: String? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class EdgeTypeDef(
    val id: String,
    val label: String,
    val inverseLabel: String,
    val from: List<String>,
    val to: List<String>,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ModuleDef(
    val id: String,
    val name: String,
    val extends: List<String> = emptyList(),
    /** Inherited types this game replaces, e.g. Heart hides core.place in favour of Landmark/Delve. */
    val hide: List<String> = emptyList(),
    val nodeTypes: List<NodeTypeDef> = emptyList(),
    val edgeTypes: List<EdgeTypeDef> = emptyList(),
    /** Settings this game adds to inherited types, e.g. Heart's "Add new…" choices on core.npc. */
    val patchTypes: List<TypePatch> = emptyList(),
    /** Extra campaign pages this game ships, e.g. Heart's GM inspiration page. */
    val views: List<ViewDef> = emptyList(),
    /** Type ids listed first on the campaign home, in this order; other types follow. */
    val overviewOrder: List<String> = emptyList(),
)

/**
 * A campaign page at /c/{id}/v/[id], rendered by [template] (resolved like print templates, so it can live
 * in the module folder). The template gets the campaign's nodes of [types] with their form fields, and for
 * each prop in [groupBy] those nodes grouped by its value.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ViewDef(
    val id: String,
    val label: String,
    val template: String,
    val types: List<String>,
    val groupBy: List<String> = emptyList(),
)

/** Settings laid over an inherited node type; each one given replaces the type's own. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TypePatch(
    val id: String,
    val label: String? = null,
    val printTemplate: String? = null,
    val props: JsonNode? = null,
    val quickCreate: List<QuickCreateDef>? = null,
    val linkedProps: List<LinkedPropDef>? = null,
    val linkLimits: Map<String, Int>? = null,
    val linkSlots: List<LinkSlotDef>? = null,
    val printSummary: PrintSummaryDef? = null,
    val printNested: List<PrintNestedDef>? = null,
    val context: String? = null,
    val mapShape: String? = null,
    val mapDepth: String? = null,
    val mapDepthFrom: String? = null,
    val overview: List<String>? = null,
    val overviewMuted: String? = null,
    val checklists: List<ChecklistDef>? = null,
    val packet: PacketDef? = null,
) {
    fun applyTo(def: NodeTypeDef) = def.copy(
        label = label ?: def.label,
        printTemplate = printTemplate ?: def.printTemplate,
        props = props ?: def.props,
        quickCreate = quickCreate ?: def.quickCreate,
        linkedProps = linkedProps ?: def.linkedProps,
        linkLimits = linkLimits ?: def.linkLimits,
        linkSlots = linkSlots ?: def.linkSlots,
        printSummary = printSummary ?: def.printSummary,
        printNested = printNested ?: def.printNested,
        context = context ?: def.context,
        mapShape = mapShape ?: def.mapShape,
        mapDepth = mapDepth ?: def.mapDepth,
        mapDepthFrom = mapDepthFrom ?: def.mapDepthFrom,
        overview = overview ?: def.overview,
        overviewMuted = overviewMuted ?: def.overviewMuted,
        checklists = checklists ?: def.checklists,
        packet = packet ?: def.packet,
    )
}

/** One choice in the "Link to…" dropdown, e.g. "has here" = located_at seen from the place. */
data class LinkOption(val edgeType: String, val direction: Direction, val label: String) {
    val key: String get() = "$edgeType:${direction.name.lowercase()}"
}

/** One choice in the "Add new…" dropdown, e.g. NPC via "has here". */
data class QuickCreateOption(val type: NodeTypeDef, val option: LinkOption) {
    val key: String get() = "${type.id}|${option.key}"
}

/** A [LinkedPropDef] resolved against the type's link options. */
data class LinkedPropRule(val prop: String, val label: String, val option: LinkOption)

/** A [LinkSlotDef] resolved against the type's link options. */
data class LinkSlot(val label: String, val options: List<LinkOption>)

// ---------- resolved module (own types + everything it extends) ----------

class ResolvedModule(
    val id: String,
    val name: String,
    private val types: Map<String, NodeTypeDef>,
    private val creatable: List<NodeTypeDef>,
    val edgeTypes: List<EdgeTypeDef>,
    /** Module views, inherited ones included; a game overrides an inherited view by reusing its id. */
    val views: List<ViewDef> = emptyList(),
    /** Type ids first on the campaign home; the nearest module that declares an order wins. */
    val overviewOrder: List<String> = emptyList(),
) {
    private val schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<String, JsonSchema>()

    /** Types offered in "New…" menus. */
    val nodeTypes: List<NodeTypeDef> get() = creatable

    fun type(id: String): NodeTypeDef? = types[id]

    fun label(typeId: String): String = types[typeId]?.label ?: typeId

    fun plural(typeId: String): String = types[typeId]?.let { it.plural ?: it.label + "s" } ?: typeId

    fun edgeType(id: String): EdgeTypeDef? = edgeTypes.firstOrNull { it.id == id }

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

    /** Can a node of [otherType] sit at the far end of this link option? */
    fun acceptsOther(option: LinkOption, otherType: String): Boolean {
        val e = edgeType(option.edgeType) ?: return false
        val targets = if (option.direction == Direction.OUT) e.to else e.from
        return matchesAny(otherType, targets)
    }

    /** First non-null [pick] walking up `extends` from [typeId]: the nearest type that declares it wins. */
    private fun <T> inherited(typeId: String, pick: (NodeTypeDef) -> T?): T? {
        var t: String? = typeId
        while (t != null) {
            val def = types[t] ?: return null
            pick(def)?.let { return it }
            t = def.extends
        }
        return null
    }

    /** Props schema for a type; inherited if the type declares none. */
    fun propsSchema(typeId: String): JsonNode? = inherited(typeId) { it.props }

    fun printTemplate(typeId: String): String? = inherited(typeId) { it.printTemplate }

    /** "Add new…" choices on a node of [typeId]. Types this game hides are left out. */
    fun quickCreate(typeId: String): List<QuickCreateOption> = inherited(typeId) { it.quickCreate }.orEmpty().mapNotNull { q ->
        val type = creatable.firstOrNull { it.id == q.type } ?: return@mapNotNull null
        val option = linkOptions(typeId).firstOrNull { it.key == q.rel } ?: return@mapNotNull null
        QuickCreateOption(type, option).takeIf { acceptsOther(option, type.id) }
    }

    /** `linkLimits` for [edgeType] in [dir] on a node of [typeId]; null = unlimited. */
    fun linkLimit(typeId: String, edgeType: String, dir: Direction): Int? =
        inherited(typeId) { it.linkLimits?.get("$edgeType:${dir.name.lowercase()}") }

    fun printSummary(typeId: String): PrintSummaryDef? = inherited(typeId) { it.printSummary }

    fun printNested(typeId: String): List<PrintNestedDef> = inherited(typeId) { it.printNested }.orEmpty()

    fun mapShape(typeId: String): String? = inherited(typeId) { it.mapShape }

    fun mapDepth(typeId: String): String? = inherited(typeId) { it.mapDepth }

    /** The type's `mapDepthFrom` link option, e.g. a delve's "connects" (to its landmarks). */
    fun mapDepthFrom(typeId: String): LinkOption? =
        inherited(typeId) { it.mapDepthFrom }?.let { key -> linkOptions(typeId).firstOrNull { it.key == key } }

    /** The type's `context` link option, e.g. a minor location's "is part of" (its landmark). */
    fun context(typeId: String): LinkOption? =
        inherited(typeId) { it.context }?.let { key -> linkOptions(typeId).firstOrNull { it.key == key } }

    /** Link slots (one-of-many dropdowns) on a node of [typeId]. */
    fun linkSlots(typeId: String): List<LinkSlot> {
        val options = linkOptions(typeId)
        return inherited(typeId) { it.linkSlots }.orEmpty().map { s ->
            LinkSlot(s.label, s.rels.mapNotNull { r -> options.firstOrNull { it.key == r } })
        }
    }

    /** The slot on a [typeId] node that holds [edgeType] links in [dir], if any. */
    fun slotFor(typeId: String, edgeType: String, dir: Direction): LinkSlot? =
        linkSlots(typeId).firstOrNull { s -> s.options.any { it.edgeType == edgeType && it.direction == dir } }

    /** Linked-prop rules for a node of [typeId]; label defaults to the prop name. */
    fun linkedProps(typeId: String): List<LinkedPropRule> = inherited(typeId) { it.linkedProps }.orEmpty().mapNotNull { l ->
        val option = linkOptions(typeId).firstOrNull { it.key == l.rel } ?: return@mapNotNull null
        LinkedPropRule(l.prop, l.label ?: l.prop.replaceFirstChar { it.uppercase() }, option)
    }

    /** Problems with declared quickCreate / linkedProps / linkLimits / linkSlots entries; empty list = valid. Hidden types are not an error. */
    internal fun configErrors(): List<String> = types.values.flatMap { def ->
        val options = linkOptions(def.id)
        val quick = def.quickCreate.orEmpty().mapNotNull { q ->
            val option = options.firstOrNull { it.key == q.rel }
            when {
                q.type !in types -> "${def.id}: quickCreate type '${q.type}' is unknown"
                option == null -> "${def.id}: quickCreate rel '${q.rel}' is not a link option of ${def.id}"
                !acceptsOther(option, q.type) -> "${def.id}: ${q.type} can't be linked via '${q.rel}'"
                else -> null
            }
        }
        val linked = def.linkedProps.orEmpty().filter { l -> options.none { it.key == l.rel } }
            .map { "${def.id}: linkedProps rel '${it.rel}' is not a link option of ${def.id}" }
        val limits = def.linkLimits.orEmpty().mapNotNull { (key, max) ->
            when {
                options.none { it.key == key } -> "${def.id}: linkLimits key '$key' is not a link option of ${def.id}"
                max < 1 -> "${def.id}: linkLimits '$key' must be at least 1"
                else -> null
            }
        }
        val slotRels = def.linkSlots.orEmpty().flatMap { it.rels }
        val slots = slotRels.filter { r -> options.none { it.key == r } }
            .map { "${def.id}: linkSlots rel '$it' is not a link option of ${def.id}" } +
            slotRels.groupingBy { it }.eachCount().filter { it.value > 1 }
                .map { "${def.id}: linkSlots rel '${it.key}' is in more than one slot" }
        val schemaProps = propsSchema(def.id)?.get("properties")
        val summary = def.printSummary?.propNames.orEmpty().filter { schemaProps?.has(it) != true }
            .map { "${def.id}: printSummary prop '$it' is not in its props schema" }
        // "show" is seen from the nested node, whose type varies, so only check that it names a real edge direction
        val edgeKeys = edgeTypes.flatMap { listOf("${it.id}:out", "${it.id}:in") }.toSet()
        val nested = def.printNested.orEmpty().flatMap { n ->
            listOfNotNull(
                "${def.id}: printNested rel '${n.rel}' is not a link option of ${def.id}".takeIf { options.none { it.key == n.rel } },
                "${def.id}: printNested show '${n.show}' is not an edge type and direction".takeIf { n.show !in edgeKeys },
            )
        }
        val context = listOfNotNull(def.context?.takeIf { key -> options.none { it.key == key } }
            ?.let { "${def.id}: context '$it' is not a link option of ${def.id}" })
        val map = listOfNotNull(def.mapShape?.takeIf { it !in MAP_SHAPES }
            ?.let { "${def.id}: mapShape '$it' is not one of ${MAP_SHAPES.joinToString()}" }) +
            listOfNotNull(def.mapDepth?.takeIf { schemaProps?.get(it)?.get("type")?.asText() != "integer" }
                ?.let { "${def.id}: mapDepth '$it' is not an integer prop in its props schema" }) +
            listOfNotNull(def.mapDepthFrom?.takeIf { key -> options.none { it.key == key } }
                ?.let { "${def.id}: mapDepthFrom '$it' is not a link option of ${def.id}" })
        val overview = def.overview.orEmpty().mapNotNull { col ->
            if (col.startsWith("@")) "${def.id}: overview '$col' is not a link option of ${def.id}".takeIf { options.none { it.key == col.drop(1) } }
            else "${def.id}: overview prop '$col' is not in its props schema".takeIf { schemaProps?.has(col) != true }
        }
        val muted = listOfNotNull(def.overviewMuted?.takeIf { schemaProps?.get(it)?.get("type")?.asText() != "boolean" }
            ?.let { "${def.id}: overviewMuted '$it' is not a boolean prop in its props schema" })
        val checklists = def.checklists.orEmpty().flatMap { c ->
            listOfNotNull("${def.id}: checklist rel '${c.rel}' is not a link option of ${def.id}".takeIf { options.none { it.key == c.rel } }) +
                c.types.filter { it !in types }.map { "${def.id}: checklist type '$it' is unknown" }
        }
        val packet = def.packet?.let { pk ->
            listOfNotNull("${def.id}: packet rel '${pk.rel}' is not a link option of ${def.id}".takeIf { options.none { it.key == pk.rel } }) +
                (pk.between.map { "between" to it } + pk.around.map { "around" to it }).flatMap { (kind, b) ->
                    if (b.type !in types) listOf("${def.id}: packet $kind type '${b.type}' is unknown")
                    else listOfNotNull("${def.id}: packet $kind rel '${b.rel}' is not a link option of ${b.type}"
                        .takeIf { linkOptions(b.type).none { it.key == b.rel } })
                }
        }.orEmpty()
        quick + linked + limits + slots + summary + nested + context + map + overview + muted + checklists + packet
    }

    fun view(id: String): ViewDef? = views.firstOrNull { it.id == id }

    /** Overview column specs for a type: prop names and "@rel" link columns. */
    fun overview(typeId: String): List<String> = inherited(typeId) { it.overview }.orEmpty()

    /** Checkbox lists on a type's page, with their link option resolved. */
    fun checklists(typeId: String): List<Pair<ChecklistDef, LinkOption>> = inherited(typeId) { it.checklists }.orEmpty()
        .mapNotNull { c -> linkOptions(typeId).firstOrNull { it.key == c.rel }?.let { c to it } }

    fun packet(typeId: String): PacketDef? = inherited(typeId) { it.packet }

    /** The boolean prop that greys out a type's overview rows, if any. */
    fun overviewMuted(typeId: String): String? = inherited(typeId) { it.overviewMuted }

    /** Sort key for campaign-home sections: position in [overviewOrder], unlisted types after. */
    fun overviewRank(typeId: String): Int = overviewOrder.indexOf(typeId).let { if (it < 0) overviewOrder.size else it }

    /** Problems with declared views and the overview order; empty list = valid. */
    internal fun viewErrors(): List<String> = overviewOrder.filter { it !in types }.map { "overviewOrder: type '$it' is unknown" } +
        views.flatMap { v ->
        v.types.filter { it !in types }.map { "view '${v.id}': type '$it' is unknown" } +
            v.groupBy.filter { prop -> v.types.none { propsSchema(it)?.get("properties")?.has(prop) == true } }
                .map { "view '${v.id}': groupBy '$it' is not a prop of ${v.types.joinToString()}" }
    }

    /** Returns human-readable validation errors; empty list = valid. */
    fun validateProps(typeId: String, props: JsonNode): List<String> {
        val def = propsSchema(typeId) ?: return emptyList()
        val schema = schemaCache.computeIfAbsent(typeId) { schemaFactory.getSchema(def) }
        return schema.validate(props).map { it.message }
    }
}

// ---------- loader ----------

class GameModules(private val modules: Map<String, ResolvedModule>) {

    fun get(id: String): ResolvedModule = modules[id] ?: error("Unknown game module '$id'")

    /** Modules you can start a campaign with (everything except bare 'core' is listed first). */
    val all: List<ResolvedModule> get() = modules.values.sortedBy { if (it.id == "core") 1 else 0 }

    companion object {
        val mapper: ObjectMapper = jacksonObjectMapper()

        /** Loads every `<dir>/<module>/module.json`. */
        fun load(dir: String): GameModules {
            val root = File(dir)
            require(root.isDirectory) { "Modules directory not found: ${root.absolutePath}" }
            val defs = root.listFiles()!!
                .mapNotNull { File(it, "module.json").takeIf(File::isFile) }
                .map { mapper.readValue<ModuleDef>(it) }
                .associateBy { it.id }

            fun chain(id: String, seen: MutableSet<String> = mutableSetOf()): List<ModuleDef> {
                require(seen.add(id)) { "Module extends cycle at '$id'" }
                val def = defs[id] ?: error("Module '$id' not found in $dir")
                return def.extends.flatMap { chain(it, seen) } + def   // base first, self last
            }

            val resolved = defs.keys.associateWith { id ->
                val c = chain(id)
                val self = c.last()
                val types = LinkedHashMap<String, NodeTypeDef>()
                val edges = LinkedHashMap<String, EdgeTypeDef>()
                c.forEach { m ->
                    m.nodeTypes.forEach { types[it.id] = it }
                    m.edgeTypes.forEach { edges[it.id] = it }
                    m.patchTypes.forEach { p ->
                        val def = requireNotNull(types[p.id]) { "Module '${m.id}' patches unknown type '${p.id}'" }
                        types[p.id] = p.applyTo(def)
                    }
                }
                val hidden = c.flatMap { it.hide }.toSet()
                ResolvedModule(
                    id = self.id,
                    name = self.name,
                    types = types,
                    creatable = types.values.filter { it.id !in hidden },
                    edgeTypes = edges.values.toList(),
                    views = c.flatMap { it.views }.associateBy { it.id }.values.toList(),
                    overviewOrder = c.lastOrNull { it.overviewOrder.isNotEmpty() }?.overviewOrder.orEmpty(),
                )
            }
            // Modules that extend others re-check inherited types, hence distinct().
            val errors = resolved.values.flatMap { it.configErrors() + it.viewErrors() }.distinct()
            require(errors.isEmpty()) { "Invalid game modules:\n" + errors.joinToString("\n") }
            return GameModules(resolved)
        }
    }
}
