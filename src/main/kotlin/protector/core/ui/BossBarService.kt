package protector.core.ui

import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import protector.core.scheduler.PluginScheduler
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class BossBarService(
    private val scheduler: PluginScheduler,
    private val enabledProvider: () -> Boolean,
) {
    private val playerBars = ConcurrentHashMap<UUID, ConcurrentHashMap<String, BossBar>>()

    fun showOrUpdate(
        player: Player,
        key: String,
        title: Component,
        progress: Float,
        color: BossBar.Color = BossBar.Color.BLUE,
        overlay: BossBar.Overlay = BossBar.Overlay.PROGRESS,
    ) {
        if (!enabledProvider()) return
        val bars = playerBars.computeIfAbsent(player.uniqueId) { ConcurrentHashMap() }
        val clamped = progress.coerceIn(0f, 1f)
        val existing = bars[key]
        if (existing != null) {
            existing.name(title)
            existing.progress(clamped)
            existing.color(color)
            existing.overlay(overlay)
        } else {
            val created = BossBar.bossBar(title, clamped, color, overlay)
            bars[key] = created
            player.showBossBar(created)
        }
    }

    fun completeAndAutoHide(
        player: Player,
        key: String,
        title: Component,
        color: BossBar.Color = BossBar.Color.GREEN,
        delayTicks: Long = 60L,
    ) {
        if (!enabledProvider()) return
        showOrUpdate(player, key, title, 1f, color, BossBar.Overlay.PROGRESS)
        scheduler.runForEntityDelayed(player, delayTicks) {
            hide(player, key)
        }
    }

    fun hide(player: Player, key: String) {
        val bars = playerBars[player.uniqueId] ?: return
        val bar = bars.remove(key) ?: return
        runCatching { player.hideBossBar(bar) }
    }

    fun hideAll(player: Player) {
        val bars = playerBars.remove(player.uniqueId) ?: return
        for (bar in bars.values) {
            runCatching { player.hideBossBar(bar) }
        }
    }

    fun clearAll() {
        for ((uuid, bars) in playerBars) {
            val player = Bukkit.getPlayer(uuid) ?: continue
            for (bar in bars.values) {
                runCatching { player.hideBossBar(bar) }
            }
        }
        playerBars.clear()
    }
}
