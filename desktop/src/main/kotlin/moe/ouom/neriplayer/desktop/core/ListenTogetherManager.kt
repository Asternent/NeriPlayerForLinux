package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import moe.ouom.neriplayer.desktop.net.ListenTogetherHttpClient
import moe.ouom.neriplayer.desktop.net.ListenTogetherWebSocketClient
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random
import kotlin.math.abs

/**
 * 一起听会话管理器。
 *
 * 职责：
 *  - 维护配置与会话（AppDirs.dataDir/listen_together.json），按需创建/加入/离开房间；
 *  - 用 HTTP 完成 create/join/state/leave/control，用 java.net.http.WebSocket 接收
 *    welcome / room_state_updated / control_result 等消息；
 *  - 作为控制者时把本地播放变化上报为控制事件（SET_TRACK/SET_QUEUE/PLAY/PAUSE/SEEK/
 *    PLAYBACK_MODE/HEARTBEAT），并定期发 HEARTBEAT 与 np_ping；
 *  - 收到权威房态时把播放/暂停/seek/切歌/队列/循环/随机应用到 container.player，
 *    应用期间加抑制标记，避免本地变化再次上报造成回环。
 *
 * 协议对照与不支持项见 desktop/docs/protocol-listen-together.md。
 */
class ListenTogetherManager(
    private val container: AppContainer,
    configFile: File = File(AppDirs.dataDir, "listen_together.json"),
    private val broadcast: (String, JsonElement) -> Unit,
) {
    private companion object {
        const val EVENT_STATE = "listenTogether.state"
        const val TICK_INTERVAL_MS = 400L
        const val HEARTBEAT_PLAYING_MS = 8_000L
        const val HEARTBEAT_PAUSED_MS = 15_000L
        const val PING_INTERVAL_MS = 12_000L
        const val STATE_REFRESH_SILENCE_MS = 35_000L
        const val LISTENER_DRIFT_TOLERANCE_MS = 2_500L
        const val LOCAL_SEEK_TOLERANCE_PLAYING_MS = 2_500L
        const val LOCAL_SEEK_TOLERANCE_PAUSED_MS = 900L
        const val CONTROL_RESULT_TIMEOUT_MS = 4_000L
        const val REMOTE_APPLY_SUPPRESS_MS = 2_500L
        const val MAX_RECONNECT_DELAY_MS = 30_000L
        const val MAX_QUEUE_SIZE = LISTEN_TOGETHER_MAX_QUEUE_SIZE
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = JsonFileStore(configFile, ListenTogetherConfig.serializer()) { ListenTogetherConfig() }
    private val http = ListenTogetherHttpClient(container.online.httpService)

    private val lock = Any()
    private val clientSequence = AtomicLong(0)
    private val pendingResults = ConcurrentHashMap<String, CompletableDeferred<ControlOutcome>>()
    private val applyMutex = Mutex()

    private var config: ListenTogetherConfig = ListenTogetherConfig()
    private var room: ListenTogetherRoomState? = null
    private var connection: String = "disconnected"
    private var lastError: String? = null
    private var notice: String? = null
    private var token: String? = null
    private var joinSecret: String? = null
    private var sessionBaseUrl: String? = null
    private var sessionUserUuid: String? = null
    private var sessionNickname: String? = null
    private var sessionRole: String? = null
    private var wsUrl: String? = null
    private var wsOpen = false
    private var expectedPositionMs = 0L
    private var expectedAtLocalMs = 0L
    private var serverClockOffsetMs = 0L
    private var autoPauseOnJoin = false
    private var lastOutboundAtMs = 0L
    private var lastInboundAtMs = 0L
    private var lastPingAtMs = 0L
    private var lastStateRefreshAtMs = 0L
    private var reconnectAttempts = 0
    private var nextReconnectAtMs = 0L
    private var reconnectInFlight = false
    private var started = false
    private var leaving = false
    private var applyingRemote = false
    private var suppressLocalUntilMs = 0L
    private var lastListenerApplyAtMs = 0L
    private var unsupportedLocalSongNotified = false
    private var wsClient: ListenTogetherWebSocketClient? = null
    private var reported: LocalReport? = null
    private var tickJob: Job? = null
    private var reconnectJob: Job? = null

    // ---------------------------------------------------------------- 生命周期

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
            config = normalizedConfig(store.load())
            store.save(config)
        }
        tickJob = scope.launch { tickLoop() }
        scope.launch { restoreSavedSession() }
        notifyState()
    }

    private fun normalizedConfig(loaded: ListenTogetherConfig): ListenTogetherConfig = loaded.copy(
        userUuid = loaded.userUuid.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
        clientInstanceId = loaded.clientInstanceId
            .takeIf { it.isNotBlank() && it.matches(Regex("^[A-Za-z0-9:_-]{1,80}$")) }
            ?: ("desktop-" + UUID.randomUUID().toString().replace("-", "").take(12)),
        serverUrl = normalizeListenTogetherBaseUrlOrNull(loaded.serverUrl).orEmpty(),
    )

    private fun persistConfig() {
        store.save(config)
    }

    // ---------------------------------------------------------------- 对外命令

    fun snapshot(): ListenTogetherSnapshot {
        val values = snapshotValues()
        return values
    }

    private fun snapshotValues(): ListenTogetherSnapshot {
        val bundle = synchronized(lock) {
            SnapshotBundle(
                config = config,
                room = room,
                role = sessionRole,
                connection = connection,
                wsOpen = wsOpen,
                joinSecret = joinSecret,
                autoPauseOnJoin = autoPauseOnJoin,
                lastError = lastError,
                notice = notice,
            )
        }
        val now = System.currentTimeMillis()
        val roomState = bundle.room
        val isController = isControllerRole(bundle.role)
        val playback = playbackSnapshot(roomState, now)
        return ListenTogetherSnapshot(
            configured = bundle.config.serverUrl.isNotBlank(),
            serverUrl = bundle.config.serverUrl,
            nickname = bundle.config.nickname,
            userUuid = bundle.config.userUuid,
            connection = bundle.connection,
            connected = bundle.connection == "connected",
            wsConnected = bundle.wsOpen,
            roomId = roomState?.roomId,
            role = bundle.role,
            isController = isController,
            selfUserUuid = bundle.config.userUuid,
            inviteLink = if (isController && roomState != null && !bundle.joinSecret.isNullOrBlank()) {
                buildListenTogetherInvite(
                    roomId = roomState.roomId,
                    nickname = bundle.config.nickname.ifBlank { null },
                    baseUrl = bundle.config.serverUrl,
                    joinSecret = bundle.joinSecret!!,
                )
            } else {
                null
            },
            members = roomState?.members.orEmpty(),
            controllerUserUuid = roomState?.controllerUserUuid,
            queue = roomState?.queue.orEmpty(),
            currentIndex = roomState?.currentIndex ?: 0,
            track = roomState?.track ?: roomState?.queue?.getOrNull(roomState.currentIndex),
            playback = playback,
            settings = roomState?.settings,
            roomStatus = roomState?.roomStatus,
            version = roomState?.version ?: 0L,
            autoPauseOnJoin = bundle.autoPauseOnJoin,
            error = bundle.lastError,
            notice = bundle.notice,
        )
    }

    private data class SnapshotBundle(
        val config: ListenTogetherConfig,
        val room: ListenTogetherRoomState?,
        val role: String?,
        val connection: String,
        val wsOpen: Boolean,
        val joinSecret: String?,
        val autoPauseOnJoin: Boolean,
        val lastError: String?,
        val notice: String?,
    )

    suspend fun configure(serverUrl: String?, nickname: String?): ListenTogetherSnapshot {
        val normalizedNickname = nickname?.trim()
        if (normalizedNickname != null && normalizedNickname.isNotEmpty()) {
            val nicknameError = validateListenTogetherNickname(normalizedNickname)
            if (nicknameError != null) {
                return fail(nicknameError)
            }
        }
        val normalizedUrl = if (serverUrl == null) {
            config.serverUrl
        } else if (serverUrl.isBlank()) {
            ""
        } else {
            normalizeListenTogetherBaseUrlOrNull(serverUrl)
                ?: return fail("服务端地址需为 http(s) URL，例如 $LISTEN_TOGETHER_DEFAULT_SERVER_URL")
        }
        val currentBase = synchronized(lock) { sessionBaseUrl }
        if (room != null && !normalizedUrl.equals(currentBase, ignoreCase = true)) {
            return fail("请先离开房间再修改服务端地址")
        }
        synchronized(lock) {
            config = config.copy(
                serverUrl = normalizedUrl,
                nickname = normalizedNickname ?: config.nickname,
            )
            lastError = null
            notice = if (normalizedUrl.isBlank()) "已清空服务端地址" else null
            persistConfig()
        }
        if (normalizedUrl.isNotBlank() && room == null) {
            val reachable = withContext(Dispatchers.IO) { runCatching { http.healthz(normalizedUrl) }.getOrDefault(false) }
            if (!reachable) {
                synchronized(lock) { notice = "服务端地址已保存，但可用性测试未通过（可能是自建实例未实现 /healthz）" }
            }
        }
        notifyState()
        return snapshot()
    }

    suspend fun createRoom(nickname: String?): ListenTogetherSnapshot {
        if (room != null) return fail("已在房间中，请先离开")
        val baseUrl = config.serverUrl
        if (baseUrl.isBlank()) return fail("请先配置一起听服务端地址")
        val resolvedNickname = resolveNickname(nickname)
        val snapshotPair = shareableQueueSnapshot()
        if (snapshotPair == null) return fail("请先播放一首在线歌曲：本地歌曲不能进入一起听房间")
        val (tracks, index) = snapshotPair
        val track = tracks.getOrNull(index) ?: return fail("当前歌曲无法分享")
        val player = container.player
        val bounded = boundQueueAround(tracks, index)
        val request = ListenTogetherCreateRoomRequest(
            userUuid = config.userUuid,
            nickname = resolvedNickname,
            initialSnapshot = ListenTogetherInitialSnapshot(
                queue = bounded.first,
                currentIndex = bounded.second,
                track = bounded.first.getOrNull(bounded.second) ?: track,
                settings = ListenTogetherRoomSettings(
                    allowMemberControl = true,
                    autoPauseOnMemberChange = true,
                    // 桌面版不实现 LINK_READY，默认不共享音频直链，避免听众等待无响应的候选地址
                    shareAudioLinks = false,
                ),
                isPlaying = player.state.value == PlaybackState.PLAYING,
                positionMs = player.positionMs.value.coerceAtLeast(0L),
                repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = player.shuffle.value,
                shuffleRestoreQueue = null,
            ),
        )
        val result = withContext(Dispatchers.IO) { http.createRoom(baseUrl, request) }
        val response = result.value
        if (result.error != null || response == null || !response.ok) {
            return fail(describeListenTogetherError(result.error ?: response?.error))
        }
        synchronized(lock) {
            config = config.copy(nickname = response.nickname ?: resolvedNickname)
            persistConfig()
        }
        adoptSession(baseUrl, response)
        connectWebSocket()
        notifyState()
        return snapshot()
    }

    suspend fun joinRoom(inviteOrRoomId: String, nickname: String?): ListenTogetherSnapshot {
        if (room != null) return fail("已在房间中，请先离开")
        val configuredBase = config.serverUrl
        val invite = parseListenTogetherInvite(inviteOrRoomId)
        val roomId: String
        val baseUrl: String
        var joinSecretForJoin: String?
        var memberSecretForJoin: String? = null
        if (invite != null) {
            roomId = invite.roomId
            baseUrl = invite.baseUrl ?: configuredBase
            joinSecretForJoin = invite.joinSecret
            if (baseUrl.isBlank()) return fail("邀请链接中的服务端地址无效，且本机未配置服务端地址")
            val saved = config.session
            if (saved != null && saved.roomId.equals(roomId, ignoreCase = true) &&
                saved.userUuid == config.userUuid
            ) {
                memberSecretForJoin = saved.memberSecret
            }
        } else {
            roomId = normalizeListenTogetherRoomId(inviteOrRoomId)
            validateListenTogetherRoomId(roomId)?.let { return fail(it) }
            baseUrl = configuredBase
            if (baseUrl.isBlank()) return fail("请先配置一起听服务端地址")
            val saved = config.session
            if (saved != null && saved.roomId.equals(roomId, ignoreCase = true)) {
                joinSecretForJoin = saved.joinSecret
                if (saved.userUuid == config.userUuid) memberSecretForJoin = saved.memberSecret
            } else {
                joinSecretForJoin = null
            }
            if (joinSecretForJoin.isNullOrBlank() && memberSecretForJoin.isNullOrBlank()) {
                return fail("首次加入需要邀请链接（链接里带有加入密钥）；只填房间号适用于重新连接已在的房间")
            }
        }
        val resolvedNickname = resolveNickname(nickname)
        val request = ListenTogetherJoinRoomRequest(
            userUuid = config.userUuid,
            nickname = resolvedNickname,
            memberSecret = memberSecretForJoin,
            joinSecret = joinSecretForJoin,
        )
        val result = withContext(Dispatchers.IO) { http.joinRoom(baseUrl, roomId, request) }
        val response = result.value
        if (result.error != null || response == null || !response.ok) {
            return fail(describeListenTogetherError(result.error ?: response?.error))
        }
        synchronized(lock) {
            config = config.copy(
                nickname = response.nickname ?: resolvedNickname,
                serverUrl = config.serverUrl.ifBlank { baseUrl },
            )
            persistConfig()
        }
        adoptSession(baseUrl, response)
        val state = response.state
        if (state != null) {
            applyAuthoritativeState(
                state = state,
                expectedPosition = responseExpectedPosition(state),
                causedBy = null,
                applyToPlayer = !isControllerRole(response.role),
            )
        }
        connectWebSocket()
        notifyState()
        return snapshot()
    }

    suspend fun leaveRoom(): ListenTogetherSnapshot {
        val (base, roomId, currentToken) = synchronized(lock) {
            Triple(sessionBaseUrl, room?.roomId, token)
        }
        leaving = true
        try {
            if (base != null && roomId != null && !currentToken.isNullOrBlank()) {
                withContext(Dispatchers.IO) {
                    runCatching { http.leaveRoom(base, roomId, currentToken) }
                }
            }
            reconnectJob?.cancel()
            reconnectJob = null
            closeSocket()
            clearSession(clearPersistedSession = true)
            synchronized(lock) {
                connection = "disconnected"
                lastError = null
                notice = "已离开房间"
            }
        } finally {
            leaving = false
        }
        notifyState()
        return snapshot()
    }

    suspend fun requestControl(
        action: String,
        positionMs: Long?,
        repeatMode: Int?,
        shuffleEnabled: Boolean?,
    ): ListenTogetherSnapshot {
        val state = room ?: return fail("不在房间中")
        if (isControllerRole(sessionRole)) return fail("你已经是控制者")
        val queue = state.queue
        val index = state.currentIndex.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        val track = state.track ?: queue.getOrNull(index)
        val stableKey = track?.stableKey
        val roomPlaying = state.playback.state == "playing"
        val targetPosition = positionMs?.coerceAtLeast(0L) ?: computeExpectedPosition(System.currentTimeMillis())
        val event = when (action) {
            "play" -> ListenTogetherControlEvent(
                type = "REQUEST_PLAY",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                shouldPlay = true,
                state = "playing",
                requestTrackStableKey = stableKey,
            )

            "pause" -> ListenTogetherControlEvent(
                type = "REQUEST_PAUSE",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                shouldPlay = false,
                state = "paused",
                requestTrackStableKey = stableKey,
            )

            "seek" -> ListenTogetherControlEvent(
                type = "REQUEST_SEEK",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                state = if (roomPlaying) "playing" else "paused",
                requestTrackStableKey = stableKey,
            )

            "playbackMode" -> ListenTogetherControlEvent(
                type = "REQUEST_PLAYBACK_MODE",
                positionMs = targetPosition,
                currentIndex = index,
                track = track,
                repeatMode = repeatMode,
                shuffleEnabled = shuffleEnabled,
                state = if (roomPlaying) "playing" else "paused",
                requestTrackStableKey = stableKey,
            )

            "next", "previous" -> {
                if (queue.isEmpty()) return fail("房间队列为空")
                val nextIndex = if (action == "next") {
                    if (index < queue.lastIndex) index + 1 else if (repeatAll(state)) 0 else queue.lastIndex
                } else {
                    if (index > 0) index - 1 else queue.lastIndex
                }
                val nextTrack = queue[nextIndex]
                ListenTogetherControlEvent(
                    type = "REQUEST_SET_TRACK",
                    positionMs = 0L,
                    currentIndex = nextIndex,
                    track = nextTrack,
                    queue = queue,
                    shouldPlay = roomPlaying,
                    state = if (roomPlaying) "playing" else "paused",
                    repeatMode = state.playback.repeatMode,
                    shuffleEnabled = state.playback.shuffleEnabled == true,
                    requestTrackStableKey = nextTrack.stableKey,
                )
            }

            else -> return fail("不支持的控制请求：$action")
        }
        val outcome = sendControlEvent(event, awaitResult = true)
        synchronized(lock) {
            if (outcome?.ok == true) {
                notice = "控制请求已提交"
                lastError = null
            } else {
                lastError = describeListenTogetherError(outcome?.error)
            }
        }
        notifyState()
        return snapshot()
    }

    suspend fun updateSettings(
        allowMemberControl: Boolean?,
        autoPauseOnMemberChange: Boolean?,
        shareAudioLinks: Boolean?,
    ): ListenTogetherSnapshot {
        val state = room ?: return fail("不在房间中")
        if (!isControllerRole(sessionRole)) return fail("只有控制者可以修改房间设置")
        val current = state.settings
        val next = current.copy(
            allowMemberControl = allowMemberControl ?: current.allowMemberControl,
            autoPauseOnMemberChange = autoPauseOnMemberChange ?: current.autoPauseOnMemberChange,
            shareAudioLinks = shareAudioLinks ?: current.shareAudioLinks,
        )
        val outcome = sendControlEvent(
            ListenTogetherControlEvent(type = "UPDATE_SETTINGS", roomSettings = next),
            awaitResult = true,
        )
        synchronized(lock) {
            if (outcome?.ok != true) lastError = describeListenTogetherError(outcome?.error)
        }
        notifyState()
        return snapshot()
    }

    suspend fun sendControlEvent(event: ListenTogetherControlEvent): ListenTogetherSnapshot {
        if (room == null) return fail("不在房间中")
        val enriched = enrichControlEvent(event)
        val outcome = sendControlEvent(enriched, awaitResult = true)
        synchronized(lock) {
            if (outcome?.ok == true) {
                lastError = null
                notice = "控制指令已提交"
            } else {
                lastError = describeListenTogetherError(outcome?.error)
            }
        }
        notifyState()
        return snapshot()
    }

    // ---------------------------------------------------------------- 会话与连接

    private fun adoptSession(baseUrl: String, response: ListenTogetherRoomResponse) {
        val state = response.state
        val now = System.currentTimeMillis()
        synchronized(lock) {
            sessionBaseUrl = baseUrl
            sessionUserUuid = response.userUuid ?: config.userUuid
            sessionNickname = response.nickname ?: config.nickname
            sessionRole = response.role ?: resolveRoleFromState(state, config.userUuid)
            token = response.token ?: token
            joinSecret = response.joinSecret ?: joinSecret
            wsUrl = response.wsUrl ?: buildWsUrl(baseUrl, response.roomId, response.token)
            autoPauseOnJoin = response.autoPauseOnJoin
            if (state != null) {
                room = mergeRoomState(room, state)
                expectedPositionMs = responseExpectedPosition(state)
                expectedAtLocalMs = now
            }
            connection = "connecting"
            lastError = null
            notice = null
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            config = config.copy(
                serverUrl = config.serverUrl.ifBlank { baseUrl },
                nickname = response.nickname ?: config.nickname,
                session = ListenTogetherSavedSession(
                    baseUrl = baseUrl,
                    roomId = response.roomId ?: state?.roomId.orEmpty(),
                    userUuid = sessionUserUuid.orEmpty(),
                    nickname = sessionNickname.orEmpty(),
                    role = sessionRole.orEmpty(),
                    token = response.token,
                    memberSecret = response.memberSecret,
                    joinSecret = response.joinSecret,
                    wsUrl = wsUrl,
                ),
            )
            persistConfig()
        }
        if (isControllerRole(response.role)) {
            reported = buildLocalReport(System.currentTimeMillis())
        } else {
            reported = null
        }
    }

    private fun connectWebSocket() {
        val url = synchronized(lock) { wsUrl ?: buildWsUrl(sessionBaseUrl, room?.roomId, token) }
        if (url.isNullOrBlank()) {
            synchronized(lock) {
                connection = "error"
                lastError = "缺少 WebSocket 地址"
            }
            notifyState()
            return
        }
        val client = ListenTogetherWebSocketClient(
            onOpen = {
                scope.launch {
                    synchronized(lock) { wsOpen = true }
                    notifyState()
                }
            },
            onText = { text -> scope.launch { handleSocketText(text) } },
            onClosed = { scope.launch { handleSocketLost("连接已断开") } },
            onFailure = { message -> scope.launch { handleSocketLost(message) } },
        )
        val previous = synchronized(lock) {
            val old = wsClient
            wsClient = client
            old
        }
        runCatching { previous?.close() }
        synchronized(lock) { connection = "connecting" }
        notifyState()
        client.connect(url)
    }

    private fun closeSocket() {
        val client = synchronized(lock) {
            wsOpen = false
            val current = wsClient
            wsClient = null
            current
        }
        runCatching { client?.close() }
        pendingResults.values.forEach { it.complete(ControlOutcome(false, "连接已关闭", null)) }
        pendingResults.clear()
    }

    private suspend fun handleSocketLost(message: String) {
        val retry = synchronized(lock) {
            wsOpen = false
            pendingResults.values.forEach { it.complete(ControlOutcome(false, message, null)) }
            pendingResults.clear()
            if (leaving || config.session == null) {
                connection = "disconnected"
                false
            } else {
                connection = "reconnecting"
                if (lastError.isNullOrBlank()) lastError = message
                reconnectAttempts += 1
                val delayMs = (1_000L shl (reconnectAttempts - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                nextReconnectAtMs = System.currentTimeMillis() + delayMs
                true
            }
        }
        notifyState()
        if (retry) scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (reconnectInFlight) return
        reconnectInFlight = true
        reconnectJob = scope.launch {
            try {
                val saved = synchronized(lock) { config.session } ?: return@launch
                connection = "connecting"
                notifyState()
                rejoinSession(saved)
            } finally {
                reconnectInFlight = false
            }
        }
    }

    private suspend fun rejoinSession(saved: ListenTogetherSavedSession) {
        if (leaving) return
        val baseUrl = normalizeListenTogetherBaseUrlOrNull(saved.baseUrl) ?: run {
            clearSession(clearPersistedSession = true)
            return
        }
        val request = ListenTogetherJoinRoomRequest(
            userUuid = saved.userUuid.ifBlank { config.userUuid },
            nickname = saved.nickname.ifBlank { resolveNickname(null) },
            memberSecret = saved.memberSecret,
            joinSecret = saved.joinSecret,
        )
        val result = withContext(Dispatchers.IO) { http.joinRoom(baseUrl, saved.roomId, request) }
        val response = result.value
        if (leaving || synchronized(lock) { config.session == null }) return
        if (result.error != null || response == null || !response.ok) {
            val rawError = result.error ?: response?.error
            synchronized(lock) {
                reconnectAttempts += 1
                nextReconnectAtMs = System.currentTimeMillis() +
                    (1_000L shl (reconnectAttempts - 1).coerceIn(0, 5)).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                lastError = describeListenTogetherError(rawError)
                connection = if (isSessionGone(rawError)) "disconnected" else "reconnecting"
            }
            if (isSessionGone(rawError)) clearSession(clearPersistedSession = true)
            notifyState()
            return
        }
        synchronized(lock) {
            sessionBaseUrl = baseUrl
            sessionUserUuid = response.userUuid ?: saved.userUuid
            sessionNickname = response.nickname ?: saved.nickname
            sessionRole = response.role ?: saved.role.ifBlank { "listener" }
            token = response.token ?: token
            joinSecret = response.joinSecret ?: joinSecret
            wsUrl = response.wsUrl ?: buildWsUrl(baseUrl, response.roomId, response.token)
            autoPauseOnJoin = response.autoPauseOnJoin
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            lastError = null
            notice = "已重新连接房间"
            config = config.copy(
                session = saved.copy(
                    baseUrl = baseUrl,
                    roomId = response.roomId ?: saved.roomId,
                    userUuid = sessionUserUuid.orEmpty(),
                    nickname = sessionNickname.orEmpty(),
                    role = sessionRole.orEmpty(),
                    token = response.token,
                    memberSecret = response.memberSecret ?: saved.memberSecret,
                    joinSecret = response.joinSecret ?: saved.joinSecret,
                    wsUrl = wsUrl,
                ),
            )
            persistConfig()
        }
        response.state?.let { state ->
            applyAuthoritativeState(
                state = state,
                expectedPosition = responseExpectedPosition(state),
                causedBy = null,
                applyToPlayer = !isControllerRole(sessionRole),
            )
        }
        connectWebSocket()
        notifyState()
    }

    private fun isSessionGone(rawError: String?): Boolean {
        val lower = rawError?.lowercase().orEmpty()
        return "room closed" in lower || "room not initialized" in lower ||
            "not in room" in lower || "member not in room" in lower || "unauthorized" in lower
    }

    private suspend fun restoreSavedSession() {
        val saved = synchronized(lock) { config.session } ?: return
        if (saved.roomId.isBlank() || saved.baseUrl.isBlank()) return
        synchronized(lock) {
            sessionBaseUrl = normalizeListenTogetherBaseUrlOrNull(saved.baseUrl)
            sessionUserUuid = saved.userUuid
            sessionNickname = saved.nickname
            sessionRole = saved.role.ifBlank { "listener" }
            joinSecret = saved.joinSecret
            token = saved.token
            connection = "connecting"
        }
        notifyState()
        scope.launch { rejoinSession(saved) }
    }

    private fun clearSession(clearPersistedSession: Boolean) {
        synchronized(lock) {
            room = null
            token = null
            joinSecret = null
            sessionBaseUrl = null
            sessionUserUuid = null
            sessionNickname = null
            sessionRole = null
            wsUrl = null
            wsOpen = false
            connection = "disconnected"
            expectedPositionMs = 0L
            expectedAtLocalMs = 0L
            reported = null
            lastOutboundAtMs = 0L
            lastInboundAtMs = 0L
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            if (clearPersistedSession) {
                config = config.copy(session = null)
                persistConfig()
            }
        }
    }

    // ---------------------------------------------------------------- WS 消息处理

    private suspend fun handleSocketText(text: String) {
        val envelope = runCatching {
            ListenTogetherJson.decodeFromString<ListenTogetherSocketEnvelope>(text)
        }.getOrNull() ?: return
        synchronized(lock) {
            lastInboundAtMs = System.currentTimeMillis()
            updateClockLocked(envelope.nowMs)
        }
        when (envelope.type) {
            "welcome" -> handleWelcome(envelope)
            "room_state_updated", "room_resumed" -> handleRoomStateMessage(envelope, message = null)
            "room_suspended" -> {
                handleRoomStateMessage(envelope, message = "控制者已离线，房间已挂起")
                synchronized(lock) { notice = "控制者已离线，等待其重连" }
                notifyState()
            }

            "room_closed" -> handleRoomClosed(envelope.message)
            "control_result" -> handleControlResult(envelope)
            "pong", "np_pong" -> Unit
            "link_requested" -> {
                synchronized(lock) {
                    notice = "听众请求共享播放地址：桌面版暂不支持音频直链，请让听众使用自己的音源解析"
                }
                notifyState()
            }

            "error" -> {
                synchronized(lock) { lastError = describeListenTogetherError(envelope.message) }
                notifyState()
            }

            else -> Unit
        }
    }

    private fun updateClockLocked(serverNowMs: Long?) {
        if (serverNowMs == null || serverNowMs <= 0L) return
        serverClockOffsetMs = serverNowMs - System.currentTimeMillis()
    }

    private suspend fun handleWelcome(envelope: ListenTogetherSocketEnvelope) {
        synchronized(lock) {
            wsOpen = true
            connection = "connected"
            reconnectAttempts = 0
            nextReconnectAtMs = 0L
            lastError = null
            envelope.userUuid?.let { sessionUserUuid = it }
            envelope.role?.let { sessionRole = it }
            envelope.nickname?.let { sessionNickname = it }
        }
        val state = envelope.state
        if (state != null) {
            applyAuthoritativeState(
                state = state,
                expectedPosition = envelope.expectedPositionMs ?: responseExpectedPosition(state),
                causedBy = null,
                applyToPlayer = !isControllerRole(envelope.role),
            )
        }
        if (isControllerRole(envelope.role) || isControllerRole(sessionRole)) {
            // 重连回来时以本地播放器为准把房间状态拉回来（控制者离线期间听众无法控制）
            pushLocalSnapshotToRoom()
        }
        notifyState()
    }

    private suspend fun handleRoomStateMessage(envelope: ListenTogetherSocketEnvelope, message: String?) {
        val state = envelope.state ?: return
        val expected = envelope.expectedPositionMs ?: responseExpectedPosition(state)
        val applyToPlayer = !(isControllerRole(sessionRole) && isSelfCause(envelope.causedBy))
        applyAuthoritativeState(
            state = state,
            expectedPosition = expected,
            causedBy = envelope.causedBy,
            applyToPlayer = applyToPlayer,
        )
        if (message != null) synchronized(lock) { notice = message }
        notifyState()
    }

    private suspend fun handleControlResult(envelope: ListenTogetherSocketEnvelope) {
        val response = envelope.result
        val ok = envelope.ok == true && response?.ok != false
        val error = envelope.message ?: response?.error
        val eventId = envelope.causedBy?.eventId
        if (eventId != null) {
            pendingResults.remove(eventId)?.complete(
                ControlOutcome(ok, error, response?.applied),
            )
        }
        val applied = response?.applied
        if (applied?.state != null) {
            applyAuthoritativeState(
                state = applied.state,
                expectedPosition = applied.expectedPositionMs ?: responseExpectedPosition(applied.state),
                causedBy = applied.causedBy ?: envelope.causedBy,
                applyToPlayer = !(isControllerRole(sessionRole) && isSelfCause(applied.causedBy ?: envelope.causedBy)),
            )
        }
        if (!ok) {
            synchronized(lock) { lastError = describeListenTogetherError(error) }
            notifyState()
        }
    }

    private fun handleRoomClosed(reason: String?) {
        closeSocket()
        clearSession(clearPersistedSession = true)
        synchronized(lock) {
            lastError = "房间已关闭" + (reason?.takeIf { it.isNotBlank() }?.let { "：$it" } ?: "")
            notice = null
        }
        notifyState()
    }

    private suspend fun applyAuthoritativeState(
        state: ListenTogetherRoomState,
        expectedPosition: Long,
        causedBy: ListenTogetherCause?,
        applyToPlayer: Boolean,
    ) {
        val now = System.currentTimeMillis()
        val merged = synchronized(lock) {
            val merged = mergeRoomState(room, state)
            room = merged
            if (merged != null) {
                expectedPositionMs = expectedPosition
                expectedAtLocalMs = now
            }
            merged
        }
        if (merged != null && applyToPlayer && !leaving) {
            applyRoomStateToPlayer(merged, expectedPosition)
        }
        notifyState()
    }

    private fun mergeRoomState(current: ListenTogetherRoomState?, candidate: ListenTogetherRoomState): ListenTogetherRoomState {
        if (current == null) return candidate
        if (candidate.roomId.isNotBlank() && current.roomId.isNotBlank() &&
            !candidate.roomId.equals(current.roomId, ignoreCase = true)
        ) {
            return candidate
        }
        return if (candidate.version >= current.version) candidate else current
    }

    private fun isSelfCause(cause: ListenTogetherCause?): Boolean {
        val self = config.userUuid
        if (self.isBlank()) return false
        val causeUser = cause?.userUuid ?: cause?.userId ?: return false
        return causeUser.equals(self, ignoreCase = true)
    }

    // ---------------------------------------------------------------- 权威状态 → 播放器

    private suspend fun applyRoomStateToPlayer(state: ListenTogetherRoomState, expectedPosition: Long) {
        applyMutex.withLock {
            val player = container.player
            val tracks = state.queue
            val songs = tracks.mapNotNull { it.toSongOrNull() }
            if (songs.isEmpty()) return
            val index = state.currentIndex.coerceIn(0, songs.lastIndex)
            val targetSong = songs.getOrNull(index) ?: return
            val roomPlaying = state.playback.state == "playing"
            val currentKey = player.currentSong.value?.key
            val sameTrack = currentKey == targetSong.key
            val sameQueue = player.queue.value.map { it.key } == songs.map { it.key }
            applyingRemote = true
            suppressLocalUntilMs = System.currentTimeMillis() + REMOTE_APPLY_SUPPRESS_MS
            try {
                if (!sameQueue) {
                    if (sameTrack) {
                        // 同一首歌只是队列变了（排序/增删）：不要 autoPlay，否则会从头重播当前曲目
                        player.setQueue(songs, index, autoPlay = false)
                    } else {
                        player.setQueue(songs, index, autoPlay = roomPlaying)
                        if (roomPlaying) {
                            awaitPlayerPrepared(targetSong.key)
                        } else {
                            // 切歌且房间是暂停态：不强行打开音频，只更新队列与当前歌曲
                            player.pause()
                        }
                    }
                } else if (!sameTrack || player.currentIndex.value != index) {
                    player.setQueue(songs, index, autoPlay = roomPlaying)
                    if (roomPlaying) awaitPlayerPrepared(targetSong.key)
                }
                val repeat = listenTogetherRepeatModeToRepeatMode(state.playback.repeatMode)
                if (player.repeatMode.value != repeat) player.setRepeatMode(repeat)
                val shuffle = state.playback.shuffleEnabled == true
                if (player.shuffle.value != shuffle) player.setShuffle(shuffle)

                val localPlaying = player.state.value == PlaybackState.PLAYING
                if (roomPlaying && !localPlaying) {
                    player.play()
                    awaitPlayerPrepared(targetSong.key)
                } else if (!roomPlaying && localPlaying) {
                    player.pause()
                }

                if (roomPlaying && player.currentSong.value?.key == targetSong.key) {
                    val duration = player.durationMs.value.takeIf { it > 0 }
                        ?: tracks.getOrNull(index)?.durationMs
                        ?: 0L
                    val target = clampPosition(expectedPosition, duration)
                    if (abs(player.positionMs.value - target) > 800) player.seekTo(target)
                } else if (!roomPlaying && player.currentSong.value?.key == targetSong.key &&
                    player.state.value == PlaybackState.PAUSED &&
                    abs(player.positionMs.value - clampPosition(expectedPosition, targetSong.durationMs)) > 1_200
                ) {
                    // 同一首歌且已经打开过音频时，暂停态也可以直接定位
                    player.seekTo(clampPosition(expectedPosition, targetSong.durationMs))
                }
            } finally {
                applyingRemote = false
                suppressLocalUntilMs = System.currentTimeMillis() + 400
                // 以「房间权威状态」而不是本地播放器更新上报基线：应用过程中用户可能又点了播放，
                // 用本地状态会把尚未同步的本地变化误判成已上报，导致房间与控制者状态分叉。
                reported = buildReportFromRoom(state, expectedPosition, System.currentTimeMillis())
            }
        }
    }

    /** 用权威房态构造上报基线：room 认为是什么，就当作已经上报过什么。 */
    private fun buildReportFromRoom(
        state: ListenTogetherRoomState,
        positionMs: Long,
        now: Long,
    ): LocalReport {
        val index = if (state.queue.isEmpty()) -1 else state.currentIndex.coerceIn(0, state.queue.lastIndex)
        val track = state.track ?: state.queue.getOrNull(index)
        return LocalReport(
            queueKeys = state.queue.map { it.stableKey },
            index = index,
            trackKey = track?.stableKey,
            playing = state.playback.state == "playing",
            repeatMode = state.playback.repeatMode ?: 0,
            shuffle = state.playback.shuffleEnabled == true,
            positionMs = positionMs.coerceAtLeast(0L),
            atMs = now,
        )
    }

    private suspend fun awaitPlayerPrepared(expectedKey: String) {
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val player = container.player
            if (player.currentSong.value?.key != expectedKey) return
            when (player.state.value) {
                PlaybackState.PLAYING, PlaybackState.PAUSED -> return
                PlaybackState.ERROR -> return
                else -> delay(80)
            }
        }
    }

    private fun clampPosition(positionMs: Long, durationMs: Long): Long {
        val value = positionMs.coerceAtLeast(0L)
        return if (durationMs > 0) value.coerceAtMost(durationMs) else value
    }

    private fun responseExpectedPosition(state: ListenTogetherRoomState): Long {
        val playback = state.playback
        if (playback.state != "playing") return playback.basePositionMs.coerceAtLeast(0L)
        val now = System.currentTimeMillis() + serverClockOffsetMs
        val projected = playback.basePositionMs +
            ((now - playback.baseTimestampMs) * (playback.playbackRate.takeIf { it > 0.0 } ?: 1.0)).toLong()
        val duration = (state.track ?: state.queue.getOrNull(state.currentIndex))?.durationMs ?: 0L
        return if (playback.repeatMode == 1 && duration > 0) {
            projected.coerceAtLeast(0L) % duration
        } else {
            projected.coerceAtLeast(0L)
        }
    }

    private fun computeExpectedPosition(now: Long): Long {
        val state = synchronized(lock) { room } ?: return 0L
        val playing = state.playback.state == "playing"
        if (!playing) return expectedPositionMs
        val elapsed = (now - expectedAtLocalMs).coerceAtLeast(0L)
        val rate = state.playback.playbackRate.takeIf { it > 0.0 } ?: 1.0
        val projected = expectedPositionMs + (elapsed * rate).toLong()
        val duration = (state.track ?: state.queue.getOrNull(state.currentIndex))?.durationMs ?: 0L
        return if (state.playback.repeatMode == 1 && duration > 0) projected % duration else projected
    }

    // ---------------------------------------------------------------- 本地播放 → 控制事件

    private data class LocalReport(
        val queueKeys: List<String>,
        val index: Int,
        val trackKey: String?,
        val playing: Boolean,
        val repeatMode: Int,
        val shuffle: Boolean,
        val positionMs: Long,
        val atMs: Long,
    )

    private fun buildLocalReport(now: Long): LocalReport? {
        val player = container.player
        val pair = shareableQueueSnapshot() ?: return null
        val (tracks, index) = pair
        val track = tracks.getOrNull(index) ?: return null
        return LocalReport(
            queueKeys = tracks.map { it.stableKey },
            index = index,
            trackKey = track.stableKey,
            playing = player.state.value == PlaybackState.PLAYING,
            repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
            shuffle = player.shuffle.value,
            positionMs = player.positionMs.value.coerceAtLeast(0L),
            atMs = now,
        )
    }

    /** 返回可分享的队列快照与当前歌曲在其中的下标；当前歌曲不可分享时返回 null。 */
    private fun shareableQueueSnapshot(): Pair<List<ListenTogetherTrack>, Int>? {
        val player = container.player
        val currentSong = player.currentSong.value ?: return null
        val currentTrack = currentSong.toListenTogetherTrackOrNull() ?: return null
        val currentIndex = player.currentIndex.value
        val entries = player.queue.value.mapIndexedNotNull { index, song ->
            song.toListenTogetherTrackOrNull()?.let { index to it }
        }
        if (entries.isEmpty()) return null
        val mappedIndex = entries.indexOfFirst { (originalIndex, track) ->
            originalIndex == currentIndex || track.stableKey == currentTrack.stableKey
        }.coerceAtLeast(0)
        return entries.map { it.second } to mappedIndex
    }

    private fun boundQueueAround(
        tracks: List<ListenTogetherTrack>,
        index: Int,
    ): Pair<List<ListenTogetherTrack>, Int> {
        if (tracks.size <= MAX_QUEUE_SIZE) return tracks to index
        val half = MAX_QUEUE_SIZE / 2
        val start = (index - half).coerceIn(0, (tracks.size - MAX_QUEUE_SIZE).coerceAtLeast(0))
        val window = tracks.subList(start, (start + MAX_QUEUE_SIZE).coerceAtMost(tracks.size)).toList()
        return window to (index - start).coerceIn(0, window.lastIndex)
    }

    private suspend fun publishLocalState(now: Long) {
        if (applyingRemote || now < suppressLocalUntilMs) return
        val player = container.player
        val state = player.state.value
        if (state == PlaybackState.PREPARING || state == PlaybackState.IDLE || state == PlaybackState.ERROR) return
        val pair = shareableQueueSnapshot()
        if (pair == null) {
            reported = null
            if (!unsupportedLocalSongNotified) {
                unsupportedLocalSongNotified = true
                synchronized(lock) { notice = "当前播放的是本地/不可分享歌曲，播放不会同步到房间" }
                notifyState()
            }
            return
        }
        unsupportedLocalSongNotified = false
        val (allTracks, index) = pair
        val (tracks, boundedIndex) = boundQueueAround(allTracks, index)
        val track = tracks.getOrNull(boundedIndex) ?: return
        val playing = state == PlaybackState.PLAYING
        val position = player.positionMs.value.coerceAtLeast(0L)
        val repeat = player.repeatMode.value.toListenTogetherRepeatMode()
        val shuffle = player.shuffle.value
        val current = LocalReport(
            queueKeys = tracks.map { it.stableKey },
            index = boundedIndex,
            trackKey = track.stableKey,
            playing = playing,
            repeatMode = repeat,
            shuffle = shuffle,
            positionMs = position,
            atMs = now,
        )
        val previous = reported
        reported = current
        if (previous == null || previous.trackKey == null) {
            sendFullSnapshot(tracks, boundedIndex, track, position, playing, repeat, shuffle)
            return
        }
        val trackChanged = previous.trackKey != track.stableKey
        val queueChanged = previous.queueKeys != current.queueKeys || previous.index != boundedIndex
        val playbackStateChanged = previous.playing != playing
        val modeChanged = previous.repeatMode != repeat || previous.shuffle != shuffle
        val expectedPosition = if (previous.playing) {
            previous.positionMs + (now - previous.atMs)
        } else {
            previous.positionMs
        }
        val seek = !trackChanged && !queueChanged && !playbackStateChanged &&
            if (previous.playing) {
                abs(position - expectedPosition) > LOCAL_SEEK_TOLERANCE_PLAYING_MS
            } else {
                abs(position - previous.positionMs) > LOCAL_SEEK_TOLERANCE_PAUSED_MS
            }

        if (trackChanged) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = "SET_TRACK",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    queue = tracks,
                    shouldPlay = playing,
                    state = if (playing) "playing" else "paused",
                    repeatMode = repeat,
                    shuffleEnabled = shuffle,
                    requestTrackStableKey = track.stableKey,
                ),
                awaitResult = false,
            )
            return
        }
        if (queueChanged) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = "SET_QUEUE",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    queue = tracks,
                    shouldPlay = playing,
                    state = if (playing) "playing" else "paused",
                    repeatMode = repeat,
                    shuffleEnabled = shuffle,
                ),
                awaitResult = false,
            )
        }
        if (playbackStateChanged) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = if (playing) "PLAY" else "PAUSE",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    shouldPlay = playing,
                    state = if (playing) "playing" else "paused",
                    repeatMode = repeat,
                    shuffleEnabled = shuffle,
                ),
                awaitResult = false,
            )
        }
        if (modeChanged) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = "PLAYBACK_MODE",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    repeatMode = repeat,
                    shuffleEnabled = shuffle,
                    state = if (playing) "playing" else "paused",
                ),
                awaitResult = false,
            )
        }
        if (seek) {
            sendControlEvent(
                ListenTogetherControlEvent(
                    type = "SEEK",
                    positionMs = position,
                    currentIndex = boundedIndex,
                    track = track,
                    state = if (playing) "playing" else "paused",
                    shouldPlay = playing,
                ),
                awaitResult = false,
            )
        }
    }

    private suspend fun sendFullSnapshot(
        tracks: List<ListenTogetherTrack>,
        index: Int,
        track: ListenTogetherTrack,
        positionMs: Long,
        playing: Boolean,
        repeatMode: Int,
        shuffle: Boolean,
    ) {
        sendControlEvent(
            ListenTogetherControlEvent(
                type = "SET_QUEUE",
                positionMs = positionMs,
                currentIndex = index,
                track = track,
                queue = tracks,
                shouldPlay = playing,
                state = if (playing) "playing" else "paused",
                repeatMode = repeatMode,
                shuffleEnabled = shuffle,
            ),
            awaitResult = false,
        )
    }

    private suspend fun pushLocalSnapshotToRoom() {
        val pair = shareableQueueSnapshot() ?: return
        val (tracks, index) = boundQueueAround(pair.first, pair.second)
        val player = container.player
        val position = player.positionMs.value.coerceAtLeast(0L)
        val playing = player.state.value == PlaybackState.PLAYING
        sendControlEvent(
            ListenTogetherControlEvent(
                type = "SET_QUEUE",
                positionMs = position,
                currentIndex = index,
                track = tracks.getOrNull(index),
                queue = tracks,
                shouldPlay = playing,
                state = if (playing) "playing" else "paused",
                repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = player.shuffle.value,
            ),
            awaitResult = false,
        )
        reported = buildLocalReport(System.currentTimeMillis())
    }

    private suspend fun maybeSendHeartbeat(now: Long) {
        if (applyingRemote || now < suppressLocalUntilMs) return
        val pair = shareableQueueSnapshot() ?: return
        val (tracks, index) = pair
        val track = tracks.getOrNull(index) ?: return
        val player = container.player
        val playing = player.state.value == PlaybackState.PLAYING
        val interval = if (playing) HEARTBEAT_PLAYING_MS else HEARTBEAT_PAUSED_MS
        if (now - lastOutboundAtMs < interval) return
        sendControlEvent(
            ListenTogetherControlEvent(
                type = "HEARTBEAT",
                positionMs = player.positionMs.value.coerceAtLeast(0L),
                currentIndex = index,
                track = track,
                state = if (playing) "playing" else "paused",
                shouldPlay = playing,
                repeatMode = player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = player.shuffle.value,
            ),
            awaitResult = false,
        )
    }

    private suspend fun maybePing(now: Long) {
        if (!wsOpen) return
        if (now - lastPingAtMs < PING_INTERVAL_MS) return
        lastPingAtMs = now
        val client = synchronized(lock) { wsClient } ?: return
        val payload = """{"type":"np_ping","t":$now}"""
        withContext(Dispatchers.IO) { runCatching { client.send(payload) } }
    }

    private suspend fun maybeRefreshState(now: Long) {
        val connected = synchronized(lock) { connection == "connected" }
        if (!connected || room == null) return
        if (now - lastInboundAtMs < STATE_REFRESH_SILENCE_MS) return
        if (now - lastStateRefreshAtMs < STATE_REFRESH_SILENCE_MS) return
        val (base, roomId, currentToken) = synchronized(lock) {
            Triple(sessionBaseUrl, room?.roomId, token)
        }
        if (base == null || roomId == null || currentToken == null) return
        lastStateRefreshAtMs = now
        val result = withContext(Dispatchers.IO) { http.getState(base, roomId, currentToken) }
        val response = result.value
        if (result.error != null || response == null || !response.ok) return
        val state = response.state ?: return
        applyAuthoritativeState(
            state = state,
            expectedPosition = response.expectedPositionMs ?: responseExpectedPosition(state),
            causedBy = null,
            applyToPlayer = !isControllerRole(sessionRole),
        )
    }

    private suspend fun correctListenerPlayback(now: Long) {
        val state = synchronized(lock) { room } ?: return
        if (state.roomStatus == "closed") return
        if (isControllerRole(sessionRole)) return
        if (now < suppressLocalUntilMs || applyingRemote) return
        if (now - lastListenerApplyAtMs < 2_000L) return
        val track = state.track ?: state.queue.getOrNull(state.currentIndex) ?: return
        val song = track.toSongOrNull() ?: return
        val player = container.player
        if (player.state.value == PlaybackState.ERROR) return
        val target = computeExpectedPosition(now)
        val sameTrack = player.currentSong.value?.key == song.key
        val roomPlaying = state.playback.state == "playing"
        val localPlaying = player.state.value == PlaybackState.PLAYING
        val needsApply = !sameTrack ||
            (roomPlaying && !localPlaying) ||
            (!roomPlaying && localPlaying) ||
            (roomPlaying && sameTrack && abs(player.positionMs.value - target) > LISTENER_DRIFT_TOLERANCE_MS)
        if (!needsApply) return
        lastListenerApplyAtMs = now
        applyRoomStateToPlayer(state, target)
    }

    // ---------------------------------------------------------------- 控制事件发送

    private data class ControlOutcome(
        val ok: Boolean,
        val error: String?,
        val applied: ListenTogetherAppliedEvent?,
    )

    private fun enrichControlEvent(event: ListenTogetherControlEvent): ListenTogetherControlEvent {
        val player = container.player
        val pair = shareableQueueSnapshot()
        val index = pair?.second
        val track = pair?.first?.getOrNull(index ?: -1)
        return when (event.type) {
            "PLAY", "PAUSE", "SEEK", "HEARTBEAT" -> event.copy(
                positionMs = event.positionMs ?: player.positionMs.value.coerceAtLeast(0L),
                currentIndex = event.currentIndex ?: index,
                track = event.track ?: track,
                state = event.state ?: when (event.type) {
                    "PLAY" -> "playing"
                    "PAUSE" -> "paused"
                    else -> player.state.value.toListenTogetherPlaybackStateName()
                },
                shouldPlay = event.shouldPlay ?: (event.type == "PLAY" ||
                    (event.type == "HEARTBEAT" && player.state.value == PlaybackState.PLAYING)),
                repeatMode = event.repeatMode ?: player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = event.shuffleEnabled ?: player.shuffle.value,
                requestTrackStableKey = event.requestTrackStableKey ?: track?.stableKey,
            )

            "PLAYBACK_MODE" -> event.copy(
                positionMs = event.positionMs ?: player.positionMs.value.coerceAtLeast(0L),
                currentIndex = event.currentIndex ?: index,
                track = event.track ?: track,
                repeatMode = event.repeatMode ?: player.repeatMode.value.toListenTogetherRepeatMode(),
                shuffleEnabled = event.shuffleEnabled ?: player.shuffle.value,
            )

            "SET_TRACK", "SET_QUEUE" -> {
                val tracks = event.queue ?: pair?.first.orEmpty()
                val targetIndex = event.currentIndex ?: index ?: 0
                event.copy(
                    queue = tracks,
                    currentIndex = targetIndex,
                    track = event.track ?: tracks.getOrNull(targetIndex),
                    positionMs = event.positionMs ?: player.positionMs.value.coerceAtLeast(0L),
                    shouldPlay = event.shouldPlay ?: (player.state.value == PlaybackState.PLAYING),
                    state = event.state ?: player.state.value.toListenTogetherPlaybackStateName(),
                    repeatMode = event.repeatMode ?: player.repeatMode.value.toListenTogetherRepeatMode(),
                    shuffleEnabled = event.shuffleEnabled ?: player.shuffle.value,
                )
            }

            else -> event
        }
    }

    private suspend fun sendControlEvent(
        event: ListenTogetherControlEvent,
        awaitResult: Boolean,
    ): ControlOutcome? {
        val (base, roomId, currentToken) = synchronized(lock) {
            Triple(sessionBaseUrl, room?.roomId, token)
        }
        if (base == null || roomId == null) {
            if (awaitResult) synchronized(lock) { lastError = "不在房间中" }
            return ControlOutcome(false, "不在房间中", null)
        }
        val prepared = event.copy(
            eventId = event.eventId ?: newEventId(),
            clientTimeMs = System.currentTimeMillis(),
            clientInstanceId = config.clientInstanceId,
            clientSequence = clientSequence.incrementAndGet(),
        )
        val json = ListenTogetherJson.encodeToString(ListenTogetherControlEvent.serializer(), prepared)
        val eventId = prepared.eventId ?: newEventId()
        synchronized(lock) { lastOutboundAtMs = System.currentTimeMillis() }

        if (synchronized(lock) { wsOpen }) {
            val client = synchronized(lock) { wsClient }
            if (awaitResult) {
                val deferred = CompletableDeferred<ControlOutcome>()
                pendingResults[eventId] = deferred
                val sent = withContext(Dispatchers.IO) { client?.send(json) == true }
                if (sent) {
                    val outcome = withTimeoutOrNull(CONTROL_RESULT_TIMEOUT_MS) { deferred.await() }
                    pendingResults.remove(eventId)
                    if (outcome != null) return outcome
                    // 控制结果超时：指令已经发出，按服务端广播的房态为准
                    return ControlOutcome(true, null, null)
                }
                pendingResults.remove(eventId)
            } else {
                val sent = withContext(Dispatchers.IO) { client?.send(json) == true }
                if (sent) return ControlOutcome(true, null, null)
            }
        }

        // WebSocket 不可用 → HTTP /control 兜底（Android 也有同样的回退路径）
        if (currentToken.isNullOrBlank()) {
            return ControlOutcome(false, "连接已断开", null)
        }
        val result = withContext(Dispatchers.IO) { http.sendControlEvent(base, roomId, currentToken, prepared) }
        val response = result.value
        if (result.error != null || response == null) {
            return ControlOutcome(false, result.error ?: "控制指令发送失败", null)
        }
        val applied = response.applied
        if (applied?.state != null) {
            applyAuthoritativeState(
                state = applied.state,
                expectedPosition = applied.expectedPositionMs ?: responseExpectedPosition(applied.state),
                causedBy = applied.causedBy,
                applyToPlayer = !(isControllerRole(sessionRole) &&
                    isSelfCause(applied.causedBy)),
            )
        }
        return ControlOutcome(
            ok = response.ok && result.status in 200..299,
            error = response.error ?: result.error,
            applied = applied,
        )
    }

    private fun newEventId(): String =
        "evt-${System.currentTimeMillis()}-${clientSequence.get()}-${UUID.randomUUID().toString().take(8)}"

    private fun buildWsUrl(baseUrl: String?, roomId: String?, tokenValue: String?): String? {
        if (baseUrl == null || roomId == null || tokenValue.isNullOrBlank()) return null
        val wsBase = when {
            baseUrl.startsWith("https://") -> "wss://" + baseUrl.removePrefix("https://")
            baseUrl.startsWith("http://") -> "ws://" + baseUrl.removePrefix("http://")
            else -> return null
        }
        val encodedToken = java.net.URLEncoder.encode(tokenValue, Charsets.UTF_8)
        return "$wsBase/api/rooms/$roomId/ws?token=$encodedToken"
    }

    private fun resolveRoleFromState(state: ListenTogetherRoomState?, userUuid: String): String {
        if (state == null || state.controllerUserUuid == null) return "listener"
        return if (state.controllerUserUuid.equals(userUuid, ignoreCase = true)) "controller" else "listener"
    }

    private fun isControllerRole(role: String?): Boolean =
        role?.equals("controller", ignoreCase = true) == true

    private fun isController(): Boolean = isControllerRole(sessionRole) && room != null

    private fun repeatAll(state: ListenTogetherRoomState): Boolean = state.playback.repeatMode == 2

    private fun resolveNickname(requested: String?): String {
        val trimmed = requested?.trim()?.takeIf { it.isNotEmpty() }
            ?: config.nickname.trim().takeIf { it.isNotEmpty() }
        if (trimmed != null && validateListenTogetherNickname(trimmed) == null) return trimmed
        return buildDefaultNickname()
    }

    private fun buildDefaultNickname(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val suffix = (1..4).map { chars[Random.nextInt(chars.length)] }.joinToString("")
        return "Neri$suffix"
    }

    // ---------------------------------------------------------------- 主循环

    private suspend fun tickLoop() {
        while (true) {
            delay(TICK_INTERVAL_MS)
            runCatching { tick() }
        }
    }

    private suspend fun tick() {
        val now = System.currentTimeMillis()
        if (isController() && synchronized(lock) { wsOpen }) {
            publishLocalState(now)
            maybeSendHeartbeat(now)
        }
        correctListenerPlayback(now)
        maybePing(now)
        maybeRefreshState(now)
        scheduleReconnectIfDue(now)
    }

    private suspend fun scheduleReconnectIfDue(now: Long) {
        // 只有「连接已断开、等待重连」（handleSocketLost/rejoin 失败）时才重连；
        // 创建/加入后正在等 welcome 的 connecting 阶段不能抢先重连，否则会重复 join + 重复 WS。
        val shouldReconnect = synchronized(lock) {
            config.session != null && !wsOpen && !leaving &&
                connection == "reconnecting" && now >= nextReconnectAtMs && !reconnectInFlight
        }
        if (!shouldReconnect) return
        val saved = synchronized(lock) { config.session } ?: return
        scheduleReconnect()
        if (saved.roomId.isBlank()) return
    }

    private fun notifyState() {
        runCatching {
            val payload = ListenTogetherJson.encodeToJsonElement(
                ListenTogetherSnapshot.serializer(),
                snapshot(),
            )
            broadcast(EVENT_STATE, payload)
        }
    }

    private fun fail(message: String): ListenTogetherSnapshot {
        synchronized(lock) { lastError = message }
        notifyState()
        return snapshot()
    }

    private fun playbackSnapshot(state: ListenTogetherRoomState?, now: Long): ListenTogetherPlaybackSnapshot {
        if (state == null) {
            return ListenTogetherPlaybackSnapshot(
                state = container.player.state.value.toListenTogetherPlaybackStateName(),
                positionMs = container.player.positionMs.value,
                durationMs = container.player.durationMs.value,
                serverNowMs = now + serverClockOffsetMs,
            )
        }
        val playing = state.playback.state == "playing"
        val duration = (state.track ?: state.queue.getOrNull(state.currentIndex))?.durationMs ?: 0L
        val position = if (playing) {
            val elapsed = (now - expectedAtLocalMs).coerceAtLeast(0L)
            val rate = state.playback.playbackRate.takeIf { it > 0.0 } ?: 1.0
            val projected = expectedPositionMs + (elapsed * rate).toLong()
            if (state.playback.repeatMode == 1 && duration > 0) projected % duration else projected
        } else {
            expectedPositionMs
        }
        return ListenTogetherPlaybackSnapshot(
            state = if (playing) "playing" else "paused",
            positionMs = position.coerceAtLeast(0L),
            durationMs = duration,
            serverNowMs = now + serverClockOffsetMs,
            basePositionMs = state.playback.basePositionMs,
            repeatMode = state.playback.repeatMode ?: 0,
            shuffleEnabled = state.playback.shuffleEnabled == true,
        )
    }
}

