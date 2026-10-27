package tablekeeper

import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Nobody reaches another user's campaign, or one campaign's nodes, links or tables through another's URLs. */
class OwnershipSmokeTest {
    @Test
    fun `another user's campaign is not found, whatever the route`() = smoke {
        val cid = campaign("heart", "Alice's own")
        val l = node(cid, "heart.landmark", "Derelict").id
        val tid = post("/c/$cid/tables", "name" to "Names").target().substringAfter("/tables/")

        assertTrue("Alice's own" !in ok("/", user = "bob").bodyAsText())
        listOf("", "/map", "/map.pdf", "/export.json", "/tables", "/tables/$tid", "/find?q=D", "/n/$l", "/n/$l/print.pdf", "/n/$l/debrief")
            .forEach { assertEquals(HttpStatusCode.NotFound, get("/c/$cid$it", user = "bob").status, "GET $it") }
        listOf("" to arrayOf("name" to "Bob's now"), "/nodes" to arrayOf("type" to "core.npc"), "/delete" to emptyArray(),
            "/n/$l" to arrayOf("autosave" to "1", "body" to "Bob was here"), "/n/$l/delete" to emptyArray(),
            "/tables/$tid/delete" to emptyArray(), "/generate/x" to emptyArray())
            .forEach { (path, form) -> assertEquals(HttpStatusCode.NotFound, post("/c/$cid$path", *form, user = "bob").status, "POST $path") }

        assertEquals("Alice's own", export(cid).campaign.name)
        assertEquals(listOf("Derelict" to ""), nodes(cid).filter { it.id == l }.map { it.title to it.body })
        assertEquals(1, deps.tables.list(cid).size)
    }

    @Test
    fun `one campaign's nodes, links and tables are not reached through another's URLs`() = smoke {
        val alice = campaign("heart", "Alice's")
        val aliceNode = node(alice, "heart.landmark", "Derelict").id
        val aliceEdge = deps.graph.links(alice, aliceNode).single().edgeId
        val aliceTable = post("/c/$alice/tables", "name" to "Names").target().substringAfter("/tables/")

        val bob = campaign("heart", "Bob's", user = "bob")
        val bobNode = node(bob, "heart.landmark", "Derelict").id
        assertEquals(HttpStatusCode.NotFound, get("/c/$bob/n/$aliceNode", user = "bob").status)
        assertEquals(HttpStatusCode.NotFound, get("/c/$bob/tables/$aliceTable", user = "bob").status)
        assertEquals(HttpStatusCode.NotFound, post("/c/$bob/n/$bobNode/links", "rel" to "relates_to:out", "other" to "$aliceNode", user = "bob").status)
        val bobNpc = create(bob, "core.npc", "Silvia", user = "bob")
        assertEquals(HttpStatusCode.NotFound,
            post("/c/$bob/n/$bobNpc/slot", "slot" to "0", "value" to "located_at:out|$aliceNode", user = "bob").status)
        post("/c/$bob/n/$bobNode/links/$aliceEdge/delete", user = "bob")
        assertEquals(listOf("features:in First Session"), links(alice, aliceNode))
    }
}
