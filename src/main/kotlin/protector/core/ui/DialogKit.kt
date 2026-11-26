package protector.core.ui

import org.bukkit.Material
import org.bukkit.entity.Player
import protector.core.compat.PlatformCompat
import protector.core.locale.MessageService

class DialogKit(private val messages: MessageService) {

    val supportsNativeDialogs: Boolean
        get() = PlatformCompat.supportsDialogs

    private val paperBackend: PaperDialogKit? by lazy {
        if (supportsNativeDialogs) PaperDialogKit(messages) else null
    }

    fun openConfirmationDialog(
        player: Player,
        titleMiniMessage: String,
        bodyMiniMessage: String,
        confirmLabelMiniMessage: String = "<red>Confirm</red>",
        onConfirm: (Player) -> Unit,
    ) {
        val backend = paperBackend
        if (backend != null) {
            backend.openConfirmationDialog(player, titleMiniMessage, bodyMiniMessage, confirmLabelMiniMessage, onConfirm)
            return
        }
        openConfirmationMenu(player, titleMiniMessage, bodyMiniMessage, onConfirm)
    }

    fun openConfirmationMenu(
        player: Player,
        titleMiniMessage: String,
        bodyMiniMessage: String,
        onConfirm: (Player) -> Unit,
    ) {
        val window = MenuWindow(messages.renderRaw(titleMiniMessage), 3)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)
        window.set(
            11,
            MenuItems.item(
                Material.LIME_CONCRETE,
                "<green><bold>✔ Confirm</bold></green>",
                bodyMiniMessage.split('\n').map { "<gray>$it</gray>" },
                messages,
            ),
        ) { click ->
            click.player.closeInventory()
            onConfirm(click.player)
        }
        window.set(
            13,
            MenuItems.item(
                Material.PAPER,
                "<yellow>Confirmation Required</yellow>",
                bodyMiniMessage.split('\n'),
                messages,
            ),
        )
        window.set(
            15,
            MenuItems.item(
                Material.RED_CONCRETE,
                "<red><bold>✖ Cancel</bold></red>",
                listOf("<gray>Close without making changes.</gray>"),
                messages,
            ),
        ) { click ->
            click.player.closeInventory()
        }
        window.open(player)
    }
}
