package protector.modules.audit

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.DyeColor
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.block.Sign
import org.bukkit.block.data.BlockData
import org.bukkit.block.sign.Side
import org.bukkit.entity.Ageable
import org.bukkit.entity.EntityType
import org.bukkit.entity.Monster
import org.bukkit.entity.Player
import org.bukkit.entity.Villager
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.material.Colorable
import protector.core.config.ProtectorConfig
import protector.core.locale.MessageService
import protector.core.scheduler.ProtectorScheduler
import protector.core.scheduler.TaskHandle
import protector.core.storage.AuditLogRepository
import protector.core.storage.BlockLogAction
import protector.core.storage.BlockLogRecord
import protector.core.storage.ContainerLogAction
import protector.core.storage.ContainerLogRecord
import protector.core.storage.EntityLogAction
import protector.core.storage.EntityLogRecord
import protector.core.storage.LookupActionFilter
import protector.core.storage.LookupFilter
import protector.core.storage.SignLogRecord
import protector.core.ui.BossBarService
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class BlockEditPlan(
    val logId: Long,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val targetMaterial: String,
    val targetBlockData: String,
    val revertMaterial: String,
    val revertBlockData: String
)

data class ContainerEditPlan(
    val logId: Long,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val material: String,
    val amount: Int,
    val addItems: Boolean
)

data class EntityRespawnPlan(
    val logId: Long,
    val worldName: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val entityType: String,
    val metadata: String?
)

data class RollbackBundle(
    val blockPlans: List<BlockEditPlan>,
    val containerPlans: List<ContainerEditPlan>,
    val entityPlans: List<EntityRespawnPlan>,
    val signSnapshots: Map<String, List<SignLogRecord>>
) {
    val totalCount: Int get() = blockPlans.size + containerPlans.size + entityPlans.size
}

data class PendingPreview(
    val filter: LookupFilter,
    val restoreMode: Boolean,
    val bundle: RollbackBundle,
    val previewLocations: List<Location>,
    val expireTask: TaskHandle?
)

data class UndoRecord(
    val restoreMode: Boolean,
    val blockPlans: List<BlockEditPlan>,
    val containerPlans: List<ContainerEditPlan>
)

