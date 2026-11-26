package protector.modules.audit.ui

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import protector.core.locale.MessageService
import protector.core.scheduler.PluginScheduler
import protector.core.ui.MenuItems
import protector.core.ui.MenuWindow
import protector.modules.audit.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

sealed interface AuditGuiItem {
    val worldName: String
    val x: Int
    val y: Int
    val z: Int
    val timeSec: Long

    data class BlockItem(val record: BlockLogRecord) : AuditGuiItem {
        override val worldName get() = record.worldName
        override val x get() = record.x
        override val y get() = record.y
        override val z get() = record.z
        override val timeSec get() = record.timeSec
    }

    data class ContainerItem(val record: ContainerLogRecord) : AuditGuiItem {
        override val worldName get() = record.worldName
        override val x get() = record.x
        override val y get() = record.y
        override val z get() = record.z
        override val timeSec get() = record.timeSec
    }

    data class EntityItem(val record: EntityLogRecord) : AuditGuiItem {
        override val worldName get() = record.worldName
        override val x get() = record.x
        override val y get() = record.y
        override val z get() = record.z
        override val timeSec get() = record.timeSec
    }

    data class SignItem(val record: SignLogRecord) : AuditGuiItem {
        override val worldName get() = record.worldName
        override val x get() = record.x
        override val y get() = record.y
        override val z get() = record.z
        override val timeSec get() = record.timeSec
    }

    data class ChatItem(val record: ChatLogRecord) : AuditGuiItem {
        override val worldName get() = record.worldName
        override val x get() = record.x
        override val y get() = record.y
        override val z get() = record.z
        override val timeSec get() = record.timeSec
    }

    data class SessionItem(val record: SessionLogRecord) : AuditGuiItem {
        override val worldName get() = record.worldName
        override val x get() = record.x
        override val y get() = record.y
        override val z get() = record.z
        override val timeSec get() = record.timeSec
    }
}

