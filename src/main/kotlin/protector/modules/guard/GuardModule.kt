package protector.modules.guard

import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import protector.core.ProtectorContext
import protector.core.command.CommandRegistry
import protector.core.command.SubcommandTabCompleter
import protector.core.compat.PlatformCompat
import protector.core.config.GuardSettings
import protector.core.module.FeatureModule
import protector.modules.guard.ui.GuardDialogs
import protector.modules.guard.ui.GuardMenus
import java.util.Locale

class GuardModule(
    private val ctx: ProtectorContext,
    dashboardOpener: (Player) -> Unit,
) : FeatureModule {
    override val id: String = "guard"
    override val description: String = "Global TNT, explosion, fire, mob griefing, portal, and death-drop protection"

    private val bypassCheck: (Player) -> Boolean = { player ->
        player.uniqueId in ctx.adminBypassPlayers && player.hasPermission("protector.admin")
    }

    val deathDrops = DeathDropProtectionService(
        ctx.plugin,
        { ctx.config },
        ctx.messages,
        ctx.actionBar,
        bypassCheck,
    )

    val worldListener = WorldProtectionListener(
        { ctx.config },
        ctx.actionBar,
        bypassCheck,
    )

    val menus = GuardMenus(ctx, dashboardOpener)
    val dialogs = GuardDialogs(ctx) { menus }

    override fun onEnable() {
        ctx.actionBar.registerHudProvider("guard_death_drops", priority = 20) { player ->
            if (deathDrops.hasLockedDrops(player.uniqueId)) {
                ctx.messages.render("drops-hud")
            } else {
                null
            }
        }
    }

    override fun onDisable() {
        ctx.actionBar.unregisterHudProvider("guard_death_drops")
    }

    override fun listeners(): List<Listener> {
        val list = mutableListOf<Listener>(worldListener, deathDrops)
        if (PlatformCompat.supportsTntPrimeEvent) {
            list += TntPrimeGuardListener(worldListener)
        }
        return list
    }

    override fun registerSubcommands(registry: CommandRegistry) {
        val ruleKeys = RULE_HANDLERS.keys.sorted()

        registry.registerSubcommand(
            name = "unlockdrops",
            description = "Unlock protected death drop items",
            permission = "protector.unlockdrops",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            deathDrops.unlockDrops(player)
            true
        }

        registry.registerSubcommand(
            name = "guard",
            description = "Configure global TNT, explosion, fire, mob, and death-drop protection",
            permission = "protector.guard",
            aliases = listOf("tntguard", "worldguard"),
            tabCompleter = SubcommandTabCompleter { _, args ->
                when (args.size) {
                    0, 1 -> (listOf("list") + ruleKeys).filter {
                        it.startsWith(args.firstOrNull().orEmpty(), ignoreCase = true)
                    }
                    2 -> if (!args[0].equals("list", ignoreCase = true)) {
                        listOf("on", "off", "toggle").filter { it.startsWith(args[1], ignoreCase = true) }
                    } else emptyList()
                    else -> emptyList()
                }
            },
        ) { sender, args ->
            if (args.isEmpty()) {
                val player = requirePlayer(sender) ?: return@registerSubcommand true
                dialogs.openGuardSettingsDialog(player)
                return@registerSubcommand true
            }
            if (args[0].equals("list", ignoreCase = true)) {
                val g = ctx.config.guard
                val summary = RULE_HANDLERS.entries.joinToString(", ") { (k, spec) ->
                    val on = spec.getter(g)
                    "<gray>$k:</gray>${if (on) "<green>ON</green>" else "<red>OFF</red>"}"
                }
                ctx.messages.sendRaw(sender, "<aqua>Guard Rules:</aqua> $summary")
                return@registerSubcommand true
            }
            val key = args[0].lowercase(Locale.ROOT)
            val handler = RULE_HANDLERS[key]
            if (handler == null) {
                ctx.messages.send(sender, "guard-rule-unknown", mapOf("rule" to args[0]))
                return@registerSubcommand true
            }
            val desired: Boolean? = when (args.getOrNull(1)?.lowercase(Locale.ROOT)) {
                "on", "true", "enable" -> true
                "off", "false", "disable" -> false
                null, "toggle" -> null
                else -> null
            }
            var finalState = false
            ctx.updateGuardSettings { cur ->
                val next = desired ?: !handler.getter(cur)
                finalState = next
                handler.setter(cur, next)
            }
            ctx.messages.send(
                sender,
                "guard-rule-updated",
                mapOf("rule" to key, "state" to if (finalState) "ON" else "OFF")
            )
            true
        }
    }

    fun activeRuleCount(): Int {
        val g = ctx.config.guard
        return RULE_HANDLERS.values.count { it.getter(g) }
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        ctx.messages.send(sender, "player-only")
        return null
    }

    private data class RuleSpec(
        val getter: (GuardSettings) -> Boolean,
        val setter: (GuardSettings, Boolean) -> GuardSettings,
    )

    companion object {
        private val RULE_HANDLERS: Map<String, RuleSpec> = linkedMapOf(
            "tnt-block" to RuleSpec({ it.tntPreventBlockDamage }) { g, v -> g.copy(tntPreventBlockDamage = v) },
            "tnt-prime" to RuleSpec({ it.tntPreventPrime }) { g, v -> g.copy(tntPreventPrime = v) },
            "tnt-entity" to RuleSpec({ it.tntPreventEntityDamage }) { g, v -> g.copy(tntPreventEntityDamage = v) },
            "tnt-minecart" to RuleSpec({ it.tntPreventMinecart }) { g, v -> g.copy(tntPreventMinecart = v) },
            "creeper" to RuleSpec({ it.preventCreeperBlockDamage }) { g, v -> g.copy(preventCreeperBlockDamage = v) },
            "wither" to RuleSpec({ it.preventWitherDamage }) { g, v -> g.copy(preventWitherDamage = v, preventWitherBlockBreak = v) },
            "fireball" to RuleSpec({ it.preventFireballBlockDamage }) { g, v -> g.copy(preventFireballBlockDamage = v) },
            "end-crystal" to RuleSpec({ it.preventEndCrystalDamage }) { g, v -> g.copy(preventEndCrystalDamage = v) },
            "bed-explode" to RuleSpec({ it.preventBedAnchorExplode }) { g, v -> g.copy(preventBedAnchorExplode = v) },
            "fire-spread" to RuleSpec({ it.preventFireSpread }) { g, v -> g.copy(preventFireSpread = v, preventBlockBurn = v) },
            "lava-fire" to RuleSpec({ it.preventLavaFireSpread }) { g, v -> g.copy(preventLavaFireSpread = v) },
            "lightning-fire" to RuleSpec({ it.preventLightningFire }) { g, v -> g.copy(preventLightningFire = v) },
            "enderman" to RuleSpec({ it.preventEndermanGrief }) { g, v -> g.copy(preventEndermanGrief = v) },
            "ravager" to RuleSpec({ it.preventRavagerGrief }) { g, v -> g.copy(preventRavagerGrief = v) },
            "non-player-portals" to RuleSpec({ it.preventNonPlayerPortals }) { g, v -> g.copy(preventNonPlayerPortals = v) },
            "death-drops" to RuleSpec({ it.protectDeathDrops }) { g, v -> g.copy(protectDeathDrops = v) },
        )
    }
}
