# 一起听（Listen Together）协议

桌面版「一起听」与 Android 客户端共用同一个 **LTW 服务端**协议
（`np-submodule/NeriPlayer-LTW`，Cloudflare Worker + Durable Object）。
本文档说明：

1. 桌面后端 ⇄ Qt 前端的 IPC 命令与事件（`listenTogether.*`）；
2. 桌面后端 ⇄ LTW 服务端的 HTTP / WebSocket 协议（与 Android 完全一致的字段子集）；
3. 持久化文件、错误码、兼容性核对与不支持项。

参考实现：

- 服务端：`np-submodule/NeriPlayer-LTW/src/worker.js`、`README.md`
- Android 客户端：`modules/model/.../data/model/ltw/`、`modules/listentogether/`
  （`api/ltw/http`、`api/ltw/ws`、`data/ltw/{control,mapping,playback,session}`）
- 桌面实现：`desktop/src/main/kotlin/.../core/ListenTogether{Models,Mapping,Manager}.kt`、
  `.../net/ListenTogetherClient.kt`、`.../backend/api/ListenTogetherApi.kt`
- 前端：`desktop/qt/qml/components/panels/ListenTogetherPanel.qml`、
  `desktop/qt/qml/components/listentogether/`

---

## 1. Qt 前端 ⇄ 桌面后端（IPC）

命令注册在 `backend/api/ListenTogetherApi.kt`，由集成方在 `BackendMain.kt` 调用
`ListenTogetherApi.register(server, container)`。所有命令都返回同一份 **合并快照**
（`ListenTogetherSnapshot`），失败时通过快照里的 `error` 字段返回可读错误（不抛异常）。
状态变化通过 `listenTogether.state` 事件推送（同一结构）。

### 1.1 合并快照 `ListenTogetherSnapshot`

```jsonc
{
  "configured": true,                 // 是否已配置服务端地址
  "serverUrl": "https://example.workers.dev",
  "defaultServerUrl": "https://neriplayer.hancat.work",
  "nickname": "桌面用户",
  "userUuid": "安装级随机 UUID",
  "connection": "connected",          // disconnected/connecting/connected/reconnecting/error
  "connected": true,
  "wsConnected": true,
  "roomId": "ABC234",                 // 不在房间时字段省略（null）
  "role": "controller",               // controller/listener
  "isController": true,
  "selfUserUuid": "…",
  "inviteLink": "neriplayer://listen-together/join?roomId=…&secret=…",
  "members": [ { "userUuid": "…", "nickname": "…", "userId": "…", "role": "listener", "joinedAt": 1700000000000 } ],
  "controllerUserUuid": "…",
  "queue": [ { "stableKey": "netease:123", "channelId": "netease", "audioId": "123",
               "name": "…", "artist": "…", "album": "…", "durationMs": 0, "coverUrl": null,
               "streamUrl": null, "streamUrls": [] } ],
  "currentIndex": 0,
  "track": { /* 同 queue 元素 */ },
  "playback": {
    "state": "playing",               // playing/paused
    "positionMs": 12345,              // 按服务端时钟推算的当前进度
    "durationMs": 240000,
    "serverNowMs": 1700000000000,
    "basePositionMs": 0,
    "repeatMode": 0,                  // 0 关闭 / 1 单曲 / 2 列表
    "shuffleEnabled": false
  },
  "settings": { "allowMemberControl": true, "autoPauseOnMemberChange": true, "shareAudioLinks": false },
  "roomStatus": "active",             // active/controller_offline/closed
  "version": 12,
  "autoPauseOnJoin": false,
  "error": null,                      // 最近一次错误（中文可读文案）
  "notice": "…"                       // 非错误提示（重连、控制者离线等）
}
```

> `explicitNulls = false`：值为 null 的可选字段在 JSON 里直接省略，QML 用 `undefined` 判空。

### 1.2 命令

