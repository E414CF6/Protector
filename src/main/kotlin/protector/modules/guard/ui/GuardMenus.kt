package protector.modules.guard.ui

import org.bukkit.Material
import org.bukkit.entity.Player
import protector.core.ProtectorContext
import protector.core.config.GuardSettings
import protector.core.ui.MenuItems
import protector.core.ui.MenuWindow

class GuardMenus(
    private val ctx: ProtectorContext,
    private val onBackToDashboard: (Player) -> Unit,
) {
    fun openGuardSettingsMenu(player: Player) {
        val g = ctx.config.guard
        val window = MenuWindow(ctx.messages.renderRaw("<red><bold>World & TNT Guard Rules</bold></red>"), 4)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        data class ToggleSpec(
            val slot: Int,
            val label: String,
            val enabled: Boolean,
            val mat: Material,
            val toggle: (GuardSettings) -> GuardSettings,
        )

        val entries = listOf(
            ToggleSpec(10, "TNT Block Damage", g.tntPreventBlockDamage, Material.TNT) { it.copy(tntPreventBlockDamage = !it.tntPreventBlockDamage) },
            ToggleSpec(11, "TNT Ignition (Prime)", g.tntPreventPrime, Material.FLINT_AND_STEEL) { it.copy(tntPreventPrime = !it.tntPreventPrime) },
            ToggleSpec(12, "TNT Entity Damage", g.tntPreventEntityDamage, Material.GUNPOWDER) { it.copy(tntPreventEntityDamage = !it.tntPreventEntityDamage) },
            ToggleSpec(13, "TNT Minecart", g.tntPreventMinecart, Material.TNT_MINECART) { it.copy(tntPreventMinecart = !it.tntPreventMinecart) },
            ToggleSpec(14, "Creeper Explosions", g.preventCreeperBlockDamage, Material.CREEPER_HEAD) { it.copy(preventCreeperBlockDamage = !it.preventCreeperBlockDamage) },
            ToggleSpec(15, "Wither Grief", g.preventWitherDamage, Material.WITHER_SKELETON_SKULL) {
                val next = !it.preventWitherDamage
                it.copy(preventWitherDamage = next, preventWitherBlockBreak = next)
            },
            ToggleSpec(16, "End Crystal Explosions", g.preventEndCrystalDamage, Material.END_CRYSTAL) { it.copy(preventEndCrystalDamage = !it.preventEndCrystalDamage) },
            ToggleSpec(19, "Fire Spread & Burn", g.preventFireSpread, Material.BLAZE_POWDER) {
                val next = !it.preventFireSpread
                it.copy(preventFireSpread = next, preventBlockBurn = next)
            },
            ToggleSpec(20, "Lava & Lightning Fire", g.preventLavaFireSpread, Material.LAVA_BUCKET) {
                val next = !it.preventLavaFireSpread
                it.copy(preventLavaFireSpread = next, preventLightningFire = next)
            },
            ToggleSpec(21, "Enderman Block Grief", g.preventEndermanGrief, Material.ENDER_PEARL) { it.copy(preventEndermanGrief = !it.preventEndermanGrief) },
            ToggleSpec(22, "Bed/Anchor Explosions", g.preventBedAnchorExplode, Material.RESPAWN_ANCHOR) { it.copy(preventBedAnchorExplode = !it.preventBedAnchorExplode) },
            ToggleSpec(23, "Non-Player Portals", g.preventNonPlayerPortals, Material.OBSIDIAN) { it.copy(preventNonPlayerPortals = !it.preventNonPlayerPortals) },
            ToggleSpec(24, "Protect Death Drops", g.protectDeathDrops, Material.CHEST) { it.copy(protectDeathDrops = !it.protectDeathDrops) },
        )

        for (spec in entries) {
            val stateBadge = if (spec.enabled) "<green>PROTECTED (BLOCKED)</green>" else "<gray>DISABLED</gray>"
            window.set(
                spec.slot,
                MenuItems.item(
                    spec.mat,
                    "<aqua>${spec.label}</aqua>",
                    listOf("<gray>Status:</gray> $stateBadge", "<yellow>Click to toggle</yellow>"),
                    ctx.messages,
                )
            ) { click ->
                ctx.updateGuardSettings(spec.toggle)
                openGuardSettingsMenu(click.player)
            }
        }

        window.set(31, MenuItems.item(Material.OAK_DOOR, "<gray>Back to Dashboard</gray>", emptyList(), ctx.messages)) { click ->
            onBackToDashboard(click.player)
        }
        window.open(player)
    }
}
