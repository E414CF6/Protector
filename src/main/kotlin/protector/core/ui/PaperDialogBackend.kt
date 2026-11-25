package protector.core.ui

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
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import protector.modules.audit.RollbackService
import protector.modules.claim.Claim
import protector.modules.claim.ClaimFlag
import protector.modules.claim.ClaimManager
import protector.modules.claim.ClaimPermission
import protector.core.locale.MessageService
import protector.core.config.ProtectorConfig
import protector.core.storage.BlockLogRecord
import protector.core.storage.ContainerLogRecord
import protector.core.storage.LookupActionFilter
import protector.core.storage.LookupFilter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Isolated Paper 1.21.6+ / 26.x Dialog API backend.
 * Only loaded when [protector.core.compat.PlatformCompat.supportsDialogs] is true.
 */
internal class PaperDialogBackend(
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val rollbackService: RollbackService,
    private val onOpenAuditMenu: () -> ((Player, LookupFilter) -> Unit)?,
    private val onReopenClaimDetail: () -> ((Player, Claim) -> Unit)?
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    private fun oneShotOptions(): ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).build()

    fun openClaimSettingsDialog(player: Player, claim: Claim) {
        val cfg = configProvider()
        val inputs = listOf(
            DialogInput.text("name", messages.renderRaw("<aqua>Claim Name</aqua>"))
                .width(260)
                .initial(claim.name)
                .maxLength(48)
                .build(),
            DialogInput.text("greeting", messages.renderRaw("<aqua>Entry Greeting (leave blank for none)</aqua>"))
                .width(260)
                .initial(claim.greeting ?: "")
                .maxLength(100)
                .build(),
            DialogInput.bool("pvp", messages.renderRaw("<red>Allow PvP Inside Claim</red>"))
                .initial(claim.flag(ClaimFlag.PVP, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("explosions", messages.renderRaw("<gold>Allow Explosion Damage</gold>"))
                .initial(claim.flag(ClaimFlag.EXPLOSIONS, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("fire_spread", messages.renderRaw("<yellow>Allow Fire Spread</yellow>"))
                .initial(claim.flag(ClaimFlag.FIRE_SPREAD, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("mob_griefing", messages.renderRaw("<green>Allow Mob Griefing</green>"))
                .initial(claim.flag(ClaimFlag.MOB_GRIEFING, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("public_access", messages.renderRaw("<aqua>Allow Public Door/Button Access</aqua>"))
                .initial(claim.flag(ClaimFlag.PUBLIC_ACCESS, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build(),
            DialogInput.bool("entry_notify", messages.renderRaw("<light_purple>Show Entry Notification</light_purple>"))
                .initial(claim.flag(ClaimFlag.ENTRY_NOTIFY, cfg.defaultFlags))
                .onTrue("true")
                .onFalse("false")
                .build()
        )

        val saveBtn = ActionButton.builder(messages.renderRaw("<green>✔ Save Settings</green>"))
            .width(130)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val newName = response.getText("name")?.trim()?.takeIf { it.isNotEmpty() } ?: claim.name
                val newGreeting = response.getText("greeting")?.trim()
                claimManager.updateMetadata(claim, newName, newGreeting)

                response.getBoolean("pvp")?.let { claimManager.setFlag(claim, ClaimFlag.PVP, it) }
                response.getBoolean("explosions")?.let { claimManager.setFlag(claim, ClaimFlag.EXPLOSIONS, it) }
                response.getBoolean("fire_spread")?.let { claimManager.setFlag(claim, ClaimFlag.FIRE_SPREAD, it) }
                response.getBoolean("mob_griefing")?.let { claimManager.setFlag(claim, ClaimFlag.MOB_GRIEFING, it) }
                response.getBoolean("public_access")?.let { claimManager.setFlag(claim, ClaimFlag.PUBLIC_ACCESS, it) }
                response.getBoolean("entry_notify")?.let { claimManager.setFlag(claim, ClaimFlag.ENTRY_NOTIFY, it) }

                messages.sendRaw(p, "<green>Saved settings for claim [<white>${claim.name}</white>].</green>")
                onReopenClaimDetail()?.invoke(p, claim)
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(110).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<gradient:#38bdf8:#818cf8><bold>Claim Settings</bold></gradient>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw(
                                        "<gray>Claim ID:</gray> <white>#${claim.id}</white>  <gray>| Size:</gray> <white>${claim.bounds.width}×${claim.bounds.length} (${claim.bounds.area} blocks)</white>"
                                    )
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(saveBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

    fun openTrustAddDialog(player: Player, claim: Claim) {
        val tierOptions = listOf(
            SingleOptionDialogInput.OptionEntry.create("ACCESS", messages.renderRaw("<gray>Access (Doors / Buttons / Levers)</gray>"), false),
            SingleOptionDialogInput.OptionEntry.create("CONTAINER", messages.renderRaw("<gold>Container (Chests / Animals / Crops)</gold>"), false),
            SingleOptionDialogInput.OptionEntry.create("BUILD", messages.renderRaw("<green>Build (Place / Break Blocks)</green>"), true),
            SingleOptionDialogInput.OptionEntry.create("MANAGE", messages.renderRaw("<aqua>Manage (Edit Trusts & Settings)</aqua>"), false)
        )

        val inputs = listOf(
            DialogInput.text("target", messages.renderRaw("<aqua>Player Name (or 'public' for everyone)</aqua>"))
                .width(240)
                .initial("")
                .maxLength(32)
                .build(),
            DialogInput.singleOption("tier", messages.renderRaw("<aqua>Trust Level</aqua>"), tierOptions)
                .width(240)
                .build()
        )

        val confirmBtn = ActionButton.builder(messages.renderRaw("<green>✔ Grant Trust</green>"))
            .width(130)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val rawTarget = response.getText("target")?.trim().orEmpty()
                if (rawTarget.isEmpty()) {
                    messages.sendRaw(p, "<red>Please enter a target player name.</red>")
                    return@customClick
                }
                val tierName = response.getText("tier") ?: "BUILD"
                val perm = runCatching { ClaimPermission.valueOf(tierName) }.getOrDefault(ClaimPermission.BUILD)

                val key = if (rawTarget.equals("public", ignoreCase = true)) {
                    Claim.PUBLIC_KEY
                } else {
                    Bukkit.getOfflinePlayer(rawTarget).uniqueId.toString()
                }
                claimManager.setTrust(claim, key, perm)
                messages.send(p, "trust-updated", mapOf("target" to rawTarget, "level" to perm.displayName))
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<aqua><bold>Add Claim Trust</bold></aqua>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<gray>Target Claim:</gray> <white>${claim.name}</white>")
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(confirmBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

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
            val seconds = RollbackService.parseDurationSeconds(timeToken) ?: 3600L
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
                onOpenAuditMenu()?.invoke(p, buildFilter(response, p))
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

    fun openConfirmationDialog(
        player: Player,
        titleMiniMessage: String,
        bodyMiniMessage: String,
        confirmLabelMiniMessage: String,
        onConfirm: (Player) -> Unit
    ) {
        val yesBtn = ActionButton.builder(messages.renderRaw(confirmLabelMiniMessage))
            .width(120)
            .action(DialogAction.customClick({ _, audience ->
                val p = audience as? Player ?: return@customClick
                onConfirm(p)
            }, oneShotOptions()))
            .build()

        val noBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(120).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw(titleMiniMessage))
                        .canCloseWithEscape(true)
                        .body(listOf(DialogBody.plainMessage(messages.renderRaw(bodyMiniMessage))))
                        .build()
                )
                .type(DialogType.confirmation(yesBtn, noBtn))
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

    fun openTransferClaimDialog(player: Player, claim: Claim) {
        val input = DialogInput.text("target", messages.renderRaw("<aqua>New Owner Player Name</aqua>"))
            .width(240)
            .initial("")
            .maxLength(32)
            .build()

        val confirmBtn = ActionButton.builder(messages.renderRaw("<gold>✔ Transfer Ownership</gold>"))
            .width(150)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val targetName = response.getText("target")?.trim().orEmpty()
                if (targetName.isEmpty()) {
                    messages.sendRaw(p, "<red>Please enter a target player name.</red>")
                    return@customClick
                }
                val offline = Bukkit.getOfflinePlayer(targetName)
                claimManager.transferClaim(claim, offline.uniqueId)
                messages.send(p, "claim-transferred", mapOf("target" to targetName))
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<gold><bold>Transfer Claim Ownership</bold></gold>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<yellow>Warning:</yellow> <gray>Ownership of <white>${claim.name}</white> and all its subclaims will be transferred.</gray>")
                                )
                            )
                        )
                        .inputs(listOf(input))
                        .build()
                )
                .type(DialogType.confirmation(confirmBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }

    fun openSubclaimSettingsDialog(player: Player, subclaim: Claim) {
        val inputs = listOf(
            DialogInput.text("name", messages.renderRaw("<aqua>Subclaim Name</aqua>"))
                .width(240)
                .initial(subclaim.name)
                .maxLength(48)
                .build(),
            DialogInput.bool("inheritNothing", messages.renderRaw("<red>Block Parent Inheritance (Isolated)</red>"))
                .initial(subclaim.inheritNothing)
                .onTrue("true")
                .onFalse("false")
                .build()
        )

        val saveBtn = ActionButton.builder(messages.renderRaw("<green>✔ Save</green>"))
            .width(120)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val newName = response.getText("name")?.trim()?.takeIf { it.isNotEmpty() } ?: subclaim.name
                val isolate = response.getBoolean("inheritNothing") ?: subclaim.inheritNothing
                claimManager.updateMetadata(subclaim, newName, subclaim.greeting, isolate)
                messages.send(p, "subclaim-restricted", mapOf("state" to if (isolate) "Isolated" else "Inheriting"))
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Cancel</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<light_purple><bold>Subclaim Settings</bold></light_purple>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    messages.renderRaw("<gray>Parent Claim:</gray> <white>${subclaim.parent?.name ?: "-"}</white>")
                                )
                            )
                        )
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(saveBtn, cancelBtn))
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
                val sec = RollbackService.parseDurationSeconds(response.getText("time") ?: "24h") ?: 86400L
                val loc = p.location
                onOpenAuditMenu()?.invoke(
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

    fun openAdminClaimBlocksDialog(player: Player) {
        val inputs = listOf(
            DialogInput.text("target", messages.renderRaw("<aqua>Target Player Name</aqua>"))
                .width(240)
                .initial(player.name)
                .maxLength(32)
                .build(),
            DialogInput.numberRange("amount", messages.renderRaw("<aqua>Bonus Claim Blocks Delta</aqua>"), -5000f, 10000f)
                .width(240)
                .labelFormat("%s: %s")
                .initial(500f)
                .step(100f)
                .build()
        )

        val applyBtn = ActionButton.builder(messages.renderRaw("<green>✔ Apply Adjustment</green>"))
            .width(150)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                val targetName = response.getText("target")?.trim().orEmpty()
                if (targetName.isEmpty()) return@customClick
                val delta = response.getFloat("amount")?.toInt() ?: 0
                val offline = Bukkit.getOfflinePlayer(targetName)
                claimManager.addBonusBlocks(offline.uniqueId, delta)
                val data = claimManager.getOrCreatePlayerData(offline.uniqueId)
                val avail = claimManager.getAvailableClaimBlocks(offline.uniqueId)
                messages.send(
                    p,
                    "claim-blocks-adjusted",
                    mapOf("target" to targetName, "bonus" to data.bonusBlocks, "available" to avail)
                )
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(messages.renderRaw("<gray>Close</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(messages.renderRaw("<red><bold>Admin: Adjust Claim Blocks</bold></red>"))
                        .canCloseWithEscape(true)
                        .body(listOf(DialogBody.plainMessage(messages.renderRaw("<gray>Add or subtract bonus claim blocks for a player.</gray>"))))
                        .inputs(inputs)
                        .build()
                )
                .type(DialogType.confirmation(applyBtn, cancelBtn))
        }
        player.showDialog(dialog)
    }
}
