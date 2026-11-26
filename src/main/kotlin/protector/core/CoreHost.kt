package protector.core

import org.bukkit.plugin.java.JavaPlugin
import protector.core.command.CommandRegistry
import protector.core.locale.MessageService
import protector.core.scheduler.PluginScheduler
import protector.core.ui.ActionBarService
import protector.core.ui.BossBarService

interface CoreHost {
    val plugin: JavaPlugin
    val scheduler: PluginScheduler
    val messages: MessageService
    val actionBar: ActionBarService
    val bossBar: BossBarService
    val commandRegistry: CommandRegistry

    val pluginDisplayName: String
    val rootCommandName: String
    val rootAliases: Set<String>
    val registerStandaloneAliases: Boolean
    val adminPermission: String
    val customAliasPermissionPrefix: String
    val defaultCustomAliases: Map<String, List<String>>

    fun reloadAll()
}
