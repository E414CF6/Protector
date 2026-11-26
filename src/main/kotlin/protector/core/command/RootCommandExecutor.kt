package protector.core.command

import org.bukkit.command.Command
import org.bukkit.command.CommandExecutor
import org.bukkit.command.CommandSender
import org.bukkit.command.TabCompleter
import org.bukkit.entity.Player
import protector.core.CoreHost
import java.util.Locale

class RootCommandExecutor(
    private val host: CoreHost,
    private val commandAliases: CommandAliasService,
    private val userPermission: String,
    private val openDashboard: (Player) -> Unit,
    private val openAdminPanel: (Player) -> Unit,
    private val statusReporter: (CommandSender) -> Unit,
) : CommandExecutor, TabCompleter {

    fun registerCoreSubcommands() {
        val reg = host.commandRegistry
        val short = host.rootAliases.firstOrNull() ?: host.rootCommandName

        reg.registerSubcommand(
            name = "menu",
            description = "Open the ${host.pluginDisplayName} Dashboard GUI",
            permission = userPermission,
            aliases = listOf("gui"),
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                host.messages.send(sender, "player-only")
                return@registerSubcommand true
            }
            openDashboard(player)
            true
        }

        reg.registerSubcommand(
            name = "admin",
            description = "Open the Admin Control Panel GUI",
            permission = host.adminPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            val player = sender as? Player ?: run {
                host.messages.send(sender, "player-only")
                return@registerSubcommand true
            }
            openAdminPanel(player)
            true
        }

        reg.registerSubcommand(
            name = "status",
            description = "View ${host.pluginDisplayName} module and service status",
            permission = host.adminPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            statusReporter(sender)
            true
        }

        reg.registerSubcommand(
            name = "aliases",
            description = "List active standalone and custom aliases",
            permission = host.adminPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            val regular = commandAliases.getRegularAliases()
            val custom = commandAliases.getCustomAliases()
            val enabledRegular = regular.values.count { it.enabled }
            host.messages.sendRaw(
                sender,
                "<aqua>Dynamic Aliases:</aqua> <white>$enabledRegular/${regular.size}</white> standalone, <white>${custom.size}</white> custom."
            )
            if (custom.isNotEmpty()) {
                for ((k, cmds) in custom) {
                    host.messages.sendRaw(
                        sender,
                        "<gray>- /$k -></gray> <white>${cmds.joinToString(" | ")}</white>",
                        withPrefix = false
                    )
                }
            }
            true
        }

        reg.registerSubcommand(
            name = "alias",
            description = "Manage custom command aliases (add/remove/list)",
            permission = host.adminPermission,
            standaloneByDefault = false,
            tabCompleter = SubcommandTabCompleter { _, args ->
                when (args.size) {
                    0, 1 -> listOf("add", "remove", "list")
                        .filter { it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true) }

                    2 -> if (args[0].equals("remove", ignoreCase = true)) {
                        commandAliases.getCustomAliases().keys.filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()

                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.isEmpty() || args[0].equals("list", ignoreCase = true)) {
                val custom = commandAliases.getCustomAliases()
                host.messages.sendRaw(
                    sender,
                    "<aqua>Custom Aliases (${custom.size}):</aqua> <white>${custom.keys.joinToString(", ").ifEmpty { "none" }}</white>"
                )
                return@registerSubcommand true
            }
            when (args[0].lowercase(Locale.ROOT)) {
                "add" -> {
                    if (args.size < 3) {
                        host.messages.sendRaw(sender, "<yellow>Usage: /$short alias add <name> <command...></yellow>")
                        return@registerSubcommand true
                    }
                    val aliasName = args[1]
                    val targetCmd = args.drop(2).joinToString(" ")
                    commandAliases.setCustomAlias(aliasName, listOf(targetCmd))
                    host.messages.sendRaw(
                        sender,
                        "<green>Registered custom alias <white>/$aliasName</white> -> <aqua>$targetCmd</aqua></green>"
                    )
                }

                "remove", "del", "delete" -> {
                    if (args.size < 2) {
                        host.messages.sendRaw(sender, "<yellow>Usage: /$short alias remove <name></yellow>")
                        return@registerSubcommand true
                    }
                    val aliasName = args[1]
                    if (commandAliases.removeCustomAlias(aliasName)) {
                        host.messages.sendRaw(sender, "<yellow>Removed custom alias <white>/$aliasName</white>.</yellow>")
                    } else {
                        host.messages.sendRaw(sender, "<red>Custom alias <white>/$aliasName</white> not found.</red>")
                    }
                }

                else -> host.messages.sendRaw(sender, "<yellow>Usage: /$short alias <add|remove|list></yellow>")
            }
            true
        }

        reg.registerSubcommand(
            name = "reload",
            description = "Reload ${host.pluginDisplayName} config, messages, modules, and command aliases",
            permission = host.adminPermission,
            standaloneByDefault = false,
        ) { sender, _ ->
            host.reloadAll()
            host.messages.send(sender, "reload-done")
            true
        }
    }

    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {
        if (args.isEmpty()) {
            val player = sender as? Player ?: run {
                host.messages.send(sender, "player-only")
                return true
            }
            openDashboard(player)
            return true
        }

        val subKey = args[0].lowercase(Locale.ROOT)
        val sub = host.commandRegistry.getSubcommand(subKey)
        if (sub == null) {
            val short = host.rootAliases.firstOrNull() ?: host.rootCommandName
            val available = host.commandRegistry.allSubcommands().joinToString(", ") { it.name }
            host.messages.sendRaw(sender, "<aqua>/$short</aqua> <gray>(Dashboard GUI) |</gray> <white>$available</white>")
            return true
        }

        if (commandAliases.isBaseDisabled(sub.name)) {
            host.messages.send(sender, "command-disabled", mapOf("command" to sub.name))
            return true
        }

        if (sub.permission != null && !sender.hasPermission(sub.permission)) {
            host.messages.send(sender, "no-permission")
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
            return host.commandRegistry.allSubcommands()
                .filter { !commandAliases.isBaseDisabled(it.name) }
                .filter { it.permission == null || sender.hasPermission(it.permission) }
                .map { it.name }
                .filter { it.startsWith(prefix, ignoreCase = true) }
        }

        val sub = host.commandRegistry.getSubcommand(args[0]) ?: return emptyList()
        if (commandAliases.isBaseDisabled(sub.name)) return emptyList()
        if (sub.permission != null && !sender.hasPermission(sub.permission)) return emptyList()
        return sub.tabCompleter?.complete(sender, args.drop(1).toTypedArray()).orEmpty()
    }
}
