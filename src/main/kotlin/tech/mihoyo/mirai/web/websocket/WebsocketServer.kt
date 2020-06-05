package tech.mihoyo.mirai.web.websocket

import io.ktor.application.Application
import io.ktor.application.install
import io.ktor.routing.Route
import io.ktor.routing.routing
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.connector
import io.ktor.server.engine.embeddedServer
import io.ktor.util.KtorExperimentalAPI
import io.ktor.util.pipeline.ContextDsl
import io.ktor.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import net.mamoe.mirai.console.plugins.PluginBase
import net.mamoe.mirai.event.Listener
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.event.subscribeAlways
import org.slf4j.helpers.NOPLoggerFactory
import tech.mihoyo.mirai.util.logger
import tech.mihoyo.mirai.web.BotSession
import tech.mihoyo.mirai.web.HttpApiService
import kotlin.coroutines.CoroutineContext




class WebSocketServer(
    override val console: PluginBase
) : HttpApiService, CoroutineScope {
    override val coroutineContext: CoroutineContext =
        CoroutineExceptionHandler { _, throwable -> logger.error(throwable) }

    lateinit var server: ApplicationEngine
    private val configByBots: MutableMap<Long, WebSocketServerConfig> = mutableMapOf()
    private var subscription: Listener<BotEvent>? = null

    override fun onLoad() {
    }

    @KtorExperimentalAPI
    override fun onEnable() {
        subscription = console.subscribeAlways {
            if (!configByBots.containsKey(bot.id)) {
                val config = WebSocketServerConfig(console.loadConfig("setting.yml"), bot)
                configByBots[bot.id] = config
                logger.info("Bot:${bot.id} Websocket服务端模块启用状态: ${config.enable}")
                if (config.enable) {
                    launch {
                        try {
                            server = embeddedServer(CIO, environment = applicationEngineEnvironment {
                                this.parentCoroutineContext = coroutineContext
                                this.module(Application::cqWebsocketServer)


                                connector {
                                    this.host = host
                                    this.port = port
                                }
                            })
                        } catch (e: Exception) {
                            logger.error("Bot:${bot.id} Websocket服务端模块启用失败")
                        }
                    }
                }
            }
        }
    }

    override fun onDisable() {
    }


}

fun Application.cqWebsocketServer() {

    install(WebSockets)
    routing {
        webSocket("/event") {

        }
        webSocket("/api") {

        }
        webSocket("/") {

        }
    }
}


