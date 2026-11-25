package protector

import org.bukkit.plugin.Plugin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import protector.core.command.ProtectorCommandRegistry
import protector.core.config.ProtectorConfig
import protector.core.module.ModuleRegistry
import protector.core.module.ProtectorModule
import protector.core.scheduler.ProtectorScheduler
import java.lang.reflect.Proxy
import java.util.logging.Logger

class ModuleAndSchedulerTest {

    @Test
    fun `ModuleRegistry honors config module flags, subcommand registration, reload, and reverse disable`() {
        val lifecycleEvents = mutableListOf<String>()
        val cfg = ProtectorConfig(
            moduleEnabledMap = mapOf(
                "claim" to true,
                "audit" to false
            )
        )
        val registry = ModuleRegistry(proxyPlugin()) { cfg }
        val commandRegistry = ProtectorCommandRegistry()

        val claimModule = object : ProtectorModule {
            override val id = "claim"
            override val description = "Claim module"
            override fun onEnable() {
                lifecycleEvents += "enable:claim"
            }

            override fun onReload() {
                lifecycleEvents += "reload:claim"
            }

            override fun onDisable() {
                lifecycleEvents += "disable:claim"
            }

            override fun registerSubcommands(registry: ProtectorCommandRegistry) {
                registry.registerSubcommand("claim", "Create claim", "protector.claim") { _, _ -> true }
            }
        }

        val auditModule = object : ProtectorModule {
            override val id = "audit"
            override val description = "Audit module"
            override fun onEnable() {
                lifecycleEvents += "enable:audit"
            }

            override fun onReload() {
                lifecycleEvents += "reload:audit"
            }

            override fun onDisable() {
                lifecycleEvents += "disable:audit"
            }

            override fun registerSubcommands(registry: ProtectorCommandRegistry) {
                registry.registerSubcommand("inspect", "Inspect", "protector.inspect") { _, _ -> true }
            }
        }

        val extraModule = object : ProtectorModule {
            override val id = "extra"
            override val description = "Extra module"
            override fun onEnable() {
                lifecycleEvents += "enable:extra"
            }

            override fun onDisable() {
                lifecycleEvents += "disable:extra"
            }
        }

        registry.register(claimModule)
        registry.register(auditModule)
        registry.register(extraModule)

        registry.enableAll()
        assertTrue(registry.isActive("claim"))
        assertFalse(registry.isActive("audit"))
        assertTrue(registry.isActive("extra"))

        registry.registerAllSubcommands(commandRegistry)
        assertNotNull(commandRegistry.getSubcommand("claim"))
        assertEquals(null, commandRegistry.getSubcommand("inspect"))

        registry.reloadAll()
        registry.disableAll()
        assertFalse(registry.isActive("claim"))
        assertEquals(
            listOf("enable:claim", "enable:extra", "reload:claim", "disable:extra", "disable:claim"),
            lifecycleEvents
        )
    }

    @Test
    fun `ProtectorScheduler constructs safely headless and shuts down cleanly`() {
        val scheduler = ProtectorScheduler(null)
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
