package protector.core.command

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.entity.Player
import protector.core.CoreHost

object SpecializedCommandExecutor {

    fun executeAll(
        host: CoreHost,
        rawLines: List<String>,
        player: Player? = null,
        extraPlaceholders: Map<String, String> = emptyMap(),
    ) {
        if (rawLines.isEmpty()) return
        executeFromIndex(host, rawLines, 0, player, extraPlaceholders)
    }

    private fun executeFromIndex(
        host: CoreHost,
        lines: List<String>,
        index: Int,
        player: Player?,
        extraPlaceholders: Map<String, String>,
    ) {
        var i = index
        while (i < lines.size) {
            val raw = lines[i].trim()
            i++
            if (raw.isEmpty() || raw.startsWith("#")) continue

            val delayTicks = parseDelayTicks(raw)
            if (delayTicks != null) {
                val nextIdx = i
                if (nextIdx >= lines.size) return
                if (player != null && player.isOnline) {
                    host.scheduler.runForEntityDelayed(player, delayTicks) {
                        executeFromIndex(host, lines, nextIdx, player, extraPlaceholders)
                    }
                } else {
                    host.scheduler.runGlobalDelayed(delayTicks) {
                        executeFromIndex(host, lines, nextIdx, player, extraPlaceholders)
                    }
                }
                return
            }

            executeSingleLine(host, raw, player, extraPlaceholders)
        }
    }

    private fun executeSingleLine(
        host: CoreHost,
        rawLine: String,
        player: Player?,
        extraPlaceholders: Map<String, String>,
    ) {
        val resolved = resolvePlaceholders(rawLine.trim(), player, extraPlaceholders)
        val lower = resolved.lowercase()

        when {
            lower.startsWith("console:") -> {
                val cmd = resolved.substring(8).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runGlobal {
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                    }
                }
            }

            lower.startsWith("player:") -> {
                val target = player ?: return
                val cmd = resolved.substring(7).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runForEntity(target) {
                        Bukkit.dispatchCommand(target, cmd)
                    }
                }
            }

            lower.startsWith("op:") -> {
                val target = player ?: return
                val cmd = resolved.substring(3).trim().removePrefix("/")
                if (cmd.isNotEmpty()) {
                    host.scheduler.runForEntity(target) {
                        val wasOp = target.isOp
                        try {
                            if (!wasOp) target.isOp = true
                            Bukkit.dispatchCommand(target, cmd)
                        } finally {
                            if (!wasOp) target.isOp = false
                        }
                    }
                }
            }

            lower.startsWith("msg:") || lower.startsWith("message:") -> {
                val payload = resolved.substringAfter(':').trim()
                val comp = host.messages.renderRaw(payload)
                if (player != null) {
                    player.sendMessage(comp)
                } else {
                    Bukkit.getConsoleSender().sendMessage(comp)
                }
            }

            lower.startsWith("broadcast:") || lower.startsWith("bc:") -> {
                val payload = resolved.substringAfter(':').trim()
                Bukkit.broadcast(host.messages.renderRaw(payload))
            }

            lower.startsWith("actionbar:") -> {
                val target = player ?: return
                val payload = resolved.substring(10).trim()
                host.actionBar.flash(target, host.messages.renderRaw(payload))
            }

            lower.startsWith("bossbar:") -> {
                val target = player ?: return
                val parts = resolved.substring(8).split('|', limit = 3)
                val color = runCatching {
                    BossBar.Color.valueOf(parts[0].trim().uppercase())
                }.getOrDefault(BossBar.Color.YELLOW)
                val seconds = parts.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, 300) ?: 5
                val msg = parts.getOrNull(2)?.trim() ?: parts.last().trim()
                host.bossBar.completeAndAutoHide(
                    player = target,
                    key = "alias_${target.uniqueId}",
                    title = host.messages.renderRaw(msg),
                    color = color,
                    delayTicks = seconds * 20L,
                )
            }

            lower.startsWith("sound:") -> {
                val target = player ?: return
                val parts = resolved.substring(6).trim().split(Regex("\\s+"))
                val soundKey = parts.firstOrNull()?.lowercase()?.replace('_', '.') ?: return
                val volume = parts.getOrNull(1)?.toFloatOrNull() ?: 1.0f
                val pitch = parts.getOrNull(2)?.toFloatOrNull() ?: 1.0f
                val sound = runCatching {
                    Registry.SOUNDS.get(NamespacedKey.minecraft(soundKey.removePrefix("minecraft:")))
                }.getOrNull() ?: return
                target.playSound(target.location, sound, volume, pitch)
            }

            else -> {
                val cmd = resolved.removePrefix("/").trim()
                if (cmd.isNotEmpty()) {
                    if (player != null) {
                        host.scheduler.runForEntity(player) {
                            Bukkit.dispatchCommand(player, cmd)
                        }
                    } else {
                        host.scheduler.runGlobal {
                            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd)
                        }
                    }
                }
            }
        }
    }

    private fun resolvePlaceholders(
        line: String,
        player: Player?,
        extra: Map<String, String>,
    ): String {
        var out = line
        if (player != null) {
            out = out.replace("{player}", player.name, ignoreCase = true)
            out = out.replace("{world}", player.world.name, ignoreCase = true)
        }
        for ((k, v) in extra) {
            out = out.replace("{$k}", v, ignoreCase = true)
        }
        return out
    }

    private fun parseDelayTicks(line: String): Long? {
        val trimmed = line.trim().lowercase()
        val rawVal = when {
            trimmed.startsWith("delay!") -> trimmed.substring(6).trim()
            trimmed.startsWith("delay:") -> trimmed.substring(6).trim()
            else -> return null
        }
        val seconds = rawVal.removeSuffix("s").toDoubleOrNull() ?: return null
        return (seconds * 20.0).toLong().coerceAtLeast(1L)
    }
}
