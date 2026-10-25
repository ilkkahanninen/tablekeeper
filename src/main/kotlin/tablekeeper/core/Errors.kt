package tablekeeper.core

/** Domain errors; App's StatusPages maps each to an HTTP status. */
class NotFound(what: String) : RuntimeException("$what not found")
class BadRequest(msg: String) : RuntimeException(msg)
class HiddenFromAudience : RuntimeException("This node is GM-only and can't be printed for players")
