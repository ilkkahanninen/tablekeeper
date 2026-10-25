package tablekeeper.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import tablekeeper.modules.GameModules
import java.util.UUID

/**
 * Nodes and edges of one campaign. All methods take a campaign id that the
 * caller has already ownership-checked via CampaignService.get().
 */
class GraphService(private val db: DSLContext) {
    private val mapper = GameModules.mapper

    private val nodeCols = "id, campaign_id, type, code, title, body, props::text as props, visibility"
    private val refCols = "n.id, n.type, n.code, n.title, n.visibility"

    fun listNodes(campaignId: UUID): List<NodeRef> =
        db.fetch("select $refCols from nodes n where n.campaign_id = ? order by n.type, n.title", campaignId)
            .map(::toRef)

    /** Every node with its props, for whole-campaign views (home tables, map, module views). */
    fun listNodesWithProps(campaignId: UUID): List<Node> =
        db.fetch("select $nodeCols from nodes where campaign_id = ? order by type, title", campaignId).map(::toNode)

    fun getNode(campaignId: UUID, nodeId: UUID): Node? =
        db.fetchOne("select $nodeCols from nodes where campaign_id = ? and id = ?", campaignId, nodeId)
            ?.let(::toNode)

    /** Creates a node with the next free short code for its prefix: L1, L2, … */
    fun createNode(campaignId: UUID, type: String, codePrefix: String, title: String): Node =
        db.transactionResult { cfg ->
            val tx = DSL.using(cfg)
            // Serialise code allocation per campaign.
            tx.fetch("select pg_advisory_xact_lock(hashtext(?))", campaignId.toString())
            val pattern = Regex("^${Regex.escape(codePrefix)}(\\d+)$")
            val next = tx.fetch("select code from nodes where campaign_id = ? and code like ?", campaignId, "$codePrefix%")
                .mapNotNull { pattern.find(it.get("code", String::class.java))?.groupValues?.get(1)?.toInt() }
                .maxOrNull()?.plus(1) ?: 1
            val r = tx.fetchOne(
                "insert into nodes (campaign_id, type, code, title) values (?, ?, ?, ?) returning $nodeCols",
                campaignId, type, "$codePrefix$next", title,
            )!!
            toNode(r)
        }

    fun updateNode(campaignId: UUID, nodeId: UUID, title: String, body: String, visibility: Visibility, props: JsonNode) {
        db.execute(
            """update nodes set title = ?, body = ?, visibility = ?, props = ?::jsonb, updated_at = now()
               where campaign_id = ? and id = ?""",
            title, body, visibility.dbValue, mapper.writeValueAsString(props), campaignId, nodeId,
        )
    }

    fun deleteNode(campaignId: UUID, nodeId: UUID) {
        db.execute("delete from nodes where campaign_id = ? and id = ?", campaignId, nodeId)
    }

    /** Title/code search for the link picker. Type filtering happens in the caller (needs module rules). */
    fun search(campaignId: UUID, query: String, excludeId: UUID, limit: Int = 30): List<NodeRef> =
        db.fetch(
            """select $refCols from nodes n
               where n.campaign_id = ? and n.id <> ? and (n.title ilike ? or n.code ilike ?)
               order by n.title limit ?""",
            campaignId, excludeId, "%$query%", "$query%", limit,
        ).map(::toRef)

    /** All edges touching a node, seen from that node. */
    fun links(campaignId: UUID, nodeId: UUID): List<Link> =
        db.fetch(
            """select e.id as edge_id, e.type as edge_type, 'OUT' as dir, $refCols
               from edges e join nodes n on n.id = e.to_id
               where e.campaign_id = ? and e.from_id = ?
               union all
               select e.id, e.type, 'IN', $refCols
               from edges e join nodes n on n.id = e.from_id
               where e.campaign_id = ? and e.to_id = ?
               order by 2, 6""",
            campaignId, nodeId, campaignId, nodeId,
        ).map {
            Link(
                edgeId = it.get("edge_id", UUID::class.java),
                type = it.get("edge_type", String::class.java),
                direction = Direction.valueOf(it.get("dir", String::class.java)),
                other = toRef(it),
            )
        }

    /** Every edge in the campaign, for whole-graph views like the map. */
    fun listEdges(campaignId: UUID): List<EdgeRef> =
        db.fetch("select from_id, to_id, type from edges where campaign_id = ?", campaignId).map {
            EdgeRef(it.get("from_id", UUID::class.java), it.get("to_id", UUID::class.java), it.get("type", String::class.java))
        }

    /** Number of [type] edges leaving ([Direction.OUT]) or entering ([Direction.IN]) a node. */
    fun countLinks(campaignId: UUID, nodeId: UUID, type: String, direction: Direction): Int {
        val end = if (direction == Direction.OUT) "from_id" else "to_id"
        return db.fetchOne("select count(*) from edges where campaign_id = ? and $end = ? and type = ?", campaignId, nodeId, type)!!
            .get(0, Int::class.java)
    }

    /**
     * For every node with [type] edges in [direction], the titles at the far end, comma-joined.
     * E.g. part_of OUT: minor location id -> "Red Market".
     */
    fun linkedTitles(campaignId: UUID, type: String, direction: Direction): Map<UUID, String> {
        val (self, far) = if (direction == Direction.OUT) "from_id" to "to_id" else "to_id" to "from_id"
        return db.fetch(
            """select e.$self as id, n.title from edges e join nodes n on n.id = e.$far
               where e.campaign_id = ? and e.type = ? order by n.title""",
            campaignId, type,
        ).groupBy({ it.get("id", UUID::class.java) }, { it.get("title", String::class.java) })
            .mapValues { it.value.joinToString(", ") }
    }

    /** Removes every [type] edge leaving ([Direction.OUT]) or entering ([Direction.IN]) a node. */
    fun deleteLinks(campaignId: UUID, nodeId: UUID, type: String, direction: Direction) {
        val end = if (direction == Direction.OUT) "from_id" else "to_id"
        db.execute("delete from edges where campaign_id = ? and $end = ? and type = ?", campaignId, nodeId, type)
    }

    fun createEdge(campaignId: UUID, fromId: UUID, toId: UUID, type: String) {
        db.execute(
            """insert into edges (campaign_id, from_id, to_id, type)
               select ?, ?, ?, ?
               where exists (select 1 from nodes where id = ? and campaign_id = ?)
                 and exists (select 1 from nodes where id = ? and campaign_id = ?)
               on conflict do nothing""",
            campaignId, fromId, toId, type, fromId, campaignId, toId, campaignId,
        )
    }

    fun deleteEdge(campaignId: UUID, edgeId: UUID) {
        db.execute("delete from edges where campaign_id = ? and id = ?", campaignId, edgeId)
    }

    private fun toRef(r: Record) = NodeRef(
        id = r.get("id", UUID::class.java),
        type = r.get("type", String::class.java),
        code = r.get("code", String::class.java),
        title = r.get("title", String::class.java),
        visibility = r.get("visibility", String::class.java),
    )

    private fun toNode(r: Record) = Node(
        id = r.get("id", UUID::class.java),
        campaignId = r.get("campaign_id", UUID::class.java),
        type = r.get("type", String::class.java),
        code = r.get("code", String::class.java),
        title = r.get("title", String::class.java),
        body = r.get("body", String::class.java),
        props = mapper.readValue(r.get("props", String::class.java)),
        visibility = r.get("visibility", String::class.java),
    )
}
