package protector

import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import protector.core.ProtectorContext
import protector.core.command.RootCommandExecutor
import protector.core.compat.PlatformCompat
import protector.core.ui.MenuListener
import protector.modules.audit.AuditModule
import protector.modules.guard.GuardModule

class Protector : JavaPlugin() {

    var context: ProtectorContext? = null
        private set

    override fun onEnable() {
        instance = this
        dataFolder.mkdirs()

        val ctx = ProtectorContext(this)
        context = ctx
        Bukkit.getServicesManager().register(ProtectorContext::class.java, ctx, this, ServicePriority.Normal)

        lateinit var dashboard: ProtectorDashboard
        val guardModule = GuardModule(ctx) { dashboard.openDashboard(it) }
        val auditModule = AuditModule(ctx) { dashboard.openDashboard(it) }
        dashboard = ProtectorDashboard(ctx, guardModule, auditModule)

        val pm = server.pluginManager
        pm.registerEvents(MenuListener(), this)
        pm.registerEvents(ctx.commandAliases, this)
        ctx.actionBar.start()

        ctx.modules.register(guardModule)
        ctx.modules.register(auditModule)
        ctx.modules.enableAll()

        ctx.commandRegistry.clear()
        val rootExecutor = RootCommandExecutor(
            host = ctx,
            commandAliases = ctx.commandAliases,
            userPermission = "protector.use",
            openDashboard = { dashboard.openDashboard(it) },
            openAdminPanel = { dashboard.openAdminPanel(it) },
            statusReporter = { sender ->
                ctx.messages.sendRaw(
                    sender,
                    "<aqua>Protector Status</aqua> <gray>|</gray> Guard Rules: <white>${guardModule.activeRuleCount()}</white> " +
                        "<gray>|</gray> Pending Queue: <white>${auditModule.consumer.pendingCount()}</white> " +
                        "<gray>|</gray> Total Logged: <white>${auditModule.consumer.totalProcessed.get()}</white>"
                )
            },
        )
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
