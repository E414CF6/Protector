package protector.core.storage

import protector.modules.claim.BoundingBox
import protector.modules.claim.Claim
import protector.modules.claim.ClaimFlag
import protector.modules.claim.ClaimPermission
import protector.modules.claim.PlayerClaimData
import java.sql.Statement
import java.util.UUID

class ClaimRepository(private val db: DatabaseManager) {

    fun loadAllClaims(): List<Claim> = db.withConnection { conn ->
        val claimsById = LinkedHashMap<Long, Claim>()

        conn.createStatement().use { st ->
            st.executeQuery("SELECT * FROM pt_claims ORDER BY parent_id NULLS FIRST, id ASC").use { rs ->
                while (rs.next()) {
                    val id = rs.getLong("id")
                    val parentObj = rs.getObject("parent_id")
                    val parentId = (parentObj as? Number)?.toLong()
                    val ownerUuidStr = rs.getString("owner_uuid")
                    val ownerId = ownerUuidStr?.let { UUID.fromString(it) }
                    val bounds = BoundingBox(
                        worldName = rs.getString("world_name"),
                        minX = rs.getInt("min_x"),
                        minY = rs.getInt("min_y"),
                        minZ = rs.getInt("min_z"),
                        maxX = rs.getInt("max_x"),
                        maxY = rs.getInt("max_y"),
                        maxZ = rs.getInt("max_z")
                    )
                    val claim = Claim(
                        id = id,
                        parentId = parentId,
                        ownerId = ownerId,
                        bounds = bounds,
                        inheritNothing = rs.getInt("inherit_nothing") != 0,
                        name = rs.getString("name"),
                        greeting = rs.getString("greeting"),
                        createdAt = rs.getLong("created_at"),
                        updatedAt = rs.getLong("updated_at")
                    )
                    claimsById[id] = claim
                }
            }

            st.executeQuery("SELECT claim_id, target_key, level FROM pt_claim_trusts").use { rs ->
                while (rs.next()) {
                    val claim = claimsById[rs.getLong("claim_id")] ?: continue
                    val targetKey = rs.getString("target_key")
                    val perm = runCatching { ClaimPermission.valueOf(rs.getString("level")) }.getOrNull() ?: continue
                    claim.trusts[targetKey] = perm
                }
            }

            st.executeQuery("SELECT claim_id, flag_key, value FROM pt_claim_flags").use { rs ->
                while (rs.next()) {
                    val claim = claimsById[rs.getLong("claim_id")] ?: continue
                    val flagKey = rs.getString("flag_key")
                    val flag = ClaimFlag.entries.firstOrNull { it.key == flagKey } ?: continue
                    claim.flags[flag] = rs.getInt("value") != 0
                }
            }
        }

        for (claim in claimsById.values) {
            val pid = claim.parentId ?: continue
            val parent = claimsById[pid]
            if (parent != null) {
                claim.parent = parent
                parent.children.add(claim)
            }
        }

        claimsById.values.toList()
    }

