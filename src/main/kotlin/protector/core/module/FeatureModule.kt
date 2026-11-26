package protector.core.module

import org.bukkit.Bukkit
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import protector.core.command.CommandRegistry

interface FeatureModule {
    val id: String
    val description: String
    val enabledByDefault: Boolean get() = true

    fun onEnable() {}
    fun onDisable() {}
    fun onReload() {}

    fun listeners(): List<Listener> = emptyList()
    fun registerSubcommands(registry: CommandRegistry) {}
}

class ModuleRegistry(
    private val plugin: Plugin,
    private val enabledProvider: (id: String, defaultEnabled: Boolean) -> Boolean,
) {
    private val registered = LinkedHashMap<String, FeatureModule>()
    private val active = LinkedHashMap<String, FeatureModule>()
    private val activeListeners = mutableListOf<Listener>()

    fun register(module: FeatureModule) {
        require(!registered.containsKey(module.id)) { "Duplicate module id: ${module.id}" }
        registered[module.id] = module
    }

    fun enableAll() {
        for ((id, module) in registered) {
            if (!enabledProvider(id, module.enabledByDefault)) {
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

    fun registerAllSubcommands(registry: CommandRegistry) {
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

    fun activeModules(): Collection<FeatureModule> = active.values.toList()
}
