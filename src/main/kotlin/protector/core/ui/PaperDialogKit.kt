package protector.core.ui

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.entity.Player
import protector.core.locale.MessageService

class PaperDialogKit(private val messages: MessageService) {

    fun oneShotOptions(): ClickCallback.Options =
        ClickCallback.Options.builder().uses(1).build()

    fun openConfirmationDialog(
        player: Player,
        titleMiniMessage: String,
        bodyMiniMessage: String,
        confirmLabelMiniMessage: String,
        onConfirm: (Player) -> Unit,
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
}
