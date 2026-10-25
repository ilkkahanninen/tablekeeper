package tablekeeper.modules

import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.convertValue
import com.fasterxml.jackson.module.kotlin.readValue
import tablekeeper.core.Json
import java.io.File

/** The loaded game modules, each resolved with everything it extends. */
class GameModules(private val modules: Map<String, ResolvedModule>) {

    fun get(id: String): ResolvedModule = modules[id] ?: error("Unknown game module '$id'")

    /** Modules you can start a campaign with (everything except bare 'core' is listed first). */
    val all: List<ResolvedModule> get() = modules.values.sortedBy { if (it.id == "core") 1 else 0 }

    companion object {
        /** Loads every `<dir>/<module>/module.json`. */
        fun load(dir: String): GameModules {
            val root = File(dir)
            require(root.isDirectory) { "Modules directory not found: ${root.absolutePath}" }
            val defs = root.listFiles()!!
                .mapNotNull { File(it, "module.json").takeIf(File::isFile) }
                .map { Json.mapper.readValue<ModuleDef>(it) }
                .associateBy { it.id }

            fun chain(id: String, seen: MutableSet<String> = mutableSetOf()): List<ModuleDef> {
                require(seen.add(id)) { "Module extends cycle at '$id'" }
                val def = defs[id] ?: error("Module '$id' not found in $dir")
                return def.extends.flatMap { chain(it, seen) } + def   // base first, self last
            }

            val resolved = defs.keys.associateWith { id ->
                val c = chain(id)
                val self = c.last()
                val types = LinkedHashMap<String, NodeTypeDef>()
                val edges = LinkedHashMap<String, EdgeTypeDef>()
                c.forEach { m ->
                    m.nodeTypes.forEach { types[it.id] = it }
                    m.edgeTypes.forEach { edges[it.id] = it }
                    m.patchTypes.forEach { p ->
                        val typeId = p.get("id")?.asText()
                        val def = requireNotNull(types[typeId]) { "Module '${m.id}' patches unknown type '$typeId'" }
                        types[def.id] = patch(def, p)
                    }
                }
                val hidden = c.flatMap { it.hide }.toSet()
                ResolvedModule(
                    id = self.id,
                    name = self.name,
                    types = types,
                    creatable = types.values.filter { it.id !in hidden },
                    edgeTypes = edges.values.toList(),
                    views = c.flatMap { it.views }.associateBy { it.id }.values.toList(),
                    overviewOrder = c.lastOrNull { it.overviewOrder.isNotEmpty() }?.overviewOrder.orEmpty(),
                )
            }
            // Modules that extend others re-check inherited types, hence distinct().
            val errors = resolved.values.flatMap { it.configErrors() + it.viewErrors() }.distinct()
            require(errors.isEmpty()) { "Invalid game modules:\n" + errors.joinToString("\n") }
            return GameModules(resolved)
        }

        /** [def] with the settings given in [patch] replacing its own; the type's id and parent stay. */
        internal fun patch(def: NodeTypeDef, patch: ObjectNode): NodeTypeDef {
            val node: ObjectNode = Json.mapper.valueToTree(def)
            node.setAll<ObjectNode>(patch.deepCopy().apply { remove(listOf("id", "extends")) })
            return Json.mapper.convertValue(node)
        }
    }
}
