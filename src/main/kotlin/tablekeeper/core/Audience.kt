package tablekeeper.core

/**
 * Who a rendered sheet is for. Enforced in PrintService before templates run,
 * so a template can never leak a GM-only fact onto a player handout.
 */
enum class Audience {
    GM, PLAYERS;

    fun canSee(visibility: String): Boolean = this == GM || Visibility.of(visibility) != Visibility.GM

    companion object {
        fun of(v: String?) = if (v.equals("players", ignoreCase = true)) PLAYERS else GM
    }
}
