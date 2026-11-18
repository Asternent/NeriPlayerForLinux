# NeriPlayer Desktop 核心 API 清单

> 用途：Qt6/QML 前端重构时，作为 **IPC 协议设计 / Kotlin 无头守护进程命令实现** 的唯一依据。
> 只读产出：本文档通过阅读源码整理，**未修改任何源码**，未构建、未运行。
>
> - 代码基线：`desktop/` 模块（`moe.ouom.neriplayer.desktop`），版本 `1.4.7`
> - 分支/提交：`fix/window-restore-and-more`，HEAD `3a7f4ece`（Merge remote-tracking branch 'upstream/master' …）
> - 覆盖范围：`core/*.kt`（17 个文件）、`net/*.kt`（4）、`sync/*.kt`（7）、`ui/**`（31 个文件）、`Main.kt`
> - 不覆盖：`tools/SelfTest.kt`、`tools/SyncE2ETest.kt`、`tools/UiScript.kt`（开发自检脚本，不是业务 API；仅在第 6 章提到它们用了哪些方法）
> - 源码规模：`core + net + sync` 共 **7332 行**；`ui + Main.kt` 约 11 900 行

---

## 0. 总览（先读这一节）

### 0.0 给 IPC/后端实现者的结论摘要（TL;DR）

1. **核心是一个纯 JVM 进程内对象图**：`AppContainer` 在构造函数里 new 出全部仓储/服务，靠 Kotlin `StateFlow` 往 Compose UI 推状态。没有现成的命令层、没有任何 IPC/序列化边界，所有现有“UI 调用”都是**直接方法调用 + collectAsState**。所以 IPC 需要新写一层薄命令分发，业务方法可以原样复用。
2. **公开状态面：33 个 `StateFlow` + 1 个 `SharedFlow`**（`player.messages`）。其中 Player 独占 19 个；这些是 QML 需要订阅的“状态通道”。`StateFlow` 天然会去重/合并，适合按 5–10 Hz 或变化时推送；`SharedFlow` **无 replay、缓冲 8**，UI 订阅前发出的消息会丢（对 QML 也是一样，需要重放或改成事件队列）。
3. **数据模型只有 33 个 `@Serializable` 声明**（29 个 data class + 4 个枚举，见 0.3）。`ArtistGroup/AlbumGroup/LyricLine/Lyrics/OnlineCollection/OnlineArtist/SearchPayload/ScanState/DownloadTask/StatsSummary/HotSong/SleepState/SyncState/NowPlayingSnapshot/EngineSnapshot/AudioInput/NeteaseHomeData/SongUrl/LoginPollResult` 等都**不是** `@Serializable`，直接 `kotlinx.serialization` 编不了，需要加注解或写 IPC DTO。
4. **有 16 个 `suspend` 方法**（扫描/歌词/全部在线接口/下载补齐/同步），它们内部基本都 `withContext(Dispatchers.IO)`；**非 suspend 方法里有大量同步阻塞文件写入**（`SettingsRepository.update`、`PlaylistRepository.*`、`HistoryRepository.*`、`StatsRepository.*` 每次都整文件 JSON 重写）。守护进程必须把这些命令放到专门的单线程 dispatcher，否则会阻塞 RPC 事件循环。
5. **封面是「绝对文件路径 + 远程 URL」二选一**（`Song.artworkPath` / `Song.artworkUrl`），UI 自己下载 URL 封面（带各平台 Referer）并缓存到 `~/.cache/NeriPlayer/covers/`。QML 端要么复用同一缓存目录并自己带 Referer 下载，要么让守护进程新增 `artwork.get` 命令返回本地路径/字节流。**这是 IPC 设计最容易漏掉的一块。**
6. **播放进度来自 ffmpeg 解码器 200 ms 定时 tick**（`AudioEngine.tickLoop`），`positionMs/durationMs` 是派生 StateFlow，天然 ~5 Hz；下载进度按 64 KB 分块更新 `tasks` StateFlow（频率可达几十 Hz），**不能原样推给 QML，必须节流**。
7. **单实例锁用的是 MPRIS well-known name**（`org.mpris.MediaPlayer2.neriplayer`）——如果 Qt 前端要自己实现 MPRIS，单实例语义会冲突，必须先决定 MPRIS 归守护进程还是归前端（建议归守护进程，见 1.10 与第 7 章“待确认”）。
8. **`AppContainer.bootstrap()` 是唯一正确的启动序列**（load 各仓库 → attachSnapshotFlow → restoreLastQueue → 启动 MPRIS 桥 → 后台扫描）。守护进程直接复用它，`scanLibrary=false` 可跳过阻塞扫描。

### 0.1 模块关系（进程内）

```
Main.kt (Compose 入口)
 └─ AppContainer                         ← 所有单例的容器 / 依赖注入
     ├─ SettingsRepository  ───────────────┐ (所有模块都读它的 AppSettings)
     ├─ LibraryRepository(settings)        │
     ├─ PlaylistRepository                 │
     ├─ HistoryRepository                  │
     ├─ StatsRepository(history)           │
     ├─ OnlineRepository                   │
     │   ├─ HttpService (Cookie jar)       │
     │   ├─ NeteaseApi / BiliApi           │
     │   └─ collectionCovers StateFlow     │
     ├─ AccountRepository(online.httpService)
     ├─ NeteaseLogin / BiliLogin(online.httpService)
     ├─ LyricsRepository(remote = online.lyrics)
     ├─ DownloadCatalog
     ├─ DownloadManager(online, settings, catalog, scope, lyricsProvider)
     ├─ PlayerManager(settings, history, stats, lyrics, online, scope, downloads)
     │   └─ AudioEngine (FfmpegAudioEngine | JavaSoundAudioEngine)
     ├─ MprisService(snapshot/回调 → player)
     ├─ SyncConfigStore
     └─ GitHubSyncManager(configStore, playlists, history, stats, library, http)
```

依赖方向是单向的：`sync → collections/library`、`download → online`、`player → lyrics/online/downloads/collections`。`net` 层只依赖 `core` 的模型，不反向依赖。**没有循环依赖**，可以整体搬到守护进程。

### 0.2 数量统计（按源码静态统计）

| 指标 | 数量 | 备注 |
|---|---:|---|
| 领域数（本文划分） | **11** | Player/Library/Collections/Online/Login/Download/Sync/Settings/Theme/Tray+MPRIS+Shortcuts/Misc |
| core 文件 | 17 | 含 AppContainer/Models/Storage/AudioEngine 等 |
| net 文件 | 4 | Http/LoginApi/OnlineApis/OnlineRepository |
| sync 文件 | 7 | Manager/Transport/Config/Serializer/Mapping/Merger/Models |
| ui 文件 | 31 | 含 screens/ 6 个、theme/ 1 个 |
| core+net+sync 公开函数声明 | **286** | 已排除 `private`/`internal` 与 23 个接口 `override`（脚本统计） |
| 其中“领域级仓储/服务方法”（IPC 候选命令面） | **161** | 见 5.1 的逐类统计 |
| `suspend` 公开方法 | **16** | 见 0.5 |
| `@Serializable` 声明 | **33** | 29 data class + 4 enum |
| 公开 `StateFlow` | **33** | 含 `AudioEngine.snapshot` 接口声明 |
| 公开 `SharedFlow` | **1** | `player.messages` |
| UI 侧 `container.*` 调用点 | **386** | `ui/` 363 处 + `Main.kt` 23 处 |
| UI 侧去重成员访问 | **122** | 其中业务方法 **79 个**、flow/属性 39 个、UI 外壳回调 3 个（详见 5.2） |

### 0.3 数据模型总览（可否直接 JSON 序列化）

**@Serializable（33 个，可直接 `NeriJson.encodeToString`）**

| 模型 | 文件 | 备注 |
|---|---|---|
| `MediaSource`（enum） | core/Models.kt | LOCAL/NETEASE/BILIBILI/YOUTUBE，有 `displayName` 派生属性 |
| `RepeatMode`（enum） | core/Models.kt | OFF/ALL/ONE |
| `PlaybackState`（enum） | core/Models.kt | IDLE/PREPARING/PLAYING/PAUSED/ERROR |
| `Song` | core/Models.kt | 15 字段；见下方“字段类型注意” |
| `Playlist` | core/Models.kt | songs: List\<Song\> |
| `UsageEntry` | core/Models.kt | song: Song、playedAt: Long、playCount: Int |
| `PlayStat` | core/Models.kt | songKey: String、day: String("yyyy-MM-dd")、playCount、listenMs: Long |
| `AppSettings` | core/Storage.kt | 65 个字段，见第 2 章 |
| `HomeCardSettings` | core/Storage.kt | 5 个 Boolean |
| `ShortcutBinding` | core/Shortcuts.kt | keyCode: Long + 4 修饰键 Boolean |
| `ShortcutSettings` | core/Shortcuts.kt | local/global: Map\<String, ShortcutBinding\> |
| `AccountInfo` | core/Account.kt | source: String（"NETEASE"/"BILIBILI"）、avatarUrl 可空 |
| `AccountStoreData` | core/Account.kt | accounts: List、cookies: Map\<String, Map\<String,String\>\> |
| `DownloadedSong` | core/Download.kt | 13 字段，含 3 个可空路径/URL、sizeBytes/downloadedAt: Long |
| `SyncConfig` | sync/SyncConfig.kt | token/owner/repo/apiBase/lastStatus 等 |
| `SyncAction`（enum） | sync/SyncModels.kt | CREATE_PLAYLIST…PLAY_SONG |
| `SyncData` + 17 个 Sync* 模型 | sync/SyncModels.kt | 带 `@ProtoNumber`，JSON/ProtoBuf 双通道；`SyncData` 是同步文件根对象 |

**非 @Serializable（IPC 必须另建 DTO 或给源码加注解）**

| 模型 | 文件 | 用途 | 关键类型 |
|---|---|---|---|
| `ArtistGroup` / `AlbumGroup` | core/Models.kt | 本地库分组（UI 直接遍历） | name/artist: String、songs: List\<Song\> |
| `LyricLine` / `Lyrics` | core/Models.kt | 歌词行与整首歌歌词 | `Lyrics` 有 raw/lines/translated/source、`isEmpty` |
| `OnlineCollection` / `OnlineArtist` / `OnlineTrack` | core/Models.kt | 在线歌单/歌手/曲目 | coverUrl/avatarUrl 可空、playCount: Long |
| `SearchKind`（enum）/ `SearchPayload` | core/Models.kt | 搜索参数与聚合结果 | SearchPayload 三个 List |
| `ScanState` | core/Library.kt | 扫描进度 | running/total/processed/found/finishedAt/error + `progress` |
| `StatsPeriod`/`StatsSort`（enum）、`StatsSummary`、`HotSong` | core/Collections.kt | 统计查询 | listenMs/lastPlayed: Long |
| `DownloadState`（enum）/ `DownloadTask` | core/Download.kt | 下载任务 | downloadedBytes/totalBytes: Long、progress 派生 |
| `SleepTimerState` | core/Player.kt | 睡眠定时器 | active/stopAfterCurrent、remainingSeconds/totalSeconds: Long |
| `NowPlayingSnapshot` | core/Mpris.kt | MPRIS/托盘快照 | durationMs/positionMs: Long、artUrl 可空 |
| `EngineSnapshot` / `AudioInput` | core/AudioEngine.kt | 解码器状态 / 播放源 | positionMs/durationMs: Long、headers: Map |
| `NeteaseHomeData` | net/OnlineRepository.kt | 首页聚合 | 3 组 List\<Song\> + 2 组 List\<OnlineCollection\> + error |
| `SongUrl` | net/OnlineApis.kt | 播放地址解析 | size: Long |
| `SyncState` | sync/GitHubSyncManager.kt | 同步状态 | running/configured/success: Boolean、lastSyncAt: Long、message |
| `LoginPollResult` | net/LoginApi.kt | 扫码轮询结果 | sealed：Expired / Waiting(scanned) / Success(AccountInfo) / Failed(message) |
| `Result<T>`（Kotlin 标准库） | sync | token 校验/建仓结果 | 需转成 `{ok, value|error}` |
| `DarkModeSetting`（enum） | core/Storage.kt | AUTO/LIGHT/DARK | 未标注 `@Serializable` 但枚举默认可被 kotlinx 序列化 |

**字段类型注意（IPC 编解码必读）**

- `Song`: `key: String`（`local:<绝对路径>` / `netease:<id>` / `bili:<bvid>` / `sync:<…>`）；`durationMs/dateAdded: Long`；`filePath/remoteId/artworkPath/artworkUrl/lyrics/artistId/albumId/extraId` 全部可空。**没有 `trackNumber` 之外的 int 风险**。
- 所有时间戳（`playedAt/downloadedAt/loginAt/lastSyncAt/finishedAt`）都是 epoch **毫秒 Long**，在 JS/QML 的 Number 安全范围（<2^53）内，可以直传。
- **`SyncMapping.stableSyncId()` 返回 `Long and 0x7FFF…`（最大 2^63-1），同步歌单/歌曲 ID 会超过 2^53**。遵守同步协议的 IPC 字段必须序列化为 **字符串**，否则 QML 侧精度丢失会写坏同步数据。
- `Map` 类型里最要注意 `AccountStoreData.cookies: Map<String, Map<String,String>>`（含 Cookie 明文，**不要原样推给前端**）。
- 浮点字段：`AppSettings.volume/playbackSpeed/pitchSemitone/lyricsFontScale/floatingLyrics*: Float`、`equalizerBands: List<Float>`；`DownloadTask.progress: Float` 是派生值。
- 枚举一律按 `name` 字符串编解码；`MediaSource` 有 `displayName`（本地/网易云/哔哩哔哩/YouTube）可直接给 QML 展示。

### 0.4 持久化位置（XDG，全部由守护进程持有）

`AppDirs`（core/Storage.kt:12）：

| 常量 | 实际路径（默认） | 内容 | 写入者 / 频率 |
|---|---|---|---|
| `dataDir` | `$XDG_DATA_HOME/NeriPlayer`（默认 `~/.local/share/NeriPlayer`） | 数据根 | — |
| `configDir` | `$XDG_CONFIG_HOME/NeriPlayer`（默认 `~/.config/NeriPlayer`） | 配置根 | — |
| `cacheDir` / `artworkDir` / `coverDir` | `$XDG_CACHE_HOME/NeriPlayer{,/artwork,/covers}` | 内嵌封面文件 / 远程封面缓存 | 扫描、封面加载 |
| `logDir` | `dataDir/logs` | 日志目录（当前代码未实际写入文件） | — |
| `libraryFile` | `dataDir/library.json` | `List<Song>` 本地库 | 扫描结束时写；`persist()` |
| `playlistFile` | `dataDir/playlists.json` | `List<Playlist>`（含“我喜欢的音乐”） | 每次歌单变更 |
| `historyFile` | `dataDir/history.json` | `List<UsageEntry>`（最多 600 条） | 每首歌开始播放时 |
| `statsFile` | `dataDir/stats.json` | `List<PlayStat>` | **播放中每 10 秒**（见坑） |
| `settingsFile` | `configDir/settings.json` | `AppSettings` 全量 | **每次设置变更（含音量滑块每动一下）** |
| `searchHistoryFile` | `dataDir/search_history.json` | 已定义但**全仓库未使用**（搜索历史实际存在 `AppSettings.exploreSearchHistory`） | — |
| `accountFile` | `configDir/accounts.json` | 账号 + Cookie，**权限 600** | 登录/登出 |
| `syncConfigFile` | `configDir/sync.json` | GitHub Token/仓库，**权限 600** | 修改同步配置 |
| `downloadCatalogFile` | `dataDir/downloads.json` | `List<DownloadedSong>` | 下载完成/删除 |

写入实现：`JsonFileStore<T>`（core/Storage.kt:46）—— 进程内 `synchronized(lock)`，**整文件读/写**，写入用 `tmp` 文件 + `renameTo` 原子替换；读取失败静默回退默认值（**坏文件会被下次写入覆盖**，不会报错）。JSON 配置：`NeriJson { prettyPrint=true; ignoreUnknownKeys=true; encodeDefaults=true; isLenient=true }`（core/Storage.kt:38）。

### 0.5 线程 / 协程模型（IPC 实现的关键前提）

| 位置 | 行为 |
|---|---|
| `AppContainer.scope` | `CoroutineScope(SupervisorJob() + Dispatchers.Default)`（core/AppContainer.kt:18）。全应用共用一个 scope，**没有 Main dispatcher** |
| UI（Compose） | 在 UI 线程**直接调用**非 suspend 方法（`player.playSongNow`、`playlists.addSongs`…） |
| `LibraryRepository.scan()` | `suspend` + `withContext(Dispatchers.IO)` 收集文件；元数据解析再 `withContext(Dispatchers.Default)`，每 64 个文件更新一次 `scanState`；最后一次性替换 `songs` |
| `OnlineRepository.*` | 全部 `suspend` + `withContext(Dispatchers.IO)` |
| `GitHubSyncManager.*` | `suspend` + `withContext(Dispatchers.IO)` |
| `DownloadManager.enqueue/cancel/retry/…` | 非 suspend，内部在 `scope`（Default）里 `launch`；`runTask` 里的 HTTP 流式下载与 jaudiotagger 写标签**阻塞在 Default 线程**（不是 IO dispatcher） |
| `DownloadManager.repairMetadata()` | `suspend` 但**没有 withContext**，从 UI 调用时会阻塞调用方 dispatcher（当前从 Compose 主线程调用 → 会卡 UI，见第 7 章） |
| `PlayerManager` | 所有方法非 suspend、可任意线程调用；内部 `engine` 用锁 + 独立解码/读数线程；`resolveInput` 用 `Mutex` 串行化（连续切歌会排队） |
| `AudioEngine.snapshot` | `FfmpegAudioEngine` 后台线程每 **200 ms** 更新一次 `positionMs/playing`（core/AudioEngine.kt:439-455）；`buffering` 由 prepare/open 切换 |
| `StatsRepository` 播放计时 | `PlayerManager.init` 里每 **10 秒** `flushListeningTime()`（仅 PLAYING 时），即每 10 秒一次 JSON 全量写盘（core/Player.kt:124-131） |
| 线程安全 | 仓储的 `_state.value` 赋值 + JSON 写入在调用线程同步执行；`JsonFileStore` 有锁，但 `SettingsRepository.update` 的读-改-写不是原子操作，多线程并发 update 有丢失更新风险（当前 UI 单线程可控） |

**给 IPC 的建议**：守护进程用「单线程命令 dispatcher + 有界队列」执行写类命令；在线/同步/扫描/下载用 `CoroutineScope(SupervisorJob() + Dispatchers.IO)`；状态推送从 StateFlow 采样。

---

## 1. 领域 API

> 每个领域固定给出：① 入口类与公开方法 ② StateFlow ③ 数据模型 ④ UI 实际调用入口 ⑤ 坑。
> 方法签名按源码原样抄写；`【suspend】` 表示 `suspend fun`；返回类型省略 `Unit`。

### 1.1 Player（播放器核心）

**入口类**：`PlayerManager`（core/Player.kt:33）。由 `AppContainer.player` 暴露；UI 不直接碰 `AudioEngine`（`player.engine` 仅 tools 用过）。

#### 公开方法（35 个）

