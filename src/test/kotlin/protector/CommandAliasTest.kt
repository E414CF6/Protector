package protector

import org.bukkit.command.CommandSender
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import protector.core.command.AliasMatcher
import protector.core.command.CommandAliasEntry
import protector.core.command.CommandAliasType
import protector.core.command.CommandRegistry

class CommandAliasTest {

    @Test
    fun `AliasMatcher resolves exact, prefix with dynamic vars, disabled, and wildcard aliases`() {
        val guardAlias = CommandAliasEntry(
            alias = "guard",
            commands = listOf("protector guard \$1-"),
            enabled = true,
            type = CommandAliasType.BASE,
            targetSubcommand = "guard"
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
            alias = "myrb ?",
            commands = listOf("protector rollback \$1-"),
            enabled = true,
            type = CommandAliasType.CUSTOM
        )

        val map = linkedMapOf(
            "guard" to guardAlias,
            "pstat" to exactStatusAlias,
            "coi" to disabledAlias,
            "myrb ?" to wildcardAlias
        )

        assertEquals(guardAlias, AliasMatcher.findBestMatch("guard", map))
        assertEquals(guardAlias, AliasMatcher.findBestMatch("guard tnt-block off", map))
        assertEquals(exactStatusAlias, AliasMatcher.findBestMatch("pstat", map))
        assertNull(AliasMatcher.findBestMatch("pstat extra", map))
        assertNull(AliasMatcher.findBestMatch("coi", map))
        assertEquals(wildcardAlias, AliasMatcher.findBestMatch("myrb t:1h", map))
        assertEquals(wildcardAlias, AliasMatcher.findBestMatch("myrb", map))
    }

    @Test
    fun `AliasMatcher expands positional arguments, rest args, {args}, and {player}`() {
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
        assertFalse(AliasMatcher.isSpecializedAlias(listOf("protector guard \$1-")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("console:say hi")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("msg:hello")))
        assertTrue(AliasMatcher.isSpecializedAlias(listOf("protector inspect", "sound:UI_BUTTON_CLICK")))
    }

    @Test
    fun `CommandRegistry registers and resolves subcommands and aliases case-insensitively`() {
        val registry = CommandRegistry()
        registry.registerSubcommand(
            name = "Lookup",
            description = "Query audit history",
            permission = "protector.lookup",
            aliases = listOf("l", "search")
        ) { _: CommandSender, _: Array<out String> -> true }

        val byName = registry.getSubcommand("lookup")
        val byAlias = registry.getSubcommand("L")
        assertNotNull(byName)
        assertEquals(byName, byAlias)
        assertEquals(listOf("l", "search"), byName?.aliases)
        assertTrue(byName!!.standaloneByDefault)
        assertEquals(1, registry.allSubcommands().size)
        assertNull(registry.getSubcommand("missing"))
    }
}
