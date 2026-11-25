package protector.core.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.io.File
import java.sql.Connection

class DatabaseManager(private val dbFile: File, poolSize: Int) {
    private val dataSource: HikariDataSource

    init {
        dbFile.parentFile?.mkdirs()
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dbFile.absolutePath}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = poolSize.coerceAtLeast(1)
            minimumIdle = 1
            connectionTestQuery = "SELECT 1"
            poolName = "Protector-SQLite"
            addDataSourceProperty("foreign_keys", "true")
            addDataSourceProperty("journal_mode", "WAL")
            addDataSourceProperty("synchronous", "NORMAL")
            addDataSourceProperty("busy_timeout", "10000")
        }
        dataSource = HikariDataSource(config)
        initializeSchema()
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

    private fun initializeSchema() {
        withConnection { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA journal_mode=WAL;")
                st.execute("PRAGMA synchronous=NORMAL;")
                st.execute("PRAGMA foreign_keys=ON;")

                // ID Mapping Tables (CoreProtect-inspired normalization)
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_users (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid TEXT NOT NULL UNIQUE,
                        name TEXT NOT NULL,
                        last_seen INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_users_name ON pt_users(name);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_worlds (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE
                    );
                    """.trimIndent()
                )

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_materials (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE
                    );
                    """.trimIndent()
                )

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_entities (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        name TEXT NOT NULL UNIQUE
                    );
                    """.trimIndent()
                )

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_blockdata (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        data TEXT NOT NULL UNIQUE
                    );
                    """.trimIndent()
                )

                // Land Claims Tables (GriefPrevention-inspired)
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_claims (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        parent_id INTEGER NULL REFERENCES pt_claims(id) ON DELETE CASCADE,
                        owner_uuid TEXT NULL,
                        world_name TEXT NOT NULL,
                        min_x INTEGER NOT NULL,
                        min_y INTEGER NOT NULL,
                        min_z INTEGER NOT NULL,
                        max_x INTEGER NOT NULL,
                        max_y INTEGER NOT NULL,
                        max_z INTEGER NOT NULL,
                        inherit_nothing INTEGER NOT NULL DEFAULT 0,
                        name TEXT NOT NULL,
                        greeting TEXT NULL,
                        created_at INTEGER NOT NULL,
                        updated_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_claims_world ON pt_claims(world_name);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_claim_trusts (
                        claim_id INTEGER NOT NULL REFERENCES pt_claims(id) ON DELETE CASCADE,
                        target_key TEXT NOT NULL,
                        level TEXT NOT NULL,
                        PRIMARY KEY (claim_id, target_key)
                    );
                    """.trimIndent()
                )

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_claim_flags (
                        claim_id INTEGER NOT NULL REFERENCES pt_claims(id) ON DELETE CASCADE,
                        flag_key TEXT NOT NULL,
                        value INTEGER NOT NULL,
                        PRIMARY KEY (claim_id, flag_key)
                    );
                    """.trimIndent()
                )

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_player_claim_data (
                        uuid TEXT PRIMARY KEY,
                        accrued_blocks INTEGER NOT NULL,
                        bonus_blocks INTEGER NOT NULL,
                        last_active INTEGER NOT NULL
                    );
                    """.trimIndent()
                )

                // Audit Log Tables (CoreProtect-inspired)
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_block_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        world_id INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        old_material_id INTEGER NOT NULL,
                        old_blockdata_id INTEGER NOT NULL,
                        new_material_id INTEGER NOT NULL,
                        new_blockdata_id INTEGER NOT NULL,
                        action INTEGER NOT NULL,
                        rolled_back INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_block_loc_time ON pt_block_logs(world_id, x, z, y, time);")
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_block_user_time ON pt_block_logs(user_id, time);")
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_block_time ON pt_block_logs(time);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_container_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        world_id INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        material_id INTEGER NOT NULL,
                        amount INTEGER NOT NULL,
                        item_nbt TEXT NULL,
                        action INTEGER NOT NULL,
                        rolled_back INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_container_loc_time ON pt_container_logs(world_id, x, z, y, time);")
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_container_user_time ON pt_container_logs(user_id, time);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_entity_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        world_id INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        entity_id INTEGER NOT NULL,
                        action INTEGER NOT NULL,
                        metadata TEXT NULL,
                        rolled_back INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_entity_loc_time ON pt_entity_logs(world_id, x, z, time);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_sign_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        world_id INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        side TEXT NOT NULL,
                        lines_json TEXT NOT NULL,
                        action INTEGER NOT NULL,
                        rolled_back INTEGER NOT NULL DEFAULT 0
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_sign_loc_time ON pt_sign_logs(world_id, x, z, y, time);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_chat_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        world_id INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        is_command INTEGER NOT NULL,
                        message TEXT NOT NULL
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_chat_user_time ON pt_chat_logs(user_id, time);")

                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS pt_session_logs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        time INTEGER NOT NULL,
                        user_id INTEGER NOT NULL,
                        world_id INTEGER NOT NULL,
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        z INTEGER NOT NULL,
                        action INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_pt_session_user_time ON pt_session_logs(user_id, time);")

                // Safe schema migrations for existing DBs
                ensureColumn(conn, "pt_sign_logs", "rolled_back", "INTEGER NOT NULL DEFAULT 0")
            }
        }
    }

    private fun ensureColumn(conn: Connection, table: String, column: String, definition: String) {
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
