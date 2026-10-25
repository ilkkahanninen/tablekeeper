package tablekeeper

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import tablekeeper.auth.NotAuthenticated
import tablekeeper.auth.currentUserPlugin
import tablekeeper.auth.userResolverFor
import tablekeeper.core.CampaignService
import tablekeeper.core.GraphService
import tablekeeper.db.Database
import tablekeeper.map.MapService
import tablekeeper.modules.GameModules
import tablekeeper.print.HiddenFromAudience
import tablekeeper.print.PrintService
import tablekeeper.web.BadRequest
import tablekeeper.web.Deps
import tablekeeper.web.NotFound
import tablekeeper.web.Templates
import tablekeeper.web.routes

fun main() {
    val config = Config.fromEnv()
    val db = Database.connect(config)
    val modules = GameModules.load(config.modulesDir)
    val campaigns = CampaignService(db)
    val graph = GraphService(db)
    val templates = Templates(config.modulesDir, cache = config.templateCache)
    val map = MapService(graph)
    val deps = Deps(campaigns, graph, modules, templates, PrintService(graph, templates, map), map)
    val resolver = userResolverFor(config.authMode, campaigns)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        install(CallLogging)
        install(currentUserPlugin(resolver))
        install(StatusPages) {
            exception<NotFound> { call, e -> call.respondText(e.message ?: "Not found", ContentType.Text.Plain, HttpStatusCode.NotFound) }
            exception<BadRequest> { call, e -> call.respondText(e.message ?: "Bad request", ContentType.Text.Plain, HttpStatusCode.BadRequest) }
            exception<HiddenFromAudience> { call, e -> call.respondText(e.message ?: "Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden) }
            exception<NotAuthenticated> { call, _ -> call.respondText("Login required", ContentType.Text.Plain, HttpStatusCode.Unauthorized) }
        }
        routes(deps)
    }.start(wait = true)
}