| method | params | 说明 |
| --- | --- | --- |
| `listenTogether.status` | — | 返回当前合并快照；面板打开 / 轮询进度用 |
| `listenTogether.configure` | `{serverUrl?, nickname?}` | 保存服务端地址（http/https，自动去尾部 `/`）与默认昵称；不在房间时顺带做 `/healthz` 可用性提示。`serverUrl` 传空串表示清空配置 |
| `listenTogether.createRoom` | `{nickname?}` | 用当前播放器队列创建房间，自己成为控制者（初始快照来自 `container.player`）。当前歌曲必须是可分享的在线歌曲（本地歌曲拒绝） |
| `listenTogether.joinRoom` | `{inviteOrRoomId, nickname?}` | 加入房间：接受邀请链接（`neriplayer://listen-together/join?roomId=…&secret=…`，链接里可含 `baseUrl`/`inviter`）或 6 位房间号。首次加入必须用邀请链接（需要 `joinSecret`）；房间号适用于重连已有身份（`memberSecret`） |
| `listenTogether.leaveRoom` | — | 显式离开（HTTP `POST /leave`）并清空本地会话 |
| `listenTogether.requestControl` | `{action, positionMs?, repeatMode?, shuffleEnabled?}` | 听众发起控制请求。`action`：`play`/`pause`/`seek`/`playbackMode`/`next`/`previous`；服务端在 `allowMemberControl` 且控制者在线时自动仲裁 |
| `listenTogether.sendControl` | `{type, …事件字段}` | 原样提交控制事件（`PLAY`/`PAUSE`/`SEEK`/`PLAYBACK_MODE`/`SET_TRACK`/`SET_QUEUE`/`HEARTBEAT`/`UPDATE_SETTINGS`/`REQUEST_*`）；缺省字段由后端用本地播放器补齐 |
| `listenTogether.updateSettings` | `{allowMemberControl?, autoPauseOnMemberChange?, shareAudioLinks?}` | 控制者修改房间设置（走 `UPDATE_SETTINGS`，仅控制者可用） |

### 1.3 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `listenTogether.state` | `ListenTogetherSnapshot` | 连接状态、房态版本、成员、设置、错误/提示变化时 |

### 1.4 QML 使用示例

```qml
Component.onCompleted: {
    Api.on("listenTogether.state", onState)
    Api.get("listenTogether.status", {}, function(result, error) {
        if (!error) panel.status = result
    })
}
Component.onDestruction: Api.off("listenTogether.state", onState)

function onState(data) { panel.status = data }

// 创建房间；结果同样是快照
Api.get("listenTogether.createRoom", {}, function(result, error) {
    if (error) Toast.show(error.message, "error")
    else panel.status = result
})
```

面板打开方式：`Nav.openPanel("listenTogether")`（集成方在 `PlayerOverlays.qml` 里挂载
`components/panels/ListenTogetherPanel.qml`）。

---

## 2. 桌面后端 ⇄ LTW 服务端

### 2.1 HTTP

所有 URL 基于归一化后的 `baseUrl`（http/https，去尾部斜杠）。

