package protector

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import protector.core.config.ProtectorConfig
import protector.core.storage.AuditLogRepository
import protector.core.storage.BlockLogAction
import protector.core.storage.ContainerLogAction
import protector.core.storage.DatabaseManager
import protector.core.storage.EntityLogAction
import protector.core.storage.IdMappingCache
import protector.core.storage.LookupFilter
import protector.core.storage.PendingBlockEvent
import protector.core.storage.PendingChatEvent
import protector.core.storage.PendingContainerEvent
import protector.core.storage.PendingEntityEvent
import protector.core.storage.PendingSessionEvent
import protector.core.storage.PendingSignEvent
import protector.modules.audit.LogQueueConsumer
import protector.modules.audit.RollbackService
import protector.modules.claim.ClaimFlag
import java.io.File
import java.nio.file.Path
import java.util.UUID
import java.util.logging.Logger

class AuditAndRollbackTest {
    private lateinit var db: DatabaseManager
    private lateinit var ids: IdMappingCache
    private lateinit var repo: AuditLogRepository
    private lateinit var consumer: LogQueueConsumer

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun setUp() {
        db = DatabaseManager(File(tempDir.toFile(), "test_audit.db"), poolSize = 2)
        ids = IdMappingCache(db).apply { warmUp() }
        repo = AuditLogRepository(db, ids)
        val cfg = ProtectorConfig(
            language = "en",
            databaseFile = "test_audit.db",
            databasePoolSize = 2,
            consumerBatchSize = 100,
            consumerFlushIntervalMs = 200L,
            enabledWorlds = emptySet(),
            claimTool = org.bukkit.Material.GOLDEN_SHOVEL,
            inspectTool = org.bukkit.Material.STICK,
            initialClaimBlocks = 400,
            accrualBlocksPerHour = 120,
            maxAccruedBlocks = 80000,
            minClaimWidth = 5,
            minClaimArea = 25,
            autoClaimRadius = 7,
            abandonRefundRatio = 1.0,
            visualizationDurationSeconds = 30,
            defaultFlags = mapOf(ClaimFlag.ENTRY_NOTIFY to true),
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
        consumer = LogQueueConsumer(repo, { cfg }, Logger.getLogger("Test"))
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `duration parser handles compound tokens`() {
        assertEquals(60L, RollbackService.parseDurationSeconds("1m"))
        assertEquals(3600L, RollbackService.parseDurationSeconds("1h"))
        assertEquals(5400L, RollbackService.parseDurationSeconds("1h30m"))
        assertEquals(86400L, RollbackService.parseDurationSeconds("1d"))
    }

    @Test
    fun `queue consumer flushes block and container events and supports rollback flag and purge`() {
        val actor = UUID.randomUUID()
        val now = System.currentTimeMillis() / 1000L

        consumer.enqueue(
            PendingBlockEvent(
                timeSec = now - 120,
                actorUuid = actor,
                actorName = "Alice",
                worldName = "world",
                x = 10,
                y = 64,
                z = 20,
                oldMaterial = "STONE",
                oldBlockData = "minecraft:stone",
                newMaterial = "AIR",
                newBlockData = "minecraft:air",
                action = BlockLogAction.BREAK
            )
        )
        consumer.enqueue(
            PendingContainerEvent(
                timeSec = now - 60,
                actorUuid = actor,
                actorName = "Alice",
                worldName = "world",
                x = 12,
                y = 64,
                z = 22,
                material = "DIAMOND",
                amount = 8,
                itemNbt = null,
                action = ContainerLogAction.REMOVE
            )
        )

        val flushed = consumer.flushNow()
        assertEquals(2, flushed)

        val atBlock = repo.queryBlockAt("world", 10, 64, 20)
        assertEquals(1, atBlock.size)
        assertEquals("Alice", atBlock[0].actorName)
        assertEquals("STONE", atBlock[0].oldMaterial)
        assertFalse(atBlock[0].rolledBack)

        val atChest = repo.queryContainerAt("world", 12, 64, 22)
        assertEquals(1, atChest.size)
        assertEquals("DIAMOND", atChest[0].material)
        assertEquals(8, atChest[0].amount)

        // Spatial lookup & rollback marking
        val areaLogs = repo.queryBlockLogs(
            LookupFilter(
                worldName = "world",
                centerX = 11,
                centerY = 64,
                centerZ = 21,
                radius = 5,
                sinceEpochSec = now - 3600,
                userName = "Alice"
            )
        )
        assertEquals(1, areaLogs.size)
        repo.markBlocksRolledBack(areaLogs.map { it.id }, true)

        val updated = repo.queryBlockAt("world", 10, 64, 20)
        assertTrue(updated[0].rolledBack)

        // Purge older than 90 seconds -> deletes the -120s block event, keeps -60s container event
        val purged = repo.purgeBefore(now - 90)
        assertEquals(1, purged)
        assertTrue(repo.queryBlockAt("world", 10, 64, 20).isEmpty())
        assertEquals(1, repo.queryContainerAt("world", 12, 64, 22).size)
    }

    @Test
    fun `sign, entity, chat, and session logs are stored, queried, and rolled back`() {
        val actor = UUID.randomUUID()
        val now = System.currentTimeMillis() / 1000L

        consumer.enqueue(
            PendingSignEvent(
                timeSec = now - 30,
                actorUuid = actor,
                actorName = "Bob",
                worldName = "world",
                x = 5,
                y = 65,
                z = 5,
                side = "FRONT",
                linesJson = "[\"Line 1\",\"Line 2\",\"\",\"\"]",
                action = 0
            )
        )
        consumer.enqueue(
            PendingEntityEvent(
                timeSec = now - 20,
                actorUuid = actor,
                actorName = "Bob",
                worldName = "world",
                x = 6,
                y = 65,
                z = 6,
                entityType = "SHEEP",
                action = EntityLogAction.KILL,
                metadata = "{\"color\":\"BLUE\",\"adult\":true}"
            )
        )
        consumer.enqueue(
            PendingChatEvent(
                timeSec = now - 10,
                actorUuid = actor,
                actorName = "Bob",
                worldName = "world",
                x = 6,
                y = 65,
                z = 6,
                isCommand = true,
                message = "/pr status"
            )
        )
        consumer.enqueue(
            PendingSessionEvent(
                timeSec = now - 5,
                actorUuid = actor,
                actorName = "Bob",
                worldName = "world",
                x = 6,
                y = 65,
                z = 6,
                login = true
            )
        )

        assertEquals(4, consumer.flushNow())

        val signs = repo.querySignAt("world", 5, 65, 5)
        assertEquals(1, signs.size)
        assertEquals("FRONT", signs[0].side)

        val filter = LookupFilter("world", 6, 65, 6, radius = 5, sinceEpochSec = now - 3600)
        val entities = repo.queryEntityLogs(filter)
        assertEquals(1, entities.size)
        assertEquals("SHEEP", entities[0].entityType)
        repo.markEntitiesRolledBack(entities.map { it.id }, true)
        assertTrue(repo.queryEntityLogs(filter)[0].rolledBack)

        val chats = repo.queryChatLogs(now - 3600, "Bob", commandsOnly = true)
        assertEquals(1, chats.size)
        assertEquals("/pr status", chats[0].message)

        val sessions = repo.querySessionLogs(now - 3600, "Bob")
        assertEquals(1, sessions.size)
        assertTrue(sessions[0].login)
    }
}
