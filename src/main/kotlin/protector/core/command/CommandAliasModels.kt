package protector.core.command

import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Classification of an alias managed by [CommandAliasService], modeled after Obsidian's CMI-style system.
 */
enum class CommandAliasType {
    /** Primary standalone alias for a Protector subcommand (e.g. `/claim` -> `/protector claim $1-`). */
    BASE,

    /** Secondary standalone alias for a Protector subcommand (e.g. `/abandonclaim` -> `/protector abandon $1-`). */
    SUBBASE,

    /** User-defined custom alias loaded from `aliases.yml`. */
    CUSTOM,
}

/**
 * Represents a single regular, subbase, or custom command alias entry.
 */
data class CommandAliasEntry(
    val alias: String,
    val commands: List<String>,
    val enabled: Boolean = true,
    val type: CommandAliasType = CommandAliasType.CUSTOM,
    val targetSubcommand: String? = null,
    val tabComplete: Boolean = true,
    val baseDisabled: Boolean = false,
    val requiresPerm: Boolean = false,
    val exact: Boolean = false,
    val addTabs: Boolean = false,
    val customTabs: List<String> = emptyList(),
) {
    val baseCommand: String
        get() = alias.trim().substringBefore(' ').lowercase(Locale.ROOT)

    val permissionKey: String
        get() = alias.trim().replace(" ", "").lowercase(Locale.ROOT)

    val containsDynamicVariables: Boolean by lazy(LazyThreadSafetyMode.PUBLICATION) {
        commands.any { cmd ->
            DYNAMIC_VAR_PATTERN.matcher(cmd).find() || cmd.contains("{args}", ignoreCase = true)
        }
    }

    val cleanCommand: String
        get() {
            val first = commands.firstOrNull()?.trim().orEmpty()
            if (first.isEmpty()) return ""
            val tokens = first.split(' ')
            val kept = ArrayList<String>(tokens.size)
            for (token in tokens) {
                if (token.startsWith("$") || token.startsWith("[") || token.equals("{args}", ignoreCase = true)) {
                    break
                }
                kept += token
            }
            return kept.joinToString(" ")
        }

    companion object {
        val DYNAMIC_VAR_PATTERN: Pattern = Pattern.compile("\\$((\\d{1,2})(-?))(?!\\d)")
    }
}

/**
 * Obsidian/CMI-style alias lookup and positional variable (`$1`, `$1-`, `{args}`, `{player}`) expansion.
 */
object AliasMatcher {

    private val SPECIALIZED_PREFIXES = listOf(
        "console:",
        "player:",
        "op:",
        "msg:",
        "message:",
        "broadcast:",
        "bc:",
        "actionbar:",
        "bossbar:",
        "sound:",
        "delay!",
        "delay:",
    )

    fun isSpecializedCommandLine(line: String): Boolean {
        val trimmed = line.trim().lowercase(Locale.ROOT)
        return SPECIALIZED_PREFIXES.any { trimmed.startsWith(it) }
    }

    fun isSpecializedAlias(commands: List<String>): Boolean =
        commands.size != 1 || commands.firstOrNull()?.let { isSpecializedCommandLine(it) } == true

    /**
     * Resolves the best matching enabled [CommandAliasEntry] for [rawCommand] (without leading `/`).
     */
    fun findBestMatch(rawCommand: String, aliases: Map<String, CommandAliasEntry>): CommandAliasEntry? {
        val normalized = rawCommand.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return null

        var bestMatch: CommandAliasEntry? = null
        var bestLength = -1
        var bestExact = false

        for (entry in aliases.values) {
            if (!entry.enabled) continue
            val key = entry.alias.trim().lowercase(Locale.ROOT)
            if (key.isEmpty() || key.endsWith("?")) continue

            val isExactHit = normalized == key
            val isPrefixHit = if (entry.exact) {
                false
            } else {
                normalized.startsWith("$key ") && entry.containsDynamicVariables
            }

            if (isExactHit || isPrefixHit) {
                if (isExactHit && !bestExact) {
                    bestMatch = entry
                    bestLength = key.length
                    bestExact = true
                } else if (isExactHit == bestExact && key.length > bestLength) {
                    bestMatch = entry
                    bestLength = key.length
                }
            }
        }

        if (bestMatch != null) return bestMatch

        // Fallback to `?` wildcard aliases (e.g. `pt ?` matches `pt anything`)
        var wildcardMatch: CommandAliasEntry? = null
        var wildcardLen = -1
        for (entry in aliases.values) {
            if (!entry.enabled) continue
            val key = entry.alias.trim().lowercase(Locale.ROOT)
            if (!key.endsWith("?")) continue
            val base = key.removeSuffix("?").trim()
            val matches = if (base.isEmpty()) {
                true
            } else {
                normalized == base || normalized.startsWith("$base ")
            }
            if (matches && base.length > wildcardLen) {
                wildcardMatch = entry
                wildcardLen = base.length
            }
        }
        return wildcardMatch
    }

    /**
     * Expands `$1`, `$2`, `$1-`, `{args}`, and `{player}` inside `entry.commands` for [rawCommand].
     */
    fun expandCommands(entry: CommandAliasEntry, rawCommand: String, senderName: String): List<String> {
        val aliasPrefix = entry.alias.trim().let {
            if (it.endsWith("?")) it.removeSuffix("?").trim() else it
        }
        val trimmedRaw = rawCommand.trim()
        val remainder = if (aliasPrefix.isNotEmpty() && trimmedRaw.length > aliasPrefix.length &&
            trimmedRaw.startsWith(aliasPrefix, ignoreCase = true)
        ) {
            trimmedRaw.substring(aliasPrefix.length).trim()
        } else {
            ""
        }
        val argTokens = if (remainder.isEmpty()) emptyList() else remainder.split(Regex("\\s+"))

        return entry.commands.map { template ->
            expandLine(template, argTokens, remainder, senderName)
        }
    }

    private fun expandLine(
        template: String,
        argTokens: List<String>,
        remainder: String,
        senderName: String,
    ): String {
        var result = template
            .replace("{args}", remainder, ignoreCase = true)
            .replace("{player}", senderName, ignoreCase = true)

        val matcher = CommandAliasEntry.DYNAMIC_VAR_PATTERN.matcher(result)
        val sb = StringBuffer()
        while (matcher.find()) {
            val indexOneBased = matcher.group(2)?.toIntOrNull() ?: 0
            val rest = !matcher.group(3).isNullOrEmpty()
            val idx = indexOneBased - 1
            val replacement = if (idx < 0 || idx >= argTokens.size) {
                ""
            } else if (rest) {
                argTokens.subList(idx, argTokens.size).joinToString(" ")
            } else {
                argTokens[idx]
            }
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement))
        }
        matcher.appendTail(sb)
        result = sb.toString()

        return result.trimEnd()
    }
}
