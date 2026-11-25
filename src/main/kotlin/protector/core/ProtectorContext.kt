package protector.core

import org.bukkit.plugin.java.JavaPlugin
import protector.core.command.CommandAliasService
import protector.core.command.ProtectorCommandRegistry
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.module.ModuleRegistry
import protector.core.scheduler.ProtectorScheduler
import protector.core.storage.AuditLogRepository
import protector.core.storage.ClaimRepository
import protector.core.storage.DatabaseManager
import protector.core.storage.IdMappingCache
import protector.core.ui.ActionBarHudService
import protector.core.ui.BossBarService
import protector.core.ui.DialogService
import protector.core.ui.MenuService
import protector.modules.audit.InspectorService
import protector.modules.audit.LogQueueConsumer
import protector.modules.audit.RollbackService
import protector.modules.claim.BoundaryVisualizer
import protector.modules.claim.ClaimManager
import protector.modules.claim.ClaimToolService
import protector.modules.claim.DeathDropProtectionService
import java.io.File

/**
 * Central service container wiring core infrastructure, UI, command aliases, and modules
 * (modeled after Obsidian's ObsidianContext).
 */
class ProtectorContext(val plugin: JavaPlugin) {

    @Volatile
    var config: ProtectorConfig = ProtectorConfig.load(plugin)
        private set

    val messages = MessageService(plugin).apply { reload(config.language) }
    val scheduler = ProtectorScheduler(plugin)

    val database = DatabaseManager(File(plugin.dataFolder, config.databaseFile), config.databasePoolSize)
    val idMappingCache = IdMappingCache(database).apply { warmUp() }
    val claimRepository = ClaimRepository(database)
    val auditLogRepository = AuditLogRepository(database, idMappingCache)

    val claimManager = ClaimManager(scheduler, claimRepository) { config }
    val boundaryVisualizer = BoundaryVisualizer(scheduler) { config }
    val bossBar = BossBarService(scheduler) { config }
    val actionBar = ActionBarHudService(scheduler, { config }, messages, claimManager, bossBar)
    val deathDrops = DeathDropProtectionService(plugin, { config }, messages, actionBar)
    val claimToolService = ClaimToolService(
        { config },
        messages,
        claimManager,
        boundaryVisualizer,
        actionBar,
        bossBar
    )

    val logQueueConsumer = LogQueueConsumer(auditLogRepository, { config }, plugin.logger)
    val rollbackService = RollbackService(
        scheduler,
        { config },
        messages,
        auditLogRepository,
        logQueueConsumer,
        bossBar
    )
    val inspectorService = InspectorService(
        scheduler,
        messages,
        auditLogRepository,
        logQueueConsumer,
        actionBar
    )

    val dialogs = DialogService(
        plugin,
        { config },
        messages,
        claimManager,
        rollbackService,
        auditLogRepository,
        logQueueConsumer
    )
    val menus = MenuService(
        plugin,
        scheduler,
        { config },
        messages,
        claimManager,
        boundaryVisualizer,
        actionBar,
        bossBar,
        deathDrops,
        inspectorService,
        rollbackService,
        dialogs,
        auditLogRepository,
        logQueueConsumer
    )

    val modules = ModuleRegistry(plugin) { config }
    val commandRegistry = ProtectorCommandRegistry()
    val commandAliases = CommandAliasService(this)

    init {
        actionBar.hasLockedDrops = { uuid -> deathDrops.hasLockedDrops(uuid) }
        inspectorService.onOpenInspectDialog = { player, loc, bLogs, cLogs ->
            dialogs.openInspectDetailDialog(player, loc, bLogs, cLogs)
        }
        dialogs.onOpenAuditMenu = { player, filter ->
            menus.openAuditBrowserAsync(player, filter, 0)
        }
        dialogs.onReopenClaimDetail = { player, claim ->
            menus.openClaimDetail(player, claim)
        }
        dialogs.onFallbackConfirmation = { player, title, body, onConfirm ->
            menus.openConfirmationMenu(player, title, body, onConfirm)
        }
        dialogs.onFallbackClaimFlags = { player, claim ->
            menus.openClaimFlagsMenu(player, claim)
        }
        dialogs.onFallbackTrustSelect = { player, claim ->
            menus.openTrustSelectPlayerMenu(player, claim)
        }
        dialogs.onFallbackTransferSelect = { player, claim ->
            menus.openTransferSelectPlayerMenu(player, claim)
        }
        dialogs.onFallbackRollbackPreset = { player, restoreMode ->
            menus.openRollbackPresetMenu(player, restoreMode)
        }
        dialogs.onFallbackAdminBlocks = { player ->
            menus.openAdminClaimBlocksMenu(player)
        }
    }

    fun reloadAll() {
        config = ProtectorConfig.load(plugin)
        messages.reload(config.language)
        actionBar.start()
        modules.reloadAll()
        commandAliases.reload()
    }

    fun shutdown() {
        commandAliases.shutdown()
        modules.disableAll()
        actionBar.stop()
        bossBar.clearAll()
        scheduler.shutdown()
        database.close()
    }
}
