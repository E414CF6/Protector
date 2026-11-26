package protector.core.command

import org.bukkit.command.CommandSender
import java.util.Locale

fun interface SubcommandExecutor {
    fun execute(sender: CommandSender, args: Array<out String>): Boolean
}

fun interface SubcommandTabCompleter {
    fun complete(sender: CommandSender, args: Array<out String>): List<String>
}

data class RegisteredSubcommand(
    val name: String,
    val description: String,
    val permission: String?,
    val aliases: List<String>,
    val standaloneByDefault: Boolean = true,
    val executor: SubcommandExecutor,
    val tabCompleter: SubcommandTabCompleter? = null,
)

class CommandRegistry {

    private val subcommands = LinkedHashMap<String, RegisteredSubcommand>()
    private val aliasLookup = LinkedHashMap<String, String>()

    fun clear() {
        subcommands.clear()
        aliasLookup.clear()
    }

    fun registerSubcommand(
        name: String,
        description: String,
        permission: String? = null,
        aliases: Collection<String> = emptyList(),
        standaloneByDefault: Boolean = true,
        tabCompleter: SubcommandTabCompleter? = null,
        executor: SubcommandExecutor,
    ) {
        val key = name.trim().lowercase(Locale.ROOT)
        val cleanAliases = aliases.map { it.trim().lowercase(Locale.ROOT) }
            .filter { it.isNotEmpty() && it != key }
            .distinct()

        subcommands[key] = RegisteredSubcommand(
            name = key,
            description = description,
            permission = permission,
            aliases = cleanAliases,
            standaloneByDefault = standaloneByDefault,
            executor = executor,
            tabCompleter = tabCompleter,
        )
        aliasLookup[key] = key
        for (alias in cleanAliases) {
            aliasLookup.putIfAbsent(alias, key)
        }
    }

    fun allSubcommands(): List<RegisteredSubcommand> = subcommands.values.toList()

    fun getSubcommand(nameOrAlias: String): RegisteredSubcommand? {
        val key = nameOrAlias.trim().lowercase(Locale.ROOT)
        val primary = aliasLookup[key] ?: return null
        return subcommands[primary]
    }
}
