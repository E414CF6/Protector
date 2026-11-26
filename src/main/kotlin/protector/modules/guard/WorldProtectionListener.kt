package protector.modules.guard

import org.bukkit.Material
import org.bukkit.entity.*
import org.bukkit.entity.minecart.ExplosiveMinecart
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.world.PortalCreateEvent
import protector.core.config.ProtectorConfig
import protector.core.ui.ActionBarService

/**
 * Global and per-world environment protection listener covering TNT, explosions,
 * fire spread, mob griefing, and non-player portals.
 */
class WorldProtectionListener(
    private val configProvider: () -> ProtectorConfig,
    private val actionBar: ActionBarService,
    private val adminBypassCheck: (Player) -> Boolean,
) : Listener {

    private fun isBypassing(player: Player?): Boolean =
        player != null && (player.hasPermission("protector.bypass.guard") || adminBypassCheck(player))

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityExplode(event: EntityExplodeEvent) {
        val guard = configProvider().guard
        val worldName = event.location.world?.name ?: return
        if (!guard.isWorldEnabled(worldName)) return

        val cancelBlocks = when (event.entity) {
            is TNTPrimed -> guard.tntPreventBlockDamage
            is ExplosiveMinecart -> guard.tntPreventMinecart || guard.tntPreventBlockDamage
            is Creeper -> guard.preventCreeperBlockDamage
            is Wither, is WitherSkull -> guard.preventWitherDamage
            is Fireball -> guard.preventFireballBlockDamage
            is EnderCrystal -> guard.preventEndCrystalDamage
            else -> false
        }
        if (cancelBlocks) {
            event.blockList().clear()
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(event.block.world.name)) return
        if (guard.preventBedAnchorExplode) {
            event.blockList().clear()
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityDamageByExplosion(event: EntityDamageByEntityEvent) {
        val guard = configProvider().guard
        val worldName = event.entity.world.name
        if (!guard.isWorldEnabled(worldName)) return
        val damager = event.damager
        val cancelDamage = when {
            damager is TNTPrimed && guard.tntPreventEntityDamage -> true
            damager is ExplosiveMinecart && (guard.tntPreventEntityDamage || guard.tntPreventMinecart) -> true
            damager is EnderCrystal && guard.preventEndCrystalDamage -> true
            else -> false
        }
        if (cancelDamage) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockSpread(event: BlockSpreadEvent) {
        if (event.source.type != Material.FIRE) return
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(event.block.world.name)) return
        if (guard.preventFireSpread) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockBurn(event: BlockBurnEvent) {
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(event.block.world.name)) return
        if (guard.preventBlockBurn) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockIgnite(event: BlockIgniteEvent) {
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(event.block.world.name)) return
        val cancel = when (event.cause) {
            BlockIgniteEvent.IgniteCause.LAVA -> guard.preventLavaFireSpread
            BlockIgniteEvent.IgniteCause.LIGHTNING -> guard.preventLightningFire
            BlockIgniteEvent.IgniteCause.SPREAD -> guard.preventFireSpread
            else -> false
        }
        if (cancel) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityChangeBlock(event: EntityChangeBlockEvent) {
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(event.block.world.name)) return
        val cancel = when (event.entity) {
            is Enderman -> guard.preventEndermanGrief
            is Ravager -> guard.preventRavagerGrief
            is Wither -> guard.preventWitherBlockBreak
            else -> false
        }
        if (cancel) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPortalCreate(event: PortalCreateEvent) {
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(event.world.name)) return
        if (!guard.preventNonPlayerPortals) return
        val player = event.entity as? Player
        if (player == null) {
            event.isCancelled = true
        } else if (isBypassing(player)) {
            return
        }
    }

    internal fun handleTntPrime(blockWorldName: String, primingEntity: Entity?, cancel: () -> Unit) {
        val guard = configProvider().guard
        if (!guard.isWorldEnabled(blockWorldName)) return
        if (!guard.tntPreventPrime) return
        val player = primingEntity as? Player
        if (isBypassing(player)) return
        if (player != null) {
            actionBar.sendDenial(player, "guard-tnt-blocked")
        }
        cancel()
    }
}

/**
 * Isolated Paper 1.19.3+ TNTPrimeEvent listener so older API servers still load safely.
 */
class TntPrimeGuardListener(
    private val parent: WorldProtectionListener,
) : Listener {
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTntPrime(event: org.bukkit.event.block.TNTPrimeEvent) {
        parent.handleTntPrime(event.block.world.name, event.primingEntity) {
            event.isCancelled = true
        }
    }
}
