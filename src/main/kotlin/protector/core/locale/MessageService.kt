package protector.core.locale

import net.kyori.adventure.audience.Audience
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class MessageService(private val plugin: JavaPlugin) {
    private val miniMessage = MiniMessage.miniMessage()
    private var messages: YamlConfiguration = YamlConfiguration()
    private var fallback: YamlConfiguration = YamlConfiguration()

    fun reload(language: String) {
        saveBundled("messages_ko.yml")
        saveBundled("messages_en.yml")

        val langFile = File(plugin.dataFolder, "messages_${language.lowercase()}.yml")
        val fallbackFile = File(plugin.dataFolder, "messages_en.yml")
        fallback = YamlConfiguration.loadConfiguration(fallbackFile)
        messages = if (langFile.exists()) YamlConfiguration.loadConfiguration(langFile) else fallback
    }

    private fun saveBundled(name: String) {
        val outFile = File(plugin.dataFolder, name)
        if (!outFile.exists()) {
            plugin.saveResource(name, false)
        }
    }

    fun raw(key: String): String =
        messages.getString(key) ?: fallback.getString(key) ?: key

    fun render(key: String, placeholders: Map<String, Any> = emptyMap()): Component {
        var template = raw(key)
        for ((k, v) in placeholders) {
            template = template.replace("{$k}", miniMessage.escapeTags(v.toString()))
        }
        return miniMessage.deserialize(template)
    }

    fun renderRaw(miniMessageText: String): Component = miniMessage.deserialize(miniMessageText)

    fun prefix(): Component = render("prefix")

    fun send(audience: Audience, key: String, placeholders: Map<String, Any> = emptyMap()) {
        audience.sendMessage(prefix().append(render(key, placeholders)))
    }

    fun sendRaw(audience: Audience, miniMessageText: String, withPrefix: Boolean = true) {
        val comp = miniMessage.deserialize(miniMessageText)
        audience.sendMessage(if (withPrefix) prefix().append(comp) else comp)
    }
}
