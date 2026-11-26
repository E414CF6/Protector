package protector

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import protector.core.compat.PlatformCompat
import protector.core.ui.MenuWindow
import java.io.InputStreamReader

class ProtectorCoreTest {

    @Test
    fun `plugin yml declares main class protector Protector, Folia support, single root command, and no claim permissions`() {
        val stream = Protector::class.java.classLoader.getResourceAsStream("plugin.yml")
        requireNotNull(stream) { "plugin.yml must exist on classpath" }
        val yaml = YamlConfiguration.loadConfiguration(InputStreamReader(stream))
        assertEquals("protector.Protector", yaml.getString("main"))
        assertEquals("1.20", yaml.getString("api-version"))
        assertTrue(yaml.getBoolean("folia-supported"), "folia-supported must be true")
        assertTrue(yaml.contains("commands.protector"), "Expected root command protector in plugin.yml")
        val registeredCommands = yaml.getConfigurationSection("commands")?.getKeys(false).orEmpty()
        assertEquals(setOf("protector"), registeredCommands)
        assertFalse(yaml.contains("permissions.protector.claim"), "Claim permission must live in Claims plugin")
        assertTrue(yaml.contains("permissions.protector.guard"), "Expected protector.guard permission")
        assertTrue(yaml.contains("permissions.protector.unlockdrops"), "Expected protector.unlockdrops permission")
    }

    @Test
    fun `compiled classes target Java 17 bytecode and PlatformCompat detects capabilities safely`() {
        val classStream = Protector::class.java.classLoader.getResourceAsStream("protector/Protector.class")
        requireNotNull(classStream)
        val bytes = classStream.readNBytes(8)
        val majorVersion = ((bytes[6].toInt() and 0xFF) shl 8) or (bytes[7].toInt() and 0xFF)
        assertEquals(
            61,
            majorVersion,
            "Protector should compile to Java 17 bytecode (major version 61) for 1.20+ compatibility"
        )
        assertTrue(PlatformCompat.hasClass("protector.core.ui.DialogKit"))
        assertFalse(PlatformCompat.hasClass("protector.modules.claim.ClaimManager"))
        assertTrue(MenuWindow.CONTENT_SLOTS.size == 28)
    }
}
