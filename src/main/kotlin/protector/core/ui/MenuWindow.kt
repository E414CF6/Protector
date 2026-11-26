package protector.core.ui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.OfflinePlayer
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.meta.SkullMeta
import protector.core.locale.MessageService

data class MenuClick(
    val player: Player,
    val slot: Int,
    val clickType: ClickType,
)

class MenuWindow(
    title: Component,
    rows: Int,
) : InventoryHolder {

    private val inv: Inventory = Bukkit.createInventory(this, (rows.coerceIn(1, 6)) * 9, title)
    private val handlers = HashMap<Int, (MenuClick) -> Unit>()

    override fun getInventory(): Inventory = inv

    fun set(slot: Int, item: ItemStack, onClick: ((MenuClick) -> Unit)? = null) {
        if (slot !in 0 until inv.size) return
        inv.setItem(slot, item)
        if (onClick != null) {
            handlers[slot] = onClick
        } else {
            handlers.remove(slot)
        }
    }

    fun fillBorder(material: Material) {
        val filler = ItemStack(material).apply {
            editMeta { it.displayName(Component.text(" ")) }
        }
        val size = inv.size
        val rows = size / 9
        for (i in 0 until size) {
            val row = i / 9
            val col = i % 9
            if (row == 0 || row == rows - 1 || col == 0 || col == 8) {
                inv.setItem(i, filler)
            }
        }
    }

    fun open(player: Player) {
        player.openInventory(inv)
    }

    internal fun handleClick(event: InventoryClickEvent) {
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return
        val player = event.whoClicked as? Player ?: return
        val handler = handlers[event.slot] ?: return
        handler(MenuClick(player, event.slot, event.click))
    }

    companion object {
        val CONTENT_SLOTS: List<Int> = (10..16) + (19..25) + (28..34) + (37..43)
    }
}

object MenuItems {
    fun item(
        material: Material,
        nameMiniMessage: String,
        loreMiniMessages: List<String>,
        messages: MessageService,
    ): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(messages.renderRaw(nameMiniMessage))
            if (loreMiniMessages.isNotEmpty()) {
                meta.lore(loreMiniMessages.map { messages.renderRaw(it) })
            }
        }
        return stack
    }

    fun playerHead(
        target: OfflinePlayer,
        nameMiniMessage: String,
        loreMiniMessages: List<String>,
        messages: MessageService,
    ): ItemStack {
        val stack = ItemStack(Material.PLAYER_HEAD)
        stack.editMeta { meta ->
            if (meta is SkullMeta) {
                runCatching { meta.owningPlayer = target }
            }
            meta.displayName(messages.renderRaw(nameMiniMessage))
            if (loreMiniMessages.isNotEmpty()) {
                meta.lore(loreMiniMessages.map { messages.renderRaw(it) })
            }
        }
        return stack
    }
}

class MenuListener : Listener {
    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val window = event.inventory.holder as? MenuWindow ?: return
        window.handleClick(event)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is MenuWindow) {
            event.isCancelled = true
        }
    }
}
