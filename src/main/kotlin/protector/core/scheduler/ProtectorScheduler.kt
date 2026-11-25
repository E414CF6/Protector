package protector.core.scheduler

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import protector.core.compat.PlatformCompat
import java.util.concurrent.TimeUnit

fun interface TaskHandle {
    fun cancel()
}

/**
 * Paper / Folia / Legacy Bukkit-compatible scheduler and CoroutineScope bridge.
 */
class ProtectorScheduler(private val plugin: Plugin?) {
    private val rootJob = SupervisorJob()
    val asyncScope: CoroutineScope = CoroutineScope(rootJob + Dispatchers.IO)

    private val useFolia: Boolean by lazy {
        plugin != null && PlatformCompat.supportsFoliaSchedulers && runCatching {
            Bukkit.getGlobalRegionScheduler()
            true
        }.getOrDefault(false)
    }

    val isFolia: Boolean
        get() = useFolia

    fun runAsync(task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        return if (useFolia) {
            val st = Bukkit.getAsyncScheduler().runNow(host) { task() }
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskAsynchronously(host, Runnable(task))
            TaskHandle { bt.cancel() }
        }
    }

    fun runAsyncDelayed(delayMillis: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        return if (useFolia) {
            val st = Bukkit.getAsyncScheduler().runDelayed(host, { task() }, delayMillis.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
            TaskHandle { st.cancel() }
        } else {
            val ticks = (delayMillis / 50L).coerceAtLeast(1L)
            val bt = Bukkit.getScheduler().runTaskLaterAsynchronously(host, Runnable(task), ticks)
            TaskHandle { bt.cancel() }
        }
    }

    fun runGlobal(task: () -> Unit) {
        val host = plugin ?: return
        if (!host.isEnabled) return
        if (useFolia) {
            Bukkit.getGlobalRegionScheduler().execute(host, task)
        } else {
            Bukkit.getScheduler().runTask(host, Runnable(task))
        }
    }

    fun runGlobalDelayed(delayTicks: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val ticks = delayTicks.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getGlobalRegionScheduler().runDelayed(host, { task() }, ticks)
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskLater(host, Runnable(task), ticks)
            TaskHandle { bt.cancel() }
        }
    }

    fun runGlobalTimer(initialDelayTicks: Long, periodTicks: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val init = initialDelayTicks.coerceAtLeast(1L)
        val period = periodTicks.coerceAtLeast(1L)
        return if (useFolia) {
            val st = Bukkit.getGlobalRegionScheduler().runAtFixedRate(host, { task() }, init, period)
            TaskHandle { st.cancel() }
        } else {
            val bt: BukkitTask = Bukkit.getScheduler().runTaskTimer(host, Runnable(task), init, period)
            TaskHandle { bt.cancel() }
        }
    }

    fun runAtLocation(location: Location, task: () -> Unit) {
        val host = plugin ?: return
        if (!host.isEnabled) return
        if (useFolia) {
            Bukkit.getRegionScheduler().execute(host, location, task)
        } else {
            Bukkit.getScheduler().runTask(host, Runnable(task))
        }
    }

    fun runForEntity(entity: Entity, task: () -> Unit) {
        val host = plugin ?: return
        if (!host.isEnabled) return
        if (useFolia) {
            entity.scheduler.execute(host, task, null, 1L)
        } else {
            Bukkit.getScheduler().runTask(host, Runnable(task))
        }
    }

    fun runForEntityDelayed(entity: Entity, delayTicks: Long, task: () -> Unit): TaskHandle? {
        val host = plugin ?: return null
        val ticks = delayTicks.coerceAtLeast(1L)
        return if (useFolia) {
            val st = entity.scheduler.runDelayed(host, { task() }, null, ticks) ?: return null
            TaskHandle { st.cancel() }
        } else {
            val bt = Bukkit.getScheduler().runTaskLater(host, Runnable(task), ticks)
            TaskHandle { bt.cancel() }
        }
    }

    fun shutdown() {
        rootJob.cancel()
        asyncScope.cancel()
        val host = plugin ?: return
        if (useFolia) {
            runCatching { Bukkit.getAsyncScheduler().cancelTasks(host) }
            runCatching { Bukkit.getGlobalRegionScheduler().cancelTasks(host) }
        } else {
            runCatching { Bukkit.getScheduler().cancelTasks(host) }
        }
    }
}
