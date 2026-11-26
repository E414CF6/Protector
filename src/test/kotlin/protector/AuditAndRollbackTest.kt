package protector

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import protector.core.config.ProtectorConfig
import protector.core.storage.DatabaseManager
import protector.modules.audit.*
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
        )
        consumer = LogQueueConsumer(repo, { cfg }, Logger.getLogger("Test"))
    }

    @AfterEach
    fun tearDown() {
        db.close()
    }

    @Test
    fun `duration and range parser handles compound tokens and ranges`() {
        assertEquals(60L, RollbackService.parseDurationSeconds("1m"))
        assertEquals(3600L, RollbackService.parseDurationSeconds("1h"))
        assertEquals(5400L, RollbackService.parseDurationSeconds("1h30m"))
        assertEquals(86400L, RollbackService.parseDurationSeconds("1d"))
        assertEquals(2_592_000L, RollbackService.parseDurationSeconds("1mo"))

        val now = 1_000_000L
        val (since, until) = AuditFilterParser.parseTimeRange("2h-30m", now)
        assertEquals(now - 7200L, since)
        assertEquals(now - 1800L, until)
    }

    @Test
    fun `filter parser supports multi-user, exclude-user, global radius, action signs, and hash flags`() {
        val filter = AuditFilterParser.parseArgs(
            args = arrayOf("u:Alice,Bob", "-u:Bot", "t:1h", "r:#global", "a:+block", "i:DIAMOND_ORE", "#preview", "#count"),
            defaultWorld = "world",
            centerX = 0,
            centerY = 64,
            centerZ = 0,
            maxRadius = 100,
            nowSec = 10_000L,
        )
        assertEquals(setOf("Alice", "Bob"), filter.effectiveUsers())
        assertEquals(setOf("Bot"), filter.excludedUsers)
        assertTrue(filter.globalWorld)
        assertTrue(filter.unboundedRadius)
        assertEquals(LookupActionFilter.BLOCK, filter.actionFilter)
        assertEquals(BlockLogAction.PLACE, filter.blockActionFilter)
        assertEquals(setOf("DIAMOND_ORE"), filter.includeMaterials)
        assertTrue(filter.previewRequested)
        assertTrue(filter.countOnly)
    }

    @Test
    fun `queue consumer flushes block and container events and supports rollback flag, global filter, and purge`() {
        val actor = UUID.randomUUID()
        val bot = UUID.randomUUID()
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
            PendingBlockEvent(
                timeSec = now - 100,
                actorUuid = bot,
                actorName = "Bot",
                worldName = "world_nether",
                x = 500,
                y = 70,
                z = 500,
                oldMaterial = "NETHERRACK",
                oldBlockData = "minecraft:netherrack",
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
        assertEquals(3, flushed)

        val atBlock = repo.queryBlockAt("world", 10, 64, 20)
        assertEquals(1, atBlock.size)
        assertEquals("Alice", atBlock[0].actorName)
        assertEquals("STONE", atBlock[0].oldMaterial)
        assertFalse(atBlock[0].rolledBack)

        val globalExceptBot = repo.queryBlockLogs(
            LookupFilter(
                worldName = "world",
                centerX = 0,
                centerY = 64,
                centerZ = 0,
                sinceEpochSec = now - 3600,
                excludedUsers = setOf("Bot"),
                globalWorld = true,
                unboundedRadius = true,
            )
        )
        assertEquals(1, globalExceptBot.size)
        assertEquals("Alice", globalExceptBot[0].actorName)

        val atChest = repo.queryContainerAt("world", 12, 64, 22)
        assertEquals(1, atChest.size)
        assertEquals("DIAMOND", atChest[0].material)
        assertEquals(8, atChest[0].amount)

        repo.markBlocksRolledBack(globalExceptBot.map { it.id }, true)
        assertTrue(repo.queryBlockAt("world", 10, 64, 20)[0].rolledBack)

        val purged = repo.purgeBefore(now - 90)
        assertEquals(2, purged)
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

        val chats = repo.queryChatLogs(now - 3600, "Bob", commandsOnly = true, textFilter = "status")
        assertEquals(1, chats.size)
        assertEquals("/pr status", chats[0].message)

        val sessions = repo.querySessionLogs(now - 3600, "Bob")
        assertEquals(1, sessions.size)
        assertTrue(sessions[0].login)
    }
}
