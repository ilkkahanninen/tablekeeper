package tablekeeper

import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CampaignSmokeTest {
    @Test
    fun `a new Heart campaign starts with its place and session, and every page and print renders`() = smoke {
        val cid = campaign("heart", "Heart pages")
        val landmark = node(cid, "heart.landmark", "Derelict")
        val session = node(cid, "core.session", "First Session")
        assertEquals(listOf("features:in First Session"), links(cid, landmark.id))
        assertTrue("Heart pages" in ok("/").bodyAsText())

        listOf("", "/map", "/tables", "/v/inspiration", "/find?q=Dere").forEach { ok("/c/$cid$it") }
        assertTrue("Derelict" in ok("/c/$cid/find?q=dere").bodyAsText())
        pdf("/c/$cid/map.pdf")
        listOf(landmark, session).forEach { n ->
            ok("/c/$cid/n/${n.id}")
            ok("/c/$cid/n/${n.id}/print")
            pdf("/c/$cid/n/${n.id}/print.pdf")
        }
        ok("/c/$cid/n/${session.id}/debrief")
    }

    @Test
    fun `without logins too, a post from another site is refused`() = smoke {
        val forged = client.submitForm("/campaigns", parameters { append("name", "Forged"); append("module", "heart") }) {
            header(USER, "csrf-target"); header(HttpHeaders.Origin, "http://evil.example")
        }
        assertEquals(HttpStatusCode.Forbidden, forged.status)
        assertEquals(emptyList(), deps.campaigns.list(deps.campaigns.ensureUser("csrf-target")))
    }

    @Test
    fun `a Heart packet prints what helps at the table and leaves out the rest`() = smoke {
        val cid = campaign("heart", "Packet contents")
        val derelict = node(cid, "heart.landmark", "Derelict").id   // L1, ticked in the first session, stress d4 ("Tier 0-1")
        val session = node(cid, "core.session", "First Session").id
        val market = create(cid, "heart.landmark", "Market")
        val redMarket = create(cid, "heart.landmark", "Red Market")   // not ticked
        post("/c/$cid/n/$session/check", "rel" to "features:out", "other" to "$market", "checked" to "on")

        // A delve between the two ticked landmarks, with an adversary in it, and an exit to Red Market
        val stair = quickCreate(cid, derelict, "heart.delve|connects:in", "The Long Stair")
        post("/c/$cid/n/$stair/links", "rel" to "connects:out", "other" to "$market")
        val exit = quickCreate(cid, derelict, "heart.delve|connects:in", "Exit Way")
        post("/c/$cid/n/$exit/links", "rel" to "connects:out", "other" to "$redMarket")
        val hound = quickCreate(cid, stair, "heart.adversary|located_at:in", "Hound")
        save(cid, hound, "prop.difficulty" to "Risky", "prop.resistance" to "6")

        val silvia = quickCreate(cid, derelict, "core.npc|located_at:in", "Silvia")
        save(cid, silvia, "prop.voice" to "Whispers")
        val ghost = quickCreate(cid, derelict, "core.npc|located_at:in", "Old Ghost")
        save(cid, ghost, "prop.dead" to "on")
        val fact = create(cid, "core.fact", "The lift still works")
        post("/c/$cid/n/$session/links", "rel" to "plans_reveal:out", "other" to "$fact")
        val second = create(cid, "core.session", "Second Session")
        post("/c/$cid/n/$second/check", "rel" to "features:out", "other" to "$derelict", "checked" to "on")

        val html = ok("/c/$cid/n/$session/print").bodyAsText()
        assertTrue("Hound" in html && "Risky" in html, "An adversary in a delve on the way gets its entry")
        assertTrue("Old Ghost" !in html, "The dead are left out")
        assertTrue("To reveal" in html && "The lift still works" in html, "Planned reveals are on the cover")
        assertTrue("→ Red Market (L3)" in html, "An exit delve says where it leads")
        assertTrue("""<div class="role">Derelict (L1)</div>""" in html, "An NPC's place is under its name")
        listOf("Tier 0-1", "Featured in", "Is located at", "Second Session").forEach { assertTrue(it !in html, "'$it' isn't printed") }
        pdf("/c/$cid/n/$session/print.pdf")

        create(cid, "heart.pc", "Karg")
        save(cid, session, "prop.recap" to "They found the lift")
        save(cid, second, "body" to "Plan: the ghost returns")
        // The campaign's print: every landmark ticked, so Red Market and the exit to it are in too, and what happened
        // in each session (but not their notes)
        val all = ok("/c/$cid/print").bodyAsText()
        assertTrue("What happened" in all && "They found the lift" in all, "Each session's recap is printed")
        assertTrue("Plan: the ghost returns" !in all, "Session notes aren't")
        listOf("Packet contents", "Red Market", "Exit Way", "Hound", "Silvia").forEach { assertTrue(it in all, "'$it' is in the campaign print") }
        assertTrue("Old Ghost" !in all, "The dead are left out")
        assertTrue(Regex("First Session").findAll(all).count() == 2, "Sessions are on the cover and head their recaps")
        assertTrue("<h2>Sessions</h2>" in all && "Karg" in all, "The cover lists the sessions and characters")
        assertTrue("Print" in ok("/c/$cid").bodyAsText())
        pdf("/c/$cid/print.pdf")

        assertTrue("""<div class="subtitle">Derelict (L1)</div>""" in ok("/c/$cid/n/$silvia/print").bodyAsText(), "The card shows it too")
    }

    @Test
    fun `a MÖRK BORG packet lists the miseries so far and leaves out the dead`() = smoke {
        val cid = campaign("morkborg", "Miseries")
        val galgenbeck = node(cid, "mb.location", "Galgenbeck").id
        val first = node(cid, "core.session", "First Session").id
        save(cid, first, "prop.date" to "2026-10-13")
        val now = create(cid, "core.session", "Into the bog")
        save(cid, now, "prop.date" to "2026-10-20")
        val later = create(cid, "core.session", "After")
        save(cid, later, "prop.date" to "2026-10-27")
        save(cid, quickCreate(cid, first, "mb.misery|came_to_pass:in", "The sun dims"), "prop.verse" to "1:1")
        save(cid, quickCreate(cid, later, "mb.misery|came_to_pass:in", "Not yet"), "prop.verse" to "2:2")

        val dungeon = nodeId(post("/c/$cid/generate/dungeon").target())
        post("/c/$cid/n/$dungeon/links", "rel" to "part_of:out", "other" to "$galgenbeck")
        save(cid, quickCreate(cid, galgenbeck, "core.npc|located_at:in", "Dead beggar"), "prop.dead" to "on")
        val karg = create(cid, "mb.pc", "Karg")
        save(cid, karg, "prop.player" to "Mira")
        listOf(galgenbeck, dungeon, karg).forEach { post("/c/$cid/n/$now/check", "rel" to "features:out", "other" to "$it", "checked" to "on") }

        val html = ok("/c/$cid/n/$now/print").bodyAsText()
        assertTrue("Miseries" in html && "1:1 The sun dims" in html, "The calendar so far is on the cover")
        assertTrue("Not yet" !in html, "A later session's misery isn't")
        assertTrue("Dead beggar" !in html, "The dead are left out")
        assertTrue("Karg (Mira)" in html && "Player" !in html, "The title shows the player; no row repeats it")
        assertTrue("""<div class="role">Galgenbeck (L1)</div>""" in html && "Is in" !in html, "A dungeon's place is under its name")
        pdf("/c/$cid/n/$now/print.pdf")
    }

    @Test
    fun `a campaign is renamed from its title and deleted with everything in it`() = smoke {
        val cid = campaign("heart", "Before")
        post("/c/$cid", "name" to "After")
        post("/c/$cid", "name" to " ")   // empty keeps the name
        assertEquals("After", deps.campaigns.get(deps.campaigns.ensureUser("alice"), cid)?.name)

        assertEquals("/", post("/c/$cid/delete").target())
        assertNull(deps.campaigns.get(deps.campaigns.ensureUser("alice"), cid))
        assertEquals(emptyList(), nodes(cid))
        assertEquals(HttpStatusCode.NotFound, get("/c/$cid").status)
    }

    @Test
    fun `a MÖRK BORG campaign gets the game's tables, and a generated dungeon has rooms, a map and a print`() = smoke {
        val cid = campaign("morkborg", "Dungeons")
        assertTrue(deps.tables.list(cid).any { it.name == "Bestiary" })

        val dungeon = nodeId(post("/c/$cid/generate/dungeon").target())
        val rooms = nodes(cid).filter { it.type == "mb.room" }
        assertEquals(10, rooms.size)
        assertTrue(rooms.all { r -> links(cid, r.id).any { it == "room_in:out ${node(cid, dungeon).title}" } })
        ok("/c/$cid/n/$dungeon/map")
        pdf("/c/$cid/n/$dungeon/map.pdf")
        pdf("/c/$cid/n/$dungeon/print.pdf")

        val creature = node(cid, nodeId(post("/c/$cid/generate/creature").target()))
        assertTrue(creature.props["hp"] != null, "A generated creature has its stat block: ${creature.props}")

        // The session packet with the dungeon ticked: one landscape PDF of everything in it
        val session = node(cid, "core.session", "First Session")
        post("/c/$cid/n/${session.id}/check", "rel" to "features:out", "other" to dungeon.toString(), "checked" to "on")
        pdf("/c/$cid/n/${session.id}/print.pdf")
        pdf("/c/$cid/print.pdf")
    }

    @Test
    fun `random tables fill fields and name new nodes`() = smoke {
        val cid = campaign("heart", "Tables")
        val tid = post("/c/$cid/tables", "name" to "Names").target().substringAfter("/tables/")
        post("/c/$cid/tables/$tid", "name" to "Names", "items" to "Silvia\nOrlo", "fields" to "core.npc:title", "autosave" to "1")
        assertTrue(ok("/c/$cid/tables/$tid/roll").bodyAsText() in setOf("Silvia", "Orlo"))
        assertEquals("Orlo", ok("/c/$cid/tables/$tid/roll?avoid=Silvia").bodyAsText())

        val npc = create(cid, "core.npc")   // no title: rolled
        assertTrue(node(cid, npc).title in setOf("Silvia", "Orlo"))

        post("/c/$cid/tables/$tid/delete")
        assertEquals(emptyList(), deps.tables.list(cid))
        assertEquals("New NPC", node(cid, create(cid, "core.npc")).title)
    }
}
