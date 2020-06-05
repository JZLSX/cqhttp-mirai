package tech.mihoyo.mirai.web.websocket

import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.plugins.Config

class WebSocketServerConfig(config: Config, bot: Bot) {
    @Suppress("UNCHECKED_CAST")
    private val  botConfig = config[bot.id.toString()] as? Map<String, Any> ?: emptyMap()

    @Suppress("UNCHECKED_CAST")
    private val serviceConfig = botConfig["ws"] as? Map<String, Any> ?: emptyMap()
    val enable: Boolean by serviceConfig.withDefault { false }
    val postMessageFormat: String by serviceConfig.withDefault { "string" }
    val wsHost: String by serviceConfig.withDefault { "0.0.0.0" }
    val wsPort: String by serviceConfig.withDefault { 6700 }
    val accessToken: String? by serviceConfig.withDefault { null }
}