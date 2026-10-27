package tablekeeper.web

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import tablekeeper.auth.user
import tablekeeper.core.Audience
import tablekeeper.core.BadRequest
import tablekeeper.core.NotFound
import tablekeeper.map.MapService
import tablekeeper.views.ModuleViews
import java.time.LocalDate

/** Under /c/{cid}: the campaign home, new nodes, module views and the map. */
fun Route.campaignRoutes(d: Deps) = with(d) {
    get {
        val (c, m) = call.game()
        call.html("campaign.peb", mapOf("campaign" to c, "module" to m, "sections" to Overview.sections(m, graph.snapshot(c.id)),
            "canPrint" to (m.campaignPacketType != null)))
    }

    // Rename (the page's title autosaves, like a node's); an empty name keeps the old one
    post {
        val (c, _) = call.game()
        val name = call.receiveParameters()["name"]?.trim().orEmpty()
        if (name.isNotEmpty()) campaigns.rename(call.user, c.id, name)
        call.html("node/_save_status.peb", mapOf("errors" to emptyList<String>()))
    }

    // Delete the campaign and everything in it, then back to the campaign list
    post("/delete") {
        val (c, _) = call.game()
        campaigns.delete(call.user, c.id)
        call.redirect("/")
    }

    post("/nodes") {
        val (c, m) = call.game()
        val p = call.receiveParameters()
        val type = m.nodeTypes.firstOrNull { it.id == p["type"] } ?: throw BadRequest("Unknown type")
        val n = graph.createNode(c.id, type.id, type.codePrefix, newTitle(c, type.id, type.label, p["title"]))
        call.respondRedirect("/c/${c.id}/n/${n.id}?new=1")   // a new node opens with its title selected
    }

    // A module's generator (Create ▾ → Generate dungeon): makes its nodes and links, then opens the first with its name selected
    post("/generate/{gid}") {
        val (c, m) = call.game()
        val def = m.generator(call.parameters["gid"].orEmpty()) ?: throw NotFound("Generator")
        val first = generate.run(c, m, def)
        call.respondRedirect("/c/${c.id}/n/${first.id}?new=1")
    }

    // Module views, e.g. Heart's GM inspiration page: the module's template over the campaign's nodes of some types.
    get("/v/{view}") {
        val (c, m) = call.game()
        val view = m.view(call.parameters["view"].orEmpty()) ?: throw NotFound("View")
        val all = graph.snapshot(c.id)
        val data = ModuleViews.build(m, view, all.nodes, all)
        call.html(view.template, mapOf(
            "campaign" to c, "module" to m, "view" to view, "nodes" to data.nodes, "groups" to data.groups,
        ))
    }

    // Quick find (top bar): nodes whose title or code matches what's typed
    get("/find") {
        val (c, m) = call.game()
        val q = call.request.queryParameters["q"].orEmpty().trim()
        val choices = if (q.isEmpty()) emptyList() else nodePage.choices(c, m, q, exclude = null, limit = 12)
        call.html("_find.peb", mapOf("campaign" to c, "module" to m, "choices" to choices))
    }

    // Campaign map (linked above the campaign page's title): places drawn by a server-side layout, so the PDF matches the screen.
    get("/map") {
        val (c, m) = call.game()
        call.html("map.peb", mapOf("campaign" to c, "module" to m, "map" to MapService.view(graph.snapshot(c.id), m), "base" to "/c/${c.id}"))
    }

    // The whole campaign as a JSON file, to keep as a backup or import elsewhere (home page)
    get("/export.json") {
        val (c, _) = call.game()
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"${slug(c.name)}-${LocalDate.now()}.json\"")
        call.respondBytes(transfer.toJson(transfer.export(c)), ContentType.Application.Json)
    }

    // The campaign's print (linked above its title): a session packet with every checklist box ticked
    get("/print") {
        val (c, m) = call.game()
        val n = print.campaignPacket(c, m) ?: throw NotFound("Print")
        call.respondText(print.html(c, m, n, Audience.of(call.request.queryParameters["audience"]), allTicked = true), ContentType.Text.Html)
    }

    get("/print.pdf") {
        val (c, m) = call.game()
        val n = print.campaignPacket(c, m) ?: throw NotFound("Print")
        call.pdf(slug(c.name)) { print.pdf(c, m, n, Audience.of(call.request.queryParameters["audience"]), allTicked = true) }
    }

    get("/map.pdf") {
        val (c, m) = call.game()
        call.pdf("${slug(c.name)}-map") { print.mapPdf(c, c.name, MapService.view(graph.snapshot(c.id), m)) }
    }
}
