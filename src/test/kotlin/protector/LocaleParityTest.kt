package protector

import org.bukkit.configuration.file.YamlConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class LocaleParityTest {

    private fun collectKeys(yamlPath: Path): Set<String> {
        val cfg = YamlConfiguration.loadConfiguration(yamlPath.toFile())
        return cfg.getKeys(true)
            .filter { key -> !cfg.isConfigurationSection(key) }
            .toSet()
    }

    @Test
    fun `messages_en and messages_ko have identical keys`() {
        val resDir = Path.of("src/main/resources")
        val enKeys = collectKeys(resDir.resolve("messages_en.yml"))
        val koKeys = collectKeys(resDir.resolve("messages_ko.yml"))

        assertTrue(enKeys.isNotEmpty(), "messages_en.yml must not be empty")
        assertEquals(emptySet<String>(), enKeys - koKeys, "Missing keys in messages_ko.yml")
        assertEquals(emptySet<String>(), koKeys - enKeys, "Missing keys in messages_en.yml")
    }

    @Test
    fun `core package does not import any feature modules`() {
        val coreDir = Path.of("src/main/kotlin/protector/core")
        val violations = mutableListOf<String>()
        Files.walk(coreDir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }.forEach { file ->
                val text = Files.readString(file)
                if (text.contains("protector.modules.")) {
                    violations += file.fileName.toString()
                }
            }
        }
        assertEquals(emptyList<String>(), violations, "Core infrastructure files must not depend on feature modules")
    }
}
