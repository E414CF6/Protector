package protector.modules.claim

import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.entity.Player
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.ui.ActionBarHudService
import protector.core.ui.BossBarService

class ClaimToolService(
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val claimManager: ClaimManager,
    private val visualizer: BoundaryVisualizer,
    private val actionBar: ActionBarHudService,
    private val bossBar: BossBarService
) {
    companion object {
        private const val MODE_BAR_KEY = "claim_mode"
    }

    fun handleInspectStick(player: Player, target: Location) {
        val claim = claimManager.getClaimAt(target)
        if (claim == null) {
            actionBar.flash(player, messages.render("wilderness"))
            val nearby = claimManager.getClaimsOverlapping(
                BoundingBox.ofCorners(
                    target.world.name,
                    target.blockX - 24, target.blockY, target.blockZ - 24,
                    target.blockX + 24, target.blockY, target.blockZ + 24
                )
            )
            if (nearby.isNotEmpty()) {
                visualizer.showClaim(player, nearby.first(), Material.DIAMOND_BLOCK)
                messages.sendRaw(
                    player,
                    "<gray>Highlighted nearby claim (${nearby.first().name}, Owner: ${
                        nearby.first().ownerName()
                    }).</gray>"
                )
            } else {
                messages.sendRaw(player, "<gray>No claims found nearby.</gray>")
            }
            return
        }

        visualizer.showClaim(player, claim)
        val perm = claim.effectivePermission(player.uniqueId)
        val permLabel = perm?.displayName ?: "None"
        messages.sendRaw(
            player,
            "<aqua>[${claim.name}]</aqua> <gray>Owner:</gray> <white>${claim.ownerName()}</white> " +
                    "<gray>| Size:</gray> <white>${claim.bounds.width}×${claim.bounds.length} (${claim.bounds.area} blocks)</white> " +
                    "<gray>| Your Trust:</gray> <green>$permLabel</green>"
        )
    }

    fun handleShovelRightClick(player: Player, clicked: Location) {
        val cfg = configProvider()
        val state = actionBar.state(player.uniqueId)
        val adminBypass = state.adminBypass && player.hasPermission("protector.admin")
        val existingAtClick = claimManager.getClaimAt(clicked)

        // Case 1: Already waiting for second corner
        val corner1 = state.pendingCorner1
        if (corner1 != null && corner1.world?.name == clicked.world.name) {
            state.pendingCorner1 = null
            bossBar.hide(player, MODE_BAR_KEY)

            val resizing = state.resizingClaim
            if (resizing != null) {
                state.resizingClaim = null
                finishResize(player, resizing, corner1, clicked, adminBypass)
                return
            }

            val subdivParent = state.subdividingParent
            if (subdivParent != null) {
                state.subdividingParent = null
                finishSubclaim(player, subdivParent, corner1, clicked)
                return
            }

            finishNewClaim(player, corner1, clicked, state.shovelMode, adminBypass)
            return
        }

        // Case 2: Clicked a corner of an existing claim -> start resize
        if (existingAtClick != null && state.shovelMode != ShovelMode.SUBDIVIDE) {
            if (isCorner(existingAtClick.bounds, clicked)) {
                if (!existingAtClick.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                    actionBar.sendDenial(player, "deny-manage", mapOf("owner" to existingAtClick.ownerName()))
                    return
                }
                state.resizingClaim = existingAtClick
                state.pendingCorner1 = clicked.clone()
                visualizer.showClaim(player, existingAtClick, Material.EMERALD_BLOCK)
                bossBar.showOrUpdate(
                    player,
                    MODE_BAR_KEY,
                    messages.renderRaw("<aqua>📐 Resizing claim — Right-click the new corner position</aqua>"),
                    1.0f,
                    BossBar.Color.YELLOW
                )
                return
            }
        }

        // Case 3: Subdivide mode
        if (state.shovelMode == ShovelMode.SUBDIVIDE) {
            val top = claimManager.getTopLevelClaimAt(clicked.world.name, clicked.blockX, clicked.blockZ)
            if (top == null) {
                messages.send(player, "not-in-claim")
                return
            }
            if (!top.hasPermission(player.uniqueId, ClaimPermission.MANAGE, adminBypass)) {
                actionBar.sendDenial(player, "deny-manage", mapOf("owner" to top.ownerName()))
                return
            }
            state.subdividingParent = top
            state.pendingCorner1 = clicked.clone()
            visualizer.showClaim(player, top, Material.LAPIS_BLOCK)
            bossBar.showOrUpdate(
                player,
                MODE_BAR_KEY,
                messages.renderRaw("<light_purple>⊞ Creating subclaim — Right-click the opposite corner</light_purple>"),
                1.0f,
                BossBar.Color.PURPLE
            )
            return
        }

        // Case 4: Start a new top-level claim
        if (existingAtClick != null) {
            messages.send(player, "claim-overlap")
            visualizer.showClaim(player, existingAtClick)
            return
        }

        state.pendingCorner1 = clicked.clone()
        visualizer.showBounds(
            player,
            BoundingBox.ofCorners(
                clicked.world.name,
                clicked.blockX,
                clicked.blockY,
                clicked.blockZ,
                clicked.blockX,
                clicked.blockY,
                clicked.blockZ
            ),
            Material.DIAMOND_BLOCK,
            Material.DIAMOND_BLOCK
        )
        bossBar.showOrUpdate(
            player,
            MODE_BAR_KEY,
            messages.renderRaw("<aqua>📐 Creating new claim — Right-click the diagonal opposite corner</aqua>"),
            1.0f,
            BossBar.Color.BLUE
        )
        actionBar.tickPlayer(player)
        if (!cfg.isWorldEnabled(clicked.world.name)) {
            state.pendingCorner1 = null
            bossBar.hide(player, MODE_BAR_KEY)
            messages.sendRaw(player, "<red>Claims are disabled in this world.</red>")
        }
    }

    fun cancelSelection(player: Player) {
        val state = actionBar.state(player.uniqueId)
        state.pendingCorner1 = null
        state.resizingClaim = null
        state.subdividingParent = null
        bossBar.hide(player, MODE_BAR_KEY)
        visualizer.clear(player)
    }

    private fun finishNewClaim(
        player: Player,
        c1: Location,
        c2: Location,
        mode: ShovelMode,
        adminBypass: Boolean
    ) {
        val world = c1.world
        val ownerId = if (mode == ShovelMode.ADMIN && player.hasPermission("protector.admin")) null else player.uniqueId
        val bounds = BoundingBox.ofCorners(
            world.name,
            c1.blockX, world.minHeight, c1.blockZ,
            c2.blockX, world.maxHeight - 1, c2.blockZ
        )
        when (val result = claimManager.createClaim(ownerId, bounds, isAdminBypass = adminBypass || ownerId == null)) {
            is ClaimResult.Success -> {
                visualizer.showClaim(player, result.claim)
                messages.send(
                    player,
                    "claim-created",
                    mapOf(
                        "w" to result.claim.bounds.width,
                        "l" to result.claim.bounds.length,
                        "area" to result.claim.bounds.area
                    )
                )
            }

            is ClaimResult.Failure -> reportFailure(player, result)
        }
    }

    private fun finishSubclaim(player: Player, parent: Claim, c1: Location, c2: Location) {
        val bounds = BoundingBox.ofCorners(
            parent.bounds.worldName,
            c1.blockX, minOf(c1.blockY, c2.blockY), c1.blockZ,
            c2.blockX, maxOf(c1.blockY, c2.blockY), c2.blockZ
        )
        when (val result = claimManager.createSubclaim(parent, bounds)) {
            is ClaimResult.Success -> {
                visualizer.showClaim(player, result.claim, Material.IRON_BLOCK)
                messages.send(player, "subclaim-created")
            }

            is ClaimResult.Failure -> reportFailure(player, result)
        }
    }

    private fun finishResize(
        player: Player,
        claim: Claim,
        oldCorner: Location,
        newCorner: Location,
        adminBypass: Boolean
    ) {
        val b = claim.bounds
        val newMinX = if (oldCorner.blockX == b.minX) newCorner.blockX else b.minX
        val newMaxX = if (oldCorner.blockX == b.maxX) newCorner.blockX else b.maxX
        val newMinZ = if (oldCorner.blockZ == b.minZ) newCorner.blockZ else b.minZ
        val newMaxZ = if (oldCorner.blockZ == b.maxZ) newCorner.blockZ else b.maxZ

        val newBounds = BoundingBox.ofCorners(
            b.worldName,
            newMinX, b.minY, newMinZ,
            newMaxX, b.maxY, newMaxZ
        )
        when (val result = claimManager.resizeClaim(claim, newBounds, isAdminBypass = adminBypass)) {
            is ClaimResult.Success -> {
                visualizer.showClaim(player, result.claim)
                messages.send(
                    player,
                    "claim-resized",
                    mapOf(
                        "w" to result.claim.bounds.width,
                        "l" to result.claim.bounds.length,
                        "area" to result.claim.bounds.area
                    )
                )
            }

            is ClaimResult.Failure -> reportFailure(player, result)
        }
    }

    private fun reportFailure(player: Player, failure: ClaimResult.Failure) {
        val cfg = configProvider()
        when (failure.reason) {
            ClaimResult.Reason.TOO_SMALL -> messages.send(
                player,
                "claim-too-small",
                mapOf("min_w" to cfg.minClaimWidth, "min_area" to cfg.minClaimArea)
            )

            ClaimResult.Reason.OVERLAPS_EXISTING -> messages.send(player, "claim-overlap")
            ClaimResult.Reason.INSUFFICIENT_BLOCKS -> messages.send(
                player,
                "claim-insufficient-blocks",
                mapOf("needed" to failure.needed, "available" to failure.available)
            )

            ClaimResult.Reason.OUTSIDE_PARENT -> messages.sendRaw(
                player,
                "<red>Subclaim cannot extend outside its parent claim.</red>"
            )

            ClaimResult.Reason.NOT_OWNER -> messages.send(player, "deny-manage", mapOf("owner" to "-"))
            ClaimResult.Reason.WORLD_DISABLED -> messages.sendRaw(
                player,
                "<red>Claims are disabled in this world.</red>"
            )
        }
    }

    private fun isCorner(bounds: BoundingBox, loc: Location): Boolean {
        val x = loc.blockX
        val z = loc.blockZ
        return (x == bounds.minX || x == bounds.maxX) && (z == bounds.minZ || z == bounds.maxZ)
    }
}
