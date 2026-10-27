package tablekeeper.core

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Brings a file from an older Tablekeeper up to [CampaignExport.VERSION], one version at a time. A step does to the
 * file's nodes what the Flyway data migrations of its version did to stored ones, so an old file imports as if its
 * campaign had been in the database all along. Steps are frozen: a later change is a new version with its own step.
 */
object ExportUpgrades {
    /** The step from version n to n + 1, by n. */
    private val steps: Map<Int, (CampaignExport) -> CampaignExport> = mapOf(
        1 to { e -> e.copy(nodes = e.nodes.map(::adversaryFieldsV6).map(::adversaryNameV7)) },
    )

    /** Versions with no step to the next; empty when every older file can be read. */
    internal fun missingSteps(): List<Int> = (1 until CampaignExport.VERSION).filter { it !in steps }

    fun upgrade(export: CampaignExport): CampaignExport {
        var e = export
        while (e.version < CampaignExport.VERSION) e = steps.getValue(e.version)(e).copy(version = e.version + 1)
        return e
    }

    /** V6: Heart adversaries' "names" and "descriptors" became "name" and "descriptor"; "resources" and "equipment" go to the notes. */
    private fun adversaryFieldsV6(n: ExportedNode): ExportedNode {
        val p = n.props as? ObjectNode
        val old = listOf("names", "descriptors", "resources", "equipment")
        if (n.type != "heart.adversary" || p == null || old.none(p::has)) return n
        val props = p.deepCopy().apply {
            remove(old)
            p.get("names")?.let { set<JsonNode>("name", it) }
            p.get("descriptors")?.let { set<JsonNode>("descriptor", it) }
        }
        val body = paragraphs(n.body, p.text("resources")?.let { "Resources: $it" }, p.text("equipment")?.let { "Equipment: $it" })
        return n.copy(body = body, props = props)
    }

    /** V7: Heart adversaries lost "name": it becomes the title of one still titled "New …", else starts its notes. */
    private fun adversaryNameV7(n: ExportedNode): ExportedNode {
        val p = n.props as? ObjectNode
        if (n.type != "heart.adversary" || p == null || !p.has("name")) return n
        val props = p.deepCopy().apply { remove("name") }
        val name = p.text("name") ?: return n.copy(props = props)
        val fresh = n.title.startsWith("New ")
        return n.copy(
            title = if (fresh) name else n.title,
            body = if (fresh || name == n.title) n.body else paragraphs("Name: $name", n.body),
            props = props,
        )
    }

    /** A prop as text, as SQL's `->>` reads it; null when missing, null or empty. */
    private fun ObjectNode.text(key: String): String? =
        get(key)?.takeUnless { it.isNull }?.let { if (it.isValueNode) it.asText() else it.toString() }?.takeIf { it.isNotEmpty() }

    private fun paragraphs(vararg parts: String?) = parts.filterNot { it.isNullOrEmpty() }.joinToString("\n\n")
}
