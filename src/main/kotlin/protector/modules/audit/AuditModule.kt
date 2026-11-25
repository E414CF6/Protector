package protector.modules.audit

import org.bukkit.Material
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import protector.core.ProtectorContext
import protector.core.command.ProtectorCommandRegistry
import protector.core.command.SubcommandTabCompleter
import protector.core.compat.PlatformCompat
import protector.core.module.ProtectorModule
import protector.core.storage.LookupActionFilter
import protector.core.storage.LookupFilter

class AuditModule(private val ctx: ProtectorContext) : ProtectorModule {
    override val id: String = "audit"
    override val description: String = "Forensic block/container/entity/chat audit logging and rollback engine"

    override fun onEnable() {
        ctx.logQueueConsumer.start()
    }

    override fun onDisable() {
        ctx.logQueueConsumer.shutdownAndDrain()
    }

    override fun listeners(): List<Listener> {
        val auditListeners = AuditListeners({ ctx.config }, ctx.logQueueConsumer)
        val result = mutableListOf<Listener>(auditListeners, ctx.inspectorService)
        result += if (PlatformCompat.supportsPaperAsyncChat) {
            PaperChatAuditListener({ ctx.config }, ctx.logQueueConsumer)
        } else {
            LegacyChatAuditListener({ ctx.config }, ctx.logQueueConsumer)
        }
        if (PlatformCompat.supportsTntPrimeEvent) {
            result += TntPrimeAuditListener({ ctx.config }, auditListeners)
        }
        if (PlatformCompat.supportsTakeLecternBookEvent) {
            result += LecternAuditListener({ ctx.config }, ctx.logQueueConsumer)
        }
        return result
    }

    override fun registerSubcommands(registry: ProtectorCommandRegistry) {
        val filterCompleter = SubcommandTabCompleter { _, args ->
            val last = args.lastOrNull().orEmpty()
            listOf(
                "u:", "t:1h", "t:24h", "r:15", "r:30",
                "a:block", "a:container", "a:entity", "a:sign", "a:chat", "a:session",
                "i:", "e:", "apply"
            ).filter { it.startsWith(last, ignoreCase = true) }
        }

        registry.registerSubcommand(
            name = "inspect",
            description = "Toggle block and container audit inspection mode",
            permission = "protector.inspect",
            aliases = listOf("i", "coi"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            ctx.inspectorService.toggleInspect(player)
            true
        }

        registry.registerSubcommand(
            name = "near",
            description = "Browse nearby audit logs in the GUI",
            permission = "protector.lookup",
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
            ctx.menus.openAuditBrowserAsync(player, filter, 0)
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
                ctx.dialogs.openRollbackFilterDialog(player, restoreMode = false)
                return@registerSubcommand true
            }
            val filter = parseLookupArgs(player, args)
            ctx.menus.openAuditBrowserAsync(player, filter, 0)
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
                ctx.dialogs.openRollbackFilterDialog(player, restoreMode = false)
                return@registerSubcommand true
            }
            val filter = parseLookupArgs(player, args)
            if (args.any { it.equals("apply", ignoreCase = true) || it.equals("now", ignoreCase = true) }) {
                ctx.rollbackService.executeDirect(player, filter, restoreMode = false)
            } else {
                ctx.rollbackService.startPreview(player, filter, restoreMode = false)
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
                ctx.dialogs.openRollbackFilterDialog(player, restoreMode = true)
                return@registerSubcommand true
            }
            val filter = parseLookupArgs(player, args)
            if (args.any { it.equals("apply", ignoreCase = true) || it.equals("now", ignoreCase = true) }) {
                ctx.rollbackService.executeDirect(player, filter, restoreMode = true)
            } else {
                ctx.rollbackService.startPreview(player, filter, restoreMode = true)
            }
            true
        }

