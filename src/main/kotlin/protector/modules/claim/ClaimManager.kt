package protector.modules.claim

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import protector.core.config.ProtectorConfig
import protector.core.scheduler.ProtectorScheduler
import protector.core.scheduler.TaskHandle
import protector.core.storage.ClaimRepository
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

sealed interface ClaimResult {
    data class Success(val claim: Claim, val refundedBlocks: Int = 0) : ClaimResult
    data class Failure(val reason: Reason, val needed: Int = 0, val available: Int = 0) : ClaimResult

    enum class Reason {
        TOO_SMALL,
        OVERLAPS_EXISTING,
        INSUFFICIENT_BLOCKS,
        OUTSIDE_PARENT,
        NOT_OWNER,
        WORLD_DISABLED
    }
}

class ClaimManager(
    private val scheduler: ProtectorScheduler?,
    private val repository: ClaimRepository,
    private val configProvider: () -> ProtectorConfig
) {
    private data class ChunkKey(val worldName: String, val chunkX: Int, val chunkZ: Int)

    private val claimsById = ConcurrentHashMap<Long, Claim>()
    private val topLevelByChunk = ConcurrentHashMap<ChunkKey, CopyOnWriteArrayList<Claim>>()
    private val playerDataMap = ConcurrentHashMap<UUID, PlayerClaimData>()
    private val dirtyPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val lastAfkPositions = ConcurrentHashMap<UUID, Location>()
    private var accrualTask: TaskHandle? = null
    private var cleanupTask: TaskHandle? = null

    fun loadAll() {
        claimsById.clear()
        topLevelByChunk.clear()
        playerDataMap.clear()

        val loadedClaims = repository.loadAllClaims()
        for (claim in loadedClaims) {
            claimsById[claim.id] = claim
            if (!claim.isSubclaim) {
                indexClaim(claim)
            }
        }
        playerDataMap.putAll(repository.loadAllPlayerData())
    }

    fun startAccrualTask() {
        val sched = scheduler ?: return
        stopAccrualTask()
        accrualTask = sched.runGlobalTimer(6000L, 6000L) {
            val cfg = configProvider()
            if (cfg.accrualBlocksPerHour <= 0) return@runGlobalTimer
            // Runs every 5 minutes (6000 ticks) -> 1/12 of hourly rate
            val increment = maxOf(1, cfg.accrualBlocksPerHour / 12)
            for (player in Bukkit.getOnlinePlayers()) {
                val loc = player.location
                val prev = lastAfkPositions.put(player.uniqueId, loc.clone())
                val moved = prev == null || prev.world?.name != loc.world?.name || prev.distanceSquared(loc) >= 9.0
                if (moved) {
                    val data = getOrCreatePlayerData(player.uniqueId)
                    if (data.accruedBlocks < cfg.maxAccruedBlocks) {
                        data.accruedBlocks = (data.accruedBlocks + increment).coerceAtMost(cfg.maxAccruedBlocks)
                        data.lastActiveAt = System.currentTimeMillis()
                        dirtyPlayers.add(player.uniqueId)
                    }
                }
            }
            sched.runAsync { flushDirtyPlayerData() }
        }

        val cfg = configProvider()
        if (cfg.unusedCleanupEnabled) {
            cleanupTask = sched.runGlobalTimer(36000L, 36000L) {
                cleanupUnusedClaims()
            }
        }
    }

    fun stopAccrualTask() {
        accrualTask?.cancel()
        accrualTask = null
        cleanupTask?.cancel()
        cleanupTask = null
        flushDirtyPlayerData()
    }

    fun flushDirtyPlayerData() {
        if (dirtyPlayers.isEmpty()) return
        val snapshot = ArrayList<PlayerClaimData>()
        val iter = dirtyPlayers.iterator()
        while (iter.hasNext()) {
            val uuid = iter.next()
            iter.remove()
            playerDataMap[uuid]?.let { snapshot.add(it) }
        }
        if (snapshot.isNotEmpty()) {
            repository.savePlayerDataBatch(snapshot)
        }
    }

    fun getOrCreatePlayerData(uuid: UUID): PlayerClaimData =
        playerDataMap.computeIfAbsent(uuid) {
            val created = PlayerClaimData(uuid, configProvider().initialClaimBlocks, 0)
            repository.savePlayerData(created)
            created
        }

    fun getUsedClaimBlocks(uuid: UUID): Int =
        claimsById.values
            .asSequence()
            .filter { !it.isSubclaim && it.ownerId == uuid }
            .sumOf { it.bounds.area }

    fun getAvailableClaimBlocks(uuid: UUID): Int {
        val cfg = configProvider()
        val total = getOrCreatePlayerData(uuid).totalBlocks(cfg.maxAccruedBlocks)
        return (total - getUsedClaimBlocks(uuid)).coerceAtLeast(0)
    }

    fun addBonusBlocks(uuid: UUID, amount: Int) {
        val data = getOrCreatePlayerData(uuid)
        data.bonusBlocks = (data.bonusBlocks + amount).coerceAtLeast(0)
        repository.savePlayerData(data)
    }

    fun setAccruedBlocks(uuid: UUID, amount: Int) {
        val cfg = configProvider()
        val data = getOrCreatePlayerData(uuid)
        data.accruedBlocks = amount.coerceIn(0, cfg.maxAccruedBlocks)
        repository.savePlayerData(data)
    }

    fun extendClaimInDirection(
        claim: Claim,
        facing: BlockFace,
        amount: Int,
        isAdminBypass: Boolean = false
    ): ClaimResult {
        val b = claim.bounds
        val newBounds = when (facing) {
            BlockFace.NORTH -> b.copy(minZ = b.minZ - amount)
            BlockFace.SOUTH -> b.copy(maxZ = b.maxZ + amount)
            BlockFace.WEST -> b.copy(minX = b.minX - amount)
            BlockFace.EAST -> b.copy(maxX = b.maxX + amount)
            else -> return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
        }
        val normalized = BoundingBox.ofCorners(
            newBounds.worldName,
            newBounds.minX, newBounds.minY, newBounds.minZ,
            newBounds.maxX, newBounds.maxY, newBounds.maxZ
        )
        return resizeClaim(claim, normalized, isAdminBypass)
    }

    fun transferClaim(claim: Claim, newOwnerId: UUID?) {
        claim.ownerId = newOwnerId
        for (child in claim.children) {
            child.ownerId = newOwnerId
            repository.updateClaimMetadata(child)
        }
        repository.updateClaimMetadata(claim)
    }

    fun deleteAllPlayerClaims(ownerId: UUID): Pair<Int, Int> {
        val owned = getPlayerClaims(ownerId)
        var totalRefund = 0
        for (c in owned) {
            totalRefund += deleteClaim(c)
        }
        return owned.size to totalRefund
    }

    fun cleanupUnusedClaims(nowMs: Long = System.currentTimeMillis()): Int {
        val cfg = configProvider()
        val cutoffMs = nowMs - (cfg.unusedCleanupInactiveDays.toLong() * 86_400_000L)
        var removed = 0
        for (claim in getAllTopLevelClaims()) {
            val owner = claim.ownerId ?: continue
            if (claim.bounds.area > cfg.unusedCleanupMaxArea) continue
            val data = playerDataMap[owner] ?: continue
            if (data.lastActiveAt < cutoffMs) {
                deleteClaim(claim)
                removed++
            }
        }
        return removed
    }

    fun getClaimById(id: Long): Claim? = claimsById[id]

    fun getAllTopLevelClaims(): List<Claim> =
        claimsById.values.filter { !it.isSubclaim }.sortedBy { it.id }

    fun getPlayerClaims(uuid: UUID): List<Claim> =
        claimsById.values.filter { !it.isSubclaim && it.ownerId == uuid }.sortedBy { it.id }

    fun getTrustedClaims(uuid: UUID): List<Claim> =
        claimsById.values
            .filter { !it.isSubclaim && it.ownerId != uuid && it.effectivePermission(uuid) != null }
            .sortedBy { it.id }

    fun getClaimAt(loc: Location): Claim? {
        val world = loc.world ?: return null
        val top = getTopLevelClaimAt(world.name, loc.blockX, loc.blockZ) ?: return null
        val child = top.children.firstOrNull {
            it.bounds.contains(loc.blockX, loc.blockY, loc.blockZ, checkY = true)
        }
        return child ?: top
    }

    fun getTopLevelClaimAt(worldName: String, x: Int, z: Int): Claim? {
        val key = ChunkKey(worldName, x shr 4, z shr 4)
        val bucket = topLevelByChunk[key] ?: return null
        return bucket.firstOrNull { it.bounds.contains(x, 0, z, checkY = false) }
    }

    fun getClaimsOverlapping(bounds: BoundingBox, excludeClaimId: Long? = null): List<Claim> {
        val minChunkX = bounds.minX shr 4
        val maxChunkX = bounds.maxX shr 4
        val minChunkZ = bounds.minZ shr 4
        val maxChunkZ = bounds.maxZ shr 4

        val seen = HashSet<Long>()
        val result = ArrayList<Claim>()
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val bucket = topLevelByChunk[ChunkKey(bounds.worldName, cx, cz)] ?: continue
                for (claim in bucket) {
                    if (claim.id == excludeClaimId) continue
                    if (seen.add(claim.id) && claim.bounds.overlaps2D(bounds)) {
                        result.add(claim)
                    }
                }
            }
        }
        return result
    }

    fun createClaim(
        ownerId: UUID?,
        bounds: BoundingBox,
        isAdminBypass: Boolean = false,
        name: String? = null
    ): ClaimResult {
        val cfg = configProvider()
        if (!cfg.isWorldEnabled(bounds.worldName)) {
            return ClaimResult.Failure(ClaimResult.Reason.WORLD_DISABLED)
        }
        if (bounds.width < cfg.minClaimWidth || bounds.length < cfg.minClaimWidth || bounds.area < cfg.minClaimArea) {
            if (!isAdminBypass) {
                return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
            }
        }
        if (getClaimsOverlapping(bounds).isNotEmpty()) {
            return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
        }
        if (ownerId != null && !isAdminBypass) {
            val available = getAvailableClaimBlocks(ownerId)
            if (bounds.area > available) {
                return ClaimResult.Failure(ClaimResult.Reason.INSUFFICIENT_BLOCKS, bounds.area, available)
            }
        }

        val claim = repository.insertClaim(
            parentId = null,
            ownerId = ownerId,
            bounds = bounds,
            inheritNothing = false,
            name = name,
            greeting = null,
            defaultFlags = cfg.defaultFlags
        )
        claimsById[claim.id] = claim
        indexClaim(claim)
        return ClaimResult.Success(claim)
    }

    fun createSubclaim(
        parent: Claim,
        bounds: BoundingBox,
        inheritNothing: Boolean = false,
        name: String? = null
    ): ClaimResult {
        if (!parent.bounds.contains2D(bounds)) {
            return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
        }
        if (parent.children.any { it.bounds.overlaps2D(bounds) }) {
            return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
        }

        val sub = repository.insertClaim(
            parentId = parent.id,
            ownerId = parent.ownerId,
            bounds = bounds,
            inheritNothing = inheritNothing,
            name = name ?: "${parent.name} Subclaim",
            greeting = null,
            defaultFlags = emptyMap()
        )
        sub.parent = parent
        parent.children.add(sub)
        claimsById[sub.id] = sub
        return ClaimResult.Success(sub)
    }

    fun resizeClaim(claim: Claim, newBounds: BoundingBox, isAdminBypass: Boolean = false): ClaimResult {
        val cfg = configProvider()
        if (claim.isSubclaim) {
            val parent = claim.parent ?: return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
            if (!parent.bounds.contains2D(newBounds)) {
                return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
            }
            if (parent.children.any { it.id != claim.id && it.bounds.overlaps2D(newBounds) }) {
                return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
            }
            claim.bounds = newBounds
            repository.updateClaimBounds(claim)
            return ClaimResult.Success(claim)
        }

        if (!isAdminBypass && (newBounds.width < cfg.minClaimWidth || newBounds.length < cfg.minClaimWidth || newBounds.area < cfg.minClaimArea)) {
            return ClaimResult.Failure(ClaimResult.Reason.TOO_SMALL)
        }
        if (getClaimsOverlapping(newBounds, excludeClaimId = claim.id).isNotEmpty()) {
            return ClaimResult.Failure(ClaimResult.Reason.OVERLAPS_EXISTING)
        }
        // Ensure all subclaims still fit inside newBounds
        if (claim.children.any { !newBounds.contains2D(it.bounds) }) {
            return ClaimResult.Failure(ClaimResult.Reason.OUTSIDE_PARENT)
        }

        val delta = newBounds.area - claim.bounds.area
        val owner = claim.ownerId
        if (owner != null && delta > 0 && !isAdminBypass) {
            val available = getAvailableClaimBlocks(owner)
            if (delta > available) {
                return ClaimResult.Failure(ClaimResult.Reason.INSUFFICIENT_BLOCKS, delta, available)
            }
        }

        deindexClaim(claim)
        claim.bounds = newBounds
        indexClaim(claim)
        repository.updateClaimBounds(claim)
        return ClaimResult.Success(claim)
    }

    fun deleteClaim(claim: Claim): Int {
        val cfg = configProvider()
        val refund = if (!claim.isSubclaim && !claim.isAdminClaim) {
            (claim.bounds.area * cfg.abandonRefundRatio).toInt()
        } else 0

        if (claim.isSubclaim) {
            claim.parent?.children?.remove(claim)
        } else {
            deindexClaim(claim)
            for (child in claim.children) {
                claimsById.remove(child.id)
            }
        }
        claimsById.remove(claim.id)
        repository.deleteClaim(claim.id)
        return refund
    }

    fun setTrust(claim: Claim, targetKey: String, permission: ClaimPermission) {
        claim.trusts[targetKey] = permission
        repository.upsertTrust(claim.id, targetKey, permission)
    }

    fun removeTrust(claim: Claim, targetKey: String) {
        claim.trusts.remove(targetKey)
        repository.removeTrust(claim.id, targetKey)
    }

    fun setFlag(claim: Claim, flag: ClaimFlag, value: Boolean) {
        claim.flags[flag] = value
        repository.upsertFlag(claim.id, flag, value)
    }

    fun updateMetadata(claim: Claim, name: String, greeting: String?, inheritNothing: Boolean = claim.inheritNothing) {
        claim.name = name
        claim.greeting = greeting?.takeIf { it.isNotBlank() }
        claim.inheritNothing = inheritNothing
        repository.updateClaimMetadata(claim)
    }

    private fun indexClaim(claim: Claim) {
        val minChunkX = claim.bounds.minX shr 4
        val maxChunkX = claim.bounds.maxX shr 4
        val minChunkZ = claim.bounds.minZ shr 4
        val maxChunkZ = claim.bounds.maxZ shr 4
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                topLevelByChunk.computeIfAbsent(ChunkKey(claim.bounds.worldName, cx, cz)) {
                    CopyOnWriteArrayList()
                }.add(claim)
            }
        }
    }

    private fun deindexClaim(claim: Claim) {
        val minChunkX = claim.bounds.minX shr 4
        val maxChunkX = claim.bounds.maxX shr 4
        val minChunkZ = claim.bounds.minZ shr 4
        val maxChunkZ = claim.bounds.maxZ shr 4
        for (cx in minChunkX..maxChunkX) {
            for (cz in minChunkZ..maxChunkZ) {
                val key = ChunkKey(claim.bounds.worldName, cx, cz)
                topLevelByChunk[key]?.remove(claim)
            }
        }
    }
}