| 方法 | 路径 | 鉴权 | 请求体 / 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/rooms` | — | `{userUuid, nickname, initialSnapshot:{queue, currentIndex, track, settings, isPlaying, positionMs, repeatMode, shuffleEnabled, shuffleRestoreQueue?}}` |
| `POST` | `/api/rooms/:roomId/join` | 可选 `Authorization: Bearer <token>`（重连） | `{userUuid, nickname, memberSecret?, joinSecret?}` |
| `GET` | `/api/rooms/:roomId/state` | `Bearer <token>` | 返回 `{ok, state, expectedPositionMs, serverNowMs, autoPauseOnJoin, error}` |
| `POST` | `/api/rooms/:roomId/leave` | `Bearer <token>` | 请求体 `{}` |
| `POST` | `/api/rooms/:roomId/control` | `Bearer <token>` | 控制事件（见 2.3） |
| `GET` | `/api/rooms/:roomId/ws?token=…` | token 查询参数 | WebSocket |
| `GET` | `/healthz` | — | 配置后的可用性探测（非官方实现可忽略结果） |

创建/加入响应（字段与 Android `ListenTogetherRoomResponse` 一致）：

```jsonc
{
  "ok": true, "roomId": "ABC234", "userUuid": "…", "userId": "…", "nickname": "…",
  "role": "controller", "memberSecret": "…", "joinSecret": "…", "autoPauseOnJoin": true,
  "token": "…", "state": { /* ListenTogetherRoomState */ }, "wsUrl": "wss://…", "error": null
}
```

房间状态 `state`（与 Android `ListenTogetherRoomState` 一致）：
`roomId/version/schemaVersion/controllerUserUuid/controllerUserId/controllerHeartbeatAt/
settings/members/queue/currentIndex/track/playback/controllerOfflineSince/roomStatus/closedReason/updatedAt`。

### 2.2 WebSocket 消息

服务端 → 客户端：

| type | 字段 |
| --- | --- |
| `welcome` | `sessionId, userUuid, userId, nickname, role, autoPauseOnJoin, state, expectedPositionMs, nowMs` |
| `room_state_updated` | `roomId, version, state, expectedPositionMs, nowMs, causedBy{userUuid,userId,nickname,eventId,type}, message` |
| `room_resumed` / `room_suspended` | 同上（控制者上线/离线） |
| `room_closed` | `roomId, version, state, expectedPositionMs, nowMs, message` |
| `control_result` | `ok, result:{ok, applied?:{type,roomId,version,state,expectedPositionMs,nowMs,causedBy}, error}, nowMs, message, causedBy` |
| `link_requested` | 听众请求音频直链（桌面版不支持，见 §4） |
| `np_pong` | `{t, nowMs}`；旧版 `pong` 也接收 |
| `error` | `{message}` |

客户端 → 服务端：

- 控制事件 JSON（字段见 2.3）；
- `{"type":"np_ping","t":<毫秒>}`（每 12s，兼作控制者心跳刷新与时钟校准）；
- `{"type":"ping"}`（旧版心跳）。

### 2.3 控制事件字段（Android `ListenTogetherEvent` 子集）

```
type, eventId, clientTimeMs, clientInstanceId, clientSequence,
positionMs, currentIndex, nextIndex, track, queue, roomSettings,
shouldPlay, state, repeatMode, shuffleEnabled, requestTrackStableKey,
forceRefresh, finishedTrackStableKey
```

桌面端实际发送：

| 场景 | 事件 |
| --- | --- |
| 本地切歌 | `SET_TRACK`（带队列快照/索引/track/进度/shouldPlay/循环/随机） |
| 本地队列变化（增删/排序） | `SET_QUEUE` |
| 播放/暂停 | `PLAY` / `PAUSE`（带 positionMs） |
| 本地 seek（进度跳变检测） | `SEEK` |
| 循环/随机变化 | `PLAYBACK_MODE` |
| 定期心跳（播放中 8s / 暂停 15s） | `HEARTBEAT`（广播 `state`/`positionMs`/`track`/`currentIndex`） |
| 听众控制 | `REQUEST_PLAY/PAUSE/SEEK/PLAYBACK_MODE/SET_TRACK` |
| 房间设置 | `UPDATE_SETTINGS{roomSettings}` |

`schemaVersion` 固定为 2（与 worker 一致），但桌面端 **不发送 `queueMutation`**，
只走 worker 仍兼容的「整队列快照」旧路径；收到 `room_state_updated` 后以服务端
`version` 最新者为准整体应用。

### 2.4 曲目字段与映射

`track` / `queue[]` 元素字段（与 Android `ListenTogetherTrack` 一致）：

```
stableKey, channelId, audioId, subAudioId, playlistContextId, mediaUri,
streamUrl, streamUrls[], name, artist, album, durationMs, coverUrl
```

`stableKey` 规则（与 Android `ListenTogetherTrackIdentity.buildStableTrackKey` 一致）：

- `netease` → `netease:<audioId>`（audioId = 网易云歌曲 id，桌面 `Song.remoteId`）
- `bilibili` → `bilibili:<audioId>[:<subAudioId>]`（桌面 `Song.remoteId` 是 bvid）
- `youtubeMusic` → `youtubeMusic:<audioId>[:<playlistContextId>]`

桌面 `Song` ⇄ 房间曲目：

| 房间字段 | 桌面 Song |
| --- | --- |
| `channelId=netease` | `source=NETEASE`，`key=netease:<audioId>`，`remoteId=audioId`，`artworkUrl=coverUrl` |
| `channelId=bilibili` | `source=BILIBILI`，`key=bili:<audioId>`，`remoteId=audioId`，`extraId=subAudioId`（cid） |
| `channelId=youtubeMusic` | `source=YOUTUBE`（仅展示，桌面无 YouTube 播放实现） |
| `channelId=local` | 拒绝：本地歌曲不能进入房间（创建/上报前过滤） |

`playback.repeatMode`：`0` 关闭、`1` 单曲循环、`2` 列表循环（桌面 `RepeatMode.OFF/ONE/ALL`）；
`playback.shuffleEnabled` 布尔。`expectedPositionMs` 由服务端按 `basePositionMs + elapsed*playbackRate`
推算，单曲循环时按曲目时长取模；客户端用它校正本地进度（漂移 > 2.5s 时 seek）。

### 2.5 控制者 / 听众行为

- **控制者**：轮询本地播放器（400ms）与"上次已上报状态"做差异比较，按上表上报控制事件；
  播放中每 8s、暂停每 15s 发送 `HEARTBEAT`；每 12s `np_ping`。
  收到他人触发的权威房态（例如听众 `REQUEST_*` 被服务端仲裁）时应用到本地播放器；
  自己事件触发的回声按 `causedBy.userUuid` 忽略（防回环）。
- **听众**：收到 `room_state_updated`/`welcome` 后把权威房态应用到 `container.player`
  （队列/当前曲/播放暂停/seek/循环/随机）；另有 400ms 看门狗，检测曲目/播放态/进度漂移
  （> 2.5s）时重新对齐。听众本地操作会转成 `REQUEST_*` 请求。
- 应用远端状态期间设置 `applyingRemote`/抑制窗口，并以上报基线=权威房态，避免本地回环。

### 2.6 重连

- WebSocket 断开且会话仍存在时进入 `reconnecting`，指数退避（1s→30s）重新 `POST /join`
  （带 `memberSecret`）拿新 token/wsUrl 后重连；`room closed`/`not in room`/`unauthorized`
  之类错误直接清理会话。
- 连接静默 35s 时用 `GET /state` 兜底刷新房态。
- 启动时若持久化会话存在，自动尝试一次重连。

---

## 3. 持久化

`AppDirs.dataDir/listen_together.json`（不写 `settings.json`，不改 `Storage.kt`）：

```jsonc
{
  "schemaVersion": 1,
  "serverUrl": "https://…",
  "nickname": "桌面用户",
  "userUuid": "安装级 UUID（创建/加入时的身份）",
  "clientInstanceId": "desktop-…（控制事件顺序号用）",
  "session": {
    "baseUrl": "https://…", "roomId": "ABC234", "userUuid": "…", "nickname": "…",
    "role": "controller", "token": "…", "memberSecret": "…", "joinSecret": "…", "wsUrl": "…"
  }
}
```

- 创建/加入成功后写入 `session`；离开房间或房间关闭后清空 `session`（保留 serverUrl/nickname）。
- `memberSecret` / `joinSecret` / `token` 只保存在本地文件，不上报给前端（`status` 只暴露
  `inviteLink`）。
- 邀请链接由后端生成：`neriplayer://listen-together/join?roomId=…&inviter=…&baseUrl=…&secret=…`，
  默认服务端地址时不写 `baseUrl`；解析时只接受 `https` 的 `baseUrl`（防中间人，和 Android 一致），
  否则回退到本机已配置地址。

