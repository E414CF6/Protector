package protector.core.command

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandMap
import org.bukkit.command.CommandSender
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.RemoteServerCommandEvent
import org.bukkit.event.server.ServerCommandEvent
import org.bukkit.event.server.TabCompleteEvent
import protector.core.CoreHost
import java.io.File
import java.lang.reflect.Method
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class CommandAliasService(private val host: CoreHost) : Listener {

    private val commandAliasFile = File(host.plugin.dataFolder, "command-aliases.yml")
    private val customAliasFile = File(host.plugin.dataFolder, "aliases.yml")

    private val regularAliases = LinkedHashMap<String, CommandAliasEntry>()
    private val customAliases = LinkedHashMap<String, CommandAliasEntry>()
    private val disabledBaseSubcommands = ConcurrentHashMap.newKeySet<String>()
    private val registeredCommands = LinkedHashMap<String, AliasCommandProxy>()

    @Volatile
    private var combinedAliases: Map<String, CommandAliasEntry> = emptyMap()

    private val dispatchDepth = ThreadLocal.withInitial { 0 }

    private val syncCommandsMethod: Method? by lazy {
        runCatching {
            Bukkit.getServer().javaClass.getMethod("syncCommands")
        }.getOrNull()
    }

    fun initializeFromRegistry() {
        loadRegularAliases()
        loadCustomAliases()
        rebuildCache()
        reregisterCommandMapAliases(syncClients = false)
    }

    fun reload() {
        if (host.commandRegistry.allSubcommands().isEmpty()) {
            loadCustomAliases()
            rebuildCache()
            return
        }
        loadRegularAliases()
        loadCustomAliases()
        rebuildCache()
        reregisterCommandMapAliases(syncClients = true)
    }

    fun shutdown() {
        unregisterCommandMapAliases()
        registeredCommands.clear()
    }

    fun getRegularAliases(): Map<String, CommandAliasEntry> = synchronized(this) { regularAliases.toMap() }

    fun getCustomAliases(): Map<String, List<String>> = synchronized(this) {
        customAliases.mapValues { it.value.commands }
    }

    fun getAllAliases(): Map<String, CommandAliasEntry> = combinedAliases

    fun isBaseDisabled(subcommand: String): Boolean =
        disabledBaseSubcommands.contains(subcommand.trim().lowercase(Locale.ROOT))

    fun setCustomAlias(name: String, commands: List<String>) {
        val cleanName = name.trim().lowercase(Locale.ROOT).removePrefix("/")
        if (cleanName.isEmpty()) return
        if (commands.any { it.trim().removePrefix("/").equals(cleanName, ignoreCase = true) }) {
            host.plugin.logger.warning("Ignored recursive custom alias '/$cleanName'.")
            return
        }
        synchronized(this) {
            customAliases[cleanName] = CommandAliasEntry(
                alias = cleanName,
                commands = commands.toList(),
                enabled = true,
                type = CommandAliasType.CUSTOM,
                tabComplete = true,
            )
            rebuildCache()
        }
        host.scheduler.runAsync { saveCustomAliases() }
        reregisterCommandMapAliases(syncClients = true)
    }

    fun removeCustomAlias(name: String): Boolean {
        val cleanName = name.trim().lowercase(Locale.ROOT).removePrefix("/")
        val removed = synchronized(this) {
            val ex = customAliases.remove(cleanName) != null
            if (ex) rebuildCache()
            ex
        }
        if (removed) {
            host.scheduler.runAsync { saveCustomAliases() }
            reregisterCommandMapAliases(syncClients = true)
        }
        return removed
    }

    private fun loadRegularAliases() {
        val yaml = if (commandAliasFile.exists()) {
            YamlConfiguration.loadConfiguration(commandAliasFile)
        } else {
            YamlConfiguration()
        }

        val rootName = host.rootCommandName
        val rootLabels = (listOf(rootName) + host.rootAliases).joinToString(", ") { "/$it" }
        yaml.options().setHeader(
            listOf(
                "${host.pluginDisplayName} Dynamic Command Alias Configuration",
                "All commands are anchored under /$rootName (<subcommand>) (aliases: $rootLabels).",
                "Set 'enabled: true/false' to toggle standalone CommandMap registration.",
                "Set 'tab: false' to disable tab completion for a standalone alias.",
                "Set 'base-disabled: true' to disable '/$rootName <subcommand>' while keeping the standalone alias.",
            )
        )

        val newRegular = LinkedHashMap<String, CommandAliasEntry>()
        val newDisabledBase = HashSet<String>()
        var modified = !commandAliasFile.exists()
        val standaloneDefault = host.registerStandaloneAliases

        for (sub in host.commandRegistry.allSubcommands()) {
            val primaryPath = "aliases.${sub.name}"
            val primaryTarget = "$rootName ${sub.name} $1-"
            if (!yaml.isConfigurationSection(primaryPath)) {
                yaml.set("$primaryPath.command", primaryTarget)
                yaml.set("$primaryPath.enabled", sub.standaloneByDefault)
                yaml.set("$primaryPath.tab", true)
                yaml.set("$primaryPath.base-disabled", false)
                modified = true
            }
            val primaryEnabled = yaml.getBoolean("$primaryPath.enabled", sub.standaloneByDefault)
            val primaryTab = yaml.getBoolean("$primaryPath.tab", true)
            val primaryBaseDisabled = yaml.getBoolean("$primaryPath.base-disabled", false)
            val primaryCmd = yaml.getString("$primaryPath.command", primaryTarget) ?: primaryTarget

            if (primaryBaseDisabled) {
                newDisabledBase += sub.name
            }

            newRegular[sub.name] = CommandAliasEntry(
                alias = sub.name,
                commands = listOf(primaryCmd),
                enabled = primaryEnabled && standaloneDefault,
                type = CommandAliasType.BASE,
                targetSubcommand = sub.name,
                tabComplete = primaryTab,
                baseDisabled = primaryBaseDisabled,
            )

            for (secAlias in sub.aliases) {
                if (newRegular.containsKey(secAlias)) continue
                val secPath = "aliases.$secAlias"
                val secTarget = "$rootName ${sub.name} $1-"
                if (!yaml.isConfigurationSection(secPath)) {
                    yaml.set("$secPath.command", secTarget)
                    yaml.set("$secPath.enabled", sub.standaloneByDefault)
                    yaml.set("$secPath.tab", true)
                    modified = true
                }
                val secEnabled = yaml.getBoolean("$secPath.enabled", sub.standaloneByDefault)
                val secTab = yaml.getBoolean("$secPath.tab", true)
                val secCmd = yaml.getString("$secPath.command", secTarget) ?: secTarget

                newRegular[secAlias] = CommandAliasEntry(
                    alias = secAlias,
                    commands = listOf(secCmd),
                    enabled = secEnabled && standaloneDefault,
                    type = CommandAliasType.SUBBASE,
                    targetSubcommand = sub.name,
                    tabComplete = secTab,
                )
            }
        }

        if (modified) {
            runCatching {
                commandAliasFile.parentFile?.mkdirs()
                yaml.save(commandAliasFile)
            }.onFailure { err ->
                host.plugin.logger.warning("Failed to save command-aliases.yml: ${err.message}")
            }
        }

        synchronized(this) {
            regularAliases.clear()
            regularAliases.putAll(newRegular)
        }
        disabledBaseSubcommands.clear()
        disabledBaseSubcommands.addAll(newDisabledBase)
    }

    private fun loadCustomAliases() {
        if (!customAliasFile.exists()) {
            createDefaultCustomAliases()
            return
        }
        val yaml = YamlConfiguration.loadConfiguration(customAliasFile)
        val sec = yaml.getConfigurationSection("aliases")
        val loaded = LinkedHashMap<String, CommandAliasEntry>()
        if (sec != null) {
            for (rawKey in sec.getKeys(false)) {
                val key = rawKey.trim().lowercase(Locale.ROOT).removePrefix("/")
                if (key.isEmpty()) continue
                val entry = when {
                    sec.isConfigurationSection(rawKey) -> {
                        val sub = sec.getConfigurationSection(rawKey)!!
                        val cmds = when {
                            sub.isList("cmds") -> sub.getStringList("cmds")
                            sub.isList("Cmds") -> sub.getStringList("Cmds")
                            sub.isString("cmd") -> listOfNotNull(sub.getString("cmd"))
                            else -> emptyList()
                        }
                        if (cmds.isEmpty()) null
                        else CommandAliasEntry(
                            alias = key,
                            commands = cmds,
                            enabled = sub.getBoolean("enabled", true),
                            type = CommandAliasType.CUSTOM,
                            tabComplete = sub.getBoolean("tab", true),
                            requiresPerm = sub.getBoolean("perm", false),
                            exact = sub.getBoolean("exact-match", false),
                            addTabs = sub.getBoolean("add-tabs", false),
                            customTabs = sub.getStringList("custom-tabs"),
                        )
                    }

                    sec.isList(rawKey) -> {
                        val lines = sec.getStringList(rawKey)
                        if (lines.isEmpty()) null
                        else CommandAliasEntry(alias = key, commands = lines, type = CommandAliasType.CUSTOM)
                    }

                    sec.isString(rawKey) -> {
                        val line = sec.getString(rawKey)
                        if (line.isNullOrBlank()) null
                        else CommandAliasEntry(alias = key, commands = listOf(line), type = CommandAliasType.CUSTOM)
                    }

                    else -> null
                }
                if (entry != null) {
                    if (entry.commands.any { it.trim().removePrefix("/").equals(key, ignoreCase = true) }) {
                        host.plugin.logger.warning("Ignored recursive custom alias '/$key'.")
                    } else {
                        loaded[key] = entry
                    }
                }
            }
        }

        synchronized(this) {
            customAliases.clear()
            customAliases.putAll(loaded)
        }
    }

    private fun createDefaultCustomAliases() {
        val defaults = host.defaultCustomAliases
        val yaml = YamlConfiguration()
        for ((k, v) in defaults) {
            yaml.set("aliases.$k", v)
        }
        runCatching {
            customAliasFile.parentFile?.mkdirs()
            yaml.save(customAliasFile)
        }
        synchronized(this) {
            customAliases.clear()
            for ((k, v) in defaults) {
                customAliases[k] = CommandAliasEntry(alias = k, commands = v, type = CommandAliasType.CUSTOM)
            }
        }
    }

    private fun saveCustomAliases() {
        val snapshot = synchronized(this) { customAliases.toMap() }
        val yaml = YamlConfiguration()
        for ((key, entry) in snapshot) {
            if (!entry.requiresPerm && !entry.exact && !entry.addTabs && entry.tabComplete && entry.customTabs.isEmpty() && entry.enabled) {
                yaml.set("aliases.$key", entry.commands)
            } else {
                yaml.set("aliases.$key.cmds", entry.commands)
                yaml.set("aliases.$key.enabled", entry.enabled)
                if (entry.requiresPerm) yaml.set("aliases.$key.perm", true)
                if (!entry.tabComplete) yaml.set("aliases.$key.tab", false)
                if (entry.exact) yaml.set("aliases.$key.exact-match", true)
                if (entry.addTabs) yaml.set("aliases.$key.add-tabs", true)
                if (entry.customTabs.isNotEmpty()) yaml.set("aliases.$key.custom-tabs", entry.customTabs)
            }
        }
        runCatching {
            customAliasFile.parentFile?.mkdirs()
            yaml.save(customAliasFile)
        }
    }

    private fun rebuildCache() {
        synchronized(this) {
            val merged = LinkedHashMap<String, CommandAliasEntry>(regularAliases.size + customAliases.size)
            merged.putAll(regularAliases)
            merged.putAll(customAliases)
            combinedAliases = merged.toMap()
        }
    }

    private fun resolveCommandMap(): CommandMap? =
        runCatching { Bukkit.getCommandMap() }.getOrElse {
            runCatching {
                val server = Bukkit.getServer()
                val field = server.javaClass.getDeclaredField("commandMap")
                field.isAccessible = true
                field.get(server) as? CommandMap
            }.getOrNull()
        }

    private fun rootTokens(): Set<String> =
        (setOf(host.rootCommandName.lowercase(Locale.ROOT)) + host.rootAliases.map { it.lowercase(Locale.ROOT) })

    @Synchronized
    private fun reregisterCommandMapAliases(syncClients: Boolean) {
        val commandMap = resolveCommandMap() ?: return
        unregisterCommandMapAliases()

        val roots = rootTokens()
        val namespace = host.rootCommandName.lowercase(Locale.ROOT)
        val baseEntries = LinkedHashMap<String, CommandAliasEntry>()
        for (entry in combinedAliases.values) {
            if (!entry.enabled) continue
            val base = entry.baseCommand
            if (base.isEmpty() || base in roots) continue
            val existing = baseEntries[base]
            if (existing == null || (existing.type == CommandAliasType.CUSTOM && entry.type != CommandAliasType.CUSTOM)) {
                baseEntries[base] = entry
            }
        }

        for ((base, entry) in baseEntries) {
            val desc = entry.targetSubcommand?.let {
                host.commandRegistry.getSubcommand(it)?.description
            }.orEmpty().ifEmpty { "${host.pluginDisplayName} command alias" }
            val proxy = AliasCommandProxy(base, this, desc)
            commandMap.register(namespace, proxy)
            registeredCommands[base] = proxy
        }

        if (syncClients) {
            syncServerCommands()
        }
    }

    @Synchronized
    private fun unregisterCommandMapAliases() {
        if (registeredCommands.isEmpty()) return
        val commandMap = resolveCommandMap() ?: return
        val known: MutableMap<String, Command>? = runCatching { commandMap.knownCommands }.getOrNull()
        val namespace = host.rootCommandName.lowercase(Locale.ROOT)

        for ((base, cmd) in registeredCommands) {
            if (known != null) {
                if (known[base] === cmd) known.remove(base)
                val namespaced = "$namespace:$base"
                if (known[namespaced] === cmd) known.remove(namespaced)
            }
            cmd.unregister(commandMap)
        }
        registeredCommands.clear()
    }

    private fun syncServerCommands() {
        runCatching {
            syncCommandsMethod?.invoke(Bukkit.getServer())
        }
        if (Bukkit.isPrimaryThread()) {
            Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
        } else {
            host.scheduler.runGlobal {
                Bukkit.getOnlinePlayers().forEach { it.updateCommands() }
            }
        }
    }

    fun canUseAlias(sender: CommandSender, aliasLabel: String): Boolean {
        val key = aliasLabel.trim().lowercase(Locale.ROOT)
        val entry = combinedAliases[key] ?: return true
        if (!entry.enabled) return false

        if (entry.type == CommandAliasType.CUSTOM) {
            if (!entry.requiresPerm) return true
            return sender.hasPermission("${host.customAliasPermissionPrefix}${entry.permissionKey}") ||
                sender.hasPermission(host.adminPermission)
        }

        val subName = entry.targetSubcommand ?: return true
        val sub = host.commandRegistry.getSubcommand(subName) ?: return true
        val perm = sub.permission ?: return true
        return sender.hasPermission(perm)
    }

    fun executeFromCommandMap(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        val cleanLabel = commandLabel.substringAfter(':').trim().lowercase(Locale.ROOT)
        val rawCommand = if (args.isEmpty()) cleanLabel else "$cleanLabel ${args.joinToString(" ")}"
        val entry = AliasMatcher.findBestMatch(rawCommand, combinedAliases) ?: return false
        if (!entry.enabled) return false

        if (entry.type == CommandAliasType.CUSTOM && entry.requiresPerm &&
            !sender.hasPermission("${host.customAliasPermissionPrefix}${entry.permissionKey}") &&
            !sender.hasPermission(host.adminPermission)
        ) {
            host.messages.send(sender, "no-permission")
            return true
        }

        val depth = dispatchDepth.get()
        if (depth >= MAX_ALIAS_DISPATCH_DEPTH) {
            host.plugin.logger.warning("Prevented recursive alias dispatch for '/$rawCommand'.")
            return false
        }

        val expanded = AliasMatcher.expandCommands(entry, rawCommand, sender.name)
        if (expanded.isEmpty()) return true

        dispatchDepth.set(depth + 1)
        return try {
            if (!AliasMatcher.isSpecializedAlias(expanded)) {
                val cmdLine = expanded.first().removePrefix("/").trim()
                if (cmdLine.isEmpty()) true else Bukkit.dispatchCommand(sender, cmdLine)
            } else {
                val remainder = rawCommand.removePrefix(entry.alias).trim()
                SpecializedCommandExecutor.executeAll(
                    host,
                    expanded,
                    sender as? Player,
                    mapOf("args" to remainder, "player" to sender.name),
                )
                true
            }
        } finally {
            dispatchDepth.set(depth)
        }
    }

    fun tabCompleteAlias(
        sender: CommandSender,
        aliasLabel: String,
        args: Array<out String>,
        location: Location?,
    ): List<String> {
        val cleanLabel = aliasLabel.substringAfter(':').trim().lowercase(Locale.ROOT)
        val argPart = args.joinToString(" ")
        val rawBuffer = if (args.isEmpty()) cleanLabel else "$cleanLabel $argPart"
        val entry = AliasMatcher.findBestMatch(rawBuffer, combinedAliases)
            ?: combinedAliases[cleanLabel]
            ?: return emptyList()

        if (!entry.enabled || !entry.tabComplete) return emptyList()

        if (entry.type == CommandAliasType.CUSTOM) {
            if (entry.customTabs.isNotEmpty()) {
                val idx = (args.size - 1).coerceAtLeast(0)
                val prefix = args.lastOrNull().orEmpty()
                val rawChoices = entry.customTabs.getOrNull(idx)
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotEmpty() }
                    .orEmpty()
                return rawChoices.filter { it.startsWith(prefix, ignoreCase = true) }
            }
            val clean = entry.cleanCommand.removePrefix("/").trim()
            val cleanTokens = clean.split(Regex("\\s+"))
            if (!AliasMatcher.isSpecializedAlias(entry.commands) &&
                cleanTokens.size >= 2 &&
                cleanTokens[0].lowercase(Locale.ROOT) in rootTokens()
            ) {
                val sub = host.commandRegistry.getSubcommand(cleanTokens[1]) ?: return emptyList()
                val combinedArgs = cleanTokens.drop(2) + args.toList()
                return sub.tabCompleter?.complete(sender, combinedArgs.toTypedArray()).orEmpty()
            }
            return emptyList()
        }

        val subTarget = entry.targetSubcommand ?: return emptyList()
        val sub = host.commandRegistry.getSubcommand(subTarget) ?: return emptyList()
        if (sub.permission != null && !sender.hasPermission(sub.permission)) return emptyList()
        return sub.tabCompleter?.complete(sender, args).orEmpty()
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlayerCommandPreprocess(event: PlayerCommandPreprocessEvent) {
        val raw = normalizeCommandInput(event.message) ?: return

        val disabledSub = extractDisabledBaseSubcommand(raw)
        if (disabledSub != null) {
            event.isCancelled = true
            host.messages.send(event.player, "command-disabled", mapOf("command" to disabledSub))
            return
        }

        val entry = AliasMatcher.findBestMatch(raw, combinedAliases) ?: return
        if (!entry.enabled) return

        if (entry.type == CommandAliasType.CUSTOM && entry.requiresPerm &&
            !event.player.hasPermission("${host.customAliasPermissionPrefix}${entry.permissionKey}") &&
            !event.player.hasPermission(host.adminPermission)
        ) {
            event.isCancelled = true
            host.messages.send(event.player, "no-permission")
            return
        }

        val expanded = AliasMatcher.expandCommands(entry, raw, event.player.name)
        if (expanded.isEmpty()) return

        if (!AliasMatcher.isSpecializedAlias(expanded)) {
            val targetCmd = expanded.first().removePrefix("/").trim()
            if (targetCmd.isNotEmpty()) {
                event.message = "/$targetCmd"
            }
        } else {
            event.isCancelled = true
            val remainder = raw.removePrefix(entry.alias).trim()
            SpecializedCommandExecutor.executeAll(
                host,
                expanded,
                event.player,
                mapOf("args" to remainder, "player" to event.player.name),
            )
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        val raw = normalizeCommandInput(event.command) ?: return
        val disabledSub = extractDisabledBaseSubcommand(raw)
        if (disabledSub != null) {
            event.isCancelled = true
            host.messages.send(event.sender, "command-disabled", mapOf("command" to disabledSub))
            return
        }

        val entry = AliasMatcher.findBestMatch(raw, combinedAliases) ?: return
        if (!entry.enabled) return

        val expanded = AliasMatcher.expandCommands(entry, raw, event.sender.name)
        if (expanded.isEmpty()) return

        if (!AliasMatcher.isSpecializedAlias(expanded)) {
            val targetCmd = expanded.first().removePrefix("/").trim()
            if (targetCmd.isNotEmpty()) {
                event.command = targetCmd
            }
        } else {
            event.isCancelled = true
            val remainder = raw.removePrefix(entry.alias).trim()
            SpecializedCommandExecutor.executeAll(
                host,
                expanded,
                null,
                mapOf("args" to remainder, "player" to event.sender.name),
            )
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    fun onRemoteServerCommand(event: RemoteServerCommandEvent) {
        val raw = normalizeCommandInput(event.command) ?: return
        val disabledSub = extractDisabledBaseSubcommand(raw)
        if (disabledSub != null) {
            event.isCancelled = true
            host.messages.send(event.sender, "command-disabled", mapOf("command" to disabledSub))
            return
        }

        val entry = AliasMatcher.findBestMatch(raw, combinedAliases) ?: return
        if (!entry.enabled) return

        val expanded = AliasMatcher.expandCommands(entry, raw, event.sender.name)
        if (expanded.isEmpty()) return

        if (!AliasMatcher.isSpecializedAlias(expanded)) {
            val targetCmd = expanded.first().removePrefix("/").trim()
            if (targetCmd.isNotEmpty()) {
                event.command = targetCmd
            }
        } else {
            event.isCancelled = true
            val remainder = raw.removePrefix(entry.alias).trim()
            SpecializedCommandExecutor.executeAll(
                host,
                expanded,
                null,
                mapOf("args" to remainder, "player" to event.sender.name),
            )
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onTabComplete(event: TabCompleteEvent) {
        val rawBuffer = event.buffer.removePrefix("/")
        if (rawBuffer.isBlank()) return

        val parts = rawBuffer.split(' ')
        val tokenIndex = parts.size - 1
        val currentPrefix = parts.lastOrNull().orEmpty()
        val lowerPrefix = rawBuffer.lowercase(Locale.ROOT)
        val customCandidates = ArrayList<String>()
        for (entry in combinedAliases.values) {
            if (!entry.enabled || entry.type != CommandAliasType.CUSTOM || !entry.tabComplete) continue
            if (entry.requiresPerm &&
                !event.sender.hasPermission("${host.customAliasPermissionPrefix}${entry.permissionKey}") &&
                !event.sender.hasPermission(host.adminPermission)
            ) {
                continue
            }
            if (entry.alias.lowercase(Locale.ROOT).startsWith(lowerPrefix)) {
                val aliasTokens = entry.alias.split(' ')
                if (aliasTokens.size > tokenIndex && tokenIndex > 0) {
                    val candidate = aliasTokens[tokenIndex]
                    if (candidate.startsWith(currentPrefix, ignoreCase = true)) {
                        customCandidates += candidate
                    }
                }
            }
        }
        if (customCandidates.isNotEmpty()) {
            val merged = LinkedHashSet(event.completions)
            merged.addAll(customCandidates)
            event.completions = merged.toList()
        }
    }

    private fun normalizeCommandInput(input: String): String? {
        var trimmed = input.trim()
        if (trimmed.startsWith("/:")) trimmed = trimmed.substring(2)
        if (trimmed.startsWith("/")) trimmed = trimmed.substring(1)
        trimmed = trimmed.trimStart()
        if (trimmed.isEmpty()) return null

        val prefix = "${host.rootCommandName.lowercase(Locale.ROOT)}:"
        val firstSpace = trimmed.indexOf(' ')
        val firstToken = if (firstSpace < 0) trimmed else trimmed.substring(0, firstSpace)
        val cleanFirst = if (firstToken.startsWith(prefix, ignoreCase = true)) {
            firstToken.substring(prefix.length)
        } else {
            firstToken
        }
        return if (firstSpace < 0) cleanFirst else cleanFirst + trimmed.substring(firstSpace)
    }

    private fun extractDisabledBaseSubcommand(rawCommand: String): String? {
        val tokens = rawCommand.split(Regex("\\s+"), limit = 3)
        if (tokens.size < 2) return null
        val root = tokens[0].lowercase(Locale.ROOT)
        if (root !in rootTokens()) return null
        val sub = tokens[1].lowercase(Locale.ROOT)
        return if (isBaseDisabled(sub)) sub else null
    }

    companion object {
        private const val MAX_ALIAS_DISPATCH_DEPTH = 4
    }
}