class AuditMenus(
    private val scheduler: PluginScheduler,
    private val messages: MessageService,
    private val repository: AuditLogRepository,
    private val consumer: LogQueueConsumer,
    private val rollbackService: RollbackService,
    private val dialogsProvider: () -> AuditDialogs,
    private val onBackToDashboard: (Player) -> Unit,
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun openAuditBrowserAsync(player: Player, filter: LookupFilter, page: Int = 0) {
        scheduler.runAsync {
            consumer.flushNow()
            val items = ArrayList<AuditGuiItem>()
            when (filter.actionFilter) {
                LookupActionFilter.ALL -> {
                    repository.queryBlockLogs(filter.copy(limit = 60)).forEach { items.add(AuditGuiItem.BlockItem(it)) }
                    repository.queryContainerLogs(filter.copy(limit = 40)).forEach { items.add(AuditGuiItem.ContainerItem(it)) }
                    repository.queryEntityLogs(filter.copy(limit = 30)).forEach { items.add(AuditGuiItem.EntityItem(it)) }
                    repository.querySignLogs(filter.copy(limit = 20)).forEach { items.add(AuditGuiItem.SignItem(it)) }
                }
                LookupActionFilter.BLOCK ->
                    repository.queryBlockLogs(filter.copy(limit = 120)).forEach { items.add(AuditGuiItem.BlockItem(it)) }
                LookupActionFilter.CONTAINER ->
                    repository.queryContainerLogs(filter.copy(limit = 120)).forEach { items.add(AuditGuiItem.ContainerItem(it)) }
                LookupActionFilter.ENTITY ->
                    repository.queryEntityLogs(filter.copy(limit = 120)).forEach { items.add(AuditGuiItem.EntityItem(it)) }
                LookupActionFilter.SIGN ->
                    repository.querySignLogs(filter.copy(limit = 120)).forEach { items.add(AuditGuiItem.SignItem(it)) }
                LookupActionFilter.CHAT ->
                    repository.queryChatLogs(filter.sinceEpochSec, filter.userName, null, 120, filter.textFilter)
                        .forEach { items.add(AuditGuiItem.ChatItem(it)) }
                LookupActionFilter.SESSION ->
                    repository.querySessionLogs(filter.sinceEpochSec, filter.userName, 120)
                        .forEach { items.add(AuditGuiItem.SessionItem(it)) }
            }
            items.sortByDescending { it.timeSec }

            scheduler.runForEntity(player) {
                if (!player.isOnline) return@runForEntity
                openAuditBrowser(player, items, filter, page)
            }
        }
    }

    fun openAuditBrowser(player: Player, items: List<AuditGuiItem>, filter: LookupFilter, page: Int = 0) {
        val pageSize = 28
        val totalPages = maxOf(1, (items.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, totalPages - 1)

        val window = MenuWindow(
            messages.renderRaw("<gold><bold>Audit Logs (${items.size} · ${safePage + 1}/$totalPages)</bold></gold>"),
            6
        )
        window.fillBorder(Material.BLACK_STAINED_GLASS_PANE)

        val tabs = listOf(
            1 to (LookupActionFilter.ALL to (Material.NETHER_STAR to "All")),
            2 to (LookupActionFilter.BLOCK to (Material.COBBLESTONE to "Blocks")),
            3 to (LookupActionFilter.CONTAINER to (Material.CHEST to "Containers")),
            4 to (LookupActionFilter.ENTITY to (Material.IRON_SWORD to "Entities")),
            5 to (LookupActionFilter.SIGN to (Material.OAK_SIGN to "Signs")),
            6 to (LookupActionFilter.CHAT to (Material.WRITABLE_BOOK to "Chat/Cmd")),
            7 to (LookupActionFilter.SESSION to (Material.COMPASS to "Sessions"))
        )
        for ((slot, spec) in tabs) {
            val (scope, iconSpec) = spec
            val selected = filter.actionFilter == scope
            window.set(
                slot,
                MenuItems.item(
                    iconSpec.first,
                    if (selected) "<green><bold>[${iconSpec.second}]</bold></green>" else "<gray>${iconSpec.second}</gray>",
                    listOf("<yellow>Click to switch tab</yellow>"),
                    messages,
                )
            ) { click ->
                openAuditBrowserAsync(click.player, filter.copy(actionFilter = scope), 0)
            }
        }

        items.drop(safePage * pageSize).take(pageSize).forEachIndexed { idx, entry ->
            val slot = MenuWindow.CONTENT_SLOTS[idx]
            val ts = timeFormatter.format(Instant.ofEpochSecond(entry.timeSec))
            val item = when (entry) {
                is AuditGuiItem.BlockItem -> {
                    val r = entry.record
                    val matName = if (r.action.name == "PLACE") r.newMaterial else r.oldMaterial
                    val mat = Material.matchMaterial(matName)?.takeIf { it.isItem } ?: Material.STONE
                    MenuItems.item(
                        mat,
                        "<aqua>[Block ${r.action.label}] $matName</aqua>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>(${r.x}, ${r.y}, ${r.z})</white>",
                            "<yellow>Click: Open Inspector Dialog</yellow>"
                        ),
                        messages,
                    )
                }
                is AuditGuiItem.ContainerItem -> {
                    val r = entry.record
                    val mat = Material.matchMaterial(r.material)?.takeIf { it.isItem } ?: Material.CHEST
                    MenuItems.item(
                        mat,
                        "<gold>[Container ${r.action.label}] ${r.material} ×${r.amount}</gold>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>(${r.x}, ${r.y}, ${r.z})</white>",
                            "<yellow>Click: Open Inspector Dialog</yellow>"
                        ),
                        messages,
                    )
                }
                is AuditGuiItem.EntityItem -> {
                    val r = entry.record
                    MenuItems.item(
                        Material.IRON_SWORD,
                        "<red>[Entity ${r.action.label}] ${r.entityType}</red>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>(${r.x}, ${r.y}, ${r.z})</white>",
                            "<yellow>Click: Teleport to location</yellow>"
                        ),
                        messages,
                    )
                }
                is AuditGuiItem.SignItem -> {
                    val r = entry.record
                    MenuItems.item(
                        Material.OAK_SIGN,
                        "<yellow>[Sign ${if (r.action == 0) "Broken" else "Edited"}] (${r.side})</yellow>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Lines:</gray> <white>${r.linesJson}</white>",
                            "<yellow>Click: Teleport to location</yellow>"
                        ),
                        messages,
                    )
                }
                is AuditGuiItem.ChatItem -> {
                    val r = entry.record
                    MenuItems.item(
                        if (r.isCommand) Material.COMMAND_BLOCK else Material.PAPER,
                        if (r.isCommand) "<gold>[Command] ${r.actorName}</gold>" else "<white>[Chat] ${r.actorName}</white>",
                        listOf(
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Text:</gray> <aqua>${r.message}</aqua>",
                            "<gray>Location:</gray> <white>${r.worldName} (${r.x}, ${r.y}, ${r.z})</white>"
                        ),
                        messages,
                    )
                }
                is AuditGuiItem.SessionItem -> {
                    val r = entry.record
                    MenuItems.item(
                        if (r.login) Material.LIME_DYE else Material.RED_DYE,
                        if (r.login) "<green>[Login] ${r.actorName}</green>" else "<red>[Logout] ${r.actorName}</red>",
                        listOf(
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>${r.worldName} (${r.x}, ${r.y}, ${r.z})</white>"
                        ),
                        messages,
                    )
                }
            }

            window.set(slot, item) { click ->
                val world = Bukkit.getWorld(entry.worldName) ?: return@set
                val loc = Location(world, entry.x.toDouble(), entry.y.toDouble(), entry.z.toDouble())
                click.player.closeInventory()
                when (entry) {
                    is AuditGuiItem.BlockItem -> dialogsProvider().openInspectDetailDialog(click.player, loc, listOf(entry.record), emptyList())
                    is AuditGuiItem.ContainerItem -> dialogsProvider().openInspectDetailDialog(click.player, loc, emptyList(), listOf(entry.record))
                    else -> click.player.teleport(loc.clone().add(0.5, 1.0, 0.5))
                }
            }
        }

        if (safePage > 0) {
            window.set(45, MenuItems.item(Material.ARROW, "<yellow>Previous Page</yellow>", emptyList(), messages)) { click ->
                openAuditBrowser(click.player, items, filter, safePage - 1)
            }
        }
        window.set(48, MenuItems.item(Material.CLOCK, "<aqua>Edit Filter (Dialog)</aqua>", emptyList(), messages)) { click ->
            click.player.closeInventory()
            dialogsProvider().openRollbackFilterDialog(click.player, restoreMode = false)
        }
        window.set(49, MenuItems.item(Material.OAK_DOOR, "<gray>Dashboard</gray>", emptyList(), messages)) { click ->
            onBackToDashboard(click.player)
        }
        if (safePage + 1 < totalPages) {
            window.set(53, MenuItems.item(Material.ARROW, "<yellow>Next Page</yellow>", emptyList(), messages)) { click ->
                openAuditBrowser(click.player, items, filter, safePage + 1)
            }
        }

        window.open(player)
    }

    fun openRollbackPresetMenu(player: Player, restoreMode: Boolean) {
        val verb = if (restoreMode) "Restore" else "Rollback"
        val window = MenuWindow(messages.renderRaw("<gold><bold>$verb Presets (r:15)</bold></gold>"), 3)
        window.fillBorder(Material.GRAY_STAINED_GLASS_PANE)

        val presets = listOf(10 to "10m", 12 to "1h", 14 to "24h", 16 to "7d")
        for ((slot, token) in presets) {
            window.set(
                slot,
                MenuItems.item(
                    Material.CLOCK,
                    "<aqua>Preview $verb: Last $token (r:15)</aqua>",
                    listOf("<yellow>Left-click: Start preview", "<gold>Right-click: Browse logs GUI</gold>"),
                    messages,
                )
            ) { click ->
                val sec = AuditFilterParser.parseDurationSeconds(token) ?: 3600L
                val loc = click.player.location
                val filter = LookupFilter(
                    worldName = loc.world.name,
                    centerX = loc.blockX,
                    centerY = loc.blockY,
                    centerZ = loc.blockZ,
                    radius = 15,
                    sinceEpochSec = (System.currentTimeMillis() / 1000L) - sec,
                    limit = 2000
                )
                if (click.clickType.isRightClick) {
                    openAuditBrowserAsync(click.player, filter, 0)
                } else {
                    click.player.closeInventory()
                    rollbackService.startPreview(click.player, filter, restoreMode)
                }
            }
        }
        window.set(22, MenuItems.item(Material.OAK_DOOR, "<gray>Dashboard</gray>", emptyList(), messages)) { click ->
            onBackToDashboard(click.player)
        }
        window.open(player)
    }
}
