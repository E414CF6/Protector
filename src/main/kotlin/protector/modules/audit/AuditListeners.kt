package protector.modules.audit

import com.google.gson.Gson
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.DoubleChest
import org.bukkit.block.Sign
import org.bukkit.block.sign.Side
import org.bukkit.entity.Ageable
import org.bukkit.entity.Player
import org.bukkit.entity.TNTPrimed
import org.bukkit.entity.Tameable
import org.bukkit.entity.Villager
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.*
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.*
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.ItemStack
import org.bukkit.material.Colorable
import protector.core.config.ProtectorConfig
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class AuditListeners(
    private val configProvider: () -> ProtectorConfig,
    private val consumer: LogQueueConsumer
) : Listener {
    private val plain = PlainTextComponentSerializer.plainText()
    private val gson = Gson()

    private data class ContainerSnapshot(
        val location: Location,
        val counts: Map<String, Pair<ItemStack, Int>>
    )

    private val openContainers = ConcurrentHashMap<UUID, ContainerSnapshot>()
    internal val tntPrimerByBlockKey = ConcurrentHashMap<String, Pair<UUID?, String>>()

    private fun nowSec(): Long = System.currentTimeMillis() / 1000L

    internal fun blockKey(b: Block): String = "${b.world.name}:${b.x}:${b.y}:${b.z}"

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        val b = event.block
        val p = event.player
        val time = nowSec()

        val state = b.state
        if (cfg.logSigns && state is Sign) {
            for (side in Side.entries) {
                val lines = state.getSide(side).lines().map { plain.serialize(it) }
                if (lines.any { it.isNotEmpty() }) {
                    consumer.enqueue(
                        PendingSignEvent(
                            timeSec = time,
                            actorUuid = p.uniqueId,
                            actorName = p.name,
                            worldName = b.world.name,
                            x = b.x,
                            y = b.y,
                            z = b.z,
                            side = side.name,
                            linesJson = gson.toJson(lines),
                            action = 0
                        )
                    )
                }
            }
        }

        consumer.enqueue(
            PendingBlockEvent(
                timeSec = time,
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = b.type.name,
                oldBlockData = b.blockData.asString,
                newMaterial = Material.AIR.name,
                newBlockData = Material.AIR.createBlockData().asString,
                action = BlockLogAction.BREAK
            )
        )

        if (cfg.logContainers && state is BlockInventoryHolder) {
            for (item in state.inventory.contents) {
                if (item == null || item.type.isAir) continue
                consumer.enqueue(
                    PendingContainerEvent(
                        timeSec = time,
                        actorUuid = p.uniqueId,
                        actorName = p.name,
                        worldName = b.world.name,
                        x = b.x,
                        y = b.y,
                        z = b.z,
                        material = item.type.name,
                        amount = item.amount,
                        itemNbt = encodeItemUnit(item),
                        action = ContainerLogAction.REMOVE
                    )
                )
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        if (event is BlockMultiPlaceEvent) return
        val b = event.blockPlaced
        val prev = event.blockReplacedState
        val p = event.player
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = prev.type.name,
                oldBlockData = prev.blockData.asString,
                newMaterial = b.type.name,
                newBlockData = b.blockData.asString,
                action = BlockLogAction.PLACE
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockMultiPlace(event: BlockMultiPlaceEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        val p = event.player
        val time = nowSec()
        for (prev in event.replacedBlockStates) {
            val b = prev.block
            consumer.enqueue(
                PendingBlockEvent(
                    timeSec = time,
                    actorUuid = p.uniqueId,
                    actorName = p.name,
                    worldName = b.world.name,
                    x = b.x,
                    y = b.y,
                    z = b.z,
                    oldMaterial = prev.type.name,
                    oldBlockData = prev.blockData.asString,
                    newMaterial = b.type.name,
                    newBlockData = b.blockData.asString,
                    action = BlockLogAction.PLACE
                )
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        logEnvironmentBreak(event.block, null, "#fire", BlockLogAction.BURN)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        var actorUuid: UUID? = null
        var actorName = "#${event.entityType.name.lowercase()}"
        val ent = event.entity
        if (ent is TNTPrimed) {
            val source = ent.source
            if (source is Player) {
                actorUuid = source.uniqueId
                actorName = source.name
            } else {
                val originBlock = ent.origin?.block
                val tracked = originBlock?.let { tntPrimerByBlockKey.remove(blockKey(it)) }
                if (tracked != null) {
                    actorUuid = tracked.first
                    actorName = tracked.second
                }
            }
        }
        for (b in event.blockList()) {
            logEnvironmentBreak(b, actorUuid, actorName, BlockLogAction.EXPLODE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        for (b in event.blockList()) {
            logEnvironmentBreak(b, null, "#explosion", BlockLogAction.EXPLODE)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        val to = event.toBlock
        val from = event.block
        if (to.type == from.type) return
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = null,
                actorName = "#${from.type.name.lowercase()}",
                worldName = to.world.name,
                x = to.x,
                y = to.y,
                z = to.z,
                oldMaterial = to.type.name,
                oldBlockData = to.blockData.asString,
                newMaterial = from.type.name,
                newBlockData = from.blockData.asString,
                action = BlockLogAction.FLOW
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logNatural) return
        val p = event.player
        val time = nowSec()
        for (state in event.blocks) {
            val prev = state.location.block
            consumer.enqueue(
                PendingBlockEvent(
                    timeSec = time,
                    actorUuid = p?.uniqueId,
                    actorName = p?.name ?: "#tree",
                    worldName = state.world.name,
                    x = state.x,
                    y = state.y,
                    z = state.z,
                    oldMaterial = prev.type.name,
                    oldBlockData = prev.blockData.asString,
                    newMaterial = state.type.name,
                    newBlockData = state.blockData.asString,
                    action = BlockLogAction.GROW
                )
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLeavesDecay(event: LeavesDecayEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logNatural) return
        logEnvironmentBreak(event.block, null, "#decay", BlockLogAction.DECAY)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFade(event: BlockFadeEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logNatural) return
        val b = event.block
        val ns = event.newState
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = null,
                actorName = "#fade",
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = b.type.name,
                oldBlockData = b.blockData.asString,
                newMaterial = ns.type.name,
                newBlockData = ns.blockData.asString,
                action = BlockLogAction.DECAY
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockForm(event: BlockFormEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logNatural) return
        val b = event.block
        val ns = event.newState
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = null,
                actorName = "#form",
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = b.type.name,
                oldBlockData = b.blockData.asString,
                newMaterial = ns.type.name,
                newBlockData = ns.blockData.asString,
                action = BlockLogAction.FORM
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onBlockFertilize(event: BlockFertilizeEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logNatural) return
        val p = event.player
        val time = nowSec()
        for (state in event.blocks) {
            val prev = state.location.block
            consumer.enqueue(
                PendingBlockEvent(
                    timeSec = time,
                    actorUuid = p?.uniqueId,
                    actorName = p?.name ?: "#bonemeal",
                    worldName = state.world.name,
                    x = state.x,
                    y = state.y,
                    z = state.z,
                    oldMaterial = prev.type.name,
                    oldBlockData = prev.blockData.asString,
                    newMaterial = state.type.name,
                    newBlockData = state.blockData.asString,
                    action = BlockLogAction.FERTILIZE
                )
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        if (event.entity is Player) return
        val b = event.block
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = null,
                actorName = "#${event.entityType.name.lowercase()}",
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = b.type.name,
                oldBlockData = b.blockData.asString,
                newMaterial = event.to.name,
                newBlockData = event.blockData.asString,
                action = if (event.to.isAir) BlockLogAction.BREAK else BlockLogAction.PLACE
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInteractLog(event: PlayerInteractEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logInteractions) return
        if (event.hand != EquipmentSlot.HAND || event.action != Action.RIGHT_CLICK_BLOCK) return
        val b = event.clickedBlock ?: return
        val type = b.type
        val interesting = b.state is BlockInventoryHolder || Tag.DOORS.isTagged(type) ||
            Tag.TRAPDOORS.isTagged(type) || Tag.FENCE_GATES.isTagged(type) ||
            Tag.BUTTONS.isTagged(type) || type == Material.LEVER
        if (!interesting) return

        val p = event.player
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = type.name,
                oldBlockData = b.blockData.asString,
                newMaterial = type.name,
                newBlockData = b.blockData.asString,
                action = BlockLogAction.INTERACT
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logSigns) return
        val lines = event.lines().map { plain.serialize(it) }
        val b = event.block
        consumer.enqueue(
            PendingSignEvent(
                timeSec = nowSec(),
                actorUuid = event.player.uniqueId,
                actorName = event.player.name,
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                side = event.side.name,
                linesJson = gson.toJson(lines),
                action = 1
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logContainers) return
        val player = event.player as? Player ?: return
        val loc = resolveContainerLocation(event.inventory) ?: return
        openContainers[player.uniqueId] = ContainerSnapshot(
            location = loc.clone(),
            counts = summarizeItems(event.inventory.contents)
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logContainers) return
        val player = event.player as? Player ?: return
        val snapshot = openContainers.remove(player.uniqueId) ?: return
        val after = summarizeItems(event.inventory.contents)

        val allKeys = snapshot.counts.keys + after.keys
        val loc = snapshot.location
        val worldName = loc.world?.name ?: return
        val time = nowSec()

        for (key in allKeys) {
            val prevCount = snapshot.counts[key]?.second ?: 0
            val nextCount = after[key]?.second ?: 0
            val delta = nextCount - prevCount
            if (delta == 0) continue
            val sampleItem = (after[key]?.first ?: snapshot.counts[key]?.first) ?: continue
            consumer.enqueue(
                PendingContainerEvent(
                    timeSec = time,
                    actorUuid = player.uniqueId,
                    actorName = player.name,
                    worldName = worldName,
                    x = loc.blockX,
                    y = loc.blockY,
                    z = loc.blockZ,
                    material = sampleItem.type.name,
                    amount = kotlin.math.abs(delta),
                    itemNbt = key,
                    action = if (delta > 0) ContainerLogAction.ADD else ContainerLogAction.REMOVE
                )
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logContainers || !cfg.logHoppers) return
        val item = event.item
        if (item.type.isAir) return
        val time = nowSec()
        val encoded = encodeItemUnit(item)

        val srcLoc = resolveContainerLocation(event.source)
        if (srcLoc != null) {
            consumer.enqueue(
                PendingContainerEvent(
                    timeSec = time,
                    actorUuid = null,
                    actorName = "#hopper",
                    worldName = srcLoc.world.name,
                    x = srcLoc.blockX,
                    y = srcLoc.blockY,
                    z = srcLoc.blockZ,
                    material = item.type.name,
                    amount = item.amount,
                    itemNbt = encoded,
                    action = ContainerLogAction.REMOVE
                )
            )
        }
        val dstLoc = resolveContainerLocation(event.destination)
        if (dstLoc != null) {
            consumer.enqueue(
                PendingContainerEvent(
                    timeSec = time,
                    actorUuid = null,
                    actorName = "#hopper",
                    worldName = dstLoc.world.name,
                    x = dstLoc.blockX,
                    y = dstLoc.blockY,
                    z = dstLoc.blockZ,
                    material = item.type.name,
                    amount = item.amount,
                    itemNbt = encoded,
                    action = ContainerLogAction.ADD
                )
            )
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onEntityDeath(event: EntityDeathEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logEntities) return
        val entity = event.entity
        if (entity is Player) return
        val killer = entity.killer
        val loc = entity.location

        val metaMap = HashMap<String, Any>()
        entity.customName()?.let { metaMap["customName"] = plain.serialize(it) }
        if (entity is Ageable) metaMap["adult"] = entity.isAdult
        if (entity is Colorable) entity.color?.name?.let { metaMap["color"] = it }
        if (entity is Villager) metaMap["profession"] = entity.profession.key().asString()
        if (entity is Tameable && entity.isTamed) entity.ownerUniqueId?.let { metaMap["owner"] = it.toString() }
        entity.lastDamageCause?.cause?.name?.let { metaMap["cause"] = it }

        consumer.enqueue(
            PendingEntityEvent(
                timeSec = nowSec(),
                actorUuid = killer?.uniqueId,
                actorName = killer?.name ?: "#environment",
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                entityType = entity.type.name,
                action = EntityLogAction.KILL,
                metadata = gson.toJson(metaMap)
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logEntities) return
        val p = event.player ?: return
        val loc = event.entity.location
        consumer.enqueue(
            PendingEntityEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                entityType = event.entity.type.name,
                action = EntityLogAction.SPAWN,
                metadata = null
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logEntities) return
        val p = event.remover as? Player
        val loc = event.entity.location
        consumer.enqueue(
            PendingEntityEvent(
                timeSec = nowSec(),
                actorUuid = p?.uniqueId,
                actorName = p?.name ?: "#${event.cause.name.lowercase()}",
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                entityType = event.entity.type.name,
                action = EntityLogAction.KILL,
                metadata = gson.toJson(mapOf("cause" to event.cause.name))
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logEntities) return
        val p = event.player
        val loc = event.rightClicked.location
        consumer.enqueue(
            PendingEntityEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                entityType = event.rightClicked.type.name,
                action = EntityLogAction.INTERACT,
                metadata = gson.toJson(mapOf("slot" to event.slot.name, "item" to event.playerItem.type.name))
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logCommands) return
        val p = event.player
        val loc = p.location
        consumer.enqueue(
            PendingChatEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                isCommand = true,
                message = event.message
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onJoin(event: PlayerJoinEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logSessions) return
        val p = event.player
        val loc = p.location
        consumer.enqueue(
            PendingSessionEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                login = true
            )
        )
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        openContainers.remove(event.player.uniqueId)
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logSessions) return
        val p = event.player
        val loc = p.location
        consumer.enqueue(
            PendingSessionEvent(
                timeSec = nowSec(),
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                login = false
            )
        )
    }

    private fun logEnvironmentBreak(b: Block, actorUuid: UUID?, actorName: String, action: BlockLogAction) {
        consumer.enqueue(
            PendingBlockEvent(
                timeSec = nowSec(),
                actorUuid = actorUuid,
                actorName = actorName,
                worldName = b.world.name,
                x = b.x,
                y = b.y,
                z = b.z,
                oldMaterial = b.type.name,
                oldBlockData = b.blockData.asString,
                newMaterial = Material.AIR.name,
                newBlockData = Material.AIR.createBlockData().asString,
                action = action
            )
        )
    }

    private fun resolveContainerLocation(inventory: Inventory): Location? {
        val holder = inventory.holder
        if (holder is DoubleChest) {
            return (holder.leftSide as? BlockInventoryHolder)?.block?.location ?: holder.location
        }
        if (holder is BlockInventoryHolder) {
            return holder.block.location
        }
        return null
    }

    private fun encodeItemUnit(item: ItemStack): String? = runCatching {
        val unit = item.clone().apply { amount = 1 }
        Base64.getEncoder().encodeToString(unit.serializeAsBytes())
    }.getOrNull()

    private fun summarizeItems(contents: Array<ItemStack?>): Map<String, Pair<ItemStack, Int>> {
        val counts = LinkedHashMap<String, Pair<ItemStack, Int>>()
        for (item in contents) {
            if (item == null || item.type.isAir || item.amount <= 0) continue
            val unit = item.clone().apply { amount = 1 }
            val key = encodeItemUnit(unit) ?: unit.type.name
            val prev = counts[key]
            counts[key] = unit to ((prev?.second ?: 0) + item.amount)
        }
        return counts
    }
}

class PaperChatAuditListener(
    private val configProvider: () -> ProtectorConfig,
    private val consumer: LogQueueConsumer
) : Listener {
    private val plain = PlainTextComponentSerializer.plainText()

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: io.papermc.paper.event.player.AsyncChatEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logChat) return
        val p = event.player
        val loc = p.location
        consumer.enqueue(
            PendingChatEvent(
                timeSec = System.currentTimeMillis() / 1000L,
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                isCommand = false,
                message = plain.serialize(event.message())
            )
        )
    }
}

@Suppress("DEPRECATION")
class LegacyChatAuditListener(
    private val configProvider: () -> ProtectorConfig,
    private val consumer: LogQueueConsumer
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onChat(event: org.bukkit.event.player.AsyncPlayerChatEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logChat) return
        val p = event.player
        val loc = p.location
        consumer.enqueue(
            PendingChatEvent(
                timeSec = System.currentTimeMillis() / 1000L,
                actorUuid = p.uniqueId,
                actorName = p.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                isCommand = false,
                message = event.message
            )
        )
    }
}

class TntPrimeAuditListener(
    private val configProvider: () -> ProtectorConfig,
    private val parent: AuditListeners
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTntPrime(event: org.bukkit.event.block.TNTPrimeEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logBlocks) return
        val entity = event.primingEntity
        val actor = if (entity is Player) entity.uniqueId to entity.name else null to "#${event.cause.name.lowercase()}"
        parent.tntPrimerByBlockKey[parent.blockKey(event.block)] = actor
    }
}

class LecternAuditListener(
    private val configProvider: () -> ProtectorConfig,
    private val consumer: LogQueueConsumer
) : Listener {
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onTakeLecternBook(event: org.bukkit.event.player.PlayerTakeLecternBookEvent) {
        val cfg = configProvider()
        if (!cfg.auditEnabled || !cfg.logContainers) return
        val book = event.book ?: return
        val loc = event.lectern?.location ?: return
        consumer.enqueue(
            PendingContainerEvent(
                timeSec = System.currentTimeMillis() / 1000L,
                actorUuid = event.player.uniqueId,
                actorName = event.player.name,
                worldName = loc.world.name,
                x = loc.blockX,
                y = loc.blockY,
                z = loc.blockZ,
                material = book.type.name,
                amount = book.amount,
                itemNbt = null,
                action = ContainerLogAction.REMOVE
            )
        )
    }
}
