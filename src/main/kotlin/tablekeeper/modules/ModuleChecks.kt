package tablekeeper.modules

/**
 * Problems with a module's settings, checked at load so a typo stops the app with a message instead of
 * silently doing nothing. Empty list = valid. Hidden types are not an error.
 */
internal fun ResolvedModule.configErrors(): List<String> = types.values.flatMap { def ->
    val t = def.id
    val options = linkOptions(t).map { it.key }.toSet()
    val props = propsSchema(t)?.get("properties")
    /** "<type>: <setting> '<key>' is not a link option of <type>", unless [key] is one. */
    fun rel(setting: String, key: String?) = key?.takeIf { it !in options }?.let { "$t: $setting '$it' is not a link option of $t" }
    fun prop(setting: String, name: String?) = name?.takeIf { props?.has(it) != true }?.let { "$t: $setting prop '$it' is not in its props schema" }
    // "show" is seen from the nested node, whose type varies, so only check that it names a real edge direction
    val edgeKeys = edgeTypes.flatMap { listOf("${it.id}:out", "${it.id}:in") }.toSet()
    val slotRels = def.linkSlots.orEmpty().flatMap { it.rels }

    def.quickCreate.orEmpty().map { q ->
        when {
            q.type !in types -> "$t: quickCreate type '${q.type}' is unknown"
            q.rel !in options -> rel("quickCreate rel", q.rel)
            !acceptsOther(linkOption(t, q.rel)!!, q.type) -> "$t: ${q.type} can't be linked via '${q.rel}'"
            else -> null
        }
    } +
        def.linkedProps.orEmpty().map { rel("linkedProps rel", it.rel) } +
        def.linkLimits.orEmpty().map { (key, max) -> rel("linkLimits key", key) ?: "$t: linkLimits '$key' must be at least 1".takeIf { max < 1 } } +
        slotRels.map { rel("linkSlots rel", it) } +
        slotRels.groupingBy { it }.eachCount().filter { it.value > 1 }.map { "$t: linkSlots rel '${it.key}' is in more than one slot" } +
        def.print?.summary?.propNames.orEmpty().map { prop("print summary", it) } +
        def.print?.nested.orEmpty().flatMap { n ->
            listOf(rel("print nested rel", n.rel), "$t: print nested show '${n.show}' is not an edge type and direction".takeIf { n.show !in edgeKeys })
        } +
        rel("context", def.context) +
        def.linkOrder.orEmpty().filter { it != "*" && it !in possibleLinkGroups(t) }
            .map { "$t: linkOrder '$it' is not a heading its links can have (${possibleLinkGroups(t).sorted().joinToString()})" } +
        def.icon?.takeIf { it !in ICONS }?.let { "$t: icon '$it' is not one of ${ICONS.joinToString()}" } +
        def.campaignMap?.shape?.takeIf { it !in MAP_SHAPES }?.let { "$t: campaignMap shape '$it' is not one of ${MAP_SHAPES.joinToString()}" } +
        def.campaignMap?.depth?.takeIf { p ->
            val s = props?.get(p)
            s?.get("type")?.asText() != "integer" && !(s?.has("enum") == true && s.get("x-depth")?.isObject == true)
        }?.let { "$t: campaignMap depth '$it' is not an integer prop, or an enum prop with \"x-depth\", in its props schema" } +
        rel("campaignMap between", def.campaignMap?.between) +
        rel("map places", def.map?.places) +
        def.map?.shape?.takeIf { it !in MAP_SHAPES || it == "none" }?.let { "$t: map shape '$it' is not one of ${(MAP_SHAPES - "none").joinToString()}" } +
        def.map?.let { m -> m.highlight?.takeIf { h -> linkOption(t, m.places)?.let { o -> placeTypes(o).none { propSchema(it, h)?.get("type")?.asText() == "boolean" } } ?: false } }
            ?.let { "$t: map highlight '$it' is not a boolean prop of its places" } +
        def.overview?.columns.orEmpty().map { col ->
            if (col.startsWith("@")) "$t: overview '$col' is not a link option of $t".takeIf { col.drop(1) !in options }
            else prop("overview", col)
        } +
        def.overview?.muted?.takeIf { props?.get(it)?.get("type")?.asText() != "boolean" }
            ?.let { "$t: overview muted '$it' is not a boolean prop in its props schema" } +
        def.checklists.orEmpty().flatMap { c ->
            listOf(rel("checklist rel", c.rel)) + c.types.filter { it !in types }.map { "$t: checklist type '$it' is unknown" }
        } +
        def.packet?.let { pk ->
            listOf(rel("packet rel", pk.rel)) +
                (pk.between.map { "between" to it } + pk.around.map { "around" to it } + pk.related.map { "related" to it }).map { (kind, b) ->
                    if (b.type !in types) "$t: packet $kind type '${b.type}' is unknown"
                    else "$t: packet $kind rel '${b.rel}' is not a link option of ${b.type}".takeIf { linkOption(b.type, b.rel) == null }
                }
        }.orEmpty() +
        prop("packet recap", def.packet?.recap) +
        def.debrief.orEmpty().flatMap { g -> debriefErrors(t, g) } +
        def.displayTitle?.let { Parts.names(it) }.orEmpty().filter { it != "title" }.map { prop("displayTitle", it) } +
        prop("overview sort", def.overview?.sort?.removePrefix("-"))
}.filterNotNull()

