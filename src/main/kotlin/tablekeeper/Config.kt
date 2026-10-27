package tablekeeper

enum class AuthMode { NONE, SESSION }

data class Config(
    val port: Int,
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
        fun fromEnv(env: Map<String, String> = System.getenv()) = Config(
            port = env["PORT"]?.toInt() ?: 8080,
            dbUrl = env["DATABASE_URL"] ?: "jdbc:postgresql://localhost:5432/tablekeeper",
            dbUser = env["DATABASE_USER"] ?: "tablekeeper",
            dbPassword = env["DATABASE_PASSWORD"] ?: "tablekeeper",
            authMode = AuthMode.valueOf((env["AUTH_MODE"] ?: "none").uppercase()),
            modulesDir = env["MODULES_DIR"] ?: "modules",
            templateCache = env["TEMPLATE_CACHE"]?.toBooleanStrictOrNull() ?: true,
            publicUrl = env["PUBLIC_URL"]?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() },
        )
    }
}
