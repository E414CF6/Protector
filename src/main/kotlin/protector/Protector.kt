package protector

import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import protector.core.ProtectorContext
import protector.core.command.ProtectorCommand
import protector.core.compat.PlatformCompat
import protector.modules.audit.AuditModule
import protector.modules.claim.ClaimModule

/**
 * Protector plugin entrypoint bootstrapping [ProtectorContext], feature modules, and dynamic command aliases
 * (modeled after Obsidian's ObsidianPlugin).
 */
class Protector : JavaPlugin() {

    var context: ProtectorContext? = null
        private set

    override fun onEnable() {
        instance = this
        dataFolder.mkdirs()

        val ctx = ProtectorContext(this)
        context = ctx
        Bukkit.getServicesManager().register(ProtectorContext::class.java, ctx, this, ServicePriority.Normal)

        val pm = server.pluginManager
        pm.registerEvents(ctx.menus, this)
        pm.registerEvents(ctx.commandAliases, this)
        ctx.actionBar.start()

        // Register feature modules in dependency order
        ctx.modules.register(ClaimModule(ctx))
        ctx.modules.register(AuditModule(ctx))
        ctx.modules.enableAll()

        // Populate central subcommand registry & initialize dynamic CommandMap aliases
        ctx.commandRegistry.clear()
        val rootExecutor = ProtectorCommand(ctx)
        rootExecutor.registerCoreSubcommands()
        ctx.modules.registerAllSubcommands(ctx.commandRegistry)

        getCommand("protector")?.let {
            it.setExecutor(rootExecutor)
            it.tabCompleter = rootExecutor
        }

        ctx.commandAliases.initializeFromRegistry()

        val uiMode = if (PlatformCompat.supportsDialogs) "Native Paper Dialogs" else "Legacy GUI Fallback"
        logger.info(
            "Protector enabled on MC ${PlatformCompat.serverVersionLabel()} ($uiMode)! " +
                "Modules: ${ctx.modules.activeModules().size}, Aliases: ${ctx.commandAliases.getAllAliases().size}"
        )
    }

    override fun onDisable() {
        context?.shutdown()
        runCatching { Bukkit.getServicesManager().unregisterAll(this) }
        context = null
        instance = null
    }

    companion object {
        @Volatile
        var instance: Protector? = null
            private set
    }
}
