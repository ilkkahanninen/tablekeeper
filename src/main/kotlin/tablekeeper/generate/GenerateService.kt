package tablekeeper.generate

import com.fasterxml.jackson.databind.JsonNode
import tablekeeper.core.BadRequest
import tablekeeper.core.Campaign
import tablekeeper.core.GraphService
import tablekeeper.core.Json
import tablekeeper.core.Node
import tablekeeper.core.RandomChoice
import tablekeeper.core.RandomTableService
import tablekeeper.core.RandomTables
import tablekeeper.core.Visibility
import tablekeeper.modules.GeneratorDef
import tablekeeper.modules.Linker
import tablekeeper.modules.ResolvedModule
import kotlin.random.Random

/** Runs a module's generators: rolls on the campaign's tables, then stores the planned nodes and links. */
class GenerateService(private val graph: GraphService, private val tables: RandomTableService, private val linker: Linker) {

    /** Makes everything [def] plans in campaign [c] (ownership-checked by the caller); returns the first node. */
    fun run(c: Campaign, m: ResolvedModule, def: GeneratorDef, random: Random = Random.Default): Node {
        val byType = mutableMapOf<String, Map<String, List<RandomChoice>>>()
        val named by lazy { tables.list(c.id).associateBy { it.name } }
        val rolls = object : Rolls {
            // A random table of the field, then a roll on it: the same as a field's Random button with several tables
            override fun field(type: String, field: String) =
                byType.getOrPut(type) { tables.forType(c.id, type) }[field]?.randomOrNull(random)?.let { RandomTables.roll(it.items, it.mix, random = random) }
            override fun table(name: String) = named[name]?.let { RandomTables.roll(it.list, it.mix, random = random) }
        }

        val plan = Generator.plan(def, m, rolls, random)
        val made = plan.nodes.map { p ->
            val type = m.type(p.type) ?: throw BadRequest("Unknown type ${p.type}")
            val n = graph.createNode(c.id, p.type, type.codePrefix, p.title)
            if (p.props.isEmpty()) n
            else {
                val props = Json.mapper.valueToTree<JsonNode>(p.props)
                m.validateProps(p.type, props).firstOrNull()?.let { throw BadRequest("${p.title}: $it") }
                graph.updateNode(c.id, n.id, p.title, "", Visibility.of(n.visibility), props)
                n.copy(props = p.props)
            }
        }
        plan.links.forEach { l ->
            val from = made[l.from]
            linker.link(c.id, m, from, m.linkOption(from.type, l.rel) ?: throw BadRequest("Can't link ${from.type} via ${l.rel}"), made[l.to])
        }
        return made.first()
    }
}
