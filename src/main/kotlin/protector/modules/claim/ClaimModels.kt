package protector.modules.claim

import org.bukkit.Bukkit
import org.bukkit.Location
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

enum class ClaimPermission(val displayName: String, val denialKey: String) {
    OWNER("Owner", "deny-manage"),
    MANAGE("Manage", "deny-manage"),
    BUILD("Build", "deny-build"),
    CONTAINER("Container", "deny-container"),
    ACCESS("Access", "deny-access");

    fun isGrantedBy(granted: ClaimPermission?): Boolean =
        granted != null && granted.ordinal <= this.ordinal

    fun nextTrustTier(): ClaimPermission = when (this) {
        ACCESS -> CONTAINER
        CONTAINER -> BUILD
        BUILD -> MANAGE
        MANAGE -> ACCESS
        OWNER -> OWNER
    }

    companion object {
        val TRUSTABLE_TIERS = listOf(ACCESS, CONTAINER, BUILD, MANAGE)

        fun parse(raw: String): ClaimPermission? = when (raw.lowercase()) {
            "access", "a", "at" -> ACCESS
            "container", "containers", "c", "ct" -> CONTAINER
            "build", "b", "tr", "trust" -> BUILD
            "manage", "m", "mt", "permission" -> MANAGE
            else -> null
        }
    }
}

enum class ClaimFlag(val key: String, val label: String) {
    PVP("pvp", "Allow PvP"),
    EXPLOSIONS("explosions", "Allow Explosions"),
    FIRE_SPREAD("fire_spread", "Allow Fire Spread"),
    MOB_GRIEFING("mob_griefing", "Allow Mob Griefing"),
    PUBLIC_ACCESS("public_access", "Public Access"),
    PISTON_CROSS_BORDER("piston_cross_border", "Cross-border Pistons"),
    ENTRY_NOTIFY("entry_notify", "Entry Notification")
}

enum class ShovelMode(val label: String) {
    BASIC("Basic"),
    SUBDIVIDE("Subdivide"),
    ADMIN("Admin")
}

data class BoundingBox(
    val worldName: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int
) {
    val width: Int get() = (maxX - minX) + 1
    val length: Int get() = (maxZ - minZ) + 1
    val area: Int get() = runCatching { Math.multiplyExact(width, length) }.getOrDefault(Int.MAX_VALUE)

    fun contains(x: Int, y: Int, z: Int, checkY: Boolean): Boolean {
        if (x < minX || x > maxX || z < minZ || z > maxZ) return false
        return !checkY || (y in minY..maxY)
    }

    fun overlaps2D(other: BoundingBox): Boolean {
        if (worldName != other.worldName) return false
        return minX <= other.maxX && maxX >= other.minX &&
            minZ <= other.maxZ && maxZ >= other.minZ
    }

    fun contains2D(other: BoundingBox): Boolean {
        if (worldName != other.worldName) return false
        return other.minX >= minX && other.maxX <= maxX &&
            other.minZ >= minZ && other.maxZ <= maxZ
    }

    companion object {
        fun ofCorners(worldName: String, x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int): BoundingBox =
            BoundingBox(
                worldName = worldName,
                minX = minOf(x1, x2),
                minY = minOf(y1, y2),
                minZ = minOf(z1, z2),
                maxX = maxOf(x1, x2),
                maxY = maxOf(y1, y2),
                maxZ = maxOf(z1, z2)
            )
    }
}

class Claim(
    val id: Long,
    var parentId: Long? = null,
    var ownerId: UUID?,
    var bounds: BoundingBox,
    var inheritNothing: Boolean = false,
    var name: String = "Claim #$id",
    var greeting: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var updatedAt: Long = createdAt
) {
    val trusts: ConcurrentHashMap<String, ClaimPermission> = ConcurrentHashMap()
    val flags: ConcurrentHashMap<ClaimFlag, Boolean> = ConcurrentHashMap()
    val children: CopyOnWriteArrayList<Claim> = CopyOnWriteArrayList()

    @Volatile
    var parent: Claim? = null

    val isAdminClaim: Boolean get() = ownerId == null
    val isSubclaim: Boolean get() = parentId != null

    fun ownerName(): String {
        val id = ownerId ?: return "Administrator"
        val shortId = id.toString().substring(0, 8)
        return runCatching { Bukkit.getOfflinePlayer(id).name }.getOrNull() ?: shortId
    }

    fun flag(flag: ClaimFlag, defaults: Map<ClaimFlag, Boolean> = emptyMap()): Boolean =
        flags[flag] ?: parent?.flags?.get(flag) ?: defaults[flag] ?: (flag == ClaimFlag.ENTRY_NOTIFY)

    fun contains(loc: Location): Boolean {
        val world = loc.world ?: return false
        if (world.name != bounds.worldName) return false
        return bounds.contains(loc.blockX, loc.blockY, loc.blockZ, checkY = isSubclaim)
    }

    fun effectivePermission(playerId: UUID): ClaimPermission? {
        if (ownerId == playerId) return ClaimPermission.OWNER
        val direct = trusts[playerId.toString()]
        val publicTrust = trusts[PUBLIC_KEY] ?: if (flags[ClaimFlag.PUBLIC_ACCESS] == true) ClaimPermission.ACCESS else null

        val localBest = listOfNotNull(direct, publicTrust).minByOrNull { it.ordinal }
        if (!isSubclaim || inheritNothing) {
            return localBest
        }
        val parentPerm = parent?.effectivePermission(playerId)
        return listOfNotNull(localBest, parentPerm).minByOrNull { it.ordinal }
    }

    fun hasPermission(playerId: UUID, required: ClaimPermission, adminBypass: Boolean = false): Boolean {
        if (adminBypass) return true
        return required.isGrantedBy(effectivePermission(playerId))
    }

    companion object {
        const val PUBLIC_KEY = "public"
    }
}

data class PlayerClaimData(
    val uuid: UUID,
    var accruedBlocks: Int,
    var bonusBlocks: Int,
    var lastActiveAt: Long = System.currentTimeMillis()
) {
    fun totalBlocks(maxAccrued: Int): Int = accruedBlocks.coerceAtMost(maxAccrued) + bonusBlocks
}
