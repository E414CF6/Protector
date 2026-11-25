package protector.core.storage

import java.sql.Connection
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    fun getOrCreateSystemActor(conn: Connection, actorKey: String, time: Long): Int =
        getOrCreateUser(conn, "system:$actorKey", actorKey, time)

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
