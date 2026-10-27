package tablekeeper.core

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import java.util.UUID

/**
 * A whole campaign as one JSON file: a backup, or a way to copy a campaign. Node and table ids are only references
 * within the file (for edges and nothing else); an import gives everything new ids.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class CampaignExport(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val exportedAt: String? = null,
    val campaign: ExportedCampaign,
    val nodes: List<ExportedNode> = emptyList(),
    val edges: List<ExportedEdge> = emptyList(),
    val randomTables: List<ExportedTable> = emptyList(),
) {
    companion object {
        const val FORMAT = "tablekeeper-campaign"

        /**
         * Raise it with every change that would make an older file import wrong: a Flyway migration that rewrites node,
         * edge or table data, or a change to this shape. Each raise needs a step in [ExportUpgrades] and an export made
         * by the new version saved as `src/test/resources/exports/v<version>-<module>.json` (ExportCompatibilityTest).
         * 2: Heart adversary fields (V6, V7).
         */
        const val VERSION = 2
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExportedCampaign(val name: String, val module: String)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExportedNode(
    val id: UUID, val type: String, val code: String, val title: String, val body: String = "",
    val props: JsonNode? = null, val tags: List<String> = emptyList(), val visibility: String = "gm",
    val createdAt: String? = null, val updatedAt: String? = null,   // ISO 8601
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExportedEdge(val from: UUID, val to: UUID, val type: String, val props: JsonNode? = null)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ExportedTable(val name: String, val items: String = "", val mix: Boolean = false, val fields: List<String> = emptyList())

/** Campaign export and import. Like the other services, export takes a campaign the caller has ownership-checked. */
class CampaignTransfer(private val db: DSLContext) {
    private val mapper = Json.mapper

    fun export(campaign: Campaign): CampaignExport {
        val nodes = db.fetch(
            """select id, type, code, title, body, props::text as props, tags, visibility,
                      to_json(created_at)#>>'{}' as created_at, to_json(updated_at)#>>'{}' as updated_at
               from nodes where campaign_id = ? order by code""", campaign.id,
        ).map {
            ExportedNode(
                it.get("id", UUID::class.java), it.get("type", String::class.java), it.get("code", String::class.java),
                it.get("title", String::class.java), it.get("body", String::class.java),
                mapper.readTree(it.get("props", String::class.java)),
                it.get("tags", Array<String>::class.java).toList(), it.get("visibility", String::class.java),
                it.get("created_at", String::class.java), it.get("updated_at", String::class.java),
            )
        }
        val edges = db.fetch("select from_id, to_id, type, props::text as props from edges where campaign_id = ? order by created_at", campaign.id).map {
            ExportedEdge(it.get("from_id", UUID::class.java), it.get("to_id", UUID::class.java), it.get("type", String::class.java),
                mapper.readTree(it.get("props", String::class.java)))
        }
        val fields = db.fetch("select table_id, field from random_table_fields where campaign_id = ? order by field", campaign.id)
            .groupBy({ it.get("table_id", UUID::class.java) }, { it.get("field", String::class.java) })
        val tables = db.fetch("select id, name, items, mix from random_tables where campaign_id = ? order by name", campaign.id).map {
            ExportedTable(it.get("name", String::class.java), it.get("items", String::class.java), it.get("mix", Boolean::class.java),
                fields[it.get("id", UUID::class.java)].orEmpty())
        }
        return CampaignExport(exportedAt = Instant.now().toString(), campaign = ExportedCampaign(campaign.name, campaign.gameModule),
            nodes = nodes, edges = edges, randomTables = tables)
    }

    fun toJson(export: CampaignExport): ByteArray = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export)

    /**
     * A new campaign of [owner] from [export], everything in one transaction: its nodes (codes kept, new ids), the edges
     * between them and its random tables. Named as exported, or "<name> (2)" etc. if the owner already has one by that name.
     */
    fun import(owner: User, export: CampaignExport): UUID = db.transactionResult { cfg ->
        val tx = DSL.using(cfg)
        val taken = tx.fetch("select name from campaigns where owner_id = ?", owner.id).map { it.get("name", String::class.java) }.toSet()
        val campaignId = tx.fetchOne(
            "insert into campaigns (owner_id, name, game_module) values (?, ?, ?) returning id",
            owner.id, CampaignImport.freeName(export.campaign.name, taken), export.campaign.module,
        )!!.get("id", UUID::class.java)
        val ids = export.nodes.associate { n ->
            n.id to tx.fetchOne(
                """insert into nodes (campaign_id, type, code, title, body, props, tags, visibility, created_at, updated_at)
                   values (?, ?, ?, ?, ?, ?::jsonb, ?::text[], ?, coalesce(?::timestamptz, now()), coalesce(?::timestamptz, now())) returning id""",
                campaignId, n.type, n.code, n.title, n.body, mapper.writeValueAsString(n.props ?: mapper.createObjectNode()),
                n.tags.toTypedArray(), Visibility.of(n.visibility).dbValue, n.createdAt, n.updatedAt,
            )!!.get("id", UUID::class.java)
        }
        export.edges.forEach { e ->
            tx.execute(
                "insert into edges (campaign_id, from_id, to_id, type, props) values (?, ?, ?, ?, ?::jsonb) on conflict do nothing",
                campaignId, ids.getValue(e.from), ids.getValue(e.to), e.type, mapper.writeValueAsString(e.props ?: mapper.createObjectNode()),
            )
        }
        export.randomTables.forEach { t ->
            val tableId = tx.fetchOne("insert into random_tables (campaign_id, name, items, mix) values (?, ?, ?, ?) returning id",
                campaignId, t.name, t.items, t.mix)!!.get("id", UUID::class.java)
            t.fields.distinct().forEach { f -> tx.execute("insert into random_table_fields (campaign_id, table_id, field) values (?, ?, ?)", campaignId, tableId, f) }
        }
        campaignId
    }
}

