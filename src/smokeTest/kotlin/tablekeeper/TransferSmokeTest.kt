package tablekeeper

import io.ktor.client.request.forms.formData
import io.ktor.client.request.forms.submitFormWithBinaryData
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import tablekeeper.core.CampaignExport
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransferSmokeTest {
    private suspend fun Smoke.import(bytes: ByteArray): HttpResponse =
        client.submitFormWithBinaryData("/campaigns/import", formData {
            append("file", bytes, Headers.build {
                append(HttpHeaders.ContentType, "application/json")
                append(HttpHeaders.ContentDisposition, "filename=\"campaign.json\"")
            })
        }) { header(USER, "alice") }

    /** What an import must keep: everything but ids, times and the export's own date. */
    private fun content(e: CampaignExport): List<Any?> {
        val codes = e.nodes.associate { it.id to it.code }
        return listOf(
            e.campaign.module, e.version,
            e.nodes.map { listOf(it.type, it.code, it.title, it.body, it.props, it.tags, it.visibility) }.sortedBy { it[1].toString() },
            e.edges.map { listOf(codes[it.from], it.type, codes[it.to], it.props) }.sortedBy { it.toString() },
            e.randomTables.sortedBy { it.name },
        )
    }

    @Test
    fun `an exported campaign imports as a copy of it`() = smoke {
        val cid = campaign("morkborg", "Round trip")
        post("/c/$cid/generate/dungeon")
        val pc = create(cid, "mb.pc", "Karg")
        save(cid, pc, "prop.player" to "Mira", "body" to "Owes [[Galgenbeck]] a debt.")
        quickCreate(cid, pc, "mb.item|carries:out", "Femur club")
        val original = export(cid)

        val copy = campaignId(import(Json.bytes(original)).target())
        assertEquals("Round trip (2)", export(copy).campaign.name)
        assertEquals(content(original), content(export(copy)))
        assertTrue(export(copy).nodes.none { n -> original.nodes.any { it.id == n.id } }, "An import gives nodes new ids")
    }

    @Test
    fun `every saved export imports through the app, and its pages and prints render`() = smoke {
        val files = File(System.getProperty("exportsDir")).listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue(files.isNotEmpty())
        files.forEach { f ->
            val cid = campaignId(import(f.readBytes()).target())
            assertEquals(CampaignExport.VERSION, export(cid).version, f.name)
            ok("/c/$cid")
            pdf("/c/$cid/map.pdf")
            nodes(cid).forEach { n ->
                ok("/c/$cid/n/${n.id}")
                if (n.type == "core.session") pdf("/c/$cid/n/${n.id}/print.pdf")
            }
        }
    }

    @Test
    fun `files that aren't campaigns, or are for a game it doesn't have, are refused with a reason`() = smoke {
        suspend fun refused(bytes: ByteArray) = import(bytes).also { assertEquals(HttpStatusCode.BadRequest, it.status) }.bodyAsText()
        assertEquals("Not a Tablekeeper campaign file", refused("hello".toByteArray()))
        val export = CampaignExport(campaign = tablekeeper.core.ExportedCampaign("Other", "nope"))
        assertTrue("nope" in refused(Json.bytes(export)))
        val unknownType = export.copy(campaign = tablekeeper.core.ExportedCampaign("Other", "heart"),
            nodes = listOf(tablekeeper.core.ExportedNode(UUID.randomUUID(), "heart.spaceship", "X1", "Ship")))
        assertTrue("heart.spaceship" in refused(Json.bytes(unknownType)))
    }

    private object Json {
        fun bytes(e: CampaignExport): ByteArray = tablekeeper.core.Json.mapper.writeValueAsBytes(e)
    }
}
