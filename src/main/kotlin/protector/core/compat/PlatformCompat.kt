package protector.core.compat

import net.kyori.adventure.audience.Audience
import org.bukkit.Bukkit

object PlatformCompat {
    val supportsDialogs: Boolean by lazy {
        hasClass("io.papermc.paper.dialog.Dialog") &&
            hasClass("io.papermc.paper.registry.data.dialog.DialogBase") &&
            runCatching {
                Audience::class.java.methods.any { it.name == "showDialog" }
            }.getOrDefault(false)
    }

    val supportsFoliaSchedulers: Boolean by lazy {
        hasClass("io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler") &&
            runCatching {
                Bukkit::class.java.methods.any { it.name == "getGlobalRegionScheduler" }
            }.getOrDefault(false)
    }

    val supportsPaperAsyncChat: Boolean by lazy {
        hasClass("io.papermc.paper.event.player.AsyncChatEvent")
    }

    val supportsTntPrimeEvent: Boolean by lazy {
        hasClass("org.bukkit.event.block.TNTPrimeEvent")
    }

    val supportsTakeLecternBookEvent: Boolean by lazy {
        hasClass("org.bukkit.event.player.PlayerTakeLecternBookEvent")
    }

    val supportsSignSide: Boolean by lazy {
        hasClass("org.bukkit.block.sign.Side")
    }

    fun serverVersionLabel(): String =
        runCatching { Bukkit.getMinecraftVersion() }.getOrElse {
            runCatching { Bukkit.getBukkitVersion() }.getOrDefault("unknown")
        }

    fun hasClass(name: String): Boolean =
        runCatching {
            Class.forName(name, false, PlatformCompat::class.java.classLoader)
            true
        }.getOrDefault(false)
}