/** Pure reading, checks and naming for imports. */
object CampaignImport {
    /** The file's contents brought up to this version ([ExportUpgrades]), or a [BadRequest] saying why it isn't a campaign export this version can read. */
    fun parse(bytes: ByteArray): CampaignExport {
        val export = runCatching { Json.mapper.readValue<CampaignExport>(bytes) }
            .getOrElse { throw BadRequest("Not a Tablekeeper campaign file") }
        if (export.format != CampaignExport.FORMAT) throw BadRequest("Not a Tablekeeper campaign file")
        if (export.version > CampaignExport.VERSION) throw BadRequest("The file is from a newer Tablekeeper (format ${export.version})")
        if (export.version < 1) throw BadRequest("Not a Tablekeeper campaign file")
        val upgraded = ExportUpgrades.upgrade(export)
        problems(upgraded).firstOrNull()?.let { throw BadRequest(it) }
        return upgraded
    }

    /** What would make [export] fail to import (duplicate codes, edges to missing nodes…); empty = fine. */
    fun problems(export: CampaignExport): List<String> {
        val ids = export.nodes.map { it.id }.toSet()
        return listOfNotNull(
            export.nodes.groupingBy { it.code }.eachCount().filterValues { it > 1 }.keys.takeIf { it.isNotEmpty() }
                ?.let { "Duplicate node codes: ${it.joinToString()}" },
            "Nodes share an id".takeIf { ids.size != export.nodes.size },
            export.edges.count { it.from !in ids || it.to !in ids }.takeIf { it > 0 }?.let { "$it links point to nodes not in the file" },
            "${export.edges.count { it.from == it.to }} links point from a node to itself".takeIf { export.edges.any { it.from == it.to } },
        )
    }

    /** [name], or "[name] (2)", "(3)"… — the first one not in [taken]. */
    fun freeName(name: String, taken: Set<String>): String =
        generateSequence(1) { it + 1 }.map { if (it == 1) name else "$name ($it)" }.first { it !in taken }
}
