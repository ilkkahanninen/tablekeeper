package tablekeeper.web

import io.ktor.http.HttpStatusCode
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveParameters
import io.ktor.server.response.respondRedirect
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import tablekeeper.auth.user
import tablekeeper.auth.userOrNull
import tablekeeper.core.BadRequest

/** Log in, sign up (open to anyone), log out and change password: AUTH_MODE=session only. */
fun Route.authRoutes(d: Deps) = with(d) {
    route("/login") {
        get {
            if (call.userOrNull != null) return@get call.respondRedirect(safeNext(call.request.queryParameters["next"]))
            call.html("login.peb", mapOf("next" to call.request.queryParameters["next"]))
        }

        // One message for a wrong name or password; too many failures for a name from one address wait a while
        post {
            val p = call.receiveParameters()
            val username = p["username"].orEmpty()
            val key = "${username.trim().lowercase()}|${call.request.origin.remoteHost}"
            val form = mapOf("next" to p["next"], "username" to username)
            if (loginThrottle.blocked(key))
                return@post call.html("login.peb", form + ("error" to "Too many tries. Wait a few minutes."), HttpStatusCode.TooManyRequests)
            val user = accounts.logIn(username, p["password"].orEmpty())
            if (user == null) {
                loginThrottle.failed(key)
                return@post call.html("login.peb", form + ("error" to "Wrong name or password"), HttpStatusCode.Unauthorized)
            }
            loginThrottle.succeeded(key)
            sessionCookie.set(call, accounts.newSession(user))
            call.respondRedirect(safeNext(p["next"]))
        }
    }

    route("/signup") {
        get { call.html("signup.peb", emptyMap()) }

        post {
            val p = call.receiveParameters()
            val username = p["username"].orEmpty()
            val user = try {
                accounts.signUp(username, p["password"].orEmpty())
            } catch (e: BadRequest) {
                return@post call.html("signup.peb", mapOf("username" to username, "error" to e.message), HttpStatusCode.BadRequest)
            }
            sessionCookie.set(call, accounts.newSession(user))
            call.respondRedirect("/")
        }
    }

    post("/logout") {
        sessionCookie.token(call)?.let(accounts::logOut)
        sessionCookie.clear(call)
        call.respondRedirect("/login")
    }

    // Change password: the other browsers logged in as this user are logged out
    route("/account") {
        get { call.html("account.peb", mapOf("user" to call.user)) }

        post {
            val user = call.user
            val p = call.receiveParameters()
            try {
                accounts.changePassword(user, sessionCookie.token(call).orEmpty(), p["current"].orEmpty(), p["password"].orEmpty())
            } catch (e: BadRequest) {
                return@post call.html("account.peb", mapOf("user" to user, "error" to e.message), HttpStatusCode.BadRequest)
            }
            call.respondRedirect("/")
        }
    }
}
