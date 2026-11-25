package protector.core.ui

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
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
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import protector.modules.audit.InspectorService
import protector.modules.audit.LogQueueConsumer
import protector.modules.audit.RollbackService
import protector.modules.claim.BoundaryVisualizer
import protector.modules.claim.Claim
import protector.modules.claim.ClaimFlag
import protector.modules.claim.ClaimManager
import protector.modules.claim.ClaimPermission
import protector.modules.claim.DeathDropProtectionService
import protector.modules.claim.ShovelMode
import protector.core.locale.MessageService
import protector.core.config.ProtectorConfig
import protector.core.scheduler.ProtectorScheduler
import protector.core.storage.AuditLogRepository
import protector.core.storage.BlockLogRecord
import protector.core.storage.ChatLogRecord
import protector.core.storage.ContainerLogRecord
import protector.core.storage.EntityLogRecord
import protector.core.storage.LookupActionFilter
import protector.core.storage.LookupFilter
import protector.core.storage.SessionLogRecord
import protector.core.storage.SignLogRecord
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

enum class MenuKind {
    DASHBOARD,
    CLAIM_LIST,
    CLAIM_DETAIL,
    CLAIM_FLAGS,
    SUBCLAIM_LIST,
    CLAIM_TRUST,
    TRUST_ADD_SELECT,
    TRANSFER_SELECT,
    ROLLBACK_PRESET,
    ADMIN_CLAIM_BLOCKS,
    CONFIRMATION,
    AUDIT_BROWSER,
    ADMIN_PANEL
}

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

class ProtectorMenuHolder(
    val kind: MenuKind,
    val page: Int = 0,
    val claimId: Long? = null,
    val auditItems: List<AuditGuiItem> = emptyList(),
    val lookupFilter: LookupFilter? = null,
    val confirmCallback: ((Player) -> Unit)? = null,
    val restoreMode: Boolean = false
) : InventoryHolder {
    lateinit var inv: Inventory
    override fun getInventory(): Inventory = inv
}

