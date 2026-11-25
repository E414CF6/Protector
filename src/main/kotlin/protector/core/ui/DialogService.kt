package protector.core.ui

import net.kyori.adventure.text.event.ClickEvent
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import protector.core.compat.PlatformCompat
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.storage.*
import protector.modules.audit.LogQueueConsumer
import protector.modules.audit.RollbackService
import protector.modules.claim.Claim
import protector.modules.claim.ClaimManager
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DialogService(
    private val plugin: JavaPlugin,
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val rollbackService: RollbackService,
    private val repository: AuditLogRepository,
    private val consumer: LogQueueConsumer
) {
    var onOpenAuditMenu: ((Player, LookupFilter) -> Unit)? = null
    var onReopenClaimDetail: ((Player, Claim) -> Unit)? = null

    // Legacy (< 1.21.6) GUI fallback hooks wired by MenuService
    var onFallbackConfirmation: ((Player, String, String, (Player) -> Unit) -> Unit)? = null
    var onFallbackClaimFlags: ((Player, Claim) -> Unit)? = null
    var onFallbackTrustSelect: ((Player, Claim) -> Unit)? = null
    var onFallbackTransferSelect: ((Player, Claim) -> Unit)? = null
    var onFallbackRollbackPreset: ((Player, Boolean) -> Unit)? = null
    var onFallbackAdminBlocks: ((Player) -> Unit)? = null

    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private val paperBackend: PaperDialogBackend? by lazy {
        if (PlatformCompat.supportsDialogs) {
            PaperDialogBackend(
                configProvider = configProvider,
                messages = messages,
                claimManager = claimManager,
                rollbackService = rollbackService,
                onOpenAuditMenu = { onOpenAuditMenu },
                onReopenClaimDetail = { onReopenClaimDetail }
            )
        } else {
            null
        }
    }

    fun openClaimSettingsDialog(player: Player, claim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openClaimSettingsDialog(player, claim)
        } else {
            onFallbackClaimFlags?.invoke(player, claim)
        }
    }

    fun openTrustAddDialog(player: Player, claim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openTrustAddDialog(player, claim)
        } else {
            onFallbackTrustSelect?.invoke(player, claim)
        }
    }

    fun openRollbackFilterDialog(player: Player, restoreMode: Boolean = false) {
        val backend = paperBackend
        if (backend != null) {
            backend.openRollbackFilterDialog(player, restoreMode)
        } else {
            onFallbackRollbackPreset?.invoke(player, restoreMode)
        }
    }

    fun openConfirmationDialog(
        player: Player,
        titleMiniMessage: String,
        bodyMiniMessage: String,
        confirmLabelMiniMessage: String = "<red>Confirm</red>",
        onConfirm: (Player) -> Unit
    ) {
        val backend = paperBackend
        if (backend != null) {
            backend.openConfirmationDialog(
                player,
                titleMiniMessage,
                bodyMiniMessage,
                confirmLabelMiniMessage,
                onConfirm
            )
        } else {
            onFallbackConfirmation?.invoke(player, titleMiniMessage, bodyMiniMessage, onConfirm)
                ?: onConfirm(player)
        }
    }

    fun openInspectDetailDialog(
        player: Player,
        loc: Location,
        blockLogs: List<BlockLogRecord>,
        containerLogs: List<ContainerLogRecord>
    ) {
        val backend = paperBackend
        if (backend != null) {
            backend.openInspectDetailDialog(player, loc, blockLogs, containerLogs)
            return
        }

        // Legacy (< 1.21.6) interactive chat fallback
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

    fun openTransferClaimDialog(player: Player, claim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openTransferClaimDialog(player, claim)
        } else {
            onFallbackTransferSelect?.invoke(player, claim)
        }
    }

    fun openSubclaimSettingsDialog(player: Player, subclaim: Claim) {
        val backend = paperBackend
        if (backend != null) {
            backend.openSubclaimSettingsDialog(player, subclaim)
            return
        }
        val next = !subclaim.inheritNothing
        openConfirmationDialog(
            player,
            "<light_purple><bold>Toggle Subclaim Isolation</bold></light_purple>",
            "<gray>Set <white>${subclaim.name}</white> isolation (inheritNothing) to <white>${if (next) "ON" else "OFF"}</white>?</gray>",
            "<green>Toggle</green>"
        ) { p ->
            claimManager.updateMetadata(subclaim, subclaim.name, subclaim.greeting, next)
            messages.send(p, "subclaim-restricted", mapOf("state" to if (next) "Isolated" else "Inheriting"))
        }
    }

    fun openActivitySearchDialog(player: Player) {
        val backend = paperBackend
        if (backend != null) {
            backend.openActivitySearchDialog(player)
            return
        }
        val loc = player.location
        onOpenAuditMenu?.invoke(
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
            )
        )
    }

    fun openAdminClaimBlocksDialog(player: Player) {
        val backend = paperBackend
        if (backend != null) {
            backend.openAdminClaimBlocksDialog(player)
        } else {
            onFallbackAdminBlocks?.invoke(player)
        }
    }
}
