package tablekeeper.web

import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import tablekeeper.core.BadRequest
import tablekeeper.core.NotFound
import tablekeeper.map.MapService
import tablekeeper.views.ModuleViews

/** Under /c/{cid}: the campaign home, new nodes, module views and the map. */
fun Route.campaignRoutes(d: Deps) = with(d) {
    get {
        val (c, m) = call.game()
        call.html("campaign.peb", mapOf("campaign" to c, "module" to m, "sections" to Overview.sections(m, graph.snapshot(c.id))))
    }

    post("/nodes") {
        val (c, m) = call.game()
        val p = call.receiveParameters()
        val type = m.nodeTypes.firstOrNull { it.id == p["type"] } ?: throw BadRequest("Unknown type")
        val n = graph.createNode(c.id, type.id, type.codePrefix, newTitle(c, type.id, type.label, p["title"]))
        call.respondRedirect("/c/${c.id}/n/${n.id}")
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

    // Campaign map: places drawn by a server-side layout, so the PDF matches the screen.
    get("/map") {
        val (c, m) = call.game()
        call.html("map.peb", mapOf("campaign" to c, "module" to m, "map" to MapService.view(graph.snapshot(c.id), m)))
    }

    get("/map.pdf") {
        val (c, m) = call.game()
        call.pdf("${slug(c.name)}-map") { print.mapPdf(c, m) }
    }
}