| # | 签名 | 说明 | 线程/副作用 |
|---|---|---|---|
| 1 | `attachSnapshotFlow()` | 订阅 `engine.snapshot`，把 `playing/buffering/error` 映射到 `state`。**必须在启动时调用一次**（`bootstrap()` 已调用） | 启动 `scope.launch` collector |
| 2 | `setQueue(songs: List<Song>, startIndex: Int, autoPlay: Boolean = true)` | 整体替换队列并跳到 `startIndex`；`songs` 为空等价 `clearQueue()` | 非 suspend；`autoPlay=true` 会触发异步解析音源 |
| 3 | `playSongNow(song: Song, queue: List<Song>? = null)` | 在 `queue ?: 当前队列`（空则 `[song]`）中找到 song 播放；找不到就播第 0 首 | 同上 |
| 4 | `enqueueNext(songs: List<Song>)` | 插到当前曲目之后；当前无歌时等价 `setQueue(songs,0,true)`；成功后 emit “已添加到下一首播放” | 非 suspend |
| 5 | `enqueue(songs: List<Song>)` | 追加到队尾；当前无歌等价 `setQueue`；emit “已添加到播放队列” | 非 suspend |
| 6 | `removeFromQueue(index: Int)` | 删除某项；删的是当前项则 `playCurrent()`；队列空则 `clearQueue()` | 非 suspend |
| 7 | `moveInQueue(from: Int, to: Int)` | 拖拽排序；按歌曲 key 重新定位 `currentIndex` | 非 suspend |
| 8 | `clearQueue()` | 停播 + 清空队列/当前歌/歌词 + state=IDLE | 非 suspend |
| 9 | `play()` | 播放；若当前歌没有真正 open 过（恢复队列后）或处于 ERROR 会重新解析音源 | 非 suspend，内部异步 |
| 10 | `pause()` | 暂停并结算收听时长 | 非 suspend |
| 11 | `togglePlayPause()` | 播放/暂停 | 非 suspend |
| 12 | `next(userInitiated: Boolean = true)` | 下一首；非用户触发且到队尾且 repeat=OFF 时**暂停在末尾**；用户触发则回绕到第 0 首 | 非 suspend |
| 13 | `previous()` | **播放位置 > 4000 ms 时执行 seekTo(0) 而不是切歌**（与手机端一致） | 非 suspend |
| 14 | `seekTo(positionMs: Long)` | 跳转；会重启 ffmpeg 解码管线（异步、有开销） | 非 suspend |
| 15 | `restartCurrent()` | 从 0 重播当前歌曲（重新解析） | 非 suspend |
| 16 | `【suspend】handleTrackCompleted()` | 解码器播完回调：睡眠定时器 stopAfterCurrent → 暂停；repeat ONE → 重播；否则 `next(false)` | 由 `engine.onCompleted` 在 scope 中调用 |
| 17 | `toggleShuffle()` | 随机开/关并重建随机序 | 非 suspend |
| 18 | `setShuffle(enabled: Boolean)` | 幂等设置随机（MPRIS 用） | 非 suspend |
| 19 | `setRepeatMode(mode: RepeatMode)` | OFF/ALL/ONE | 非 suspend |
| 20 | `cycleRepeatMode()` | OFF→ALL→ONE→OFF（播放页循环按钮） | 非 suspend |
| 21 | `flushListeningTime()` | 把“本次播放片段”的时长写入统计。暂停/切歌/播完/每 10 s 自动调用 | **同步写 stats.json** |
| 22 | `reloadLyrics()` | 失效缓存并重新加载当前歌歌词（异步） | 非 suspend，内部 `scope.launch` |
| 23 | `setVolume(value: Float)` | clamp 到 **0..1**，写引擎 + `settings.volume` | 同步写 settings.json |
| 24 | `setSpeed(value: Float)` | clamp **0.5..3**，写引擎 + settings | 同步写盘；会重启解码管线 |
| 25 | `setPitch(semitones: Float)` | clamp **-12..12**，同上 | 同上 |
| 26 | `setLoudness(enabled: Boolean)` | 响度增强开关，同上 | 同上 |
| 27 | `setEqualizerEnabled(enabled: Boolean)` | 均衡器开关 | 同上 |
| 28 | `setEqualizerPreset(name: String, bands: List<Float>)` | 应用预设（同时 enabled=true，写 equalizerPreset/bands/enabled） | 同上 |
| 29 | `setEqualizerBand(index: Int, gain: Float)` | 单频段调整（preset 记为“自定义”） | 同上 |
| 30 | `resetEffects()` | 重置倍速/变调/响度/均衡器为默认 | 同上（多次写盘） |
| 31 | `startSleepTimer(minutes: Int)` | 倒计时后暂停；每秒更新 `sleepTimer`；结束 emit 消息 | 启动协程 |
| 32 | `stopAfterCurrentSong()` | 播完当前歌暂停 | 非 suspend |
| 33 | `cancelSleepTimer()` | 取消定时器 | 非 suspend |
| 34 | `persistQueueState()` | 把队列前 200 首 + 当前下标 + 当前进度写入 settings（`lastQueue/lastQueueIndex/lastPositionMs`） | **同步写 settings.json**，每次 `playCurrent()` 都调用 |
| 35 | `restoreLastQueue()` | 从 settings 恢复队列/下标/当前歌，state=PAUSED（**不恢复进度、不 open 音源**） | 非 suspend，bootstrap 调用 |

附加公开成员：`val engine: AudioEngine`（内部用）、`val supportsEffects: Boolean`（= 引擎是否支持倍速/变调/均衡器，即 ffmpeg 是否可用）。

#### 公开 StateFlow / SharedFlow（19 + 1）

| 名称 | 类型 | 变化时机 | QML 用途 |
|---|---|---|---|
| `queue` | `StateFlow<List<Song>>` | setQueue/enqueue/remove/move/clear | 播放队列面板 |
| `currentIndex` | `StateFlow<Int>` | 切歌/队列变化；无歌为 -1 | 队列高亮、进度 |
| `currentSong` | `StateFlow<Song?>` | 切歌/恢复队列/清空 | 全局“当前歌曲” |
| `state` | `StateFlow<PlaybackState>` | 解析/开始/暂停/错误/结束 | 播放按钮、窗口标题前缀 |
| `lyrics` | `StateFlow<Lyrics>` | 切歌先放缓存或空，异步加载完成再更新 | 歌词页/悬浮歌词/封面歌词 |
| `lyricsLoading` | `StateFlow<Boolean>` | 异步加载期间 true | 歌词页 loading |
| `coverSeedColor` | `StateFlow<String?>` | 切歌后异步从封面提取（6 位 HEX，无 #）；失败为 null | 动态取色（Theme） |
| `shuffle` | `StateFlow<Boolean>` | toggleShuffle/setShuffle | 随机按钮状态 |
| `repeatMode` | `StateFlow<RepeatMode>` | set/cycle | 循环按钮图标 |
| `volume` | `StateFlow<Float>` | setVolume；初始值来自 settings.volume | 音量面板/迷你条 |
| `speed` | `StateFlow<Float>` | setSpeed | 音效面板 |
| `pitch` | `StateFlow<Float>` | setPitch | 音效面板 |
| `loudness` | `StateFlow<Boolean>` | setLoudness | 音效面板 |
| `equalizerEnabled` | `StateFlow<Boolean>` | setEqualizerEnabled/Preset | 音效面板 |
| `equalizerBands` | `StateFlow<List<Float>>` | setEqualizerBand/Preset/resetEffects | 10 段滑块 |
| `sleepTimer` | `StateFlow<SleepTimerState>` | start/cancel/stopAfterCurrent，倒计时每秒 | 睡眠定时器面板 |
| `buffering` | `StateFlow<Boolean>` | 引擎 snapshot.buffering | 封面 loading 遮罩 |
| `positionMs` | `StateFlow<Long>` | **引擎 200 ms tick**（播放时）；暂停时保持 | 进度条；需节流 |
| `durationMs` | `StateFlow<Long>` | 打开音源/探测时长后 | 进度条总长 |
| `messages` | **`SharedFlow<String>`** | 各种提示：“已添加到播放队列”、“无法获取播放地址…”等；`extraBufferCapacity=8`、**无 replay** | Snackbar 提示；QML 需要可靠投递时改为事件队列 |

#### 数据模型

- `Song`（@Serializable，见 0.3）：`key/source/title/artist/album/durationMs/filePath/remoteId/artworkPath/artworkUrl/lyrics/dateAdded/trackNumber/artistId/albumId/extraId`；派生方法 `displayName()/artistText()/albumText()/isLocalFile()`。
- `PlaybackState`、`RepeatMode`、`SleepTimerState`（非 @Serializable：active/stopAfterCurrent/remainingSeconds/totalSeconds）。
- `EngineSnapshot`（positionMs/durationMs/playing/buffering/error）、`AudioInput`（path/url/headers/target/displayTarget）。

#### UI 实际调用入口

| 文件 | 函数/位置 | 调用了什么（用途） |
|---|---|---|
| `ui/NeriApp.kt` | `AppScaffold` → `MiniPlayer` | `player.currentSong/state/positionMs/durationMs` 收集；`onToggle=player.togglePlayPause()`、`onNext=player.next()`、`onPrevious=player.previous()`、`onSeek=player.seekTo(it)` |
| `ui/NeriApp.kt` | `NeriThemeForSettings` | `player.coverSeedColor`（动态取色种子） |
| `ui/NeriApp.kt` | `LaunchedEffect` | `player.messages.collect` → snackbar |
| `ui/PlayerComponents.kt` | `MiniPlayerCard` | `player.volume/queue/currentIndex/sleepTimer/speed/pitch/loudness/equalizerEnabled/equalizerBands`；`setVolume`、`setSpeed`、`setPitch`、`setLoudness`、`setEqualizerPreset`、`setEqualizerBand`、`resetEffects`、`startSleepTimer`、`stopAfterCurrentSong`、`cancelSleepTimer`、`setQueue(queue,index,true)`、`removeFromQueue`、`moveInQueue`、`clearQueue` |
| `ui/PlayerComponents.kt` | `PlayerProgressBar` | 进度拖动回调 → 外部 `onSeek`（实际绑定 `player.seekTo`） |
| `ui/PlayerComponents.kt` | `LyricsPane` | 行点击 → `player.seekTo(line.timeMs)` |
| `ui/screens/NowPlayingScreen.kt` | `NowPlayingScreen` / `PlayerCore` / `CoverLyricsLine` / `MoreOptionsDialog` | 收集 19 个 flow；`setQueue(queue,index,true)`、`toggleShuffle`、`previous`、`togglePlayPause`、`next`、`cycleRepeatMode`、`seekTo`、`startSleepTimer`、`stopAfterCurrentSong`、`cancelSleepTimer`、`setSpeed/setPitch/setLoudness/setEqualizerPreset/setEqualizerBand/resetEffects`、`enqueueNext`、`enqueue`、`reloadLyrics`、`restartCurrent`、`supportsEffects` |
| `ui/screens/LibraryScreen.kt` | `LocalLibraryContent`（播放全部/随机） | `playSongNow(songs.first(), songs)`、`setQueue(songs.shuffled(),0,true)`、`playSongNow(song,songs)`、`enqueueNext` |
| `ui/screens/LibraryScreen.kt` | 下载分栏 | `playSongNow(song, downloadSongs)` |
| `ui/screens/HomeScreen.kt` | 继续播放/歌曲卡片/最近添加 | `playSongNow(entry.song, continueEntries)`、`playSongNow(song, section.songs)`、`playSongNow(song, recentlyAdded)` |
| `ui/screens/ExploreScreen.kt` | `SearchResults` / `DefaultExploreContent` | `playSongNow(song,payload.songs)`、`enqueueNext(listOf(song))`、`playSongNow(song, songs)` |
| `ui/screens/DetailScreens.kt` | 歌单/歌手/歌单详情/最近/统计 | `setQueue(songs,0,true)`、`setQueue(songs.shuffled(),0,true)`、`playSongNow(song, songs)`、`enqueueNext`、`enqueue` |
| `ui/TrayControlPanel.kt` | `TrayControlPanel` | `currentSong/state/positionMs/durationMs/queue/currentIndex/volume`；`previous/togglePlayPause/next/setVolume` |
| `ui/FloatingLyrics.kt` | `FloatingLyricsWindow` | `currentSong/lyrics/positionMs/state`；`previous/togglePlayPause/next` |
| `ui/ShortcutDispatcher.kt` | `dispatch()` | `togglePlayPause/next/previous/setVolume/seekTo`（音量 ±0.05、快进/后退 5 s） |
| `Main.kt` | 托盘/MPRIS 回调 | `togglePlayPause/previous/next/persistQueueState`、`currentSong/state` |

#### 坑

- **`positionMs` 的推送频率**：引擎 tick 200 ms → 5 Hz；如果也把 `state/queue/volume` 全量广播，注意 QML 侧不要每帧重建列表。
- **`previous()` 语义**：位置 >4 s 时是“回到本曲开头”，不是上一首。新前端要保留这个行为（快捷键/MPRIS/硬件媒体键都走这里）。
- **`next()` 回绕**：用户点下一首在最后一首会回第 0 首（repeat OFF 也如此）；自动播完则停在末尾。IPC 需要区分 `next(userInitiated=true/false)`，建议命令 `player.next` 默认 true，内部事件用 false。
- **`seekTo` 会重启解码管线**（`FfmpegAudioEngine.seekTo → startPipeline`），拖动进度条要防抖，否则会频繁重启 ffmpeg。
- **`setSpeed/setPitch/setLoudness/setEqualizer` 也会重启管线**（`restartPreservingPosition`），滑块拖动时要节流（当前 UI 每 change 都调用）。
- **音源解析异步且带 Mutex**：`setQueue/playSongNow` 立即返回，真正出声要等 `state → PLAYING`；连续快速切歌会排队解析（每次都可能走网络）。
- **错误只通过 `state=ERROR` + `messages` 体现**：`engine.onError` 消息是中文纯文本，没有错误码。QML 想区分错误类型需要新增错误枚举。
- **恢复队列不恢复进度**：`restoreLastQueue()` 忽略 `settings.lastPositionMs`（该字段只写不读），恢复后 state=PAUSED 且音频未 open。
- **`persistQueueState` 每次 `playCurrent` 写 settings.json**（含最多 200 首 Song 的完整 JSON），高频切歌会造成磁盘写入放大。
- **音量 0..1**，但引擎内部 clamp 0..2（MPRIS 也按 0..1 语义）；`setVolume` 同时写 settings。
- **`messages` 无重放**：QML 订阅晚了会丢提示；建议守护进程把 messages 转成带 seq 的事件推送或环形缓冲。
- **歌词加载是“先缓存后异步”**：切歌瞬间 `lyrics` 可能是缓存或空，`lyrics.load` 走网络 + 本地 .lrc/内嵌标签，完成后才更新；`lyricsLoading` 只覆盖异步阶段。
- **统计副作用**：播放会持续写 `history.json`（开始时）与 `stats.json`（每 10 s）——无头守护进程里这两个写操作也应该异步化。

### 1.2 Library（本地媒体库）

**入口类**：`LibraryRepository(settings: SettingsRepository)`（core/Library.kt:65），`AppContainer.library`。

#### 公开方法（7 个）

| # | 签名 | 说明 | 线程/副作用 |
|---|---|---|---|
| 1 | `load()` | 从 `library.json` 读 `List<Song>`，按 `SONG_ORDER`（艺术家→专辑→音轨号→标题）排序，置 `indexLoaded=true` | 同步阻塞读文件，bootstrap 调用 |
| 2 | `persist()` | 把当前歌曲列表写回 `library.json` | 同步写文件 |
| 3 | `byKey(key: String): Song?` | 按 `Song.key` 线性查找（O(n)） | 同步 |
| 4 | `byPath(path: String): Song?` | 按绝对路径查找 | 同步 |
| 5 | `artists(): List<ArtistGroup>` | 按 `artistText()` 分组排序（内存计算 O(n log n)） | 同步 |
| 6 | `albums(): List<AlbumGroup>` | 按 (album, artist) 分组排序 | 同步 |
| 7 | `【suspend】scan(): Int` | 扫描 `settings.musicFolders`，读标签/封面，返回扫描到的歌曲数 | `withContext(IO)`；**可能持续数十秒~数分钟**；内部每 64 个文件更新 `scanState`；结束时整体替换 `songs` 并 `persist()` |

**顶层辅助**：`isSupportedAudioFile(file: File): Boolean` —— 扩展名白名单 20 种：mp3/flac/m4a/aac/ogg/oga/opus/wav/wave/aiff/aif/aifc/wma/ape/mka/mp4/alac/dsf/dff（大小写不敏感）。

#### 公开 StateFlow（3 个）

| 名称 | 类型 | 变化时机 |
|---|---|---|
| `songs` | `StateFlow<List<Song>>` | `load()` 或 `scan()` 完成时整体替换 |
| `scanState` | `StateFlow<ScanState>` | 扫描开始置 `running=true,total=0`；每 64 个文件更新 `processed/found`；结束置 `running=false,finishedAt=now` |
| `indexLoaded` | `StateFlow<Boolean>` | `load()` 后 true（**当前 UI 未使用**，可作为 IPC 的“库就绪”信号） |

#### 数据模型

- `ScanState`（非 @Serializable）：`running: Boolean, total/processed/found: Int, finishedAt: Long, error: String?`，派生 `progress: Float = processed/total`（total=0 时为 0）。
- `ArtistGroup(name: String, songs: List<Song>)`、`AlbumGroup(name: String, artist: String, songs: List<Song>)`（非 @Serializable）。
- `Song`（@Serializable）。本地歌的 `key = "local:<绝对路径>"`、`filePath=绝对路径`、`artworkPath=~/.../cache/NeriPlayer/artwork/<md5(path)>.img`（内嵌封面已抽取到文件）、`dateAdded=文件 lastModified`。

#### UI 实际调用入口

| 文件 | 函数 | 用途 |
|---|---|---|
| `ui/screens/LibraryScreen.kt` | `LibraryScreen` | `library.songs`（列表/过滤/统计）、`library.scanState`（顶部进度条）、`library.scan()`（右上角刷新按钮，经 `container.scope.launch`）、`library.albums()`（专辑分栏） |
| `ui/screens/HomeScreen.kt` | `HomeScreen` | `library.songs`（最近添加 = 按 `dateAdded` 降序取 12） |
| `ui/screens/ExploreScreen.kt` | `ExploreScreen` / `DefaultExploreContent` | `library.songs` 做本地搜索与“本地媒体库”区块 |
| `ui/screens/DetailScreens.kt` | `LocalArtistDetailScreen` | `library.songs` 按 `artistText()` 过滤 |
| `ui/screens/SettingsScreen.kt` | `SettingsScreen` | `library.songs`（总数展示）、`library.scanState`、`library.scan()`（“立即扫描”，在 `container.scope.launch` 中） |
| `Main.kt` | bootstrap 前 | 无直接调用（`AppContainer.bootstrap()` 内部 `library.load()` + `scope.launch { library.scan() }`） |

#### 坑

- **扫描是重 IO**：jaudiotagger 读标签 + 抽取内嵌封面（写文件），大库几百 GB 会很久；`scan()` 没有取消/进度回调以外的机制，**并发调用两次会重复扫描**（没有 running 保护，只有 `scanState.running` 标记）。
- 扫描在 UI 线程之外，但 **`scanState` 每 64 个文件更新一次**，QML 进度条平滑度取决于此。
- 扫描结束才更新 `songs`（中途不增量），所以新扫描期间旧列表仍可用。
- 封面抽取路径是 **绝对文件路径**，QML 端不能直接“读 URL”，需要本地文件访问或 IPC 传字节。
- 目录递归最大深度 12，跳过 `.` 开头的隐藏目录；不处理符号链接循环。
- 扫描时文件夹来自 `settings.current.musicFolders`，即：**改设置后必须重新 scan**。
- `byKey/byPath` O(n)，列表大时不要高频调用（IPC 应提供批量/索引查询）。
- `library.json` 读失败会静默返回空列表（`JsonFileStore` 的行为），坏文件会丢库但不会报错。

### 1.3 Collections（歌单 / 收藏 / 历史 / 统计）

三个独立仓储，都由 `AppContainer` 持有。

#### 1.3.1 `PlaylistRepository`（core/Collections.kt:16）

常量 `FAVORITES_PLAYLIST_ID = "system:favorites"`（“我喜欢的音乐”固定 ID，永远排在列表第一且不可删除）。

**公开方法（14 个）**

