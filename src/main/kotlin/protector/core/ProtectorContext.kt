package protector.core

import org.bukkit.plugin.java.JavaPlugin
import protector.core.command.CommandAliasService
import protector.core.command.CommandRegistry
import protector.core.config.GuardSettings
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.module.ModuleRegistry
import protector.core.scheduler.PluginScheduler
import protector.core.storage.DatabaseManager
import protector.core.ui.ActionBarService
import protector.core.ui.BossBarService
import protector.core.ui.DialogKit
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ProtectorContext(override val plugin: JavaPlugin) : CoreHost {

    @Volatile
    var config: ProtectorConfig = ProtectorConfig.load(plugin)
        private set

    override val messages = MessageService(plugin).apply { reload(config.language) }
    override val scheduler = PluginScheduler(plugin)
    val database = DatabaseManager(File(plugin.dataFolder, config.databaseFile), config.databasePoolSize, "Protector-SQLite")

    override val bossBar = BossBarService(scheduler) { config.bossBarEnabled }
    override val actionBar = ActionBarService(scheduler, { config.actionBarSettings() }, messages)
    val dialogs = DialogKit(messages)

    val modules = ModuleRegistry(plugin) { id, defaultEnabled -> config.isModuleEnabled(id, defaultEnabled) }
    override val commandRegistry = CommandRegistry()

    val adminBypassPlayers: MutableSet<UUID> = ConcurrentHashMap.newKeySet()

    override val pluginDisplayName: String = "Protector"
    override val rootCommandName: String = "protector"
    override val rootAliases: Set<String> = setOf("pr", "pt")
    override val registerStandaloneAliases: Boolean get() = config.registerStandaloneAliases
    override val adminPermission: String = "protector.admin"
    override val customAliasPermissionPrefix: String = "protector.customalias."
    override val defaultCustomAliases: Map<String, List<String>> = linkedMapOf(
        "auditnear" to listOf("player: protector near 15"),
        "tntguard" to listOf("player: protector guard"),
        "prhelp" to listOf(
            "msg: <aqua><bold>Protector Guide:</bold></aqua> <gray>Use <white>/pr</white> for the dashboard, <white>/inspect</white> for block history, or <white>/guard</white> for world rules.</gray>"
        ),
    )

    val commandAliases = CommandAliasService(this)

    fun updateGuardSettings(transform: (GuardSettings) -> GuardSettings) {
        val nextGuard = transform(config.guard)
        config = config.copy(guard = nextGuard)
        val raw = plugin.config
        raw.set("guard.protect-death-drops", nextGuard.protectDeathDrops)
        raw.set("guard.tnt.prevent-prime", nextGuard.tntPreventPrime)
        raw.set("guard.tnt.prevent-block-damage", nextGuard.tntPreventBlockDamage)
        raw.set("guard.tnt.prevent-entity-damage", nextGuard.tntPreventEntityDamage)
        raw.set("guard.tnt.prevent-minecart-tnt", nextGuard.tntPreventMinecart)
        raw.set("guard.explosions.prevent-creeper-block-damage", nextGuard.preventCreeperBlockDamage)
        raw.set("guard.explosions.prevent-wither-damage", nextGuard.preventWitherDamage)
        raw.set("guard.explosions.prevent-fireball-block-damage", nextGuard.preventFireballBlockDamage)
        raw.set("guard.explosions.prevent-end-crystal-damage", nextGuard.preventEndCrystalDamage)
        raw.set("guard.explosions.prevent-bed-anchor-explode", nextGuard.preventBedAnchorExplode)
        raw.set("guard.fire.prevent-fire-spread", nextGuard.preventFireSpread)
        raw.set("guard.fire.prevent-block-burn", nextGuard.preventBlockBurn)
        raw.set("guard.fire.prevent-lava-fire-spread", nextGuard.preventLavaFireSpread)
        raw.set("guard.fire.prevent-lightning-fire", nextGuard.preventLightningFire)
        raw.set("guard.mobs.prevent-enderman-grief", nextGuard.preventEndermanGrief)
        raw.set("guard.mobs.prevent-ravager-grief", nextGuard.preventRavagerGrief)
        raw.set("guard.mobs.prevent-wither-block-break", nextGuard.preventWitherBlockBreak)
        raw.set("guard.world.prevent-non-player-portals", nextGuard.preventNonPlayerPortals)
        runCatching { plugin.saveConfig() }
    }

    override fun reloadAll() {
        config = ProtectorConfig.load(plugin)
        messages.reload(config.language)
        actionBar.start()
        modules.reloadAll()
        commandAliases.reload()
    }

    fun shutdown() {
        commandAliases.shutdown()
        modules.disableAll()
        actionBar.stop()
        bossBar.clearAll()
        scheduler.shutdown()
        database.close()
    }
}
