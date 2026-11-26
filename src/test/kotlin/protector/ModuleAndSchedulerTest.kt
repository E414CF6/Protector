package protector

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import protector.core.command.CommandRegistry
import protector.core.config.ProtectorConfig
import protector.core.module.FeatureModule
import protector.core.module.ModuleRegistry
import protector.core.scheduler.PluginScheduler
import java.lang.reflect.Proxy
import java.util.logging.Logger

class ModuleAndSchedulerTest {

    @Test
    fun `ModuleRegistry honors config module flags, subcommand registration, reload, and reverse disable`() {
        val lifecycleEvents = mutableListOf<String>()
        val cfg = ProtectorConfig(
            moduleEnabledMap = mapOf(
                "guard" to true,
                "audit" to false
            )
        )
        val registry = ModuleRegistry(proxyPlugin()) { id, def -> cfg.isModuleEnabled(id, def) }
        val commandRegistry = CommandRegistry()

        val guardModule = object : FeatureModule {
            override val id = "guard"
            override val description = "Guard module"
            override fun onEnable() { lifecycleEvents += "enable:guard" }
            override fun onReload() { lifecycleEvents += "reload:guard" }
            override fun onDisable() { lifecycleEvents += "disable:guard" }
            override fun registerSubcommands(registry: CommandRegistry) {
                registry.registerSubcommand("guard", "Configure guard", "protector.guard") { _, _ -> true }
            }
        }

        val auditModule = object : FeatureModule {
            override val id = "audit"
            override val description = "Audit module"
            override fun onEnable() { lifecycleEvents += "enable:audit" }
            override fun registerSubcommands(registry: CommandRegistry) {
                registry.registerSubcommand("inspect", "Inspect", "protector.inspect") { _, _ -> true }
            }
        }

        val extraModule = object : FeatureModule {
            override val id = "extra"
            override val description = "Extra module"
            override fun onEnable() { lifecycleEvents += "enable:extra" }
            override fun onDisable() { lifecycleEvents += "disable:extra" }
        }

        registry.register(guardModule)
        registry.register(auditModule)
        registry.register(extraModule)

        registry.enableAll()
        assertTrue(registry.isActive("guard"))
        assertFalse(registry.isActive("audit"))
        assertTrue(registry.isActive("extra"))

        registry.registerAllSubcommands(commandRegistry)
        assertNotNull(commandRegistry.getSubcommand("guard"))
        assertNull(commandRegistry.getSubcommand("inspect"))

        registry.reloadAll()
        registry.disableAll()
        assertFalse(registry.isActive("guard"))
        assertEquals(
            listOf("enable:guard", "enable:extra", "reload:guard", "disable:extra", "disable:guard"),
            lifecycleEvents
        )
    }

    @Test
    fun `PluginScheduler constructs safely headless and shuts down cleanly`() {
        val scheduler = PluginScheduler(null)
        assertFalse(scheduler.isFolia)
        scheduler.shutdown()
    }

    private fun proxyPlugin(): Plugin {
        val logger = Logger.getLogger("ModuleAndSchedulerTest")
        return Proxy.newProxyInstance(
            Plugin::class.java.classLoader,
            arrayOf(Plugin::class.java)
        ) { _, method, _ ->
            when (method.name) {
                "getLogger" -> logger
                "getName" -> "ProtectorTest"
                else -> null
            }
        } as Plugin
    }
}
