package protector.modules.audit.ui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.modules.audit.AuditFilterParser
import protector.modules.audit.BlockLogRecord
import protector.modules.audit.ContainerLogRecord
import protector.modules.audit.LookupActionFilter
import protector.modules.audit.LookupFilter
import protector.modules.audit.RollbackService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal class PaperAuditDialogs(
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val rollbackService: RollbackService,
    private val onOpenAuditMenu: (Player, LookupFilter) -> Unit,
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private fun oneShotOptions(): ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).build()

    fun openRollbackFilterDialog(player: Player, restoreMode: Boolean) {
        val cfg = configProvider()
        val timeOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("10m", Component.text("Last 10 minutes"), false),
            SingleOptionDialogInput.OptionEntry.create("1h", Component.text("Last 1 hour"), true),
            SingleOptionDialogInput.OptionEntry.create("6h", Component.text("Last 6 hours"), false),
            SingleOptionDialogInput.OptionEntry.create("24h", Component.text("Last 24 hours"), false),
            SingleOptionDialogInput.OptionEntry.create("7d", Component.text("Last 7 days"), false)
        )
        val actionOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("ALL", Component.text("All (Blocks / Containers / Entities)"), true),
            SingleOptionDialogInput.OptionEntry.create("BLOCK", Component.text("Blocks Only"), false),
            SingleOptionDialogInput.OptionEntry.create("CONTAINER", Component.text("Containers Only"), false),
            SingleOptionDialogInput.OptionEntry.create("ENTITY", Component.text("Entities Only"), false)
        )

        val inputs = listOf(
            DialogInput.text("user", messages.renderRaw("<aqua>Target Player (leave blank for all)</aqua>"))
                .width(240)
                .initial("")
                .maxLength(32)
                .build(),
            DialogInput.singleOption("time", messages.renderRaw("<aqua>Time Range</aqua>"), timeOptions)
                .width(240)
                .build(),
            DialogInput.numberRange("radius", messages.renderRaw("<aqua>Radius (blocks)</aqua>"), 1f, cfg.maxRollbackRadius.toFloat())
                .width(240)
                .labelFormat("%s: %s")
                .initial(15f)
                .step(1f)
                .build(),
            DialogInput.singleOption("scope", messages.renderRaw("<aqua>Action Scope</aqua>"), actionOptions)
                .width(240)
                .build()
        )

        fun buildFilter(response: io.papermc.paper.dialog.DialogResponseView, p: Player): LookupFilter {
            val user = response.getText("user")?.trim()?.takeIf { it.isNotEmpty() }
            val timeToken = response.getText("time") ?: "1h"
            val seconds = AuditFilterParser.parseDurationSeconds(timeToken) ?: 3600L
            val radius = (response.getFloat("radius")?.toInt() ?: 15).coerceIn(1, cfg.maxRollbackRadius)
            val scope = runCatching { LookupActionFilter.valueOf(response.getText("scope") ?: "ALL") }
                .getOrDefault(LookupActionFilter.ALL)

            val loc = p.location
            return LookupFilter(
                worldName = loc.world.name,
                centerX = loc.blockX,
                centerY = loc.blockY,
                centerZ = loc.blockZ,
                radius = radius,
                sinceEpochSec = (System.currentTimeMillis() / 1000L) - seconds,
                userName = user,
                actionFilter = scope,
                limit = 2000
            )
        }

        val previewBtn = ActionButton.builder(messages.renderRaw("<aqua>👁 Preview</aqua>"))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                rollbackService.startPreview(p, buildFilter(response, p), restoreMode)
            }, oneShotOptions()))
            .build()

        val executeBtn = ActionButton.builder(
            messages.renderRaw(if (restoreMode) "<green>⚡ Restore Now</green>" else "<gold>⚡ Rollback Now</gold>")
        )
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                rollbackService.executeDirect(p, buildFilter(response, p), restoreMode)
            }, oneShotOptions()))
            .build()

        val browseBtn = ActionButton.builder(messages.renderRaw("<yellow>📋 Browse GUI</yellow>"))
            .width(140)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                onOpenAuditMenu(p, buildFilter(response, p))
            }, oneShotOptions()))
            .build()

        val closeBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(100).build()

        val title = if (restoreMode) {
            "<green><bold>Audit Restore Filter</bold></green>"
        } else {
            "<gradient:#38bdf8:#fbbf24><bold>Audit Lookup & Rollback Filter</bold></gradient>"
        }
        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw(title))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<gray>Preview, execute, or browse matching changes centered on your current location.</gray>")
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(
                    DialogType.multiAction(listOf(previewBtn, executeBtn, browseBtn))
                        .exitAction(closeBtn)
                        .columns(3)
                        .build()
                )
        }
        player.showDialog(dialog)
    }

    fun openInspectDetailDialog(
        player: Player,
        loc: Location,
        blockLogs: List<BlockLogRecord>,
        containerLogs: List<ContainerLogRecord>
    ) {
        val bodies = ArrayList<DialogBody>()
        val mat = loc.block.type
        if (!mat.isAir && mat.isItem) {
            bodies.add(
                DialogBody.item(ItemStack(mat))
                    .description(
                        DialogBody.plainMessage(
                            messages.renderRaw("<white>${loc.world.name}</white> <gray>(${loc.blockX}, ${loc.blockY}, ${loc.blockZ})</gray>")
                        )
                    )
                    .showDecorations(true)
                    .showTooltip(true)
                    .build()
            )
        } else {
            bodies.add(
                DialogBody.plainMessage(
                    messages.renderRaw("<white>${loc.world.name}</white> <gray>(${loc.blockX}, ${loc.blockY}, ${loc.blockZ})</gray>")
                )
            )
        }

        val summaryLines = buildString {
            if (blockLogs.isNotEmpty()) {
                append("<aqua><bold>[Block History]</bold></aqua>\n")
                for (b in blockLogs.take(6)) {
                    val ts = timeFormatter.format(Instant.ofEpochSecond(b.timeSec))
                    val target = if (b.action.name == "PLACE") b.newMaterial else b.oldMaterial
                    append("<gray>$ts</gray> <white>${b.actorName}</white> <yellow>${b.action.label}</yellow> <aqua>$target</aqua>\n")
                }
            }
            if (containerLogs.isNotEmpty()) {
                append("<gold><bold>[Container History]</bold></gold>\n")
                for (c in containerLogs.take(6)) {
                    val ts = timeFormatter.format(Instant.ofEpochSecond(c.timeSec))
                    append("<gray>$ts</gray> <white>${c.actorName}</white> <gold>${c.action.label}</gold> <aqua>${c.material} ×${c.amount}</aqua>\n")
                }
            }
        }.trimEnd()

        bodies.add(DialogBody.plainMessage(messages.renderRaw(summaryLines), 320))

        val actions = ArrayList<ActionButton>()
        if (player.hasPermission("protector.rollback")) {
            actions.add(
                ActionButton.builder(messages.renderRaw("<gold>↺ Rollback Block (1h)</gold>"))
                    .width(150)
                    .action(DialogAction.customClick({ _, audience ->
                        val p = audience as? Player ?: return@customClick
                        val filter = LookupFilter(
                            worldName = loc.world.name,
                            centerX = loc.blockX,
                            centerY = loc.blockY,
                            centerZ = loc.blockZ,
                            radius = 0,
                            sinceEpochSec = (System.currentTimeMillis() / 1000L) - 3600L,
                            limit = 50
                        )
                        rollbackService.executeDirect(p, filter, restoreMode = false)
                    }, oneShotOptions()))
                    .build()
            )
        }
        actions.add(
            ActionButton.builder(messages.renderRaw("<aqua>⚑ Teleport Here</aqua>"))
                .width(130)
                .action(DialogAction.customClick({ _, audience ->
                    val p = audience as? Player ?: return@customClick
                    p.teleport(loc.clone().add(0.5, 1.0, 0.5))
                }, oneShotOptions()))
                .build()
        )

        val closeBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<#fbbf24><bold>Audit Inspector Details</bold></#fbbf24>"))
                        .canCloseWithEscape(true)
                        .body(bodies)
                        .build()
                )
                .type(
                    DialogType.multiAction(actions)
                        .exitAction(closeBtn)
                        .columns(2)
                        .build()
                )
        }
        player.showDialog(dialog)
    }

    fun openActivitySearchDialog(player: Player) {
        val typeOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("CHAT", Component.text("Chat & Commands"), true),
            SingleOptionDialogInput.OptionEntry.create("SESSION", Component.text("Login / Logout Sessions"), false),
            SingleOptionDialogInput.OptionEntry.create("SIGN", Component.text("Nearby Sign Records"), false)
        )
        val timeOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("1h", Component.text("Last 1 hour"), false),
            SingleOptionDialogInput.OptionEntry.create("24h", Component.text("Last 24 hours"), true),
            SingleOptionDialogInput.OptionEntry.create("7d", Component.text("Last 7 days"), false)
        )
        val inputs = listOf(
            DialogInput.text("user", messages.renderRaw("<aqua>Player Name (leave blank for all)</aqua>"))
                .width(240)
                .initial("")
                .maxLength(32)
                .build(),
            DialogInput.singleOption("type", messages.renderRaw("<aqua>Activity Type</aqua>"), typeOptions)
                .width(240)
                .build(),
            DialogInput.singleOption("time", messages.renderRaw("<aqua>Time Range</aqua>"), timeOptions)
                .width(240)
                .build()
        )

        val searchBtn = ActionButton.builder(messages.renderRaw("<aqua>🔍 Open GUI Browser</aqua>"))
            .width(170)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val user = response.getText("user")?.trim()?.takeIf { it.isNotEmpty() }
                val type = runCatching { LookupActionFilter.valueOf(response.getText("type") ?: "CHAT") }
                    .getOrDefault(LookupActionFilter.CHAT)
                val sec = AuditFilterParser.parseDurationSeconds(response.getText("time") ?: "24h") ?: 86400L
                val loc = p.location
                onOpenAuditMenu(
                    p,
                    LookupFilter(
                        worldName = loc.world.name,
                        centerX = loc.blockX,
                        centerY = loc.blockY,
                        centerZ = loc.blockZ,
                        radius = 40,
                        sinceEpochSec = (System.currentTimeMillis() / 1000L) - sec,
                        userName = user,
                        actionFilter = type,
                        limit = 100
                    )
                )
            }, oneShotOptions()))
            .build()

        val closeBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<yellow><bold>Activity Log Search</bold></yellow>"))
                        .canCloseWithEscape(true)
                        .body(listOf(DialogBody.plainMessage(messages.renderRaw("<gray>Search chat, command, session, or sign logs.</gray>"))))
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(searchBtn, closeBtn))
        }
        player.showDialog(dialog)
    }
}
