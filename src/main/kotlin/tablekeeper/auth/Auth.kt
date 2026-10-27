package tablekeeper.auth

import io.ktor.http.Cookie
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createApplicationPlugin
import io.ktor.util.AttributeKey
import tablekeeper.AuthMode
import tablekeeper.core.CampaignService
import tablekeeper.core.User

/**
 * Resolves the current user for a request.
 *
 * AUTH_MODE=none    -> everyone is the 'local' user (single-user, local Docker).
 * AUTH_MODE=session -> the user of the request's session cookie ([Accounts]), or none: then `call.user` throws
 *                      [NotAuthenticated], which sends the browser to /login. Routes and data stay the same either
 *                      way, because everything is scoped by owner_id.
 */
fun interface UserResolver {
    fun resolve(call: ApplicationCall): User?
}

class LocalUserResolver(campaigns: CampaignService) : UserResolver {
    private val local = campaigns.ensureUser("local")
    override fun resolve(call: ApplicationCall): User = local
}

/** The user of the request's session cookie; renews the cookie along with a renewed session. */
class SessionUserResolver(private val accounts: Accounts, private val cookie: SessionCookie) : UserResolver {
    override fun resolve(call: ApplicationCall): User? {
        val token = cookie.token(call) ?: return null
        val session = accounts.session(token) ?: return null
        if (session.renewed) cookie.set(call, token)
        return session.user
    }
}

fun userResolverFor(mode: AuthMode, campaigns: CampaignService, accounts: Accounts, cookie: SessionCookie): UserResolver = when (mode) {
    AuthMode.NONE -> LocalUserResolver(campaigns)
    AuthMode.SESSION -> SessionUserResolver(accounts, cookie)
}

/** The session cookie: HttpOnly, SameSite=Lax, for the whole site, and [secure] (https only) when the app is served over https. */
class SessionCookie(private val secure: Boolean) {
    fun token(call: ApplicationCall): String? = call.request.cookies[NAME]

    fun set(call: ApplicationCall, token: String) = call.response.cookies.append(cookie(token, Accounts.SESSION_DAYS * 24 * 3600))

    fun clear(call: ApplicationCall) = call.response.cookies.append(cookie("", 0))

    private fun cookie(value: String, maxAge: Int) =
        Cookie(NAME, value, maxAge = maxAge, path = "/", secure = secure, httpOnly = true, extensions = mapOf("SameSite" to "Lax"))

    companion object {
        const val NAME = "tablekeeper_session"
    }
}

private val UserKey = AttributeKey<User>("tablekeeper.user")

class NotAuthenticated : RuntimeException("Not authenticated")

/** Puts the resolved user on every call; routes read it with `call.user`. */
fun currentUserPlugin(resolver: UserResolver) = createApplicationPlugin("CurrentUser") {
    onCall { call ->
        resolver.resolve(call)?.let { call.attributes.put(UserKey, it) }
    }
}

val ApplicationCall.user: User
    get() = userOrNull ?: throw NotAuthenticated()

/** The current user, or null when nobody is logged in (the login pages). */
val ApplicationCall.userOrNull: User?
    get() = attributes.getOrNull(UserKey)
