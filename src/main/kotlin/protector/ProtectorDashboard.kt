package protector

import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import protector.core.ProtectorContext
import protector.core.ui.MenuItems
import protector.core.ui.MenuWindow
import protector.modules.audit.AuditModule
import protector.modules.audit.LookupFilter
import protector.modules.guard.GuardModule

class ProtectorDashboard(
    private val ctx: ProtectorContext,
    private val guardModule: GuardModule,
    private val auditModule: AuditModule,
) {
    fun openDashboard(player: Player) {
        val window = MenuWindow(
            ctx.messages.renderRaw("<gradient:#38bdf8:#818cf8><bold>Protector Dashboard</bold></gradient>"),
            4
        )
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        val inspecting = auditModule.inspectorService.isInspecting(player.uniqueId)
        val hudEnabled = ctx.actionBar.isHudEnabled(player.uniqueId)
        val bypassing = player.uniqueId in ctx.adminBypassPlayers

        window.set(
            4,
            MenuItems.item(
                Material.SHIELD,
                "<aqua><bold>Protector Overview</bold></aqua>",
                listOf(
                    "<gray>Active Guard Rules:</gray> <white>${guardModule.activeRuleCount()}</white>",
                    "<gray>Pending Audit Queue:</gray> <white>${auditModule.consumer.pendingCount()}</white>",
                    "<gray>Total Logged Events:</gray> <white>${auditModule.consumer.totalProcessed.get()}</white>",
                ),
                ctx.messages,
            )
        )

        if (player.hasPermission("protector.guard")) {
            window.set(
                11,
                MenuItems.item(
                    Material.TNT,
                    "<red><bold>World & TNT Guard Rules</bold></red>",
                    listOf("<gray>Toggle TNT, explosion, fire spread,", "<gray>mob griefing, and death-drop protection."),
                    ctx.messages,
                )
            ) { click ->
                click.player.closeInventory()
                guardModule.dialogs.openGuardSettingsDialog(click.player)
            }
        }

        if (player.hasPermission("protector.inspect")) {
            window.set(
                13,
                MenuItems.item(
                    if (inspecting) Material.ENDER_EYE else Material.SPYGLASS,
                    if (inspecting) "<green><bold>Inspector Mode: ON</bold></green>" else "<gray><bold>Inspector Mode: OFF</bold></gray>",
                    listOf("<gray>Click blocks/containers to view audit history.", "<yellow>Click to toggle</yellow>"),
                    ctx.messages,
                )
            ) { click ->
                auditModule.inspectorService.toggleInspect(click.player)
                openDashboard(click.player)
            }
        }

        if (player.hasPermission("protector.lookup") || player.hasPermission("protector.rollback")) {
            window.set(
                15,
                MenuItems.item(
                    Material.CLOCK,
                    "<gold><bold>Audit Lookup & Rollback (Dialog)</bold></gold>",
                    listOf("<gray>Configure time, radius, and user filters", "<gray>to preview, rollback, or browse logs."),
                    ctx.messages,
                )
            ) { click ->
                click.player.closeInventory()
                auditModule.dialogs.openRollbackFilterDialog(click.player, restoreMode = false)
            }

            window.set(
                20,
                MenuItems.item(
                    Material.BOOK,
                    "<yellow><bold>Nearby Audit Logs (GUI)</bold></yellow>",
                    listOf("<gray>Browse the last 24h within 20 blocks", "<gray>using tabbed category views."),
                    ctx.messages,
                )
            ) { click ->
                val loc = click.player.location
                auditModule.menus.openAuditBrowserAsync(
                    click.player,
                    LookupFilter(
                        worldName = loc.world.name,
                        centerX = loc.blockX,
                        centerY = loc.blockY,
                        centerZ = loc.blockZ,
                        radius = 20,
                        sinceEpochSec = (System.currentTimeMillis() / 1000L) - 86400L,
                        limit = 100
                    ),
                    0
                )
            }
        }

        if (guardModule.deathDrops.hasLockedDrops(player.uniqueId)) {
            window.set(
                22,
                MenuItems.item(
                    Material.CHEST,
                    "<aqua><bold>Unlock Protected Death Drops</bold></aqua>",
                    listOf("<gray>Your death drops are currently protected.", "<yellow>Click to allow others to pick them up.</yellow>"),
                    ctx.messages,
                )
            ) { click ->
                guardModule.deathDrops.unlockDrops(click.player)
                openDashboard(click.player)
            }
        }

        window.set(
            24,
            MenuItems.item(
                if (hudEnabled) Material.BELL else Material.BARRIER,
                if (hudEnabled) "<aqua>ActionBar HUD: ON</aqua>" else "<gray>ActionBar HUD: OFF</gray>",
                listOf("<yellow>Click to toggle persistent ActionBar HUD</yellow>"),
                ctx.messages,
            )
        ) { click ->
            val next = !ctx.actionBar.isHudEnabled(click.player.uniqueId)
            ctx.actionBar.setHudEnabled(click.player.uniqueId, next)
            if (!next) click.player.sendActionBar(Component.empty())
            openDashboard(click.player)
        }

        if (player.hasPermission("protector.admin")) {
            window.set(
                30,
                MenuItems.item(
                    Material.COMMAND_BLOCK,
                    "<red><bold>Admin Control Panel</bold></red>",
                    listOf("<gray>Configure guard rules, search activity logs,", "<gray>purge old logs, and view queue stats."),
                    ctx.messages,
                )
            ) { click -> openAdminPanel(click.player) }

            window.set(
                32,
                MenuItems.item(
                    if (bypassing) Material.NETHER_STAR else Material.IRON_INGOT,
                    if (bypassing) "<red><bold>Admin Bypass: ON</bold></red>" else "<gray>Admin Bypass: OFF</gray>",
                    listOf("<yellow>Temporarily bypass global guard restrictions.</yellow>"),
                    ctx.messages,
                )
            ) { click ->
                if (click.player.uniqueId in ctx.adminBypassPlayers) {
                    ctx.adminBypassPlayers.remove(click.player.uniqueId)
                } else {
                    ctx.adminBypassPlayers.add(click.player.uniqueId)
                }
                openDashboard(click.player)
            }
        }

        window.open(player)
    }

    fun openAdminPanel(player: Player) {
        if (!player.hasPermission("protector.admin")) return
        val window = MenuWindow(ctx.messages.renderRaw("<red><bold>Protector Admin Panel</bold></red>"), 3)
        window.fillBorder(Material.RED_STAINED_GLASS_PANE)

        window.set(
            10,
            MenuItems.item(
                Material.TNT,
                "<red><bold>World & TNT Guard Rules</bold></red>",
                listOf("<gray>Edit global TNT, explosion, fire, and mob rules."),
                ctx.messages,
            )
        ) { click ->
            click.player.closeInventory()
            guardModule.dialogs.openGuardSettingsDialog(click.player)
        }

        window.set(
            12,
            MenuItems.item(
                Material.PAPER,
                "<yellow><bold>Activity Log Search (Dialog)</bold></yellow>",
                listOf("<gray>Search chat, command, session, and sign records."),
                ctx.messages,
            )
        ) { click ->
            click.player.closeInventory()
            auditModule.dialogs.openActivitySearchDialog(click.player)
        }

        window.set(
            14,
            MenuItems.item(
                Material.LAVA_BUCKET,
                "<red><bold>Purge Logs Older Than 30d</bold></red>",
                listOf("<gray>Opens a confirmation Dialog before deleting."),
                ctx.messages,
            )
        ) { click ->
            click.player.closeInventory()
            ctx.dialogs.openConfirmationDialog(
                click.player,
                "<red><bold>Confirm 30d Log Purge</bold></red>",
                "<gray>Permanently delete all audit logs older than 30 days.</gray>",
                "<red>Purge</red>",
            ) { p ->
                auditModule.rollbackService.purgeBefore(p, p, 30L * 86400L)
            }
        }

        window.set(
            16,
            MenuItems.item(
                Material.REDSTONE_TORCH,
                "<aqua><bold>System & Queue Status</bold></aqua>",
                listOf(
                    "<gray>Active Guard Rules:</gray> <white>${guardModule.activeRuleCount()}</white>",
                    "<gray>Pending Log Queue:</gray> <white>${auditModule.consumer.pendingCount()}</white>",
                    "<gray>Total Processed:</gray> <white>${auditModule.consumer.totalProcessed.get()}</white>",
                ),
                ctx.messages,
            )
        )

        window.set(22, MenuItems.item(Material.OAK_DOOR, "<gray>Back to Dashboard</gray>", emptyList(), ctx.messages)) { click ->
            openDashboard(click.player)
        }
        window.open(player)
    }
}