| # | 签名 | 说明 |
|---|---|---|
| 1 | `load()` | 读 `playlists.json`，把“我喜欢的音乐”置顶，其余按 `createdAt` 升序 |
| 2 | `userPlaylists(): List<Playlist>` | 非 system 的歌单 |
| 3 | `favorites(): Playlist` | 收藏歌单（不存在则返回内存默认对象） |
| 4 | `byId(id: String): Playlist?` | 查找 |
| 5 | `isFavorite(song: Song): Boolean` | 按 `song.key` 判断 |
| 6 | `createPlaylist(name: String): Playlist` | UUID 生成 id，返回新歌单 |
| 7 | `renamePlaylist(id: String, name: String)` | 改名 + updatedAt |
| 8 | `deletePlaylist(id: String)` | 删除（**system 歌单直接 return，删不掉**） |
| 9 | `addSongs(id: String, songs: List<Song>)` | 按 key 去重追加 |
| 10 | `removeSong(id: String, songKey: String)` | 按 key 移除 |
| 11 | `moveSong(id: String, from: Int, to: Int)` | 歌单内排序 |
| 12 | `toggleFavorite(song: Song): Boolean` | 收藏切换，返回切换后状态 |
| 13 | `upsertFromSync(playlistId: String, name: String, songs: List<Song>, createdAt: Long, updatedAt: Long): Boolean` | 同步导入（存在则覆盖内容，返回是否有变化） |
| 14 | `replaceFavorites(songs: List<Song>)` | 同步导入：整体替换收藏 |

StateFlow：`playlists: StateFlow<List<Playlist>>` —— 以上任何写方法后整体替换并发持久化。

**数据模型**：`Playlist`（@Serializable：id/name/songs/system/createdAt/updatedAt）。`Song` 见 0.3。

#### 1.3.2 `HistoryRepository`（core/Collections.kt:160）

**公开方法（8 个）**

| # | 签名 | 说明 |
|---|---|---|
| 1 | `load()` | 读 `history.json`，按 `playedAt` 降序 |
| 2 | `record(song: Song, playedMs: Long = 0L)` | 记录一次播放：已有条目移到最前、`playCount+1`、更新 `playedAt`；**上限 600 条** |
| 3 | `clear()` | 清空历史（同步写盘） |
| 4 | `removeEntry(songKey: String)` | 移除单条 |
| 5 | `importEntry(song: Song, playedAt: Long, playCount: Int)` | 同步导入，保留原时间戳，`playCount` 取 max |
| 6 | `continuePlaying(limit: Int = 12): List<UsageEntry>` | “继续播放”列表（**当前 UI 未使用**，Home 直接用 entries.take(12)） |
| 7 | `recentlyPlayedSongs(limit: Int = 60): List<Song>` | 最近播放歌曲（**未使用**） |
| 8 | `mostPlayedSongs(limit: Int = 12): List<Song>` | 按 playCount 取最多（**未使用**） |

StateFlow：`entries: StateFlow<List<UsageEntry>>` —— 每次 record/clear/remove/import 后替换。

#### 1.3.3 `StatsRepository(history: HistoryRepository)`（core/Collections.kt:241）

**公开方法（8 个）**

| # | 签名 | 说明 |
|---|---|---|
| 1 | `load()` | 读 `stats.json` |
| 2 | `recordPlay(song: Song)` | 当日 (+1 播放, +0 ms) |
| 3 | `addListenTime(song: Song, deltaMs: Long)` | 追加收听时长（Player 每 10 s 调用一次） |
| 4 | `record(song: Song, listenMs: Long)` | 一次写完播放次数 + 时长 |
| 5 | `clear()` | 清空统计 |
| 6 | `upsertFromSync(songKey: String, day: String, playCount: Int, listenMs: Long)` | 同步导入，按 (歌, 日期) 取 max |
| 7 | `summary(period: StatsPeriod): StatsSummary` | 汇总：plays/listenMs/trackCount |
| 8 | `topSongs(period: StatsPeriod, sort: StatsSort, limit: Int = 50): List<HotSong>` | 排行；需要 history 中能找到歌曲对象 |

StateFlow：`stats: StateFlow<List<PlayStat>>` —— 每次 `mutate` 都会整表替换 + **同步写盘**。

**顶层辅助**：`dayKeyOf(epochMs: Long): String`、`daysBetween(from, to): Long`（当前未被使用）。
**数据模型**：`StatsPeriod{DAY,WEEK,MONTH,YEAR,ALL}`、`StatsSort{PLAY_COUNT,LISTEN_TIME,RECENT}`、`StatsSummary(plays:Int, listenMs:Long, trackCount:Int)`、`HotSong(song, playCount:Int, listenMs:Long, lastPlayed:Long)`（均非 @Serializable）；`PlayStat/UsageEntry/Playlist`（@Serializable）。
**周期定义**：DAY=今天、WEEK=近 7 天、MONTH=近 30 天、YEAR=近 365 天、ALL=全部（`LocalDate.now()` 系统时区）。

#### UI 实际调用入口

| 文件 | 函数 | 调用了什么（用途） |
|---|---|---|
| `ui/screens/LibraryScreen.kt` | `LibraryScreen` | `playlists.playlists`（本地/收藏分栏）；`createPlaylist(name)`（新建歌单对话框）；`favorites()` 通过 `playlists.firstOrNull{it.system}` 取 |
| `ui/screens/LibraryScreen.kt` | `FavoriteContent` | `playSongNow(favorites.first(), favorites)`、收藏歌曲行 |
| `ui/screens/HomeScreen.kt` | `HomeScreen` | `history.entries`（继续播放、最近添加、常听本地）；`history.removeEntry(key)`（继续播放卡片“移除”）；收藏歌单 = `playlists.firstOrNull{it.system}` |
| `ui/screens/DetailScreens.kt` | `LocalPlaylistDetailScreen` | `playlists.playlists`；`setQueue`；`downloads.enqueue(songs, showMessage)`（下载全部）；`playlists.removeSong(id,key)`（从歌单移除）；`playlists.deletePlaylist(id)`（删除歌单确认框） |
| `ui/screens/DetailScreens.kt` | `SongActionsDialog` | `playlists.isFavorite(song)`、`addSongs(playlistId,listOf(song))`、`toggleFavorite(song)`、`createPlaylist("新歌单")` + `addSongs` |
| `ui/screens/DetailScreens.kt` | `RecentScreen` | `history.entries`（列表 + `playSongNow`）、`history.clear()`（清空确认） |
| `ui/screens/DetailScreens.kt` | `StatsScreen` | `stats.stats`、`stats.summary(period)`、`stats.topSongs(period,sort,50)`、`stats.clear()` |
| `ui/screens/NowPlayingScreen.kt` | `PlayerCore` / 添加歌单弹窗 | `playlists.isFavorite(current)`、`toggleFavorite(current)`、`playlists.playlists`（歌单选择）、`addSongs`、`createPlaylist` + `addSongs` |
| `ui/screens/ExploreScreen.kt` | `DefaultExploreContent` | 无直接调用（`library.songs` 本地搜索） |
| `ui/ShortcutDispatcher.kt` | `dispatch` | 无（歌单相关无快捷键） |

#### 坑

- **所有写方法都在调用线程同步写整个 JSON 文件**（`playlists.json/history.json/stats.json`）。UI 拖动/连点会在 UI 线程做磁盘 IO；守护进程必须串行化。
- `HistoryRepository.record` 在**每首歌开始播放时**调用（不是播完），所以“最近播放”包含刚点开的歌。
- `StatsRepository.addListenTime` 每 10 秒写一次盘（播放过程中持续 IO）；`flushListeningTime` 在暂停/切歌/播完时再写一次。
- 统计的 `topSongs` 只会返回**能在 history 中找到歌曲对象**的条目；清空 history 会让统计排行变空（但 stats 数据仍在）。
- `topSongs` 的中文排序/分页没有；limit 默认 50，UI 传 50。
- “我喜欢的音乐”是 `system=true` 的普通 Playlist，**收藏 = 往 favorites 里加歌**；没有跨平台“喜欢的音乐”概念，`favoritePlaylists` 的同步按 `MediaSource` 分组（见 sync）。
- 歌单 `addSongs` 按 `song.key` 去重：同一首歌从不同音源导入（key 不同）会被视为两首。
- `deletePlaylist` 对 system 歌单无效但**不报错**。
- `StatsPeriod` 计算基于系统时区，跨时区/跨天边界需要前端不重复计算。

### 1.4 Online（探索 / 搜索 / 详情 / 歌词 / 播放地址）

**聚合入口**：`OnlineRepository(http: HttpService = HttpService())`（net/OnlineRepository.kt:22），`AppContainer.online`。

#### 公开成员与方法（10 个）

| # | 签名 | 说明 | 线程 |
|---|---|---|---|
| 1 | `val httpService: HttpService` | 暴露给账号/同步复用的同一份 Cookie 容器 | — |
| 2 | `val netease: NeteaseApi` / `val bilibili: BiliApi` | 两个平台客户端（UI 会直接调用它们的部分方法，见下表） | — |
| 3 | `val collectionCovers: StateFlow<Map<String,String>>` | 收藏夹类没有封面时，加载内容后按 id 缓存首条封面 | — |
| 4 | `coverFor(collection: OnlineCollection): String?` | 优先 `collection.coverUrl`，否则查 `collectionCovers` | 同步 |
| 5 | `【suspend】search(source: MediaSource, kind: SearchKind, query: String, page: Int = 1): SearchPayload` | 统一搜索：NETEASE 按 kind 分派（song/playlist/artist/album），BILIBILI 只搜视频，LOCAL/YOUTUBE 返回空 | IO |
| 6 | `【suspend】resolvePlayback(song: Song, quality: String): AudioInput?` | 解析播放源：LOCAL→filePath；NETEASE→songUrl（带 Referer/UA headers）；BILIBILI→videoPages 取 cid + audioUrl（带 bili headers）；YOUTUBE→null | IO |
| 7 | `【suspend】lyrics(song: Song): Pair<String, String?>?` | NETEASE→lyric(id)；BILIBILI→null；LOCAL→用“标题 歌手”搜网易云再取词；返回 (原文, 翻译) | IO |
| 8 | `【suspend】neteaseHome(): NeteaseHomeData` | 首页聚合：热门歌曲 18 + 热歌榜 18 + 推荐新歌 18 + 私人雷达 12 + 榜单 12；内部 6+ 次串行请求；失败时返回 `error` 字段 | IO |
| 9 | `【suspend】playlistSongs(collection): List<Song>` | 按 source 取歌单/收藏夹全部歌曲 | IO |
| 10 | `【suspend】songsForCollection(collection, onPage: (List<Song>) -> Unit = {}): List<Song>` | 歌单/专辑/收藏夹内容；**只有 BILIBILI 分支会逐页回调 onPage**；NETEASE 为空时回退 albumDetail；结束后可缓存收藏夹封面 | IO |
| — | `【suspend】albumSongs(collection)` / `【suspend】artistSongs(artistId: String)` | 网易云专辑 / 歌手热门歌曲（**UI 未直接使用**，仅内部能力） | IO |

#### 平台客户端（UI 会直接调用其中一部分）

`NeteaseApi`（net/OnlineApis.kt:42）14 个公开方法：

| 方法 | 说明 | UI 是否直接调用 |
|---|---|---|
| `searchSongs(query, limit=30, offset=0): List<Song>` | 搜索歌曲；缺封面会用 `/api/song/detail` 批量补齐（最多 50 首） | 否（走 repository） |
| `searchPlaylists(query, limit=30, offset=0): List<OnlineCollection>` | 搜歌单 | **是**（LibraryScreen） |
| `searchArtists(query, limit=30, offset=0): List<OnlineArtist>` | 搜歌手 | **是**（LibraryScreen） |
| `searchAlbums(query, limit=30, offset=0): List<OnlineCollection>` | 搜专辑 | 否 |
| `hotSearchKeywords(): List<String>` | 热门搜索词 | **是**（ExploreScreen） |
| `songUrl(id: String, level: String = "exhigh"): SongUrl?` | 播放地址；level 只映射码率（standard 128k/higher 192k/其余 320k） | 否（player 内部） |
| `lyric(id: String): Pair<String,String?>?` | 歌词 + 翻译 | 否（repository） |
| `playlistDetail(id, limit=Int.MAX_VALUE): Pair<OnlineCollection,List<Song>>?` | 歌单详情；trackIds 与 tracks 数量不符时按 id 批量补齐（200/批） | 否 |
| `albumDetail(id): Pair<OnlineCollection,List<Song>>?` | 专辑详情 | 否 |
| `artistTopSongs(id): List<Song>` | 歌手热门 50 首 | 否（走 `online.artistSongs`） |
| `recommendedNewSongs(limit=12)` / `recommendedPlaylists(limit=12)` / `toplists()` | 推荐新歌/推荐歌单/榜单 | **是**（`recommendedPlaylists` 在 Explore/Library） |
| `userPlaylists(uid): List<OnlineCollection>` | 登录后用户歌单（**需要 Cookie**） | **是**（LibraryScreen） |

`BiliApi`（net/OnlineApis.kt:345）7 个公开方法：

| 方法 | 说明 | UI 是否直接调用 |
|---|---|---|
| `searchVideos(keyword, page=1): List<Song>` | WBI 签名搜索；风控时回退旧接口；失败重试 3 次（`Thread.sleep` 600/1200 ms） | **是**（LibraryScreen） |
| `videoPages(bvid): List<Pair<String,String>>` | 分 P 列表 (cid, part) | 否（player 内部） |
| `audioUrl(bvid, cid): String?` | DASH 音频流地址（取最高 bandwidth）；重试 3 次 | 否 |
| `biliHeaders(): Map<String,String>` | Referer/UA/Origin | 否（repository） |
| `favoriteFolders(mid): List<OnlineCollection>` | 收藏夹列表（**需要登录 Cookie**） | **是**（LibraryScreen） |
| `favoriteFolderSongs(mediaId, maxItems=2000, onPage): List<Song>` | 收藏夹内容，20/页翻页，逐页回调 | 否（songsForCollection） |
| `debugSearch(keyword): String` | 调试字符串（tools 用） | 否 |

`HttpService`（net/Http.kt:72）11 个公开方法：`jarKeyFor(host)`, `cookiesFor(host)`, `cookieSnapshot(key)`, `setCookie(host,name,value)`, `importCookies(key,cookies)`, `clearCookies(key)`, `get(url,headers,timeoutSeconds=15): String?`, `postForm(url,form,headers,timeoutSeconds=15)`, `download(url,headers): ByteArray?`, `execute(method,url,body,headers,timeoutSeconds=30): RawResponse?`（含 `status/bytes/header()`，同步用）, `downloadToFile(url,headers,target,onProgress): Boolean`（流式下载，30 分钟超时）。Cookie 是**内存 `ConcurrentHashMap`**，按 host 归组为 `netease`/`bilibili` 两个 jar；`Set-Cookie` 自动收集；请求自动带 gzip 解压。

#### 数据模型

`SearchKind{SONG,PLAYLIST,ARTIST,ALBUM}`、`SearchPayload(songs, collections, artists)`、`OnlineCollection(id,name,creator,coverUrl?,trackCount,playCount:Long,source,description)`、`OnlineArtist(id,name,avatarUrl?,songCount,source)`、`OnlineTrack(song, albumName, coverUrl?)`、`NeteaseHomeData`（5 个 List + error）、`SongUrl(url, level, type, size:Long)`、`AudioInput`（core）。`OnlineCollection/OnlineArtist/OnlineTrack/SearchPayload/NeteaseHomeData/SongUrl` 均**非 @Serializable**。

#### UI 实际调用入口

| 文件 | 函数 | 调用 | 用途 |
|---|---|---|---|
| `ui/screens/HomeScreen.kt` | `HomeScreen` | `online.neteaseHome()`（IO） | 首页推荐/热歌榜/新歌/雷达/榜单；刷新按钮触发 |
| `ui/screens/ExploreScreen.kt` | `ExploreScreen` | `online.search(source, kind, query)`、`online.netease.hotSearchKeywords()`、`online.netease.recommendedPlaylists(12)` | 搜索 + 默认页热门词/推荐歌单 |
| `ui/screens/ExploreScreen.kt` | `SearchResults` | `playSongNow(song, payload.songs)`、`enqueueNext` | 搜索结果播放 |
| `ui/screens/LibraryScreen.kt` | `LibraryScreen`（网易云分栏） | `online.netease.recommendedPlaylists(12)`、`searchPlaylists(keyword,20)`、`searchArtists(keyword,20)`、`userPlaylists(uid)` | 我的歌单 / 推荐 / 搜索结果 / 歌手 |
| `ui/screens/LibraryScreen.kt` | `LibraryScreen`（B 站分栏） | `online.bilibili.searchVideos(keyword)`、`favoriteFolders(mid)`、`online.coverFor(collection)` | 视频搜索、收藏夹、封面回退 |
| `ui/screens/DetailScreens.kt` | `OnlineCollectionDetailScreen` | `online.songsForCollection(collection, onPage)`、`online.coverFor(collection)`、`playSongNow`、`setQueue` | 歌单详情（B 站收藏夹边加载边显示） |
| `ui/screens/DetailScreens.kt` | `RemoteArtistDetailScreen` | `online.artistSongs(artist.id)` | 歌手热门歌曲 |
| `ui/screens/DetailScreens.kt` | `SongActionsDialog` | 无直接 online 调用（下载走 downloads.enqueue） | — |
| `ui/NeriApp.kt` | `AppScaffold` | 无直接调用（路由到各屏） | — |

#### 坑

- **全部网络方法必须视为“慢 + 可能失败”**：返回 `null`/空列表/`error` 字段，**没有异常**；`runCatching` 已在外层吞掉。IPC 需要把“空结果”和“失败”区分开（当前代码很多地方分不清）。
- **登录 Cookie 决定可用性**：网易云 `userPlaylists`、B 站 `favoriteFolders/favoriteFolderSongs` 未登录返回空；搜索/推荐/歌词/播放地址在未登录下基本可用（音质受限）。
- **B 站接口有风控与阻塞重试**：`Thread.sleep` 在调用线程（IO）上最多阻塞 1.8 s；`buvid3/buvid4` 指纹 Cookie 需先访问主页；WBI 签名密钥有缓存，风控后 `refreshWbiKeys()`。
- **播放地址会过期**：`resolvePlayback` 结果被 Player 缓存在内存 `resolvedCache`（按 `song.key`），长时间暂停后再播可能 403；`downloadToFile` 也可能因地址过期失败（错误信息只说“网络或地址过期”）。
- **封面是 URL，不是字节**：`OnlineCollection.coverUrl/OnlineArtist.avatarUrl/Song.artworkUrl` 都要前端自行下载（UI 的 `Artwork.kt` 带 `Referer: https://music.163.com/` 或 bilibili 站点，缓存到 `covers/`）。B 站图片是 http → 代码会 `normalizeImageUrl` 转 https；部分图片防盗链必须带 Referer。
- **`neteaseHome()` 是 N 次串行请求**（推荐/榜单/新歌/雷达/榜单列表），耗时可能数秒，且没有分步进度；首页加载应异步 + 可重试。
- **`playlistDetail` 对“热歌榜”这类大歌单会补拉歌曲详情**（最多 200/批），可能很多次请求；`limit` 参数能省流量（首页传 30）。
- **搜索结果结构复用**：网易云“搜索歌单/专辑”都塞进 `SearchPayload.collections`，前端需按 `kind` 或 `collection` 字段区分。
- **B 站搜索结果 `durationMs` 来自 "mm:ss" 字符串解析**；收藏夹 `duration` 单位是秒。
- **UI 直接调用了 `online.netease.*` / `online.bilibili.*` 子客户端**（7 处），IPC 设计时要决定：是暴露 `online.netease.xxx` 扁平方法，还是包一层 `online.search*` —— 前者更贴近代码、后者更干净。
- **本地歌曲歌词兜底**是“标题+歌手”搜网易云再取词，会产生网络请求；离线时会走 `Lyrics` 的 missCache 缓存。

### 1.5 Login（网易云 / 哔哩哔哩扫码登录 + 账号态）

