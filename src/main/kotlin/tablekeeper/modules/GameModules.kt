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
            val trees = root.listFiles()!!.mapNotNull { File(it, "module.json").takeIf(File::isFile) }.map { Json.mapper.readTree(it) as ObjectNode }
            val renamed = trees.flatMap(::renamedSettings)
            require(renamed.isEmpty()) { "Invalid game modules:\n" + renamed.joinToString("\n") }
            val defs = trees.map { Json.mapper.convertValue<ModuleDef>(it) }.associateBy { it.id }

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
                // Shared fields, a game's overriding inherited ones by name; props refer to them as "@name" or {"use": name}
                val fields = Props(c.fold(emptyMap()) { acc, m -> acc + m.fields })
                c.forEach { m ->
                    m.nodeTypes.forEach { types[it.id] = it.copy(props = fields.expand(it.props, it.id)) }
                    m.edgeTypes.forEach { edges[it.id] = it }
                    m.patchTypes.forEach { p ->
                        val typeId = p.get("id")?.asText()
                        val def = requireNotNull(types[typeId]) { "Module '${m.id}' patches unknown type '$typeId'" }
                        val patch = p.deepCopy().apply { fields.expand(get("props"), def.id)?.let { replace("props", it) } }
                        types[def.id] = Overlay.patch(def, patch)
                    }
                }
                require(fields.errors.isEmpty()) { "Invalid game modules:\n" + fields.errors.distinct().joinToString("\n") }
                val hidden = c.flatMap { it.hide }.toSet()
                val resolved = Overlay.resolve(types)
                ResolvedModule(
                    id = self.id,
                    name = self.name,
                    types = resolved,
                    creatable = resolved.values.filter { it.id !in hidden },
                    edgeTypes = edges.values.toList(),
                    views = c.flatMap { it.views }.associateBy { it.id }.values.toList(),
                    overviewOrder = c.lastOrNull { it.overviewOrder.isNotEmpty() }?.overviewOrder.orEmpty(),
                    // A game overrides an inherited table or generator by reusing its name / id
                    tables = c.flatMap { it.tables }.associateBy { it.name }.values.toList(),
                    generators = c.flatMap { it.generators }.associateBy { it.id }.values.toList(),
                    starter = self.starter,
                    mapOrientation = c.lastOrNull { it.mapOrientation != null }?.mapOrientation ?: "landscape",
                )
            }
            // Modules that extend others re-check inherited types, hence distinct().
            val errors = resolved.values.flatMap { it.configErrors() + it.viewErrors() + it.generatorErrors() }.distinct()
            require(errors.isEmpty()) { "Invalid game modules:\n" + errors.joinToString("\n") }
            return GameModules(resolved)
        }

        /** Type settings that moved into groups: old key -> where it is now. */
        private val RENAMED = mapOf(
            "overviewSort" to "overview.sort", "overviewMuted" to "overview.muted", "overviewHidden" to "overview.hidden",
            "printTemplate" to "print.template", "printSummary" to "print.summary", "printNested" to "print.nested",
            "mapShape" to "campaignMap.shape", "mapDepth" to "campaignMap.depth", "mapBetween" to "campaignMap.between",
        )

        /** Old flat settings in a module's types and patches, which would otherwise be silently ignored. */
        private fun renamedSettings(module: ObjectNode): List<String> =
            (module.path("nodeTypes") + module.path("patchTypes")).flatMap { t ->
                val where = "${module.path("id").asText()} ${t.path("id").asText()}"
                RENAMED.filterKeys { t.has(it) }.map { (old, now) -> "$where: '$old' is now '$now'" } +
                    listOfNotNull("$where: 'overview' is now an object; its list is 'overview.columns'".takeIf { t.path("overview").isArray })
            }
    }
}
