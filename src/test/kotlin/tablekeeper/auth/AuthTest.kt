package tablekeeper.auth

import tablekeeper.web.loginUrl
import tablekeeper.web.safeNext
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthTest {
    @Test
    fun `a password hash is an Argon2id PHC string that verifies only its password`() {
        val hash = Passwords.hash("correct horse battery")
        assertTrue(hash.startsWith("\$argon2id\$v=19\$m=19456,t=2,p=1\$"), hash)
        assertTrue(Passwords.verify("correct horse battery", hash))
        assertFalse(Passwords.verify("correct horse batterY", hash))
        assertNotEquals(hash, Passwords.hash("correct horse battery"), "Each hash has its own salt")
        assertFalse(Passwords.verify("anything", "plain text"))
        assertFalse(Passwords.verify("anything", "\$argon2id\$v=19\$m=x\$AAAA\$AAAA"))
    }

    @Test
    fun `names and passwords that won't do`() {
        assertNull(Accounts.nameProblem("Mira.K_9"))
        listOf("ab", "a".repeat(33), "with space", "ääkkönen", "LOCAL").forEach { assertTrue(Accounts.nameProblem(it) != null, it) }
        assertNull(Accounts.passwordProblem("ten chars!"))
        assertTrue(Accounts.passwordProblem("nine char") != null)
        assertTrue(Accounts.passwordProblem("x".repeat(201)) != null)
    }

    @Test
    fun `five failures block a name from one address until the oldest is 15 minutes old`() {
        var now = Instant.parse("2026-10-27T12:00:00Z")
        val throttle = LoginThrottle(now = { now })
        repeat(4) { throttle.failed("mira|1.2.3.4"); now += Duration.ofMinutes(1) }
        assertFalse(throttle.blocked("mira|1.2.3.4"))
        throttle.failed("mira|1.2.3.4")
        assertTrue(throttle.blocked("mira|1.2.3.4"))
        assertFalse(throttle.blocked("mira|5.6.7.8"), "Another address isn't blocked")
        now += Duration.ofMinutes(11)   // the first failure is now 15 minutes old
        assertFalse(throttle.blocked("mira|1.2.3.4"))
        throttle.failed("mira|1.2.3.4")
        assertTrue(throttle.blocked("mira|1.2.3.4"))
        throttle.succeeded("mira|1.2.3.4")
        assertFalse(throttle.blocked("mira|1.2.3.4"))
    }

    @Test
    fun `after a login only paths on this site are followed`() {
        assertEquals("/c/1/n/2?new=1", safeNext("/c/1/n/2?new=1"))
        listOf(null, "", "https://evil.example", "//evil.example", "/\\evil.example").forEach { assertEquals("/", safeNext(it), "$it") }
        assertEquals("/login", loginUrl("/"))
        assertEquals("/login?next=%2Fc%2F1%3Fx%3D1", loginUrl("/c/1?x=1"))
    }
}