**入口**：`NeteaseLogin(online.httpService)`、`BiliLogin(online.httpService)`（net/LoginApi.kt）、`AccountRepository(online.httpService)`（core/Account.kt:34）。分别对应 `AppContainer.neteaseLogin / biliLogin / accounts`。

#### 公开方法

**`NeteaseLogin`（3 个）**

| 签名 | 说明 |
|---|---|
| `createQrCode(): Pair<String, String>?` | 调网易云 unikey 接口，返回 `(key, "https://music.163.com/login?codekey=<key>")`；失败 null |
| `poll(key: String): LoginPollResult` | 轮询：code 800→Expired、801→Waiting(false)、802→Waiting(true)、803→Success(fetchProfile)、网络异常→Failed |
| `fetchProfile(pollObject: JsonObject? = null): AccountInfo?` | 拉账号信息（昵称/UID/头像/vip）；接口失败时若 `MUSIC_U` Cookie 存在则兜底返回“只有 Cookie”的账号 |

**`BiliLogin`（3 个）**

| 签名 | 说明 |
|---|---|
| `createQrCode(): Pair<String, String>?` | 返回 `(qrcode_key, url)` |
| `poll(key: String): LoginPollResult` | code 86101→Waiting(false)、86090→Waiting(true)、86038→Expired、0→Success(fetchProfile) |
| `fetchProfile(): AccountInfo?` | nav 接口 → 成员接口 → SESSDATA Cookie 兜底 |

**`AccountRepository`（5 个 + 1 flow）**

| 签名 | 说明 |
|---|---|
| `load()` | 读 `accounts.json`，把 `cookies` 导入 `HttpService`，**文件权限收紧 600** |
| `accountOf(source: MediaSource): AccountInfo?` | 按 `source.name` 查 |
| `isLoggedIn(source: MediaSource): Boolean` | userId 或 nickname 非空 |
| `save(source: MediaSource, info: AccountInfo, jarKey: String)` | 用 `http.cookieSnapshot(jarKey)` 快照 Cookie，写 accounts.json（600） |
| `logout(source: MediaSource, jarKey: String)` | 清 Cookie + 删账号 + 写盘 |
| `state: StateFlow<AccountStoreData>` | 账号/Cookie 变化 |

**顶层辅助**（net/LoginApi.kt 底部，可离线单测）：`biliAccountFromNav(JsonObject)`、`biliAccountFromMember(JsonObject)`、`parseBiliAccount(navText,memberText,cookies)`、`parseCookieFallbackAccount(source,cookies,cookieName,userId)`、`AccountInfo.displayName()`（昵称→UID→“已登录”）。

#### 数据模型

`AccountInfo`（@Serializable：`source: String`、`nickname`、`userId`、`avatarUrl?`、`vip: Boolean`、`loginAt: Long`）、`AccountStoreData`（@Serializable：`accounts: List<AccountInfo>`、`cookies: Map<String, Map<String,String>>`）。`LoginPollResult` 是 sealed interface（非 @Serializable）：`Expired` / `Waiting(scanned: Boolean)` / `Success(account: AccountInfo)` / `Failed(message: String)`。

#### UI 实际调用入口

| 文件 | 函数 | 调用 | 用途 |
|---|---|---|---|
| `ui/LoginFlow.kt` | `LoginDialog` | `neteaseLogin/biliLogin.createQrCode()`、`.poll(key)`（`delay(2000)` 循环）、`.fetchProfile()`（间接）；成功后 `accounts.save(source, account, jarKey)` | 扫码登录弹窗：二维码渲染（zxing）、状态文案、成功后关闭 |
| `ui/screens/SettingsScreen.kt` | `AccountSettingsSection` | `accounts.state`、`accounts.logout(NETEASE,"netease")`、`accounts.logout(BILIBILI,"bilibili")` | 账号卡片：已登录/未登录、退出登录、发起登录 |
| `ui/screens/LibraryScreen.kt` | `LibraryScreen` | `accounts.state`、`accounts.accountOf(source)`；兜底补拉 `neteaseLogin.fetchProfile()` / `biliLogin.fetchProfile()` 后 `accounts.save(...)` | 进入网易云/B 站分栏时补全 UID 并加载“我的歌单/收藏夹” |
| `ui/NeriApp.kt` | `AppScaffold` | `loginSource` 状态 + `LoginDialog` | 打开登录弹窗 |

#### 坑

- **登录轮询目前在 UI 层**（`LaunchedEffect` + `delay(2000)`），QML 重构时建议**移到守护进程**：命令 `login.start(source)` 返回 `{qrContent, key}`，后续用事件推送 `waiting(scanned)`，成功后推送 `success(account)`。否则前端也要实现两套轮询。
- **Cookie 只在 `accounts.save()` 时快照持久化**：登录成功后必须立即 save；否则进程退出登录态丢失（Cookie 只在内存 HttpService）。
- **`AccountInfo.source` 是 String 不是枚举**（"NETEASE"/"BILIBILI"），而 API 参数用 `MediaSource`；IPC 要统一。
- `jarKey` 是固定字符串 `"netease"` / `"bilibili"`（由 `HttpService.jarKeyFor` 归类），不是 host。
- **`cookies` 字段含敏感凭据**（MUSIC_U/SESSDATA），任何 IPC 推送都要过滤；`accounts.json` 权限 600，但 `settings.json/sync.json` 之外的 settings 是默认权限。
- 二维码内容就是 URL，前端可直接用二维码库渲染；网易云 QR 内容为 `https://music.163.com/login?codekey=...`，B 站是 passport url。
- **登录态失效检测不存在**：接口 401/风控只表现为空列表，不会自动登出。
- 账号信息接口偶发失败会保存“只有 Cookie”的账号，`fetchProfile` 在启动时由 `AppContainer.refreshAccountProfiles()` 补拉（IO）。
- `vip` 字段只用于展示（网易云 vipType>0，B 站 vipStatus>0）。

### 1.6 Download（下载管理 / 离线索引）

**入口**：`DownloadCatalog(catalogFile = AppDirs.downloadCatalogFile)`（core/Download.kt:70）、`DownloadManager(online, settings, catalog, scope, directoryOverride=null, lyricsProvider)`（core/Download.kt:107）。对应 `AppContainer.downloadCatalog / downloads`。

#### 公开方法

**`DownloadCatalog`（6 个）**

| 签名 | 说明 |
|---|---|
| `load()` | 读 `downloads.json`，**过滤掉文件已不存在的记录**，按 songKey 建 Map |
| `record(entry: DownloadedSong)` | 记录/覆盖一条下载（写盘） |
| `remove(songKey: String)` | 删除记录（不删文件） |
| `fileFor(songKey: String): File?` | 取本地文件，**文件不存在返回 null**；Player 用它优先离线播放 |
| `contains(songKey: String): Boolean` | 是否已下载（UI 显示离线标记） |
| `totalSizeBytes(): Long` | 总占用（**UI 未使用**，UI 自己 `sumOf{sizeBytes}`） |

StateFlow：`items: StateFlow<Map<String, DownloadedSong>>`（key = 原歌曲 key）。

**`DownloadManager`（9 个）**

| # | 签名 | 说明 | 线程 |
|---|---|---|---|
| 1 | `downloadDirectory(): File` | 配置目录（空则 `~/Music/NeriPlayer`） | 同步 |
| 2 | `enqueue(songs: List<Song>, showMessage: ((String) -> Unit)? = null): Int` | 入队：跳过 LOCAL、已下载、已在队列；返回新增数；通过**回调**提示“已加入下载队列：N 首”/“这些歌曲都已在本地或下载队列中” | 非 suspend；内部启动 worker 协程 |
| 3 | `cancel(taskId: String)` | 标记取消（下载线程检测后删除半成品） | 同步更新 tasks |
| 4 | `retry(taskId: String)` | 失败/取消的任务重置为 QUEUED 并重启 worker | 非 suspend |
| 5 | `removeFinished()` | 移除 DONE/FAILED/CANCELED，只留 QUEUED/RUNNING | 同步 |
| 6 | `clearAll()` | 取消进行中任务并清空任务列表（**不删已下载文件**） | 同步 |
| 7 | `deleteDownload(songKey: String): Boolean` | 删除本地文件 + catalog 记录，返回文件是否删除成功 | 同步（文件 IO） |
| 8 | `【suspend】repairMetadata(): Int` | 给已下载歌曲补封面/标签/歌词（用 jaudiotagger 重写文件），返回处理条数 | **无 withContext，阻塞调用方** |
| 9 | `buildTargetFile(song: Song, url: String): File` | 生成 `歌手 - 标题.ext`（清理非法字符、限 120 字符） | 同步 |

StateFlow：`tasks: StateFlow<List<DownloadTask>>`、`activeCount: StateFlow<Int>`（QUEUED+RUNNING 数量，UI 角标）。

#### 数据模型

- `DownloadedSong`（@Serializable，13 字段）：`songKey, filePath, title, artist, album, durationMs: Long, artworkUrl?, artworkPath?, lyricsPath?, source: String, sizeBytes: Long, downloadedAt: Long`；`toSong(): Song` 转成可播放本地歌（**保留原 songKey**，所以下载的歌在 UI 上仍显示原音源 key 但 `source=LOCAL`、`filePath` 指向本地）。
- `DownloadState`（enum）：QUEUED/RUNNING/DONE/FAILED/CANCELED。
- `DownloadTask`（非 @Serializable）：`id: String, song: Song, state, downloadedBytes: Long, totalBytes: Long(-1 未知), error: String?, filePath: String?`；派生 `progress: Float`（DONE=1，total>0 时比例）、`hasKnownSize`。

#### UI 实际调用入口

| 文件 | 函数 | 调用 | 用途 |
|---|---|---|---|
| `ui/DownloadPanel.kt` | `DownloadPanel` | `downloads.tasks`、`downloadCatalog.items`、`downloads.downloadDirectory()`、`cancel/retry/deleteDownload`、`repairMetadata()`、`removeFinished()`、`clearAll()`、`openDirectory(dir,showMessage)` | 下载管理面板：进度条、取消/重试/删除、补齐标签、清空、打开目录、`formatSize` |
| `ui/DownloadSettings.kt` | `DownloadSettingsSection` | `downloadCatalog.items`、`downloads.downloadDirectory()`、`downloadCatalog.remove(songKey)`（清理失效记录） | 设置页下载目录/并发/音质/占用 |
| `ui/screens/LibraryScreen.kt` | 下载分栏 | `downloadCatalog.items`（列表）、`deleteDownload(song.key)`、`downloads.downloadDirectory()` | 媒体库“下载”分栏 |
| `ui/screens/LibraryScreen.kt` | 顶部动作 | `downloads.activeCount`（角标）、`downloadCatalog.items`（已下载数 tooltip） | 下载入口徽标 |
| `ui/screens/DetailScreens.kt` | `LocalPlaylistDetailScreen` / `SongActionsDialog` | `downloads.enqueue(songs, showMessage)`（下载全部）、`enqueue(listOf(song),showMessage)`、`deleteDownload(song.key)`、`downloadCatalog.contains` | 歌单批量下载 / 单曲下载与删除 |
| `ui/screens/DetailScreens.kt` | 歌单/收藏/歌单详情行 | `downloadCatalog.contains(song.key)` → 行尾离线图标 | — |
| `Main.kt` / `ui/TrayControlPanel.kt` | 托盘 | `AppIntents.openDownloads` → NeriApp 打开 `DownloadPanel` | 入口 |

#### 坑

- **`tasks` 只存在内存**：进程重启后下载面板为空（catalog 里仍有已下载记录，媒体库“下载”分栏能看到）。IPC 若要恢复“未完成任务”需要自己持久化。
- **下载进度更新极频繁**：`downloadToFile` 每 64 KB 回调一次 → `_tasks.value` 整表复制替换；QML 必须节流（建议 200–500 ms）。
- **下载 worker 并发数来自 `settings.downloadConcurrency`（1..4）**，运行中改设置不会调整已有并发（`startWorkers` 每轮重新读）。
- **`enqueue` 的提示走回调 `showMessage`**，不是 `player.messages`；IPC 里建议统一改成事件。
- **下载完成会写标签/封面/歌词**（jaudiotagger，`DownloadMetadataWriter`，支持 mp3/m4a/mp4/flac/ogg/oga），封面同时另存为 `<同名>.jpg/png/webp`、歌词存 `<同名>.lrc`；写标签失败**不影响音频文件**（音频已下载成功）。
- **下载文件重名会覆盖**（同一“歌手 - 标题”）；没有重名检测。
- **`repairMetadata()` 阻塞**：从 Compose 主线程调用会卡 UI（当前实现如此）；守护进程必须放 IO。
- **`deleteDownload` 删文件 + 记录**，但不删除同名 `.lrc`/封面 sidecar 文件。
- 下载的歌 `source=LOCAL` 但 `songKey` 保留原 key，因此**同步/歌单去重按原 key**；播放时通过 `downloads.fileFor` 命中本地文件。
- `DownloadManager` 的 `lyricsProvider` 由 AppContainer 注入 `{ song -> lyrics.load(song).raw.takeIf{...} }`（**下载时会在 worker 里加载歌词**，可能触发网络）。
- **失败原因只有一句中文**（“无法获取播放地址”/“下载失败（网络或地址过期）”），无错误码。

### 1.7 Sync（GitHub 跨设备同步）

**入口**：`SyncConfigStore`（sync/SyncConfig.kt:29）、`GitHubSyncManager(configStore, playlists, history, stats, library, http)`（sync/GitHubSyncManager.kt:33），对应 `AppContainer.syncConfig / sync`。

#### 公开方法

**`SyncConfigStore`（2 个 + 1 flow）**

| 签名 | 说明 |
|---|---|
| `state: StateFlow<SyncConfig>` | 配置变化 |
| `current: SyncConfig` | 当前值（非 flow 读取） |
| `update(transform: (SyncConfig) -> SyncConfig)` | 修改并写 `sync.json`（**权限 600**） |
| `clear()` | 清空配置但保留 `autoSync` 开关 |

`SyncConfig`（@Serializable）：`deviceId=""`（首次同步时生成 `desktop-xxxxxxxx`）、`token=""`、`owner=""`、`repo=""`、`useDataSaver=false`、`autoSync=true`、`apiBase="https://api.github.com"`、`lastSyncAt=0L`、`lastStatus=""`；派生 `configured`（token+owner+repo 都非空）、`repoFullName`。

**`GitHubSyncManager`（9 个 + 1 flow）**

| # | 签名 | 说明 | 线程 |
|---|---|---|---|
| 1 | `state: StateFlow<SyncState>` | 同步状态 | — |
| 2 | `refreshConfiguredState()` | 从 configStore 刷新 `configured/lastSyncAt/message`（UI 改完配置后调用） | 同步 |
| 3 | `【suspend】verifyToken(token): Result<String>` | 校验 token，返回 GitHub login；无 repo 权限时报错 | IO |
| 4 | `【suspend】listRepos(token): List<String>` | 列出可写仓库 `full_name` | IO |
| 5 | `【suspend】createRepo(token, name): Result<String>` | 创建私有仓库（auto_init），返回 full_name | IO |
| 6 | `【suspend】performSync(): SyncState` | 完整同步：本地快照 → 拉远端 → 合并 → 写回本地 → 上传；冲突自动重试一次（循环 2 次） | IO；**可能数十秒** |
| 7 | `summarize(data: SyncData): String` | 调试摘要（tools） | 同步 |
| 8 | `mergeForTest(local, remote)` / 9 `localSnapshotForTest()` / `importedStatCount(songKey)` | 仅供测试 | — |

`SyncState`（非 @Serializable）：`running: Boolean, configured: Boolean, lastSyncAt: Long, message: String, success: Boolean`。

**同步管线中的其他公开对象**（IPC 一般不需要暴露，但数据格式必须理解）：

- `SyncDataSerializer`（sync/SyncDataSerializer.kt）：`fileNameFor(useDataSaver)`（`backup.json` / `backup-raw.bin`）、`readFallbackFileNames`、`serialize(data,useDataSaver): ByteArray`、`deserialize(bytes): SyncData`、`isBinaryFileName`。三通道：JSON 文本 / 原始 GZIP(ProtoBuf)（0x1F8B 开头）/ 旧版 Base64(GZIP(ProtoBuf))。上传上限 12 MB、解压上限 32 MB。
- `SyncMapping`（sync/SyncMapping.kt）：本地↔同步模型互转；**身份规则 `id|album|mediaUri`**（与手机端一致）；`stableSyncId(localId)`（SHA-256 前 8 字节取正数）、`syncPlaylistId(syncId)="sync:<id>"`、`favoriteSyncId(source)`、`toSyncSong/fromSyncSong/toSyncPlaylist`。
- `SyncMerger`：`merge(local, remote): SyncData`、`equivalent(a,b): Boolean`；策略=歌单按 modifiedAt 取新 + 歌曲并集 + 删除墓碑、收藏按来源合并、最近播放按身份去重取最新、统计按 (identity, day) 取 max。
- `GitHubSyncTransport`：Git 数据接口（blob/tree/commit/ref），写文件带 `expectedHead` 做乐观并发（冲突返回 `SyncWriteResult.Conflict`）；`deleteRepo` 仅测试用。

#### UI 实际调用入口

| 文件 | 函数 | 调用 | 用途 |
|---|---|---|---|
| `ui/SyncSettings.kt` | `SyncSettingsSection` | `syncConfig.state`、`syncConfig.update{...}`、`syncConfig.clear()`、`sync.state`、`sync.verifyToken(token)`、`sync.listRepos(token)`、`sync.createRepo(token,name)`、`sync.performSync()`、`sync.refreshConfiguredState()` | Token 校验、仓库选择/创建、立即同步、自动同步/省流开关、清除配置 |
| `ui/screens/SettingsScreen.kt` | 卡片容器 | 渲染 `SyncSettingsSection` | — |

#### 坑

- **同步是重操作**：`performSync` 串行读远端 + 合并 + 写本地（catalog/history/stats/playlists 全部落盘）+ 上传，可能几十秒；UI 用 `SyncState.running` 显示转圈。IPC 里必须有超时与进度/取消。
- **自动同步在 `AppContainer.observeLocalChangesForAutoSync()` 里**：每 15 s 检查一次「歌单/历史/统计条目数」是否变化（只比较 size！同数量内容变化不会触发），且启动后第一次循环只记基线；`autoSync && configured` 才执行。**这个机制搬到守护进程要保留**。
- **冲突重试**：`expectedHead` 不一致 → 重新拉取合并，最多 2 次；第二次失败返回 `success=false`。
- **Token 明文存 `~/.config/NeriPlayer/sync.json`（600）**；绝不能通过 IPC 回传完整 token（读取时应打码）。
- **同步文件与手机端共用**：文件名/通道/字段必须一致（`backup.json` / `backup-raw.bin` / `backup.bin`，`SYNC_DATA_VERSION="2.0"`）；`useDataSaver` 两端必须一致才能读到最新数据（finder 会回退其他通道只读）。
- **`Long` 同步 ID 超 2^53**（见 0.3）；JSON 通道虽然 kotlinx 写数字，但手机端按 Long 解析；QML 侧只做透传时也应转字符串。
- **本地数据写回**会调用 `playlists.upsertFromSync / replaceFavorites / history.importEntry / stats.upsertFromSync`，每个都同步写盘（一次同步多次全量落盘）。
- **`playlists` 同步 ID 匹配**靠 `SyncMapping.playlistSyncId(playlist)`；本地歌单 id 是 UUID，同步 id 是哈希，导入后用 `sync:<id>` 作为本地 id（`syncPlaylistId`）。
- 同步不包含：播放队列、下载记录、账号 Cookie、设置。
- `SyncState.message` 是中文自然语言（“已是最新（远端数据已合并）”、“同步完成：歌单 N 个…”），前端只能直接展示。

### 1.8 Settings（设置仓库）

**入口**：`SettingsRepository`（core/Storage.kt:173），`AppContainer.settings`。

#### 公开成员（2 方法 + 1 flow + 1 属性）