class RollbackService(
    private val scheduler: ProtectorScheduler,
    private val configProvider: () -> ProtectorConfig,
    private val messages: MessageService,
    private val repository: AuditLogRepository,
    private val consumer: LogQueueConsumer,
    private val bossBar: BossBarService
) {
    private val previews = ConcurrentHashMap<UUID, PendingPreview>()
    private val undoHistory = ConcurrentHashMap<UUID, UndoRecord>()
    private val gson = Gson()
    private val stringListType = object : TypeToken<List<String>>() {}.type
    private val mapType = object : TypeToken<Map<String, Any>>() {}.type

    companion object {
        private const val PREVIEW_BAR_KEY = "rollback_preview"
        private const val PROGRESS_BAR_KEY = "rollback_progress"
        private const val PURGE_BAR_KEY = "purge_progress"

        fun parseDurationSeconds(token: String): Long? {
            val regex = Regex("(\\d+)([smhdw])", RegexOption.IGNORE_CASE)
            val matches = regex.findAll(token.trim()).toList()
            if (matches.isEmpty()) return null
            var total = 0L
            for (m in matches) {
                val value = m.groupValues[1].toLongOrNull() ?: continue
                val mult = when (m.groupValues[2].lowercase()) {
                    "s" -> 1L
                    "m" -> 60L
                    "h" -> 3600L
                    "d" -> 86400L
                    "w" -> 604800L
                    else -> 0L
                }
                total += value * mult
            }
            return total.takeIf { it > 0L }
        }
    }

    fun startPreview(player: Player, filter: LookupFilter, restoreMode: Boolean = false) {
        cancelPreview(player, silent = true)
        val effectiveFilter = filter.copy(rolledBackFilter = restoreMode)

        scheduler.runAsync {
            consumer.flushNow()
            val bundle = queryBundle(effectiveFilter, restoreMode)

            scheduler.runForEntity(player) {
                if (!player.isOnline) return@runForEntity
                if (bundle.totalCount == 0) {
                    messages.send(player, "rollback-no-matches")
                    return@runForEntity
                }
                val world = Bukkit.getWorld(effectiveFilter.worldName) ?: return@runForEntity
                val previewLocs = ArrayList<Location>(minOf(bundle.blockPlans.size, 4000))
                for (plan in bundle.blockPlans.take(4000)) {
                    val loc = Location(world, plan.x.toDouble(), plan.y.toDouble(), plan.z.toDouble())
                    val data = parseBlockData(plan.targetMaterial, plan.targetBlockData)
                    runCatching {
                        player.sendBlockChange(loc, data)
                        previewLocs.add(loc)
                    }
                }

                val expireTicks = configProvider().previewDurationSeconds * 20L
                val expireTask = scheduler.runForEntityDelayed(player, expireTicks) {
                    cancelPreview(player, silent = false)
                }

                previews[player.uniqueId] = PendingPreview(effectiveFilter, restoreMode, bundle, previewLocs, expireTask)
                val label = if (restoreMode) "Restore" else "Rollback"
                bossBar.showOrUpdate(
                    player,
                    PREVIEW_BAR_KEY,
                    messages.renderRaw(
                        "<aqua>👁 $label Preview (Blocks ${bundle.blockPlans.size} · Containers ${bundle.containerPlans.size} · Entities ${bundle.entityPlans.size}) — <white>/pr apply</white> | <white>/pr cancel</white></aqua>"
                    ),
                    1.0f,
                    BossBar.Color.BLUE
                )
                messages.send(player, "rollback-preview-started", mapOf("count" to bundle.totalCount))
            }
        }
    }

    fun applyPreview(player: Player): Boolean {
        val pending = previews.remove(player.uniqueId) ?: return false
        pending.expireTask?.cancel()
        restoreClientBlocks(player, pending.previewLocations)
        bossBar.hide(player, PREVIEW_BAR_KEY)
        executeBundle(player, pending.bundle, pending.restoreMode, recordUndo = true)
        return true
    }

    fun cancelPreview(player: Player, silent: Boolean = false): Boolean {
        val pending = previews.remove(player.uniqueId) ?: return false
        pending.expireTask?.cancel()
        restoreClientBlocks(player, pending.previewLocations)
        bossBar.hide(player, PREVIEW_BAR_KEY)
        if (!silent) {
            messages.send(player, "rollback-cancelled")
        }
        return true
    }

    fun executeDirect(player: Player, filter: LookupFilter, restoreMode: Boolean = false) {
        cancelPreview(player, silent = true)
        val effectiveFilter = filter.copy(rolledBackFilter = restoreMode)
        scheduler.runAsync {
            consumer.flushNow()
            val bundle = queryBundle(effectiveFilter, restoreMode)
            scheduler.runForEntity(player) {
                if (!player.isOnline) return@runForEntity
                if (bundle.totalCount == 0) {
                    messages.send(player, "rollback-no-matches")
                    return@runForEntity
                }
                executeBundle(player, bundle, restoreMode, recordUndo = true)
            }
        }
    }

    fun undoLast(player: Player) {
        val last = undoHistory.remove(player.uniqueId)
        if (last == null || (last.blockPlans.isEmpty() && last.containerPlans.isEmpty())) {
            messages.send(player, "undo-empty")
            return
        }
        val invertedBlocks = last.blockPlans.reversed().map { p ->
            p.copy(
                targetMaterial = p.revertMaterial,
                targetBlockData = p.revertBlockData,
                revertMaterial = p.targetMaterial,
                revertBlockData = p.targetBlockData
            )
        }
        val invertedContainers = last.containerPlans.reversed().map { c ->
            c.copy(addItems = !c.addItems)
        }
        executeBundle(
            player,
            RollbackBundle(invertedBlocks, invertedContainers, emptyList(), emptyMap()),
            restoreMode = !last.restoreMode,
            recordUndo = false,
            isUndo = true
        )
    }

    fun purgeBefore(audience: Audience, player: Player?, olderThanSeconds: Long, worldName: String? = null) {
        val cutoff = (System.currentTimeMillis() / 1000L) - olderThanSeconds
        if (player != null) {
            bossBar.showOrUpdate(
                player,
                PURGE_BAR_KEY,
                messages.renderRaw("<yellow>🧹 Purging old audit logs...</yellow>"),
                0.5f,
                BossBar.Color.YELLOW
            )
        }
        scheduler.runAsync {
            consumer.flushNow()
            val deleted = repository.purgeBefore(cutoff, worldName)
            scheduler.runGlobal {
                if (player != null && player.isOnline) {
                    bossBar.completeAndAutoHide(
                        player,
                        PURGE_BAR_KEY,
                        messages.renderRaw("<green>✔ Purge complete ($deleted entries deleted)</green>")
                    )
                }
                messages.send(audience, "purge-done", mapOf("count" to deleted))
            }
        }
    }

    private fun queryBundle(filter: LookupFilter, restoreMode: Boolean): RollbackBundle {
        val cfg = configProvider()
        val blockLogs = if (filter.actionFilter == LookupActionFilter.ALL || filter.actionFilter == LookupActionFilter.BLOCK) {
            repository.queryBlockLogs(filter, ascendingTime = restoreMode)
                .filter { it.action != BlockLogAction.INTERACT }
        } else emptyList()

        val containerLogs = if (cfg.rollbackContainers && (filter.actionFilter == LookupActionFilter.ALL || filter.actionFilter == LookupActionFilter.CONTAINER)) {
            repository.queryContainerLogs(filter, ascendingTime = restoreMode)
        } else emptyList()

        val entityLogs = if (!restoreMode && cfg.rollbackEntities && (filter.actionFilter == LookupActionFilter.ALL || filter.actionFilter == LookupActionFilter.ENTITY)) {
            repository.queryEntityLogs(filter, ascendingTime = false)
                .filter { it.action == EntityLogAction.KILL }
        } else emptyList()

        val signLogs = repository.querySignLogs(filter).groupBy { "${it.x},${it.y},${it.z}" }

        return RollbackBundle(
            blockPlans = buildBlockPlans(blockLogs, restoreMode),
            containerPlans = buildContainerPlans(containerLogs, restoreMode),
            entityPlans = buildEntityPlans(entityLogs),
            signSnapshots = signLogs
        )
    }

    private fun executeBundle(
        player: Player,
        bundle: RollbackBundle,
        restoreMode: Boolean,
        recordUndo: Boolean,
        isUndo: Boolean = false
    ) {
        val total = bundle.totalCount
        val perTick = configProvider().rollbackBlocksPerTick
        var blockCursor = 0
        var containerDone = false
        var entitiesDone = false

        val verb = when {
            isUndo -> "Undo"
            restoreMode -> "Restore"
            else -> "Rollback"
        }

        if (recordUndo) {
            undoHistory[player.uniqueId] = UndoRecord(restoreMode, bundle.blockPlans, bundle.containerPlans)
        }

        var taskRef: TaskHandle? = null
        taskRef = scheduler.runGlobalTimer(1L, 1L) {
            // Phase 1: Blocks + Signs
            if (blockCursor < bundle.blockPlans.size) {
                val end = minOf(blockCursor + perTick, bundle.blockPlans.size)
                while (blockCursor < end) {
                    val plan = bundle.blockPlans[blockCursor++]
                    val world = Bukkit.getWorld(plan.worldName) ?: continue
                    val loc = Location(world, plan.x.toDouble(), plan.y.toDouble(), plan.z.toDouble())
                    scheduler.runAtLocation(loc) {
                        val block = world.getBlockAt(plan.x, plan.y, plan.z)
                        val data = parseBlockData(plan.targetMaterial, plan.targetBlockData)
                        block.setBlockData(data, false)

                        val state = block.state
                        if (state is Sign) {
                            val signs = bundle.signSnapshots["${plan.x},${plan.y},${plan.z}"]
                            if (!signs.isNullOrEmpty()) {
                                applySignSnapshots(state, signs)
                            }
                        }
                    }
                }
            }

            // Phase 2: Containers (once blocks/chests are restored)
            if (blockCursor >= bundle.blockPlans.size && !containerDone) {
                containerDone = true
                for (c in bundle.containerPlans) {
                    val world = Bukkit.getWorld(c.worldName) ?: continue
                    scheduler.runAtLocation(Location(world, c.x.toDouble(), c.y.toDouble(), c.z.toDouble())) {
                        applyContainerEdit(c)
                    }
                }
            }

            // Phase 3: Entities
            if (blockCursor >= bundle.blockPlans.size && containerDone && !entitiesDone) {
                entitiesDone = true
                for (e in bundle.entityPlans) {
                    val world = Bukkit.getWorld(e.worldName) ?: continue
                    scheduler.runAtLocation(Location(world, e.x.toDouble(), e.y.toDouble(), e.z.toDouble())) {
                        respawnEntity(e)
                    }
                }
            }

            val processed = blockCursor +
                (if (containerDone) bundle.containerPlans.size else 0) +
                (if (entitiesDone) bundle.entityPlans.size else 0)
            val ratio = if (total == 0) 1f else processed.toFloat() / total.toFloat()

            if (player.isOnline) {
                bossBar.showOrUpdate(
                    player,
                    PROGRESS_BAR_KEY,
                    messages.renderRaw("<aqua>⚙ $verb in progress: ${(ratio * 100).toInt()}% ($processed / $total)</aqua>"),
                    ratio,
                    BossBar.Color.BLUE
                )
            }

            if (blockCursor >= bundle.blockPlans.size && containerDone && entitiesDone) {
                taskRef?.cancel()
                val blockIds = bundle.blockPlans.map { it.logId }
                val containerIds = bundle.containerPlans.map { it.logId }
                val entityIds = bundle.entityPlans.map { it.logId }
                scheduler.runAsync {
                    repository.markBlocksRolledBack(blockIds, rolledBack = !restoreMode)
                    repository.markContainersRolledBack(containerIds, rolledBack = !restoreMode)
                    repository.markEntitiesRolledBack(entityIds, rolledBack = !restoreMode)
                }
                if (player.isOnline) {
                    bossBar.completeAndAutoHide(
                        player,
                        PROGRESS_BAR_KEY,
                        messages.renderRaw("<green>✔ $verb complete! ($total entries)</green>")
                    )
                    if (isUndo) {
                        messages.send(player, "undo-applied", mapOf("count" to total))
                    } else {
                        messages.send(player, "rollback-applied", mapOf("count" to total))
                    }
                }
            }
        }
    }

    private fun applySignSnapshots(sign: Sign, logs: List<SignLogRecord>) {
        for (side in Side.entries) {
            val latest = logs.firstOrNull { it.side.equals(side.name, ignoreCase = true) } ?: continue
            val lines: List<String> = runCatching { gson.fromJson<List<String>>(latest.linesJson, stringListType) }
                .getOrNull() ?: continue
            val signSide = sign.getSide(side)
            for (i in 0 until minOf(4, lines.size)) {
                signSide.line(i, Component.text(lines[i]))
            }
        }
        sign.update(false, false)
    }

    private fun applyContainerEdit(plan: ContainerEditPlan) {
        val world = Bukkit.getWorld(plan.worldName) ?: return
        val block = world.getBlockAt(plan.x, plan.y, plan.z)
        val holder = block.state as? BlockInventoryHolder ?: return
        val mat = Material.matchMaterial(plan.material)?.takeIf { it.isItem && !it.isAir } ?: return
        val inv = holder.inventory
        var remaining = plan.amount
        if (plan.addItems) {
            while (remaining > 0) {
                val stackAmt = minOf(remaining, mat.maxStackSize)
                inv.addItem(ItemStack(mat, stackAmt))
                remaining -= stackAmt
            }
        } else {
            for (slot in 0 until inv.size) {
                if (remaining <= 0) break
                val item = inv.getItem(slot) ?: continue
                if (item.type != mat) continue
                val take = minOf(remaining, item.amount)
                if (take >= item.amount) {
                    inv.setItem(slot, null)
                } else {
                    item.amount -= take
                }
                remaining -= take
            }
        }
    }

    private fun respawnEntity(plan: EntityRespawnPlan) {
        val world = Bukkit.getWorld(plan.worldName) ?: return
        val type = runCatching { EntityType.valueOf(plan.entityType) }.getOrNull() ?: return
        val entityClass = type.entityClass ?: return
        if (Monster::class.java.isAssignableFrom(entityClass)) return
        val loc = Location(world, plan.x + 0.5, plan.y.toDouble(), plan.z + 0.5)
        val spawned = runCatching { world.spawnEntity(loc, type) }.getOrNull() ?: return

        val meta: Map<String, Any> = plan.metadata?.let {
            runCatching { gson.fromJson<Map<String, Any>>(it, mapType) }.getOrNull()
        } ?: return

        (meta["customName"] as? String)?.let { spawned.customName(Component.text(it)) }
        if (spawned is Ageable && meta["adult"] == false) {
            spawned.setBaby()
        }
        if (spawned is Colorable) {
            (meta["color"] as? String)?.let { colorName ->
                runCatching { DyeColor.valueOf(colorName) }.getOrNull()?.let { spawned.color = it }
            }
        }
        if (spawned is Villager) {
            (meta["profession"] as? String)?.let { profKey ->
                runCatching { NamespacedKey.fromString(profKey) }.getOrNull()?.let { key ->
                    Registry.VILLAGER_PROFESSION.get(key)?.let { spawned.profession = it }
                }
            }
        }
    }

    private fun buildBlockPlans(logs: List<BlockLogRecord>, restoreMode: Boolean): List<BlockEditPlan> {
        val plans = ArrayList<BlockEditPlan>(logs.size)
        for (log in logs) {
            if (restoreMode) {
                plans.add(
                    BlockEditPlan(
                        logId = log.id,
                        worldName = log.worldName,
                        x = log.x,
                        y = log.y,
                        z = log.z,
                        targetMaterial = log.newMaterial,
                        targetBlockData = log.newBlockData,
                        revertMaterial = log.oldMaterial,
                        revertBlockData = log.oldBlockData
                    )
                )
            } else {
                plans.add(
                    BlockEditPlan(
                        logId = log.id,
                        worldName = log.worldName,
                        x = log.x,
                        y = log.y,
                        z = log.z,
                        targetMaterial = log.oldMaterial,
                        targetBlockData = log.oldBlockData,
                        revertMaterial = log.newMaterial,
                        revertBlockData = log.newBlockData
                    )
                )
            }
        }
        return plans
    }

    private fun buildContainerPlans(logs: List<ContainerLogRecord>, restoreMode: Boolean): List<ContainerEditPlan> =
        logs.map { log ->
            val wasRemoved = log.action == ContainerLogAction.REMOVE
            // Rollback: if removed -> add back; if added -> remove. Restore: opposite.
            val addItems = if (restoreMode) !wasRemoved else wasRemoved
            ContainerEditPlan(
                logId = log.id,
                worldName = log.worldName,
                x = log.x,
                y = log.y,
                z = log.z,
                material = log.material,
                amount = log.amount,
                addItems = addItems
            )
        }

    private fun buildEntityPlans(logs: List<EntityLogRecord>): List<EntityRespawnPlan> =
        logs.map { log ->
            EntityRespawnPlan(
                logId = log.id,
                worldName = log.worldName,
                x = log.x,
                y = log.y,
                z = log.z,
                entityType = log.entityType,
                metadata = log.metadata
            )
        }

    private fun parseBlockData(materialName: String, rawBlockData: String): BlockData {
        if (rawBlockData.isNotBlank()) {
            runCatching { return Bukkit.createBlockData(rawBlockData) }
        }
        val mat = Material.matchMaterial(materialName) ?: Material.AIR
        return mat.createBlockData()
    }

    private fun restoreClientBlocks(player: Player, locs: List<Location>) {
        if (!player.isOnline) return
        for (loc in locs) {
            runCatching { player.sendBlockChange(loc, loc.block.blockData) }
        }
    }
}
