package protector

import org.bukkit.Material
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import protector.core.config.ProtectorConfig
import protector.core.storage.ClaimRepository
import protector.core.storage.DatabaseManager
import protector.modules.claim.BoundingBox
import protector.modules.claim.ClaimFlag
import protector.modules.claim.ClaimManager
import protector.modules.claim.ClaimPermission
import protector.modules.claim.ClaimResult
import java.io.File
import java.nio.file.Path
import java.util.UUID

class ClaimSystemTest {
    private lateinit var db: DatabaseManager
    private lateinit var repo: ClaimRepository
    private lateinit var claimManager: ClaimManager

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        db = DatabaseManager(File(tempDir.toFile(), "test_claims.db"), poolSize = 2)
        repo = ClaimRepository(db)
        val cfg = defaultTestConfig()
        claimManager = ClaimManager(null, repo) { cfg }
        claimManager.loadAll()
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `permission hierarchy follows Owner-Manage-Build-Container-Access`() {
        assertTrue(ClaimPermission.ACCESS.isGrantedBy(ClaimPermission.BUILD))
        assertTrue(ClaimPermission.CONTAINER.isGrantedBy(ClaimPermission.BUILD))
        assertTrue(ClaimPermission.BUILD.isGrantedBy(ClaimPermission.MANAGE))
        assertFalse(ClaimPermission.BUILD.isGrantedBy(ClaimPermission.CONTAINER))
        assertFalse(ClaimPermission.MANAGE.isGrantedBy(ClaimPermission.BUILD))
    }

    @Test
    fun `claim creation, overlap rejection, resize, reload persistence, and deletion refund`() {
        val owner = UUID.randomUUID()
        val stranger = UUID.randomUUID()

        val initialAvailable = claimManager.getAvailableClaimBlocks(owner)
        assertEquals(400, initialAvailable)

        val bounds = BoundingBox.ofCorners("world", 0, -64, 0, 9, 319, 9) // 10x10 = 100 blocks
        val created = claimManager.createClaim(owner, bounds)
        val claim = assertInstanceOf(ClaimResult.Success::class.java, created).claim

        assertEquals(100, claim.bounds.area)
        assertEquals(300, claimManager.getAvailableClaimBlocks(owner))
        assertTrue(claim.hasPermission(owner, ClaimPermission.OWNER))
        assertFalse(claim.hasPermission(stranger, ClaimPermission.ACCESS))

        // Overlapping claim fails
        val overlap = claimManager.createClaim(stranger, BoundingBox.ofCorners("world", 5, -64, 5, 14, 319, 14))
        val fail = assertInstanceOf(ClaimResult.Failure::class.java, overlap)
        assertEquals(ClaimResult.Reason.OVERLAPS_EXISTING, fail.reason)

        // Resize from 10x10 (100) to 15x10 (150)
        val resized = claimManager.resizeClaim(claim, BoundingBox.ofCorners("world", 0, -64, 0, 14, 319, 9))
        assertInstanceOf(ClaimResult.Success::class.java, resized)
        assertEquals(250, claimManager.getAvailableClaimBlocks(owner))

        // Grant container trust & flags
        claimManager.setTrust(claim, stranger.toString(), ClaimPermission.CONTAINER)
        claimManager.setFlag(claim, ClaimFlag.PVP, true)
        claimManager.updateMetadata(claim, "My Base", "Welcome!")

        // Reload from SQLite and verify state survives restart
        val reloaded = ClaimManager(null, repo) { defaultTestConfig() }.apply { loadAll() }
        val persisted = reloaded.getTopLevelClaimAt("world", 5, 5)
        assertNotNull(persisted)
        assertEquals("My Base", persisted!!.name)
        assertEquals("Welcome!", persisted.greeting)
        assertTrue(persisted.flag(ClaimFlag.PVP))
        assertTrue(persisted.hasPermission(stranger, ClaimPermission.CONTAINER))
        assertFalse(persisted.hasPermission(stranger, ClaimPermission.BUILD))

        // Delete & verify refund
        val refund = reloaded.deleteClaim(persisted)
        assertEquals(150, refund)
        assertEquals(400, reloaded.getAvailableClaimBlocks(owner))
        assertNull(reloaded.getTopLevelClaimAt("world", 5, 5))
    }

