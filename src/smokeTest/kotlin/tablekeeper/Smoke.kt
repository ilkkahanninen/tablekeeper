package tablekeeper

import io.ktor.client.HttpClient
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import io.ktor.server.testing.testApplication
import org.testcontainers.postgresql.PostgreSQLContainer
import tablekeeper.auth.SessionUserResolver
import tablekeeper.auth.UserResolver
import tablekeeper.core.CampaignExport
import tablekeeper.core.Json
import tablekeeper.core.Node
import tablekeeper.db.Database
import tablekeeper.modules.GameModules
import tablekeeper.templates.Templates
import tablekeeper.web.Deps
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Postgres in Docker, started once for all smoke tests. Every test makes its own campaigns and users, so tests don't meet. */
object SmokeDb {
    private val dir = System.getProperty("modulesDir") ?: "modules"
    private val db by lazy {
        val pg = PostgreSQLContainer("postgres:16").apply { start() }
        // As hosting hands it out: one postgres:// URL with the login in it
        Database.connect(Config.fromEnv(mapOf("DATABASE_URL" to
            "postgres://${pg.username}:${pg.password}@${pg.host}:${pg.firstMappedPort}/${pg.databaseName}")))
    }

    /** AUTH_MODE=none: the user comes from the [USER] header. */
    val deps: Deps by lazy { deps(db, GameModules.load(dir), Templates(dir, cache = true)) }

    /** AUTH_MODE=session: the user comes from the session cookie. */
    val sessionDeps: Deps by lazy { deps(db, GameModules.load(dir), Templates(dir, cache = true), AuthMode.SESSION) }
}

/** Where the test client's requests come from, as a browser on the app's own pages says (the CSRF check). */
const val ORIGIN = "http://localhost"

/** What every browser sends and the test client doesn't: the Host, which the CSRF check compares the Origin with. */
private fun DefaultRequest.DefaultRequestBuilder.asBrowser(origin: String?) {
    header(HttpHeaders.Host, "localhost")
    origin?.let { header(HttpHeaders.Origin, it) }
}

/** The request header that picks the user, so a test can be someone else; without it, requests are "alice"'s. */
const val USER = "X-Smoke-User"

/** Runs [block] against the whole app (plugins, routes, templates, SQL) as one in-process server. */
fun smoke(block: suspend Smoke.() -> Unit) = testApplication {
    val d = SmokeDb.deps
    application { app(d, UserResolver { call -> d.campaigns.ensureUser(call.request.headers[USER] ?: "alice") }) }
    Smoke(createClient { followRedirects = false; defaultRequest { asBrowser(ORIGIN) } }, d).block()
}

/**
 * Runs [block] against the whole app with logins (AUTH_MODE=session). [Browsers.browser] makes a client that keeps
 * its cookies, like one browser: several are several people, or one person on several devices. Its requests come from
 * `origin` (null: they say nothing, to test the CSRF check).
 */
fun sessionSmoke(block: suspend Browsers.() -> Unit) = testApplication {
    val d = SmokeDb.sessionDeps
    application { app(d, SessionUserResolver(d.accounts, d.sessionCookie)) }
    Browsers(d) { origin ->
        createClient { install(HttpCookies); followRedirects = false; defaultRequest { asBrowser(origin) } }
    }.block()
}

class Browsers(val deps: Deps, private val client: (origin: String?) -> HttpClient) {
    fun browser(origin: String? = ORIGIN): HttpClient = client(origin)
}

class Smoke(val client: HttpClient, val deps: Deps) {
    suspend fun get(path: String, user: String = "alice"): HttpResponse = client.get(path) { header(USER, user) }

    /** A form post; repeat a name for several values (e.g. "only"). */
    suspend fun post(path: String, vararg form: Pair<String, String>, user: String = "alice", htmx: Boolean = false): HttpResponse =
        client.submitForm(path, parameters { form.forEach { (k, v) -> append(k, v) } }) {
            header(USER, user)
            if (htmx) header("HX-Request", "true")
        }

    /** Where [this] sends the browser: a redirect, or htmx's HX-Redirect. */
    fun HttpResponse.target(): String = headers[HttpHeaders.Location] ?: headers["HX-Redirect"] ?: error("No redirect: $status")

    /** A new campaign of [user]'s; its id. */
    suspend fun campaign(module: String, name: String = "Smoke $module", user: String = "alice"): UUID =
        post("/campaigns", "name" to name, "module" to module, user = user).target().let(::campaignId)

    /** A new node from the top bar's Create; its id. */
    suspend fun create(cid: UUID, type: String, title: String = "", user: String = "alice"): UUID =
        post("/c/$cid/nodes", "type" to type, "title" to title, user = user).target().let(::nodeId)

    /** A node made from [from]'s "Add new" ([choice]: "<type>|<rel>"), linked to it; its id. */
    suspend fun quickCreate(cid: UUID, from: UUID, choice: String, title: String): UUID =
        post("/c/$cid/n/$from/quick-create", "choice" to choice, "title" to title).target().let(::nodeId)

    /** An autosave of [nid]'s form; the save status, which lists the errors if there are any. */
    suspend fun save(cid: UUID, nid: UUID, vararg form: Pair<String, String>): String =
        post("/c/$cid/n/$nid", "autosave" to "1", *form).also { assertEquals(HttpStatusCode.OK, it.status) }.bodyAsText()

    fun node(cid: UUID, nid: UUID): Node = deps.graph.getNode(cid, nid) ?: error("No node $nid")
    fun nodes(cid: UUID): List<Node> = deps.graph.listNodesWithProps(cid)
    fun node(cid: UUID, type: String, title: String): Node = nodes(cid).single { it.type == type && it.title == title }

    /** [nid]'s links as "<edge type>:<direction> <other's title>", sorted. */
    fun links(cid: UUID, nid: UUID): List<String> = deps.graph.links(cid, nid).map { "${it.key} ${it.other.title}" }.sorted()

    suspend fun export(cid: UUID, user: String = "alice"): CampaignExport =
        Json.mapper.readValue(ok("/c/$cid/export.json", user).bodyAsBytes(), CampaignExport::class.java)

    /** GET [path] and check it answers 200. */
    suspend fun ok(path: String, user: String = "alice"): HttpResponse =
        get(path, user).also { assertEquals(HttpStatusCode.OK, it.status, "GET $path: ${it.bodyAsText().take(300)}") }

    /** GET [path] and check it's a PDF. */
    suspend fun pdf(path: String) {
        val bytes = ok(path).bodyAsBytes()
        assertTrue(bytes.decodeToString(0, 5) == "%PDF-", "$path is not a PDF")
    }
}

/** The ids in an app URL, e.g. a redirect to "/c/<campaign>/n/<node>?new=1". */
fun campaignId(url: String): UUID = UUID.fromString(url.substringAfter("/c/").substringBefore("/").substringBefore("?"))
fun nodeId(url: String): UUID = UUID.fromString(url.substringAfter("/n/").substringBefore("/").substringBefore("?"))
