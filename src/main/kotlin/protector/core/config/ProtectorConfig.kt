package protector.core.config

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import protector.core.ui.ActionBarSettings
import java.util.Locale

data class GuardSettings(
    val enabledWorlds: Set<String> = emptySet(),
    val protectDeathDrops: Boolean = true,
    val tntPreventPrime: Boolean = false,
    val tntPreventBlockDamage: Boolean = true,
    val tntPreventEntityDamage: Boolean = false,
    val tntPreventMinecart: Boolean = true,
    val preventCreeperBlockDamage: Boolean = true,
    val preventWitherDamage: Boolean = true,
    val preventFireballBlockDamage: Boolean = true,
    val preventEndCrystalDamage: Boolean = false,
    val preventBedAnchorExplode: Boolean = false,
    val preventFireSpread: Boolean = true,
    val preventBlockBurn: Boolean = true,
    val preventLavaFireSpread: Boolean = true,
    val preventLightningFire: Boolean = true,
    val preventEndermanGrief: Boolean = true,
    val preventRavagerGrief: Boolean = true,
    val preventWitherBlockBreak: Boolean = true,
    val preventNonPlayerPortals: Boolean = false,
) {
    fun isWorldEnabled(worldName: String): Boolean =
        enabledWorlds.isEmpty() || enabledWorlds.contains(worldName)
}