/** Problems with one `debrief` list of type [t]: unknown types, and fields or links none of its types have. */
private fun ResolvedModule.debriefErrors(t: String, g: DebriefDef): List<String?> {
    val known = g.types.filter { it in types }
    fun hasProp(types: List<String>, f: String) = types.any { propSchema(it, f) != null }
    return g.types.filter { it !in types }.map { "$t: debrief type '$it' is unknown" } +
        g.fields.filterNot { hasProp(known, it) }.map { "$t: debrief field '$it' is not a prop of ${g.types.joinToString()}" } +
        g.links.flatMap { l ->
            val options = known.mapNotNull { linkOption(it, l.rel) }
            listOf(
                "$t: debrief link '${l.rel}' is not a link option of ${g.types.joinToString()}".takeIf { options.isEmpty() },
                l.create?.let { c ->
                    when {
                        c !in types -> "$t: debrief create type '$c' is unknown"
                        known.none { k -> quickCreate(k).any { it.type.id == c && it.option.key == l.rel } } ->
                            "$t: debrief create '$c' via '${l.rel}' is not a quickCreate choice of ${g.types.joinToString()}"
                        else -> null
                    }
                },
            ) + l.fields.filter { f -> l.create != null && l.create in types && !hasProp(listOf(l.create), f) }
                .map { "$t: debrief link field '$it' is not a prop of ${l.create}" }
        }
}

/** Types that can sit at the far end of [option], e.g. a dungeon's rooms for "room_in:in". */
private fun ResolvedModule.placeTypes(option: LinkOption): List<String> = types.keys.filter { acceptsOther(option, it) }

/** The type a generator group makes: its own, or the first type its generator makes (null: unknown or circular). */
private fun ResolvedModule.groupType(g: GenNodeDef, seen: Set<String> = emptySet()): String? =
    g.type ?: g.generator?.takeIf { it !in seen }?.let { generator(it) }?.nodes?.firstOrNull()?.let { groupType(it, seen + g.generator!!) }

/** Does generator [id] use generator [target], itself or through the generators it uses? */
private fun ResolvedModule.uses(id: String, target: String, seen: Set<String> = emptySet()): Boolean =
    id == target || (id !in seen && generator(id)?.nodes.orEmpty().any { n -> n.generator?.let { uses(it, target, seen + id) } == true })

/** Problems with starter tables and generators; empty list = valid. */
internal fun ResolvedModule.generatorErrors(): List<String> {
    val fillable = fillableFields().map { it.key }.toSet()
    return tables.flatMap { tb -> tb.fields.filter { it !in fillable }.map { "table '${tb.name}': field '$it' is not a title or text field of a type" } } +
        (generators + listOfNotNull(starter?.copy(id = "starter"))).flatMap { g ->
            val seen = mutableSetOf<String>()
            g.nodes.flatMap { n ->
                val where = "generator '${g.id}' ${n.id}"
                val type = groupType(n)
                val errors = when {
                    (n.type == null) == (n.generator == null) -> listOf("$where: give either a type or a generator")
                    n.generator != null && generator(n.generator) == null -> listOf("$where: generator '${n.generator}' is unknown")
                    n.generator != null && uses(n.generator, g.id) -> listOf("$where: generator '${n.generator}' uses '${g.id}' in turn")
                    type == null || type !in types -> listOf("$where: type '${n.type}' is unknown")
                    else -> listOfNotNull(
                        "$where: count must be n or [min, max]".takeIf { n.count.size !in 1..2 || n.count.any { it < 0 } || n.count.first() > n.count.last() },
                        n.link?.let { l ->
                            when {
                                l.to !in seen -> "$where: link to '${l.to}' is not an earlier group"
                                linkOption(type, l.rel) == null -> "$where: link '${l.rel}' is not a link option of $type"
                                l.pick !in setOf("first", "random") -> "$where: link pick '${l.pick}' is not first or random"
                                else -> null
                            }
                        },
                    ) + (n.fill + n.chance.keys + (n.first.keys - "body") + n.row?.columns.orEmpty()).distinct().filter { f -> f != "title" && propSchema(type, f) == null }
                        .map { "$where: field '$it' is not a prop of $type" }
                }
                seen += n.id
                errors
            } + g.connect?.let { c ->
                val group = g.nodes.firstOrNull { it.id == c.nodes }
                listOfNotNull(
                    "generator '${g.id}': connect '${c.nodes}' is not a group".takeIf { group == null },
                    "generator '${g.id}': connect '${c.rel}' is not a link option of ${group?.let(::groupType)}"
                        .takeIf { group != null && groupType(group).let { it != null && it in types && linkOption(it, c.rel) == null } },
                    "generator '${g.id}': layout '${c.layout}' is not one of ${GEN_LAYOUTS.joinToString()}".takeIf { c.layout !in GEN_LAYOUTS },
                )
            }.orEmpty()
        }
}

/** Problems with declared views and the overview order; empty list = valid. */
internal fun ResolvedModule.viewErrors(): List<String> =
    overviewOrder.filter { it !in types }.map { "overviewOrder: type '$it' is unknown" } +
        views.flatMap { v ->
            v.types.filter { it !in types }.map { "view '${v.id}': type '$it' is unknown" } +
                v.groupBy.filter { prop -> v.types.none { propSchema(it, prop) != null } }
                    .map { "view '${v.id}': groupBy '$it' is not a prop of ${v.types.joinToString()}" } +
                v.links.filter { key -> v.types.none { it in types && linkOption(it, key) != null } }
                    .map { "view '${v.id}': link '$it' is not a link option of ${v.types.joinToString()}" }
        }