class MenuService(
    private val plugin: JavaPlugin,
    private val scheduler: ProtectorScheduler,
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val visualizer: BoundaryVisualizer,
    private val actionBar: ActionBarHudService,
    private val bossBar: BossBarService,
    private val deathDrops: DeathDropProtectionService,
    private val inspector: InspectorService,
    private val rollbackService: RollbackService,
    private val dialogs: DialogService,
    private val repository: AuditLogRepository,
    private val consumer: LogQueueConsumer
) : Listener {

    private val actionKey = NamespacedKey(plugin, "menu_action")
    private val targetKey = NamespacedKey(plugin, "menu_target")
    private val timeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault())

    fun openDashboard(player: Player) {
        val holder = ProtectorMenuHolder(MenuKind.DASHBOARD)
        val inv = Bukkit.createInventory(holder, 36, messages.renderRaw("<gradient:#38bdf8:#818cf8><bold>Protector Dashboard</bold></gradient>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        val state = actionBar.state(player.uniqueId)
        val available = claimManager.getAvailableClaimBlocks(player.uniqueId)
        val used = claimManager.getUsedClaimBlocks(player.uniqueId)
        val myClaims = claimManager.getPlayerClaims(player.uniqueId)
        val currentClaim = claimManager.getClaimAt(player.location)

        inv.setItem(
            4,
            icon(
                Material.SHIELD,
                "<aqua><bold>Protection Overview</bold></aqua>",
                listOf(
                    "<gray>Owned Claims:</gray> <white>${myClaims.size}</white>",
                    "<gray>Used Blocks:</gray> <white>$used</white>",
                    "<gray>Available Blocks:</gray> <green>$available</green>",
                    "<gray>Current Location:</gray> <white>${currentClaim?.name ?: "Wilderness"}</white>"
                )
            )
        )

        inv.setItem(
            11,
            icon(
                Material.GRASS_BLOCK,
                "<green><bold>My Claims</bold></green>",
                listOf("<gray>Browse claims you own or are trusted in,", "<gray>and teleport, visualize, or configure them."),
                action = "open_claims"
            )
        )

        val currentDesc = if (currentClaim != null) {
            listOf("<gray>Standing in:</gray> <white>${currentClaim.name}</white>", "<yellow>Click to manage settings & trusts</yellow>")
        } else {
            listOf("<red>You are not inside a claim.</red>", "<gray>Click to create a claim around you.</gray>")
        }
        inv.setItem(
            13,
            icon(
                Material.COMPARATOR,
                "<aqua><bold>Manage Current Claim</bold></aqua>",
                currentDesc,
                action = "manage_current"
            )
        )

        inv.setItem(
            15,
            icon(
                configProvider().claimTool,
                "<gold><bold>Claim Tool & Mode</bold></gold>",
                listOf(
                    "<gray>Current Mode:</gray> <aqua>${state.shovelMode.label}</aqua>",
                    "<yellow>Left-click: Get tool | Right-click: Cycle mode</yellow>"
                ),
                action = "claim_tool"
            )
        )

        if (player.hasPermission("protector.inspect")) {
            inv.setItem(
                20,
                icon(
                    if (state.inspectMode) Material.ENDER_EYE else Material.SPYGLASS,
                    if (state.inspectMode) "<green><bold>Inspector Mode: ON</bold></green>" else "<gray><bold>Inspector Mode: OFF</bold></gray>",
                    listOf("<gray>Click blocks/containers to view audit history.", "<yellow>Click to toggle</yellow>"),
                    action = "toggle_inspect"
                )
            )
        }

        if (player.hasPermission("protector.lookup") || player.hasPermission("protector.rollback")) {
            inv.setItem(
                22,
                icon(
                    Material.CLOCK,
                    "<gold><bold>Audit Lookup & Rollback (Dialog)</bold></gold>",
                    listOf("<gray>Configure time, radius, and user filters", "<gray>to preview, rollback, or browse logs."),
                    action = "open_rollback_dialog"
                )
            )
            inv.setItem(
                24,
                icon(
                    Material.BOOK,
                    "<yellow><bold>Nearby Audit Logs (GUI)</bold></yellow>",
                    listOf("<gray>Browse the last 24h within 20 blocks", "<gray>using tabbed category views."),
                    action = "open_recent_audit"
                )
            )
        }

        if (deathDrops.hasLockedDrops(player.uniqueId)) {
            inv.setItem(
                29,
                icon(
                    Material.CHEST,
                    "<aqua><bold>Unlock Protected Death Drops</bold></aqua>",
                    listOf("<gray>Your death drops are currently protected.", "<yellow>Click to allow others to pick them up.</yellow>"),
                    action = "unlock_drops"
                )
            )
        }

        inv.setItem(
            30,
            icon(
                if (state.territoryBanner) Material.BEACON else Material.LANTERN,
                if (state.territoryBanner) "<aqua>BossBar Territory Banner: ON</aqua>" else "<gray>BossBar Territory Banner: OFF</gray>",
                listOf("<yellow>Display claim status on top BossBar</yellow>"),
                action = "toggle_territory_bar"
            )
        )

        inv.setItem(
            31,
            icon(
                if (state.hudEnabled) Material.BELL else Material.BARRIER,
                if (state.hudEnabled) "<aqua>ActionBar HUD: ON</aqua>" else "<gray>ActionBar HUD: OFF</gray>",
                listOf("<yellow>Click to toggle persistent ActionBar HUD</yellow>"),
                action = "toggle_hud"
            )
        )

        if (player.hasPermission("protector.admin")) {
            inv.setItem(
                33,
                icon(
                    Material.COMMAND_BLOCK,
                    "<red><bold>Admin Control Panel</bold></red>",
                    listOf("<gray>Adjust claim blocks, search activity logs,", "<gray>purge old logs, and view queue stats."),
                    action = "open_admin_panel"
                )
            )
            inv.setItem(
                35,
                icon(
                    if (state.adminBypass) Material.NETHER_STAR else Material.IRON_INGOT,
                    if (state.adminBypass) "<red><bold>Admin Bypass: ON</bold></red>" else "<gray>Admin Bypass: OFF</gray>",
                    listOf("<yellow>Temporarily bypass all claim restrictions.</yellow>"),
                    action = "toggle_bypass"
                )
            )
        }

        player.openInventory(inv)
    }

    fun openAdminPanel(player: Player) {
        if (!player.hasPermission("protector.admin")) return
        val holder = ProtectorMenuHolder(MenuKind.ADMIN_PANEL)
        val inv = Bukkit.createInventory(holder, 27, messages.renderRaw("<red><bold>Protector Admin Panel</bold></red>"))
        holder.inv = inv
        fillBorder(inv, Material.RED_STAINED_GLASS_PANE)

        inv.setItem(
            10,
            icon(
                Material.GOLD_INGOT,
                "<gold><bold>Adjust Claim Blocks (Dialog)</bold></gold>",
                listOf("<gray>Add or subtract bonus claim blocks using a slider."),
                action = "admin_claim_blocks"
            )
        )
        inv.setItem(
            12,
            icon(
                Material.PAPER,
                "<yellow><bold>Activity Log Search (Dialog)</bold></yellow>",
                listOf("<gray>Search chat, command, session, and sign records."),
                action = "admin_activity_search"
            )
        )
        inv.setItem(
            14,
            icon(
                Material.LAVA_BUCKET,
                "<red><bold>Purge Logs Older Than 30d</bold></red>",
                listOf("<gray>Opens a confirmation Dialog before deleting."),
                action = "admin_purge_30d"
            )
        )
        inv.setItem(
            16,
            icon(
                Material.REDSTONE_TORCH,
                "<aqua><bold>System & Queue Status</bold></aqua>",
                listOf(
                    "<gray>Loaded Claims:</gray> <white>${claimManager.getAllTopLevelClaims().size}</white>",
                    "<gray>Pending Log Queue:</gray> <white>${consumer.pendingCount()}</white>",
                    "<gray>Total Processed:</gray> <white>${consumer.totalProcessed.get()}</white>"
                )
            )
        )
        inv.setItem(22, icon(Material.OAK_DOOR, "<gray>Back to Dashboard</gray>", emptyList(), action = "open_dashboard"))
        player.openInventory(inv)
    }

    fun openClaimList(player: Player, page: Int = 0) {
        val owned = claimManager.getPlayerClaims(player.uniqueId)
        val trusted = claimManager.getTrustedClaims(player.uniqueId)
        val all = (owned + trusted).distinctBy { it.id }

        val pageSize = 28
        val totalPages = maxOf(1, (all.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, totalPages - 1)

        val holder = ProtectorMenuHolder(MenuKind.CLAIM_LIST, page = safePage)
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<aqua><bold>My Claims (${safePage + 1}/$totalPages)</bold></aqua>"))
        holder.inv = inv
        fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE)

        val slice = all.drop(safePage * pageSize).take(pageSize)
        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        slice.forEachIndexed { idx, claim ->
            val slot = contentSlots[idx]
            val isOwner = claim.ownerId == player.uniqueId
            val mat = if (claim.isAdminClaim) Material.BEACON else if (isOwner) Material.EMERALD_BLOCK else Material.GOLD_BLOCK
            inv.setItem(
                slot,
                icon(
                    mat,
                    "<aqua>${claim.name}</aqua> <dark_gray>#${claim.id}</dark_gray>",
                    listOf(
                        "<gray>Owner:</gray> <white>${claim.ownerName()}</white>",
                        "<gray>World:</gray> <white>${claim.bounds.worldName}</white>",
                        "<gray>Bounds:</gray> <white>(${claim.bounds.minX}, ${claim.bounds.minZ}) ~ (${claim.bounds.maxX}, ${claim.bounds.maxZ})</white>",
                        "<gray>Size:</gray> <white>${claim.bounds.width}×${claim.bounds.length} (${claim.bounds.area} blocks)</white>",
                        "<gray>Subclaims:</gray> <white>${claim.children.size}</white>",
                        "",
                        "<yellow>Left-click: Manage details & trusts</yellow>",
                        "<aqua>Right-click: Visualize & Teleport</aqua>"
                    ),
                    action = "select_claim",
                    target = claim.id.toString()
                )
            )
        }

        if (safePage > 0) {
            inv.setItem(45, icon(Material.ARROW, "<yellow>Previous Page</yellow>", emptyList(), action = "claims_prev"))
        }
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Back to Dashboard</gray>", emptyList(), action = "open_dashboard"))
        if (safePage + 1 < totalPages) {
            inv.setItem(53, icon(Material.ARROW, "<yellow>Next Page</yellow>", emptyList(), action = "claims_next"))
        }

        player.openInventory(inv)
    }

    fun openClaimDetail(player: Player, claim: Claim) {
        val holder = ProtectorMenuHolder(MenuKind.CLAIM_DETAIL, claimId = claim.id)
        val inv = Bukkit.createInventory(holder, 27, messages.renderRaw("<aqua><bold>Claim: ${claim.name}</bold></aqua>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        val adminBypass = actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
        val canManage = claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)
        val isOwner = claim.ownerId == player.uniqueId || adminBypass

        inv.setItem(
            10,
            icon(
                Material.PLAYER_HEAD,
                "<green><bold>Trusted Players & Permissions</bold></green>",
                listOf(
                    "<gray>Trusted Entries:</gray> <white>${claim.trusts.size}</white>",
                    "<yellow>Click to open trust matrix</yellow>"
                ),
                action = "open_trust_menu",
                target = claim.id.toString()
            )
        )

        if (canManage) {
            inv.setItem(
                11,
                icon(
                    Material.WRITABLE_BOOK,
                    "<aqua><bold>Claim Name & Flags (Dialog)</bold></aqua>",
                    listOf("<gray>Edit PvP, explosions, fire spread, mob griefing,", "<gray>and greeting message via Paper Dialog."),
                    action = "open_claim_dialog",
                    target = claim.id.toString()
                )
            )
        }

        if (!claim.isSubclaim) {
            inv.setItem(
                13,
                icon(
                    Material.IRON_BARS,
                    "<light_purple><bold>Subclaims</bold></light_purple>",
                    listOf(
                        "<gray>Subclaims:</gray> <white>${claim.children.size}</white>",
                        "<yellow>Click to list & configure subclaims</yellow>"
                    ),
                    action = "open_subclaims",
                    target = claim.id.toString()
                )
            )
        }

        inv.setItem(
            14,
            icon(
                Material.GLOWSTONE,
                "<yellow><bold>Visualize & Teleport</bold></yellow>",
                listOf("<gray>Left-click: Show boundary", "<aqua>Right-click: Teleport to center</aqua>"),
                action = "visualize_or_tp",
                target = claim.id.toString()
            )
        )

        if (isOwner && !claim.isSubclaim) {
            inv.setItem(
                15,
                icon(
                    Material.NAME_TAG,
                    "<gold><bold>Transfer Ownership (Dialog)</bold></gold>",
                    listOf("<gray>Transfer this claim to another player."),
                    action = "open_transfer_dialog",
                    target = claim.id.toString()
                )
            )
        }

        if (isOwner) {
            inv.setItem(
                16,
                icon(
                    Material.TNT,
                    "<red><bold>Abandon Claim</bold></red>",
                    listOf("<gray>Opens confirmation Dialog before deleting."),
                    action = "abandon_claim_confirm",
                    target = claim.id.toString()
                )
            )
        }

        inv.setItem(22, icon(Material.OAK_DOOR, "<gray>Back to Claims</gray>", emptyList(), action = "open_claims"))
        player.openInventory(inv)
    }

    fun openSubclaimList(player: Player, parent: Claim) {
        val holder = ProtectorMenuHolder(MenuKind.SUBCLAIM_LIST, claimId = parent.id)
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<light_purple><bold>Subclaims: ${parent.name}</bold></light_purple>"))
        holder.inv = inv
        fillBorder(inv, Material.PURPLE_STAINED_GLASS_PANE)

        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        parent.children.take(28).forEachIndexed { idx, sub ->
            val slot = contentSlots[idx]
            inv.setItem(
                slot,
                icon(
                    if (sub.inheritNothing) Material.IRON_DOOR else Material.OAK_FENCE_GATE,
                    "<light_purple>${sub.name}</light_purple> <dark_gray>#${sub.id}</dark_gray>",
                    listOf(
                        "<gray>Isolated (inheritNothing):</gray> ${if (sub.inheritNothing) "<red>ON</red>" else "<green>OFF</green>"}",
                        "<gray>Bounds:</gray> <white>(${sub.bounds.minX}, ${sub.bounds.minY}, ${sub.bounds.minZ}) ~ (${sub.bounds.maxX}, ${sub.bounds.maxY}, ${sub.bounds.maxZ})</white>",
                        "<yellow>Left-click: Settings Dialog (Name / Isolation)</yellow>",
                        "<aqua>Right-click: Visualize boundary</aqua>"
                    ),
                    action = "select_subclaim",
                    target = sub.id.toString()
                )
            )
        }

        inv.setItem(
            4,
            icon(
                Material.GOLDEN_SHOVEL,
                "<aqua>How to Create a Subclaim</aqua>",
                listOf("<gray>Run /pr subdivide and right-click two corners", "<gray>inside the parent claim with the claim tool.")
            )
        )
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Back to Parent Claim</gray>", emptyList(), action = "select_claim_back", target = parent.id.toString()))
        player.openInventory(inv)
    }

    fun openClaimTrustMenu(player: Player, claim: Claim, page: Int = 0) {
        val entries = claim.trusts.entries.sortedBy { it.key }
        val pageSize = 28
        val totalPages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, totalPages - 1)

        val holder = ProtectorMenuHolder(MenuKind.CLAIM_TRUST, page = safePage, claimId = claim.id)
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<green><bold>Trust Matrix: ${claim.name}</bold></green>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        entries.drop(safePage * pageSize).take(pageSize).forEachIndexed { idx, (key, level) ->
            val slot = contentSlots[idx]
            val displayName = if (key == Claim.PUBLIC_KEY) {
                "Public (Everyone)"
            } else {
                runCatching { Bukkit.getOfflinePlayer(UUID.fromString(key)).name ?: key.take(8) }.getOrDefault(key)
            }
            val item = ItemStack(if (key == Claim.PUBLIC_KEY) Material.ENDER_PEARL else Material.PLAYER_HEAD)
            item.editMeta { meta ->
                if (meta is SkullMeta && key != Claim.PUBLIC_KEY) {
                    runCatching { meta.owningPlayer = Bukkit.getOfflinePlayer(UUID.fromString(key)) }
                }
                meta.displayName(messages.renderRaw("<aqua>$displayName</aqua>"))
                meta.lore(
                    listOf(
                        messages.renderRaw("<gray>Current Tier:</gray> <green>${level.displayName} (${level.name})</green>"),
                        Component.empty(),
                        messages.renderRaw("<yellow>Left-click: Cycle next tier</yellow>"),
                        messages.renderRaw("<red>Right-click: Remove trust</red>")
                    )
                )
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, "trust_entry")
                meta.persistentDataContainer.set(targetKey, PersistentDataType.STRING, key)
            }
            inv.setItem(slot, item)
        }

        inv.setItem(
            4,
            icon(
                Material.EMERALD,
                "<green><bold>+ Add Trusted Player (Dialog)</bold></green>",
                listOf("<gray>Open input & tier selector Dialog."),
                action = "open_trust_add_dialog",
                target = claim.id.toString()
            )
        )
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Back to Claim Details</gray>", emptyList(), action = "select_claim_back", target = claim.id.toString()))

        player.openInventory(inv)
    }

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
                    repository.queryChatLogs(filter.sinceEpochSec, filter.userName, null, 120)
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

        val holder = ProtectorMenuHolder(
            kind = MenuKind.AUDIT_BROWSER,
            page = safePage,
            auditItems = items,
            lookupFilter = filter
        )
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<gold><bold>Audit Logs (${items.size} · ${safePage + 1}/$totalPages)</bold></gold>"))
        holder.inv = inv
        fillBorder(inv, Material.BLACK_STAINED_GLASS_PANE)

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
            inv.setItem(
                slot,
                icon(
                    iconSpec.first,
                    if (selected) "<green><bold>[${iconSpec.second}]</bold></green>" else "<gray>${iconSpec.second}</gray>",
                    listOf("<yellow>Click to switch tab</yellow>"),
                    action = "audit_tab",
                    target = scope.name
                )
            )
        }

        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        items.drop(safePage * pageSize).take(pageSize).forEachIndexed { idx, entry ->
            val slot = contentSlots[idx]
            val globalIndex = safePage * pageSize + idx
            val ts = timeFormatter.format(Instant.ofEpochSecond(entry.timeSec))
            val item = when (entry) {
                is AuditGuiItem.BlockItem -> {
                    val r = entry.record
                    val matName = if (r.action.name == "PLACE") r.newMaterial else r.oldMaterial
                    val mat = Material.matchMaterial(matName)?.takeIf { it.isItem } ?: Material.STONE
                    icon(
                        mat,
                        "<aqua>[Block ${r.action.label}] $matName</aqua>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>(${r.x}, ${r.y}, ${r.z})</white>",
                            "<yellow>Click: Open Inspector Dialog</yellow>"
                        ),
                        action = "audit_entry",
                        target = globalIndex.toString()
                    )
                }
                is AuditGuiItem.ContainerItem -> {
                    val r = entry.record
                    val mat = Material.matchMaterial(r.material)?.takeIf { it.isItem } ?: Material.CHEST
                    icon(
                        mat,
                        "<gold>[Container ${r.action.label}] ${r.material} ×${r.amount}</gold>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>(${r.x}, ${r.y}, ${r.z})</white>",
                            "<yellow>Click: Open Inspector Dialog</yellow>"
                        ),
                        action = "audit_entry",
                        target = globalIndex.toString()
                    )
                }
                is AuditGuiItem.EntityItem -> {
                    val r = entry.record
                    icon(
                        Material.IRON_SWORD,
                        "<red>[Entity ${r.action.label}] ${r.entityType}</red>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>(${r.x}, ${r.y}, ${r.z})</white>",
                            "<yellow>Click: Teleport to location</yellow>"
                        ),
                        action = "audit_entry",
                        target = globalIndex.toString()
                    )
                }
                is AuditGuiItem.SignItem -> {
                    val r = entry.record
                    icon(
                        Material.OAK_SIGN,
                        "<yellow>[Sign ${if (r.action == 0) "Broken" else "Edited"}] (${r.side})</yellow>",
                        listOf(
                            "<gray>Actor:</gray> <white>${r.actorName}</white>",
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Lines:</gray> <white>${r.linesJson}</white>",
                            "<yellow>Click: Teleport to location</yellow>"
                        ),
                        action = "audit_entry",
                        target = globalIndex.toString()
                    )
                }
                is AuditGuiItem.ChatItem -> {
                    val r = entry.record
                    icon(
                        if (r.isCommand) Material.COMMAND_BLOCK else Material.PAPER,
                        if (r.isCommand) "<gold>[Command] ${r.actorName}</gold>" else "<white>[Chat] ${r.actorName}</white>",
                        listOf(
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Text:</gray> <aqua>${r.message}</aqua>",
                            "<gray>Location:</gray> <white>${r.worldName} (${r.x}, ${r.y}, ${r.z})</white>"
                        ),
                        action = "audit_entry",
                        target = globalIndex.toString()
                    )
                }
                is AuditGuiItem.SessionItem -> {
                    val r = entry.record
                    icon(
                        if (r.login) Material.LIME_DYE else Material.RED_DYE,
                        if (r.login) "<green>[Login] ${r.actorName}</green>" else "<red>[Logout] ${r.actorName}</red>",
                        listOf(
                            "<gray>Time:</gray> <white>$ts</white>",
                            "<gray>Location:</gray> <white>${r.worldName} (${r.x}, ${r.y}, ${r.z})</white>"
                        ),
                        action = "audit_entry",
                        target = globalIndex.toString()
                    )
                }
            }
            inv.setItem(slot, item)
        }

        if (safePage > 0) {
            inv.setItem(45, icon(Material.ARROW, "<yellow>Previous Page</yellow>", emptyList(), action = "audit_prev"))
        }
        inv.setItem(48, icon(Material.CLOCK, "<aqua>Edit Filter (Dialog)</aqua>", emptyList(), action = "open_rollback_dialog"))
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Dashboard</gray>", emptyList(), action = "open_dashboard"))
        if (safePage + 1 < totalPages) {
            inv.setItem(53, icon(Material.ARROW, "<yellow>Next Page</yellow>", emptyList(), action = "audit_next"))
        }

        player.openInventory(inv)
    }

    // --- Legacy (< 1.21.6) GUI Fallback Menus ---

    fun openConfirmationMenu(
        player: Player,
        titleMiniMessage: String,
        bodyMiniMessage: String,
        onConfirm: (Player) -> Unit
    ) {
        val holder = ProtectorMenuHolder(MenuKind.CONFIRMATION, confirmCallback = onConfirm)
        val inv = Bukkit.createInventory(holder, 27, messages.renderRaw(titleMiniMessage))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        inv.setItem(
            11,
            icon(
                Material.LIME_CONCRETE,
                "<green><bold>✔ Confirm</bold></green>",
                bodyMiniMessage.split('\n').map { "<gray>$it</gray>" },
                action = "confirm_yes"
            )
        )
        inv.setItem(13, icon(Material.PAPER, "<yellow>Confirmation Required</yellow>", bodyMiniMessage.split('\n')))
        inv.setItem(
            15,
            icon(
                Material.RED_CONCRETE,
                "<red><bold>✖ Cancel</bold></red>",
                listOf("<gray>Close without making changes.</gray>"),
                action = "confirm_no"
            )
        )
        player.openInventory(inv)
    }

    fun openClaimFlagsMenu(player: Player, claim: Claim) {
        val cfg = configProvider()
        val holder = ProtectorMenuHolder(MenuKind.CLAIM_FLAGS, claimId = claim.id)
        val inv = Bukkit.createInventory(holder, 36, messages.renderRaw("<aqua><bold>Claim Flags: ${claim.name}</bold></aqua>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        val slots = listOf(10, 11, 12, 13, 14, 15, 16)
        ClaimFlag.entries.forEachIndexed { idx, flag ->
            val enabled = claim.flag(flag, cfg.defaultFlags)
            inv.setItem(
                slots[idx],
                icon(
                    if (enabled) Material.LIME_DYE else Material.GRAY_DYE,
                    "<aqua>${flag.label}</aqua>",
                    listOf(
                        "<gray>Current:</gray> ${if (enabled) "<green>ENABLED</green>" else "<red>DISABLED</red>"}",
                        "<yellow>Click to toggle</yellow>"
                    ),
                    action = "toggle_flag",
                    target = flag.name
                )
            )
        }
        inv.setItem(31, icon(Material.OAK_DOOR, "<gray>Back to Claim Details</gray>", emptyList(), action = "select_claim_back", target = claim.id.toString()))
        player.openInventory(inv)
    }

    fun openTrustSelectPlayerMenu(player: Player, claim: Claim) {
        val holder = ProtectorMenuHolder(MenuKind.TRUST_ADD_SELECT, claimId = claim.id)
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<green><bold>Select Player to Trust</bold></green>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        inv.setItem(
            4,
            icon(
                Material.ENDER_PEARL,
                "<aqua>Trust Public (Everyone)</aqua>",
                listOf("<yellow>Click to grant Access trust to everyone</yellow>"),
                action = "trust_select_target",
                target = Claim.PUBLIC_KEY
            )
        )

        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        Bukkit.getOnlinePlayers().filter { it.uniqueId != claim.ownerId }.take(28).forEachIndexed { idx, online ->
            val item = ItemStack(Material.PLAYER_HEAD)
            item.editMeta { meta ->
                if (meta is SkullMeta) runCatching { meta.owningPlayer = online }
                meta.displayName(messages.renderRaw("<aqua>${online.name}</aqua>"))
                meta.lore(listOf(messages.renderRaw("<yellow>Click to grant Build trust</yellow>")))
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, "trust_select_target")
                meta.persistentDataContainer.set(targetKey, PersistentDataType.STRING, online.uniqueId.toString())
            }
            inv.setItem(contentSlots[idx], item)
        }
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Back to Trust Matrix</gray>", emptyList(), action = "open_trust_menu", target = claim.id.toString()))
        player.openInventory(inv)
    }

    fun openTransferSelectPlayerMenu(player: Player, claim: Claim) {
        val holder = ProtectorMenuHolder(MenuKind.TRANSFER_SELECT, claimId = claim.id)
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<gold><bold>Select New Claim Owner</bold></gold>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        Bukkit.getOnlinePlayers().filter { it.uniqueId != claim.ownerId }.take(28).forEachIndexed { idx, online ->
            val item = ItemStack(Material.PLAYER_HEAD)
            item.editMeta { meta ->
                if (meta is SkullMeta) runCatching { meta.owningPlayer = online }
                meta.displayName(messages.renderRaw("<gold>${online.name}</gold>"))
                meta.lore(listOf(messages.renderRaw("<yellow>Click to transfer ownership</yellow>")))
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, "transfer_select_target")
                meta.persistentDataContainer.set(targetKey, PersistentDataType.STRING, online.uniqueId.toString())
            }
            inv.setItem(contentSlots[idx], item)
        }
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Back to Claim Details</gray>", emptyList(), action = "select_claim_back", target = claim.id.toString()))
        player.openInventory(inv)
    }

    fun openRollbackPresetMenu(player: Player, restoreMode: Boolean) {
        val holder = ProtectorMenuHolder(MenuKind.ROLLBACK_PRESET, restoreMode = restoreMode)
        val verb = if (restoreMode) "Restore" else "Rollback"
        val inv = Bukkit.createInventory(holder, 27, messages.renderRaw("<gold><bold>$verb Presets (r:15)</bold></gold>"))
        holder.inv = inv
        fillBorder(inv, Material.GRAY_STAINED_GLASS_PANE)

        val presets = listOf(10 to "10m", 12 to "1h", 14 to "24h", 16 to "7d")
        for ((slot, token) in presets) {
            inv.setItem(
                slot,
                icon(
                    Material.CLOCK,
                    "<aqua>Preview $verb: Last $token (r:15)</aqua>",
                    listOf("<yellow>Left-click: Start preview", "<gold>Right-click: Browse logs GUI</gold>"),
                    action = "rollback_preset_select",
                    target = token
                )
            )
        }
        inv.setItem(22, icon(Material.OAK_DOOR, "<gray>Dashboard</gray>", emptyList(), action = "open_dashboard"))
        player.openInventory(inv)
    }

    fun openAdminClaimBlocksMenu(player: Player) {
        val holder = ProtectorMenuHolder(MenuKind.ADMIN_CLAIM_BLOCKS)
        val inv = Bukkit.createInventory(holder, 54, messages.renderRaw("<red><bold>Adjust Bonus Claim Blocks (+500)</bold></red>"))
        holder.inv = inv
        fillBorder(inv, Material.RED_STAINED_GLASS_PANE)

        val contentSlots = (10..16) + (19..25) + (28..34) + (37..43)
        Bukkit.getOnlinePlayers().take(28).forEachIndexed { idx, online ->
            val avail = claimManager.getAvailableClaimBlocks(online.uniqueId)
            val item = ItemStack(Material.PLAYER_HEAD)
            item.editMeta { meta ->
                if (meta is SkullMeta) runCatching { meta.owningPlayer = online }
                meta.displayName(messages.renderRaw("<aqua>${online.name}</aqua>"))
                meta.lore(
                    listOf(
                        messages.renderRaw("<gray>Available:</gray> <green>$avail</green>"),
                        messages.renderRaw("<yellow>Left-click: +500 blocks | Right-click: -500 blocks</yellow>")
                    )
                )
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, "admin_adjust_player")
                meta.persistentDataContainer.set(targetKey, PersistentDataType.STRING, online.uniqueId.toString())
            }
            inv.setItem(contentSlots[idx], item)
        }
        inv.setItem(49, icon(Material.OAK_DOOR, "<gray>Back to Admin Panel</gray>", emptyList(), action = "open_admin_panel"))
        player.openInventory(inv)
    }

    @EventHandler
    fun onInventoryDrag(event: InventoryDragEvent) {
        if (event.inventory.holder is ProtectorMenuHolder) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onInventoryClick(event: InventoryClickEvent) {
        val holder = event.inventory.holder as? ProtectorMenuHolder ?: return
        event.isCancelled = true
        if (event.clickedInventory != event.view.topInventory) return

        val player = event.whoClicked as? Player ?: return
        val item = event.currentItem ?: return
        val meta = item.itemMeta ?: return
        val action = meta.persistentDataContainer.get(actionKey, PersistentDataType.STRING) ?: return
        val target = meta.persistentDataContainer.get(targetKey, PersistentDataType.STRING)

        when (action) {
            "open_dashboard" -> openDashboard(player)
            "open_admin_panel" -> openAdminPanel(player)
            "open_claims" -> openClaimList(player, 0)
            "claims_prev" -> openClaimList(player, holder.page - 1)
            "claims_next" -> openClaimList(player, holder.page + 1)
            "toggle_inspect" -> {
                inspector.toggleInspect(player)
                openDashboard(player)
            }
            "toggle_hud" -> {
                val st = actionBar.state(player.uniqueId)
                st.hudEnabled = !st.hudEnabled
                if (!st.hudEnabled) player.sendActionBar(Component.empty())
                openDashboard(player)
            }
            "toggle_territory_bar" -> {
                val st = actionBar.state(player.uniqueId)
                st.territoryBanner = !st.territoryBanner
                if (!st.territoryBanner) bossBar.hide(player, "territory_banner")
                actionBar.tickPlayer(player)
                openDashboard(player)
            }
            "unlock_drops" -> {
                deathDrops.unlockDrops(player)
                openDashboard(player)
            }
            "toggle_bypass" -> {
                if (player.hasPermission("protector.admin")) {
                    val st = actionBar.state(player.uniqueId)
                    st.adminBypass = !st.adminBypass
                    openDashboard(player)
                }
            }
            "admin_claim_blocks" -> {
                player.closeInventory()
                dialogs.openAdminClaimBlocksDialog(player)
            }
            "admin_activity_search" -> {
                player.closeInventory()
                dialogs.openActivitySearchDialog(player)
            }
            "admin_purge_30d" -> {
                player.closeInventory()
                dialogs.openConfirmationDialog(
                    player,
                    "<red><bold>Confirm 30d Log Purge</bold></red>",
                    "<gray>Permanently delete all audit logs older than 30 days.</gray>",
                    "<red>Purge</red>"
                ) { p ->
                    rollbackService.purgeBefore(p, p, 30L * 86400L)
                }
            }
            "claim_tool" -> {
                if (event.click == ClickType.RIGHT) {
                    val st = actionBar.state(player.uniqueId)
                    st.shovelMode = when (st.shovelMode) {
                        ShovelMode.BASIC -> ShovelMode.SUBDIVIDE
                        ShovelMode.SUBDIVIDE -> if (player.hasPermission("protector.admin")) ShovelMode.ADMIN else ShovelMode.BASIC
                        ShovelMode.ADMIN -> ShovelMode.BASIC
                    }
                    openDashboard(player)
                } else {
                    val tool = configProvider().claimTool
                    if (!player.inventory.contains(tool)) {
                        player.inventory.addItem(ItemStack(tool))
                    }
                    player.closeInventory()
                    actionBar.flash(player, messages.renderRaw("<aqua>⛏ Claim tool (${tool.name}) ready! Right-click two corners.</aqua>"))
                }
            }
            "manage_current" -> {
                val current = claimManager.getClaimAt(player.location)
                if (current != null) {
                    openClaimDetail(player, current)
                } else {
                    player.closeInventory()
                    player.performCommand("claim")
                }
            }
            "open_rollback_dialog" -> {
                player.closeInventory()
                dialogs.openRollbackFilterDialog(player, restoreMode = false)
            }
            "open_recent_audit" -> {
                val loc = player.location
                val filter = LookupFilter(
                    worldName = loc.world.name,
                    centerX = loc.blockX,
                    centerY = loc.blockY,
                    centerZ = loc.blockZ,
                    radius = 20,
                    sinceEpochSec = (System.currentTimeMillis() / 1000L) - 86400L,
                    limit = 100
                )
                openAuditBrowserAsync(player, filter, 0)
            }
            "audit_tab" -> {
                val filter = holder.lookupFilter ?: return
                val scope = runCatching { LookupActionFilter.valueOf(target ?: "ALL") }.getOrDefault(LookupActionFilter.ALL)
                openAuditBrowserAsync(player, filter.copy(actionFilter = scope), 0)
            }
            "select_claim" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                if (event.click == ClickType.RIGHT) {
                    player.closeInventory()
                    visualizer.showClaim(player, claim)
                    teleportToClaimCenter(player, claim)
                } else {
                    openClaimDetail(player, claim)
                }
            }
            "select_claim_back" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                openClaimDetail(player, claim)
            }
            "open_subclaims" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                openSubclaimList(player, claim)
            }
            "select_subclaim" -> {
                val id = target?.toLongOrNull() ?: return
                val sub = claimManager.getClaimById(id) ?: return
                if (event.click == ClickType.RIGHT) {
                    player.closeInventory()
                    visualizer.showClaim(player, sub, Material.IRON_BLOCK)
                } else {
                    player.closeInventory()
                    dialogs.openSubclaimSettingsDialog(player, sub)
                }
            }
            "open_trust_menu" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                openClaimTrustMenu(player, claim, 0)
            }
            "open_trust_add_dialog" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                player.closeInventory()
                dialogs.openTrustAddDialog(player, claim)
            }
            "open_claim_dialog" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                player.closeInventory()
                dialogs.openClaimSettingsDialog(player, claim)
            }
            "open_transfer_dialog" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                player.closeInventory()
                dialogs.openTransferClaimDialog(player, claim)
            }
            "visualize_or_tp" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                player.closeInventory()
                visualizer.showClaim(player, claim)
                if (event.click == ClickType.RIGHT) {
                    teleportToClaimCenter(player, claim)
                }
            }
            "abandon_claim_confirm" -> {
                val id = target?.toLongOrNull() ?: return
                val claim = claimManager.getClaimById(id) ?: return
                player.closeInventory()
                dialogs.openConfirmationDialog(
                    player,
                    "<red><bold>Abandon Claim</bold></red>",
                    "<gray>Are you sure you want to abandon <white>${claim.name}</white>?\nAll trust entries and flags for this claim will be deleted.</gray>",
                    "<red>Abandon</red>"
                ) { p ->
                    val refund = claimManager.deleteClaim(claim)
                    visualizer.clear(p)
                    messages.send(p, "claim-abandoned", mapOf("refund" to refund))
                }
            }
            "trust_entry" -> {
                val claimId = holder.claimId ?: return
                val claim = claimManager.getClaimById(claimId) ?: return
                val key = target ?: return
                val adminBypass = actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
                if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                    actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                    return
                }
                if (event.click == ClickType.RIGHT) {
                    player.closeInventory()
                    dialogs.openConfirmationDialog(
                        player,
                        "<red><bold>Remove Claim Trust</bold></red>",
                        "<gray>Remove this player's trust in <white>${claim.name}</white>?</gray>",
                        "<red>Remove</red>"
                    ) { p ->
                        claimManager.removeTrust(claim, key)
                        messages.send(p, "trust-removed", mapOf("target" to key))
                        openClaimTrustMenu(p, claim, holder.page)
                    }
                } else {
                    val current = claim.trusts[key] ?: ClaimPermission.ACCESS
                    val next = current.nextTrustTier()
                    claimManager.setTrust(claim, key, next)
                    openClaimTrustMenu(player, claim, holder.page)
                }
            }
            "audit_prev" -> {
                val filter = holder.lookupFilter ?: return
                openAuditBrowser(player, holder.auditItems, filter, holder.page - 1)
            }
            "audit_next" -> {
                val filter = holder.lookupFilter ?: return
                openAuditBrowser(player, holder.auditItems, filter, holder.page + 1)
            }
            "confirm_yes" -> {
                player.closeInventory()
                holder.confirmCallback?.invoke(player)
            }
            "confirm_no" -> player.closeInventory()
            "toggle_flag" -> {
                val claimId = holder.claimId ?: return
                val claim = claimManager.getClaimById(claimId) ?: return
                val flag = runCatching { ClaimFlag.valueOf(target ?: return) }.getOrNull() ?: return
                val next = !claim.flag(flag, configProvider().defaultFlags)
                claimManager.setFlag(claim, flag, next)
                openClaimFlagsMenu(player, claim)
            }
            "trust_select_target" -> {
                val claimId = holder.claimId ?: return
                val claim = claimManager.getClaimById(claimId) ?: return
                val key = target ?: return
                val level = if (key == Claim.PUBLIC_KEY) ClaimPermission.ACCESS else ClaimPermission.BUILD
                claimManager.setTrust(claim, key, level)
                openClaimTrustMenu(player, claim, 0)
            }
            "transfer_select_target" -> {
                val claimId = holder.claimId ?: return
                val claim = claimManager.getClaimById(claimId) ?: return
                val targetUuid = runCatching { UUID.fromString(target ?: return) }.getOrNull() ?: return
                val targetName = Bukkit.getOfflinePlayer(targetUuid).name ?: targetUuid.toString().take(8)
                openConfirmationMenu(
                    player,
                    "<gold><bold>Confirm Ownership Transfer</bold></gold>",
                    "Transfer ${claim.name} to $targetName?"
                ) { p ->
                    claimManager.transferClaim(claim, targetUuid)
                    messages.send(p, "claim-transferred", mapOf("target" to targetName))
                }
            }
            "rollback_preset_select" -> {
                val token = target ?: "1h"
                val sec = RollbackService.parseDurationSeconds(token) ?: 3600L
                val loc = player.location
                val filter = LookupFilter(
                    worldName = loc.world.name,
                    centerX = loc.blockX,
                    centerY = loc.blockY,
                    centerZ = loc.blockZ,
                    radius = 15,
                    sinceEpochSec = (System.currentTimeMillis() / 1000L) - sec,
                    limit = 2000
                )
                if (event.click == ClickType.RIGHT) {
                    openAuditBrowserAsync(player, filter, 0)
                } else {
                    player.closeInventory()
                    rollbackService.startPreview(player, filter, holder.restoreMode)
                }
            }
            "admin_adjust_player" -> {
                if (!player.hasPermission("protector.admin")) return
                val targetUuid = runCatching { UUID.fromString(target ?: return) }.getOrNull() ?: return
                val delta = if (event.click == ClickType.RIGHT) -500 else 500
                claimManager.addBonusBlocks(targetUuid, delta)
                openAdminClaimBlocksMenu(player)
            }
            "audit_entry" -> {
                val index = target?.toIntOrNull() ?: return
                val entry = holder.auditItems.getOrNull(index) ?: return
                val world = Bukkit.getWorld(entry.worldName) ?: return
                val loc = Location(world, entry.x.toDouble(), entry.y.toDouble(), entry.z.toDouble())
                player.closeInventory()
                when (entry) {
                    is AuditGuiItem.BlockItem -> dialogs.openInspectDetailDialog(player, loc, listOf(entry.record), emptyList())
                    is AuditGuiItem.ContainerItem -> dialogs.openInspectDetailDialog(player, loc, emptyList(), listOf(entry.record))
                    is AuditGuiItem.EntityItem,
                    is AuditGuiItem.SignItem,
                    is AuditGuiItem.ChatItem,
                    is AuditGuiItem.SessionItem -> player.teleport(loc.clone().add(0.5, 1.0, 0.5))
                }
            }
        }
    }

    private fun teleportToClaimCenter(player: Player, claim: Claim) {
        val world = Bukkit.getWorld(claim.bounds.worldName) ?: return
        val cx = (claim.bounds.minX + claim.bounds.maxX) / 2
        val cz = (claim.bounds.minZ + claim.bounds.maxZ) / 2
        val cy = world.getHighestBlockYAt(cx, cz) + 1
        player.teleport(Location(world, cx + 0.5, cy.toDouble(), cz + 0.5))
    }

    private fun fillBorder(inv: Inventory, mat: Material) {
        val filler = ItemStack(mat).apply {
            editMeta { it.displayName(Component.text(" ")) }
        }
        val size = inv.size
        for (i in 0 until size) {
            val row = i / 9
            val col = i % 9
            if (row == 0 || row == (size / 9) - 1 || col == 0 || col == 8) {
                inv.setItem(i, filler)
            }
        }
    }

    private fun icon(
        material: Material,
        nameMiniMessage: String,
        loreMiniMessages: List<String>,
        action: String? = null,
        target: String? = null
    ): ItemStack {
        val stack = ItemStack(material)
        stack.editMeta { meta ->
            meta.displayName(messages.renderRaw(nameMiniMessage))
            if (loreMiniMessages.isNotEmpty()) {
                meta.lore(loreMiniMessages.map { messages.renderRaw(it) })
            }
            if (action != null) {
                meta.persistentDataContainer.set(actionKey, PersistentDataType.STRING, action)
            }
            if (target != null) {
                meta.persistentDataContainer.set(targetKey, PersistentDataType.STRING, target)
            }
        }
        return stack
    }
}
