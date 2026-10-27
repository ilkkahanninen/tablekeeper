package tablekeeper.auth

import org.jooq.DSLContext
import tablekeeper.core.BadRequest
import tablekeeper.core.User
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

/** A logged-in browser's user; [renewed]: its session was just extended, so the cookie should be too. */
data class Session(val user: User, val renewed: Boolean)

/**
 * Accounts and their sessions (AUTH_MODE=session). Anyone can sign up; names are compared ignoring case, and "local"
 * (AUTH_MODE=none's user, which has no password) is taken. A session lasts [SESSION_DAYS] days and is extended when
 * used in its second half. The cookie holds a random token; the table only its SHA-256.
 */
class Accounts(private val db: DSLContext) {
    private val random = SecureRandom()
    /** Checked when a name doesn't exist, so a login takes as long either way. */
    private val dummyHash by lazy { Passwords.hash("not anyone's password") }

    /** A new account, or a [BadRequest] saying why not. */
    fun signUp(username: String, password: String): User {
        val name = username.trim()
        (nameProblem(name) ?: passwordProblem(password))?.let { throw BadRequest(it) }
        val r = db.fetchOne(
            "insert into users (username, password_hash) values (?, ?) on conflict do nothing returning id, username",
            name, Passwords.hash(password),
        ) ?: throw BadRequest("That name is taken")
        return User(r.get("id", UUID::class.java), r.get("username", String::class.java))
    }

    /** The account with [username] (any case) and [password], or null. */
    fun logIn(username: String, password: String): User? {
        val r = db.fetchOne("select id, username, password_hash from users where lower(username) = lower(?)", username.trim())
        val hash = r?.get("password_hash", String::class.java)
        if (hash == null) {
            Passwords.verify(password, dummyHash)
            return null
        }
        return if (Passwords.verify(password, hash)) User(r.get("id", UUID::class.java), r.get("username", String::class.java)) else null
    }

    /** A new session for [user]: its token, for the cookie. Clears out expired sessions on the way. */
    fun newSession(user: User): String {
        db.execute("delete from sessions where expires_at < now()")
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))
        db.execute("insert into sessions (token_hash, user_id, expires_at) values (?, ?, now() + make_interval(days => ?))",
            sha256(token), user.id, SESSION_DAYS)
        return token
    }

    /** The session [token] belongs to, if it hasn't expired; extended when it's in its second half. */
    fun session(token: String): Session? {
        val hash = sha256(token)
        val r = db.fetchOne(
            """select u.id, u.username, s.expires_at < now() + make_interval(days => ?) as renew
               from sessions s join users u on u.id = s.user_id where s.token_hash = ? and s.expires_at > now()""",
            SESSION_DAYS / 2, hash,
        ) ?: return null
        val renew = r.get("renew", Boolean::class.java)
        if (renew) db.execute("update sessions set expires_at = now() + make_interval(days => ?) where token_hash = ?", SESSION_DAYS, hash)
        return Session(User(r.get("id", UUID::class.java), r.get("username", String::class.java)), renew)
    }

    fun logOut(token: String) {
        db.execute("delete from sessions where token_hash = ?", sha256(token))
    }

    /** A new password for [user], given the current one; ends their other sessions (all but [token]'s). */
    fun changePassword(user: User, token: String, current: String, new: String) {
        val stored = db.fetchOne("select password_hash from users where id = ?", user.id)?.get("password_hash", String::class.java)
        if (stored == null || !Passwords.verify(current, stored)) throw BadRequest("The current password is wrong")
        passwordProblem(new)?.let { throw BadRequest(it) }
        db.execute("update users set password_hash = ? where id = ?", Passwords.hash(new), user.id)
        db.execute("delete from sessions where user_id = ? and token_hash <> ?", user.id, sha256(token))
    }

    private fun sha256(token: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))

    companion object {
        const val SESSION_DAYS = 30
        private val NAME = Regex("[A-Za-z0-9._-]{3,32}")

        /** Why [name] can't be an account's name, or null. */
        fun nameProblem(name: String): String? = when {
            !NAME.matches(name) -> "A name is 3–32 letters (a–z), digits, dots, dashes or underscores"
            name.equals("local", ignoreCase = true) -> "That name is taken"
            else -> null
        }

        /** Why [password] won't do, or null. */
        fun passwordProblem(password: String): String? = when {
            password.length < 10 -> "A password needs at least 10 characters"
            password.length > 200 -> "A password can have at most 200 characters"
            else -> null
        }
    }
}
