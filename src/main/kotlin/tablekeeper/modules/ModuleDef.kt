package tablekeeper.modules

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import tablekeeper.core.Direction
import tablekeeper.core.Link
import tablekeeper.core.TableSeed

// The shape of module.json, and the link options resolved from it.

@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeTypeDef(
    val id: String,
    val label: String,
    /** Plural label for headings, e.g. "Landmarks". Missing = [label] + "s". */
    val plural: String? = null,
    /**
     * How a node's name is shown everywhere but its own title field, as [Parts], e.g. ["{title}", "({player})"]
     * -> "Thonal (Jori)", just "Thonal" without a player. Missing = inherit; none = the title.
     */
    val displayTitle: List<String>? = null,
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
    /**
     * Order of the headings in this type's Links box and print lists, e.g. ["Reached via", "Resources", "*", "Featured in"]:
     * listed headings in this order, "*" for the others in their default order (end if missing). Missing = inherit.
     */
    val linkOrder: List<String>? = null,
    /** Shown for this type's nodes on screen (instead of their code): one of [ICONS]. Missing = inherit; none = a circle. */
    val icon: String? = null,
    /** Drawn on the campaign map with this shape: one of [MAP_SHAPES]. Missing = inherit; "none" or none at all = not on the map
     *  (e.g. a dungeon's rooms, places inside one that would crowd it). */
    val mapShape: String? = null,
    /**
     * Prop placing this type vertically on the map, higher values lower, e.g. Heart's "tier": an integer, or an enum
     * whose "x-depth" maps values to depths ("Tier 2": 2; values left out, like "Rogue", are placed freely). Missing = inherit.
     */
    val mapDepth: String? = null,
    /** Link to the places this type is drawn between on the map, e.g. a delve's landmarks; its depth is their average. Missing = inherit. */
    val mapBetween: String? = null,
    /** This type's nodes have a map of their own (a dungeon's rooms), shown from their page and on their prints. Missing = inherit. */
    val map: NodeMapDef? = null,
    /**
     * Columns of this type's table on the campaign home: prop names, or "@rel" for linked titles
     * (e.g. "@connects:out"). Link slots are added automatically. Missing = inherit.
     */
    val overview: List<String>? = null,
    /** Boolean prop that greys out this type's rows on the campaign home when true, e.g. an NPC's "dead". Missing = inherit. */
    val overviewMuted: String? = null,
    /** Prop ordering this type's rows on the campaign home, e.g. "date"; "-date" for newest first. Missing = inherit; none = by title. */
    val overviewSort: String? = null,
    /** Checkbox lists on this type's page; ticking links the node via the list's rel. Missing = inherit. */
    val checklists: List<ChecklistDef>? = null,
    /** Printing a node of this type builds a session packet from what it links. Missing = inherit. */
    val packet: PacketDef? = null,
    /** After play: what this type's page lets you update on the packet's nodes, e.g. a session's characters and NPCs. Missing = inherit. */
    val debrief: List<DebriefDef>? = null,
)

/** A checkbox list of every node of [types]; ticked = linked via [rel], e.g. a session's featured landmarks. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class ChecklistDef(val rel: String, val types: List<String>)

/**
 * A printed packet of the nodes linked via [rel] (e.g. a session's "features"), plus [between] nodes:
 * those whose every link of a kind points at a selected node, e.g. delves between selected landmarks.
 * [around] adds nodes whose link of a kind ends at a selected node, or at a node inside one (via its `context`),
 * e.g. NPCs located at a selected landmark or its minor locations.
 * [related] then adds nodes with a link of a kind to anything already in the packet, e.g. the factions its NPCs are members of.
 * [columns] > 1 prints the text on landscape A4 in that many columns (after the template's cover part).
 * [recap]: a prop of this type, e.g. "recap"; the cover then shows the previous node's (by `overviewSort`), "Last time".
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PacketDef(
    val rel: String,
    val between: List<BetweenDef> = emptyList(),
    val around: List<BetweenDef> = emptyList(),
    val related: List<BetweenDef> = emptyList(),
    val columns: Int = 1,
    val recap: String? = null,
)

/**
 * One list in a node's debrief: the packet's nodes of [types], each with its [fields] editable in place.
 * [done]: each "x-row" among the fields (e.g. a beat) gets a Done button that clears it.
 * [slots]: show the type's link slots (Location) as dropdowns, and also list nodes whose slot points into the packet,
 * e.g. resources at a selected landmark or carried by an NPC there.
 * [links]: linked nodes listed under each, e.g. a character's fallouts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DebriefDef(
    val types: List<String>,
    val fields: List<String> = emptyList(),
    val done: Boolean = false,
    val slots: Boolean = false,
    val links: List<DebriefLinkDef> = emptyList(),
)

/**
 * Nodes linked via [rel], with their [fields] editable in place; [create] = a type you can add a new one of, e.g. "heart.fallout";
 * [remove]: each gets a button that removes it from this node (a fallout healed for one character); the linked node
 * itself is kept, to reuse later.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DebriefLinkDef(val rel: String, val fields: List<String> = emptyList(), val create: String? = null, val remove: Boolean = false)

/** Nodes of [type] included when each of their [rel] links ends at a selected node (and there is at least one). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BetweenDef(val type: String, val rel: String)

/** A place's depth on the map (higher = lower), and the guide label for its level, e.g. 2.0 / "Tier 2". */
data class MapDepth(val depth: Double, val label: String)

