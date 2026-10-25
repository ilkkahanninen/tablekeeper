package tablekeeper.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.http.content.staticResources
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tablekeeper.auth.user
import tablekeeper.core.Audience
import tablekeeper.core.Campaign
import tablekeeper.core.CampaignService
import tablekeeper.core.Direction
import tablekeeper.core.GraphService
import tablekeeper.core.RandomTableService
import tablekeeper.core.RandomTables
import tablekeeper.core.Node
import tablekeeper.core.NodeRef
import tablekeeper.core.Visibility
import tablekeeper.modules.GameModules
import tablekeeper.modules.LinkOption
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import tablekeeper.map.MapService
import tablekeeper.print.PrintService
import tablekeeper.views.ModuleViews
import java.util.UUID

class NotFound(what: String) : RuntimeException("$what not found")
class BadRequest(msg: String) : RuntimeException(msg)

class Deps(
    val campaigns: CampaignService,
    val graph: GraphService,
    val modules: GameModules,
    val templates: Templates,
    val print: PrintService,
    val map: MapService,
    val tables: RandomTableService,
)

// View models for the "Linked" sidebar
data class UiLink(val edgeId: UUID, val otherId: UUID, val code: String, val title: String, val typeLabel: String, val visibility: String)
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
/** Dropdown for a link slot, e.g. Location: places, then carriers. */
data class SlotView(val index: Int, val label: String, val groups: List<ChoiceGroup>)

