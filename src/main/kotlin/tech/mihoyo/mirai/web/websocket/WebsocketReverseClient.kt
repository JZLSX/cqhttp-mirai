package tech.mihoyo.mirai.web.websocket

import io.ktor.client.HttpClient
import io.ktor.client.features.websocket.DefaultClientWebSocketSession
import io.ktor.client.features.websocket.ws
import io.ktor.client.request.header

import io.ktor.http.cio.websocket.Frame
import io.ktor.client.features.websocket.WebSockets
import io.ktor.http.cio.websocket.readText
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.consumeEach
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.plugins.description

import net.mamoe.mirai.event.Listener
import net.mamoe.mirai.event.events.BotEvent
import net.mamoe.mirai.event.events.MemberJoinRequestEvent
import net.mamoe.mirai.event.events.NewFriendRequestEvent
import net.mamoe.mirai.event.subscribeAlways
import net.mamoe.mirai.message.TempMessageEvent
import net.mamoe.mirai.utils.currentTimeMillis
import tech.mihoyo.mirai.BotSession
import tech.mihoyo.mirai.PluginBase
import tech.mihoyo.mirai.data.common.*
import tech.mihoyo.mirai.util.logger
import tech.mihoyo.mirai.util.toJson
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException

@KtorExperimentalAPI
class WebSocketReverseClient(
    val session: BotSession
) {
    private val httpClients: MutableMap<String, HttpClient> = mutableMapOf()
    private var serviceConfig: List<WebSocketReverseServiceConfig> = mutableListOf()
    private var subscriptionByHost: MutableMap<String, Listener<BotEvent>?> = mutableMapOf()
    private var websocketSessionByHost: MutableMap<String, DefaultClientWebSocketSession> = mutableMapOf()

    init {
        serviceConfig = session.config.getConfigSectionList("ws_reverse").map { WebSocketReverseServiceConfig(it) }
        serviceConfig.forEach {
            logger.debug("Host: ${it.reverseHost}, Port: ${it.reversePort}, Enable: ${it.enable}")
            if (it.enable) {
                val httpClientKey = "${it.reverseHost}:${it.reversePort}"
                if (!httpClients.containsKey(httpClientKey)) {
                    httpClients[httpClientKey] = HttpClient {
                        install(WebSockets)
                    }
                    GlobalScope.launch {
                        startWebsocketClient(session.bot, it)
                    }
                } else {
                    logger.error("已有相同主机及端口的实例存在. Host: ${it.reverseHost}, Port: ${it.reversePort}")
                }
            }
        }
    }

    @KtorExperimentalAPI
    @ExperimentalCoroutinesApi
    private suspend fun startWebsocketClient(bot: Bot, config: WebSocketReverseServiceConfig) {
        val httpClientKey = "${config.reverseHost}:${config.reversePort}"
        val isRawMessage = config.postMessageFormat != "array"

        try {
            logger.debug("$httpClientKey 开始启动")
            httpClients[httpClientKey]!!.ws(
                host = config.reverseHost,
                port = config.reversePort,
                path = config.reversePath,
                request = {
                    header("User-Agent", "MiraiHttp/${PluginBase.description.version}")
                    header("X-Self-ID", bot.id.toString())
                    header("X-Client-Role", "Universal")
                    config.accessToken?.let {
                        header(
                            "Authorization",
                            "Token ${config.accessToken}"
                        )
                    }
                }
            ) {
                // 用来检测Websocket连接是否关闭
                websocketSessionByHost[httpClientKey] = this
                // 构建事件监听器
                if (!subscriptionByHost.containsKey(httpClientKey)) {
                    // 通知服务方链接建立
                    send(Frame.Text(CQMetaEventDTO(bot.id, "connect", currentTimeMillis).toJson()))
                    subscriptionByHost[httpClientKey] = bot.subscribeAlways {
                        // 保存Event以便在WebsocketSession Block中使用
                        if (this.bot.id == session.botId) {
                            val event = this
                            when (event) {
                                is TempMessageEvent -> session.cqApiImpl.cachedTempContact[event.sender.id] =
                                    event.group.id
                                is NewFriendRequestEvent -> session.cqApiImpl.cacheRequestQueue.add(event)
                                is MemberJoinRequestEvent -> session.cqApiImpl.cacheRequestQueue.add(event)
                            }
                            event.toCQDTO(isRawMessage = isRawMessage).takeIf { it !is CQIgnoreEventDTO }?.apply {
                                send(Frame.Text(this.toJson()))
                            }
                        }
                    }

                    logger.debug("$httpClientKey Websocket Client启动完毕")
                    startWebsocketConnectivityCheck(bot, config)

                    incoming.consumeEach {
                        when (it) {
                            is Frame.Text -> {
                                handleWebSocketActions(outgoing, session.cqApiImpl, it.readText())
                            }
                            else -> logger.warning("Unsupported incomeing frame")
                        }
                    }

                } else {
                    logger.warning("Websocket session alredy exist, $httpClientKey")
                }
            }
        } catch (e: Exception) {
            when (e) {
                is ConnectException -> {
                    logger.warning("Websocket连接出错, 请检查服务器是否开启并确认正确监听端口, 将在${config.reconnectInterval / 1000}秒后重试连接, Host: $httpClientKey")
                    delay(config.reconnectInterval)
                    startWebsocketClient(bot, config)
                }
                is EOFException -> {
                    logger.warning("Websocket连接出错, 服务器返回数据不正确, 请检查Websocket服务器是否配置正确, 将在${config.reconnectInterval / 1000}秒后重试连接, Host: $httpClientKey")
                    delay(config.reconnectInterval)
                    startWebsocketClient(bot, config)
                }
                is IOException -> {
                    logger.warning("Websocket连接出错, 可能被服务器关闭, 将在${config.reconnectInterval / 1000}秒后重试连接, Host: $httpClientKey")
                    delay(config.reconnectInterval)
                    startWebsocketClient(bot, config)
                }
                is CancellationException -> logger.info("Websocket连接关闭中, Host: $httpClientKey")
                else -> logger.warning("Websocket连接出错, 未知错误, 放弃重试连接, 请检查配置正确后重启mirai  " + e.message + e.javaClass.name)
            }
        }
    }

    @KtorExperimentalAPI
    @ExperimentalCoroutinesApi
    private fun startWebsocketConnectivityCheck(bot: Bot, config: WebSocketReverseServiceConfig) {
        GlobalScope.launch {
            val httpClientKey = "${config.reverseHost}:${config.reversePort}"
            if (httpClients.containsKey(httpClientKey)) {
                var stillActive = true
                while (true) {
                    websocketSessionByHost[httpClientKey]?.apply {
                        if (!this.isActive) {
                            stillActive = false
                            this.cancel()
                        }
                    }

                    if (!stillActive) {
                        websocketSessionByHost.remove(httpClientKey)
                        subscriptionByHost[httpClientKey]?.apply {
                            this.complete()
                        }
                        subscriptionByHost.remove(httpClientKey)
                        httpClients[httpClientKey].apply {
                            this!!.close()
                        }
                        logger.warning("Websocket连接已断开, 将在${config.reconnectInterval / 1000}秒后重试连接")
                        delay(config.reconnectInterval)
                        httpClients[httpClientKey] = HttpClient {
                            install(WebSockets)
                        }
                        startWebsocketClient(bot, config)
                        break
                    }
                    delay(5000)
                }
                startWebsocketConnectivityCheck(bot, config)
            }
        }
    }

    fun close() {
        websocketSessionByHost.forEach { it.value.cancel() }
        websocketSessionByHost.clear()
        subscriptionByHost.forEach { it.value?.complete() }
        subscriptionByHost.clear()
        httpClients.forEach { it.value.close() }
        httpClients.clear()
        logger.info("反向Websocket模块已禁用")
    }
}