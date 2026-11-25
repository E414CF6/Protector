package protector.modules.claim

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.BlockFace
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import protector.core.ProtectorContext
import protector.core.command.ProtectorCommandRegistry
import protector.core.command.SubcommandTabCompleter
import protector.core.module.ProtectorModule

class ClaimModule(private val ctx: ProtectorContext) : ProtectorModule {
    override val id: String = "claim"
    override val description: String = "Land claims, trust matrix, grief prevention, and death drop protection"

    override fun onEnable() {
        ctx.claimManager.loadAll()
        ctx.claimManager.startAccrualTask()
    }

    override fun onDisable() {
        ctx.boundaryVisualizer.clearAll()
        ctx.claimManager.stopAccrualTask()
    }

    override fun listeners(): List<Listener> = listOf(
        ProtectionListener(
            ctx.scheduler,
            { ctx.config },
            ctx.messages,
            ctx.claimManager,
            ctx.claimToolService,
            ctx.boundaryVisualizer,
            ctx.actionBar,
            ctx.bossBar,
        ),
        ctx.deathDrops,
    )

    override fun registerSubcommands(registry: ProtectorCommandRegistry) {
        val playerSuggest = SubcommandTabCompleter { _, args ->
            if (args.size <= 1) {
                val prefix = args.firstOrNull().orEmpty()
                Bukkit.getOnlinePlayers().map { it.name }.filter { it.startsWith(prefix, ignoreCase = true) }
            } else emptyList()
        }

        val trustCompleter = SubcommandTabCompleter { _, args ->
            when (args.size) {
                0, 1 -> {
                    val prefix = args.firstOrNull().orEmpty()
                    (Bukkit.getOnlinePlayers().map { it.name } + "public")
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                }
                2 -> listOf("access", "container", "build", "manage")
                    .filter { it.startsWith(args[1], ignoreCase = true) }
                else -> emptyList()
            }
        }

        registry.registerSubcommand(
            name = "claim",
            description = "Create a land claim centered on your position",
            permission = "protector.claim",
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val cfg = ctx.config
            val radius = args.firstOrNull()?.toIntOrNull()?.coerceIn(2, 128) ?: cfg.autoClaimRadius
            val loc = player.location
            val bounds = BoundingBox.ofCorners(
                loc.world.name,
                loc.blockX - radius, loc.world.minHeight, loc.blockZ - radius,
                loc.blockX + radius, loc.world.maxHeight - 1, loc.blockZ + radius
            )
            val bypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            when (val result = ctx.claimManager.createClaim(player.uniqueId, bounds, isAdminBypass = bypass)) {
                is ClaimResult.Success -> {
                    ctx.boundaryVisualizer.showClaim(player, result.claim)
                    ctx.messages.send(
                        player,
                        "claim-created",
                        mapOf("w" to result.claim.bounds.width, "l" to result.claim.bounds.length, "area" to result.claim.bounds.area)
                    )
                }
                is ClaimResult.Failure -> reportClaimFailure(player, result)
            }
            true
        }

        registry.registerSubcommand(
            name = "claims",
            description = "Browse claims you own or are trusted in",
            permission = "protector.claim",
            aliases = listOf("claimslist", "list"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            ctx.menus.openClaimList(player)
            true
        }

        registry.registerSubcommand(
            name = "settings",
            description = "Edit settings and flags for the current claim",
            permission = "protector.claim",
            aliases = listOf("flags", "claimsettings"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            if (claim.isSubclaim) {
                ctx.dialogs.openSubclaimSettingsDialog(player, claim)
            } else {
                ctx.dialogs.openClaimSettingsDialog(player, claim)
            }
            true
        }

        registry.registerSubcommand(
            name = "trust",
            description = "Grant claim permissions to a player",
            permission = "protector.claim",
            aliases = listOf("tr"),
            tabCompleter = trustCompleter,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            if (args.isEmpty()) {
                ctx.dialogs.openTrustAddDialog(player, claim)
                return@registerSubcommand true
            }
            val targetName = args[0]
            val perm = args.getOrNull(1)?.let { ClaimPermission.parse(it) } ?: ClaimPermission.BUILD
            val key = if (targetName.equals("public", ignoreCase = true)) {
                Claim.PUBLIC_KEY
            } else {
                Bukkit.getOfflinePlayer(targetName).uniqueId.toString()
            }
            ctx.claimManager.setTrust(claim, key, perm)
            ctx.messages.send(player, "trust-updated", mapOf("target" to targetName, "level" to perm.displayName))
            true
        }

        registry.registerSubcommand(
            name = "untrust",
            description = "Revoke a player's claim permissions",
            permission = "protector.claim",
            aliases = listOf("ut"),
            tabCompleter = playerSuggest,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val targetName = args.firstOrNull()
            if (targetName == null) {
                ctx.menus.openClaimTrustMenu(player, claim)
                return@registerSubcommand true
            }
            val key = if (targetName.equals("public", ignoreCase = true)) {
                Claim.PUBLIC_KEY
            } else {
                Bukkit.getOfflinePlayer(targetName).uniqueId.toString()
            }
            ctx.claimManager.removeTrust(claim, key)
            ctx.messages.send(player, "trust-removed", mapOf("target" to targetName))
            true
        }

        registry.registerSubcommand(
            name = "extend",
            description = "Extend or shrink the current claim in your facing direction",
            permission = "protector.claim",
            aliases = listOf("extendclaim", "expandclaim", "expand"),
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val amount = args.firstOrNull()?.toIntOrNull()
            if (amount == null || amount == 0) {
                ctx.messages.sendRaw(player, "<yellow>Usage: /extendclaim <blocks> (expands or shrinks in the direction you face)</yellow>")
                return@registerSubcommand true
            }
            val facing = cardinalFacing(player.location.yaw)
            when (val res = ctx.claimManager.extendClaimInDirection(claim, facing, amount, adminBypass)) {
                is ClaimResult.Success -> {
                    ctx.boundaryVisualizer.showClaim(player, res.claim)
                    ctx.messages.send(
                        player,
                        "claim-resized",
                        mapOf("w" to res.claim.bounds.width, "l" to res.claim.bounds.length, "area" to res.claim.bounds.area)
                    )
                }
                is ClaimResult.Failure -> reportClaimFailure(player, res)
            }
            true
        }

        registry.registerSubcommand(
            name = "transfer",
            description = "Transfer ownership of the current claim",
            permission = "protector.claim",
            aliases = listOf("transferclaim"),
            tabCompleter = playerSuggest,
        ) { sender, args ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getTopLevelClaimAt(player.world.name, player.location.blockX, player.location.blockZ)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (claim.ownerId != player.uniqueId && !adminBypass) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val targetName = args.firstOrNull()
            if (targetName == null) {
                ctx.dialogs.openTransferClaimDialog(player, claim)
                return@registerSubcommand true
            }
            val offline = Bukkit.getOfflinePlayer(targetName)
            ctx.dialogs.openConfirmationDialog(
                player,
                "<gold><bold>Confirm Ownership Transfer</bold></gold>",
                "<gray>Transfer ownership of <white>${claim.name}</white> to <white>$targetName</white>?</gray>",
                "<gold>Transfer</gold>"
            ) { p ->
                ctx.claimManager.transferClaim(claim, offline.uniqueId)
                ctx.messages.send(p, "claim-transferred", mapOf("target" to targetName))
            }
            true
        }

        registry.registerSubcommand(
            name = "restrict",
            description = "Toggle permission inheritance isolation on a subclaim",
            permission = "protector.claim",
            aliases = listOf("restrictsubclaim", "rsc"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getClaimAt(player.location)
            if (claim == null || !claim.isSubclaim) {
                ctx.messages.sendRaw(player, "<yellow>You must be standing inside a subclaim.</yellow>")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (!claim.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            val next = !claim.inheritNothing
            ctx.claimManager.updateMetadata(claim, claim.name, claim.greeting, next)
            ctx.messages.send(player, "subclaim-restricted", mapOf("state" to if (next) "Isolated" else "Inheriting"))
            true
        }

        registry.registerSubcommand(
            name = "abandon",
            description = "Abandon the current claim",
            permission = "protector.claim",
            aliases = listOf("abandonclaim", "unclaim"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getClaimAt(player.location)
            if (claim == null) {
                ctx.messages.send(player, "not-in-claim")
                return@registerSubcommand true
            }
            val adminBypass = ctx.actionBar.state(player.uniqueId).adminBypass && player.hasPermission("protector.admin")
            if (claim.ownerId != player.uniqueId && !adminBypass) {
                ctx.actionBar.sendDenial(player, "deny-manage", mapOf("owner" to claim.ownerName()))
                return@registerSubcommand true
            }
            ctx.dialogs.openConfirmationDialog(
                player,
                "<red><bold>Abandon Claim</bold></red>",
                "<gray>Are you sure you want to abandon <white>${claim.name}</white>?</gray>",
                "<red>Abandon</red>"
            ) { p ->
                val refund = ctx.claimManager.deleteClaim(claim)
                ctx.boundaryVisualizer.clear(p)
                ctx.messages.send(p, "claim-abandoned", mapOf("refund" to refund))
            }
            true
        }

        registry.registerSubcommand(
            name = "abandonall",
            description = "Abandon all claims you own",
            permission = "protector.claim",
            aliases = listOf("abandonallclaims"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val owned = ctx.claimManager.getPlayerClaims(player.uniqueId)
            if (owned.isEmpty()) {
                ctx.messages.sendRaw(player, "<gray>You do not own any claims.</gray>")
                return@registerSubcommand true
            }
            ctx.dialogs.openConfirmationDialog(
                player,
                "<red><bold>Abandon All Claims</bold></red>",
                "<gray>Permanently abandon all <white>${owned.size}</white> claims you own.\nAre you sure you want to continue?</gray>",
                "<red>Abandon All</red>"
            ) { p ->
                val (count, refund) = ctx.claimManager.deleteAllPlayerClaims(p.uniqueId)
                ctx.boundaryVisualizer.clear(p)
                ctx.messages.send(p, "claims-abandoned-all", mapOf("count" to count, "refund" to refund))
            }
            true
        }

        registry.registerSubcommand(
            name = "subdivide",
            description = "Toggle subclaim shovel mode",
            permission = "protector.claim",
            aliases = listOf("subdivideclaims", "sc"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val st = ctx.actionBar.state(player.uniqueId)
            st.shovelMode = if (st.shovelMode == ShovelMode.SUBDIVIDE) ShovelMode.BASIC else ShovelMode.SUBDIVIDE
            ctx.actionBar.tickPlayer(player)
            ctx.messages.sendRaw(player, "<aqua>Claim tool mode switched to <white>${st.shovelMode.label}</white>.</aqua>")
            true
        }

        registry.registerSubcommand(
            name = "adminclaims",
            description = "Toggle admin claim shovel mode",
            permission = "protector.admin",
            aliases = listOf("ac"),
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val st = ctx.actionBar.state(player.uniqueId)
            st.shovelMode = if (st.shovelMode == ShovelMode.ADMIN) ShovelMode.BASIC else ShovelMode.ADMIN
            ctx.actionBar.tickPlayer(player)
            ctx.messages.sendRaw(player, "<gold>Claim tool mode switched to <white>${st.shovelMode.label}</white>.</gold>")
            true
        }

        registry.registerSubcommand(
            name = "unlockdrops",
            description = "Unlock protected death drop items",
            permission = "protector.claim",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            ctx.deathDrops.unlockDrops(player)
            true
        }

        registry.registerSubcommand(
            name = "adjustblocks",
            description = "Adjust bonus claim blocks for a player",
            permission = "protector.admin",
            aliases = listOf("adjustclaimblocks", "acb"),
            tabCompleter = playerSuggest,
        ) { sender, args ->
            if (args.size < 2) {
                val player = sender as? Player
                if (player != null) {
                    ctx.dialogs.openAdminClaimBlocksDialog(player)
                } else {
                    ctx.messages.sendRaw(sender, "<yellow>Usage: /adjustclaimblocks <player> <amount></yellow>")
                }
                return@registerSubcommand true
            }
            val targetName = args[0]
            val delta = args[1].toIntOrNull()
            if (delta == null) {
                ctx.messages.sendRaw(sender, "<red>Amount must be a valid integer.</red>")
                return@registerSubcommand true
            }
            val offline = Bukkit.getOfflinePlayer(targetName)
            ctx.claimManager.addBonusBlocks(offline.uniqueId, delta)
            val data = ctx.claimManager.getOrCreatePlayerData(offline.uniqueId)
            val avail = ctx.claimManager.getAvailableClaimBlocks(offline.uniqueId)
            ctx.messages.send(
                sender,
                "claim-blocks-adjusted",
                mapOf("target" to targetName, "bonus" to data.bonusBlocks, "available" to avail)
            )
            true
        }

        registry.registerSubcommand(
            name = "trapped",
            description = "Escape to nearby wilderness if trapped inside a claim",
            permission = "protector.claim",
        ) { sender, _ ->
            val player = requirePlayer(sender) ?: return@registerSubcommand true
            val claim = ctx.claimManager.getTopLevelClaimAt(player.world.name, player.location.blockX, player.location.blockZ)
            if (claim == null || claim.hasPermission(player.uniqueId, ClaimPermission.BUILD, false)) {
                ctx.messages.send(player, "trapped-not-needed")
                return@registerSubcommand true
            }
            val b = claim.bounds
            val loc = player.location
            val candidates = listOf(
                Location(player.world, (b.minX - 2).toDouble(), loc.y, loc.z),
                Location(player.world, (b.maxX + 2).toDouble(), loc.y, loc.z),
                Location(player.world, loc.x, loc.y, (b.minZ - 2).toDouble()),
                Location(player.world, loc.x, loc.y, (b.maxZ + 2).toDouble())
            )
            val best = candidates
                .filter { ctx.claimManager.getTopLevelClaimAt(it.world.name, it.blockX, it.blockZ) == null }
                .minByOrNull { it.distanceSquared(loc) }
            if (best == null) {
                ctx.messages.sendRaw(player, "<red>Could not find a nearby safe wilderness spot.</red>")
                return@registerSubcommand true
            }
            val safeY = player.world.getHighestBlockYAt(best.blockX, best.blockZ) + 1
            best.x = best.blockX + 0.5
            best.y = safeY.toDouble()
            best.z = best.blockZ + 0.5
            player.teleport(best)
            ctx.messages.send(player, "trapped-success")
            true
        }
    }

    private fun requirePlayer(sender: CommandSender): Player? {
        if (sender is Player) return sender
        ctx.messages.send(sender, "player-only")
        return null
    }

    private fun reportClaimFailure(player: Player, failure: ClaimResult.Failure) {
        val cfg = ctx.config
        when (failure.reason) {
            ClaimResult.Reason.OVERLAPS_EXISTING -> ctx.messages.send(player, "claim-overlap")
            ClaimResult.Reason.TOO_SMALL -> ctx.messages.send(
                player,
                "claim-too-small",
                mapOf("min_w" to cfg.minClaimWidth, "min_area" to cfg.minClaimArea)
            )
            ClaimResult.Reason.INSUFFICIENT_BLOCKS -> ctx.messages.send(
                player,
                "claim-insufficient-blocks",
                mapOf("needed" to failure.needed, "available" to failure.available)
            )
            ClaimResult.Reason.OUTSIDE_PARENT -> ctx.messages.sendRaw(player, "<red>Subclaim cannot extend outside its parent claim.</red>")
            else -> ctx.messages.sendRaw(player, "<red>Unable to create or update claim.</red>")
        }
    }

    private fun cardinalFacing(yaw: Float): BlockFace {
        val norm = ((yaw % 360f) + 360f) % 360f
        return when {
            norm < 45f || norm >= 315f -> BlockFace.SOUTH
            norm < 135f -> BlockFace.WEST
            norm < 225f -> BlockFace.NORTH
            else -> BlockFace.EAST
        }
    }
}
