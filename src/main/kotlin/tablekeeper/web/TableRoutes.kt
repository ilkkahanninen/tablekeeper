package tablekeeper.web

import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import tablekeeper.core.BadRequest
import tablekeeper.core.Campaign
import tablekeeper.core.NotFound

/** Under /c/{cid}/tables: the campaign's random tables; each fills chosen fields through a Random button on node pages. */
fun Route.tableRoutes(d: Deps) = with(d) {
    fun ApplicationCall.table(c: Campaign) = tables.get(c.id, uuid("tid")) ?: throw NotFound("Table")

    get {
        val (c, m) = call.game()
        val list = tables.list(c.id)
        call.html("tables.peb", mapOf(
            "campaign" to c, "module" to m, "tables" to list,
            "labels" to m.fillableFields().associate { it.key to it.label },
            // The game's starter tables this campaign hasn't got (by name): older campaigns, or deleted ones
            "missingSeeds" to m.tables.count { s -> list.none { it.name == s.name } },
        ))
    }

    // "Add the game's tables": the module's starter tables the campaign lacks
    post("/seed") {
        val (c, m) = call.game()
        tables.seed(c.id, m.tables)
        call.respondRedirect("/c/${c.id}/tables")
    }

    post {
        val (c, _) = call.game()
        val name = call.receiveParameters()["name"]?.trim().orEmpty().ifEmpty { throw BadRequest("Name is required") }
        call.respondRedirect("/c/${c.id}/tables/${tables.create(c.id, name)}")
    }

    get("/{tid}") {
        val (c, m) = call.game()
        call.html("table.peb", mapOf(
            "campaign" to c, "module" to m, "table" to call.table(c), "options" to m.fillableFields(), "errors" to emptyList<String>(),
        ))
    }

    // Autosave, like node pages: only the status is swapped
    post("/{tid}") {
        val (c, _) = call.game()
        val t = call.table(c)
        val p = call.receiveParameters()
        val name = p["name"]?.trim().orEmpty().ifEmpty { t.name }
        tables.update(c.id, t.id, name, p["items"].orEmpty(), mix = p["mix"] != null)
        tables.setFields(c.id, t.id, p.getAll("fields").orEmpty())
        if (p["autosave"] == "1") call.html("node/_save_status.peb", mapOf("errors" to emptyList<String>()))
        else call.respondRedirect("/c/${c.id}/tables/${t.id}")
    }

    // A Random button's roll; avoids repeating the field's current value
    get("/{tid}/roll") {
        val (c, _) = call.game()
        val value = tables.roll(c.id, call.table(c).id, call.request.queryParameters["avoid"]) ?: throw NotFound("Items")
        call.respondText(value, ContentType.Text.Plain)
    }

    post("/{tid}/delete") {
        val (c, _) = call.game()
        tables.delete(c.id, call.table(c).id)
        call.redirect("/c/${c.id}/tables")
    }
}
