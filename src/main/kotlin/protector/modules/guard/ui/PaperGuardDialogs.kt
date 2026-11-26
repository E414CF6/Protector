package protector.modules.guard.ui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.input.DialogInput
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import protector.core.ProtectorContext

internal class PaperGuardDialogs(private val ctx: ProtectorContext) {

    private fun oneShotOptions(): ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).build()

    fun openGuardSettingsDialog(player: Player) {
        val g = ctx.config.guard
        val inputs = listOf(
            DialogInput.bool("tnt_blocks", ctx.messages.renderRaw("<red>Prevent TNT Block Damage</red>"))
                .initial(g.tntPreventBlockDamage).onTrue("true").onFalse("false").build(),
            DialogInput.bool("tnt_prime", ctx.messages.renderRaw("<red>Prevent TNT Ignition (Prime)</red>"))
                .initial(g.tntPreventPrime).onTrue("true").onFalse("false").build(),
            DialogInput.bool("tnt_entity", ctx.messages.renderRaw("<gold>Prevent TNT Entity Damage</gold>"))
                .initial(g.tntPreventEntityDamage).onTrue("true").onFalse("false").build(),
            DialogInput.bool("tnt_cart", ctx.messages.renderRaw("<gold>Prevent TNT Minecart Damage</gold>"))
                .initial(g.tntPreventMinecart).onTrue("true").onFalse("false").build(),
            DialogInput.bool("creeper", ctx.messages.renderRaw("<green>Prevent Creeper Block Damage</green>"))
                .initial(g.preventCreeperBlockDamage).onTrue("true").onFalse("false").build(),
            DialogInput.bool("wither", ctx.messages.renderRaw("<dark_purple>Prevent Wither Griefing</dark_purple>"))
                .initial(g.preventWitherDamage).onTrue("true").onFalse("false").build(),
            DialogInput.bool("fire_spread", ctx.messages.renderRaw("<yellow>Prevent Fire Spread & Burn</yellow>"))
                .initial(g.preventFireSpread).onTrue("true").onFalse("false").build(),
            DialogInput.bool("enderman", ctx.messages.renderRaw("<light_purple>Prevent Enderman Block Grief</light_purple>"))
                .initial(g.preventEndermanGrief).onTrue("true").onFalse("false").build(),
            DialogInput.bool("death_drops", ctx.messages.renderRaw("<aqua>Protect Non-PvP Death Drops</aqua>"))
                .initial(g.protectDeathDrops).onTrue("true").onFalse("false").build(),
        )

        val saveBtn = ActionButton.builder(ctx.messages.renderRaw("<green>✔ Save Guard Rules</green>"))
            .width(150)
            .action(DialogAction.customClick({ response, audience ->
                val p = audience as? Player ?: return@customClick
                ctx.updateGuardSettings { cur ->
                    val fire = response.getBoolean("fire_spread") ?: cur.preventFireSpread
                    val wither = response.getBoolean("wither") ?: cur.preventWitherDamage
                    cur.copy(
                        tntPreventBlockDamage = response.getBoolean("tnt_blocks") ?: cur.tntPreventBlockDamage,
                        tntPreventPrime = response.getBoolean("tnt_prime") ?: cur.tntPreventPrime,
                        tntPreventEntityDamage = response.getBoolean("tnt_entity") ?: cur.tntPreventEntityDamage,
                        tntPreventMinecart = response.getBoolean("tnt_cart") ?: cur.tntPreventMinecart,
                        preventCreeperBlockDamage = response.getBoolean("creeper") ?: cur.preventCreeperBlockDamage,
                        preventWitherDamage = wither,
                        preventWitherBlockBreak = wither,
                        preventFireSpread = fire,
                        preventBlockBurn = fire,
                        preventEndermanGrief = response.getBoolean("enderman") ?: cur.preventEndermanGrief,
                        protectDeathDrops = response.getBoolean("death_drops") ?: cur.protectDeathDrops,
                    )
                }
                ctx.messages.sendRaw(p, "<green>Saved world & TNT guard rules to config.yml.</green>")
            }, oneShotOptions()))
            .build()

        val cancelBtn = ActionButton.builder(ctx.messages.renderRaw("<gray>Close</gray>")).width(100).build()

        val dialog = Dialog.create { factory ->
            factory.empty()
                .base(
                    DialogBase.builder(ctx.messages.renderRaw("<gradient:#ef4444:#f59e0b><bold>World & TNT Guard Rules</bold></gradient>"))
                        .canCloseWithEscape(true)
                        .body(
                            listOf(
                                DialogBody.plainMessage(
                                    ctx.messages.renderRaw("<gray>Configure global explosion, TNT, fire, mob grief, and death drop rules.</gray>")
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
}
