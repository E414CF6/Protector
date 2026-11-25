package protector.core.ui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.scheduler.ProtectorScheduler
import protector.core.scheduler.TaskHandle
import protector.modules.claim.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

data class PlayerSessionState(
    var inspectMode: Boolean = false,
    var shovelMode: ShovelMode = ShovelMode.BASIC,
    var pendingCorner1: Location? = null,
    var resizingClaim: Claim? = null,
    var subdividingParent: Claim? = null,
    var adminBypass: Boolean = false,
    var hudEnabled: Boolean = true,
    var territoryBanner: Boolean = false,
    var lastClaimId: Long? = null,
    var flashUntilMs: Long = 0L,
    var flashMessage: Component? = null,
    var lastDenialMs: Long = 0L
)

class ActionBarHudService(
    private val scheduler: ProtectorScheduler,
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val bossBar: BossBarService
) {
    private val sessions = ConcurrentHashMap<UUID, PlayerSessionState>()
    private var task: TaskHandle? = null
    var hasLockedDrops: ((UUID) -> Boolean)? = null

    fun state(uuid: UUID): PlayerSessionState =
        sessions.computeIfAbsent(uuid) { PlayerSessionState() }

    fun clearSession(uuid: UUID) {
        sessions.remove(uuid)
    }

    fun start() {
        stop()
        val cfg = configProvider()
        if (!cfg.actionBarEnabled) return
        task = scheduler.runGlobalTimer(cfg.actionBarRefreshTicks, cfg.actionBarRefreshTicks) {
            for (player in Bukkit.getOnlinePlayers()) {
                scheduler.runForEntity(player) {
                    tickPlayer(player)
                }
            }
        }
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    fun flash(player: Player, component: Component, durationMs: Long = 2200L) {
        val st = state(player.uniqueId)
        st.flashMessage = component
        st.flashUntilMs = System.currentTimeMillis() + durationMs
        player.sendActionBar(component)
    }

    fun sendDenial(player: Player, key: String, placeholders: Map<String, Any> = emptyMap()) {
        val st = state(player.uniqueId)
        val now = System.currentTimeMillis()
        val cooldown = configProvider().denialCooldownMs
        if (now - st.lastDenialMs < cooldown) return
        st.lastDenialMs = now
        val comp = messages.render(key, placeholders)
        flash(player, comp, 2000L)
    }

    fun tickPlayer(player: Player) {
        val cfg = configProvider()
        if (!cfg.actionBarEnabled) return
        val st = state(player.uniqueId)
        if (!st.hudEnabled) return

        val now = System.currentTimeMillis()
        if (now < st.flashUntilMs && st.flashMessage != null) {
            player.sendActionBar(st.flashMessage!!)
            return
        }

        // Priority 1: Inspector mode
        if (st.inspectMode) {
            player.sendActionBar(messages.render("inspect-hud"))
            return
        }

        // Priority 2: Holding Claim Tool
        val held = player.inventory.itemInMainHand.type
        if (held == cfg.claimTool) {
            val available = claimManager.getAvailableClaimBlocks(player.uniqueId)
            val corner = st.pendingCorner1
            if (corner != null && corner.world?.name == player.world.name) {
                val target = player.location
                val w = kotlin.math.abs(target.blockX - corner.blockX) + 1
                val l = kotlin.math.abs(target.blockZ - corner.blockZ) + 1
                val area = w * l
                player.sendActionBar(
                    messages.render(
                        "shovel-corner-hud",
                        mapOf(
                            "x" to corner.blockX,
                            "z" to corner.blockZ,
                            "w" to w,
                            "l" to l,
                            "area" to area,
                            "available" to available
                        )
                    )
                )
            } else {
                val modeName = st.shovelMode.label
                player.sendActionBar(
                    messages.render(
                        "shovel-idle-hud",
                        mapOf("mode" to modeName, "available" to available)
                    )
                )
            }
            return
        }

        // Priority 3: Current Territory Status
        val claim = claimManager.getClaimAt(player.location)
        val showBanner = cfg.bossBarTerritoryBanner || st.territoryBanner
        if (claim != null) {
            if (st.lastClaimId != claim.id) {
                st.lastClaimId = claim.id
                val greeting = claim.greeting
                if (!greeting.isNullOrBlank() && claim.flag(ClaimFlag.ENTRY_NOTIFY, cfg.defaultFlags)) {
                    flash(player, messages.renderRaw("<aqua>⚑ $greeting</aqua>"), 2500L)
                    return
                }
            }
            val displayTitle = if (claim.isSubclaim && claim.parent != null) {
                val lockTag = if (claim.inheritNothing) " <red>[Isolated]</red>" else ""
                "${claim.parent!!.name} › ${claim.name}$lockTag"
            } else {
                claim.name
            }
            if (showBanner) {
                val pvpTag =
                    if (claim.flag(ClaimFlag.PVP, cfg.defaultFlags)) "<red>⚔ PvP</red>" else "<green>🛡 Safe</green>"
                bossBar.showOrUpdate(
                    player,
                    "territory_banner",
                    messages.renderRaw("<aqua>${claim.ownerName()}</aqua> <gray>·</gray> <white>$displayTitle</white> <gray>|</gray> $pvpTag"),
                    1.0f,
                    if (claim.isAdminClaim) net.kyori.adventure.bossbar.BossBar.Color.YELLOW else net.kyori.adventure.bossbar.BossBar.Color.GREEN
                )
            } else {
                bossBar.hide(player, "territory_banner")
            }

            if (claim.isAdminClaim) {
                player.sendActionBar(messages.render("claim-hud-admin", mapOf("name" to displayTitle)))
            } else {
                val perm = claim.effectivePermission(player.uniqueId)
                val badge = when {
                    perm == ClaimPermission.OWNER -> "<green>[Owner]</green>"
                    perm != null -> "<aqua>[${perm.displayName}]</aqua>"
                    claim.flag(ClaimFlag.PVP, cfg.defaultFlags) -> "<red>[⚔ PvP]</red>"
                    else -> "<gray>[Protected]</gray>"
                }
                player.sendActionBar(
                    messages.render(
                        "claim-hud",
                        mapOf("owner" to claim.ownerName(), "name" to displayTitle, "badge" to badge)
                    )
                )
            }
        } else {
            st.lastClaimId = null
            bossBar.hide(player, "territory_banner")
            if (hasLockedDrops?.invoke(player.uniqueId) == true) {
                player.sendActionBar(messages.renderRaw("<aqua>🛡 Death Drops Protected</aqua> <gray>| Use /unlockdrops to unlock</gray>"))
            } else {
                player.sendActionBar(messages.render("wilderness"))
            }
        }
    }
}
