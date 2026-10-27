package tablekeeper

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.csrf.CSRF
import io.ktor.server.request.accept
import io.ktor.server.request.httpMethod
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respondRedirect
import io.ktor.server.response.respondText
import org.jooq.DSLContext
import tablekeeper.auth.Accounts
import tablekeeper.auth.NotAuthenticated
import tablekeeper.auth.SessionCookie
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
import tablekeeper.web.loginUrl
import tablekeeper.web.routes

fun main() {
    val config = Config.fromEnv()
    val deps = deps(Database.connect(config), GameModules.load(config.modulesDir), Templates(config.modulesDir, cache = config.templateCache),
        config.authMode, config.publicUrl)
    val resolver = userResolverFor(config.authMode, deps.campaigns, deps.accounts, deps.sessionCookie)

    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        install(CallLogging)
        app(deps, resolver)
    }.start(wait = true)
}

/** The services over [db]. [publicUrl]: where users reach the app (Config.publicUrl), if it's behind a proxy. */
fun deps(db: DSLContext, modules: GameModules, templates: Templates, authMode: AuthMode = AuthMode.NONE, publicUrl: String? = null): Deps {
    val graph = GraphService(db)
    val chromium = Chromium().also { c -> Runtime.getRuntime().addShutdownHook(Thread { c.close() }) }
    return Deps(CampaignService(db), graph, modules, templates, PrintService(graph, templates, chromium), RandomTableService(db), CampaignTransfer(db),
        authMode, Accounts(db), SessionCookie(secure = publicUrl?.startsWith("https://") == true), publicUrl)
}

/** The app without its server: who's asking, domain errors as HTTP statuses, and the routes (the smoke tests run this too). */
fun Application.app(deps: Deps, resolver: UserResolver) {
    // Every POST must come from the app's own pages (its Origin, or Referer): another site can't make a visitor's browser
    // change anything, logged in (with SameSite=Lax cookies) or in AUTH_MODE=none. Behind a proxy, from PUBLIC_URL.
    install(CSRF) {
        deps.publicUrl?.let { allowOrigin(it) } ?: originMatchesHost()
        onFailure { reason -> respondText("Refused: not from Tablekeeper's own pages ($reason)", ContentType.Text.Plain, HttpStatusCode.Forbidden) }
    }
    install(currentUserPlugin(resolver))
    install(StatusPages) {
        exception<NotFound> { call, e -> call.respondText(e.message ?: "Not found", ContentType.Text.Plain, HttpStatusCode.NotFound) }
        exception<BadRequest> { call, e -> call.respondText(e.message ?: "Bad request", ContentType.Text.Plain, HttpStatusCode.BadRequest) }
        exception<HiddenFromAudience> { call, e -> call.respondText(e.message ?: "Forbidden", ContentType.Text.Plain, HttpStatusCode.Forbidden) }
        // Not logged in: a page goes to the login, which comes back here after; htmx follows HX-Redirect; anything else is 401
        exception<NotAuthenticated> { call, _ ->
            val htmxPage = call.request.headers["HX-Current-URL"]?.let { Url(it).encodedPathAndQuery }
            when {
                call.request.headers["HX-Request"] == "true" -> {
                    call.response.header("HX-Redirect", loginUrl(htmxPage))
                    call.respondText("", status = HttpStatusCode.Unauthorized)
                }
                call.request.httpMethod == HttpMethod.Get && call.request.accept().orEmpty().contains("text/html") ->
                    call.respondRedirect(loginUrl(call.request.uri))
                else -> call.respondText("Login required", ContentType.Text.Plain, HttpStatusCode.Unauthorized)
            }
        }
    }
    routes(deps)
}
