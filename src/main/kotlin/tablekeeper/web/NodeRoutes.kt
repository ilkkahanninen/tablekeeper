package tablekeeper.web

import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import tablekeeper.core.Audience
import tablekeeper.core.BadRequest
import tablekeeper.core.Json
import tablekeeper.core.Visibility
import tablekeeper.modules.PropsForm

/** Under /c/{cid}/n/{nid}: a node's page, its links, and its print. */
fun Route.nodeRoutes(d: Deps) = with(d) {
    get {
        val (c, m) = call.game()
        call.html("node.peb", nodePage.model(c, m, call.node(c)))
    }

    // Save: htmx swaps only the form, so the page and sidebar stay put.
    post {
        val (c, m) = call.game()
        val n = call.node(c)
        val p = call.receiveParameters()
        // "only": a few fields posted on their own (a session's debrief), merged over the stored props; "clear" empties them (Done)
        val posted = if (p["clear"] == "1") emptyMap() else p.asMap()
        val (props, parseErrors) = PropsForm.parse(m.propsSchema(n.type), posted, p.getAll("only")?.toSet(), n.props)
        val errors = parseErrors + m.validateProps(n.type, props)
        val title = p["title"]?.trim().orEmpty().ifEmpty { n.title }
        // Missing (a partial save from a debrief) keeps the notes; an empty field clears them
        val body = p["body"] ?: n.body
        // Visibility isn't in the form for now; keep the stored value unless one is posted.
        val vis = Visibility.of(p["visibility"] ?: n.visibility)
        if (errors.isEmpty()) {
            graph.updateNode(c.id, n.id, title, body, vis, props)
            // Renamed: [[Old name]] mentions follow, so they keep pointing here
            if (title != n.title) graph.renameMentions(c.id, n.title, title)
        }
        // On error, re-show what the user typed rather than the stored values.
        @Suppress("UNCHECKED_CAST")
        val shown = if (errors.isEmpty()) call.node(c)
            else n.copy(title = title, body = body, visibility = vis.dbValue,
                        props = Json.mapper.convertValue(props, Map::class.java) as Map<String, Any?>)
        val model = nodePage.model(c, m, shown, errors)
        // Autosave swaps only the status (errors, or nothing), so focus and typed text stay put
        if (p["refresh"] == "1") call.response.header("HX-Trigger", DEBRIEF_CHANGED)
        when {
            // A full form save also refreshes the formatted notes; a partial one (debrief) is about another page's node
            p["autosave"] == "1" -> call.html("node/_save_status.peb", model + ("notesOob" to (p.getAll("only") == null)))
            call.isHtmx() -> call.html("node/_form.peb", model)
            else -> call.html("node.peb", model)
        }
    }

    post("/delete") {
        val (c, _) = call.game()
        // A Delete button may post no form at all; "quiet" comes from a session's debrief (removing a fallout)
        val p = runCatching { call.receiveParameters() }.getOrDefault(Parameters.Empty)
        graph.deleteNode(c.id, call.node(c).id)
        if (p["quiet"] == "1") call.quiet(p) else call.redirect("/c/${c.id}")
    }

    // Link box: results for the chosen link type, filtered by the module's endpoint rules.
    get("/link-search") {
        val (c, m) = call.game()
        val n = call.node(c)
        val q = call.request.queryParameters
        val results = nodePage.search(c, m, n, q["rel"].orEmpty(), q["q"].orEmpty().trim())
        call.html("node/_link_results.peb", mapOf("campaign" to c, "module" to m, "node" to n, "results" to results))
    }

    post("/links") {
        val g = call.game()
        val n = call.node(g.campaign)
        val p = call.receiveParameters()
        val option = g.module.requireLinkOption(n, p["rel"])
        linker.link(g.campaign.id, g.module, n, option, otherNode(g, p["other"], option))
        call.linksChanged(g, n)
    }

    // Checklist box (e.g. a session's landmarks): ticked = link via the list's relation, unticked = unlink.
    post("/check") {
        val g = call.game()
        val n = call.node(g.campaign)
        val p = call.receiveParameters()
        val option = g.module.checklists(n.type).map { it.second }.firstOrNull { it.key == p["rel"] }
            ?: throw BadRequest("'${p["rel"]}' is not a checklist of ${n.type}")
        val other = otherNode(g, p["other"], option, what = "Node")
        if (p["checked"] != null) linker.link(g.campaign.id, g.module, n, option, other)
        else linker.unlink(g.campaign.id, n, option, other)
        call.linksChanged(g, n)
    }

    // Link slot dropdown (e.g. Location). Value "<rel>|<node id>"; empty = unlinked, "in the pool".
    post("/slot") {
        val g = call.game()
        val n = call.node(g.campaign)
        val p = call.receiveParameters()
        val slot = p["slot"]?.toIntOrNull()?.let { g.module.linkSlots(n.type).getOrNull(it) }
            ?: throw BadRequest("Unknown slot for ${n.type}")
        val value = p["value"].orEmpty()
        if (value.isEmpty()) linker.clear(g.campaign.id, n, slot)
        else {
            val rel = value.substringBefore("|")
            val option = slot.options.firstOrNull { it.key == rel } ?: throw BadRequest("'$rel' is not in slot ${slot.label}")
            linker.link(g.campaign.id, g.module, n, option, otherNode(g, value.substringAfter("|"), option))
        }
        if (p["quiet"] == "1") call.quiet(p) else call.linksChanged(g, n)
    }

    // "Add new…": create a node, link it to this one, then open it.
    post("/quick-create") {
        val g = call.game()
        val (c, m) = g
        val n = call.node(c)
        val p = call.receiveParameters()
        val q = m.quickCreate(n.type).firstOrNull { it.key == p["choice"] }
            ?: throw BadRequest("Can't create that from ${m.label(n.type)}")
        // Check the limit before creating, so a full link type doesn't leave an unlinked node behind.
        linker.limitError(c.id, m, n, q.option)?.let { throw BadRequest(it) }
        // The Link box sends its search text as "q"
        val created = graph.createNode(c.id, q.type.id, q.type.codePrefix, newTitle(c, q.type.id, q.type.label, p["title"] ?: p["q"]))
        linker.link(c.id, m, n, q.option, created)
        // "stay": create without opening, refreshing this page's links, ready for the next one
        if (p["quiet"] == "1") call.quiet(p)
        else if (p["stay"] == "1") call.linksChanged(g, n)
        else call.redirect("/c/${c.id}/n/${created.id}")
    }

    post("/links/{eid}/delete") {
        val g = call.game()
        val n = call.node(g.campaign)
        graph.deleteEdge(g.campaign.id, call.uuid("eid"))
        call.linksChanged(g, n)
    }

    // Duplicate: a copy with the same fields and notes but no links, then open it
    post("/duplicate") {
        val (c, m) = call.game()
        val n = call.node(c)
        val type = m.type(n.type) ?: throw BadRequest("Unknown type ${n.type}")
        val copy = graph.duplicateNode(n, type.codePrefix, "${n.title} (copy)")
        call.redirect("/c/${c.id}/n/${copy.id}")
    }

    // The [[ picker in notes: nodes whose title or code matches what's typed after [[
    get("/mentions") {
        val (c, m) = call.game()
        val n = call.node(c)
        call.html("node/_mentions.peb", mapOf("module" to m, "choices" to nodePage.mentionChoices(c, m, n, call.request.queryParameters["q"].orEmpty().trim())))
    }

    // A session's debrief section, reloaded after something is added or done there
    get("/debrief") {
        val (c, m) = call.game()
        val n = call.node(c)
        call.html("node/_debrief.peb", mapOf("campaign" to c, "module" to m, "node" to n, "debrief" to nodePage.debrief(c, m, n)))
    }

    // Print preview in the browser (same HTML that becomes the PDF).
    get("/print") {
        val (c, m) = call.game()
        call.respondText(print.html(c, m, call.node(c), Audience.of(call.request.queryParameters["audience"])), ContentType.Text.Html)
    }

    get("/print.pdf") {
        val (c, m) = call.game()
        val n = call.node(c)
        val audience = Audience.of(call.request.queryParameters["audience"])
        call.pdf("${n.code}-${slug(n.title)}") { print.pdf(c, m, n, audience) }
    }
}
