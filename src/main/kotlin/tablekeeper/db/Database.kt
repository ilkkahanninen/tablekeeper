package tablekeeper.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import tablekeeper.Config
import javax.sql.DataSource

object Database {
    fun connect(config: Config): DSLContext {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = config.dbUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 5
        })
        migrate(ds)
        return DSL.using(ds, SQLDialect.POSTGRES)
    }

    private fun migrate(ds: DataSource) {
        Flyway.configure()
            .dataSource(ds)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }
}
