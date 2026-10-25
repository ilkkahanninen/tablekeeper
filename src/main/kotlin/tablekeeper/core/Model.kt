package tablekeeper.core

import java.util.UUID

data class User(val id: UUID, val username: String)

data class Campaign(val id: UUID, val name: String, val gameModule: String)

enum class Visibility(val dbValue: String, val label: String) {
    GM("gm", "GM only"),
    PLAYERS("players", "Players may learn"),
    REVEALED("revealed", "Revealed");

    companion object {
        fun of(v: String?) = entries.firstOrNull { it.dbValue == v } ?: GM
    }
}

data class Node(
    val id: UUID,
    val campaignId: UUID,
    val type: String,
    val code: String,
    val title: String,
    val body: String,
    val props: Map<String, Any?>,
    val visibility: String,
)

/** Lightweight node reference for lists, search results and links. */
data class NodeRef(
    val id: UUID,
    val type: String,
    val code: String,
    val title: String,
    val visibility: String,
)

/** A bare edge: [fromId] --[type]--> [toId]. */
data class EdgeRef(val fromId: UUID, val toId: UUID, val type: String)

enum class Direction {
    OUT, IN;

    val opposite: Direction get() = if (this == OUT) IN else OUT

    /** A link option's key for an edge type in this direction, as written in module.json: "located_at:in". */
    fun key(edgeType: String): String = "$edgeType:${name.lowercase()}"
}

/**
 * An edge seen from one node. For an IN edge, `other` is the edge's source.
 * Example: Silvia --located_at--> Red Market seen from Red Market is
 * Link(type=located_at, direction=IN, other=Silvia).
 */
data class Link(
    val edgeId: UUID,
    val type: String,
    val direction: Direction,
    val other: NodeRef,
) {
    /** "located_at:in", matching the link option this edge is seen through. */
    val key: String get() = direction.key(type)
}
