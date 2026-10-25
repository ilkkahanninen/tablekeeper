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
import tablekeeper.core.BadRequest
import tablekeeper.core.Campaign
import tablekeeper.core.CampaignService
import tablekeeper.core.GraphService
import tablekeeper.core.Node
import tablekeeper.core.NotFound
import tablekeeper.core.RandomTableService
import tablekeeper.modules.GameModules
import tablekeeper.modules.LinkOption
import tablekeeper.modules.Linker
import tablekeeper.modules.ResolvedModule
import tablekeeper.print.PrintService
import tablekeeper.templates.Templates
import java.util.UUID

/** A campaign and the game module it's played with: what nearly every handler starts from. */
data class Game(val campaign: Campaign, val module: ResolvedModule)

/**
 * Everything the routes use, plus the request helpers they share. Route files run inside `with(d)`,
 * so `call.game()`, `call.html(…)` etc. read like ordinary calls.
 */
class Deps(
    val campaigns: CampaignService,
    val graph: GraphService,
    val modules: GameModules,
    val templates: Templates,
    val print: PrintService,
    val tables: RandomTableService,
) {
    val linker = Linker(graph)
    val nodePage = NodePage(graph, linker, tables, print)

    suspend fun ApplicationCall.html(template: String, model: Map<String, Any?>) =
        respondText(templates.render(template, model), ContentType.Text.Html)

    fun ApplicationCall.uuid(name: String): UUID = parameters[name].toUuid() ?: throw NotFound(name)

    /** The URL's campaign, owned by the current user, with its module; 404 otherwise. */
    fun ApplicationCall.game(): Game {
        val c = campaigns.get(user, uuid("cid")) ?: throw NotFound("Campaign")
        return Game(c, modules.get(c.gameModule))
    }

    fun ApplicationCall.node(c: Campaign): Node = graph.getNode(c.id, uuid("nid")) ?: throw NotFound("Node")

    /** The node with id [id] (a posted value), checked to fit at the far end of [option]. */
    fun otherNode(g: Game, id: String?, option: LinkOption, what: String = "Linked node"): Node {
        val other = id.toUuid()?.let { graph.getNode(g.campaign.id, it) } ?: throw NotFound(what)
        if (!g.module.acceptsOther(option, other.type)) throw BadRequest("Can't link ${g.module.label(other.type)} that way")
        return other
    }

    fun ApplicationCall.isHtmx() = request.headers["HX-Request"] == "true"

    /** To [url]: by HX-Redirect for htmx requests (a full page load), else a plain redirect. */
    suspend fun ApplicationCall.redirect(url: String) {
        if (isHtmx()) {
            response.header("HX-Redirect", url)
            respondText("")
        } else respondRedirect(url)
    }

    /** After a link change: the node page's link parts, and an event that refreshes the Link box (limits may have changed). */
    suspend fun ApplicationCall.linksChanged(g: Game, n: Node) {
        response.header("HX-Trigger", "links-changed")
        html("node/_links_changed.peb", nodePage.model(g.campaign, g.module, n))
    }

    /**
     * Done, for a change made from another node's page (a session's debrief), so there are no page parts to swap.
     * "refresh" in [p] reloads that debrief, e.g. after adding a fallout.
     */
    suspend fun ApplicationCall.quiet(p: Parameters) {
        if (p["refresh"] == "1") response.header("HX-Trigger", DEBRIEF_CHANGED)
        respondText("")
    }

    /** A PDF shown in the browser, saved as "<fileName>.pdf". Rendered off the request thread. */
    suspend fun ApplicationCall.pdf(fileName: String, render: () -> ByteArray) {
        val pdf = withContext(Dispatchers.IO) { render() }
        response.header(HttpHeaders.ContentDisposition, "inline; filename=\"$fileName.pdf\"")
        respondBytes(pdf, ContentType.Application.Pdf, HttpStatusCode.OK)
    }

    fun Parameters.asMap(): Map<String, List<String>> = entries().associate { it.key to it.value }

    /** A new node's title: the given one, else a roll on the type's title tables, else "New <type>". */
    fun newTitle(c: Campaign, typeId: String, typeLabel: String, given: String?): String =
        given?.trim()?.takeIf { it.isNotEmpty() } ?: tables.rollTitle(c.id, typeId) ?: "New $typeLabel"
}

/** Event that reloads a session page's debrief (`node/_debrief.peb`). */
const val DEBRIEF_CHANGED = "debrief-changed"

private fun String?.toUuid(): UUID? = this?.let { runCatching { UUID.fromString(it) }.getOrNull() }

/** For file names: "Red Market!" -> "red-market". */
internal fun slug(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

fun Application.routes(d: Deps) = with(d) {
    routing {
        staticResources("/static", "static")

        get("/") {
            call.html("index.peb", mapOf(
                "campaigns" to campaigns.list(call.user),
                "modules" to modules.all,
            ))
        }

        post("/campaigns") {
            val p = call.receiveParameters()
            val name = p["name"]?.trim().orEmpty().ifEmpty { throw BadRequest("Name is required") }
            val module = modules.get(p["module"] ?: "heart")
            val c = campaigns.create(call.user, name, module.id)
            call.respondRedirect("/c/${c.id}")
        }

        route("/c/{cid}") {
            campaignRoutes(d)
            route("/tables") { tableRoutes(d) }
            route("/n/{nid}") { nodeRoutes(d) }
        }
    }
}