val MAP_SHAPES = setOf("circle", "diamond", "dot", "none")

/** Built-in ways a generator's `connect` links a group (generate/), e.g. a dungeon's rooms. */
val GEN_LAYOUTS = setOf("dungeon")

/** Icons bundled in static/icons.svg (from Lucide); a type's `icon` must be one of them. */
val ICONS = setOf("map-pin", "castle", "route", "user", "drama", "package", "gem", "sword", "heart-crack", "flag", "scroll-text", "calendar", "skull", "door-open", "ghost", "hourglass", "circle")

/**
 * On the card, under each node linked via [rel], list the nodes linked to *it* via [show].
 * Landmark {"rel": "part_of:in", "show": "located_at:in"} lists what each minor location has here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PrintNestedDef(val rel: String, val show: String)

/**
 * A node's own map: the nodes it links via [places] (e.g. a dungeon's rooms, "room_in:in"), drawn as [shape], and the links
 * between them; places whose boolean prop [highlight] is set are filled red (a dungeon's entrance).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class NodeMapDef(val places: String, val shape: String = "circle", val highlight: String? = null)

/**
 * A generator: one click (Create ▾) makes [nodes] and links them, with no input; the first node made is opened.
 * Content comes from the campaign's random tables (the module's starter `tables` are added to new campaigns).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GeneratorDef(val id: String, val label: String, val nodes: List<GenNodeDef>, val connect: GenConnectDef? = null)

/**
 * One group of nodes a generator makes, named [id] within it: [count] nodes of [type] ([n] or [min, max]).
 * [fill]: fields rolled from the campaign's tables for that field ("title" from the type's title tables); an enum prop
 * without a table gets a random value. [chance]: a field filled only that often (0..1). [first]: values for the group's
 * first node ("title" its title, the rest props), e.g. a dungeon's entrance. [title]: title pattern when none is rolled,
 * "{n}" counting the nodes without a [first] title. [link]: each node links to a node of an earlier group.
 * [row]: several fields from one roll on a table of rows (a bestiary). [generator] instead of [type]: each node of the
 * group is made by that generator (its first node is the group's member), e.g. a dungeon's creatures.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GenNodeDef(
    val id: String,
    val type: String? = null,
    val generator: String? = null,
    @JsonFormat(with = [JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY])
    val count: List<Int> = listOf(1),
    val fill: List<String> = emptyList(),
    val chance: Map<String, Double> = emptyMap(),
    val first: Map<String, Any?> = emptyMap(),
    val title: String? = null,
    val row: GenRowDef? = null,
    val link: GenLinkDef? = null,
)

/**
 * A roll on the campaign table named [table] whose lines are rows of "|"-separated columns, filling [columns] in order
 * ("title" or props), e.g. a bestiary: "Goblin | 6 | 7 | Ropy skin -d2 | …". Missing table: the group's `fill` and `title`.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GenRowDef(val table: String, val columns: List<String>)

/** Links each node of a group via [rel] to the group [to]'s first node, or with [pick] "random" to a random one (not its first, if it has others). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GenLinkDef(val rel: String, val to: String, val pick: String = "first")

/** Links the nodes of group [nodes] to each other via [rel], in a built-in [layout] ("dungeon": rooms from the first, the entrance). */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GenConnectDef(val nodes: String, val rel: String, val layout: String)

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
    val propNames: Set<String> get() = Parts.names(parts)
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
    /** On the `to` side (e.g. a place), these links are listed by the linked node's type ("NPCs", "Resources") instead of under [inverseLabel]. */
    val groupByType: Boolean = false,
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
    /**
     * Settings this game lays over inherited types, e.g. Heart's "Add new…" choices on core.npc: each an object with
     * the type's `id` and any [NodeTypeDef] settings, which replace the type's own.
     */
    val patchTypes: List<ObjectNode> = emptyList(),
    /** Extra campaign pages this game ships, e.g. Heart's GM inspiration page. */
    val views: List<ViewDef> = emptyList(),
    /** Type ids listed first on the campaign home, in this order; other types follow. */
    val overviewOrder: List<String> = emptyList(),
    /** Starter random tables, added to new campaigns of this game (and on the Tables page to older ones). */
    val tables: List<TableSeed> = emptyList(),
    /** One-click generators in the Create ▾ menu, e.g. a whole dungeon. */
    val generators: List<GeneratorDef> = emptyList(),
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
    /** Link options whose linked nodes are listed under each node, with their print summary (e.g. a character's fallouts). */
    val links: List<String> = emptyList(),
)

/** One choice in the "Link to…" dropdown, e.g. "has here" = located_at seen from the place. */
data class LinkOption(val edgeType: String, val direction: Direction, val label: String) {
    val key: String get() = direction.key(edgeType)

    /** Is [link] an edge of this option, seen from the same end? */
    fun matches(link: Link): Boolean = link.type == edgeType && link.direction == direction
}

/** One choice in the "Add new…" dropdown, e.g. NPC via "has here". */
data class QuickCreateOption(val type: NodeTypeDef, val option: LinkOption) {
    val key: String get() = "${type.id}|${option.key}"
}

/** A [LinkedPropDef] resolved against the type's link options. */
data class LinkedPropRule(val prop: String, val label: String, val option: LinkOption)

/** A [LinkSlotDef] resolved against the type's link options. */
data class LinkSlot(val label: String, val options: List<LinkOption>)
