package protector.modules.audit

import org.bukkit.Material
import java.util.Locale

/**
 * CoreProtect-style parameter parser (`u:`, `-u:`, `t:2d-1h`, `r:#global|<world>|<n>`,
 * `a:block|+block|-block|container|+container|-container|entity|sign|chat|session`,
 * `i:`, `e:`, `f:`, `#preview`, `#count`, `apply`/`now`).
 */
object AuditFilterParser {

    private val DURATION_TOKEN = Regex("(\\d+)(mo|[smhdwy])", RegexOption.IGNORE_CASE)

    fun parseDurationSeconds(token: String): Long? {
        val cleaned = token.trim().lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return null
        val matches = DURATION_TOKEN.findAll(cleaned).toList()
        if (matches.isEmpty()) return null
        if (matches.joinToString("") { it.value } != cleaned) return null

        var total = 0L
        for (m in matches) {
            val value = m.groupValues[1].toLongOrNull() ?: continue
            val mult = when (m.groupValues[2].lowercase(Locale.ROOT)) {
                "s" -> 1L
                "m" -> 60L
                "h" -> 3600L
                "d" -> 86400L
                "w" -> 604800L
                "mo" -> 2_592_000L
                "y" -> 31_536_000L
                else -> 0L
            }
            total += value * mult
        }
        return total.takeIf { it > 0L }
    }

    fun parseTimeRange(raw: String, nowSec: Long = System.currentTimeMillis() / 1000L): Pair<Long?, Long?> {
        val cleaned = raw.trim().lowercase(Locale.ROOT)
        if (cleaned.isEmpty()) return null to null
        if ('-' in cleaned) {
            val parts = cleaned.split('-', limit = 2)
            val d1 = parseDurationSeconds(parts[0])
            val d2 = parseDurationSeconds(parts[1])
            val t1 = d1?.let { nowSec - it }
            val t2 = d2?.let { nowSec - it }
            if (t1 != null && t2 != null) {
                return minOf(t1, t2) to maxOf(t1, t2)
            }
            return t1 to t2
        }
        val sec = parseDurationSeconds(cleaned) ?: return null to null
        return (nowSec - sec) to null
    }

    fun parseArgs(
        args: Array<out String>,
        defaultWorld: String,
        centerX: Int,
        centerY: Int,
        centerZ: Int,
        maxRadius: Int = 100,
        nowSec: Long = System.currentTimeMillis() / 1000L,
    ): LookupFilter {
        var user: String? = null
        val users = LinkedHashSet<String>()
        val excludedUsers = LinkedHashSet<String>()
        var sinceSec = nowSec - 3600L
        var untilSec: Long? = null
        var worldName: String? = defaultWorld
        var radius: Int? = 15
        var actionFilter = LookupActionFilter.ALL
        var blockActionFilter: BlockLogAction? = null
        var containerActionFilter: ContainerLogAction? = null
        val include = HashSet<String>()
        val exclude = HashSet<String>()
        var textFilter: String? = null
        var previewFlag = false
        var countOnly = false

        for (raw in args) {
            val lower = raw.lowercase(Locale.ROOT)
            when (lower) {
                "#preview", "#p" -> {
                    previewFlag = true
                    continue
                }
                "#count", "#c" -> {
                    countOnly = true
                    continue
                }
                "#global", "#g" -> {
                    radius = null
                    worldName = null
                    continue
                }
            }

            val idx = raw.indexOf(':')
            if (idx <= 0) continue
            val key = raw.substring(0, idx).lowercase(Locale.ROOT)
            val value = raw.substring(idx + 1).trim()
            if (value.isEmpty()) continue

            when (key) {
                "u", "user", "users" -> {
                    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { u ->
                        if (u.startsWith("-")) {
                            excludedUsers += u.removePrefix("-")
                        } else {
                            users += u
                        }
                    }
                    if (users.size == 1) user = users.first()
                }
                "-u", "eu" -> {
                    value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { excludedUsers += it }
                }
                "t", "time" -> {
                    val (s, u) = parseTimeRange(value, nowSec)
                    if (s != null) sinceSec = s
                    if (u != null) untilSec = u
                }
                "r", "radius" -> {
                    when (val rv = value.lowercase(Locale.ROOT)) {
                        "#global", "global" -> {
                            radius = null
                            worldName = null
                        }
                        else -> {
                            val num = rv.toIntOrNull()
                            if (num != null) {
                                radius = num.coerceIn(1, maxRadius)
                            } else {
                                worldName = value.removePrefix("#")
                                radius = null
                            }
                        }
                    }
                }
                "i", "include" -> value.split(',').mapNotNull { Material.matchMaterial(it.trim())?.name }.forEach { include.add(it) }
                "e", "exclude" -> value.split(',').mapNotNull { Material.matchMaterial(it.trim())?.name }.forEach { exclude.add(it) }
                "f", "filter" -> textFilter = value
                "a", "action" -> when (value.lowercase(Locale.ROOT)) {
                    "+block", "+blocks" -> {
                        actionFilter = LookupActionFilter.BLOCK
                        blockActionFilter = BlockLogAction.PLACE
                    }
                    "-block", "-blocks" -> {
                        actionFilter = LookupActionFilter.BLOCK
                        blockActionFilter = BlockLogAction.BREAK
                    }
                    "block", "blocks" -> actionFilter = LookupActionFilter.BLOCK
                    "+container", "+chest" -> {
                        actionFilter = LookupActionFilter.CONTAINER
                        containerActionFilter = ContainerLogAction.ADD
                    }
                    "-container", "-chest" -> {
                        actionFilter = LookupActionFilter.CONTAINER
                        containerActionFilter = ContainerLogAction.REMOVE
                    }
                    "container", "chest" -> actionFilter = LookupActionFilter.CONTAINER
                    "entity", "kill" -> actionFilter = LookupActionFilter.ENTITY
                    "sign", "signs" -> actionFilter = LookupActionFilter.SIGN
                    "chat", "command", "cmd" -> actionFilter = LookupActionFilter.CHAT
                    "session", "login" -> actionFilter = LookupActionFilter.SESSION
                    else -> actionFilter = LookupActionFilter.ALL
                }
            }
        }

        return LookupFilter(
            worldName = worldName ?: defaultWorld,
            globalWorld = worldName == null,
            centerX = centerX,
            centerY = centerY,
            centerZ = centerZ,
            radius = radius ?: 0,
            unboundedRadius = radius == null,
            sinceEpochSec = sinceSec,
            untilEpochSec = untilSec,
            userName = user,
            users = users,
            excludedUsers = excludedUsers,
            includeMaterials = include,
            excludeMaterials = exclude,
            actionFilter = actionFilter,
            blockActionFilter = blockActionFilter,
            containerActionFilter = containerActionFilter,
            textFilter = textFilter,
            previewRequested = previewFlag,
            countOnly = countOnly,
            limit = 2000,
        )
    }
}