    fun insertClaim(
        parentId: Long?,
        ownerId: UUID?,
        bounds: BoundingBox,
        inheritNothing: Boolean,
        name: String?,
        greeting: String?,
        defaultFlags: Map<ClaimFlag, Boolean>
    ): Claim = db.withTransaction { conn ->
        val now = System.currentTimeMillis()
        val id = conn.prepareStatement(
            """
            INSERT INTO pt_claims(
                parent_id, owner_uuid, world_name, min_x, min_y, min_z, max_x, max_y, max_z,
                inherit_nothing, name, greeting, created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            Statement.RETURN_GENERATED_KEYS
        ).use { ps ->
            if (parentId == null) ps.setNull(1, java.sql.Types.INTEGER) else ps.setLong(1, parentId)
            if (ownerId == null) ps.setNull(2, java.sql.Types.VARCHAR) else ps.setString(2, ownerId.toString())
            ps.setString(3, bounds.worldName)
            ps.setInt(4, bounds.minX)
            ps.setInt(5, bounds.minY)
            ps.setInt(6, bounds.minZ)
            ps.setInt(7, bounds.maxX)
            ps.setInt(8, bounds.maxY)
            ps.setInt(9, bounds.maxZ)
            ps.setInt(10, if (inheritNothing) 1 else 0)
            ps.setString(11, name ?: "Claim")
            if (greeting == null) ps.setNull(12, java.sql.Types.VARCHAR) else ps.setString(12, greeting)
            ps.setLong(13, now)
            ps.setLong(14, now)
            ps.executeUpdate()
            ps.generatedKeys.use { rs ->
                rs.next()
                rs.getLong(1)
            }
        }

        val finalName = name ?: "Claim #$id"
        if (name == null) {
            conn.prepareStatement("UPDATE pt_claims SET name = ? WHERE id = ?").use { ps ->
                ps.setString(1, finalName)
                ps.setLong(2, id)
                ps.executeUpdate()
            }
        }

        val claim = Claim(
            id = id,
            parentId = parentId,
            ownerId = ownerId,
            bounds = bounds,
            inheritNothing = inheritNothing,
            name = finalName,
            greeting = greeting,
            createdAt = now,
            updatedAt = now
        )

        if (defaultFlags.isNotEmpty()) {
            conn.prepareStatement("INSERT INTO pt_claim_flags(claim_id, flag_key, value) VALUES (?, ?, ?)").use { ps ->
                for ((flag, value) in defaultFlags) {
                    ps.setLong(1, id)
                    ps.setString(2, flag.key)
                    ps.setInt(3, if (value) 1 else 0)
                    ps.addBatch()
                    claim.flags[flag] = value
                }
                ps.executeBatch()
            }
        }

        claim
    }

    fun updateClaimBounds(claim: Claim) = db.withConnection { conn ->
        claim.updatedAt = System.currentTimeMillis()
        conn.prepareStatement(
            "UPDATE pt_claims SET min_x = ?, min_y = ?, min_z = ?, max_x = ?, max_y = ?, max_z = ?, updated_at = ? WHERE id = ?"
        ).use { ps ->
            ps.setInt(1, claim.bounds.minX)
            ps.setInt(2, claim.bounds.minY)
            ps.setInt(3, claim.bounds.minZ)
            ps.setInt(4, claim.bounds.maxX)
            ps.setInt(5, claim.bounds.maxY)
            ps.setInt(6, claim.bounds.maxZ)
            ps.setLong(7, claim.updatedAt)
            ps.setLong(8, claim.id)
            ps.executeUpdate()
        }
    }

    fun updateClaimMetadata(claim: Claim) = db.withConnection { conn ->
        claim.updatedAt = System.currentTimeMillis()
        conn.prepareStatement(
            "UPDATE pt_claims SET owner_uuid = ?, name = ?, greeting = ?, inherit_nothing = ?, updated_at = ? WHERE id = ?"
        ).use { ps ->
            if (claim.ownerId == null) ps.setNull(1, java.sql.Types.VARCHAR) else ps.setString(1, claim.ownerId.toString())
            ps.setString(2, claim.name)
            if (claim.greeting == null) ps.setNull(3, java.sql.Types.VARCHAR) else ps.setString(3, claim.greeting)
            ps.setInt(4, if (claim.inheritNothing) 1 else 0)
            ps.setLong(5, claim.updatedAt)
            ps.setLong(6, claim.id)
            ps.executeUpdate()
        }
    }

    fun upsertTrust(claimId: Long, targetKey: String, level: ClaimPermission) = db.withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO pt_claim_trusts(claim_id, target_key, level) VALUES (?, ?, ?) ON CONFLICT(claim_id, target_key) DO UPDATE SET level = excluded.level"
        ).use { ps ->
            ps.setLong(1, claimId)
            ps.setString(2, targetKey)
            ps.setString(3, level.name)
            ps.executeUpdate()
        }
    }

    fun removeTrust(claimId: Long, targetKey: String) = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM pt_claim_trusts WHERE claim_id = ? AND target_key = ?").use { ps ->
            ps.setLong(1, claimId)
            ps.setString(2, targetKey)
            ps.executeUpdate()
        }
    }

    fun upsertFlag(claimId: Long, flag: ClaimFlag, value: Boolean) = db.withConnection { conn ->
        conn.prepareStatement(
            "INSERT INTO pt_claim_flags(claim_id, flag_key, value) VALUES (?, ?, ?) ON CONFLICT(claim_id, flag_key) DO UPDATE SET value = excluded.value"
        ).use { ps ->
            ps.setLong(1, claimId)
            ps.setString(2, flag.key)
            ps.setInt(3, if (value) 1 else 0)
            ps.executeUpdate()
        }
    }

    fun deleteClaim(claimId: Long) = db.withConnection { conn ->
        conn.prepareStatement("DELETE FROM pt_claims WHERE id = ?").use { ps ->
            ps.setLong(1, claimId)
            ps.executeUpdate()
        }
    }

    fun loadAllPlayerData(): Map<UUID, PlayerClaimData> = db.withConnection { conn ->
        val result = HashMap<UUID, PlayerClaimData>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT uuid, accrued_blocks, bonus_blocks, last_active FROM pt_player_claim_data").use { rs ->
                while (rs.next()) {
                    val uuid = UUID.fromString(rs.getString("uuid"))
                    result[uuid] = PlayerClaimData(
                        uuid = uuid,
                        accruedBlocks = rs.getInt("accrued_blocks"),
                        bonusBlocks = rs.getInt("bonus_blocks"),
                        lastActiveAt = rs.getLong("last_active")
                    )
                }
            }
        }
        result
    }

    fun savePlayerData(data: PlayerClaimData) = db.withConnection { conn ->
        conn.prepareStatement(
            """
            INSERT INTO pt_player_claim_data(uuid, accrued_blocks, bonus_blocks, last_active)
            VALUES (?, ?, ?, ?)
            ON CONFLICT(uuid) DO UPDATE SET
                accrued_blocks = excluded.accrued_blocks,
                bonus_blocks = excluded.bonus_blocks,
                last_active = excluded.last_active
            """.trimIndent()
        ).use { ps ->
            ps.setString(1, data.uuid.toString())
            ps.setInt(2, data.accruedBlocks)
            ps.setInt(3, data.bonusBlocks)
            ps.setLong(4, data.lastActiveAt)
            ps.executeUpdate()
        }
    }

    fun savePlayerDataBatch(items: Collection<PlayerClaimData>) {
        if (items.isEmpty()) return
        db.withTransaction { conn ->
            conn.prepareStatement(
                """
                INSERT INTO pt_player_claim_data(uuid, accrued_blocks, bonus_blocks, last_active)
                VALUES (?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    accrued_blocks = excluded.accrued_blocks,
                    bonus_blocks = excluded.bonus_blocks,
                    last_active = excluded.last_active
                """.trimIndent()
            ).use { ps ->
                for (data in items) {
                    ps.setString(1, data.uuid.toString())
                    ps.setInt(2, data.accruedBlocks)
                    ps.setInt(3, data.bonusBlocks)
                    ps.setLong(4, data.lastActiveAt)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
        }
    }
}