data class ProtectorConfig(
    val language: String = "en",
    val registerStandaloneAliases: Boolean = true,
    val moduleEnabledMap: Map<String, Boolean> = emptyMap(),
    val databaseFile: String = "protector.db",
    val databasePoolSize: Int = 4,
    val consumerBatchSize: Int = 500,
    val consumerFlushIntervalMs: Long = 500L,
    val guard: GuardSettings = GuardSettings(),
    val auditEnabled: Boolean = true,
    val logBlocks: Boolean = true,
    val logContainers: Boolean = true,
    val logHoppers: Boolean = true,
    val logNatural: Boolean = true,
    val logInteractions: Boolean = true,
    val logEntities: Boolean = true,
    val logSigns: Boolean = true,
    val logChat: Boolean = true,
    val logCommands: Boolean = true,
    val logSessions: Boolean = true,
    val rollbackContainers: Boolean = true,
    val rollbackEntities: Boolean = true,
    val defaultLookupLimit: Int = 50,
    val maxRollbackRadius: Int = 100,
    val rollbackBlocksPerTick: Int = 1500,
    val previewDurationSeconds: Int = 60,
    val actionBarEnabled: Boolean = true,
    val actionBarRefreshTicks: Long = 10L,
    val denialCooldownMs: Long = 1500L,
    val bossBarEnabled: Boolean = true,
    val bossBarModeBanner: Boolean = true,
) {
    fun isModuleEnabled(id: String, defaultEnabled: Boolean = true): Boolean =
        moduleEnabledMap[id.lowercase(Locale.ROOT)] ?: defaultEnabled

    fun actionBarSettings(): ActionBarSettings = ActionBarSettings(
        enabled = actionBarEnabled,
        refreshTicks = actionBarRefreshTicks,
        denialCooldownMs = denialCooldownMs,
    )

    companion object {
        fun load(plugin: JavaPlugin): ProtectorConfig {
            plugin.saveDefaultConfig()
            plugin.reloadConfig()
            val cfg: FileConfiguration = plugin.config

            val moduleFlags = HashMap<String, Boolean>()
            val modSec = cfg.getConfigurationSection("modules")
            if (modSec != null) {
                for (key in modSec.getKeys(false)) {
                    moduleFlags[key.lowercase(Locale.ROOT)] = modSec.getBoolean("$key.enabled", true)
                }
            }

            val guard = GuardSettings(
                enabledWorlds = cfg.getStringList("guard.enabled-worlds").toSet(),
                protectDeathDrops = cfg.getBoolean("guard.protect-death-drops", true),
                tntPreventPrime = cfg.getBoolean("guard.tnt.prevent-prime", false),
                tntPreventBlockDamage = cfg.getBoolean("guard.tnt.prevent-block-damage", true),
                tntPreventEntityDamage = cfg.getBoolean("guard.tnt.prevent-entity-damage", false),
                tntPreventMinecart = cfg.getBoolean("guard.tnt.prevent-minecart-tnt", true),
                preventCreeperBlockDamage = cfg.getBoolean("guard.explosions.prevent-creeper-block-damage", true),
                preventWitherDamage = cfg.getBoolean("guard.explosions.prevent-wither-damage", true),
                preventFireballBlockDamage = cfg.getBoolean("guard.explosions.prevent-fireball-block-damage", true),
                preventEndCrystalDamage = cfg.getBoolean("guard.explosions.prevent-end-crystal-damage", false),
                preventBedAnchorExplode = cfg.getBoolean("guard.explosions.prevent-bed-anchor-explode", false),
                preventFireSpread = cfg.getBoolean("guard.fire.prevent-fire-spread", true),
                preventBlockBurn = cfg.getBoolean("guard.fire.prevent-block-burn", true),
                preventLavaFireSpread = cfg.getBoolean("guard.fire.prevent-lava-fire-spread", true),
                preventLightningFire = cfg.getBoolean("guard.fire.prevent-lightning-fire", true),
                preventEndermanGrief = cfg.getBoolean("guard.mobs.prevent-enderman-grief", true),
                preventRavagerGrief = cfg.getBoolean("guard.mobs.prevent-ravager-grief", true),
                preventWitherBlockBreak = cfg.getBoolean("guard.mobs.prevent-wither-block-break", true),
                preventNonPlayerPortals = cfg.getBoolean("guard.world.prevent-non-player-portals", false),
            )

            return ProtectorConfig(
                language = cfg.getString("language", "en") ?: "en",
                registerStandaloneAliases = cfg.getBoolean("command-aliases.register-standalone-aliases", true),
                moduleEnabledMap = moduleFlags,
                databaseFile = cfg.getString("database.file", "protector.db") ?: "protector.db",
                databasePoolSize = cfg.getInt("database.pool-size", 4).coerceAtLeast(1),
                consumerBatchSize = cfg.getInt("database.consumer-batch-size", 500).coerceAtLeast(50),
                consumerFlushIntervalMs = cfg.getLong("database.consumer-flush-interval-ms", 500L).coerceAtLeast(100L),
                guard = guard,
                auditEnabled = cfg.getBoolean("audit.enabled", true),
                logBlocks = cfg.getBoolean("audit.log-blocks", true),
                logContainers = cfg.getBoolean("audit.log-containers", true),
                logHoppers = cfg.getBoolean("audit.log-hoppers", true),
                logNatural = cfg.getBoolean("audit.log-natural", true),
                logInteractions = cfg.getBoolean("audit.log-interactions", true),
                logEntities = cfg.getBoolean("audit.log-entities", true),
                logSigns = cfg.getBoolean("audit.log-signs", true),
                logChat = cfg.getBoolean("audit.log-chat", true),
                logCommands = cfg.getBoolean("audit.log-commands", true),
                logSessions = cfg.getBoolean("audit.log-sessions", true),
                rollbackContainers = cfg.getBoolean("audit.rollback-containers", true),
                rollbackEntities = cfg.getBoolean("audit.rollback-entities", true),
                defaultLookupLimit = cfg.getInt("audit.default-lookup-limit", 50).coerceIn(5, 500),
                maxRollbackRadius = cfg.getInt("audit.max-rollback-radius", 100).coerceIn(5, 500),
                rollbackBlocksPerTick = cfg.getInt("audit.rollback-blocks-per-tick", 1500).coerceIn(50, 20000),
                previewDurationSeconds = cfg.getInt("audit.preview-duration-seconds", 60).coerceAtLeast(10),
                actionBarEnabled = cfg.getBoolean("ui.actionbar.enabled", true),
                actionBarRefreshTicks = cfg.getLong("ui.actionbar.refresh-ticks", 10L).coerceAtLeast(2L),
                denialCooldownMs = cfg.getLong("ui.actionbar.denial-cooldown-ms", 1500L).coerceAtLeast(200L),
                bossBarEnabled = cfg.getBoolean("ui.bossbar.enabled", true),
                bossBarModeBanner = cfg.getBoolean("ui.bossbar.show-mode-banner", true),
            )
        }
    }
}
