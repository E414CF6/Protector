package protector

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import protector.core.command.AliasMatcher
import protector.core.command.CommandAliasEntry
import protector.core.command.CommandAliasType
import protector.core.command.ProtectorCommandRegistry

class CommandAliasTest {

    @Test
    fun `AliasMatcher resolves exact, prefix with dynamic vars, disabled, and wildcard aliases`() {
        val claimAlias = CommandAliasEntry(
            alias = "claim",
            commands = listOf("protector claim \$1-"),
            enabled = true,
            type = CommandAliasType.BASE,
            targetSubcommand = "claim"
        )
        val exactStatusAlias = CommandAliasEntry(
            alias = "pstat",
            commands = listOf("protector status"),
            enabled = true,
            type = CommandAliasType.CUSTOM,
            exact = true
        )
        val disabledAlias = CommandAliasEntry(
            alias = "coi",
            commands = listOf("protector inspect \$1-"),
            enabled = false,
            type = CommandAliasType.SUBBASE,
            targetSubcommand = "inspect"
        )
        val wildcardAlias = CommandAliasEntry(
            alias = "myclaim ?",
            commands = listOf("protector claim \$1-"),
            enabled = true,
            type = CommandAliasType.CUSTOM
        )

        val map = linkedMapOf(
            "claim" to claimAlias,
            "pstat" to exactStatusAlias,
            "coi" to disabledAlias,
            "myclaim ?" to wildcardAlias
        )

        // Exact match and dynamic prefix match
        assertEquals(claimAlias, AliasMatcher.findBestMatch("claim", map))
        assertEquals(claimAlias, AliasMatcher.findBestMatch("claim 10", map))

        // Exact-only custom alias does not match trailing arguments
        assertEquals(exactStatusAlias, AliasMatcher.findBestMatch("pstat", map))
        assertNull(AliasMatcher.findBestMatch("pstat extra", map))

        // Disabled alias is ignored
        assertNull(AliasMatcher.findBestMatch("coi", map))

        // Wildcard `?` fallback matches trailing tokens
        assertEquals(wildcardAlias, AliasMatcher.findBestMatch("myclaim 15", map))
        assertEquals(wildcardAlias, AliasMatcher.findBestMatch("myclaim", map))
    }

    @Test
    fun `AliasMatcher expands positional arguments, rest args, {args}, and {player}`() {
        val trustAlias = CommandAliasEntry(
            alias = "trust",
            commands = listOf("protector trust \$1 \$2", "msg:\$1 was granted {args} by {player}")
        )
        val expanded = AliasMatcher.expandCommands(trustAlias, "trust Alice build", "OwnerSteve")
        assertEquals(
            listOf(
                "protector trust Alice build",
                "msg:Alice was granted Alice build by OwnerSteve"
            ),
            expanded
        )

        val restAlias = CommandAliasEntry(
            alias = "rb",
            commands = listOf("protector rollback \$1-")
        )
        assertEquals(
            listOf("protector rollback u:Steve t:1h r:15"),
            AliasMatcher.expandCommands(restAlias, "rb u:Steve t:1h r:15", "Admin")
        )
        assertEquals(
            listOf("protector rollback"),
            AliasMatcher.expandCommands(restAlias, "rb", "Admin")
        )
    }

    @Test
    fun `AliasMatcher identifies specialized alias lists vs simple single-command aliases`() {
        assertFalse(AliasMatcher.isSpecializedAlias(listOf("protector claim \$1-")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("console:say hi")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("msg:hello")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("protector inspect", "sound:UI_BUTTON_CLICK")))
    }

    @Test
    fun `ProtectorCommandRegistry registers and resolves subcommands and aliases case-insensitively`() {
        val registry = ProtectorCommandRegistry()
        var executedWith = emptyList<String>()

        registry.registerSubcommand(
            name = "Lookup",
            description = "Query audit history",
            permission = "protector.lookup",
            aliases = listOf("l", "search")
        ) { _: CommandSender, args: Array<out String> ->
            executedWith = args.toList()
            true
        }

        val byName = registry.getSubcommand("lookup")
        val byAlias = registry.getSubcommand("L")
        assertNotNull(byName)
        assertEquals(byName, byAlias)
        assertEquals(listOf("l", "search"), byName?.aliases)
        assertEquals(1, registry.allSubcommands().size)
        assertNull(registry.getSubcommand("missing"))
    }
}
