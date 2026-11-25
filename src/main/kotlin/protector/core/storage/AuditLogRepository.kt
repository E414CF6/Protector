package protector.core.storage

import java.sql.PreparedStatement
import java.util.UUID

enum class BlockLogAction(val code: Int, val label: String) {
    BREAK(0, "broke"),
    PLACE(1, "placed"),
    EXPLODE(2, "exploded"),
    BURN(3, "burned"),
    FLOW(4, "flowed"),
    INTERACT(5, "clicked"),
    GROW(6, "grew"),
    DECAY(7, "decayed"),
    FORM(8, "formed"),
    FERTILIZE(9, "fertilized");

    companion object {
        fun fromCode(code: Int): BlockLogAction = entries.firstOrNull { it.code == code } ?: BREAK
    }
}

enum class ContainerLogAction(val code: Int, val label: String) {
    REMOVE(0, "removed"),
    ADD(1, "added");

    companion object {
        fun fromCode(code: Int): ContainerLogAction = entries.firstOrNull { it.code == code } ?: REMOVE
    }
}

enum class EntityLogAction(val code: Int, val label: String) {
    KILL(0, "killed"),
    SPAWN(1, "spawned"),
    INTERACT(2, "interacted");

    companion object {
        fun fromCode(code: Int): EntityLogAction = entries.firstOrNull { it.code == code } ?: KILL
    }
}

enum class LookupActionFilter {
    ALL, BLOCK, CONTAINER, ENTITY, SIGN, CHAT, SESSION
}

sealed interface PendingAuditEvent {
    val timeSec: Long
    val actorUuid: UUID?
    val actorName: String
    val worldName: String
    val x: Int
    val y: Int
    val z: Int
}

data class PendingBlockEvent(
    override val timeSec: Long,
    override val actorUuid: UUID?,
    override val actorName: String,
    override val worldName: String,
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val oldMaterial: String,
    val oldBlockData: String,
    val newMaterial: String,
    val newBlockData: String,
    val action: BlockLogAction
) : PendingAuditEvent

data class PendingContainerEvent(
    override val timeSec: Long,
    override val actorUuid: UUID?,
    override val actorName: String,
    override val worldName: String,
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val material: String,
    val amount: Int,
    val itemNbt: String?,
    val action: ContainerLogAction
) : PendingAuditEvent

data class PendingEntityEvent(
    override val timeSec: Long,
    override val actorUuid: UUID?,
    override val actorName: String,
    override val worldName: String,
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val entityType: String,
    val action: EntityLogAction,
    val metadata: String?
) : PendingAuditEvent

data class PendingSignEvent(
    override val timeSec: Long,
    override val actorUuid: UUID?,
    override val actorName: String,
    override val worldName: String,
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val side: String,
    val linesJson: String,
    val action: Int
) : PendingAuditEvent

data class PendingChatEvent(
    override val timeSec: Long,
    override val actorUuid: UUID?,
    override val actorName: String,
    override val worldName: String,
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val isCommand: Boolean,
    val message: String
) : PendingAuditEvent

data class PendingSessionEvent(
    override val timeSec: Long,
    override val actorUuid: UUID?,
    override val actorName: String,
    override val worldName: String,
    override val x: Int,
    override val y: Int,
    override val z: Int,
    val login: Boolean
) : PendingAuditEvent

data class BlockLogRecord(
    val id: Long,
    val timeSec: Long,
    val actorName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val oldMaterial: String,
    val oldBlockData: String,
    val newMaterial: String,
    val newBlockData: String,
    val action: BlockLogAction,
    val rolledBack: Boolean
)

data class ContainerLogRecord(
    val id: Long,
    val timeSec: Long,
    val actorName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val material: String,
    val amount: Int,
    val itemNbt: String?,
    val action: ContainerLogAction,
    val rolledBack: Boolean
)

data class EntityLogRecord(
    val id: Long,
    val timeSec: Long,
    val actorName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val entityType: String,
    val action: EntityLogAction,
    val metadata: String?,
    val rolledBack: Boolean
)

data class SignLogRecord(
    val id: Long,
    val timeSec: Long,
    val actorName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val side: String,
    val linesJson: String,
    val action: Int,
    val rolledBack: Boolean
)

data class ChatLogRecord(
    val id: Long,
    val timeSec: Long,
    val actorName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val isCommand: Boolean,
    val message: String
)

data class SessionLogRecord(
    val id: Long,
    val timeSec: Long,
    val actorName: String,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val login: Boolean
)

