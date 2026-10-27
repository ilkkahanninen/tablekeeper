package tablekeeper

import java.net.URI
import java.net.URLDecoder

enum class AuthMode { NONE, SESSION }

data class Config(
    val port: Int,
    /** The database as JDBC wants it: see [database] for what DATABASE_URL may be. */
    val dbUrl: String,
    val dbUser: String,
    val dbPassword: String,
    val authMode: AuthMode,
    val modulesDir: String,
    /** Set TEMPLATE_CACHE=false while editing templates to see changes without restarting. */
    val templateCache: Boolean,
    /** Where users reach the app, e.g. https://tables.example.org, when it's behind a proxy: the only Origin a POST may come from, and https makes the session cookie Secure. */
    val publicUrl: String?,
) {
    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            val (dbUrl, dbUser, dbPassword) = database(env["DATABASE_URL"] ?: DEFAULT_DATABASE_URL, env["DATABASE_USER"], env["DATABASE_PASSWORD"])
            return Config(
                port = env["PORT"]?.toInt() ?: 8080,
                dbUrl = dbUrl,
                dbUser = dbUser,
                dbPassword = dbPassword,
                authMode = AuthMode.valueOf((env["AUTH_MODE"] ?: "none").uppercase()),
                modulesDir = env["MODULES_DIR"] ?: "modules",
                templateCache = env["TEMPLATE_CACHE"]?.toBooleanStrictOrNull() ?: true,
                publicUrl = env["PUBLIC_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
            )
        }

        const val DEFAULT_DATABASE_URL = "postgres://tablekeeper:tablekeeper@localhost:5432/tablekeeper"

        /**
         * DATABASE_URL as a JDBC URL, user and password. Usually `postgres://user:password@host:port/database?sslmode=require`
         * (or `postgresql://`), the form hosting and orchestration hand out: user and password may be percent-encoded, the
         * port defaults to 5432 and query parameters pass through to the driver. A `jdbc:postgresql://` URL works too,
         * with [user] and [password] (DATABASE_USER, DATABASE_PASSWORD), which also fill in what a postgres:// URL lacks.
         */
        fun database(url: String, user: String?, password: String?): Triple<String, String, String> {
            if (url.startsWith("jdbc:")) return Triple(url, user ?: "tablekeeper", password ?: "tablekeeper")
            // The scheme only: the rest may hold a password
            val scheme = url.substringBefore("://", "")
            require(scheme == "postgres" || scheme == "postgresql") {
                "DATABASE_URL must look like postgres://user:password@host:port/database (or be a jdbc:postgresql:// URL), not '$scheme://…'"
            }
            val uri = URI(url)
            // From the raw authority, not URI.host, which is null for names with underscores (Docker services often have them)
            val authority = uri.rawAuthority ?: ""
            val login = authority.substringBeforeLast('@', "")
            val hostPort = authority.substringAfterLast('@')
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore("]") + "]" else hostPort.substringBefore(':')
            val port = hostPort.removePrefix(host).removePrefix(":").ifEmpty { "5432" }
            require(host.isNotEmpty()) { "DATABASE_URL has no host" }
            val jdbc = "jdbc:postgresql://$host:$port${uri.rawPath.orEmpty()}" + (uri.rawQuery?.let { "?$it" } ?: "")
            val urlUser = login.substringBefore(':').takeIf { it.isNotEmpty() }?.let(::decode)
            val urlPassword = login.substringAfter(':', "").takeIf { ':' in login }?.let(::decode)
            return Triple(jdbc, urlUser ?: user ?: "tablekeeper", urlPassword ?: password ?: "tablekeeper")
        }

        /** Percent-decoding as in a URL's user info, where "+" is a plus, not a space. */
        private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), Charsets.UTF_8)
    }
}