| 签名 | 说明 |
|---|---|
| `state: StateFlow<AppSettings>` | 全部设置；`loadDefaults()` 在构造时执行（见坑） |
| `current: AppSettings` | 当前值（非 collect） |
| `update(transform: (AppSettings) -> AppSettings)` | 读-改-写 + **同步写 settings.json** |
| `replace(next: AppSettings)` | 整体替换 + 写盘 |

持久化：`~/.config/NeriPlayer/settings.json`（**未收紧权限，默认 644**）；读取包一层“默认音乐文件夹”逻辑：若 `musicFolders` 为空且 `~/Music` 是目录，自动填 `[~/Music]`（core/Storage.kt:178-186）。

#### 完整字段表

见 **第 2 章**（65 个字段逐项：类型 / 默认值 / 含义 / UI 修改入口）。

#### UI 实际调用入口

- `settings.state`：16 个 UI 文件 collect（几乎所有页面）。
- `settings.update { it.copy(...) }`：**71 处**，涵盖主题、播放、媒体库、同步、下载、悬浮歌词、快捷键、首页栏目、后台开关等全部设置项（见第 2 章逐字段）。
- `settings.current`：4 处（`downloadConcurrency`、`floatingLyricsEnabled`、`floatingLyricsLocked`、`shortcuts`）。
- 另有通过 Player 方法间接写设置：`setVolume/setSpeed/setPitch/setLoudness/setEqualizer*`。
- `onboardingAccepted` 由首次启动的“使用须知”弹窗写入（`NeriApp`）。
- `trayHintShown` 由 `Main.kt` 关闭到托盘时写入。

#### 坑

- **每次 update 都同步写整个 settings.json**（prettyPrint 全量、`lastQueue` 最多 200 首 Song）→ 拖动音量/倍速滑块会连续写盘。守护进程里建议改成防抖（如 300 ms 合并写）或把“高频设置”与“低频设置”分离。
- **`update` 是读-改-写，非原子**：多线程并发 update 会丢更新（当前 UI 单线程掩盖了这个问题）。IPC 多客户端/事件循环要串行化。
- 设置文件**读取失败静默回退默认值**（`JsonFileStore.load`），用户设置会“消失”；且下一次写盘会覆盖坏文件。
- `settings.json` 权限未收紧（只有 accounts.json/sync.json 是 600），**未来若把 Cookie/Token 放进来要注意**。
- 默认音乐文件夹自动注入只在 `musicFolders` 为空时发生一次（构造时），删空后不会再次注入（除非重启且仍为空）。
- `lastQueue/lastQueueIndex/lastPositionMs` 存在设置里，进度字段**实际从未被读取**（死字段）。
- 有 10 个设置字段**当前 UI 完全没用到**（见第 2 章“未使用/半使用”标注）：`customSeedColors`、`youtubeEnabled`、`defaultStartTab`、`libraryViewMode`、`cacheLimitMb`、`downloadNotifyOnComplete`、`floatingLyrics*` 的部分也没有全部暴露、`lastQueue*`。IPC 可以先不实现，但字段要保留（手机端/未来兼容）。
- `neteaseEnabled/bilibiliEnabled` 只被 UI 用来决定**是否请求/展示**，**核心层没有检查**——关掉开关也不会阻止播放。
- `qualityPreference` 只影响在线播放解析；`downloadQuality` 只影响下载；两套独立。

### 1.9 Theme（动态取色 / 主题种子）

**没有独立的核心服务**：主题全部由设置字段 + `PlayerManager.coverSeedColor` 派生，UI 侧用 `materialkolor` 生成 Material3 配色（Compose 专属，Qt 端要自己实现等价算法）。

**核心提供的数据**：

| 来源 | 类型 | 说明 |
|---|---|---|
| `AppSettings.themeSeedColor` | `String`（6 位 HEX，无 `#`） | 手动主题种子，默认 `"0061A4"` |
| `AppSettings.dynamicColor` | `Boolean`，默认 false | 是否跟随封面取色 |
| `AppSettings.darkMode` | `DarkModeSetting{AUTO,LIGHT,DARK}`，默认 AUTO | 深浅色 |
| `AppSettings.paletteStyle` | `String`，默认 `"TonalSpot"` | 9 种调色风格 |
| `AppSettings.colorSpec` | `String`，默认 `"SPEC_2021"` | `SPEC_2021` / `SPEC_2025` |
| `AppSettings.customSeedColors` | `List<String>`，默认空 | **UI 未使用**（死字段） |
| `PlayerManager.coverSeedColor` | `StateFlow<String?>` | 当前歌曲封面提取的 6 位 HEX（异步，失败 null） |

**UI 侧实现**（`ui/theme/Theme.kt`，共 256 行）：`PRESET_SEED_COLORS`（8 色）、`PALETTE_STYLES`（TonalSpot/Neutral/Vibrant/Expressive/Rainbow/FruitSalad/Monochrome/Fidelity/Content）、`COLOR_SPECS`、`sanitizeSeedColorHex()`、`seedColorArgb()`、`buildDynamicScheme()`（HCT + material-color-utilities）、`NeriTheme(seedColorHex, isDark, paletteStyle, colorSpec, content)`、颜色动画 420 ms。`NeriThemeForSettings(container)` 决定种子：`dynamicColor && coverSeedColor!=null ? coverSeedColor : themeSeedColor`。

**UI 修改入口**：`ui/screens/SettingsScreen.kt` → 主题卡片（深浅色 FilterChip、动态取色 Switch、8 个预设色圆点、自定义 HEX 输入框 + “应用”、调色风格、色彩空间）；`NeriApp.kt`/`FloatingLyrics.kt`/`TrayControlPanel.kt` 共用 `NeriThemeForSettings`。

**坑**

- **取色是核心侧异步计算**：`PlayerManager.updateCoverSeed` 读 `artworkPath` 文件，或下载 `artworkUrl` 到 `coverDir`，用 ImageIO 采样 32×32 找“色度+亮度”最高分像素，返回 `%06X`；结果按歌曲 key 缓存在内存 `seedCache`。QML 端要么消费 `coverSeedColor` 事件，要么自己算（会得到不同结果，因为算法是自定义的）。
- **`coverSeedColor` 只随当前歌曲变化**，无封面时 null → 主题回退 `themeSeedColor`。
- `materialkolor` 是 Compose/JVM 库，Qt 端需要等价实现（Material 3 HCT scheme）；如果 QML 侧不能完全复刻，至少保证 8 个预设色 + 种子色一致。
- 主题动画（420 ms 颜色渐变）是 UI 行为，不属于核心 API。
- Qt 的 `QT_SCALE_FACTOR` 环境变量在这里**只被 UiScale 当作缩放来源参考**（见 1.11），不参与主题。

### 1.10 Tray + MPRIS + Shortcuts（系统集成）

这三大块都依赖桌面环境（D-Bus / 托盘 / portal），是“前端换成 Qt 后归属最需要先定”的部分。

#### 1.10.1 MPRIS（core/Mpris.kt）

常量：`MPRIS_BUS_NAME="org.mpris.MediaPlayer2.neriplayer"`、`MPRIS_OBJECT_PATH="/org/mpris/MediaPlayer2"`。

`MprisService` 构造参数全是回调（由 AppContainer 注入 player/volume/shuffle/repeat 的读写）：

| 公开成员 | 说明 |
|---|---|
| `start(): Boolean` | 连接会话总线、导出对象、请求 bus name；失败返回 false 且不影响其它功能 |
| `stop()` | 断开连接 |
| `refresh(vararg properties: String)` | 广播 `PropertiesChanged`（默认 PlaybackStatus/Metadata/Volume/LoopStatus/Shuffle；Seek 后还会发给根接口） |
| `running: Boolean` | 是否已注册 |
| `var onRaise: (() -> Unit)?` / `var onQuit: (() -> Unit)?` | 根接口 Raise/Quit 回调（Main.kt 注入“恢复窗口/退出”） |
| `NowPlayingSnapshot` / `buildNowPlayingSnapshot(song,durationMs,positionMs,playing)` | 顶层快照构造 |

D-Bus 接口：根接口 `Raise()/Quit()`，播放器接口 `Next/Previous/Pause/PlayPause/Stop/Play/Seek(offset:Long µs)/SetPosition(trackId, position:Long µs)/OpenUri(uri)`（`OpenUri` 空实现）；属性 `PlaybackStatus/LoopStatus/Rate/Shuffle/Metadata/Volume/Position/Can*`。
**单位注意**：MPRIS 用**微秒**，`Seek(offset)` → `onSeekBy(offset/1000)` → `player.seekTo(positionMs + deltaMs)`；`SetPosition(position)` → `position/1000`；`Position` 属性 = `positionMs*1000`；`mpris:length` = `durationMs*1000`。`LoopStatus` 映射：Track↔ONE、Playlist↔ALL、None↔OFF。`Metadata` 的 `mpris:artUrl` 直接用 `song.artworkUrl`（**没有封面时不提供**）；`xesam:url = neriplayer://<source>/<trackId>`。

UI 调用：`Main.kt` 注册 `mpris.onRaise/onQuit`；`AppContainer.startMprisBridge()` 监听 player 状态变化自动 refresh + 按 `settings.mprisEnabled` start/stop；`ui/BackgroundSettings.kt` 显示 `mpris.running` 并修改 `mprisEnabled`。

#### 1.10.2 托盘（core/StatusNotifier.kt + ui/DesktopTray.kt + ui/TrayControlPanel.kt）

`StatusNotifierService`（269 行，**原生 SNI + DBusMenu**）：

| 公开成员 | 说明 |
|---|---|
| `start(): Boolean` | 检测 `python3 -c "import gi"`；导出 `org.neriplayer.Tray`；启动打包在 jar 里的 `neriplayer-tray-bridge.py`（子进程），8 s 内等待“已注册托盘指示器” |
| `stop()` | 杀桥接进程 + 断开总线 |
| `refresh()` | 空实现（桥接进程自己轮询菜单模型） |
| `running: Boolean` | bridge 进程存活 |

构造回调：`title/playing/floatingLyricsEnabled/floatingLyricsLocked` 提供状态，`onTogglePlay/onPrevious/onNext/onShowWindow/onToggleFloatingLyrics/onToggleFloatingLyricsLock/onOpenDownloads/onOpenSettings/onOpenPanel/onQuit` 处理菜单项。菜单项 ID（`TrayMenuIds`，私有）：1 播放/暂停、2 上一首、3 下一首、4 分隔、5 显示主窗口、6 悬浮歌词、7 下载管理、8 设置、9 分隔、10 控制面板、12 退出、13 固定歌词位置。托盘图标标题/菜单文案随播放状态刷新。

回退路径：`ui/DesktopTray.kt` 的 AWT `TrayIcon`（无菜单，点击左/右键都打开 `TrayControlPanel`；气泡通知 `notify(title,message)`；tooltip）。`ui/BackgroundTray.kt`：`isTrayAvailable()`、`AppTray`（安装托盘 + tooltip）、`SongChangeNotifier`（窗口隐藏时歌曲变化发通知）。

`TrayControlPanel`（ui/TrayControlPanel.kt，499 行）：独立无边框透明窗口（340×436 × 界面缩放），内容=封面/歌名/进度/上一首/播放/下一首/音量/显示主窗口/悬浮歌词/下载/设置/退出；贴着鼠标弹出，鼠标移开 1.2 s 自动收起（Wayland 下禁用自动收起）。

**UI 修改入口**：`Main.kt` 创建 `StatusNotifierService` 与 AWT 托盘；`ui/BackgroundSettings.kt` 开关 `closeToTray/minimizeToTray/notifyOnSongChange/mprisEnabled`；`AppIntents`（ui/AppIntents.kt）7 个回调把“显示/隐藏主窗口、打开设置/下载、切换控制面板、退出、打开系统全局快捷键设置”暴露给托盘/MPRIS/快捷键。

#### 1.10.3 快捷键（core/Shortcuts.kt + core/GlobalShortcutsPortal.kt + ui/ShortcutDispatcher.kt + ui/ShortcutSettings.kt）

`ShortcutAction` 枚举（16 个），每项含：`id`、`label`（中文）、`category`（播放控制/功能与窗口/页面导航）、`systemKey`（基础键只读）、`globalSupported`（能否注册全局）、`defaultLocalBinding`、`defaultGlobalBinding`。

| id | 标签 | systemKey | globalSupported | 默认应用内 | 默认全局 |
|---|---|---|---|---|---|
| `play_pause` | 播放 / 暂停 | ✅ | ✅ | Space | Ctrl+Shift+Space |
| `next_song` | 下一首 | ❌ | ✅ | MediaNext | Ctrl+Shift+→ |
| `previous_song` | 上一首 | ❌ | ✅ | MediaPrevious | Ctrl+Shift+← |
| `volume_up` / `volume_down` | 音量 +/− | ❌ | ✅ | Ctrl+↑ / Ctrl+↓ | 未设置 |
| `seek_forward` / `seek_backward` | 快进/后退 5 秒 | ❌ | ❌ | Ctrl+→ / Ctrl+← | — |
| `toggle_floating_lyrics` | 开关悬浮歌词 | ❌ | ✅ | Ctrl+L | Ctrl+Alt+L |
| `toggle_floating_lyrics_lock` | 固定歌词位置 | ❌ | ✅ | Ctrl+Alt+L | Ctrl+Alt+Shift+L |
| `open_now_playing` | 打开播放页 | ❌ | ✅ | Ctrl+P | Ctrl+Alt+P |
| `toggle_tray_panel` | 打开/关闭控制面板 | ❌ | ✅ | Ctrl+M | Ctrl+Alt+M |
| `toggle_downloads` | 打开下载管理 | ❌ | ❌ | Ctrl+D | — |
| `nav_back` | 返回上一页 | ✅ | ❌ | Esc | — |
| `nav_home` / `nav_explore` / `nav_library` / `nav_settings` | 页面导航 | ❌ | ❌ | Ctrl+H / Ctrl+N / Ctrl+J / Ctrl+S | — |

`ShortcutBinding`（@Serializable）：`keyCode: Long`（Compose 打包 keyCode，低 32 位有效）、`ctrl/alt/shift/meta: Boolean`；`isBound/normalizedKeyCode/matches(KeyEvent)/display()`；companion `NONE`、`bound()`、`fromEvent()`、`isModifierKey()`。
`ShortcutSettings`（@Serializable，嵌在 AppSettings）：`local: Map<String,ShortcutBinding>`、`global: Map<String,ShortcutBinding>`；`localBindingFor/globalBindingFor/withLocal/withGlobal/clearLocalOverride/clearGlobal/localConflict/globalConflict/activeGlobalBindings`。
`ShortcutDispatcher.dispatch(action, container): Boolean` 是**动作执行入口**：播放类直接调 player；音量 ±0.05、快进/后退 5 s；悬浮歌词开关走 settings；导航/托盘面板/下载通过 `onNavigate/onToggleTrayPanel/onOpenDownloads` 回调交给 UI（`capturePaused` 供录制快捷键时暂停分发）。`keyDisplayName(rawKeyCode)` 生成“Ctrl+Shift+L”文案。

`GlobalShortcutsPortal(onActivated: (String) -> Unit)`：`registerDefaults(): Boolean`（按 `defaultGlobalBinding` 注册全部 globalSupported 动作）、`register(bindings): Boolean`、`openConfigure(): Boolean`（portal ≥1.21 的 `ConfigureShortcuts`，GNOME 可能失败）、`stop()`、`isActive`。流程：Registry.Register(appId="neriplayer") → CreateSession（15 s 超时）→ BindShortcuts（120 s 超时，等待用户授权）→ 监听 Activated（**120 ms 去抖**）。**依赖 `~/.local/share/applications/neriplayer.desktop` 存在**（`Main.ensureDesktopFile()` 启动时补写）。Wayland 下这是唯一跨桌面方案；失败静默降级为仅应用内快捷键。

`SingleInstance.acquire(): Boolean`（core/SingleInstance.kt）：用 MPRIS bus name 判断是否已有实例，有则调用远端 `Raise()` 唤起并返回 false（本进程应退出）。**单实例与 MPRIS 强耦合**。

**UI 修改入口**：
- 应用内：`ui/ShortcutSettings.kt`（录制按键、冲突检测、恢复默认）；`Main.kt` `handleShortcut()` 在窗口 `onPreviewKeyEvent` 中匹配；`NowPlayingScreen` 的 Esc 返回单独处理（读 `shortcuts.localBindingFor(NAV_BACK)`）。
- 全局：只读展示（`ShortcutSettings.kt` 右列），改键走系统设置；`Main.kt` 启动时 `globalShortcuts.registerDefaults()`（IO 线程），`AppIntents.openGlobalShortcutSettings` 指向 `openConfigure()`。
- `ui/BackgroundSettings.kt` 不涉及快捷键。

#### 坑

- **MPRIS / 托盘 / 全局快捷键全在“应用进程”内**。Qt 前端 + Kotlin 守护进程之后，建议 **MPRIS 与托盘留在守护进程**（它们本来就是无头可用的），前端只提供 UI；否则 Qt 要分别实现 MPRIS 与 SNI。
- **托盘桥接要求 `python3-gi`**（GIO/GLib）与随包资源 `neriplayer-tray-bridge.py`/`neriplayer-tray.png`；不可用时回退 AWT 托盘（无菜单，点击打开 Compose 自绘面板）。**Qt 版如果自带托盘，就不需要这条回退链**。
- **托盘菜单是“固定项 + 状态勾选”**（没有动态歌名），点菜单项通过 D-Bus 回调；`TrayControlPanel` 是 Compose 自绘窗口，Qt 端要么复刻要么放弃这个面板（功能可并入主窗口）。
- **单实例锁 = MPRIS bus name**：若守护进程不再注册 MPRIS，单实例要另选机制（如 `flock` 锁文件 + Unix socket 占用检测）。
- **全局快捷键依赖 `.desktop` app id**（`neriplayer`），且 `BindShortcuts` 可能弹系统授权框等待用户，**不能假设注册成功**；改键后不会自动重注册（注释明确“改键走系统设置”）。
- MPRIS 单位换算（µs ↔ ms）容易错；`Position` 在暂停时仍返回最后位置（快照来自 StateFlow）。
- `OpenUri` 未实现；`CanControl` 恒 true。
- 快捷键的 `keyCode` 是 Compose 打包值（`(keyLocation<<32)|keyCode`），**Qt/QML 需要另一套键码映射**；`ShortcutBinding.keyCode: Long` 跨框架没有意义，IPC 建议改成“键名 + 修饰键”字符串（如 `"L"`, `ctrl=true`）。
- 托盘/MPRIS 回调最终都落到 `AppIntents` / `ShortcutDispatcher` 这些 **UI 层全局可变单例**；无头化时要把它们改成守护进程的窗口控制命令（`window.show/hide`、`app.quit`）。

### 1.11 Misc（单实例、缩放、环境变量、目录、工具）

