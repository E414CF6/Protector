package protector.core.module

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import protector.core.command.ProtectorCommandRegistry
import protector.core.config.ProtectorConfig

/**
 * Contract for self-contained feature modules in Protector (modeled after Obsidian's ObsidianModule).
 */
interface ProtectorModule {
    val id: String
    val description: String
    val enabledByDefault: Boolean get() = true

    fun onEnable() {}
    fun onDisable() {}
    fun onReload() {}

    fun listeners(): List<Listener> = emptyList()
    fun registerSubcommands(registry: ProtectorCommandRegistry) {}
}

/**
 * Manages ordered registration, configuration gating, listener lifecycle, and reload of [ProtectorModule]s.
 */
class ModuleRegistry(
    private val plugin: Plugin,
    private val configProvider: () -> ProtectorConfig,
) {
    private val registered = LinkedHashMap<String, ProtectorModule>()
    private val active = LinkedHashMap<String, ProtectorModule>()
    private val activeListeners = mutableListOf<Listener>()

    fun register(module: ProtectorModule) {
        require(!registered.containsKey(module.id)) { "Duplicate module id: ${module.id}" }
        registered[module.id] = module
    }

    fun enableAll() {
        val cfg = configProvider()
        for ((id, module) in registered) {
            if (!cfg.isModuleEnabled(id, module.enabledByDefault)) {
                plugin.logger.info("Module '$id' is disabled in config.yml.")
                continue
            }
            runCatching {
                module.onEnable()
                module.listeners().forEach { listener ->
                    Bukkit.getPluginManager().registerEvents(listener, plugin)
                    activeListeners += listener
                }
                active[id] = module
                plugin.logger.info("Enabled module '$id' (${module.description}).")
            }.onFailure { err ->
                plugin.logger.severe("Failed to enable module '$id': ${err.message}")
                err.printStackTrace()
            }
        }
    }

    fun registerAllSubcommands(registry: ProtectorCommandRegistry) {
        for (module in active.values) {
            runCatching { module.registerSubcommands(registry) }.onFailure { err ->
                plugin.logger.severe("Failed to register subcommands for module '${module.id}': ${err.message}")
                err.printStackTrace()
            }
        }
    }

    fun reloadAll() {
        for (module in active.values) {
            runCatching { module.onReload() }.onFailure { err ->
                plugin.logger.severe("Failed to reload module '${module.id}': ${err.message}")
                err.printStackTrace()
            }
        }
    }

    fun disableAll() {
        activeListeners.forEach { HandlerList.unregisterAll(it) }
        activeListeners.clear()
        for (module in active.values.reversed()) {
            runCatching { module.onDisable() }.onFailure { err ->
                plugin.logger.severe("Error disabling module '${module.id}': ${err.message}")
                err.printStackTrace()
            }
        }
        active.clear()
    }

    fun isActive(id: String): Boolean = active.containsKey(id)

    fun activeModules(): Collection<ProtectorModule> = active.values.toList()
}
