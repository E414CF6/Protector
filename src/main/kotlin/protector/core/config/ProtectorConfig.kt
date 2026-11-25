package protector.core.config

import org.bukkit.Material
import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.plugin.java.JavaPlugin
import protector.modules.claim.ClaimFlag
import java.util.Locale

data class ProtectorConfig(
    val language: String = "en",
    val registerStandaloneAliases: Boolean = true,
    val moduleEnabledMap: Map<String, Boolean> = emptyMap(),
    val databaseFile: String = "protector.db",
    val databasePoolSize: Int = 4,
    val consumerBatchSize: Int = 500,
    val consumerFlushIntervalMs: Long = 500L,
    val enabledWorlds: Set<String> = emptySet(),
    val claimTool: Material = Material.GOLDEN_SHOVEL,
    val inspectTool: Material = Material.STICK,
    val initialClaimBlocks: Int = 400,
    val accrualBlocksPerHour: Int = 120,
    val maxAccruedBlocks: Int = 80000,
    val minClaimWidth: Int = 5,
    val minClaimArea: Int = 25,
    val autoClaimRadius: Int = 7,
    val abandonRefundRatio: Double = 1.0,
    val visualizationDurationSeconds: Int = 30,
    val protectDeathDrops: Boolean = true,
    val limitTreeGrowth: Boolean = true,
    val preventNonPlayerPortals: Boolean = true,
    val unusedCleanupEnabled: Boolean = false,
    val unusedCleanupInactiveDays: Int = 60,
    val unusedCleanupMaxArea: Int = 2500,
    val defaultFlags: Map<ClaimFlag, Boolean> = mapOf(ClaimFlag.ENTRY_NOTIFY to true),
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
    val bossBarTerritoryBanner: Boolean = false
) {
    fun isWorldEnabled(worldName: String): Boolean =
        enabledWorlds.isEmpty() || enabledWorlds.contains(worldName)

    fun isModuleEnabled(id: String, defaultEnabled: Boolean = true): Boolean =
        moduleEnabledMap[id.lowercase(Locale.ROOT)] ?: defaultEnabled

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

            val flags = mapOf(
                ClaimFlag.PVP to cfg.getBoolean("claims.default-flags.pvp", false),
                ClaimFlag.EXPLOSIONS to cfg.getBoolean("claims.default-flags.explosions", false),
                ClaimFlag.FIRE_SPREAD to cfg.getBoolean("claims.default-flags.fire-spread", false),
                ClaimFlag.MOB_GRIEFING to cfg.getBoolean("claims.default-flags.mob-griefing", false),
                ClaimFlag.PUBLIC_ACCESS to cfg.getBoolean("claims.default-flags.public-access", false),
                ClaimFlag.PISTON_CROSS_BORDER to cfg.getBoolean("claims.default-flags.piston-cross-border", false),
                ClaimFlag.ENTRY_NOTIFY to cfg.getBoolean("claims.default-flags.entry-notify", true)
            )

            return ProtectorConfig(
                language = cfg.getString("language", "en") ?: "en",
                registerStandaloneAliases = cfg.getBoolean("command-aliases.register-standalone-aliases", true),
                moduleEnabledMap = moduleFlags,
                databaseFile = cfg.getString("database.file", "protector.db") ?: "protector.db",
                databasePoolSize = cfg.getInt("database.pool-size", 4).coerceAtLeast(1),
                consumerBatchSize = cfg.getInt("database.consumer-batch-size", 500).coerceAtLeast(50),
                consumerFlushIntervalMs = cfg.getLong("database.consumer-flush-interval-ms", 500L).coerceAtLeast(100L),
                enabledWorlds = cfg.getStringList("claims.enabled-worlds").toSet(),
                claimTool = Material.matchMaterial(cfg.getString("claims.claim-tool") ?: "GOLDEN_SHOVEL")
                    ?: Material.GOLDEN_SHOVEL,
                inspectTool = Material.matchMaterial(cfg.getString("claims.inspect-tool") ?: "STICK")
                    ?: Material.STICK,
                initialClaimBlocks = cfg.getInt("claims.initial-claim-blocks", 400).coerceAtLeast(0),
                accrualBlocksPerHour = cfg.getInt("claims.accrual-blocks-per-hour", 120).coerceAtLeast(0),
                maxAccruedBlocks = cfg.getInt("claims.max-accrued-blocks", 80000).coerceAtLeast(100),
                minClaimWidth = cfg.getInt("claims.min-claim-width", 5).coerceAtLeast(1),
                minClaimArea = cfg.getInt("claims.min-claim-area", 25).coerceAtLeast(1),
                autoClaimRadius = cfg.getInt("claims.auto-claim-radius", 7).coerceAtLeast(2),
                abandonRefundRatio = cfg.getDouble("claims.abandon-refund-ratio", 1.0).coerceIn(0.0, 1.0),
                visualizationDurationSeconds = cfg.getInt("claims.visualization-duration-seconds", 30).coerceAtLeast(5),
                protectDeathDrops = cfg.getBoolean("claims.protect-death-drops", true),
                limitTreeGrowth = cfg.getBoolean("claims.limit-tree-growth", true),
                preventNonPlayerPortals = cfg.getBoolean("claims.prevent-non-player-portals", true),
                unusedCleanupEnabled = cfg.getBoolean("claims.unused-cleanup.enabled", false),
                unusedCleanupInactiveDays = cfg.getInt("claims.unused-cleanup.inactive-days", 60).coerceAtLeast(1),
                unusedCleanupMaxArea = cfg.getInt("claims.unused-cleanup.max-area-to-expire", 2500).coerceAtLeast(1),
                defaultFlags = flags,
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
                bossBarTerritoryBanner = cfg.getBoolean("ui.bossbar.show-territory-banner", false)
            )
        }
    }
}
