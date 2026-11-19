package moe.ouom.neriplayer.desktop.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 一起听（Listen Together，下文简称 LTW）线协议模型。
 *
 * 服务端协议见 np-submodule/NeriPlayer-LTW/src/worker.js，字段名以 Android 客户端
 * modules/model/src/main/java/moe/ouom/neriplayer/data/model/ltw 下的序列化模型为准：
 * 两边都按 camelCase 解析并忽略未知字段，因此这里的默认值与 Android 保持一致即可
 * 互相兼容（例如缺省的 repeatMode/shuffleEnabled 视为旧客户端）。
 */
val ListenTogetherJson: Json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
    explicitNulls = false
    isLenient = true
}

/** Android 端 ListenTogetherBaseUrl.kt 的默认服务端；桌面版未配置时仅作为提示展示。 */
const val LISTEN_TOGETHER_DEFAULT_SERVER_URL = "https://neriplayer.hancat.work"

/** worker.js 的 QUEUE_MUTATION_SCHEMA_VERSION；桌面版只使用旧版整队列快照路径。 */
const val LISTEN_TOGETHER_SCHEMA_VERSION = 2

const val LISTEN_TOGETHER_MAX_QUEUE_SIZE = 2000

const val LISTEN_TOGETHER_CHANNEL_NETEASE = "netease"
const val LISTEN_TOGETHER_CHANNEL_BILIBILI = "bilibili"
const val LISTEN_TOGETHER_CHANNEL_YOUTUBE_MUSIC = "youtubeMusic"
const val LISTEN_TOGETHER_CHANNEL_LOCAL = "local"

@Serializable
data class ListenTogetherTrack(
    val stableKey: String = "",
    val channelId: String = "",
    val audioId: String = "",
    val subAudioId: String? = null,
    val playlistContextId: String? = null,
    val mediaUri: String? = null,
    /** streamUrl 是旧客户端的第一个候选地址，桌面版默认不共享直链。 */
    val streamUrl: String? = null,
    val streamUrls: List<String> = emptyList(),
    val name: String = "",
    val artist: String = "",
    val album: String? = null,
    val durationMs: Long = 0L,
    val coverUrl: String? = null,
)

@Serializable
data class ListenTogetherRoomSettings(
    val allowMemberControl: Boolean = true,
    val autoPauseOnMemberChange: Boolean = true,
    val shareAudioLinks: Boolean = true,
)

@Serializable
data class ListenTogetherMember(
    val userUuid: String = "",
    val nickname: String = "",
    val userId: String? = null,
    val role: String = "listener",
    val joinedAt: Long = 0L,
)

@Serializable
data class ListenTogetherPlaybackState(
    val state: String = "paused",
    val basePositionMs: Long = 0L,
    val baseTimestampMs: Long = 0L,
    val playbackRate: Double = 1.0,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
)

@Serializable
data class ListenTogetherRoomState(
    val roomId: String = "",
    val version: Long = 0L,
    val schemaVersion: Int = 1,
    val controllerUserUuid: String? = null,
    val controllerUserId: String? = null,
    val controllerHeartbeatAt: Long? = null,
    val settings: ListenTogetherRoomSettings = ListenTogetherRoomSettings(),
    val members: List<ListenTogetherMember> = emptyList(),
    val queue: List<ListenTogetherTrack> = emptyList(),
    val currentIndex: Int = 0,
    val track: ListenTogetherTrack? = null,
    val playback: ListenTogetherPlaybackState = ListenTogetherPlaybackState(),
    val controllerOfflineSince: Long? = null,
    val roomStatus: String = "active",
    val closedReason: String? = null,
    val updatedAt: Long = 0L,
)