    @Test
    fun `subclaim inherits parent trust unless inheritNothing is enabled`() {
        val owner = UUID.randomUUID()
        val friend = UUID.randomUUID()
        val parent = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createClaim(owner, BoundingBox.ofCorners("world", 100, -64, 100, 119, 319, 119))
        ).claim
        claimManager.setTrust(parent, friend.toString(), ClaimPermission.BUILD)

        val sub1 = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createSubclaim(parent, BoundingBox.ofCorners("world", 102, 60, 102, 108, 70, 108), inheritNothing = false)
        ).claim
        assertTrue(sub1.hasPermission(friend, ClaimPermission.BUILD))

        val sub2 = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createSubclaim(parent, BoundingBox.ofCorners("world", 110, 60, 110, 116, 70, 116), inheritNothing = true)
        ).claim
        assertFalse(sub2.hasPermission(friend, ClaimPermission.ACCESS))

        // Granting explicitly on restricted subclaim works
        claimManager.setTrust(sub2, friend.toString(), ClaimPermission.CONTAINER)
        assertTrue(sub2.hasPermission(friend, ClaimPermission.CONTAINER))
        assertNotNull(claimManager.getClaimById(sub2.id))
    }

    @Test
    fun `extendClaim, transferClaim, deleteAllPlayerClaims, and cleanupUnusedClaims work`() {
        val owner = UUID.randomUUID()
        val newOwner = UUID.randomUUID()
        val claim = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.createClaim(owner, BoundingBox.ofCorners("world", 0, -64, 0, 9, 319, 9))
        ).claim

        val extended = assertInstanceOf(
            ClaimResult.Success::class.java,
            claimManager.extendClaimInDirection(claim, org.bukkit.block.BlockFace.EAST, 5)
        ).claim
        assertEquals(14, extended.bounds.maxX)
        assertEquals(150, extended.bounds.area)

        claimManager.transferClaim(extended, newOwner)
        assertEquals(newOwner, extended.ownerId)
        assertEquals(1, claimManager.getPlayerClaims(newOwner).size)
        assertEquals(0, claimManager.getPlayerClaims(owner).size)

        val (count, refund) = claimManager.deleteAllPlayerClaims(newOwner)
        assertEquals(1, count)
        assertEquals(150, refund)

        // Cleanup unused claims
        val staleOwner = UUID.randomUUID()
        val data = claimManager.getOrCreatePlayerData(staleOwner)
        data.lastActiveAt = System.currentTimeMillis() - (90L * 86_400_000L)
        repo.savePlayerData(data)

        claimManager.createClaim(staleOwner, BoundingBox.ofCorners("world", 50, -64, 50, 59, 319, 59))
        val cleaned = claimManager.cleanupUnusedClaims()
        assertEquals(1, cleaned)
        assertNull(claimManager.getTopLevelClaimAt("world", 55, 55))
    }

    companion object {
        fun defaultTestConfig(): ProtectorConfig = ProtectorConfig(
            language = "en",
            databaseFile = "test.db",
            databasePoolSize = 2,
            consumerBatchSize = 100,
            consumerFlushIntervalMs = 200L,
            enabledWorlds = emptySet(),
            claimTool = Material.GOLDEN_SHOVEL,
            inspectTool = Material.STICK,
            initialClaimBlocks = 400,
            accrualBlocksPerHour = 120,
            maxAccruedBlocks = 80000,
            minClaimWidth = 5,
            minClaimArea = 25,
            autoClaimRadius = 7,
            abandonRefundRatio = 1.0,
            visualizationDurationSeconds = 30,
            defaultFlags = mapOf(
                ClaimFlag.PVP to false,
                ClaimFlag.EXPLOSIONS to false,
                ClaimFlag.FIRE_SPREAD to false,
                ClaimFlag.MOB_GRIEFING to false,
                ClaimFlag.PUBLIC_ACCESS to false,
                ClaimFlag.PISTON_CROSS_BORDER to false,
                ClaimFlag.ENTRY_NOTIFY to true
            ),
            auditEnabled = true,
            logBlocks = true,
            logContainers = true,
            logEntities = true,
            logSigns = true,
            logChat = true,
            logCommands = true,
            logSessions = true,
            defaultLookupLimit = 50,
            maxRollbackRadius = 100,
            rollbackBlocksPerTick = 1500,
            previewDurationSeconds = 60,
            actionBarEnabled = true,
            actionBarRefreshTicks = 10L,
            denialCooldownMs = 1500L,
            bossBarEnabled = true,
            bossBarModeBanner = true
        )
    }
}
