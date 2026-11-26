package protector.modules.audit.ui

import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Location
import org.bukkit.entity.Player
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.ui.DialogKit
import protector.modules.audit.BlockLogRecord
import protector.modules.audit.ContainerLogRecord
import protector.modules.audit.LookupActionFilter
import protector.modules.audit.LookupFilter
import protector.modules.audit.RollbackService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class AuditDialogs(
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val rollbackService: RollbackService,
    private val dialogKit: DialogKit,
    private val menusProvider: () -> AuditMenus,
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val paperBackend: PaperAuditDialogs? by lazy {
        if (dialogKit.supportsNativeDialogs) {
            PaperAuditDialogs(configProvider, messages, rollbackService) { player, filter ->
                menusProvider().openAuditBrowserAsync(player, filter, 0)
            }
        } else {
            null
        }
    }

    fun openRollbackFilterDialog(player: Player, restoreMode: Boolean = false) {
        val backend = paperBackend
        if (backend != null) {
            backend.openRollbackFilterDialog(player, restoreMode)
        } else {
            menusProvider().openRollbackPresetMenu(player, restoreMode)
        }
    }

    fun openInspectDetailDialog(
        player: Player,
        loc: Location,
        blockLogs: List<BlockLogRecord>,
        containerLogs: List<ContainerLogRecord>,
    ) {
        val backend = paperBackend
        if (backend != null) {
            backend.openInspectDetailDialog(player, loc, blockLogs, containerLogs)
            return
        }

        messages.sendRaw(
            player,
            "<#fbbf24>━━━ Audit Details: ${loc.world.name} (${loc.blockX}, ${loc.blockY}, ${loc.blockZ}) ━━━</#fbbf24>",
            withPrefix = false
        )
        for (b in blockLogs.take(6)) {
            val ts = timeFormatter.format(Instant.ofEpochSecond(b.timeSec))
            val target = if (b.action.name == "PLACE") b.newMaterial else b.oldMaterial
            messages.sendRaw(
                player,
                "<gray>[$ts]</gray> <white>${b.actorName}</white> <yellow>${b.action.label}</yellow> <aqua>$target</aqua>",
                withPrefix = false
            )
        }
        for (c in containerLogs.take(6)) {
            val ts = timeFormatter.format(Instant.ofEpochSecond(c.timeSec))
            messages.sendRaw(
                player,
                "<gray>[$ts]</gray> <white>${c.actorName}</white> <gold>${c.action.label}</gold> <aqua>${c.material} ×${c.amount}</aqua>",
                withPrefix = false
            )
        }
        if (player.hasPermission("protector.rollback")) {
            val rbComp = messages.renderRaw("<gold>[↺ Rollback 1h (r:1)]</gold> ")
                .clickEvent(ClickEvent.runCommand("/rollback t:1h r:1"))
            player.sendMessage(rbComp)
        }
    }

    fun openActivitySearchDialog(player: Player) {
        val backend = paperBackend
        if (backend != null) {
            backend.openActivitySearchDialog(player)
            return
        }
        val loc = player.location
        menusProvider().openAuditBrowserAsync(
            player,
            LookupFilter(
                worldName = loc.world.name,
                centerX = loc.blockX,
                centerY = loc.blockY,
                centerZ = loc.blockZ,
                radius = 40,
                sinceEpochSec = (System.currentTimeMillis() / 1000L) - 86400L,
                actionFilter = LookupActionFilter.CHAT,
                limit = 100
            ),
            0
        )
    }
}
