package tablekeeper.core

import org.jooq.DSLContext
import org.jooq.Record
import java.util.UUID

class CampaignService(private val db: DSLContext) {

    /** Creates the user if missing (used for the implicit 'local' user). */
    fun ensureUser(username: String): User {
        db.execute("insert into users (username) values (?) on conflict (username) do nothing", username)
        val r = db.fetchOne("select id, username from users where username = ?", username)!!
        return User(r.get("id", UUID::class.java), r.get("username", String::class.java))
    }

    fun list(owner: User): List<Campaign> =
        db.fetch("select id, name, game_module from campaigns where owner_id = ? order by created_at", owner.id)
            .map(::toCampaign)

    fun create(owner: User, name: String, gameModule: String): Campaign {
        val r = db.fetchOne(
            "insert into campaigns (owner_id, name, game_module) values (?, ?, ?) returning id, name, game_module",
            owner.id, name, gameModule,
        )!!
        return toCampaign(r)
    }

    /** Ownership check lives here: every campaign-scoped route goes through this. */
    fun get(owner: User, id: UUID): Campaign? =
        db.fetchOne("select id, name, game_module from campaigns where id = ? and owner_id = ?", id, owner.id)
            ?.let(::toCampaign)

    private fun toCampaign(r: Record) = Campaign(
        id = r.get("id", UUID::class.java),
        name = r.get("name", String::class.java),
        gameModule = r.get("game_module", String::class.java),
    )
}