---

## 4. 兼容性核对与不支持项

已逐项对照 `worker.js` / Android 序列化字段实现：

- ✅ HTTP create/join/state/leave/control、`/healthz`、`ws?token=`；字段名、鉴权方式、错误文案键一致
  （`join_secret_required`、`member_secret_required`、`controller offline`、`member control disabled`…）。
- ✅ `welcome` / `room_state_updated` / `room_suspended` / `room_resumed` / `room_closed` /
  `control_result` / `np_ping`↔`np_pong` / 旧版 `ping`↔`pong`。
- ✅ 事件类型 `PLAY/PAUSE/SEEK/PLAYBACK_MODE/SET_TRACK/SET_QUEUE/HEARTBEAT/UPDATE_SETTINGS`、
  听众 `REQUEST_*` 仲裁、`clientInstanceId/clientSequence/clientTimeMs` 顺序字段。
- ✅ `schemaVersion=2`、`repeatMode 0/1/2`、`shuffleEnabled`、`expectedPositionMs` 推算与单曲循环取模、
  `autoPauseOnMemberChange`（新成员加入/离开暂停）、`allowMemberControl`。
- ✅ 本地歌曲拒绝（`channelId=local` 不上报；服务端也会拒绝）。
- ✅ 邀请链接格式（`neriplayer://listen-together/join`）与 Android `ListenTogetherInviteParser` 一致；
  额外容忍 `neriplayer-debug://`。