data class LookupFilter(
    val worldName: String,
    val centerX: Int,
    val centerY: Int,
    val centerZ: Int,
    val radius: Int = 10,
    val sinceEpochSec: Long = 0L,
    val userName: String? = null,
    val includeMaterials: Set<String> = emptySet(),
    val excludeMaterials: Set<String> = emptySet(),
    val actionFilter: LookupActionFilter = LookupActionFilter.ALL,
    val limit: Int = 500,
    val rolledBackFilter: Boolean? = null
)

class AuditLogRepository(
    private val db: DatabaseManager,
    private val ids: IdMappingCache
) {
    fun insertBatch(events: List<PendingAuditEvent>) {
        if (events.isEmpty()) return
        db.withTransaction { conn ->
            val blockPs = conn.prepareStatement(
                """
                INSERT INTO pt_block_logs(
                    time, user_id, world_id, x, y, z,
                    old_material_id, old_blockdata_id, new_material_id, new_blockdata_id, action, rolled_back
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent()
            )
            val containerPs = conn.prepareStatement(
                """
                INSERT INTO pt_container_logs(
                    time, user_id, world_id, x, y, z,
                    material_id, amount, item_nbt, action, rolled_back
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent()
            )
            val entityPs = conn.prepareStatement(
                """
                INSERT INTO pt_entity_logs(
                    time, user_id, world_id, x, y, z, entity_id, action, metadata, rolled_back
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent()
            )
            val signPs = conn.prepareStatement(
                """
                INSERT INTO pt_sign_logs(
                    time, user_id, world_id, x, y, z, side, lines_json, action, rolled_back
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0)
                """.trimIndent()
            )
            val chatPs = conn.prepareStatement(
                """
                INSERT INTO pt_chat_logs(
                    time, user_id, world_id, x, y, z, is_command, message
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )
            val sessionPs = conn.prepareStatement(
                """
                INSERT INTO pt_session_logs(
                    time, user_id, world_id, x, y, z, action
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            )

            var hasBlocks = false
            var hasContainers = false
            var hasEntities = false
            var hasSigns = false
            var hasChats = false
            var hasSessions = false

            try {
                for (ev in events) {
                    val userId = ids.getOrCreateUser(
                        conn,
                        IdMappingCache.actorUuidString(ev.actorUuid, ev.actorName),
                        ev.actorName,
                        ev.timeSec
                    )
                    val worldId = ids.getOrCreateWorld(conn, ev.worldName)

                    when (ev) {
                        is PendingBlockEvent -> {
                            blockPs.setLong(1, ev.timeSec)
                            blockPs.setInt(2, userId)
                            blockPs.setInt(3, worldId)
                            blockPs.setInt(4, ev.x)
                            blockPs.setInt(5, ev.y)
                            blockPs.setInt(6, ev.z)
                            blockPs.setInt(7, ids.getOrCreateMaterial(conn, ev.oldMaterial))
                            blockPs.setInt(8, ids.getOrCreateBlockData(conn, ev.oldBlockData))
                            blockPs.setInt(9, ids.getOrCreateMaterial(conn, ev.newMaterial))
                            blockPs.setInt(10, ids.getOrCreateBlockData(conn, ev.newBlockData))
                            blockPs.setInt(11, ev.action.code)
                            blockPs.addBatch()
                            hasBlocks = true
                        }

                        is PendingContainerEvent -> {
                            containerPs.setLong(1, ev.timeSec)
                            containerPs.setInt(2, userId)
                            containerPs.setInt(3, worldId)
                            containerPs.setInt(4, ev.x)
                            containerPs.setInt(5, ev.y)
                            containerPs.setInt(6, ev.z)
                            containerPs.setInt(7, ids.getOrCreateMaterial(conn, ev.material))
                            containerPs.setInt(8, ev.amount)
                            if (ev.itemNbt == null) containerPs.setNull(9, java.sql.Types.VARCHAR)
                            else containerPs.setString(9, ev.itemNbt)
                            containerPs.setInt(10, ev.action.code)
                            containerPs.addBatch()
                            hasContainers = true
                        }

                        is PendingEntityEvent -> {
                            entityPs.setLong(1, ev.timeSec)
                            entityPs.setInt(2, userId)
                            entityPs.setInt(3, worldId)
                            entityPs.setInt(4, ev.x)
                            entityPs.setInt(5, ev.y)
                            entityPs.setInt(6, ev.z)
                            entityPs.setInt(7, ids.getOrCreateEntity(conn, ev.entityType))
                            entityPs.setInt(8, ev.action.code)
                            if (ev.metadata == null) entityPs.setNull(9, java.sql.Types.VARCHAR)
                            else entityPs.setString(9, ev.metadata)
                            entityPs.addBatch()
                            hasEntities = true
                        }

                        is PendingSignEvent -> {
                            signPs.setLong(1, ev.timeSec)
                            signPs.setInt(2, userId)
                            signPs.setInt(3, worldId)
                            signPs.setInt(4, ev.x)
                            signPs.setInt(5, ev.y)
                            signPs.setInt(6, ev.z)
                            signPs.setString(7, ev.side)
                            signPs.setString(8, ev.linesJson)
                            signPs.setInt(9, ev.action)
                            signPs.addBatch()
                            hasSigns = true
                        }

                        is PendingChatEvent -> {
                            chatPs.setLong(1, ev.timeSec)
                            chatPs.setInt(2, userId)
                            chatPs.setInt(3, worldId)
                            chatPs.setInt(4, ev.x)
                            chatPs.setInt(5, ev.y)
                            chatPs.setInt(6, ev.z)
                            chatPs.setInt(7, if (ev.isCommand) 1 else 0)
                            chatPs.setString(8, ev.message)
                            chatPs.addBatch()
                            hasChats = true
                        }

                        is PendingSessionEvent -> {
                            sessionPs.setLong(1, ev.timeSec)
                            sessionPs.setInt(2, userId)
                            sessionPs.setInt(3, worldId)
                            sessionPs.setInt(4, ev.x)
                            sessionPs.setInt(5, ev.y)
                            sessionPs.setInt(6, ev.z)
                            sessionPs.setInt(7, if (ev.login) 1 else 0)
                            sessionPs.addBatch()
                            hasSessions = true
                        }
                    }
                }

                if (hasBlocks) blockPs.executeBatch()
                if (hasContainers) containerPs.executeBatch()
                if (hasEntities) entityPs.executeBatch()
                if (hasSigns) signPs.executeBatch()
                if (hasChats) chatPs.executeBatch()
                if (hasSessions) sessionPs.executeBatch()
            } finally {
                blockPs.close()
                containerPs.close()
                entityPs.close()
                signPs.close()
                chatPs.close()
                sessionPs.close()
            }
        }
    }

    fun queryBlockAt(worldName: String, x: Int, y: Int, z: Int, limit: Int = 20): List<BlockLogRecord> {
        val worldId = ids.worldId(worldName) ?: return emptyList()
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT id, time, user_id, x, y, z, old_material_id, old_blockdata_id, new_material_id, new_blockdata_id, action, rolled_back
                FROM pt_block_logs
                WHERE world_id = ? AND x = ? AND y = ? AND z = ?
                ORDER BY time DESC, id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, worldId)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<BlockLogRecord>()
                    while (rs.next()) {
                        list.add(
                            BlockLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                oldMaterial = ids.materialName(rs.getInt("old_material_id")),
                                oldBlockData = ids.blockDataText(rs.getInt("old_blockdata_id")),
                                newMaterial = ids.materialName(rs.getInt("new_material_id")),
                                newBlockData = ids.blockDataText(rs.getInt("new_blockdata_id")),
                                action = BlockLogAction.fromCode(rs.getInt("action")),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun queryContainerAt(worldName: String, x: Int, y: Int, z: Int, limit: Int = 20): List<ContainerLogRecord> {
        val worldId = ids.worldId(worldName) ?: return emptyList()
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT id, time, user_id, x, y, z, material_id, amount, item_nbt, action, rolled_back
                FROM pt_container_logs
                WHERE world_id = ? AND x = ? AND y = ? AND z = ?
                ORDER BY time DESC, id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, worldId)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<ContainerLogRecord>()
                    while (rs.next()) {
                        list.add(
                            ContainerLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                material = ids.materialName(rs.getInt("material_id")),
                                amount = rs.getInt("amount"),
                                itemNbt = rs.getString("item_nbt"),
                                action = ContainerLogAction.fromCode(rs.getInt("action")),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun querySignAt(worldName: String, x: Int, y: Int, z: Int, limit: Int = 10): List<SignLogRecord> {
        val worldId = ids.worldId(worldName) ?: return emptyList()
        return db.withConnection { conn ->
            conn.prepareStatement(
                """
                SELECT id, time, user_id, x, y, z, side, lines_json, action, rolled_back
                FROM pt_sign_logs
                WHERE world_id = ? AND x = ? AND y = ? AND z = ?
                ORDER BY time DESC, id DESC
                LIMIT ?
                """.trimIndent()
            ).use { ps ->
                ps.setInt(1, worldId)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<SignLogRecord>()
                    while (rs.next()) {
                        list.add(
                            SignLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                side = rs.getString("side"),
                                linesJson = rs.getString("lines_json"),
                                action = rs.getInt("action"),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun queryBlockLogs(filter: LookupFilter, ascendingTime: Boolean = false): List<BlockLogRecord> {
        val worldId = ids.worldId(filter.worldName) ?: return emptyList()
        val userId = filter.userName?.let { ids.findUserIdByName(it) ?: return emptyList() }
        val order = if (ascendingTime) "ASC" else "DESC"

        val sql = buildString {
            append(
                """
                SELECT id, time, user_id, x, y, z, old_material_id, old_blockdata_id, new_material_id, new_blockdata_id, action, rolled_back
                FROM pt_block_logs
                WHERE world_id = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND time >= ?
                """.trimIndent()
            )
            if (userId != null) append(" AND user_id = ?")
            if (filter.rolledBackFilter != null) append(" AND rolled_back = ?")
            append(" ORDER BY time $order, id $order LIMIT ?")
        }

        return db.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setInt(idx++, worldId)
                ps.setInt(idx++, filter.centerX - filter.radius)
                ps.setInt(idx++, filter.centerX + filter.radius)
                ps.setInt(idx++, filter.centerY - filter.radius)
                ps.setInt(idx++, filter.centerY + filter.radius)
                ps.setInt(idx++, filter.centerZ - filter.radius)
                ps.setInt(idx++, filter.centerZ + filter.radius)
                ps.setLong(idx++, filter.sinceEpochSec)
                if (userId != null) ps.setInt(idx++, userId)
                if (filter.rolledBackFilter != null) ps.setInt(idx++, if (filter.rolledBackFilter) 1 else 0)
                ps.setInt(idx, filter.limit)

                ps.executeQuery().use { rs ->
                    val list = ArrayList<BlockLogRecord>()
                    while (rs.next()) {
                        val oldMat = ids.materialName(rs.getInt("old_material_id"))
                        val newMat = ids.materialName(rs.getInt("new_material_id"))
                        if (filter.includeMaterials.isNotEmpty() &&
                            oldMat !in filter.includeMaterials && newMat !in filter.includeMaterials
                        ) continue
                        if (filter.excludeMaterials.isNotEmpty() &&
                            (oldMat in filter.excludeMaterials || newMat in filter.excludeMaterials)
                        ) continue

                        list.add(
                            BlockLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = filter.worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                oldMaterial = oldMat,
                                oldBlockData = ids.blockDataText(rs.getInt("old_blockdata_id")),
                                newMaterial = newMat,
                                newBlockData = ids.blockDataText(rs.getInt("new_blockdata_id")),
                                action = BlockLogAction.fromCode(rs.getInt("action")),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun queryContainerLogs(filter: LookupFilter, ascendingTime: Boolean = false): List<ContainerLogRecord> {
        val worldId = ids.worldId(filter.worldName) ?: return emptyList()
        val userId = filter.userName?.let { ids.findUserIdByName(it) ?: return emptyList() }
        val order = if (ascendingTime) "ASC" else "DESC"

        val sql = buildString {
            append(
                """
                SELECT id, time, user_id, x, y, z, material_id, amount, item_nbt, action, rolled_back
                FROM pt_container_logs
                WHERE world_id = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND time >= ?
                """.trimIndent()
            )
            if (userId != null) append(" AND user_id = ?")
            if (filter.rolledBackFilter != null) append(" AND rolled_back = ?")
            append(" ORDER BY time $order, id $order LIMIT ?")
        }

        return db.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setInt(idx++, worldId)
                ps.setInt(idx++, filter.centerX - filter.radius)
                ps.setInt(idx++, filter.centerX + filter.radius)
                ps.setInt(idx++, filter.centerY - filter.radius)
                ps.setInt(idx++, filter.centerY + filter.radius)
                ps.setInt(idx++, filter.centerZ - filter.radius)
                ps.setInt(idx++, filter.centerZ + filter.radius)
                ps.setLong(idx++, filter.sinceEpochSec)
                if (userId != null) ps.setInt(idx++, userId)
                if (filter.rolledBackFilter != null) ps.setInt(idx++, if (filter.rolledBackFilter) 1 else 0)
                ps.setInt(idx, filter.limit)

                ps.executeQuery().use { rs ->
                    val list = ArrayList<ContainerLogRecord>()
                    while (rs.next()) {
                        val mat = ids.materialName(rs.getInt("material_id"))
                        if (filter.includeMaterials.isNotEmpty() && mat !in filter.includeMaterials) continue
                        if (filter.excludeMaterials.isNotEmpty() && mat in filter.excludeMaterials) continue
                        list.add(
                            ContainerLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = filter.worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                material = mat,
                                amount = rs.getInt("amount"),
                                itemNbt = rs.getString("item_nbt"),
                                action = ContainerLogAction.fromCode(rs.getInt("action")),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun queryEntityLogs(filter: LookupFilter, ascendingTime: Boolean = false): List<EntityLogRecord> {
        val worldId = ids.worldId(filter.worldName) ?: return emptyList()
        val userId = filter.userName?.let { ids.findUserIdByName(it) ?: return emptyList() }
        val order = if (ascendingTime) "ASC" else "DESC"

        val sql = buildString {
            append(
                """
                SELECT id, time, user_id, x, y, z, entity_id, action, metadata, rolled_back
                FROM pt_entity_logs
                WHERE world_id = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND time >= ?
                """.trimIndent()
            )
            if (userId != null) append(" AND user_id = ?")
            if (filter.rolledBackFilter != null) append(" AND rolled_back = ?")
            append(" ORDER BY time $order, id $order LIMIT ?")
        }

        return db.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setInt(idx++, worldId)
                ps.setInt(idx++, filter.centerX - filter.radius)
                ps.setInt(idx++, filter.centerX + filter.radius)
                ps.setInt(idx++, filter.centerY - filter.radius)
                ps.setInt(idx++, filter.centerY + filter.radius)
                ps.setInt(idx++, filter.centerZ - filter.radius)
                ps.setInt(idx++, filter.centerZ + filter.radius)
                ps.setLong(idx++, filter.sinceEpochSec)
                if (userId != null) ps.setInt(idx++, userId)
                if (filter.rolledBackFilter != null) ps.setInt(idx++, if (filter.rolledBackFilter) 1 else 0)
                ps.setInt(idx, filter.limit)

                ps.executeQuery().use { rs ->
                    val list = ArrayList<EntityLogRecord>()
                    while (rs.next()) {
                        list.add(
                            EntityLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = filter.worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                entityType = ids.entityName(rs.getInt("entity_id")),
                                action = EntityLogAction.fromCode(rs.getInt("action")),
                                metadata = rs.getString("metadata"),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun querySignLogs(filter: LookupFilter): List<SignLogRecord> {
        val worldId = ids.worldId(filter.worldName) ?: return emptyList()
        val userId = filter.userName?.let { ids.findUserIdByName(it) ?: return emptyList() }
        val sql = buildString {
            append(
                """
                SELECT id, time, user_id, x, y, z, side, lines_json, action, rolled_back
                FROM pt_sign_logs
                WHERE world_id = ? AND x BETWEEN ? AND ? AND y BETWEEN ? AND ? AND z BETWEEN ? AND ? AND time >= ?
                """.trimIndent()
            )
            if (userId != null) append(" AND user_id = ?")
            append(" ORDER BY time DESC, id DESC LIMIT ?")
        }
        return db.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setInt(idx++, worldId)
                ps.setInt(idx++, filter.centerX - filter.radius)
                ps.setInt(idx++, filter.centerX + filter.radius)
                ps.setInt(idx++, filter.centerY - filter.radius)
                ps.setInt(idx++, filter.centerY + filter.radius)
                ps.setInt(idx++, filter.centerZ - filter.radius)
                ps.setInt(idx++, filter.centerZ + filter.radius)
                ps.setLong(idx++, filter.sinceEpochSec)
                if (userId != null) ps.setInt(idx++, userId)
                ps.setInt(idx, filter.limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<SignLogRecord>()
                    while (rs.next()) {
                        list.add(
                            SignLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = filter.worldName,
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                side = rs.getString("side"),
                                linesJson = rs.getString("lines_json"),
                                action = rs.getInt("action"),
                                rolledBack = rs.getInt("rolled_back") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun queryChatLogs(
        sinceEpochSec: Long,
        userName: String? = null,
        commandsOnly: Boolean? = null,
        limit: Int = 100
    ): List<ChatLogRecord> {
        val userId = userName?.let { ids.findUserIdByName(it) ?: return emptyList() }
        val sql = buildString {
            append("SELECT id, time, user_id, world_id, x, y, z, is_command, message FROM pt_chat_logs WHERE time >= ?")
            if (userId != null) append(" AND user_id = ?")
            if (commandsOnly != null) append(" AND is_command = ?")
            append(" ORDER BY time DESC, id DESC LIMIT ?")
        }
        return db.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setLong(idx++, sinceEpochSec)
                if (userId != null) ps.setInt(idx++, userId)
                if (commandsOnly != null) ps.setInt(idx++, if (commandsOnly) 1 else 0)
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<ChatLogRecord>()
                    while (rs.next()) {
                        list.add(
                            ChatLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = ids.worldName(rs.getInt("world_id")),
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                isCommand = rs.getInt("is_command") != 0,
                                message = rs.getString("message")
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun querySessionLogs(sinceEpochSec: Long, userName: String? = null, limit: Int = 100): List<SessionLogRecord> {
        val userId = userName?.let { ids.findUserIdByName(it) ?: return emptyList() }
        val sql = buildString {
            append("SELECT id, time, user_id, world_id, x, y, z, action FROM pt_session_logs WHERE time >= ?")
            if (userId != null) append(" AND user_id = ?")
            append(" ORDER BY time DESC, id DESC LIMIT ?")
        }
        return db.withConnection { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                ps.setLong(idx++, sinceEpochSec)
                if (userId != null) ps.setInt(idx++, userId)
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    val list = ArrayList<SessionLogRecord>()
                    while (rs.next()) {
                        list.add(
                            SessionLogRecord(
                                id = rs.getLong("id"),
                                timeSec = rs.getLong("time"),
                                actorName = ids.userName(rs.getInt("user_id")),
                                worldName = ids.worldName(rs.getInt("world_id")),
                                x = rs.getInt("x"),
                                y = rs.getInt("y"),
                                z = rs.getInt("z"),
                                login = rs.getInt("action") != 0
                            )
                        )
                    }
                    list
                }
            }
        }
    }

    fun markBlocksRolledBack(idsToUpdate: List<Long>, rolledBack: Boolean) {
        markRolledBackTable("pt_block_logs", idsToUpdate, rolledBack)
    }

    fun markContainersRolledBack(idsToUpdate: List<Long>, rolledBack: Boolean) {
        markRolledBackTable("pt_container_logs", idsToUpdate, rolledBack)
    }

    fun markEntitiesRolledBack(idsToUpdate: List<Long>, rolledBack: Boolean) {
        markRolledBackTable("pt_entity_logs", idsToUpdate, rolledBack)
    }

    fun markSignsRolledBack(idsToUpdate: List<Long>, rolledBack: Boolean) {
        markRolledBackTable("pt_sign_logs", idsToUpdate, rolledBack)
    }

    private fun markRolledBackTable(table: String, idsToUpdate: List<Long>, rolledBack: Boolean) {
        if (idsToUpdate.isEmpty()) return
        db.withTransaction { conn ->
            conn.prepareStatement("UPDATE $table SET rolled_back = ? WHERE id = ?").use { ps ->
                val flag = if (rolledBack) 1 else 0
                for (id in idsToUpdate) {
                    ps.setInt(1, flag)
                    ps.setLong(2, id)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }

    fun purgeBefore(cutoffEpochSec: Long, worldName: String? = null): Int {
        val worldId = worldName?.let { ids.worldId(it) ?: return 0 }
        return db.withTransaction { conn ->
            var deleted = 0
            val tables = listOf(
                "pt_block_logs",
                "pt_container_logs",
                "pt_entity_logs",
                "pt_sign_logs",
                "pt_chat_logs",
                "pt_session_logs"
            )
            for (table in tables) {
                val sql = if (worldId == null) {
                    "DELETE FROM $table WHERE time < ?"
                } else {
                    "DELETE FROM $table WHERE time < ? AND world_id = ?"
                }
                conn.prepareStatement(sql).use { ps: PreparedStatement ->
                    ps.setLong(1, cutoffEpochSec)
                    if (worldId != null) ps.setInt(2, worldId)
                    deleted += ps.executeUpdate()
                }
            }
            deleted
        }
    }
}
