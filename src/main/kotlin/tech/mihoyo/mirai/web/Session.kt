package tech.mihoyo.mirai

import kotlinx.coroutines.*
import net.mamoe.mirai.Bot
import net.mamoe.mirai.console.plugins.ConfigSection
import tech.mihoyo.mirai.web.websocket.WebSocketReverseClient
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

internal object SessionManager {

    val allSession: MutableMap<Long, Session> = mutableMapOf()

    operator fun get(botId: Long) = allSession[botId]

    fun containSession(botId: Long): Boolean = allSession.containsKey(botId)

    fun closeSession(botId: Long) = allSession.remove(botId)?.also { it.close() }

    fun closeSession(session: Session) = closeSession(session.botId)

    fun createBotSession(bot: Bot, config: ConfigSection): BotSession =
        BotSession(bot, config, EmptyCoroutineContext).also { session -> allSession[bot.id] = session }
}

/**
 * @author NaturalHG
 * 这个用于管理不同Client与Mirai HTTP的会话
 *
 * [Session]均为内部操作用类
 * 需使用[SessionManager]
 */
abstract class Session internal constructor(
    coroutineContext: CoroutineContext,
    open val botId: Long
) : CoroutineScope {
    val supervisorJob = SupervisorJob(coroutineContext[Job])
    final override val coroutineContext: CoroutineContext = supervisorJob + coroutineContext

    internal open fun close() {
        supervisorJob.complete()
    }
}


class BotSession internal constructor(val bot: Bot, val config: ConfigSection, coroutineContext: CoroutineContext) :
    Session(coroutineContext, bot.id) {
    val cqApiImpl = MiraiApi(bot)
    val websocketClient = WebSocketReverseClient(this)

    override fun close() {
        websocketClient.close()
        super.close()
    }
}