| 能力 | 入口 | 公开 API | 说明 |
|---|---|---|---|
| 应用容器/启动序列 | `AppContainer`（core/AppContainer.kt） | `bootstrap(scanLibrary: Boolean = true)`，以及 17 个公开 val（settings/library/playlists/history/stats/online/accounts/syncConfig/downloadCatalog/downloads/mpris/sync/neteaseLogin/biliLogin/lyrics/player/scope） | `bootstrap` 顺序：`library.load() → downloadCatalog.load() → accounts.load() → playlists.load() → history.load() → stats.load() → player.attachSnapshotFlow() → player.restoreLastQueue() → scope.launch(IO){refreshAccountProfiles()} → observeLocalChangesForAutoSync() → startMprisBridge() → if(scanLibrary) scope.launch{library.scan()}` |
| 单实例 | `SingleInstance.acquire()` | 1 个方法 | 见 1.10 坑（依赖 MPRIS） |
| 界面缩放 | `UiScale`（core/UiScale.kt） | `SYSTEM=0f`、`MIN=1f`、`MAX=3f`、`PRESETS: List<Pair<Float,String>>`（跟随系统/100/125/150/175/200/250%）、`systemScale(): Float`、`resolve(setting: Float): Float`、`source: String`（检测来源说明） | 检测顺序：`NERIPLAYER_UI_SCALE` 环境变量 → JVM `sun.java2d.uiScale` → `GDK_SCALE` → `QT_SCALE_FACTOR` → XSETTINGS `gnome.Xft/DPI` → `xrdb -query Xft.dpi` → 平台 defaultTransform；**取候选最大值**；设置值 0=跟随系统 |
| 环境变量 | 见右 | `NERIPLAYER_UI_SCALE`（强制缩放）、`NERIPLAYER_WINDOW_SIZE`（`"1180x820"` 初始窗口尺寸）、`NERIPLAYER_UI_TEST`（UI 脚本路径）、`NERIPLAYER_UI_TEST_EXIT`（脚本后退出）、`GH_TOKEN` / `GH_MOCK_BASE`（仅 syncE2E 测试） | 另有 XDG_DATA_HOME/XDG_CONFIG_HOME/XDG_CACHE_HOME、DISPLAY、WAYLAND_DISPLAY、XDG_SESSION_TYPE 被读取 |
| ffmpeg 检测 | `FfmpegSupport`（core/AudioEngine.kt:63） | `available: Boolean`（lazy，执行 `ffmpeg -version`）、`version: String`（lazy，第一行）、`probeDurationMs(input: AudioInput): Long`（ffprobe） | `createAudioEngine()` 决定用 `FfmpegAudioEngine`（支持倍速/变调/响度/均衡器）还是 `JavaSoundAudioEngine`（不支持 effects，格式少） |
| 音频引擎接口 | `AudioEngine` | `snapshot: StateFlow<EngineSnapshot>`、`supportsEffects: Boolean`、`onCompleted/onError` 回调、`open(input,startMs,durationMs)`、`prepare(durationMs)`、`play/pause/release/seekTo/setVolume/setSpeed/setPitch/setLoudness/setEqualizer` | 实现类 709 行；IPC 不需要暴露，但**守护进程必须保证 ffmpeg 存在**，否则 effects 不可用且部分格式无法播放 |
| JSON 存储 | `JsonFileStore<T>` | `load(): T`、`save(value)` | 见 0.4；`NeriJson` 配置 |
| 解析工具 | `NeriJsonParser` + JsonObject 扩展 | `parse`、`asObject/str/long/int/bool/double/obj/array/objects`、`urlEncode` | 仅内部使用 |
| 元数据写入 | `DownloadMetadataWriter` | `supports(file)`、`write(file,title,artist,album,lyrics,coverBytes,coverMimeType): Result` | 支持容器：mp3/m4a/mp4/flac/ogg/oga |
| 工具/自检（不属于 API） | `tools/SelfTest.kt`(707 行)、`tools/UiScript.kt`(519 行)、`tools/SyncE2ETest.kt`(193 行) | 见第 6 章尾部 | 自检脚本直接调用核心与 UI，无 IPC 价值，但可作为“命令冒烟测试”的参考 |

**UI 实际调用入口（Misc）**：`SettingsScreen` 使用 `AppDirs`（打开数据目录/清封面缓存）、`FfmpegSupport`（音频引擎信息）、`UiScale`（缩放设置项）；`Main.kt` 使用 `NERIPLAYER_WINDOW_SIZE`、`UiScale.resolve`、`ApplyUiScale`；`NeriApp` 使用 `NERIPLAYER_UI_TEST`。

**坑**

- `AppContainer.bootstrap()` **必须只调用一次**（`attachSnapshotFlow` 重复订阅会导致多次 collect；`restoreLastQueue` 会覆盖当前队列）。
- `bootstrap(scanLibrary=true)` 默认会后台扫描；守护进程启动如果要求秒开，建议先 `false`，前端就绪后再发 `library.scan` 命令。
- `UiScale` 的检测依赖 AWT Toolkit / `xrdb`，纯 headless 守护进程里可能拿到 1.0；**缩放实际是 Qt 前端的事**，守护进程只需提供设置值。
- 环境变量 `NERIPLAYER_UI_TEST` 会加载并执行 `UiScript`（会操作 UI、退出进程），生产环境不要设置。
- `gh`/`GH_TOKEN` 仅用于同步 E2E 测试。
- `AppDirs` 用 `System.getProperty("user.home")` 与 XDG 环境变量，在 systemd 服务里要显式传递环境（否则路径会指到服务用户 home）。

## 2. 设置项全表（`AppSettings`）

**仓库**：`SettingsRepository`（core/Storage.kt:173）。**写入方式统一是 `container.settings.update { it.copy(field = value) }`**（71 处），少数通过 Player 的 setter 间接写入；没有 `setXxx` 方法。`onboardingAccepted/trayHintShown/lastQueue*` 由应用逻辑写入。

下表中「UI 修改入口」列：**写法默认是 `settings.update{copy(...)}`**，除非特别注明“经 player”。

### 2.1 主题 / 界面（1–8）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口 |
|---|---|---|---|---|---|
| 1 | `themeSeedColor` | `String` | `"0061A4"` | 主题种子色（6 位 HEX 无 #） | `SettingsScreen` 主题卡片：8 个预设色圆点、自定义 HEX 输入 + “应用”（同时 `dynamicColor=false`） |
| 2 | `customSeedColors` | `List<String>` | `[]` | 自定义种子色列表 | **未使用（死字段）** |
| 3 | `dynamicColor` | `Boolean` | `false` | 是否跟随当前歌曲封面取色 | `SettingsScreen` SwitchRow「动态取色」 |
| 4 | `darkMode` | `DarkModeSetting` | `AUTO` | AUTO/LIGHT/DARK | `SettingsScreen` 深浅色 FilterChip（浅色/深色/跟随系统） |
| 5 | `paletteStyle` | `String` | `"TonalSpot"` | Material3 调色风格（9 种） | `SettingsScreen` 色彩风格 FilterChip |
| 6 | `colorSpec` | `String` | `"SPEC_2021"` | 色彩规范版本 `SPEC_2021`/`SPEC_2025` | `SettingsScreen` 色彩空间 FilterChip |
| 7 | `musicFolders` | `List<String>` | `[]`（首次启动若 `~/Music` 存在则自动填） | 本地扫描目录（绝对路径） | `SettingsScreen` 媒体库卡片：`＋ 添加文件夹`（JFileChooser）、每行「移除」 |
| 8 | `uiScale` | `Float` | `0f` | 界面缩放；0=跟随系统 | `SettingsScreen` 界面缩放 FilterChip（`UiScale.PRESETS`） |

### 2.2 播放（9–20）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口 |
|---|---|---|---|---|---|
| 9 | `volume` | `Float` | `0.85f` | 音量 0..1（初始值） | 经 `player.setVolume`：设置页 Slider、音量面板、迷你条、托盘面板、快捷键、MPRIS |
| 10 | `playbackSpeed` | `Float` | `1.0f` | 默认倍速 0.5..3 | 经 `player.setSpeed`：设置页 Slider、音效面板 |
| 11 | `pitchSemitone` | `Float` | `0.0f` | 变调半音 -12..12 | 经 `player.setPitch`：**仅音效面板**（设置页无此项） |
| 12 | `loudnessEnhancer` | `Boolean` | `false` | 响度增强 | 经 `player.setLoudness`：设置页 Switch、音效面板 Switch |
| 13 | `equalizerEnabled` | `Boolean` | `false` | 均衡器开关 | 经 `player.setEqualizerEnabled`：设置页、音效面板 |
| 14 | `equalizerPreset` | `String` | `"平直"` | 预设名（8 个预设：平直/原声/低音增强/高频增强/人声增强/摇滚/爵士/古典） | 经 `player.setEqualizerPreset` / 单频段调整后变「自定义」 |
| 15 | `equalizerBands` | `List<Float>` | `List(10){0f}` | 10 段增益 dB（31/62/125/250/500/1k/2k/4k/8k/16k Hz） | 经 `player.setEqualizerBand` / `setEqualizerPreset` |
| 16 | `lyricsFontScale` | `Float` | `1.0f` | 歌词字号倍率 | 设置页 Slider（0.7..1.8）；播放页全屏歌词 A+/A-（步进 0.1，范围 0.7..1.8） |
| 17 | `showLyricTranslation` | `Boolean` | `true` | 显示歌词翻译 | `SettingsScreen` SwitchRow |
| 18 | `coverShowsLyrics` | `Boolean` | `true` | 播放页封面下方显示当前歌词行 | `SettingsScreen` SwitchRow |
| 19 | `showNowPlayingTitle` | `Boolean` | `true` | 播放页顶部显示“正在播放 + 音源” | `SettingsScreen` SwitchRow |
| 20 | `songTitleMarquee` | `Boolean` | `true` | 长歌名滚动 | **只写不读（死开关）**：`MiniPlayerCard` 无条件 marquee |

### 2.3 在线 / 启动 / 队列（21–35）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口 |
|---|---|---|---|---|---|
| 21 | `qualityPreference` | `String` | `"exhigh"` | 在线播放音质：standard/higher/exhigh/lossless | `SettingsScreen` 默认音质 FilterChip；`Player` 解析时读；`NowPlayingScreen.qualityLabel` 展示 |
| 22 | `neteaseEnabled` | `Boolean` | `true` | 网易云开关 | `SettingsScreen` 在线音源 Switch；`HomeScreen/ExploreScreen` 用它决定是否请求 |
| 23 | `bilibiliEnabled` | `Boolean` | `true` | 哔哩哔哩开关 | `SettingsScreen` Switch；**UI 其余处未读取，核心不检查** |
| 24 | `youtubeEnabled` | `Boolean` | `false` | YouTube 开关 | **未使用**（设置页的 YouTube 开关硬编码 `checked=false, enabled=false`） |
| 25 | `defaultStartTab` | `String` | `"home"` | 默认启动页 | **未使用**（启动固定 HOME） |
| 26 | `sleepTimerMinutes` | `Int` | `30` | 睡眠定时器默认时长（展示用） | `SettingsScreen` FilterChip 15/30/60/90 |
| 27 | `resumeLastQueue` | `Boolean` | `true` | 启动恢复上次队列 | `SettingsScreen` Switch；`Player.restoreLastQueue` 读 |
| 28 | `lastQueue` | `List<Song>` | `[]` | 上次队列（最多 200 首） | 仅 `player.persistQueueState()` 写 |
| 29 | `lastQueueIndex` | `Int` | `0` | 上次当前下标 | 同上 |
| 30 | `lastPositionMs` | `Long` | `0L` | 上次播放位置 | 同上；**从未被读取（死字段）** |
| 31 | `homeCards` | `HomeCardSettings` | 全 true | 首页卡片开关：`continuePlaying/guessYouLike/hotTracks/radar/recommended` | `SettingsScreen` 首页栏目 4 个 Switch（继续播放/为你推荐/热歌榜/私人雷达）；**只有 `continuePlaying` 被 HomeScreen 读取，其余 4 个是死开关**；`guessYouLike` 无 UI |
| 32 | `libraryViewMode` | `String` | `"list"` | 媒体库视图模式 | **未使用** |
| 33 | `cacheLimitMb` | `Int` | `1024` | 缓存上限 | **未使用**（封面缓存有硬编码 64 MB 预算） |
| 34 | `onboardingAccepted` | `Boolean` | `false` | 是否已同意使用须知 | `NeriApp` 首次启动弹窗「我已了解并同意」写入 |
| 35 | `exploreSearchHistory` | `List<String>` | `[]` | 探索页搜索历史（最多 12 条） | `ExploreScreen` 搜索时写入（去重取前 12）、「清空」按钮 |

### 2.4 悬浮歌词（36–55，全部在 `ui/FloatingLyricsSettings.kt` 有入口）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口（除本条外均在该设置卡片） |
|---|---|---|---|---|---|
| 36 | `floatingLyricsEnabled` | `Boolean` | `false` | 悬浮歌词总开关 | 设置卡片 Switch；快捷键 `toggle_floating_lyrics`；托盘菜单；TrayControlPanel；播放页/迷你条按钮；悬浮窗关闭按钮；`Main.kt` 据此决定是否创建窗口 |
| 37 | `floatingLyricsHideInApp` | `Boolean` | `false` | 主窗口聚焦时隐藏悬浮歌词 | 设置卡片 Switch；`Main` 读取 |
| 38 | `floatingLyricsLocked` | `Boolean` | `false` | 锁定位置（鼠标穿透） | 设置卡片 Switch；快捷键 `toggle_floating_lyrics_lock`；托盘菜单；悬浮窗锁按钮 |
| 39 | `floatingLyricsTextColor` | `String` | `"WHITE"` | 歌词颜色（枚举名：WHITE/BLACK/RED/ORANGE/YELLOW/GREEN/CYAN/BLUE/PURPLE/PINK） | `ColorRow` |
| 40 | `floatingLyricsRenderStyle` | `String` | `"SHADOW"` | SHADOW（阴影）/OUTLINE（描边） | `FilterChip` |
| 41 | `floatingLyricsShadowColor` | `String` | `"BLACK"` | 阴影颜色 | `ColorRow`（SHADOW 时） |
| 42 | `floatingLyricsOutlineColor` | `String` | `"BLACK"` | 描边颜色 | `ColorRow`（OUTLINE 时） |
| 43 | `floatingLyricsFontSize` | `Float` | `30f` | 字号 dp | Slider 14..72 |
| 44 | `floatingLyricsOutlineWidth` | `Float` | `2.0f` | 描边宽度 | Slider 0.5..6 |
| 45 | `floatingLyricsShadowBlur` | `Float` | `6.0f` | 阴影模糊 | Slider 0..20 |
| 46 | `floatingLyricsLyricAlpha` | `Float` | `1.0f` | 主歌词不透明度 | Slider 0.1..1 |
| 47 | `floatingLyricsTranslationAlpha` | `Float` | `0.75f` | 翻译不透明度 | Slider 0.1..1 |
| 48 | `floatingLyricsBackgroundAlpha` | `Float` | `0.35f` | 背景不透明度 | Slider 0..1 |
| 49 | `floatingLyricsBackgroundColor` | `String` | `"BLACK"` | 背景颜色 | `ColorRow` |
| 50 | `floatingLyricsShowTranslation` | `Boolean` | `true` | 显示翻译行 | Switch |
| 51 | `floatingLyricsRevealAnimation` | `Boolean` | `true` | 切行淡入动画 | Switch |
| 52 | `floatingLyricsMaxWidthDp` | `Float` | `900f` | 窗口最大宽度 dp | Slider 320..1600 |
| 53 | `floatingLyricsAlignment` | `String` | `"CENTER"` | LEFT/CENTER/RIGHT | FilterChip |
| 54 | `floatingLyricsPositionX` | `Float` | `0.5f` | 水平位置（屏幕比例 0..1） | Slider；**拖动悬浮窗后回写** |
| 55 | `floatingLyricsPositionY` | `Float` | `0.85f` | 垂直位置（屏幕比例 0..1） | Slider；拖动后回写 |

### 2.5 下载（56–59）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口 |
|---|---|---|---|---|---|
| 56 | `downloadDirectory` | `String` | `""` | 下载目录；空=`~/Music/NeriPlayer` | `DownloadSettingsSection`：选择目录（JFileChooser）/打开目录/恢复默认 |
| 57 | `downloadQuality` | `String` | `"exhigh"` | 下载音质（4 档） | `DownloadSettingsSection` FilterChip |
| 58 | `downloadConcurrency` | `Int` | `2` | 并发下载数 1..4 | `DownloadSettingsSection` FilterChip |
| 59 | `downloadNotifyOnComplete` | `Boolean` | `true` | 下载完成通知 | **未使用（死字段）**，无完成通知实现 |

### 2.6 后台与系统控制（60–64）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口 |
|---|---|---|---|---|---|
| 60 | `closeToTray` | `Boolean` | `true` | 关窗时最小化到托盘 | `BackgroundSettingsSection` Switch；`Main.kt onCloseRequest` 读 |
| 61 | `minimizeToTray` | `Boolean` | `true` | 最小化时隐藏到托盘 | `BackgroundSettingsSection` Switch；`Main.kt LaunchedEffect` 读 |
| 62 | `notifyOnSongChange` | `Boolean` | `true` | 窗口隐藏时歌曲变化发系统通知 | `BackgroundSettingsSection` Switch；`SongChangeNotifier` 读 |
| 63 | `mprisEnabled` | `Boolean` | `true` | 注册 MPRIS | `BackgroundSettingsSection` Switch；`AppContainer.startMprisBridge` 读 |
| 64 | `trayHintShown` | `Boolean` | `false` | 是否已提示过“最小化到托盘” | `Main.kt` 关窗到托盘时写 |

### 2.7 快捷键（65）

| # | 字段 | 类型 | 默认值 | 含义 | UI 修改入口 |
|---|---|---|---|---|---|
| 65 | `shortcuts` | `ShortcutSettings` | `ShortcutSettings()`（local/global 两个空 Map） | 应用内 / 全局快捷键绑定 | `ShortcutSettingsSection`：点击录制、冲突检测、恢复默认（`ShortcutSettings()`）；`Main.kt handleShortcut` 读取匹配 |

### 2.8 设置项小结

- **65 个字段**（含嵌套 `homeCards` 5 个子字段、`shortcuts` 2 个 Map）。
- **UI 完全未使用/只写不读的字段（共 15 个）**：`customSeedColors`、`songTitleMarquee`、`bilibiliEnabled`、`youtubeEnabled`、`defaultStartTab`、`lastQueue`、`lastQueueIndex`、`lastPositionMs`、`libraryViewMode`、`cacheLimitMb`、`downloadNotifyOnComplete`、`homeCards.guessYouLike`、`homeCards.hotTracks`、`homeCards.radar`、`homeCards.recommended`。新前端可以选择补实现或明确标注“未实现”。
- **高频写**：`volume`/`playbackSpeed`（滑块拖动即写盘）、`lastQueue*`（每次切歌）、`exploreSearchHistory`。
- **`pitchSemitone`/`equalizerBands` 只在播放页音效面板修改**，设置页只显示预设。
- 所有字段都参与 JSON 读写（`encodeDefaults=true`），新增字段需给默认值保证旧文件兼容（`ignoreUnknownKeys=true`，旧版本读到新字段会忽略）。

## 3. 事件 / 提示清单（QML 需要订阅的所有“推送”）

核心侧只有两种推送机制：**StateFlow（可合并、只在变化时推）** 与 **SharedFlow（一次性消息）**。UI 侧还有一层 `showMessage: (String) -> Unit` 回调（Snackbar），它不是核心事件，而是**命令的同步返回值/回调**，IPC 里应改成命令返回 + 事件。

### 3.1 状态类（StateFlow，按变化推送）

