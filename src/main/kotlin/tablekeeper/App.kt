package tablekeeper

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respondText
import org.jooq.DSLContext
import tablekeeper.auth.NotAuthenticated
import tablekeeper.auth.UserResolver
import tablekeeper.auth.currentUserPlugin
import tablekeeper.auth.userResolverFor
import tablekeeper.core.BadRequest
import tablekeeper.core.CampaignService
import tablekeeper.core.CampaignTransfer
import tablekeeper.core.GraphService
import tablekeeper.core.HiddenFromAudience
import tablekeeper.core.NotFound
import tablekeeper.core.RandomTableService
import tablekeeper.db.Database
import tablekeeper.modules.GameModules
import tablekeeper.print.Chromium
import tablekeeper.print.PrintService
import tablekeeper.templates.Templates
import tablekeeper.web.Deps
import tablekeeper.web.routes

fun main() {
    val config = Config.fromEnv()
    val deps = deps(Database.connect(config), GameModules.load(config.modulesDir), Templates(config.modulesDir, cache = config.templateCache))
    val resolver = userResolverFor(config.authMode, deps.campaigns)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        install(CallLogging)
        app(deps, resolver)
    }.start(wait = true)
}

/** The services over [db]. */
fun deps(db: DSLContext, modules: GameModules, templates: Templates): Deps {
    val graph = GraphService(db)
    val chromium = Chromium().also { c -> Runtime.getRuntime().addShutdownHook(Thread { c.close() }) }
    return Deps(CampaignService(db), graph, modules, templates, PrintService(graph, templates, chromium), RandomTableService(db), CampaignTransfer(db))
}

/** The app without its server: who's asking, domain errors as HTTP statuses, and the routes (the smoke tests run this too). */
fun Application.app(deps: Deps, resolver: UserResolver) {
    install(currentUserPlugin(resolver))
    install(StatusPages) {
        exception<NotFound> { call, e -> call.respondText(e.message ?: "Not found", ContentType.Text.Plain, HttpStatusCode.NotFound) }
        exception<BadRequest> { call, e -> call.respondText(e.message ?: "Bad request", ContentType.Text.Plain, HttpStatusCode.BadRequest) }
        exception<HiddenFromAudience> { call, e -> call.respondText(e.message ?: "Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden) }
        exception<NotAuthenticated> { call, _ -> call.respondText("Login required", ContentType.Text.Plain, HttpStatusCode.Unauthorized) }
    }
    routes(deps)
}
