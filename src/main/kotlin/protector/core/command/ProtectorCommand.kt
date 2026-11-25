package protector.core.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import protector.core.ProtectorContext
import java.util.Locale

/**
 * Root `/protector` (`/pr`, `/pt`) command executor routing subcommands via [ProtectorCommandRegistry]
 * and honoring `base-disabled` rules from [CommandAliasService].
 */
class ProtectorCommand(private val ctx: ProtectorContext) : CommandExecutor, TabCompleter {

    fun registerCoreSubcommands() {
        val reg = ctx.commandRegistry

        reg.registerSubcommand(
            name = "menu",
            description = "Open the Protector Dashboard GUI",
            permission = "protector.use",
            aliases = listOf("gui"),
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                ctx.messages.send(sender, "player-only")
                return@registerSubcommand true
            }
            ctx.menus.openDashboard(player)
            true
        }

        reg.registerSubcommand(
            name = "admin",
            description = "Open the Admin Control Panel GUI",
            permission = "protector.admin",
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                ctx.messages.send(sender, "player-only")
                return@registerSubcommand true
            }
            ctx.menus.openAdminPanel(player)
            true
        }

        reg.registerSubcommand(
            name = "aliases",
            description = "List active standalone and custom aliases",
            permission = "protector.admin",
        ) { sender, _ ->
            val regular = ctx.commandAliases.getRegularAliases()
            val custom = ctx.commandAliases.getCustomAliases()
            val enabledRegular = regular.values.count { it.enabled }
            ctx.messages.sendRaw(
                sender,
                "<aqua>Dynamic Aliases:</aqua> <white>$enabledRegular/${regular.size}</white> standalone, <white>${custom.size}</white> custom."
            )
            if (custom.isNotEmpty()) {
                for ((k, cmds) in custom) {
                    ctx.messages.sendRaw(sender, "<gray>- /$k -></gray> <white>${cmds.joinToString(" | ")}</white>", withPrefix = false)
                }
            }
            true
        }

        reg.registerSubcommand(
            name = "alias",
            description = "Manage custom command aliases (add/remove/list)",
            permission = "protector.admin",
            tabCompleter = SubcommandTabCompleter { _, args ->
                when (args.size) {
                    0, 1 -> listOf("add", "remove", "list")
                        .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }
                    2 -> if (args[0].equals("remove", ignoreCase = true)) {
                        ctx.commandAliases.getCustomAliases().keys.filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
                val custom = ctx.commandAliases.getCustomAliases()
                ctx.messages.sendRaw(sender, "<aqua>Custom Aliases (${custom.size}):</aqua> <white>${custom.keys.joinToString(", ").ifEmpty { "none" }}</white>")
                return@registerSubcommand true
            }
            when (args[0].lowercase(Locale.ROOT)) {
                "add" -> {
                    if (args.size < 3) {
                        ctx.messages.sendRaw(sender, "<yellow>Usage: /pr alias add <name> <command...></yellow>")
                        return@registerSubcommand true
                    }
                    val aliasName = args[1]
                    val targetCmd = args.drop(2).joinToString(" ")
                    ctx.commandAliases.setCustomAlias(aliasName, listOf(targetCmd))
                    ctx.messages.sendRaw(sender, "<green>Registered custom alias <white>/$aliasName</white> -> <aqua>$targetCmd</aqua></green>")
                }
                "remove", "del", "delete" -> {
                    if (args.size < 2) {
                        ctx.messages.sendRaw(sender, "<yellow>Usage: /pr alias remove <name></yellow>")
                        return@registerSubcommand true
                    }
                    val aliasName = args[1]
                    if (ctx.commandAliases.removeCustomAlias(aliasName)) {
                        ctx.messages.sendRaw(sender, "<yellow>Removed custom alias <white>/$aliasName</white>.</yellow>")
                    } else {
                        ctx.messages.sendRaw(sender, "<red>Custom alias <white>/$aliasName</white> not found.</red>")
                    }
                }
                else -> ctx.messages.sendRaw(sender, "<yellow>Usage: /pr alias <add|remove|list></yellow>")
            }
            true
        }

        reg.registerSubcommand(
            name = "reload",
            description = "Reload Protector config, messages, modules, and command aliases",
            permission = "protector.admin",
        ) { sender, _ ->
            ctx.reloadAll()
            ctx.messages.send(sender, "reload-done")
            true
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player ?: run {
                ctx.messages.send(sender, "player-only")
                return true
            }
            ctx.menus.openDashboard(player)
            return true
        }

        val subKey = args[0].lowercase(Locale.ROOT)
        val sub = ctx.commandRegistry.getSubcommand(subKey)
        if (sub == null) {
            val available = ctx.commandRegistry.allSubcommands().joinToString(", ") { it.name }
            ctx.messages.sendRaw(sender, "<aqua>/pr</aqua> <gray>(Dashboard GUI) |</gray> <white>$available</white>")
            return true
        }

        if (ctx.commandAliases.isBaseDisabled(sub.name)) {
            ctx.messages.send(sender, "command-disabled", mapOf("command" to sub.name))
            return true
        }

        if (sub.permission != null && !sender.hasPermission(sub.permission)) {
            ctx.messages.send(sender, "no-permission")
            return true
        }

        val subArgs = args.drop(1).toTypedArray()
        return sub.executor.execute(sender, subArgs)
    }

    override fun onTabComplete(
        sender: CommandSender,
        command: Command,
        alias: String,
        args: Array<out String>
    ): List<String> {
        if (args.size <= 1) {
            val prefix = args.firstOrNull().orEmpty()
            return ctx.commandRegistry.allSubcommands()
                .filter { !ctx.commandAliases.isBaseDisabled(it.name) }
                .filter { it.permission == null || sender.hasPermission(it.permission) }
                .map { it.name }
                .filter { it.startsWith(prefix, ignoreCase = true) }
        }

        val sub = ctx.commandRegistry.getSubcommand(args[0]) ?: return emptyList()
        if (ctx.commandAliases.isBaseDisabled(sub.name)) return emptyList()
        if (sub.permission != null && !sender.hasPermission(sub.permission)) return emptyList()
        return sub.tabCompleter?.complete(sender, args.drop(1).toTypedArray()).orEmpty()
    }
}