fun Application.routes(d: Deps) {

    // ---------- helpers (closures over deps) ----------

    suspend fun ApplicationCall.html(template: String, model: Map<String, Any?>) =
        respondText(d.templates.render(template, model), ContentType.Text.Html)

    fun ApplicationCall.uuid(name: String): UUID =
        parameters[name]?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: throw NotFound(name)

    fun ApplicationCall.campaign(): Campaign = d.campaigns.get(user, uuid("cid")) ?: throw NotFound("Campaign")

    fun ApplicationCall.node(c: Campaign): Node = d.graph.getNode(c.id, uuid("nid")) ?: throw NotFound("Node")

    fun ApplicationCall.isHtmx() = request.headers["HX-Request"] == "true"

    fun Parameters.asMap(): Map<String, List<String>> = entries().associate { it.key to it.value }

    /** Message if the node already has its type's `linkLimits` maximum of [edgeType] links in [dir], else null. */
    fun limitError(c: Campaign, m: ResolvedModule, nodeId: UUID, nodeType: String, name: String, edgeType: String, dir: Direction): String? {
        val max = m.linkLimit(nodeType, edgeType, dir) ?: return null
        if (d.graph.countLinks(c.id, nodeId, edgeType, dir) < max) return null
        val e = m.edgeType(edgeType)
        val label = (if (dir == Direction.OUT) e?.label else e?.inverseLabel) ?: edgeType
        return "$name already has the maximum of $max “$label” links"
    }

    /**
     * Creates the edge for [option] between [n] and [other], respecting direction and limits at both ends.
     * If an end holds this link in a slot (an item's Location), the slot's old link is replaced, so the item moves.
     */
    fun link(c: Campaign, m: ResolvedModule, n: Node, option: LinkOption, other: Node) {
        val ends = listOf(n to option.direction, other to option.direction.opposite)
        ends.firstNotNullOfOrNull { (end, dir) -> limitError(c, m, end.id, end.type, "${end.code} ${end.title}", option.edgeType, dir) }
            ?.let { throw BadRequest(it) }
        ends.forEach { (end, dir) ->
            m.slotFor(end.type, option.edgeType, dir)?.options?.forEach { d.graph.deleteLinks(c.id, end.id, it.edgeType, it.direction) }
        }
        if (option.direction == Direction.OUT) d.graph.createEdge(c.id, n.id, other.id, option.edgeType)
        else d.graph.createEdge(c.id, other.id, n.id, option.edgeType)
    }

    /** "Add new…" choices, minus those whose link type is already full on this node. */
    fun quickCreateOptions(c: Campaign, m: ResolvedModule, n: Node) = m.quickCreate(n.type).filter {
        limitError(c, m, n.id, n.type, n.title, it.option.edgeType, it.option.direction) == null
    }

    /** Display titles of all the campaign's nodes, e.g. "Thonal (Jori)" (module `displayTitle`). */
    fun displayTitles(c: Campaign, m: ResolvedModule): Map<UUID, String> = m.displayTitles(d.graph.listNodesWithProps(c.id))

    /**
     * Picker labels: "Butcher's Row (Red Market)": the title, plus the far end of the type's `context` link in
     * brackets if it has one. No type label: pickers show the code too, and its prefix already says the type.
     */
    fun pickerLabels(c: Campaign, m: ResolvedModule, nodes: List<NodeRef>): Map<UUID, String> {
        val contexts = nodes.mapNotNull { m.context(it.type) }.toSet()
            .associateWith { d.graph.linkedTitles(c.id, it.edgeType, it.direction) }
        val names = displayTitles(c, m)
        return nodes.associate { n ->
            val context = m.context(n.type)?.let { contexts[it]?.get(n.id) }
            n.id to "${names[n.id] ?: n.title}${context?.let { " ($it)" }.orEmpty()}"
        }
    }


    /** Link options held in this type's slots; they're set with a dropdown instead of the link picker. */
    fun slotOptions(m: ResolvedModule, n: Node): Set<LinkOption> = m.linkSlots(n.type).flatMap { it.options }.toSet()

    /**
     * Link options for the Link box, most useful first: the type's quick-create relations, then other specific
     * ones, then catch-alls whose both ends accept anything ("relates to"). Slot relations have their own dropdown.
     */
    fun orderedLinkOptions(m: ResolvedModule, n: Node): List<LinkOption> {
        val quick = m.quickCreate(n.type).map { it.option.key }.toSet()
        return (m.linkOptions(n.type) - slotOptions(m, n)).sortedBy { o ->
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
        val links = d.graph.links(c.id, n.id)
        val nodes = d.graph.listNodes(c.id)
        val labels = pickerLabels(c, m, nodes)
        return slots.mapIndexed { i, s ->
            SlotView(i, s.label, s.options.map { o ->
                val current = links.filter { it.type == o.edgeType && it.direction == o.direction }.map { it.other.id }.toSet()
                ChoiceGroup(o.label.replaceFirstChar { it.uppercase() }, nodes
                    .filter { it.id != n.id && m.acceptsOther(o, it.type) }
                    .map { Choice("${o.key}|${it.id}", "${it.code} ${labels.getValue(it.id)}", it.id in current) })
            })
        }
    }

    /** The node's checklists, e.g. a session's landmarks and characters, ticked where already linked. */
    fun checklists(c: Campaign, m: ResolvedModule, n: Node): List<ChecklistView> {
        val lists = m.checklists(n.type)
        if (lists.isEmpty()) return emptyList()
        val links = d.graph.links(c.id, n.id)
        val nodes = d.graph.listNodes(c.id)
        val names = displayTitles(c, m)
        return lists.map { (def, option) ->
            val linked = links.filter { it.type == option.edgeType && it.direction == option.direction }.map { it.other.id }.toSet()
            ChecklistView(
                def.types.joinToString(", ") { m.label(it) }, option,
                nodes.filter { other -> def.types.any { m.isA(other.type, it) } }.sortedBy { it.title }
                    .map { ChecklistItem(it.id, it.code, names[it.id] ?: it.title, it.id in linked) },
            )
        }
    }

    /** Links for the sidebar, slot links included so the place or carrier is one click away. */
    fun linkGroups(c: Campaign, m: ResolvedModule, n: Node): List<UiGroup> {
        val names = displayTitles(c, m)
        return d.graph.links(c.id, n.id)
            .sortedWith(compareBy({ m.linkGroupRank(it.type, it.other.type).first }, { m.linkGroupRank(it.type, it.other.type).second }))
            .groupBy { link -> m.linkGroupLabel(link.type, link.direction, link.other.type) }
            .map { (label, links) ->
                UiGroup(label, links.map {
                    UiLink(it.edgeId, it.other.id, it.other.code, names[it.other.id] ?: it.other.title, m.label(it.other.type), it.other.visibility)
                })
            }
    }

    fun nodeModel(c: Campaign, m: ResolvedModule, n: Node, errors: List<String> = emptyList()) = mapOf(
        "campaign" to c,
        "module" to m,
        "node" to n,
        "typeLabel" to m.label(n.type),
        "displayTitle" to m.displayTitle(n.type, n.title, n.props),
        "rows" to PropsForm.rows(PropsForm.fields(m.propsSchema(n.type), n.props)),
        "linkOptions" to orderedLinkOptions(m, n),
        "slots" to slots(c, m, n),
        // Random buttons: field name -> its tables (each button asks the server for a roll)
        "random" to d.tables.forType(c.id, n.type),
        "checklists" to checklists(c, m, n),
        "quickCreate" to quickCreateOptions(c, m, n).let { options ->
            // Just the type ("Delve"); the relation only where one type is offered through several
            val repeated = options.groupingBy { it.type.id }.eachCount().filterValues { it > 1 }.keys
            options.map { CreateChoice(it.key, if (it.type.id in repeated) "${it.type.label} · ${it.option.label}" else it.type.label) }
        },
        "linkedProps" to d.print.linkedProps(c, m, n, Audience.GM),
        "groups" to linkGroups(c, m, n),
        "errors" to errors,
    )

    /** A new node's title: the given one, else a roll on the type's title tables, else "New <type>". */
    fun newTitle(c: Campaign, typeId: String, typeLabel: String, given: String?): String =
        given?.trim()?.takeIf { it.isNotEmpty() } ?: d.tables.rollTitle(c.id, typeId) ?: "New $typeLabel"

    fun parseOption(m: ResolvedModule, n: Node, key: String?): LinkOption {
        val parts = key?.split(":") ?: throw BadRequest("Missing link type")
        val dir = runCatching { Direction.valueOf(parts.getOrNull(1).orEmpty().uppercase()) }.getOrNull()
        return m.linkOptions(n.type).firstOrNull { it.edgeType == parts[0] && it.direction == dir }
            ?: throw BadRequest("Link type '$key' not allowed for ${n.type}")
    }

    // ---------- routes ----------

    routing {
        staticResources("/static", "static")

        get("/") {
            call.html("index.peb", mapOf(
                "campaigns" to d.campaigns.list(call.user),
                "modules" to d.modules.all,
            ))
        }

        post("/campaigns") {
            val p = call.receiveParameters()
            val name = p["name"]?.trim().orEmpty().ifEmpty { throw BadRequest("Name is required") }
            val module = d.modules.get(p["module"] ?: "heart")
            val c = d.campaigns.create(call.user, name, module.id)
            call.respondRedirect("/c/${c.id}")
        }

        route("/c/{cid}") {

            get {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val nodes = d.graph.listNodesWithProps(c.id)
                val byId = nodes.associateBy { it.id }
                val edges = d.graph.listEdges(c.id)
                fun linked(n: Node, o: LinkOption): List<Node> = edges.filter { it.type == o.edgeType }.mapNotNull { e ->
                    if (o.direction == Direction.OUT) e.toId.takeIf { e.fromId == n.id } else e.fromId.takeIf { e.toId == n.id }
                }.mapNotNull { byId[it] }
                // A node whose type has a `context` link is listed under that node, e.g. a minor location under its landmark
                val parent = nodes.mapNotNull { n -> m.context(n.type)?.let { o -> linked(n, o).firstOrNull()?.let { n.id to it.id } } }.toMap()
                val columns = nodes.map { it.type }.distinct().associateWith { Overview.columns(m, it) }
                fun row(n: Node): OverviewRow = OverviewRow(
                    n.id, n.code, m.displayTitle(n.type, n.title, n.props),
                    Overview.cells(columns.getValue(n.type), PropsForm.fields(m.propsSchema(n.type), n.props).associateBy { it.name }) { o ->
                        linked(n, o).map { m.displayTitle(it.type, it.title, it.props) }
                    },
                    children = nodes.filter { parent[it.id] == n.id }.map(::row),
                    muted = m.overviewMuted(n.type)?.let { n.props[it] == true } == true,
                )
                val sections = nodes.filter { it.id !in parent }.groupBy { it.type }
                    .map { (type, ofType) ->
                        val ordered = Overview.sorted(ofType, m.overviewSort(type), { it.props }, { it.title })
                        OverviewSection(type, m.plural(type), columns.getValue(type), ordered.map(::row), creatable = m.nodeTypes.any { it.id == type })
                    }
                    .sortedWith(compareBy({ m.overviewRank(it.typeId) }, { it.label }))
                call.html("campaign.peb", mapOf("campaign" to c, "module" to m, "sections" to sections))
            }

            post("/nodes") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val p = call.receiveParameters()
                val type = m.nodeTypes.firstOrNull { it.id == p["type"] } ?: throw BadRequest("Unknown type")
                val title = newTitle(c, type.id, type.label, p["title"])
                val n = d.graph.createNode(c.id, type.id, type.codePrefix, title)
                call.respondRedirect("/c/${c.id}/n/${n.id}")
            }

            // Module views, e.g. Heart's GM inspiration page: the module's template over the campaign's nodes of some types.
            get("/v/{view}") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val view = m.view(call.parameters["view"].orEmpty()) ?: throw NotFound("View")
                val data = ModuleViews.build(m, view, d.graph.listNodesWithProps(c.id), d.graph.listEdges(c.id))
                call.html(view.template, mapOf(
                    "campaign" to c, "module" to m, "view" to view, "nodes" to data.nodes, "groups" to data.groups,
                ))
            }

            // Random tables: the campaign's own lists; each fills chosen fields through a Random button on node pages
            route("/tables") {
                fun ApplicationCall.table(c: Campaign) = d.tables.get(c.id, uuid("tid")) ?: throw NotFound("Table")

                get {
                    val c = call.campaign()
                    val m = d.modules.get(c.gameModule)
                    val labels = RandomTables.fieldOptions(m).associate { it.key to it.label }
                    call.html("tables.peb", mapOf(
                        "campaign" to c, "module" to m, "tables" to d.tables.list(c.id),
                        "labels" to labels,
                    ))
                }

                post {
                    val c = call.campaign()
                    val name = call.receiveParameters()["name"]?.trim().orEmpty().ifEmpty { throw BadRequest("Name is required") }
                    call.respondRedirect("/c/${c.id}/tables/${d.tables.create(c.id, name)}")
                }

                get("/{tid}") {
                    val c = call.campaign()
                    val m = d.modules.get(c.gameModule)
                    val t = call.table(c)
                    call.html("table.peb", mapOf(
                        "campaign" to c, "module" to m, "table" to t, "options" to RandomTables.fieldOptions(m), "errors" to emptyList<String>(),
                    ))
                }

                // Autosave, like node pages: only the status is swapped
                post("/{tid}") {
                    val c = call.campaign()
                    val t = call.table(c)
                    val p = call.receiveParameters()
                    val name = p["name"]?.trim().orEmpty().ifEmpty { t.name }
                    d.tables.update(c.id, t.id, name, p["items"].orEmpty(), mix = p["mix"] != null)
                    d.tables.setFields(c.id, t.id, p.getAll("fields").orEmpty())
                    if (p["autosave"] == "1") call.html("node/_save_status.peb", mapOf("errors" to emptyList<String>()))
                    else call.respondRedirect("/c/${c.id}/tables/${t.id}")
                }

                // A Random button's roll; avoids repeating the field's current value
                get("/{tid}/roll") {
                    val c = call.campaign()
                    val value = d.tables.roll(c.id, call.table(c).id, call.request.queryParameters["avoid"]) ?: throw NotFound("Items")
                    call.respondText(value, ContentType.Text.Plain)
                }

                post("/{tid}/delete") {
                    val c = call.campaign()
                    d.tables.delete(c.id, call.table(c).id)
                    if (call.isHtmx()) {
                        call.response.header("HX-Redirect", "/c/${c.id}/tables")
                        call.respondText("")
                    } else call.respondRedirect("/c/${c.id}/tables")
                }
            }

            // Campaign map: places drawn by a server-side layout, so the PDF matches the screen.
            get("/map") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                call.html("map.peb", mapOf("campaign" to c, "module" to m, "map" to d.map.view(c, m)))
            }

            get("/map.pdf") {
                val c = call.campaign()
                val html = d.templates.render("print/map.peb", mapOf("campaign" to c, "map" to d.map.view(c, d.modules.get(c.gameModule))))
                val pdf = withContext(Dispatchers.IO) { d.print.pdf(html) }
                val fileName = "${c.name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}-map.pdf"
                call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$fileName\"")
                call.respondBytes(pdf, ContentType.Application.Pdf, HttpStatusCode.OK)
            }

            get("/n/{nid}") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                call.html("node.peb", nodeModel(c, m, call.node(c)))
            }

            // Save: htmx swaps only the form, so the page and sidebar stay put.
            post("/n/{nid}") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val p = call.receiveParameters()
                val (props, parseErrors) = PropsForm.parse(m.propsSchema(n.type), p.asMap())
                val errors = parseErrors + m.validateProps(n.type, props)
                val title = p["title"]?.trim().orEmpty().ifEmpty { n.title }
                val body = p["body"].orEmpty()
                // Visibility isn't in the form for now; keep the stored value unless one is posted.
                val vis = Visibility.of(p["visibility"] ?: n.visibility)
                if (errors.isEmpty()) d.graph.updateNode(c.id, n.id, title, body, vis, props)
                // On error, re-show what the user typed rather than the stored values.
                val shown = if (errors.isEmpty()) call.node(c)
                    else n.copy(title = title, body = body, visibility = vis.dbValue,
                                props = GameModules.mapper.convertValue(props, Map::class.java) as Map<String, Any?>)
                val model = nodeModel(c, m, shown, errors)
                // Autosave swaps only the status (errors, or nothing), so focus and typed text stay put
                when {
                    p["autosave"] == "1" -> call.html("node/_save_status.peb", model)
                    call.isHtmx() -> call.html("node/_form.peb", model)
                    else -> call.html("node.peb", model)
                }
            }

            post("/n/{nid}/delete") {
                val c = call.campaign()
                d.graph.deleteNode(c.id, call.node(c).id)
                if (call.isHtmx()) {
                    call.response.header("HX-Redirect", "/c/${c.id}")
                    call.respondText("")
                } else call.respondRedirect("/c/${c.id}")
            }

            // Link picker: results for the chosen link type, filtered by the module's endpoint rules.
            get("/n/{nid}/link-search") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val rel = call.request.queryParameters["rel"].orEmpty()
                val q = call.request.queryParameters["q"].orEmpty().trim()
                // Empty rel = Any: every relation this node can take, most useful first
                val options = if (rel.isEmpty()) orderedLinkOptions(m, n) else listOf(parseOption(m, n, rel))
                // This node already full for an option (e.g. a delve with two landmarks): that option is disabled everywhere
                val ownFull = options.associateWith { limitError(c, m, n.id, n.type, "${n.code} ${n.title}", it.edgeType, it.direction) }
                val found = if (rel.isEmpty() && q.isEmpty()) emptyList() else d.graph.search(c.id, q, n.id, limit = 100)
                    .filter { other -> options.any { m.acceptsOther(it, other.type) } }
                    .take(20)
                val labels = pickerLabels(c, m, found)
                val results = found.map { other ->
                    SearchResult(other.id, other.code, labels.getValue(other.id),
                        options.filter { m.acceptsOther(it, other.type) }.take(3).map { o ->
                            // Far end already full (e.g. a delve with two landmarks) also disables the option
                            RelationChoice(o, ownFull[o] ?: limitError(c, m, other.id, other.type, other.code, o.edgeType, o.direction.opposite))
                        })
                }
                call.html("node/_link_results.peb", mapOf("campaign" to c, "node" to n, "results" to results))
            }

            post("/n/{nid}/links") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val p = call.receiveParameters()
                val option = parseOption(m, n, p["rel"])
                val other = p["other"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?.let { d.graph.getNode(c.id, it) } ?: throw NotFound("Linked node")
                if (!m.acceptsOther(option, other.type)) throw BadRequest("Can't link ${m.label(other.type)} that way")
                link(c, m, n, option, other)
                call.response.header("HX-Trigger", "links-changed")  // refreshes the link picker (limits may have changed)
                call.html("node/_links_changed.peb", nodeModel(c, m, n))
            }

            // Checklist box (e.g. a session's landmarks): ticked = link via the list's relation, unticked = unlink.
            post("/n/{nid}/check") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val p = call.receiveParameters()
                val option = m.checklists(n.type).map { it.second }.firstOrNull { it.key == p["rel"] }
                    ?: throw BadRequest("'${p["rel"]}' is not a checklist of ${n.type}")
                val other = p["other"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?.let { d.graph.getNode(c.id, it) } ?: throw NotFound("Node")
                if (!m.acceptsOther(option, other.type)) throw BadRequest("Can't link ${m.label(other.type)} that way")
                when {
                    p["checked"] != null -> link(c, m, n, option, other)
                    option.direction == Direction.OUT -> d.graph.deleteEdgeBetween(c.id, n.id, other.id, option.edgeType)
                    else -> d.graph.deleteEdgeBetween(c.id, other.id, n.id, option.edgeType)
                }
                call.response.header("HX-Trigger", "links-changed")
                call.html("node/_links_changed.peb", nodeModel(c, m, n))
            }

            // Link slot dropdown (e.g. Location). Value "<rel>|<node id>"; empty = unlinked, "in the pool".
            post("/n/{nid}/slot") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val p = call.receiveParameters()
                val slot = p["slot"]?.toIntOrNull()?.let { m.linkSlots(n.type).getOrNull(it) }
                    ?: throw BadRequest("Unknown slot for ${n.type}")
                val value = p["value"].orEmpty()
                if (value.isEmpty()) slot.options.forEach { d.graph.deleteLinks(c.id, n.id, it.edgeType, it.direction) }
                else {
                    val option = slot.options.firstOrNull { it.key == value.substringBefore("|") }
                        ?: throw BadRequest("'${value.substringBefore("|")}' is not in slot ${slot.label}")
                    val other = runCatching { UUID.fromString(value.substringAfter("|")) }.getOrNull()
                        ?.let { d.graph.getNode(c.id, it) } ?: throw NotFound("Linked node")
                    if (!m.acceptsOther(option, other.type)) throw BadRequest("Can't link ${m.label(other.type)} that way")
                    link(c, m, n, option, other)
                }
                call.response.header("HX-Trigger", "links-changed")
                call.html("node/_links_changed.peb", nodeModel(c, m, n))
            }

            // "Add new…": create a node, link it to this one, then open it.
            post("/n/{nid}/quick-create") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val p = call.receiveParameters()
                val q = m.quickCreate(n.type).firstOrNull { it.key == p["choice"] }
                    ?: throw BadRequest("Can't create that from ${m.label(n.type)}")
                // Check the limit before creating, so a full link type doesn't leave an unlinked node behind.
                limitError(c, m, n.id, n.type, "${n.code} ${n.title}", q.option.edgeType, q.option.direction)?.let { throw BadRequest(it) }
                // The Link box sends its search text as "q"
                val title = newTitle(c, q.type.id, q.type.label, p["title"] ?: p["q"])
                val created = d.graph.createNode(c.id, q.type.id, q.type.codePrefix, title)
                link(c, m, n, q.option, created)
                if (p["stay"] == "1") {
                    // Create without opening: refresh this page's links, ready for the next one
                    call.response.header("HX-Trigger", "links-changed")
                    call.html("node/_links_changed.peb", nodeModel(c, m, n))
                } else if (call.isHtmx()) {
                    call.response.header("HX-Redirect", "/c/${c.id}/n/${created.id}")
                    call.respondText("")
                } else call.respondRedirect("/c/${c.id}/n/${created.id}")
            }

            post("/n/{nid}/links/{eid}/delete") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                d.graph.deleteEdge(c.id, call.uuid("eid"))
                call.response.header("HX-Trigger", "links-changed")  // refreshes the link picker (limits may have changed)
                call.html("node/_links_changed.peb", nodeModel(c, m, n))
            }

            // Print preview in the browser (same HTML that becomes the PDF).
            get("/n/{nid}/print") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val audience = Audience.of(call.request.queryParameters["audience"])
                call.respondText(d.print.html(c, m, call.node(c), audience), ContentType.Text.Html)
            }

            get("/n/{nid}/print.pdf") {
                val c = call.campaign()
                val m = d.modules.get(c.gameModule)
                val n = call.node(c)
                val audience = Audience.of(call.request.queryParameters["audience"])
                val pdf = withContext(Dispatchers.IO) { d.print.pdf(c, m, n, audience) }
                val fileName = "${n.code}-${n.title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')}.pdf"
                call.response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$fileName\"")
                call.respondBytes(pdf, ContentType.Application.Pdf, HttpStatusCode.OK)
            }
        }
    }
}
