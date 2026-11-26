package protector.modules.audit

import protector.core.storage.DatabaseManager
import java.sql.Connection
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object AuditSchema {
    fun ensureInitialized(db: DatabaseManager) {
        db.initSchema { conn ->
            conn.createStatement().use { st ->
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

                db.ensureColumn(conn, "pt_sign_logs", "rolled_back", "INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}

class IdMappingCache(private val db: DatabaseManager) {
    private val userByKey = ConcurrentHashMap<String, Int>()
    private val userNameById = ConcurrentHashMap<Int, String>()
    private val userIdByName = ConcurrentHashMap<String, Int>()

    private val worldByName = ConcurrentHashMap<String, Int>()
    private val worldById = ConcurrentHashMap<Int, String>()

    private val materialByName = ConcurrentHashMap<String, Int>()
    private val materialById = ConcurrentHashMap<Int, String>()

    private val entityByName = ConcurrentHashMap<String, Int>()
    private val entityById = ConcurrentHashMap<Int, String>()

    private val blockDataByText = ConcurrentHashMap<String, Int>()
    private val blockDataById = ConcurrentHashMap<Int, String>()

    fun warmUp() {
        AuditSchema.ensureInitialized(db)
        db.withConnection { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, uuid, name FROM pt_users").use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        val uuid = rs.getString("uuid")
                        val name = rs.getString("name")
                        userByKey[uuid] = id
                        userNameById[id] = name
                        userIdByName[name.lowercase()] = id
                    }
                }
                st.executeQuery("SELECT id, name FROM pt_worlds").use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        val name = rs.getString("name")
                        worldByName[name] = id
                        worldById[id] = name
                    }
                }
                st.executeQuery("SELECT id, name FROM pt_materials").use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        val name = rs.getString("name")
                        materialByName[name] = id
                        materialById[id] = name
                    }
                }
                st.executeQuery("SELECT id, name FROM pt_entities").use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        val name = rs.getString("name")
                        entityByName[name] = id
                        entityById[id] = name
                    }
                }
                st.executeQuery("SELECT id, data FROM pt_blockdata").use { rs ->
                    while (rs.next()) {
                        val id = rs.getInt("id")
                        val data = rs.getString("data")
                        blockDataByText[data] = id
                        blockDataById[id] = data
                    }
                }
            }
        }
    }

    fun getOrCreateUser(conn: Connection, uuid: String, name: String, time: Long): Int {
        userByKey[uuid]?.let { id ->
            val prev = userNameById[id]
            if (prev != name) {
                userNameById[id] = name
                userIdByName[name.lowercase()] = id
                conn.prepareStatement("UPDATE pt_users SET name = ?, last_seen = ? WHERE id = ?").use { ps ->
                    ps.setString(1, name)
                    ps.setLong(2, time)
                    ps.setInt(3, id)
                    ps.executeUpdate()
                }
            }
            return id
        }

        synchronized(userByKey) {
            userByKey[uuid]?.let { return it }
            conn.prepareStatement(
                "INSERT INTO pt_users(uuid, name, last_seen) VALUES (?, ?, ?) ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, last_seen = excluded.last_seen",
                Statement.RETURN_GENERATED_KEYS
            ).use { ps ->
                ps.setString(1, uuid)
                ps.setString(2, name)
                ps.setLong(3, time)
                ps.executeUpdate()
            }
            val id = conn.prepareStatement("SELECT id FROM pt_users WHERE uuid = ?").use { ps ->
                ps.setString(1, uuid)
                ps.executeQuery().use { rs -> rs.getInt("id") }
            }
            userByKey[uuid] = id
            userNameById[id] = name
            userIdByName[name.lowercase()] = id
            return id
        }
    }

    fun findUserIdByName(name: String): Int? = userIdByName[name.lowercase()]

    fun userName(id: Int): String = userNameById[id] ?: "#$id"

    fun getOrCreateWorld(conn: Connection, worldName: String): Int =
        getOrCreateSimple(conn, worldName, worldByName, worldById, "pt_worlds", "name")

    fun worldName(id: Int): String = worldById[id] ?: "world"

    fun worldId(name: String): Int? = worldByName[name]

    fun getOrCreateMaterial(conn: Connection, materialName: String): Int =
        getOrCreateSimple(conn, materialName, materialByName, materialById, "pt_materials", "name")

    fun materialName(id: Int): String = materialById[id] ?: "AIR"

    fun getOrCreateEntity(conn: Connection, entityName: String): Int =
        getOrCreateSimple(conn, entityName, entityByName, entityById, "pt_entities", "name")

    fun entityName(id: Int): String = entityById[id] ?: "UNKNOWN"

    fun getOrCreateBlockData(conn: Connection, blockData: String): Int =
        getOrCreateSimple(conn, blockData, blockDataByText, blockDataById, "pt_blockdata", "data")

    fun blockDataText(id: Int): String = blockDataById[id] ?: ""

    private fun getOrCreateSimple(
        conn: Connection,
        value: String,
        forward: ConcurrentHashMap<String, Int>,
        reverse: ConcurrentHashMap<Int, String>,
        table: String,
        column: String
    ): Int {
        forward[value]?.let { return it }
        synchronized(forward) {
            forward[value]?.let { return it }
            conn.prepareStatement("INSERT OR IGNORE INTO $table($column) VALUES (?)").use { ps ->
                ps.setString(1, value)
                ps.executeUpdate()
            }
            val id = conn.prepareStatement("SELECT id FROM $table WHERE $column = ?").use { ps ->
                ps.setString(1, value)
                ps.executeQuery().use { rs -> rs.getInt("id") }
            }
            forward[value] = id
            reverse[id] = value
            return id
        }
    }

    companion object {
        fun actorUuidString(uuid: UUID?, actorName: String): String =
            uuid?.toString() ?: "system:$actorName"
    }
}
