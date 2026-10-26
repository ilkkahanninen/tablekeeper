package tablekeeper.modules

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import tablekeeper.core.Json

/**
 * How type settings combine: a type over the one it `extends`, and a `patchTypes` entry over a type. Objects merge key by
 * key (so a patch of `overview.sort` keeps the other overview settings, and `linkLimits` merge per key); lists, values and
 * `props` (a whole schema) replace.
 */
object Overlay {
    /** Settings each type gives for itself: never taken from the type it extends. */
    val OWN = setOf("id", "label", "plural", "codePrefix", "extends")

    private val mapper = Json.mapper.copy().setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

    fun merge(base: ObjectNode, over: ObjectNode): ObjectNode {
        val out = base.deepCopy()
        over.properties().forEach { (key, value) ->
            val old = out.get(key)
            out.replace(key, if (key != "props" && old is ObjectNode && value is ObjectNode) merge(old, value) else value.deepCopy<JsonNode>())
        }
        return out
    }

    fun tree(def: NodeTypeDef): ObjectNode = mapper.valueToTree(def)

    fun def(tree: ObjectNode): NodeTypeDef = Json.mapper.convertValue(tree)

    /** [def] with [patch] laid over it; its id and parent stay. */
    fun patch(def: NodeTypeDef, patch: ObjectNode): NodeTypeDef =
        def(merge(tree(def), patch.deepCopy().apply { remove(listOf("id", "extends")) }))

    /**
     * Every type with what it inherits: its parent's resolved settings (but not the parent's [OWN] ones) under its own,
     * down the `extends` chain. A type extending an unknown one or a cycle fails.
     */
    fun resolve(types: Map<String, NodeTypeDef>): Map<String, NodeTypeDef> {
        val done = LinkedHashMap<String, ObjectNode>()
        fun of(id: String, seen: Set<String>): ObjectNode = done[id] ?: run {
            require(id !in seen) { "Type extends cycle at '$id'" }
            val def = requireNotNull(types[id]) { "Unknown type '$id'" }
            val own = tree(def)
            val tree = def.extends?.let { parent ->
                require(parent in types) { "$id: extends unknown type '$parent'" }
                merge(of(parent, seen + id).deepCopy().apply { remove(OWN) }, own)
            } ?: own
            tree.also { done[id] = it }
        }
        return types.keys.associateWith { def(of(it, emptySet())) }
    }
}
