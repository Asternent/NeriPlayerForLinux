# NeriPlayer Qt 前端 ⇄ 无头后端 IPC 协议（v1）

Qt 前端（`desktop/qt`）与 Kotlin 无头后端（`neriplayer-core.jar`）通过 **Unix domain socket** 通信。
后端由 Qt 程序在找不到 socket 时自动拉起（`NERIPLAYER_BACKEND_JAR`），也可以手工运行：

```bash
cd desktop && ./gradlew -q backendJar --offline
java -jar build/libs/neriplayer-core.jar [--socket <path>] [--no-scan]
```

## 1. 传输格式

- socket 路径：`$NERIPLAYER_SOCKET` > `$XDG_RUNTIME_DIR/neriplayer/core.sock` > `$TMPDIR/neriplayer/core.sock`
- 每行一条 JSON（UTF-8，LF 结尾，JSON 内不能含换行），即 newline-delimited JSON。
- 三种包：

```jsonc
// 请求（前端 → 后端）
{"id": 1, "method": "player.play", "params": {}}
// 成功响应
{"id": 1, "result": {...}}
// 失败响应
{"id": 1, "error": {"code": "bad_request", "message": "缺少 positionMs"}}
// 事件（后端 → 前端，可主动推送）
{"event": "player", "data": {...}}
```

- `id` 由前端生成、单调递增；响应可能乱序，前端按 id 匹配。
- 后端在客户端连接后立即广播一遍当前 `settings`/`theme`/`player`/`player.queue`/`player.lyrics`。
- 错误码：`bad_request`（参数错误）、`unknown_method`、`not_found`、`internal_error`、`unsupported`、`timeout`。
- 命令处理函数是 `suspend`，在 IO 协程里执行，可以调核心的 suspend API；**不要**在处理器里做长时间阻塞 IO 而不 suspend。

## 2. 模型编码约定

- 核心模型凡是能在 Kotlin 里 `@Serializable` 的，后端直接 `encodeJson(模型)`（kotlinx.serialization），
  字段名与 Kotlin 属性名一致（camelCase）。已标注的包括：
  `Song`、`Playlist`、`UsageEntry`、`PlayStat`、`ArtistGroup`、`AlbumGroup`、`LyricLine`、`Lyrics`、
  `OnlineCollection`、`OnlineArtist`、`OnlineTrack`、`SearchPayload`、`MediaSource`、`RepeatMode`、
  `PlaybackState`、`ScanState`、`DownloadTask`/`DownloadState`、`StatsPeriod`/`StatsSort`/`StatsSummary`/`HotSong`、
  `AccountInfo`、`NeteaseHomeData`、`SongUrl`、`LoginPollResult`、`SyncConfig`。
- 枚举按名字序列化（如 `"PLAYING"`、`"NETEASE"`）。
- 时间都是毫秒时间戳/毫秒时长（`Long`）。
- 队列/歌曲列表：`[Song, ...]`。
- 封面：`Song.artworkPath`（本地文件绝对路径）与 `Song.artworkUrl`（远程 URL）二选一；远程封面用 `artwork.resolve` 取本地缓存路径（见 protocol-artwork.md）。

## 3. 命令清单

### 3.1 应用 / 设置 / 主题

| method | params | result |
| --- | --- | --- |
| `app.ping` | — | `{pong:true}` |
| `app.bootstrap` | — | `{protocol,appVersion,settings,theme,player,queue,lyrics,accounts,sync,downloads,library,playlists,history}` |
| `app.setSystemTheme` | `{isDark, accent}` | `{ok:true}`（同时广播 `theme`） |
| `app.refreshTheme` | — | `theme` 对象（同时广播 `theme`） |
| `app.theme` | — | `theme` 对象 |
| `settings.get` | — | `{settings:<AppSettings>}` |
| `settings.set` | `{patch:{...}}` 局部字段覆盖 | `{settings:<AppSettings>}`（广播 `settings`+`theme`） |

`theme` 对象：`{isDark, seed, seedSource("cover"|"system"|"settings"), paletteStyle, colorSpec, followSystemTheme, roles:{49 个 Material3 color role -> "#RRGGBB"}}`。

### 3.2 播放器

