package tablekeeper.auth

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Slows down password guessing: after [limit] failed logins for one key (name and address) within [window], that key
 * is refused until the oldest of them is [window] old. In memory, so a restart forgets it.
 */
class LoginThrottle(
    private val limit: Int = 5,
    private val window: Duration = Duration.ofMinutes(15),
    private val now: () -> Instant = Instant::now,
) {
    private val failures = ConcurrentHashMap<String, List<Instant>>()

    fun blocked(key: String): Boolean = recent(failures[key].orEmpty()).size >= limit

    fun failed(key: String) {
        if (failures.size > MAX_KEYS) failures.entries.removeIf { recent(it.value).isEmpty() }
        failures.merge(key, listOf(now())) { old, new -> recent(old) + new }
    }

    fun succeeded(key: String) {
        failures.remove(key)
    }

    private fun recent(times: List<Instant>): List<Instant> = now().minus(window).let { since -> times.filter { it.isAfter(since) } }

    private companion object {
        const val MAX_KEYS = 10_000
    }
}
