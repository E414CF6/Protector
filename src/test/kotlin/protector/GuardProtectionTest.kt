package protector

import org.bukkit.ExplosionResult
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockState
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Creeper
import org.bukkit.entity.Enderman
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import protector.core.config.GuardSettings
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.scheduler.PluginScheduler
import protector.core.ui.ActionBarService
import protector.modules.guard.WorldProtectionListener
import java.lang.reflect.Proxy
import java.util.UUID

class GuardProtectionTest {

    @Test
    fun `world protection listener blocks TNT, creeper, block explosion, fire spread, and enderman grief`() {
        var cfg = ProtectorConfig(
            guard = GuardSettings(
                enabledWorlds = setOf("world"),
                tntPreventPrime = true,
                tntPreventBlockDamage = true,
                preventCreeperBlockDamage = true,
                preventBedAnchorExplode = true,
                preventFireSpread = true,
                preventBlockBurn = true,
                preventLavaFireSpread = true,
                preventEndermanGrief = true,
            )
        )
        val fakePlugin = allocateUnsafe(Protector::class.java)
        val scheduler = PluginScheduler(null)
        val messages = MessageService(fakePlugin)
        val actionBar = ActionBarService(scheduler, { cfg.actionBarSettings() }, messages)
        val listener = WorldProtectionListener({ cfg }, actionBar) { false }

        val world = proxyWorld("world")
        val nether = proxyWorld("world_nether")
        val block1 = proxyBlock(world, 10, 64, 10, Material.STONE)
        val block2 = proxyBlock(world, 11, 64, 10, Material.DIRT)
        val fireBlock = proxyBlock(world, 12, 64, 10, Material.FIRE)
        val netherBlock = proxyBlock(nether, 10, 64, 10, Material.NETHERRACK)

        // TNT explosion clears blockList in enabled world
        val tntEntity = allocateUnsafe(TNTPrimed::class.java)
        val tntEvent = EntityExplodeEvent(tntEntity, Location(world, 10.0, 64.0, 10.0), mutableListOf(block1, block2), 4.0f, ExplosionResult.DESTROY)
        listener.onEntityExplode(tntEvent)
        assertTrue(tntEvent.blockList().isEmpty(), "TNT blockList should be cleared when tntPreventBlockDamage=true")

        // World not in enabledWorlds is untouched
        val netherTnt = EntityExplodeEvent(tntEntity, Location(nether, 10.0, 64.0, 10.0), mutableListOf(netherBlock), 4.0f, ExplosionResult.DESTROY)
        listener.onEntityExplode(netherTnt)
        assertEquals(1, netherTnt.blockList().size)

        // Creeper explosion clears blockList
        val creeper = allocateUnsafe(Creeper::class.java)
        val creeperEvent = EntityExplodeEvent(creeper, Location(world, 10.0, 64.0, 10.0), mutableListOf(block1), 3.0f, ExplosionResult.DESTROY)
        listener.onEntityExplode(creeperEvent)
        assertTrue(creeperEvent.blockList().isEmpty())

        // BlockExplodeEvent clears blockList
        val blockExplode = BlockExplodeEvent(block1, allocateUnsafe(BlockState::class.java), mutableListOf(block2), 4.0f, ExplosionResult.DESTROY)
        listener.onBlockExplode(blockExplode)
        assertTrue(blockExplode.blockList().isEmpty())

        // Fire spread & burn & lava ignite
        val spreadEvent = BlockSpreadEvent(block1, fireBlock, allocateUnsafe(BlockState::class.java))
        listener.onBlockSpread(spreadEvent)
        assertTrue(spreadEvent.isCancelled)

        val burnEvent = BlockBurnEvent(block1, fireBlock)
        listener.onBlockBurn(burnEvent)
        assertTrue(burnEvent.isCancelled)

        val igniteEvent = BlockIgniteEvent(block1, BlockIgniteEvent.IgniteCause.LAVA, null, null)
        listener.onBlockIgnite(igniteEvent)
        assertTrue(igniteEvent.isCancelled)

        // Enderman grief
        val enderman = allocateUnsafe(Enderman::class.java)
        val airData = allocateUnsafe(BlockData::class.java)
        val endermanEvent = EntityChangeBlockEvent(enderman, block1, airData)
        listener.onEntityChangeBlock(endermanEvent)
        assertTrue(endermanEvent.isCancelled)

        // TNT prime cancellation & bypass
        val normalPlayer = proxyPlayer(UUID.randomUUID(), "Normal", bypass = false)
        val bypassPlayer = proxyPlayer(UUID.randomUUID(), "Admin", bypass = true)

        var normalCancelled = false
        listener.handleTntPrime("world", normalPlayer) { normalCancelled = true }
        assertTrue(normalCancelled)

        var bypassCancelled = false
        listener.handleTntPrime("world", bypassPlayer) { bypassCancelled = true }
        assertFalse(bypassCancelled)

        // Disabling rule dynamically lets TNT pass
        cfg = cfg.copy(guard = cfg.guard.copy(tntPreventBlockDamage = false))
        val allowedTnt = EntityExplodeEvent(tntEntity, Location(world, 10.0, 64.0, 10.0), mutableListOf(block1), 4.0f, ExplosionResult.DESTROY)
        listener.onEntityExplode(allowedTnt)
        assertEquals(1, allowedTnt.blockList().size)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocateUnsafe(clazz: Class<T>): T {
        if (clazz.isInterface) {
            return Proxy.newProxyInstance(clazz.classLoader, arrayOf(clazz)) { _, method, _ ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(clazz)
                    "equals" -> false
                    "toString" -> "Mock(${clazz.simpleName})"
                    else -> when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Double::class.javaPrimitiveType -> 0.0
                        Float::class.javaPrimitiveType -> 0.0f
                        Material::class.java -> Material.AIR
                        else -> null
                    }
                }
            } as T
        }
        val unsafeField = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
        unsafeField.isAccessible = true
        val unsafe = unsafeField.get(null) as sun.misc.Unsafe
        return unsafe.allocateInstance(clazz) as T
    }

    private fun proxyWorld(name: String): World =
        Proxy.newProxyInstance(
            World::class.java.classLoader,
            arrayOf(World::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getName" -> name
                "hashCode" -> name.hashCode()
                "equals" -> false
                else -> null
            }
        } as World

    private fun proxyBlock(world: World, x: Int, y: Int, z: Int, type: Material): Block {
        val loc = Location(world, x.toDouble(), y.toDouble(), z.toDouble())
        return Proxy.newProxyInstance(
            Block::class.java.classLoader,
            arrayOf(Block::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getWorld" -> world
                "getLocation" -> loc
                "getX" -> x
                "getY" -> y
                "getZ" -> z
                "getType" -> type
                else -> null
            }
        } as Block
    }

    private fun proxyPlayer(uuid: UUID, name: String, bypass: Boolean): Player =
        Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getUniqueId" -> uuid
                "getName" -> name
                "hasPermission" -> bypass
                "sendActionBar" -> null
                "hashCode" -> uuid.hashCode()
                "equals" -> false
                else -> null
            }
        } as Player
}