| method | params | 说明 |
| --- | --- | --- |
| `player.togglePlayPause` / `play` / `pause` | — | |
| `player.next` / `previous` | — | |
| `player.seekTo` | `{positionMs}` | |
| `player.setVolume` | `{value:0..1}` | |
| `player.setShuffle` / `toggleShuffle` | `{enabled}` | |
| `player.setRepeatMode` | `{mode:"OFF"|"ALL"|"ONE"}` | |
| `player.cycleRepeatMode` | — | |
| `player.setSpeed` / `setPitch` / `setLoudness` | `{value}` / `{enabled}` | 需要 ffmpeg |
| `player.setEqualizerEnabled` | `{enabled}` | |
| `player.setEqualizerPreset` | `{name, bands:[10×float]}` | |
| `player.setEqualizerBand` | `{index, gain}` | |
| `player.resetEffects` | — | |
| `player.setQueue` | `{songs:[Song], startIndex, autoPlay}` | |
| `player.playSongNow` | `{song:Song, queue?:[Song]}` | |
| `player.playAt` | `{index}` | |
| `player.enqueue` / `enqueueNext` | `{songs}` | |
| `player.removeFromQueue` | `{index}` | |
| `player.moveInQueue` | `{from, to}` | |
| `player.clearQueue` | — | |
| `player.startSleepTimer` | `{minutes}` | |
| `player.stopAfterCurrentSong` / `cancelSleepTimer` | — | |
| `player.reloadLyrics` | — | |
| `player.state` / `player.queue` | — | 当前快照（一般靠事件推送） |

## 4. 事件清单

| event | data | 触发时机 |
| --- | --- | --- |
| `player` | 播放状态快照：`{song,state,positionMs,durationMs,volume,shuffle,repeatMode,speed,pitch,loudness,equalizerEnabled,equalizerBands,buffering,sleepTimer,supportsEffects,queueIndex,coverSeed}` | 变化时推送，播放中约 4Hz |
| `player.queue` | `{songs:[Song], index}` | 队列或当前下标变化 |
| `player.lyrics` | `{lines:[{timeMs,text,translation}], translated:[…], source, raw}` | 歌词加载/切换 |
| `player.lyricsLoading` | `{loading}` | 歌词加载开始/结束 |
| `player.coverSeed` | `{seed}` | 封面主色变化 |
| `theme` | 见 3.1 | 主题/系统主题/封面种子变化 |
| `settings` | `<AppSettings>` | 设置变化 |
| `toast` | `{message, kind:"info"|"error"}` | 需要提示用户 |
| `library.changed` | `{songCount}` | 媒体库变化 |
| `library.scan` | `{running,total,processed,found,error}` | 扫描进度 |
| `collections.changed` | `{count}` | 歌单/收藏变化 |
| `history.changed` / `stats.changed` | `{count}` | 历史/统计变化 |
| `accounts.changed` | `[AccountInfo]` | 账号登录/登出 |
| `downloads.changed` | `{activeCount, taskCount}` | 下载任务变化 |
| `sync.state` | `{configured,running,lastSyncAt,message,success}` | 同步状态变化 |
| `navigate` | `{name, arg?}` | 前端跳转请求（如托盘菜单） |

## 5. 新增领域命令

1. 在 `desktop/src/main/kotlin/.../backend/api/<Domain>Api.kt` 里实现 `object <Domain>Api { fun register(server: CoreServer, container: AppContainer) }`，
   用 `server.on("domain.verb") { params -> ... }` 注册；参数解析工具在 `backend/Wire.kt`（`stringOrNull`/`intOrNull`/`boolOrNull`/`objectOrNull`/`arrayOrNull`、`encodeJson`、`encodeAny`）。
2. 在 `BackendMain.kt` 的注册列表里加一行（**注册顺序不影响**，但文件由专人维护，改动前确认）。
3. 写 `desktop/docs/protocol-<domain>.md`：命令表（method/params/result）+ 相关事件 + 错误码 + QML 使用示例。
4. 需要新的推送事件时，在 `StatePublisher` 里加对应 StateFlow 的 collect。

各领域文档：
- `protocol-library.md`（本地媒体库 + 扫描）
- `protocol-collections.md`（歌单/收藏/历史/统计）
- `protocol-online.md`（探索/搜索/详情/歌词）
- `protocol-artwork.md`（封面解析缓存）
- `protocol-login.md`（扫码登录）
- `protocol-download.md`（下载）
- `protocol-sync.md`（GitHub 同步）
