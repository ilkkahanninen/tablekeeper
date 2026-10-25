package tablekeeper.print

import java.util.UUID

/** One part of a session packet: the selected (or in-between) nodes of one type, each as its print model. */
data class PacketSection(val typeId: String, val label: String, val items: List<Map<String, Any?>>)

/** Pure selection rules for session packets. */
object Packet {
    /**
     * [candidates] (e.g. delves) whose links (given by [linksOf], e.g. to their landmarks) all end at [selected]
     * nodes, with at least one such link: the delves between the session's landmarks, dead ends included.
     */
    fun between(selected: Set<UUID>, candidates: List<UUID>, linksOf: (UUID) -> List<UUID>): List<UUID> =
        candidates.filter { c -> linksOf(c).let { links -> links.isNotEmpty() && links.all { it in selected } } }

    /**
     * [candidates] (e.g. NPCs) with a link (given by [linksOf], e.g. their location) to a [selected] node, or to a node
     * whose [parentOf] is selected: NPCs at a selected landmark or at one of its minor locations.
     */
    fun around(selected: Set<UUID>, candidates: List<UUID>, linksOf: (UUID) -> List<UUID>, parentOf: (UUID) -> UUID?): List<UUID> =
        candidates.filter { c -> linksOf(c).any { it in selected || parentOf(it) in selected } }
}
