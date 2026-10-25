package tablekeeper.core

import org.jooq.DSLContext
import org.jooq.impl.DSL
import tablekeeper.modules.PropsForm
import tablekeeper.modules.ResolvedModule
import java.util.UUID

/** A user's random table: one item per line in [items]; [fields] it fills, as "<type id>:<field>" keys. */
/** [mix]: a roll joins one line's first words with another line's last word, e.g. first name + surname. */
data class RandomTable(val id: UUID, val name: String, val items: String, val fields: List<String>, val mix: Boolean = false) {
    val list: List<String> get() = RandomTables.items(items)
}

/** A field a table can fill: [key] "core.npc:voice", [label] "NPC: Voice". */
data class FieldOption(val key: String, val label: String)

/** One table offered by a field's Random button: its [name] and [items]. */
data class RandomChoice(val name: String, val items: List<String>, val mix: Boolean = false)

/** Pure helpers for random tables. */
object RandomTables {
    /** One item per line; surrounding spaces trimmed, empty lines dropped. */
    fun items(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    /** Fields a table can fill: each creatable type's title, then its text fields (one-line or long), in schema order. */
    fun fieldOptions(module: ResolvedModule): List<FieldOption> = module.nodeTypes.flatMap { t ->
        listOf(FieldOption("${t.id}:title", "${t.label}: Title")) +
            PropsForm.fields(module.propsSchema(t.id), emptyMap()).filter { it.kind == "text" || it.kind == "textarea" }
                .map { FieldOption("${t.id}:${it.name}", "${t.label}: ${it.label}") }
    }
}

/**
 * Random tables of one campaign. All methods take a campaign id that the caller has already ownership-checked via
 * CampaignService.get(), like GraphService.
 */
class RandomTableService(private val db: DSLContext) {

    fun list(campaignId: UUID): List<RandomTable> {
        val fields = fieldsByTable(campaignId)
        return db.fetch("select id, name, items, mix from random_tables where campaign_id = ? order by name", campaignId).map {
            val id = it.get("id", UUID::class.java)
            RandomTable(id, it.get("name", String::class.java), it.get("items", String::class.java), fields[id].orEmpty(), it.get("mix", Boolean::class.java))
        }
    }

    fun get(campaignId: UUID, id: UUID): RandomTable? = list(campaignId).firstOrNull { it.id == id }

    fun create(campaignId: UUID, name: String): UUID =
        db.fetchOne("insert into random_tables (campaign_id, name) values (?, ?) returning id", campaignId, name)!!
            .get("id", UUID::class.java)

    fun update(campaignId: UUID, id: UUID, name: String, items: String, mix: Boolean) {
        db.execute("update random_tables set name = ?, items = ?, mix = ? where campaign_id = ? and id = ?", name, items, mix, campaignId, id)
    }

    /** Replaces the fields the table fills. Other tables may fill the same fields. */
    fun setFields(campaignId: UUID, id: UUID, fields: Collection<String>) {
        db.transaction { cfg ->
            val tx = DSL.using(cfg)
            tx.execute("delete from random_table_fields where campaign_id = ? and table_id = ?", campaignId, id)
            fields.distinct().forEach { f ->
                tx.execute(
                    """insert into random_table_fields (campaign_id, table_id, field)
                       select ?, id, ? from random_tables where campaign_id = ? and id = ?
                       on conflict do nothing""",
                    campaignId, f, campaignId, id,
                )
            }
        }
    }

    fun delete(campaignId: UUID, id: UUID) {
        db.execute("delete from random_tables where campaign_id = ? and id = ?", campaignId, id)
    }

    /**
     * The tables per field name ("title", "voice") for a node page's Random buttons, by table name.
     * Tables without items are left out, and so are fields left with none.
     */
    fun forType(campaignId: UUID, typeId: String): Map<String, List<RandomChoice>> =
        db.fetch(
            """select f.field, t.name, t.items, t.mix from random_table_fields f join random_tables t on t.id = f.table_id
               where f.campaign_id = ? and f.field like ? order by t.name""",
            campaignId, "$typeId:%",
        ).map { it.get("field", String::class.java).removePrefix("$typeId:") to RandomChoice(it.get("name", String::class.java), RandomTables.items(it.get("items", String::class.java)), it.get("mix", Boolean::class.java)) }
            .filter { it.second.items.isNotEmpty() }
            .groupBy({ it.first }, { it.second })

    private fun fieldsByTable(campaignId: UUID): Map<UUID, List<String>> =
        db.fetch("select table_id, field from random_table_fields where campaign_id = ? order by field", campaignId)
            .groupBy({ it.get("table_id", UUID::class.java) }, { it.get("field", String::class.java) })
}
