package protector.modules.audit

import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.EquipmentSlot
import protector.core.locale.MessageService
import protector.core.scheduler.PluginScheduler
import protector.core.ui.ActionBarService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InspectorService(
    private val scheduler: PluginScheduler,
    private val messages: MessageService,
    private val repository: AuditLogRepository,
    private val consumer: LogQueueConsumer,
    private val actionBar: ActionBarService,
) : Listener {
    var onOpenInspectDialog: ((Player, Location, List<BlockLogRecord>, List<ContainerLogRecord>) -> Unit)? = null

    private val inspectingPlayers = ConcurrentHashMap.newKeySet<UUID>()
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun isInspecting(uuid: UUID): Boolean = uuid in inspectingPlayers

    fun setInspecting(player: Player, enabled: Boolean) {
        if (enabled) {
            inspectingPlayers.add(player.uniqueId)
            messages.send(player, "inspect-enabled")
        } else {
            inspectingPlayers.remove(player.uniqueId)
            messages.send(player, "inspect-disabled")
        }
        actionBar.tickPlayer(player)
    }

    fun toggleInspect(player: Player): Boolean {
        val next = !isInspecting(player.uniqueId)
        setInspecting(player, next)
        return next
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        inspectingPlayers.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun onInspectClick(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        val player = event.player
        if (!isInspecting(player.uniqueId)) return
        if (!player.hasPermission("protector.inspect")) {
            inspectingPlayers.remove(player.uniqueId)
            return
        }

        val clicked = event.clickedBlock ?: return
        val targetBlock = when (event.action) {
            Action.LEFT_CLICK_BLOCK -> clicked
            Action.RIGHT_CLICK_BLOCK -> {
                if (clicked.state is BlockInventoryHolder) clicked
                else clicked.getRelative(event.blockFace)
            }
            else -> return
        }

        event.isCancelled = true
        val loc = targetBlock.location.clone()
        val isSneaking = player.isSneaking

        scheduler.runAsync {
            consumer.flushNow()
            val blockLogs = repository.queryBlockAt(loc.world.name, loc.blockX, loc.blockY, loc.blockZ, limit = 10)
            val containerLogs = repository.queryContainerAt(loc.world.name, loc.blockX, loc.blockY, loc.blockZ, limit = 10)

            scheduler.runForEntity(player) {
                if (!player.isOnline) return@runForEntity
                if (blockLogs.isEmpty() && containerLogs.isEmpty()) {
                    messages.send(
                        player,
                        "inspect-empty",
                        mapOf("x" to loc.blockX, "y" to loc.blockY, "z" to loc.blockZ)
                    )
                    return@runForEntity
                }

                if (isSneaking && onOpenInspectDialog != null) {
                    onOpenInspectDialog?.invoke(player, loc, blockLogs, containerLogs)
                    return@runForEntity
                }

                messages.sendRaw(
                    player,
                    "<aqua>─── Audit History (${loc.blockX}, ${loc.blockY}, ${loc.blockZ}) ───</aqua>",
                    withPrefix = false
                )
                for (b in blockLogs.take(5)) {
                    val ts = timeFormatter.format(Instant.ofEpochSecond(b.timeSec))
                    val targetMat = if (b.action.name == "PLACE") b.newMaterial else b.oldMaterial
                    val rbTag = if (b.rolledBack) " <dark_gray>[Rolled back]</dark_gray>" else ""
                    messages.sendRaw(
                        player,
                        "<gray>[$ts]</gray> <white>${b.actorName}</white> <yellow>${b.action.label}</yellow> <aqua>$targetMat</aqua>$rbTag",
                        withPrefix = false
                    )
                }
                for (c in containerLogs.take(5)) {
                    val ts = timeFormatter.format(Instant.ofEpochSecond(c.timeSec))
                    val rbTag = if (c.rolledBack) " <dark_gray>[Rolled back]</dark_gray>" else ""
                    messages.sendRaw(
                        player,
                        "<gray>[$ts]</gray> <white>${c.actorName}</white> <gold>${c.action.label}</gold> <aqua>${c.material} ×${c.amount}</aqua>$rbTag",
                        withPrefix = false
                    )
                }
                if (onOpenInspectDialog != null) {
                    messages.sendRaw(
                        player,
                        "<dark_gray>💡 Shift+Click to open the detailed Inspector Dialog.</dark_gray>",
                        withPrefix = false
                    )
                }
            }
        }
    }
}
