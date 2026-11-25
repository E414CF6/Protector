package protector.modules.claim

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.block.data.type.Dispenser
import org.bukkit.entity.Animals
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Hanging
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.entity.Vehicle
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDispenseEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.PlayerLeashEntityEvent
import org.bukkit.event.entity.PotionSplashEvent
import org.bukkit.event.hanging.HangingBreakByEntityEvent
import org.bukkit.event.hanging.HangingPlaceEvent
import org.bukkit.event.inventory.InventoryMoveItemEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.player.PlayerArmorStandManipulateEvent
import org.bukkit.event.player.PlayerBedEnterEvent
import org.bukkit.event.player.PlayerBucketEmptyEvent
import org.bukkit.event.player.PlayerBucketFillEvent
import org.bukkit.event.player.PlayerFishEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerPortalEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerTakeLecternBookEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.world.PortalCreateEvent
import org.bukkit.event.world.StructureGrowEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.EquipmentSlot
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.scheduler.ProtectorScheduler
import protector.core.ui.ActionBarHudService
import protector.core.ui.BossBarService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProtectionListener(
    private val scheduler: ProtectorScheduler,
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val toolService: ClaimToolService,
    private val visualizer: BoundaryVisualizer,
    private val actionBar: ActionBarHudService,
    private val bossBar: BossBarService
) : Listener {

    private val portalReturnLocations = ConcurrentHashMap<UUID, Location>()

    private fun isBypassing(player: Player): Boolean {
        val state = actionBar.state(player.uniqueId)
        return state.adminBypass && player.hasPermission("protector.admin")
    }

    private fun checkPermission(player: Player, loc: Location, required: ClaimPermission): Boolean {
        if (!configProvider().isWorldEnabled(loc.world?.name ?: return true)) return true
        val claim = claimManager.getClaimAt(loc) ?: return true
        if (claim.hasPermission(player.uniqueId, required, isBypassing(player))) {
            return true
        }
        actionBar.sendDenial(player, required.denialKey, mapOf("owner" to claim.ownerName()))
        return false
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBreak(event: BlockBreakEvent) {
        if (!checkPermission(event.player, event.block.location, ClaimPermission.BUILD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockPlace(event: BlockPlaceEvent) {
        if (!checkPermission(event.player, event.block.location, ClaimPermission.BUILD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        if (!checkPermission(event.player, event.block.location, ClaimPermission.BUILD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketEmpty(event: PlayerBucketEmptyEvent) {
        if (!checkPermission(event.player, event.block.location, ClaimPermission.BUILD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBucketFill(event: PlayerBucketFillEvent) {
        if (!checkPermission(event.player, event.block.location, ClaimPermission.BUILD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPlayerInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        val cfg = configProvider()
        val heldType = player.inventory.itemInMainHand.type

        val state = actionBar.state(player.uniqueId)
        if (!state.inspectMode && event.action == Action.RIGHT_CLICK_BLOCK) {
            val clickedBlock = event.clickedBlock
            if (clickedBlock != null && heldType == cfg.claimTool) {
                event.isCancelled = true
                toolService.handleShovelRightClick(player, clickedBlock.location)
                return
            }
            if (clickedBlock != null && heldType == cfg.inspectTool) {
                event.isCancelled = true
                toolService.handleInspectStick(player, clickedBlock.location)
                return
            }
        }

        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) return
        val block = event.clickedBlock ?: return

        if (event.action == Action.PHYSICAL) {
            if (block.type == Material.FARMLAND) {
                if (!checkPermission(player, block.location, ClaimPermission.BUILD)) {
                    event.isCancelled = true
                }
                return
            }
            if (!checkPermission(player, block.location, ClaimPermission.ACCESS)) {
                event.isCancelled = true
            }
            return
        }

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val required = classifyBlockInteraction(block) ?: return
        if (!checkPermission(player, block.location, required)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBedEnter(event: PlayerBedEnterEvent) {
        if (!checkPermission(event.player, event.bed.location, ClaimPermission.ACCESS)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTakeLecternBook(event: PlayerTakeLecternBookEvent) {
        val loc = event.lectern?.location ?: return
        if (!checkPermission(event.player, loc, ClaimPermission.CONTAINER)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryOpen(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val holder = event.inventory.holder as? BlockInventoryHolder ?: return
        if (!checkPermission(player, holder.block.location, ClaimPermission.CONTAINER)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryMoveItem(event: InventoryMoveItemEvent) {
        val srcLoc = (event.source.holder as? BlockInventoryHolder)?.block?.location ?: return
        val dstLoc = (event.destination.holder as? BlockInventoryHolder)?.block?.location ?: return
        val srcClaim = claimManager.getTopLevelClaimAt(srcLoc.world.name, srcLoc.blockX, srcLoc.blockZ) ?: return
        val dstClaim = claimManager.getTopLevelClaimAt(dstLoc.world.name, dstLoc.blockX, dstLoc.blockZ)
        // Prevent hoppers from pulling items out of a claim into wilderness or another owner's claim
        if (dstClaim == null || dstClaim.id != srcClaim.id) {
            if (dstClaim?.ownerId != srcClaim.ownerId || srcClaim.ownerId == null) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDispense(event: BlockDispenseEvent) {
        val fromBlock = event.block
        if (!configProvider().isWorldEnabled(fromBlock.world.name)) return
        val dispenser = fromBlock.blockData as? Dispenser ?: return
        val toBlock = fromBlock.getRelative(dispenser.facing)
        val fromClaim = claimManager.getTopLevelClaimAt(fromBlock.world.name, fromBlock.x, fromBlock.z)
        val toClaim = claimManager.getTopLevelClaimAt(toBlock.world.name, toBlock.x, toBlock.z)
        if (toClaim != null && toClaim.id != fromClaim?.id) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onStructureGrow(event: StructureGrowEvent) {
        val cfg = configProvider()
        if (!cfg.limitTreeGrowth || !cfg.isWorldEnabled(event.world.name)) return
        val sourceClaim = claimManager.getTopLevelClaimAt(event.world.name, event.location.blockX, event.location.blockZ)
        val placer = event.player

        event.blocks.removeIf { blockState ->
            val targetClaim = claimManager.getTopLevelClaimAt(event.world.name, blockState.x, blockState.z)
            if (targetClaim == null || targetClaim.id == sourceClaim?.id) {
                false
            } else {
                placer == null || !targetClaim.hasPermission(placer.uniqueId, ClaimPermission.BUILD, isBypassing(placer))
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPortalCreate(event: PortalCreateEvent) {
        val cfg = configProvider()
        if (!cfg.isWorldEnabled(event.world.name)) return
        val player = event.entity as? Player
        if (player == null && !cfg.preventNonPlayerPortals) return

        for (blockState in event.blocks) {
            val claim = claimManager.getClaimAt(blockState.location) ?: continue
            if (player == null || !claim.hasPermission(player.uniqueId, ClaimPermission.BUILD, isBypassing(player))) {
                if (player != null) {
                    actionBar.sendDenial(player, "deny-build", mapOf("owner" to claim.ownerName()))
                }
                event.isCancelled = true
                return
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerPortal(event: PlayerPortalEvent) {
        val player = event.player
        val from = event.from.clone()
        val to = event.to
        val destClaim = claimManager.getClaimAt(to) ?: return
        if (destClaim.hasPermission(player.uniqueId, ClaimPermission.ACCESS, isBypassing(player))) return

        portalReturnLocations[player.uniqueId] = from
        // Check in 15 seconds if player is trapped inside a blocked portal
        scheduler.runForEntityDelayed(player, 300L) {
            val returnLoc = portalReturnLocations.remove(player.uniqueId) ?: return@runForEntityDelayed
            if (!player.isOnline) return@runForEntityDelayed
            val blockType = player.location.block.type
            if (blockType == Material.NETHER_PORTAL || player.portalCooldown > 0) {
                val stillInClaim = claimManager.getClaimAt(player.location)
                if (stillInClaim != null && !stillInClaim.hasPermission(player.uniqueId, ClaimPermission.ACCESS, isBypassing(player))) {
                    player.teleport(returnLoc)
                    messages.send(player, "portal-trap-rescued")
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPlayerJoin(event: PlayerJoinEvent) {
        val player = event.player
        if (player.location.block.type == Material.NETHER_PORTAL) {
            val claim = claimManager.getClaimAt(player.location)
            if (claim != null && !claim.hasPermission(player.uniqueId, ClaimPermission.ACCESS, isBypassing(player))) {
                val b = claim.bounds
                val safeLoc = Location(
                    player.world,
                    (b.minX - 2) + 0.5,
                    (player.world.getHighestBlockYAt(b.minX - 2, player.location.blockZ) + 1).toDouble(),
                    player.location.z
                )
                scheduler.runForEntityDelayed(player, 80L) {
                    if (player.isOnline && player.location.block.type == Material.NETHER_PORTAL) {
                        player.teleport(safeLoc)
                        messages.send(player, "portal-trap-rescued")
                    }
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlayerFish(event: PlayerFishEvent) {
        val caught = event.caught ?: return
        if (caught is Monster) return
        if (!checkPermission(event.player, caught.location, ClaimPermission.CONTAINER)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLeashEntity(event: PlayerLeashEntityEvent) {
        if (!checkPermission(event.player, event.entity.location, ClaimPermission.CONTAINER)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPotionSplash(event: PotionSplashEvent) {
        val thrower = event.potion.shooter as? Player ?: return
        val hasHarmful = event.potion.effects.any { effect ->
            effect.type.key().value().lowercase() in HARMFUL_EFFECT_KEYS
        }
        if (!hasHarmful) return
        val cfg = configProvider()
        for (entity in event.affectedEntities) {
            if (entity is Monster) continue
            val claim = claimManager.getClaimAt(entity.location) ?: continue
            if (entity is Player && entity.uniqueId != thrower.uniqueId) {
                if (!claim.flag(ClaimFlag.PVP, cfg.defaultFlags) && !isBypassing(thrower)) {
                    event.setIntensity(entity, 0.0)
                }
            } else if (entity !is Player) {
                if (!claim.hasPermission(thrower.uniqueId, ClaimPermission.CONTAINER, isBypassing(thrower))) {
                    event.setIntensity(entity, 0.0)
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityBlockForm(event: EntityBlockFormEvent) {
        val entity = event.entity
        val claim = claimManager.getClaimAt(event.block.location) ?: return
        if (entity is Player) {
            if (!claim.hasPermission(entity.uniqueId, ClaimPermission.BUILD, isBypassing(entity))) {
                event.isCancelled = true
            }
        } else if (!claim.flag(ClaimFlag.MOB_GRIEFING, configProvider().defaultFlags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInteractEntity(event: PlayerInteractEntityEvent) {
        val entity = event.rightClicked
        val required = when (entity) {
            is Animals, is ArmorStand, is Hanging, is Vehicle -> ClaimPermission.CONTAINER
            else -> ClaimPermission.ACCESS
        }
        if (!checkPermission(event.player, entity.location, required)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onArmorStandManipulate(event: PlayerArmorStandManipulateEvent) {
        if (!checkPermission(event.player, event.rightClicked.location, ClaimPermission.CONTAINER)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingPlace(event: HangingPlaceEvent) {
        val player = event.player ?: return
        if (!checkPermission(player, event.entity.location, ClaimPermission.BUILD)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakByEntityEvent) {
        val claim = claimManager.getClaimAt(event.entity.location) ?: return
        val remover = resolvePlayerDamager(event.remover)
        if (remover != null) {
            if (!checkPermission(remover, event.entity.location, ClaimPermission.BUILD)) {
                event.isCancelled = true
            }
        } else if (!claim.flag(ClaimFlag.EXPLOSIONS, configProvider().defaultFlags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) {
        val attacker = resolvePlayerDamager(event.attacker) ?: return
        if (!checkPermission(attacker, event.vehicle.location, ClaimPermission.CONTAINER)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val target = event.entity
        val attacker = resolvePlayerDamager(event.damager)
        val cfg = configProvider()

        if (target is Player && attacker != null && target.uniqueId != attacker.uniqueId) {
            val targetClaim = claimManager.getClaimAt(target.location)
            val attackerClaim = claimManager.getClaimAt(attacker.location)
            val pvpDenied = (targetClaim != null && !targetClaim.flag(ClaimFlag.PVP, cfg.defaultFlags)) ||
                (attackerClaim != null && !attackerClaim.flag(ClaimFlag.PVP, cfg.defaultFlags))
            if (pvpDenied && !isBypassing(attacker)) {
                actionBar.sendDenial(attacker, "deny-pvp")
                event.isCancelled = true
                return
            }
        }

        if (attacker != null && target !is Monster && target !is Player) {
            if (!checkPermission(attacker, target.location, ClaimPermission.CONTAINER)) {
                event.isCancelled = true
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val defaults = configProvider().defaultFlags
        event.blockList().removeIf { block ->
            val claim = claimManager.getClaimAt(block.location)
            claim != null && !claim.flag(ClaimFlag.EXPLOSIONS, defaults)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val defaults = configProvider().defaultFlags
        event.blockList().removeIf { block ->
            val claim = claimManager.getClaimAt(block.location)
            claim != null && !claim.flag(ClaimFlag.EXPLOSIONS, defaults)
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        if (event.entity is Player) return
        val claim = claimManager.getClaimAt(event.block.location) ?: return
        if (!claim.flag(ClaimFlag.MOB_GRIEFING, configProvider().defaultFlags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        val claim = claimManager.getClaimAt(event.block.location) ?: return
        if (!claim.flag(ClaimFlag.FIRE_SPREAD, configProvider().defaultFlags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (event.source.type != Material.FIRE) return
        val claim = claimManager.getClaimAt(event.block.location) ?: return
        if (!claim.flag(ClaimFlag.FIRE_SPREAD, configProvider().defaultFlags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        val player = event.player
        if (player != null) {
            if (!checkPermission(player, event.block.location, ClaimPermission.BUILD)) {
                event.isCancelled = true
            }
            return
        }
        val claim = claimManager.getClaimAt(event.block.location) ?: return
        if (!claim.flag(ClaimFlag.FIRE_SPREAD, configProvider().defaultFlags)) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLiquidFlow(event: BlockFromToEvent) {
        val fromClaim = claimManager.getTopLevelClaimAt(event.block.world.name, event.block.x, event.block.z)
        val toClaim = claimManager.getTopLevelClaimAt(event.toBlock.world.name, event.toBlock.x, event.toBlock.z)
        if (toClaim != null && toClaim.id != fromClaim?.id) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        checkPistonBoundary(event.block, event.blocks.map { it.getRelative(event.direction) } + event.blocks) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        checkPistonBoundary(event.block, event.blocks) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val player = event.player
        portalReturnLocations.remove(player.uniqueId)
        visualizer.clear(player)
        bossBar.hideAll(player)
        actionBar.clearSession(player.uniqueId)
    }

    private inline fun checkPistonBoundary(piston: Block, affected: List<Block>, cancel: () -> Unit) {
        val defaults = configProvider().defaultFlags
        val pistonClaim = claimManager.getTopLevelClaimAt(piston.world.name, piston.x, piston.z)
        for (b in affected) {
            val targetClaim = claimManager.getTopLevelClaimAt(b.world.name, b.x, b.z)
            if (targetClaim != null && targetClaim.id != pistonClaim?.id) {
                if (!targetClaim.flag(ClaimFlag.PISTON_CROSS_BORDER, defaults)) {
                    cancel()
                    return
                }
            }
        }
    }

    private fun resolvePlayerDamager(entity: org.bukkit.entity.Entity?): Player? = when (entity) {
        is Player -> entity
        is Projectile -> entity.shooter as? Player
        else -> null
    }

    private fun classifyBlockInteraction(block: Block): ClaimPermission? {
        val type = block.type
        if (block.state is BlockInventoryHolder) return ClaimPermission.CONTAINER
        if (type == Material.ENDER_CHEST) return null
        if (Tag.BEDS.isTagged(type) || Tag.DOORS.isTagged(type) || Tag.TRAPDOORS.isTagged(type) ||
            Tag.FENCE_GATES.isTagged(type) || Tag.BUTTONS.isTagged(type) || type == Material.LEVER ||
            type == Material.REPEATER || type == Material.COMPARATOR || type == Material.DAYLIGHT_DETECTOR ||
            type == Material.NOTE_BLOCK
        ) {
            return ClaimPermission.ACCESS
        }
        if (type == Material.ANVIL || type == Material.CHIPPED_ANVIL || type == Material.DAMAGED_ANVIL ||
            type == Material.BEACON || type == Material.LECTERN || type == Material.FLOWER_POT ||
            type == Material.CAULDRON || type == Material.WATER_CAULDRON || type == Material.JUKEBOX ||
            type == Material.SWEET_BERRY_BUSH
        ) {
            return ClaimPermission.CONTAINER
        }
        return null
    }

    companion object {
        private val HARMFUL_EFFECT_KEYS = setOf(
            "poison",
            "instant_damage",
            "harm",
            "wither",
            "slowness",
            "slow",
            "weakness",
            "mining_fatigue",
            "slow_digging"
        )
    }
}