- ✅ 会话持久化与用 `memberSecret` 重连（不触发自动暂停，符合 worker 语义）。

不支持的子集 / 已知限制：

1. **音频直链共享（`REQUEST_LINK` / `LINK_READY` / `LINK_UNAVAILABLE` / stream-url 缓存）**：
   桌面版不向服务端上传解析出的播放地址，也不缓存他人的候选地址。桌面创建房间时
   `shareAudioLinks` 默认 `false`；面板里该开关为禁用状态。收到 `link_requested` 只提示、
   不回应（Android 听众等待超时后回退本机解析）。
2. **`queueMutation`（schemaVersion 2 的增量队列操作）**：桌面端只发整队列快照；
   不发送/重放 `queueMutation`（worker 仍兼容旧客户端完整快照路径）。
3. **`TRACK_FINISHED` 多端结束栅栏**：桌面端不上报 `TRACK_FINISHED`；本地播完切歌在控制者身份下
   由轮询检测为 `SET_TRACK`。服务端的 `trackFinishBarrier` 逻辑不会被桌面端触发。
4. **`REQUEST_SET_QUEUE` / `REQUEST_SET_TRACK` 的细粒度顺序/多重集合校验**：桌面听众只提供
   "上一首/下一首"的 `REQUEST_SET_TRACK`（整队列快照），不做本地乐观队列变更。
5. **bilibili 跨端身份**：桌面 `Song.remoteId` 是 bvid，发送 `bilibili:<bvid>`；
   Android 对多 P 视频使用 `bilibili:<aid>:<cid>`。同一视频两端 stableKey 可能不同
   （网易云曲目不受影响）。桌面端不会去在线查询 aid↔bvid。
6. **YouTube Music**：可接收/展示 Android 房间里的 `youtubeMusic` 曲目，但桌面版没有 YouTube
   播放实现，播放会由在线音源解析失败报错。
7. **控制者本地播放不可分享歌曲（本地文件等）**：不上报事件（房间保持上一次权威状态），面板给提示；
   恢复播放可分享歌曲后自动重新同步。
8. **无 Android 的高级恢复策略**：没有网络切换监听、后台保活、播放卡顿探测、
   `FOREGROUND` 恢复等策略，只用简单 WS 重连 + 35s 静默 HTTP 兜底 + 2.5s 漂移校正。
9. **面板不提供改昵称/重置 UUID 的入口**：昵称在面板的服务端配置里统一设置，
   `userUuid` 与安装绑定（持久化文件），不提供重置按钮。

---

## 5. 错误码 / 提示

后端返回的命令结果不带异常，错误放在快照 `error` 字段（已翻译为中文提示）；常见服务端错误：

| worker 错误 | 面板提示 |
| --- | --- |
| `join_secret_required` | 首次加入需要邀请链接（其中带有加入密钥），只填房间号仅适用于重连 |
| `member_secret_required` | 该身份已在房间中，需要用原身份重连 |
| `room not initialized` / `room closed` | 房间不存在或已过期 / 房间已关闭 |
| `controller offline` | 控制者不在线 |
| `member control disabled` | 控制者关闭了听众控制 |
| `only controller …` | 只有控制者可以执行该操作 |
| `member control target mismatch` | 请求控制的目标歌曲已变化，请重试 |
| `audio link sharing disabled` | 房间未开启音频直链共享 |
| `nickname …` | 昵称不合法（1-24 位中文/字母/数字） |

---

## 6. 集成方需要做的事

1. `BackendMain.kt` 注册（仅一行，文件由集成负责人维护）：

   ```kotlin
   import moe.ouom.neriplayer.desktop.backend.api.ListenTogetherApi
   // ...其它 register 之后
   ListenTogetherApi.register(server, container)
   ```

2. `PlayerOverlays.qml` 挂载面板：

   ```qml
   case "listenTogether":
       return "panels/ListenTogetherPanel.qml"
   ```

3. 入口：设置页 `SettingsPage.qml`「一起听」区按钮调用 `Nav.openPanel("listenTogether")`
   （可选在 `NavGlobal.qml` 加 `openListenTogetherPanel()` 便捷函数）。
4. 本功能不依赖登录态/账号；不需要新增账号相关事件或设置项。服务端地址、昵称与房间
   交互全部在面板内完成，配置与会话持久化在 `AppDirs.dataDir/listen_together.json`。
