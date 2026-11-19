package moe.ouom.neriplayer.desktop.backend.api

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.arrayOrNull
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.boolOrNull
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.intOrNull
import moe.ouom.neriplayer.desktop.backend.longOrNull
import moe.ouom.neriplayer.desktop.backend.objectOrNull
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.ListenTogetherControlEvent
import moe.ouom.neriplayer.desktop.core.ListenTogetherJson
import moe.ouom.neriplayer.desktop.core.ListenTogetherManager
import moe.ouom.neriplayer.desktop.core.ListenTogetherRoomSettings
import moe.ouom.neriplayer.desktop.core.ListenTogetherSnapshot
import moe.ouom.neriplayer.desktop.core.ListenTogetherTrack
import kotlinx.serialization.builtins.ListSerializer

/**
 * 一起听（Listen Together）命令。
 *
 * 注册方式（BackendMain.kt，由集成方添加）：
 *   ListenTogetherApi.register(server, container)
 *
 * 状态通过 listenTogether.state 事件推送，命令返回值是同一份合并快照。
 * 协议文档：desktop/docs/protocol-listen-together.md
 */
object ListenTogetherApi {

    fun register(server: CoreServer, container: AppContainer) {
        val manager = ListenTogetherManager(container) { event, data -> server.broadcast(event, data) }
        manager.start()

        fun reply(snapshot: ListenTogetherSnapshot): JsonElement = encodeJson(snapshot)

        server.on("listenTogether.status") { reply(manager.snapshot()) }

        server.on("listenTogether.configure") { params ->
            reply(
                manager.configure(
                    serverUrl = params.stringOrNull("serverUrl"),
                    nickname = params.stringOrNull("nickname"),
                ),
            )
        }

        server.on("listenTogether.createRoom") { params ->
            reply(manager.createRoom(params.stringOrNull("nickname")))
        }

        server.on("listenTogether.joinRoom") { params ->
            val target = params.stringOrNull("inviteOrRoomId")?.trim()
                ?: params.stringOrNull("roomId")?.trim()
                ?: badRequest("缺少 inviteOrRoomId")
            reply(manager.joinRoom(target, params.stringOrNull("nickname")))
        }

        server.on("listenTogether.leaveRoom") { reply(manager.leaveRoom()) }

        server.on("listenTogether.requestControl") { params ->
            val action = params.stringOrNull("action")
                ?: params.stringOrNull("type")
                ?: badRequest("缺少 action")
            val repeatMode = params.intOrNull("repeatMode")
                ?: params.stringOrNull("repeatMode")?.let(::repeatModeValue)
            val shuffleEnabled = params.boolOrNull("shuffleEnabled")
                ?: params.stringOrNull("shuffleEnabled")?.let { it == "true" }
            reply(
                manager.requestControl(
                    action = action,
                    positionMs = params.longOrNull("positionMs"),
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                ),
            )
        }

        server.on("listenTogether.sendControl") { params ->
            reply(manager.sendControlEvent(parseControlEvent(params)))
        }

        server.on("listenTogether.updateSettings") { params ->
            reply(
                manager.updateSettings(
                    allowMemberControl = params.boolOrNull("allowMemberControl"),
                    autoPauseOnMemberChange = params.boolOrNull("autoPauseOnMemberChange"),
                    shareAudioLinks = params.boolOrNull("shareAudioLinks"),
                ),
            )
        }
    }

    /** repeatMode 同时接受 0/1/2 与 OFF/ONE/ALL 字符串。 */
    private fun repeatModeValue(raw: String): Int? = when (raw.trim().uppercase()) {
        "OFF", "0", "NONE" -> 0
        "ONE", "SINGLE", "1" -> 1
        "ALL", "LIST", "2" -> 2
        else -> null
    }

    private fun parseControlEvent(params: JsonObject): ListenTogetherControlEvent {
        val type = params.stringOrNull("type") ?: badRequest("缺少 type")
        val track = params.objectOrNull("track")?.let { element ->
            runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListenTogetherTrack.serializer(), element)
            }.getOrElse { badRequest("track 格式非法: ${it.message}") }
        }
        val queue = params.arrayOrNull("queue")?.let { array ->
            runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListSerializer(ListenTogetherTrack.serializer()), array)
            }.getOrElse { badRequest("queue 格式非法: ${it.message}") }
        }
        val roomSettings = params.objectOrNull("roomSettings")?.let { element ->
            runCatching {
                ListenTogetherJson.decodeFromJsonElement(ListenTogetherRoomSettings.serializer(), element)
            }.getOrElse { badRequest("roomSettings 格式非法: ${it.message}") }
        }
        return ListenTogetherControlEvent(
            type = type,
            eventId = params.stringOrNull("eventId"),
            positionMs = params.longOrNull("positionMs"),
            currentIndex = params.intOrNull("currentIndex"),
            nextIndex = params.intOrNull("nextIndex"),
            track = track,
            queue = queue,
            roomSettings = roomSettings,
            shouldPlay = params.boolOrNull("shouldPlay"),
            state = params.stringOrNull("state"),
            repeatMode = params.intOrNull("repeatMode"),
            shuffleEnabled = params.boolOrNull("shuffleEnabled"),
            requestTrackStableKey = params.stringOrNull("requestTrackStableKey"),
            forceRefresh = params.boolOrNull("forceRefresh"),
            finishedTrackStableKey = params.stringOrNull("finishedTrackStableKey"),
        )
    }
}
