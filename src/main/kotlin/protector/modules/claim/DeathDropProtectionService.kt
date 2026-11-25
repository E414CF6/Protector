package protector.modules.claim

import org.bukkit.NamespacedKey
import org.bukkit.entity.Item
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.inventory.InventoryPickupItemEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import protector.core.locale.MessageService
import protector.core.config.ProtectorConfig
import protector.core.ui.ActionBarHudService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DeathDropProtectionService(
    plugin: JavaPlugin,
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val actionBar: ActionBarHudService
) : Listener {
    private val ownerKey = NamespacedKey(plugin, "death_drop_owner")
    private val ownerNameKey = NamespacedKey(plugin, "death_drop_owner_name")
    private val lockedOwners = ConcurrentHashMap.newKeySet<UUID>()

    fun hasLockedDrops(uuid: UUID): Boolean = uuid in lockedOwners

    fun unlockDrops(player: Player): Boolean {
        val removed = lockedOwners.remove(player.uniqueId)
        for (entity in player.world.getEntitiesByClass(Item::class.java)) {
            val ownerStr = entity.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) ?: continue
            if (ownerStr == player.uniqueId.toString()) {
                entity.persistentDataContainer.remove(ownerKey)
                entity.persistentDataContainer.remove(ownerNameKey)
            }
        }
        if (removed) {
            messages.send(player, "drops-unlocked")
        } else {
            messages.send(player, "drops-not-locked")
        }
        return removed
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onPlayerDeath(event: PlayerDeathEvent) {
        val cfg = configProvider()
        if (!cfg.protectDeathDrops || event.keepInventory || event.drops.isEmpty()) return
        val player = event.entity
        // If killed by another player in PvP, do not lock PvP loot
        if (player.killer != null && player.killer?.uniqueId != player.uniqueId) return

        val loc = player.location
        val world = loc.world ?: return
        lockedOwners.add(player.uniqueId)

        val droppedCopies = event.drops.map { it.clone() }
        event.drops.clear()
        for (stack in droppedCopies) {
            val item = world.dropItemNaturally(loc, stack)
            item.persistentDataContainer.set(ownerKey, PersistentDataType.STRING, player.uniqueId.toString())
            item.persistentDataContainer.set(ownerNameKey, PersistentDataType.STRING, player.name)
        }
        messages.send(player, "drops-protected")
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEntityPickup(event: EntityPickupItemEvent) {
        val ownerStr = event.item.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) ?: return
        val ownerId = runCatching { UUID.fromString(ownerStr) }.getOrNull() ?: return
        if (ownerId !in lockedOwners) return

        val entity = event.entity
        if (entity is Player) {
            if (entity.uniqueId == ownerId) return
            val st = actionBar.state(entity.uniqueId)
            if (st.adminBypass && entity.hasPermission("protector.admin")) return

            val ownerName = event.item.persistentDataContainer.get(ownerNameKey, PersistentDataType.STRING) ?: "Player"
            actionBar.sendDenial(entity, "drops-locked-deny", mapOf("owner" to ownerName))
            event.isCancelled = true
        } else {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHopperPickup(event: InventoryPickupItemEvent) {
        if (event.inventory.type != InventoryType.HOPPER) return
        val ownerStr = event.item.persistentDataContainer.get(ownerKey, PersistentDataType.STRING) ?: return
        val ownerId = runCatching { UUID.fromString(ownerStr) }.getOrNull() ?: return
        if (ownerId in lockedOwners) {
            event.isCancelled = true
        }
    }
}