        registry.registerSubcommand(
            name = "apply",
            description = "Apply an active rollback or restore preview",
            permission = "protector.rollback",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            if (!ctx.rollbackService.applyPreview(player)) {
                ctx.messages.sendRaw(player, "<yellow>No active rollback or restore preview to apply.</yellow>")
            }
            true
        }

        registry.registerSubcommand(
            name = "cancel",
            description = "Cancel an active rollback preview or claim selection",
            permission = "protector.use",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val cancelledPreview = ctx.rollbackService.cancelPreview(player)
            ctx.claimToolService.cancelSelection(player)
            if (!cancelledPreview) {
                ctx.messages.sendRaw(player, "<gray>Cleared active selection.</gray>")
            }
            true
        }

        registry.registerSubcommand(
            name = "undo",
            description = "Undo your last rollback or restore",
            permission = "protector.rollback",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            ctx.rollbackService.undoLast(player)
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
            val seconds = RollbackService.parseDurationSeconds(durationToken)
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
                    ctx.rollbackService.purgeBefore(p, p, seconds)
                }
            } else {
                ctx.rollbackService.purgeBefore(sender, null, seconds)
            }
            true
        }

        registry.registerSubcommand(
            name = "status",
            description = "View claims and audit log consumer status",
            permission = "protector.inspect",
        ) { sender, _ ->
            val claimsCount = ctx.claimManager.getAllTopLevelClaims().size
            ctx.messages.sendRaw(
                sender,
                "<aqua>Protector Status</aqua> <gray>|</gray> Claims: <white>$claimsCount</white> " +
                        "<gray>|</gray> Pending Queue: <white>${ctx.logQueueConsumer.pendingCount()}</white> " +
                        "<gray>|</gray> Total Logged: <white>${ctx.logQueueConsumer.totalProcessed.get()}</white>"
            )
            true
        }
    }

    private fun parseLookupArgs(player: Player, args: Array<out String>): LookupFilter {
        val cfg = ctx.config
        var user: String? = null
        var seconds = 3600L
        var radius = 15
        var actionFilter = LookupActionFilter.ALL
        val include = HashSet<String>()
        val exclude = HashSet<String>()

        for (arg in args) {
            val idx = arg.indexOf(':')
            if (idx <= 0) continue
            val key = arg.substring(0, idx).lowercase()
            val value = arg.substring(idx + 1)
            when (key) {
                "u", "user" -> user = value
                "t", "time" -> RollbackService.parseDurationSeconds(value)?.let { seconds = it }
                "r", "radius" -> value.toIntOrNull()?.let { radius = it.coerceIn(1, cfg.maxRollbackRadius) }
                "i", "include" -> value.split(',').mapNotNull { Material.matchMaterial(it)?.name }
                    .forEach { include.add(it) }

                "e", "exclude" -> value.split(',').mapNotNull { Material.matchMaterial(it)?.name }
                    .forEach { exclude.add(it) }

                "a", "action" -> actionFilter = when (value.lowercase()) {
                    "block", "blocks" -> LookupActionFilter.BLOCK
                    "container", "chest" -> LookupActionFilter.CONTAINER
                    "entity", "kill" -> LookupActionFilter.ENTITY
                    "sign", "signs" -> LookupActionFilter.SIGN
                    "chat", "command", "cmd" -> LookupActionFilter.CHAT
                    "session", "login" -> LookupActionFilter.SESSION
                    else -> LookupActionFilter.ALL
                }
            }
        }
        val loc = player.location
        return LookupFilter(
            worldName = loc.world.name,
            centerX = loc.blockX,
            centerY = loc.blockY,
            centerZ = loc.blockZ,
            radius = radius,
            sinceEpochSec = (System.currentTimeMillis() / 1000L) - seconds,
            userName = user,
            includeMaterials = include,
            excludeMaterials = exclude,
            actionFilter = actionFilter,
            limit = 2000
        )
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        ctx.messages.send(sender, "player-only")
        return null
    }
}
