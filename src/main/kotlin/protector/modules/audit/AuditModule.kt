package protector.modules.audit

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import protector.core.ProtectorContext
import protector.core.command.CommandRegistry
import protector.core.command.SubcommandTabCompleter
import protector.core.compat.PlatformCompat
import protector.core.module.FeatureModule
import protector.modules.audit.ui.AuditDialogs
import protector.modules.audit.ui.AuditMenus
import java.util.Locale

class AuditModule(
    private val ctx: ProtectorContext,
    dashboardOpener: (Player) -> Unit,
) : FeatureModule {
    override val id: String = "audit"
    override val description: String = "Forensic block/container/entity/chat audit logging, inspector, and rollback engine"

    val idMappingCache = IdMappingCache(ctx.database).apply { warmUp() }
    val repository = AuditLogRepository(ctx.database, idMappingCache)
    val consumer = LogQueueConsumer(repository, { ctx.config }, ctx.plugin.logger)
    val rollbackService = RollbackService(
        ctx.scheduler,
        { ctx.config },
        ctx.messages,
        repository,
        consumer,
        ctx.bossBar,
    )
    val inspectorService = InspectorService(
        ctx.scheduler,
        ctx.messages,
        repository,
        consumer,
        ctx.actionBar,
    )

    lateinit var menus: AuditMenus
        private set
    lateinit var dialogs: AuditDialogs
        private set

    init {
        dialogs = AuditDialogs({ ctx.config }, ctx.messages, rollbackService, ctx.dialogs) { menus }
        menus = AuditMenus(
            ctx.scheduler,
            ctx.messages,
            repository,
            consumer,
            rollbackService,
            { dialogs },
            dashboardOpener,
        )
        inspectorService.onOpenInspectDialog = { player, loc, bLogs, cLogs ->
            dialogs.openInspectDetailDialog(player, loc, bLogs, cLogs)
        }
    }

    override fun onEnable() {
        consumer.start()
        ctx.actionBar.registerHudProvider("audit_inspector", priority = 50) { player ->
            if (inspectorService.isInspecting(player.uniqueId)) {
                ctx.messages.render("inspect-hud")
            } else {
                null
            }
        }
    }

    override fun onDisable() {
        ctx.actionBar.unregisterHudProvider("audit_inspector")
        consumer.shutdownAndDrain()
    }

    override fun listeners(): List<Listener> {
        val auditListeners = AuditListeners({ ctx.config }, consumer)
        val result = mutableListOf<Listener>(auditListeners, inspectorService)
        result += if (PlatformCompat.supportsPaperAsyncChat) {
            PaperChatAuditListener({ ctx.config }, consumer)
        } else {
            LegacyChatAuditListener({ ctx.config }, consumer)
        }
        if (PlatformCompat.supportsTntPrimeEvent) {
            result += TntPrimeAuditListener({ ctx.config }, auditListeners)
        }
        if (PlatformCompat.supportsTakeLecternBookEvent) {
            result += LecternAuditListener({ ctx.config }, consumer)
        }
        return result
    }

    override fun registerSubcommands(registry: CommandRegistry) {
        val filterCompleter = SubcommandTabCompleter { _, args ->
            val last = args.lastOrNull().orEmpty()
            listOf(
                "u:", "-u:", "t:1h", "t:24h", "t:1d-1h", "r:15", "r:30", "r:#global",
                "a:block", "a:+block", "a:-block", "a:container", "a:+container", "a:-container",
                "a:entity", "a:sign", "a:chat", "a:session",
                "i:", "e:", "f:", "#preview", "#count", "apply"
            ).filter { it.startsWith(last, ignoreCase = true) }
        }

        registry.registerSubcommand(
            name = "inspect",
            description = "Toggle block and container audit inspection mode",
            permission = "protector.inspect",
            aliases = listOf("i", "coi"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            inspectorService.toggleInspect(player)
            true
        }

        registry.registerSubcommand(
            name = "near",
            description = "Browse nearby audit logs in the GUI",
            permission = "protector.lookup",
            standaloneByDefault = false,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val radius = args.firstOrNull()?.toIntOrNull()?.coerceIn(1, ctx.config.maxRollbackRadius) ?: 10
            val loc = player.location
            val filter = LookupFilter(
                worldName = loc.world.name,
                centerX = loc.blockX,
                centerY = loc.blockY,
                centerZ = loc.blockZ,
                radius = radius,
                sinceEpochSec = (System.currentTimeMillis() / 1000L) - 86400L,
                limit = 150
            )
            menus.openAuditBrowserAsync(player, filter, 0)
            true
        }

        registry.registerSubcommand(
            name = "lookup",
            description = "Open lookup filter Dialog or query audit logs",
            permission = "protector.lookup",
            aliases = listOf("l"),
            tabCompleter = filterCompleter,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            if (args.isEmpty()) {
                dialogs.openRollbackFilterDialog(player, restoreMode = false)
                return@registerSubcommand true
            }
            val filter = parseLookupArgs(player, args)
            if (filter.countOnly) {
                rollbackService.countMatches(player, filter)
            } else {
                menus.openAuditBrowserAsync(player, filter, 0)
            }
            true
        }

        registry.registerSubcommand(
            name = "rollback",
            description = "Preview or roll back logged changes",
            permission = "protector.rollback",
            aliases = listOf("rb"),
            tabCompleter = filterCompleter,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            if (args.isEmpty()) {
                dialogs.openRollbackFilterDialog(player, restoreMode = false)
                return@registerSubcommand true
            }
            val filter = parseLookupArgs(player, args)
            if (filter.countOnly) {
                rollbackService.countMatches(player, filter)
            } else if (!filter.previewRequested &&
                args.any { it.equals("apply", ignoreCase = true) || it.equals("now", ignoreCase = true) }
            ) {
                rollbackService.executeDirect(player, filter, restoreMode = false)
            } else {
                rollbackService.startPreview(player, filter, restoreMode = false)
            }
            true
        }

        registry.registerSubcommand(
            name = "restore",
            description = "Restore previously rolled-back changes",
            permission = "protector.restore",
            aliases = listOf("rs"),
            tabCompleter = filterCompleter,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            if (args.isEmpty()) {
                dialogs.openRollbackFilterDialog(player, restoreMode = true)
                return@registerSubcommand true
            }
            val filter = parseLookupArgs(player, args)
            if (filter.countOnly) {
                rollbackService.countMatches(player, filter)
            } else if (!filter.previewRequested &&
                args.any { it.equals("apply", ignoreCase = true) || it.equals("now", ignoreCase = true) }
            ) {
                rollbackService.executeDirect(player, filter, restoreMode = true)
            } else {
                rollbackService.startPreview(player, filter, restoreMode = true)
            }
            true
        }

        registry.registerSubcommand(
            name = "apply",
            description = "Apply an active rollback or restore preview",
            permission = "protector.rollback",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            if (!rollbackService.applyPreview(player)) {
                ctx.messages.sendRaw(player, "<yellow>No active rollback or restore preview to apply.</yellow>")
            }
            true
        }

        registry.registerSubcommand(
            name = "cancel",
            description = "Cancel an active rollback or restore preview",
            permission = "protector.rollback",
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            if (!rollbackService.cancelPreview(player)) {
                ctx.messages.sendRaw(player, "<gray>No active rollback preview to cancel.</gray>")
            }
            true
        }

        registry.registerSubcommand(
            name = "undo",
            description = "Undo your last rollback or restore",
            permission = "protector.rollback",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            rollbackService.undoLast(player)
            true
        }

        registry.registerSubcommand(
            name = "purge",
            description = "Purge old audit logs",
            permission = "protector.purge",
        ) { sender, args ->
            val durationToken = args.firstOrNull { it.startsWith("t:", ignoreCase = true) }?.substring(2)
                ?: args.firstOrNull()
                ?: "30d"
            val seconds = AuditFilterParser.parseDurationSeconds(durationToken)
            if (seconds == null) {
                ctx.messages.sendRaw(sender, "<red>Invalid duration format. Example: /pr purge 30d</red>")
                return@registerSubcommand true
            }
            val player = sender as? Player
            if (player != null) {
                ctx.dialogs.openConfirmationDialog(
                    player,
                    "<red><bold>Confirm Audit Log Purge</bold></red>",
                    "<gray>Permanently delete all audit logs older than <white>$durationToken</white>.\nThis action cannot be undone.</gray>",
                    "<red>Purge</red>"
                ) { p ->
                    rollbackService.purgeBefore(p, p, seconds)
                }
            } else {
                rollbackService.purgeBefore(sender, null, seconds)
            }
            true
        }

        registry.registerSubcommand(
            name = "consumer",
            description = "Control or inspect the async audit log consumer queue",
            permission = "protector.consumer",
            standaloneByDefault = false,
            tabCompleter = SubcommandTabCompleter { _, args ->
                if (args.size <= 1) {
                    listOf("status", "pause", "resume", "flush")
                        .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
                } else emptyList()
            },
        ) { sender, args ->
            when (args.firstOrNull()?.lowercase(Locale.ROOT) ?: "status") {
                "pause" -> {
                    consumer.paused.set(true)
                    ctx.messages.send(sender, "consumer-state", mapOf("state" to "PAUSED"))
                }
                "resume", "unpause" -> {
                    consumer.paused.set(false)
                    ctx.messages.send(sender, "consumer-state", mapOf("state" to "ACTIVE"))
                }
                "flush" -> {
                    ctx.scheduler.runAsync {
                        val flushed = consumer.flushNow()
                        ctx.scheduler.runGlobal {
                            ctx.messages.send(sender, "consumer-flushed", mapOf("count" to flushed))
                        }
                    }
                }
                else -> {
                    val state = if (consumer.paused.get()) "PAUSED" else "ACTIVE"
                    ctx.messages.sendRaw(
                        sender,
                        "<aqua>Consumer:</aqua> <white>$state</white> <gray>| Pending:</gray> <white>${consumer.pendingCount()}</white> <gray>| Processed:</gray> <white>${consumer.totalProcessed.get()}</white>"
                    )
                }
            }
            true
        }
    }

    private fun parseLookupArgs(player: Player, args: Array<out String>): LookupFilter {
        val loc = player.location
        return AuditFilterParser.parseArgs(
            args = args,
            defaultWorld = loc.world.name,
            centerX = loc.blockX,
            centerY = loc.blockY,
            centerZ = loc.blockZ,
            maxRadius = ctx.config.maxRollbackRadius,
        )
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        ctx.messages.send(sender, "player-only")
        return null
    }
}
