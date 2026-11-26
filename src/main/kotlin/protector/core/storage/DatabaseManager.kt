package protector.core.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection

class DatabaseManager(
    dbFile: File,
    poolSize: Int,
    poolName: String = "Protector-SQLite",
) {
    private val dataSource: HikariDataSource

    init {
        dbFile.parentFile?.mkdirs()
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = poolSize.coerceAtLeast(1)
            minimumIdle = 1
            connectionTestQuery = "SELECT 1"
            this.poolName = poolName
            addDataSourceProperty("foreign_keys", "true")
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "NORMAL")
            addDataSourceProperty("busy_timeout", "10000")
        }
        dataSource = HikariDataSource(config)
        withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute("PRAGMA synchronous=NORMAL;")
                st.execute("PRAGMA foreign_keys=ON;")
            }
        }
    }

    fun initSchema(block: (Connection) -> Unit) {
        withConnection(block)
    }

    fun <T> withConnection(block: (Connection) -> T): T =
        dataSource.connection.use(block)

    fun <T> withTransaction(block: (Connection) -> T): T =
        dataSource.connection.use { conn ->
            val prevAutoCommit = conn.autoCommit
            conn.autoCommit = false
            try {
                val result = block(conn)
                conn.commit()
                result
            } catch (ex: Throwable) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                conn.autoCommit = prevAutoCommit
            }
        }

    fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
        var exists = false
        conn.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info($table);").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name").equals(column, ignoreCase = true)) {
                        exists = true
                        break
                    }
                }
            }
            if (!exists) {
                st.execute("ALTER TABLE $table ADD COLUMN $column $definition;")
            }
        }
    }

    fun close() {
        if (!dataSource.isClosed) {
            dataSource.close()
        }
    }
}
