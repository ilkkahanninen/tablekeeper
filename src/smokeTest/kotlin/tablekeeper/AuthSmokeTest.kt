package tablekeeper

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.parameters
import tablekeeper.auth.SessionCookie
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AUTH_MODE=session: sign-up, login, logout, password change, the throttle, and the CSRF check. */
class AuthSmokeTest {
    private val password = "a long enough password"
    private fun name() = "u" + UUID.randomUUID().toString().take(8)

    private suspend fun HttpClient.form(path: String, vararg form: Pair<String, String>, origin: String? = null): HttpResponse =
        submitForm(path, parameters { form.forEach { (k, v) -> append(k, v) } }) { origin?.let { header(HttpHeaders.Origin, it) } }

    /** A page request, as a browser makes it. */
    private suspend fun HttpClient.page(path: String): HttpResponse = get(path) { header(HttpHeaders.Accept, "text/html") }

    private fun HttpResponse.location() = headers[HttpHeaders.Location]
    private fun HttpResponse.sessionCookie() = headers.getAll(HttpHeaders.SetCookie).orEmpty().firstOrNull { it.startsWith(SessionCookie.NAME) }

    private suspend fun HttpClient.signUp(name: String) =
        form("/signup", "username" to name, "password" to password).also { assertEquals("/", it.location(), it.bodyAsText().take(300)) }

    @Test
    fun `without a login, pages go to the login and come back after, and nothing else answers`() = sessionSmoke {
        val b = browser()
        assertEquals("/login", b.page("/").location())
        assertEquals("/login?next=%2Fc%2Fx%3Fy%3D1", b.page("/c/x?y=1").location())
        val htmx = b.get("/c/x/find?q=a") { header("HX-Request", "true"); header("HX-Current-URL", "http://localhost/c/x") }
        assertEquals(HttpStatusCode.Unauthorized to "/login?next=%2Fc%2Fx", htmx.status to htmx.headers["HX-Redirect"])
        assertEquals(HttpStatusCode.Unauthorized, b.form("/campaigns", "name" to "Mine", "module" to "heart").status)
        assertEquals(HttpStatusCode.OK, b.page("/login").status)
        assertEquals(HttpStatusCode.OK, b.page("/signup").status)
    }

    @Test
    fun `sign up, use the app, log out, log back in`() = sessionSmoke {
        val name = name()
        val b = browser()
        assertTrue(b.signUp(name).sessionCookie().orEmpty().let { "HttpOnly" in it && "SameSite=Lax" in it })
        assertTrue(name in b.page("/").bodyAsText(), "The user menu shows the name")
        val campaign = campaignId(b.form("/campaigns", "name" to "Mine", "module" to "heart").location()!!)
        assertEquals(HttpStatusCode.OK, b.page("/c/$campaign").status)

        assertEquals("/login", b.form("/logout").location())
        assertEquals("/login?next=%2Fc%2F$campaign", b.page("/c/$campaign").location())

        // A wrong password: no session, one message for both kinds of mistake
        val wrong = b.form("/login", "username" to name, "password" to "not the password")
        assertEquals(HttpStatusCode.Unauthorized, wrong.status)
        assertNull(wrong.sessionCookie())
        assertTrue("Wrong name or password" in wrong.bodyAsText())
        assertTrue("Wrong name or password" in b.form("/login", "username" to "nobody-${name()}", "password" to password).bodyAsText())

        // Any capitals; back where the login was asked for
        assertEquals("/c/$campaign", b.form("/login", "username" to name.uppercase(), "password" to password, "next" to "/c/$campaign").location())
        assertEquals(HttpStatusCode.OK, b.page("/c/$campaign").status)
        assertEquals("/", b.form("/login", "username" to name, "password" to password, "next" to "//evil.example").location())
    }

    @Test
    fun `a taken name in any capitals, local, and short passwords are refused with a reason`() = sessionSmoke {
        val name = name()
        browser().signUp(name)
        suspend fun refused(username: String, password: String = this@AuthSmokeTest.password) =
            browser().form("/signup", "username" to username, "password" to password).also { assertEquals(HttpStatusCode.BadRequest, it.status) }.bodyAsText()
        assertTrue("taken" in refused(name.uppercase()))
        assertTrue("taken" in refused("local"))
        assertTrue("10 characters" in refused(name(), "short"))
        assertTrue("letters" in refused("no spaces here"))
    }

    @Test
    fun `five wrong passwords block that name for a while, even with the right one`() = sessionSmoke {
        val name = name()
        browser().signUp(name)
        val b = browser()
        repeat(5) { assertEquals(HttpStatusCode.Unauthorized, b.form("/login", "username" to name, "password" to "guess number $it").status) }
        val blocked = b.form("/login", "username" to name, "password" to password)
        assertEquals(HttpStatusCode.TooManyRequests, blocked.status)
        assertNull(blocked.sessionCookie())
    }

    @Test
    fun `one user's campaign is not found for another`() = sessionSmoke {
        val alice = browser().also { it.signUp(name()) }
        val bob = browser().also { it.signUp(name()) }
        val campaign = campaignId(alice.form("/campaigns", "name" to "Alice's", "module" to "heart").location()!!)
        assertEquals(HttpStatusCode.NotFound, bob.page("/c/$campaign").status)
        assertEquals(HttpStatusCode.NotFound, bob.form("/c/$campaign/delete").status)
        assertTrue("Alice's" !in bob.page("/").bodyAsText())
        assertEquals(HttpStatusCode.OK, alice.page("/c/$campaign").status)
    }

    @Test
    fun `a new password logs out the user's other browsers`() = sessionSmoke {
        val name = name()
        val laptop = browser().also { it.signUp(name) }
        val phone = browser().also { it.form("/login", "username" to name, "password" to password) }
        assertEquals(HttpStatusCode.OK, phone.page("/").status)

        assertEquals(HttpStatusCode.BadRequest, laptop.form("/account", "current" to "not it", "password" to "a new long password").status)
        assertEquals("/", laptop.form("/account", "current" to password, "password" to "a new long password").location())
        assertEquals(HttpStatusCode.OK, laptop.page("/").status)
        assertEquals("/login", phone.page("/").location())
        assertEquals("/", browser().form("/login", "username" to name, "password" to "a new long password").location())
    }

    @Test
    fun `a post from another site, or with no origin, is refused`() = sessionSmoke {
        val name = name()
        val b = browser(origin = null)
        assertEquals("/", b.form("/signup", "username" to name, "password" to password, origin = ORIGIN).location())
        assertEquals(HttpStatusCode.Forbidden, b.form("/campaigns", "name" to "Forged", "module" to "heart", origin = "http://evil.example").status)
        assertEquals(HttpStatusCode.Forbidden, b.form("/campaigns", "name" to "Forged", "module" to "heart").status)
        assertEquals(emptyList(), deps.campaigns.list(deps.accounts.logIn(name, password)!!))
    }
}
