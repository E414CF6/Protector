package protector.core.ui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import protector.core.locale.MessageService
import protector.core.scheduler.PluginScheduler
import protector.core.scheduler.TaskHandle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

data class ActionBarSettings(
    val enabled: Boolean = true,
    val refreshTicks: Long = 10L,
    val denialCooldownMs: Long = 1500L,
)

data class HudProvider(
    val id: String,
    val priority: Int,
    val render: (Player) -> Component?,
)

private data class PlayerActionBarState(
    var hudEnabled: Boolean = true,
    var flashUntilMs: Long = 0L,
    var flashMessage: Component? = null,
    var lastDenialMs: Long = 0L,
)

class ActionBarService(
    private val scheduler: PluginScheduler,
    private val settingsProvider: () -> ActionBarSettings,
    private val messages: MessageService,
) {
    private val states = ConcurrentHashMap<UUID, PlayerActionBarState>()
    private val providers = CopyOnWriteArrayList<HudProvider>()
    private var task: TaskHandle? = null

    fun registerHudProvider(id: String, priority: Int, render: (Player) -> Component?) {
        providers.removeIf { it.id == id }
        providers.add(HudProvider(id, priority, render))
        providers.sortByDescending { it.priority }
    }

    fun unregisterHudProvider(id: String) {
        providers.removeIf { it.id == id }
    }

    fun isHudEnabled(uuid: UUID): Boolean =
        states[uuid]?.hudEnabled ?: true

    fun setHudEnabled(uuid: UUID, enabled: Boolean) {
        states.computeIfAbsent(uuid) { PlayerActionBarState() }.hudEnabled = enabled
    }

    fun clearSession(uuid: UUID) {
        states.remove(uuid)
    }

    fun start() {
        stop()
        val cfg = settingsProvider()
        if (!cfg.enabled) return
        task = scheduler.runGlobalTimer(cfg.refreshTicks, cfg.refreshTicks) {
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
        val st = states.computeIfAbsent(player.uniqueId) { PlayerActionBarState() }
        st.flashMessage = component
        st.flashUntilMs = System.currentTimeMillis() + durationMs
        player.sendActionBar(component)
    }

    fun sendDenial(player: Player, key: String, placeholders: Map<String, Any> = emptyMap()) {
        val st = states.computeIfAbsent(player.uniqueId) { PlayerActionBarState() }
        val now = System.currentTimeMillis()
        val cooldown = settingsProvider().denialCooldownMs
        if (now - st.lastDenialMs < cooldown) return
        st.lastDenialMs = now
        val comp = messages.render(key, placeholders)
        flash(player, comp, 2000L)
    }

    fun tickPlayer(player: Player) {
        val cfg = settingsProvider()
        if (!cfg.enabled) return
        val st = states.computeIfAbsent(player.uniqueId) { PlayerActionBarState() }
        if (!st.hudEnabled) return

        val now = System.currentTimeMillis()
        if (now < st.flashUntilMs && st.flashMessage != null) {
            player.sendActionBar(st.flashMessage!!)
            return
        }

        for (provider in providers) {
            val comp = provider.render(player)
            if (comp != null) {
                player.sendActionBar(comp)
                return
            }
        }
    }
}