| 主题 | 来源 | 数据形状 | 触发时机 | 频率 / 量级 | QML 用途 |
|---|---|---|---|---|---|
| 播放器状态 | `player.state` | 枚举 IDLE/PREPARING/PLAYING/PAUSED/ERROR | 解析/开播/暂停/错误/播完/清空 | 每次变化（切歌时数次） | 播放按钮、窗口标题、托盘文案 |
| 播放位置 | `player.positionMs` | `Long` | 引擎 200 ms tick（仅播放中） | **5 Hz**（暂停不更新） | 进度条；建议节流到 4–5 Hz 或 250 ms |
| 总时长 | `player.durationMs` | `Long` | open/探测完成后 | 每次切歌 | 进度条 |
| 缓冲中 | `player.buffering` | `Boolean` | prepare/open/播放/错误 | 随状态 | 封面 loading 遮罩 |
| 当前歌曲 | `player.currentSong` | `Song?` | 切歌/恢复/清空 | 每次切歌 | 全局当前曲目 |
| 队列 | `player.queue` / `player.currentIndex` | `List<Song>` / `Int` | set/enqueue/remove/move/clear | 每次队列操作 | 队列面板 |
| 随机/循环 | `player.shuffle` / `player.repeatMode` | `Boolean` / 枚举 | 用户操作/MPRIS | 低频 | 按钮状态 |
| 音量 | `player.volume` | `Float` | 用户操作/MPRIS/快捷键 | 低频（拖动时高频） | 音量条 |
| 音效 | `player.speed/pitch/loudness/equalizerEnabled/equalizerBands` | Float/Float/Bool/Bool/List\<Float\> | 音效面板操作 | 低频（滑块拖动高频） | 音效面板 |
| 睡眠定时器 | `player.sleepTimer` | `SleepTimerState` | 启动后**每秒**、取消、结束 | **1 Hz** | 倒计时显示 |
| 歌词 | `player.lyrics` | `Lyrics`（raw/lines/translated/source） | 切歌（先缓存/空）→ 异步加载完成 | 每首歌 1–2 次 | 歌词页/悬浮歌词/封面歌词 |
| 歌词加载中 | `player.lyricsLoading` | `Boolean` | 异步加载期间 | 每首歌 | loading |
| 封面种子色 | `player.coverSeedColor` | `String?`（6 位 HEX） | 切歌后异步提取/失败置 null | 每首歌 1 次（内存缓存命中则立即） | 动态取色 |
| 媒体库 | `library.songs` | `List<Song>` | load/scan 完成 | 启动 1 次 + 每次扫描 | 列表/搜索/统计 |
| 扫描进度 | `library.scanState` | `ScanState`（running/total/processed/found/finishedAt/error） | 扫描开始、**每 64 个文件**、结束 | 大库可达数百次 | 进度条 + “正在扫描 N/M” |
| 歌单 | `playlists.playlists` | `List<Playlist>` | 任何歌单写操作 | 低频 | 歌单列表/收藏 |
| 历史 | `history.entries` | `List<UsageEntry>` | 每首歌开始播放（record）/清空/删除/同步导入 | **每首歌 1 次**（播放开始时） | 继续播放/最近播放 |
| 统计 | `stats.stats` | `List<PlayStat>` | 每次播放/每 10 秒听歌计时/清空/同步 | **1/10 s + 每次切歌** | 统计页 |
| 下载任务 | `downloads.tasks` | `List<DownloadTask>` | 入队/状态变化/**每 64 KB 下载进度** | **可达数十 Hz**（强依赖网速） | 下载面板进度；**必须节流** |
| 下载中数量 | `downloads.activeCount` | `Int` | QUEUED+RUNNING 数变化 | 低频 | 媒体库角标 |
| 已下载索引 | `downloadCatalog.items` | `Map<String, DownloadedSong>` | 下载完成/删除/启动 load | 低频 | 离线标记/下载分栏 |
| 账号 | `accounts.state` | `AccountStoreData` | 登录/登出/load | 低频 | 账号卡片 |
| 在线封面缓存 | `online.collectionCovers` | `Map<String,String>` | 加载无封面收藏夹后 | 少量 | 收藏夹封面 |
| 同步状态 | `sync.state` | `SyncState`（running/configured/lastSyncAt/message/success） | 开始同步/结束/失败 | 每次同步 2–3 次 | 同步卡片转圈/文案 |
| 同步配置 | `syncConfig.state` | `SyncConfig` | 改 Token/仓库/开关 | 低频 | 同步设置表单（**token 需打码**） |
| 设置 | `settings.state` | `AppSettings` | 任何设置修改 | 低频（但音量/倍速滑块拖动时高频） | 全部设置项；**建议按字段 diff 或分主题推送** |
| MPRIS | `mpris.running` | `Boolean` | start/stop | 低频 | 后台设置页状态 |

### 3.2 消息类（SharedFlow / 回调）

| 主题 | 来源 | 数据形状 | 触发时机 | 频率 | QML 用途 |
|---|---|---|---|---|---|
| 播放器提示 | `player.messages`（SharedFlow\<String\>，**无 replay、buffer 8**） | 纯文本中文 | 见下表 6 条 | 低频 | Snackbar |
| 下载提示 | `DownloadManager.enqueue(songs, showMessage)` 的回调 | 纯文本 | 入队成功/全部已存在 | 每次入队 | Snackbar |
| 登录状态 | UI 轮询（`LoginDialog` 每 2 s） | 文本 status + `LoginPollResult` | 扫码/确认/成功/过期/失败 | **0.5 Hz** | 登录弹窗文案 |
| 扫描提示 | UI `showMessage` | “开始扫描本地音乐…”/“扫描完成，共 N 首歌曲” | 手动扫描 | 2 次 | Snackbar |
| 错误 | `player.messages` + `player.state=ERROR` | 文本 | 音频设备/解码器/播放地址失败 | 偶发 | Snackbar + 错误态 |
| 系统通知 | `DesktopTray.notify(title,message)` | 文本 | 窗口隐藏时歌曲变化 | 每首歌 | 桌面通知（若前端保留托盘） |
| 同步提示 | `sync.state.message` + UI `showMessage(result.message)` | 文本 | 同步结束 | 每次同步 | Snackbar/同步卡片 |

`player.messages` 的全部 6 个 emit 点（core/Player.kt）：

| 文本 | 触发 |
|---|---|
| `已添加到下一首播放` | `enqueueNext` 成功 |
| `已添加到播放队列` | `enqueue` 成功 |
| `无法获取播放地址，请稍后重试` | `playCurrent` 解析音源失败 |
| `睡眠定时器已结束，播放已暂停` | 倒计时结束 |
| `播完当前歌曲后停止` | `stopAfterCurrentSong` |
| `已播放完当前歌曲，播放暂停` | 播完最后一首且 stopAfterCurrent |

此外 **UI 自己产生的 40+ 条 `showMessage` 文本**（歌单创建/删除、收藏、下载、设置保存、登录、快捷键冲突、Token 校验…）属于“命令完成提示”，IPC 建议由**命令返回 `{message}` 或 `{ok, message}`**，由 QML 统一弹 Toast，不要做成全局事件。

### 3.3 事件设计注意事项

- **位置与下载进度必须节流**：position 5 Hz 可接受；`downloads.tasks` 每 64 KB 变化一次，1 MB/s 网络就是 16 Hz 且每次带整个任务列表 → 建议 daemon 侧 200–500 ms 合并推送，或只推 `{taskId, downloadedBytes, totalBytes, state}` 增量。
- **设置变化会触发全量 `AppSettings`**：QML 每次都要重新对比；建议 daemon 额外推 `settings.patch`（字段名 → 新值）。
- **歌词是两段式**：`lyrics` 先推缓存/空，再推加载完成；QML 需要 `lyricsLoading` 区分“暂无歌词”与“正在加载”。
- **`SharedFlow` 无 replay**：daemon 不能直接用 SharedFlow 对接 socket，必须自己维护有序事件队列 + seq，客户端断线重连按 seq 补偿（或至少允许丢消息并文档化）。
- **状态快照**：新客户端连接时应先发一次全量状态快照（player 19 个 flow + settings + accounts + library + sync + downloads + mpris），再转入增量推送；否则 QML 要等每个 flow 首次发射。
- **错误事件缺失**：核心只有 `state=ERROR` + 文本；建议 IPC 层扩展 `error: {code, message}`（`resolve_failed` / `decode_failed` / `audio_device_failed` / `network`）。
- **系统通知/托盘事件**：若 MPRIS+托盘留在 daemon，QML 不需要订阅事件，只需订阅命令可用的状态（`mpris.running`、`tray.available`）。

---

## 4. IPC 命令切分建议（基于实际代码，不臆造）

> 前提：Kotlin 核心作为无头守护进程，Unix socket 上跑 JSON-RPC（你已定的方案）。下面所有命令都**对应真实存在的公开方法**；括号里标注“对应源码方法 / 备注”。命名风格 `domain.verb`，参数是对象（便于扩展），返回 `{ok:true, data}` / `{ok:false, error:{code,message}}`。

### 4.1 连接与握手

| 命令 | 参数 | 返回 | 说明 |
|---|---|---|---|
| `app.hello` | `{protocol: 1, client: "qt-frontend"}` | `{version, protocol, capabilities:[...], pid}` | 版本协商；不匹配则拒绝 |
| `app.status` | — | `{ready:Boolean, ffmpeg:{available,version}, dirs:{data,config,cache}, mprisRunning, trayAvailable, scanning}` | 启动/诊断信息（对应 `FfmpegSupport`、`AppDirs`、`mpris.running`、`library.scanState`） |
| `app.quit` | — | `{ok}` | 对应 `AppIntents.quit` / `player.persistQueueState` + 退出；daemon 应在退出前 `player.persistQueueState()`（Main.kt 的行为） |
| `state.snapshot` | — | 全量状态对象 | 首次连接/重连用，见 3.3 |
| `events.subscribe` | `{topics:["player.*","library.*",...]}` | `{subscriptionId}` | 订阅推送（见 4.6） |
| `events.unsubscribe` | `{subscriptionId}` | `{ok}` | — |

### 4.2 Player

| 命令 | 参数 | 对应方法 | 说明 |
|---|---|---|---|
| `player.setQueue` | `{songs:[Song], startIndex:Int, autoPlay:Boolean=true}` | `setQueue` | 播放全部/随机/队列点播 |
| `player.playSong` | `{song:Song, queue?:[Song]}` | `playSongNow` | 列表点击播放（最常用，20 处调用） |
| `player.enqueueNext` | `{songs:[Song]}` | `enqueueNext` | “下一首播放” |
| `player.enqueue` | `{songs:[Song]}` | `enqueue` | 加入队尾 |
| `player.removeFromQueue` | `{index:Int}` | `removeFromQueue` | 队列删除 |
| `player.moveInQueue` | `{from:Int, to:Int}` | `moveInQueue` | 队列拖拽 |
| `player.clearQueue` | — | `clearQueue` | 清空队列 |
| `player.play` / `player.pause` / `player.toggle` | — | `play`/`pause`/`togglePlayPause` | 播放控制 |
| `player.next` | `{userInitiated:Boolean=true}` | `next` | 默认 true；自动切歌事件由 daemon 内部触发 false |
| `player.previous` | — | `previous` | 注意 >4 s 是回到开头 |
| `player.seek` | `{positionMs:Long}` | `seekTo` | 建议前端防抖 150–250 ms |
| `player.restartCurrent` | — | `restartCurrent` | 重新播放当前歌 |
| `player.setShuffle` / `player.toggleShuffle` | `{enabled:Boolean}` / — | `setShuffle`/`toggleShuffle` | MPRIS 用 set，UI 用 toggle |
| `player.setRepeatMode` / `player.cycleRepeat` | `{mode:"OFF\|ALL\|ONE"}` / — | `setRepeatMode`/`cycleRepeatMode` | — |
| `player.reloadLyrics` | — | `reloadLyrics` | 重新拉歌词 |
| `player.setVolume` | `{volume:Float}` | `setVolume` | 0..1 |
| `player.setSpeed` | `{speed:Float}` | `setSpeed` | 0.5..3 |
| `player.setPitch` | `{semitones:Float}` | `setPitch` | -12..12 |
| `player.setLoudness` | `{enabled:Boolean}` | `setLoudness` | — |
| `player.setEqualizerEnabled` | `{enabled:Boolean}` | `setEqualizerEnabled` | — |
| `player.setEqualizerPreset` | `{name:String, bands:[Float]}` | `setEqualizerPreset` | 预设由前端提供（或 daemon 内置 8 预设表）；也可只传 name 由 daemon 查表 |
| `player.setEqualizerBand` | `{index:Int, gain:Float}` | `setEqualizerBand` | — |
| `player.resetEffects` | — | `resetEffects` | — |
| `player.startSleepTimer` | `{minutes:Int}` | `startSleepTimer` | — |
| `player.stopAfterCurrent` / `player.cancelSleepTimer` | — | `stopAfterCurrentSong`/`cancelSleepTimer` | — |
| `player.persistQueue` | — | `persistQueueState` | 退出前保存（`app.quit` 内部已做） |

> 也可提供合并命令 `player.playCollection {songs, startIndex, shuffle:Boolean}` → daemon 内部 `setQueue(songs.shuffled(),0,true)`，但为保持一一对应，建议保留基础命令、由前端组合。

### 4.3 Library

| 命令 | 参数 | 对应方法 | 说明 |
|---|---|---|---|
| `library.listSongs` | `{query?, offset?, limit?}` | `songs` + 前端过滤 | 当前核心没有分页/服务端过滤，daemon 侧可补 |
| `library.getSong` | `{key}` | `byKey` | 当前未使用，但命令很有用 |
| `library.listArtists` | — | `artists()` | 返回分组（建议扁平 `{name, songs:[key]}` 或直接歌曲） |
| `library.listAlbums` | — | `albums()` | 同上 |
| `library.scan` | `{}` | `scan()`（suspend，长任务） | 建议异步：立即返回 `{started:true}`，进度走 `library.scan` 事件 |
| `library.scanStatus` | — | `scanState` | 供非订阅客户端轮询 |

### 4.4 Collections（歌单 / 历史 / 统计）

| 命令 | 参数 | 对应方法 |
|---|---|---|
| `playlist.list` | — | `playlists` flow（含 system 收藏） |
| `playlist.create` | `{name}` | `createPlaylist` |
| `playlist.rename` | `{id, name}` | `renamePlaylist`（**UI 当前无入口**，但 API 存在） |
| `playlist.delete` | `{id}` | `deletePlaylist` |
| `playlist.addSongs` | `{id, songs:[Song]}` | `addSongs` |
| `playlist.removeSong` | `{id, songKey}` | `removeSong` |
| `playlist.moveSong` | `{id, from, to}` | `moveSong`（**UI 无入口**） |
| `favorite.toggle` | `{song:Song}` | `toggleFavorite` |
| `favorite.check` | `{songKey}` | `isFavorite`（当前 UI 用 song 对象） |
| `history.list` | `{limit?=600}` | `entries` |
| `history.remove` | `{songKey}` | `removeEntry` |
| `history.clear` | — | `clear` |
| `stats.summary` | `{period:"DAY\|WEEK\|MONTH\|YEAR\|ALL"}` | `summary` |
| `stats.topSongs` | `{period, sort:"PLAY_COUNT\|LISTEN_TIME\|RECENT", limit=50}` | `topSongs` |
| `stats.clear` | — | `clear` |

### 4.5 Online / Login / Download / Sync / Settings

| 命令 | 参数 | 对应方法 | 说明 |
|---|---|---|---|
| `online.home` | `{}` | `neteaseHome()` | 首页聚合（6+ 请求，slow） |
| `online.search` | `{source, kind, query, page}` | `search()` | source: LOCAL/NETEASE/BILIBILI |
| `online.collectionSongs` | `{collection:OnlineCollection}` | `songsForCollection(collection, onPage)` | B 站收藏夹分页：进度用事件 `online.collectionPage` 推 |
| `online.albumSongs` | `{collection}` | `albumSongs` | UI 未用，可保留 |
| `online.artistSongs` | `{artistId}` | `artistSongs` | 只支持网易云 |
| `online.hotKeywords` | — | `netease.hotSearchKeywords()` | — |
| `online.recommendedPlaylists` | `{limit=12}` | `netease.recommendedPlaylists` | — |
| `online.userPlaylists` | `{uid}` | `netease.userPlaylists` | 需登录 |
| `online.searchPlaylists` / `online.searchArtists` / `online.searchVideos` | `{query, limit?}` | 对应 Netease/Bili 方法 | — |
| `online.favoriteFolders` | `{mid}` | `bilibili.favoriteFolders` | 需登录 |
| `online.coverFor` | `{collection}` | `coverFor` | 返回封面 URL（可能为空） |
| `login.start` | `{source:"NETEASE\|BILIBILI"}` | `createQrCode()` | 返回 `{qrContent, key}` |
| `login.cancel` | — | 前端取消（daemon 停止轮询） | 需 daemon 侧实现轮询 |
| `account.list` | — | `accounts.state` | **过滤 cookies 字段** |
| `account.logout` | `{source}` | `logout(source, jarKey)` | jarKey 由 source 推导 |
| `download.list` | — | `tasks` + `items` | — |
| `download.enqueue` | `{songs:[Song]}` | `enqueue` | 返回 `{added:Int, message}` |
| `download.cancel` / `download.retry` / `download.delete` | `{taskId}` / `{taskId}` / `{songKey}` | `cancel`/`retry`/`deleteDownload` | — |
| `download.clearFinished` / `download.clearAll` | — | `removeFinished`/`clearAll` | — |
| `download.directory` | — | `downloadDirectory()` | — |
| `download.repairMetadata` | — | `repairMetadata()` | 长任务，建议异步 + 事件 |
| `sync.status` | — | `sync.state` | — |
| `sync.config` | `{}` | `syncConfig.state` | **token 打码** |
| `sync.setRepo` | `{owner, repo}` | `syncConfig.update` + `refreshConfiguredState` | — |
| `sync.verifyToken` | `{token}` | `verifyToken` | 长任务（网络） |
| `sync.listRepos` | — | `listRepos(config.token)` | 用已保存 token |
| `sync.createRepo` | `{name}` | `createRepo` | — |
| `sync.setAutoSync` / `sync.setDataSaver` | `{enabled}` | `syncConfig.update` | — |
| `sync.start` | — | `performSync()` | 长任务，进度走 `sync.state` |
| `sync.clearConfig` | — | `syncConfig.clear` | — |
| `settings.get` | — | `settings.state` | 全量 |
| `settings.set` | `{field, value}` 或 `{patch:{...}}` | `settings.update{copy}` | 建议 patch 形式；**禁止**直接写 `lastQueue*`（由 player 管理） |
| `settings.setShortcuts` | `{local:{...}, global:{...}}` | `settings.update` | — |
| `misc.ffmpeg` | — | `FfmpegSupport` | — |
| `misc.dirs` / `misc.openDirectory` | `{which:"data\|cache\|download"}` | `AppDirs` + `Desktop.getDesktop().open` | 打开目录（Qt 也可自行实现） |
| `misc.uiScale` | `{setting:Float}` | — | 建议前端自行换算，daemon 只存 `settings.uiScale` |

**不建议暴露**：`player.engine`、`LyricsRepository` 内部方法、`HttpService`、`GitHubSyncTransport`、`SyncDataSerializer/Mapping/Merger`、`DownloadMetadataWriter`、`LrcParser`、`AudioEngine`、`JsonFileStore`、`NeteaseApi/BiliApi` 的底层方法（除上面列出的 UI 直接调用项外，都应该由 daemon 组合命令）。

### 4.6 事件推送格式（建议）

```json
{"jsonrpc":"2.0","method":"event","params":{
  "topic":"download.tasks","seq":1234,"ts":1763500000123,
  "data":{"tasks":[{"id":"...","songKey":"...","state":"RUNNING","downloadedBytes":1048576,"totalBytes":4194304}]}
}}
```

建议 topic 清单（与 3.1 一一对应）：
`player.state`、`player.position`（5 Hz 或 250 ms 节流）、`player.queue`、`player.lyrics`、`player.message`、`library.scan`、`library.songs`、`playlist.changed`、`history.changed`、`stats.changed`、`download.tasks`（节流 200–500 ms）、`download.catalog`、`sync.state`、`login.status`、`accounts.changed`、`settings.changed`（建议 patch 形式）、`online.covers`、`mpris.running`、`scan.progress`（可并入 library.scan）。

**全量快照命令 `state.snapshot` 的返回结构建议**：

```json
{
  "player": { "state":"PLAYING","currentSong":{...}|null,"queue":[...],"currentIndex":0,
              "positionMs":12345,"durationMs":200000,"volume":0.85,"shuffle":false,
              "repeatMode":"OFF","lyrics":{...},"lyricsLoading":false,"coverSeedColor":"3F51B5",
              "speed":1.0,"pitch":0.0,"loudness":false,"equalizerEnabled":false,
              "equalizerBands":[0,0,0,0,0,0,0,0,0,0],"sleepTimer":{...},"buffering":false },
  "library": { "songs":[...], "scan":{...} },
  "playlists": [...], "history":[...], "stats":[...],
  "downloads": {"tasks":[...], "items":[...]},
  "accounts": [{"source":"NETEASE","nickname":"...","userId":"...","vip":false}],
  "sync": {"state":{...},"config":{...token打码...}},
  "settings": {...},
  "mpris": {"running":true},
  "ffmpeg": {"available":true,"version":"ffmpeg version ..."}
}
```

### 4.7 并发 / 生命周期约定（建议）

| 类别 | 规则 |
|---|---|
| 写命令（播放/歌单/设置/下载/同步/登录） | 进入**单线程串行队列**（daemon 主命令线程），避免 `settings.update` 并发丢更新、JSON 写竞争 |
| 长任务（scan/home/search/songsForCollection/performSync/repairMetadata/verifyToken） | 返回 `{started:true, taskId}`，进度走事件；同域单飞（scan 不允许并发 2 个，sync 不允许并发） |
| 状态推送 | 由单个推送协程从 StateFlow `conflate` 采样后发出，避免慢客户端拖垮 daemon |
| 客户端断开 | 取消订阅，长任务是否取消由命令参数决定（默认后台继续，如扫描） |
| 启动顺序 | `SingleInstance.acquire` → `AppContainer()` → `bootstrap(scanLibrary=false)` → 创建 socket 监听 → `app.hello` 后可下发命令 |
| 退出顺序 | 收到 `app.quit`：`player.persistQueueState()` → 停止下载/同步任务 → 断开 socket → 停止 MPRIS/托盘 → 退出（对应 Main.kt 的退出路径） |
| 安全 | socket 放 `$XDG_RUNTIME_DIR/NeriPlayer/daemon.sock`（权限 0700/0600）；不推送 cookies/token；`account.list` 过滤 Cookie |

### 4.8 数据编码约定（建议）

- 枚举：`name` 字符串（`"PLAYING"`、`"NETEASE"`、`"OFF"`）。
- `Long`：常规时间戳/时长用 JSON number；**同步 ID（`SyncMapping.stableSyncId` 结果）用 String**。
- `Song` 直接复用 @Serializable 结构（可直接 JSON）；非 @Serializable 的 `Lyrics/OnlineCollection/ScanState/DownloadTask/...` 需要 daemon 侧定义 DTO（或给源码补 `@Serializable`）。
- `null` 显式出现（可空字段）；`AppSettings.floatingLyricsTextColor` 等是枚举名字符串（"WHITE"），不是颜色值。
- 封面：`Song.artworkPath`（绝对路径，daemon 与前端同机可读）或 `Song.artworkUrl`（URL，需要 Referer 下载）；建议新增 `artwork.get {songKey}` 返回 `{path}`（daemon 复用 `coverDir` 缓存 + 各平台 Referer），避免前端重复实现下载逻辑。
- 文本：所有提示都是中文自然语言，直接展示即可。

## 5. 统计与调用清单（命令清单底稿）

### 5.1 core + net + sync 公开 API 数量（逐类）

| 类 / 对象 | 文件 | 公开方法数 | StateFlow / SharedFlow | 备注 |
|---|---|---:|---|---|
| `PlayerManager` | core/Player.kt | **35** | 19 + SharedFlow 1 | 最大命令面；全部非 suspend |
| `LibraryRepository` | core/Library.kt | 7 | 3 | 含 1 个 suspend |
| `PlaylistRepository` | core/Collections.kt | 14 | 1 | 全部同步写盘 |
| `HistoryRepository` | core/Collections.kt | 8 | 1 | 其中 3 个未被使用 |
| `StatsRepository` | core/Collections.kt | 8 | 1 | `record()` 未被调用 |
| `SettingsRepository` | core/Storage.kt | 2 | 1 | update/replace |
| `AccountRepository` | core/Account.kt | 5 | 1 | Cookie 持久化 |
| `DownloadCatalog` | core/Download.kt | 6 | 1 | — |
| `DownloadManager` | core/Download.kt | 9 | 2 | 含 1 个 suspend |
| `LyricsRepository` | core/Lyrics.kt | 4 | 0 | 1 个 suspend |
| `LrcParser`（object） | core/Lyrics.kt | 3 | 0 | 纯函数 |
| `OnlineRepository` | net/OnlineRepository.kt | 9 | 1 | 8 个 suspend |
| `NeteaseApi` | net/OnlineApis.kt | 14 | 0 | 纯同步 HTTP（阻塞） |
| `BiliApi` | net/OnlineApis.kt | 7 | 0 | 同上，含 Thread.sleep 重试 |
| `NeteaseLogin` / `BiliLogin` | net/LoginApi.kt | 3 + 3 | 0 | 纯同步 HTTP |
| `HttpService` | net/Http.kt | 11 | 0 | Cookie 容器 + HTTP 原语 |
| `MprisService` | core/Mpris.kt | 3 | 0 | + `running` + 2 回调变量 |
| `StatusNotifierService` | core/StatusNotifier.kt | 3 | 0 | + `running` |
| `GlobalShortcutsPortal` | core/GlobalShortcutsPortal.kt | 4 | 0 | + `isActive` |
| `SingleInstance`（object） | core/SingleInstance.kt | 1 | 0 | — |
| `UiScale`（object） | core/UiScale.kt | 2 | 0 | + PRESETS/source/SYSTEM |
| `FfmpegSupport`（object） | core/AudioEngine.kt | 3 | 0 | available/version/probeDurationMs |
| `SyncConfigStore` | sync/SyncConfig.kt | 2 | 1 | — |
| `GitHubSyncManager` | sync/GitHubSyncManager.kt | 9 | 1 | 4 个 suspend + 3 个测试辅助 |
| `GitHubSyncTransport` | sync/GitHubSyncTransport.kt | 9 | 0 | 内部 |
| `SyncDataSerializer` | sync/SyncDataSerializer.kt | 5 | 0 | 内部 |
| `SyncMapping`（object） | sync/SyncMapping.kt | 11 | 0 | 内部（其中 `identityKey` 2 个重载） |
| `SyncMerger`（object） | sync/SyncMerger.kt | 2 | 0 | 内部 |
| `ShortcutSettings` | core/Shortcuts.kt | 9 | 0 | 配置模型 |
| `ShortcutBinding` | core/Shortcuts.kt | 7 | 0 | 4 实例 + 3 companion |
| `AudioEngine`（接口） | core/AudioEngine.kt | 11 | 1 | 内部，不需 IPC |
| `JsonFileStore` | core/Storage.kt | 2 | 0 | 内部 |
| `DownloadMetadataWriter`（object） | core/DownloadMetadata.kt | 2 | 0 | 内部 |
| 顶层函数（core+net+sync） | 各文件 | ~20 | 0 | 见 5.3 |

**汇总**

| 口径 | 数量 |
|---|---:|
| core+net+sync 公开函数声明（脚本统计，排除 private/internal 与接口 override） | **286** |
| 其中“领域级仓储 / 服务 / 平台客户端”方法（IPC 候选命令面） | **161** |
| 顶层纯函数（key 构造、工具、解析、映射等） | ~20 |
| DBus / D-Bus 接口方法声明（MPRIS 2 接口 11 个 + 托盘接口 6 个 + portal 私有接口等） | ~20 |
| 接口实现 override（不计入公开声明） | 23 |
| `suspend` 方法 | **16** |
| 公开 `StateFlow` / `SharedFlow` | **33 / 1** |
| `@Serializable` 声明 | **33**（29 data class + 4 enum） |
| 非 @Serializable 的公开模型 | ~20 |

**161 个 IPC 候选的构成**：Player 35 + Library 7 + Playlist 14 + History 8 + Stats 8 + Settings 2 + Account 5 + DownloadCatalog 6 + DownloadManager 9 + Lyrics 4 + OnlineRepository 9 + NeteaseApi 14 + BiliApi 7 + NeteaseLogin 3 + BiliLogin 3 + Mpris 3 + StatusNotifier 3 + GlobalShortcutsPortal 4 + SingleInstance 1 + UiScale 2 + FfmpegSupport 3 + SyncConfigStore 2 + GitHubSyncManager 9 = **161**。

### 5.2 UI 侧去重调用清单（79 个业务方法，按领域分组）

> 统计范围：`ui/**` + `Main.kt` 中出现的 `container.<receiver>.<member>`（含 `.value` / `.collectAsState` 归并）。
> 共 **386 处调用 / 122 个不同成员访问**，其中业务方法 **79 个**、flow/属性 39 个、UI 外壳回调 3 个（`scope.launch`、`mpris.onRaise`、`mpris.onQuit`）、`settings.current.*` 字段读 4 个、`supportsEffects/mpris.running` 2 个、`player.currentSong.value/player.state.value` 2 个。
> 「次数」= 源码中出现次数。

**Player（27）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `playSongNow` | 20 | DetailScreens, ExploreScreen, HomeScreen, LibraryScreen |
| `setQueue` | 11 | DetailScreens, LibraryScreen, NowPlayingScreen, PlayerComponents |
| `togglePlayPause` | 5 | FloatingLyrics, Main, NeriApp, NowPlayingScreen, TrayControlPanel |
| `next` | 5 | FloatingLyrics, Main, NeriApp, NowPlayingScreen, TrayControlPanel |
| `previous` | 5 | 同上 |
| `setVolume` | 5 | NowPlayingScreen, PlayerComponents, SettingsScreen, TrayControlPanel |
| `seekTo` | 4 | NeriApp, NowPlayingScreen |
| `enqueueNext` | 4 | DetailScreens, ExploreScreen, LibraryScreen, NowPlayingScreen |
| `persistQueueState` | 4 | Main |
| `setSpeed` | 3 | NowPlayingScreen, PlayerComponents, SettingsScreen |
| `setEqualizerPreset` | 3 | NowPlayingScreen, PlayerComponents, SettingsScreen |
| `setLoudness` | 3 | NowPlayingScreen, PlayerComponents, SettingsScreen |
| `enqueue` | 2 | DetailScreens, NowPlayingScreen |
| `moveInQueue` | 2 | NowPlayingScreen, PlayerComponents |
| `removeFromQueue` | 2 | NowPlayingScreen, PlayerComponents |
| `resetEffects` | 2 | NowPlayingScreen, PlayerComponents |
| `setEqualizerBand` | 2 | NowPlayingScreen, PlayerComponents |
| `setPitch` | 2 | NowPlayingScreen, PlayerComponents |
| `startSleepTimer` | 2 | NowPlayingScreen, PlayerComponents |
| `stopAfterCurrentSong` | 2 | NowPlayingScreen, PlayerComponents |
| `cancelSleepTimer` | 2 | NowPlayingScreen, PlayerComponents |
| `clearQueue` | 2 | NowPlayingScreen, PlayerComponents |
| `reloadLyrics` | 1 | NowPlayingScreen |
| `restartCurrent` | 1 | NowPlayingScreen |
| `cycleRepeatMode` | 1 | NowPlayingScreen |
| `toggleShuffle` | 1 | NowPlayingScreen |
| `setEqualizerEnabled` | 1 | SettingsScreen |

**Collections（11）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `playlists.addSongs` | 4 | DetailScreens, NowPlayingScreen |
| `playlists.createPlaylist` | 3 | DetailScreens, LibraryScreen, NowPlayingScreen |
| `playlists.isFavorite` | 2 | DetailScreens, NowPlayingScreen |
| `playlists.toggleFavorite` | 2 | DetailScreens, NowPlayingScreen |
| `playlists.deletePlaylist` | 1 | DetailScreens |
| `playlists.removeSong` | 1 | DetailScreens |
| `history.removeEntry` | 1 | HomeScreen |
| `history.clear` | 2 | DetailScreens, SettingsScreen |
| `stats.summary` | 1 | DetailScreens |
| `stats.topSongs` | 1 | DetailScreens |
| `stats.clear` | 2 | DetailScreens, SettingsScreen |

**Library（2）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `library.scan` | 2 | LibraryScreen, SettingsScreen |
| `library.albums` | 1 | LibraryScreen |

**Online（12）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `online.neteaseHome` | 1 | HomeScreen |
| `online.search` | 1 | ExploreScreen |
| `online.songsForCollection` | 1 | DetailScreens |
| `online.artistSongs` | 1 | DetailScreens |
| `online.coverFor` | 2 | DetailScreens, LibraryScreen |
| `online.netease.hotSearchKeywords` | 1 | ExploreScreen |
| `online.netease.recommendedPlaylists` | 2 | ExploreScreen, LibraryScreen |
| `online.netease.searchPlaylists` | 1 | LibraryScreen |
| `online.netease.searchArtists` | 1 | LibraryScreen |
| `online.netease.userPlaylists` | 1 | LibraryScreen |
| `online.bilibili.searchVideos` | 1 | LibraryScreen |
| `online.bilibili.favoriteFolders` | 1 | LibraryScreen |

**Login（9）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `accounts.accountOf` | 4 | LibraryScreen |
| `accounts.save` | 3 | LibraryScreen, LoginFlow |
| `accounts.logout` | 2 | SettingsScreen |
| `neteaseLogin.createQrCode` | 1 | LoginFlow |
| `neteaseLogin.poll` | 1 | LoginFlow |
| `neteaseLogin.fetchProfile` | 1 | LibraryScreen |
| `biliLogin.createQrCode` | 1 | LoginFlow |
| `biliLogin.poll` | 1 | LoginFlow |
| `biliLogin.fetchProfile` | 1 | LibraryScreen |

**Download（10）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `downloadCatalog.contains` | 5 | DetailScreens, LibraryScreen |
| `downloads.deleteDownload` | 3 | DetailScreens, DownloadPanel, LibraryScreen |
| `downloads.downloadDirectory` | 3 | DownloadPanel, DownloadSettings, LibraryScreen |
| `downloads.enqueue` | 2 | DetailScreens |
| `downloadCatalog.remove` | 1 | DownloadSettings |
| `downloads.cancel` | 1 | DownloadPanel |
| `downloads.retry` | 1 | DownloadPanel |
| `downloads.removeFinished` | 1 | DownloadPanel |
| `downloads.clearAll` | 1 | DownloadPanel |
| `downloads.repairMetadata` | 1 | DownloadPanel |

**Sync（7）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `sync.refreshConfiguredState` | 4 | SyncSettings |
| `sync.verifyToken` | 1 | SyncSettings |
| `sync.listRepos` | 1 | SyncSettings |
| `sync.createRepo` | 1 | SyncSettings |
| `sync.performSync` | 1 | SyncSettings |
| `syncConfig.update` | 6 | SyncSettings |
| `syncConfig.clear` | 1 | SyncSettings |

**Settings（1）**

| 方法 | 次数 | 调用文件 |
|---|---:|---|
| `settings.update` | **71** | BackgroundSettings, DownloadSettings, ExploreScreen, FloatingLyrics, FloatingLyricsSettings, Main, NeriApp, NowPlayingScreen, PlayerComponents, SettingsScreen, ShortcutDispatcher, ShortcutSettings, TrayControlPanel |

**合计：27 + 11 + 2 + 12 + 9 + 10 + 7 + 1 = 79 个业务方法。**

**另有 39 个 flow / 属性被 UI 读取**（去重）：
`player.currentSong/state/positionMs/durationMs/volume/speed/pitch/loudness/equalizerEnabled/equalizerBands/shuffle/repeatMode/queue/currentIndex/lyrics/lyricsLoading/coverSeedColor/sleepTimer/buffering/messages/supportsEffects`（21）、`library.songs/scanState`（2）、`playlists.playlists`（1）、`history.entries`（1）、`stats.stats`（1）、`downloads.tasks/activeCount`（2）、`downloadCatalog.items`（1）、`accounts.state`（1）、`settings.state`（1）、`sync.state`（1）、`syncConfig.state`（1）、`mpris.running`（1）→ 34 个 flow + `player.currentSong.value/player.state.value` + `settings.current.*` 4 项 + `supportsEffects/mpris.running` 已计 → 汇总见 5.2 开头说明。

### 5.3 公开但 UI 未调用的方法（IPC 可延后 / 仅 daemon 内部）

**A. 完全无调用方（死代码，可先不实现）**

| 方法 | 位置 | 说明 |
|---|---|---|
| `StatsRepository.record(song, listenMs)` | core/Collections.kt:283 | 无人调用（播放走 recordPlay + addListenTime） |
| `PlaylistRepository.renamePlaylist(id, name)` | core/Collections.kt:58 | UI 没有改名入口 |
| `PlaylistRepository.moveSong(id, from, to)` | core/Collections.kt:93 | UI 没有歌单内排序入口（队列排序走 `player.moveInQueue`） |
| `HistoryRepository.continuePlaying/recentlyPlayedSongs/mostPlayedSongs` | core/Collections.kt:213-220 | Home 直接操作 entries |
| `LibraryRepository.byKey/byPath` | core/Library.kt:87-89 | 无人调用 |
| `LibraryRepository.indexLoaded`（flow） | core/Library.kt:75 | 无人 collect |
| `OnlineRepository.playlistSongs/albumSongs` | net/OnlineRepository.kt:127/164 | 无人调用（songsForCollection 内部走了 playlistDetail/albumDetail） |
| `DownloadCatalog.totalSizeBytes()` | core/Download.kt:100 | UI 自己 sum |
| `dayKeyOf/daysBetween` | core/Collections.kt:357-360 | 无人调用 |
| `DownloadManager.buildTargetFile()` | core/Download.kt:375 | 仅内部调用 |
| `NeteaseApi.searchAlbums` | net/OnlineApis.kt:144 | 无人调用（搜索专辑走 search 的 kind=ALBUM） |
| `SyncConfigStore.current` | sync/SyncConfig.kt:36 | 内部读取用（非 UI） |

**B. 仅被核心/系统集成调用（daemon 内部，不需要 IPC 命令）**

`player.handleTrackCompleted`（解码完成回调）、`player.setShuffle/setRepeatMode`（MPRIS）、`player.flushListeningTime`（内部计时）、`player.attachSnapshotFlow/restoreLastQueue`（bootstrap）、`LyricsRepository.cached/put/invalidate/load`（player）、`OnlineRepository.resolvePlayback/lyrics`（player/lyrics）、`HttpService.*`（login/download/sync 内部）、`NeteaseApi.songUrl/lyric/playlistDetail/albumDetail/artistTopSongs/searchSongs`、`BiliApi.videoPages/audioUrl/favoriteFolderSongs/biliHeaders`、`DownloadCatalog.fileFor`（player 离线优先）、`MprisService.start/stop/refresh`（AppContainer）、`StatusNotifierService.start/stop/refresh`（Main）、`GlobalShortcutsPortal.registerDefaults/register/openConfigure/stop`（Main）、`SingleInstance.acquire`（Main）、`JsonFileStore.*`、`LrcParser.*`、`SyncDataSerializer/Mapping/Merger/Transport.*`、`AudioEngine.*`、`DownloadMetadataWriter.*`。

**C. 仅测试脚本使用**

`GitHubSyncManager.summarize/localSnapshotForTest/mergeForTest/importedStatCount`、`GitHubSyncTransport.deleteRepo/currentUser/tokenScopes/...`、`BiliApi.debugSearch`、`tools/SelfTest.kt`、`tools/UiScript.kt`、`tools/SyncE2ETest.kt`（`GH_TOKEN/GH_MOCK_BASE`）。

### 5.4 `tools/` 自检脚本用到的核心方法（可作为 IPC 冒烟测试参考）

`tools/SelfTest.kt`/`UiScript.kt` 直接调用：`player.playSongNow/setQueue/play/pause/next/previous/seekTo/setVolume/setSpeed/resetEffects/cycleRepeatMode/toggleShuffle/engine`、`online.resolvePlayback/lyrics/search`、`neteaseLogin.createQrCode/poll`、`biliLogin.createQrCode/poll`、`downloads.enqueue/downloadDirectory`、`library.songs`、`playlists.*`、`accounts.accountOf`、`settings.update`、`FfmpegSupport.available`、`artworkCacheStats()`。说明这些方法在无 UI 环境下也能跑通（守护进程可直接复用）。

<!-- APPEND -->
