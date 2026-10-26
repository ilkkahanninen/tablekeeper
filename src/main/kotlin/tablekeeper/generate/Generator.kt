package tablekeeper.generate

import tablekeeper.modules.GeneratorDef
import tablekeeper.modules.ResolvedModule
import kotlin.random.Random

/** A node a generator will make: the [index]th of its [group] (a [GenNodeDef] id). */
data class PlannedNode(val group: String, val index: Int, val type: String, val title: String, val props: Map<String, Any?>)

/** A link to make: node [from] links node [to] (indices into [GenPlan.nodes]) via [rel], seen from [from]. */
data class PlannedLink(val from: Int, val rel: String, val to: Int)

data class GenPlan(val nodes: List<PlannedNode>, val links: List<PlannedLink>)

/** Where a generator's random content comes from: the campaign's tables. Null = nothing to roll on. */
interface Rolls {
    /** A roll on the tables of a type's field ("title" or a prop). */
    fun field(type: String, field: String): String?

    /** A roll on the table named [name], e.g. a bestiary of "|"-separated rows. */
    fun table(name: String): String?
}

/**
 * What a generator makes, decided before anything is stored: pure, so the same [Random] and rolls give the same plan.
 * A group made by another generator ("generator") adds that generator's whole plan per node; its first node is the
 * group's member, the one links and other groups use.
 */
object Generator {
    fun plan(def: GeneratorDef, module: ResolvedModule, rolls: Rolls, random: Random): GenPlan {
        val nodes = mutableListOf<PlannedNode>()
        val links = mutableListOf<PlannedLink>()
        val groups = mutableMapOf<String, List<Int>>()

        def.nodes.forEach { g ->
            val count = if (g.count.size == 2) random.nextInt(g.count[0], g.count[1] + 1) else g.count.first()
            var numbered = 0
            val made = (0 until count).map { i ->
                val sub = g.generator?.let { module.generator(it) ?: error("Unknown generator $it") }
                if (sub != null) {
                    // Another generator's whole plan, shifted after what's planned so far; its first node is this member
                    val offset = nodes.size
                    val p = plan(sub, module, rolls, random)
                    nodes += p.nodes
                    links += p.links.map { it.copy(from = it.from + offset, to = it.to + offset) }
                    return@map offset
                }
                val type = g.type!!
                val first = if (i == 0) g.first else emptyMap()
                val row = g.row?.let { r -> rolls.table(r.table)?.let { columns(module, type, r.columns, it) } }.orEmpty()
                val fields = g.fill.filter { f -> f !in row && g.chance[f]?.let { random.nextDouble() < it } ?: true }
                val props = fields.filter { it != "title" }.mapNotNull { f -> value(module, type, f, rolls, random)?.let { f to it } }.toMap()
                val title = first["title"]?.toString()
                    ?: row["title"]?.toString()
                    ?: ("title" in fields).takeIf { it }?.let { rolls.field(type, "title") }
                    ?: g.title?.replace("{n}", "${++numbered}")
                    ?: "New ${module.label(type)}"
                nodes += PlannedNode(g.id, i, type, title, (row - "title") + props + (first - "title"))
                nodes.size - 1
            }
            g.link?.let { l ->
                val target = def.nodes.first { it.id == l.to }
                val to = groups.getValue(l.to)
                // "random": any of the group, but not its first (the entrance) when there are others
                val pool = if (target.first.isNotEmpty() && to.size > 1) to.drop(1) else to
                made.forEach { n -> links += PlannedLink(n, l.rel, if (l.pick == "random") pool.random(random) else to.first()) }
            }
            groups[g.id] = made
        }

        def.connect?.let { c ->
            val group = groups.getValue(c.nodes)
            val pairs = when (c.layout) {
                "dungeon" -> DungeonLayout.connect(group.size, random)
                else -> error("Unknown layout ${c.layout}")
            }
            pairs.forEach { (a, b) -> links += PlannedLink(group[a], c.rel, group[b]) }
        }
        return GenPlan(nodes, links)
    }

    /**
     * A table row's "|"-separated columns as [fields] values, typed by the props schema: a number for an integer prop
     * (dropped if it isn't one), an enum value only if it is one; empty columns are left out.
     */
    fun columns(module: ResolvedModule, type: String, fields: List<String>, row: String): Map<String, Any> =
        fields.zip(row.split("|").map { it.trim() }).filter { it.second.isNotEmpty() }.mapNotNull { (f, v) ->
            val schema = if (f == "title") null else module.propSchema(type, f)
            when {
                schema?.get("type")?.asText() == "integer" -> v.toIntOrNull()?.let { f to it }
                schema?.has("enum") == true -> v.takeIf { schema["enum"].any { e -> e.asText() == it } }?.let { f to it }
                else -> f to v
            }
        }.toMap()

    /** A roll for [field]; an enum prop (a dungeon's state) takes one of its values: a roll naming one, or else a random one. */
    private fun value(module: ResolvedModule, type: String, field: String, rolls: Rolls, random: Random): String? {
        val rolled = rolls.field(type, field)
        val values = module.propSchema(type, field)?.get("enum")?.map { it.asText() } ?: return rolled
        return rolled?.takeIf { it in values } ?: values.randomOrNull(random)
    }
}
