package tablekeeper.auth

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
 * AUTH_MODE=session -> not implemented yet: add a login page, Ktor Sessions cookie
 *                      and Argon2 password check here. Routes and data stay unchanged
 *                      because everything is already scoped by owner_id.
 */
fun interface UserResolver {
    fun resolve(call: ApplicationCall): User?
}

class LocalUserResolver(campaigns: CampaignService) : UserResolver {
    private val local = campaigns.ensureUser("local")
    override fun resolve(call: ApplicationCall): User = local
}

fun userResolverFor(mode: AuthMode, campaigns: CampaignService): UserResolver = when (mode) {
    AuthMode.NONE -> LocalUserResolver(campaigns)
    AuthMode.SESSION -> error("AUTH_MODE=session is not implemented yet; use AUTH_MODE=none")
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
    get() = attributes.getOrNull(UserKey) ?: throw NotAuthenticated()
