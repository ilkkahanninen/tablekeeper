package tablekeeper

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class ConfigTest {
    private fun db(url: String, user: String? = null, password: String? = null) = Config.database(url, user, password)

    @Test
    fun `a postgres URL becomes a JDBC URL with its user and password`() {
        assertEquals(Triple("jdbc:postgresql://db.internal:6543/tables?sslmode=require", "tk", "secret"),
            db("postgres://tk:secret@db.internal:6543/tables?sslmode=require"))
        assertEquals(Triple("jdbc:postgresql://db:5432/tablekeeper", "tk", "secret"), db("postgresql://tk:secret@db/tablekeeper"), "port 5432 by default")
        assertEquals(Triple("jdbc:postgresql://localhost:5432/tablekeeper", "tablekeeper", "tablekeeper"), db(Config.DEFAULT_DATABASE_URL))
    }

    @Test
    fun `encoded passwords, plus signs, underscores in hosts and IPv6 survive`() {
        assertEquals("p@ss/w:rd+1", db("postgres://tk:p%40ss%2Fw%3Ard+1@db/tablekeeper").third)
        assertEquals("jdbc:postgresql://my_postgres:5432/tablekeeper", db("postgres://tk:x@my_postgres:5432/tablekeeper").first)
        assertEquals("jdbc:postgresql://[::1]:5433/tablekeeper", db("postgres://tk:x@[::1]:5433/tablekeeper").first)
    }

    @Test
    fun `DATABASE_USER and DATABASE_PASSWORD go with a JDBC URL, or fill in what a postgres URL lacks`() {
        assertEquals(Triple("jdbc:postgresql://db:5432/tablekeeper", "app", "pw"), db("jdbc:postgresql://db:5432/tablekeeper", "app", "pw"))
        assertEquals(Triple("jdbc:postgresql://db:5432/tablekeeper", "app", "pw"), db("postgres://db/tablekeeper", "app", "pw"))
        assertEquals("tk" to "pw", db("postgres://tk@db/tablekeeper", null, "pw").let { it.second to it.third })
    }

    @Test
    fun `another kind of URL is refused without showing its password`() {
        val e = assertFailsWith<IllegalArgumentException> { db("mysql://tk:hunter2@db/tablekeeper") }
        assertFalse("hunter2" in e.message.orEmpty(), e.message)
        assertFailsWith<IllegalArgumentException> { db("postgres:///tablekeeper") }
    }
}
