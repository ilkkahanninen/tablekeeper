package tablekeeper.modules

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import tablekeeper.core.Json

/**
 * Expands a type's `props` as written in module.json into a full JSON Schema: just the fields is short for
 * {"type": "object", "additionalProperties": false, "properties": …}, and a field may be one of the module's shared
 * [fields]: "@stress", or {"use": "stress", "x-row": "Beat 1"} with changes laid over it. Unknown names go to [errors].
 */
class Props(private val fields: Map<String, JsonNode>) {
    val errors = mutableListOf<String>()

    fun expand(props: JsonNode?, typeId: String): JsonNode? {
        if (props == null || !props.isObject) return props
        val schema = if (props.path("type").asText() == "object") props.deepCopy<ObjectNode>()
            else Json.mapper.createObjectNode().put("type", "object").put("additionalProperties", false).set<ObjectNode>("properties", props.deepCopy())
        val properties = schema.get("properties") as? ObjectNode ?: return schema
        properties.properties().toList().forEach { (name, field) -> properties.replace(name, field(field, "$typeId $name")) }
        return schema
    }

    private fun field(field: JsonNode, where: String): JsonNode {
        val use = when {
            field.isTextual && field.asText().startsWith("@") -> field.asText().drop(1)
            field.isObject && field.has("use") -> field["use"].asText()
            else -> return field
        }
        val shared = fields[use] ?: return field.also { errors += "$where: shared field '$use' is unknown" }
        return if (field.isObject) Overlay.merge(shared.deepCopy(), (field as ObjectNode).deepCopy().apply { remove("use") }) else shared.deepCopy()
    }
}