@Serializable
data class ListenTogetherInitialSnapshot(
    val queue: List<ListenTogetherTrack> = emptyList(),
    val currentIndex: Int = 0,
    val track: ListenTogetherTrack? = null,
    val settings: ListenTogetherRoomSettings = ListenTogetherRoomSettings(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val shuffleRestoreQueue: List<ListenTogetherTrack>? = null,
)

@Serializable
data class ListenTogetherCreateRoomRequest(
    val userUuid: String,
    val nickname: String,
    val initialSnapshot: ListenTogetherInitialSnapshot,
)

@Serializable
data class ListenTogetherJoinRoomRequest(
    val userUuid: String,
    val nickname: String,
    val memberSecret: String? = null,
    val joinSecret: String? = null,
)

@Serializable
data class ListenTogetherCause(
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val eventId: String? = null,
    val type: String? = null,
)

@Serializable
data class ListenTogetherAppliedEvent(
    val type: String = "",
    val roomId: String? = null,
    val version: Long? = null,
    val state: ListenTogetherRoomState? = null,
    val expectedPositionMs: Long? = null,
    val nowMs: Long? = null,
    val causedBy: ListenTogetherCause? = null,
)

@Serializable
data class ListenTogetherRoomResponse(
    val ok: Boolean = false,
    val roomId: String? = null,
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val memberSecret: String? = null,
    val joinSecret: String? = null,
    val autoPauseOnJoin: Boolean = false,
    val token: String? = null,
    val state: ListenTogetherRoomState? = null,
    val wsUrl: String? = null,
    val error: String? = null,
)

@Serializable
data class ListenTogetherStateResponse(
    val ok: Boolean = false,
    val state: ListenTogetherRoomState? = null,
    val expectedPositionMs: Long? = null,
    val serverNowMs: Long? = null,
    val autoPauseOnJoin: Boolean = false,
    val error: String? = null,
)

@Serializable
data class ListenTogetherControlResponse(
    val ok: Boolean = false,
    val applied: ListenTogetherAppliedEvent? = null,
    val error: String? = null,
)

@Serializable
data class ListenTogetherLeaveRoomResponse(
    val ok: Boolean = false,
    val applied: ListenTogetherAppliedEvent? = null,
    val error: String? = null,
)

/** WebSocket 消息：服务端下发的 welcome / room_state_updated / control_result 等共用。 */
@Serializable
data class ListenTogetherSocketEnvelope(
    val type: String = "",
    val sessionId: String? = null,
    val userUuid: String? = null,
    val userId: String? = null,
    val nickname: String? = null,
    val role: String? = null,
    val autoPauseOnJoin: Boolean = false,
    val state: ListenTogetherRoomState? = null,
    val expectedPositionMs: Long? = null,
    val nowMs: Long? = null,
    val t: Long? = null,
    val ok: Boolean? = null,
    val result: ListenTogetherControlResponse? = null,
    val message: String? = null,
    val roomId: String? = null,
    val version: Long? = null,
    val causedBy: ListenTogetherCause? = null,
    val track: ListenTogetherTrack? = null,
    val queue: List<ListenTogetherTrack>? = null,
    val positionMs: Long? = null,
    val currentIndex: Int? = null,
    val requestTrackStableKey: String? = null,
    val shouldPlay: Boolean? = null,
    val stateName: String? = null,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
)

/** 客户端上行的控制事件（Android ListenTogetherEvent 的子集）。 */
@Serializable
data class ListenTogetherControlEvent(
    val type: String,
    val eventId: String? = null,
    val clientTimeMs: Long? = null,
    val clientInstanceId: String? = null,
    val clientSequence: Long? = null,
    val positionMs: Long? = null,
    val currentIndex: Int? = null,
    val nextIndex: Int? = null,
    val track: ListenTogetherTrack? = null,
    val queue: List<ListenTogetherTrack>? = null,
    val roomSettings: ListenTogetherRoomSettings? = null,
    val shouldPlay: Boolean? = null,
    val state: String? = null,
    val repeatMode: Int? = null,
    val shuffleEnabled: Boolean? = null,
    val requestTrackStableKey: String? = null,
    val forceRefresh: Boolean? = null,
    val finishedTrackStableKey: String? = null,
)

/** 持久化配置与会话（AppDirs.dataDir/listen_together.json）。 */
@Serializable
data class ListenTogetherConfig(
    val schemaVersion: Int = 1,
    val serverUrl: String = "",
    val nickname: String = "",
    val userUuid: String = "",
    val clientInstanceId: String = "",
    val session: ListenTogetherSavedSession? = null,
)

@Serializable
data class ListenTogetherSavedSession(
    val baseUrl: String = "",
    val roomId: String = "",
    val userUuid: String = "",
    val nickname: String = "",
    val role: String = "",
    val token: String? = null,
    val memberSecret: String? = null,
    val joinSecret: String? = null,
    val wsUrl: String? = null,
)

@Serializable
data class ListenTogetherPlaybackSnapshot(
    val state: String = "paused",
    /** 生成快照时按服务端时钟推算出的当前位置。 */
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val serverNowMs: Long = 0L,
    val basePositionMs: Long = 0L,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
)

/**
 * 给 Qt 前端的合并状态：配置 + 连接 + 房间 + 自身角色。
 * 命令结果与 listenTogether.state 事件都使用同一结构。
 */
@Serializable
data class ListenTogetherSnapshot(
    val configured: Boolean = false,
    val serverUrl: String = "",
    val defaultServerUrl: String = LISTEN_TOGETHER_DEFAULT_SERVER_URL,
    val nickname: String = "",
    val userUuid: String = "",
    /** disconnected / connecting / connected / reconnecting / error */
    val connection: String = "disconnected",
    val connected: Boolean = false,
    val wsConnected: Boolean = false,
    val roomId: String? = null,
    val role: String? = null,
    val isController: Boolean = false,
    val selfUserUuid: String? = null,
    val inviteLink: String? = null,
    val members: List<ListenTogetherMember> = emptyList(),
    val controllerUserUuid: String? = null,
    val queue: List<ListenTogetherTrack> = emptyList(),
    val currentIndex: Int = 0,
    val track: ListenTogetherTrack? = null,
    val playback: ListenTogetherPlaybackSnapshot = ListenTogetherPlaybackSnapshot(),
    val settings: ListenTogetherRoomSettings? = null,
    val roomStatus: String? = null,
    val version: Long = 0L,
    val autoPauseOnJoin: Boolean = false,
    /** 最近一次错误；面板显示为红色提示，成功后清空。 */
    val error: String? = null,
    /** 非错误类提示（例如控制者离线、请求地址不受支持）。 */
    val notice: String? = null,
)
