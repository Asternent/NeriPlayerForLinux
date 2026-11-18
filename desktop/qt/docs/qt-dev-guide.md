# Qt 前端迁移 · 开发指南（给协作 agent）

本仓库的 Linux 桌面版正在从 Kotlin + Compose Desktop 迁移到 **Qt6 / QML**。
架构：**Kotlin 无头后端**（保留原有播放/网络/同步逻辑）+ **Qt6/QML 前端**，两者通过 Unix socket 上的 JSON 协议通信。
本文件是协作开发的统一入口，动手前先读完。

---

## 1. 关键路径

| 内容 | 路径 |
| --- | --- |
| Qt 前端 | `desktop/qt/`（CMake 工程，QML 从源码目录加载，改 QML 不需要编译） |
| QML 根 | `desktop/qt/qml/`（`Main.qml`、`AppShell.qml`、`globals/`、`components/`、`pages/`） |
| QML 全局对象 | `desktop/qt/qml/globals/{Api,Player,Settings,Theme,Nav,Toast}.qml` |
| 后端（Kotlin） | `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/backend/` |
| 后端命令 | `backend/api/*Api.kt`，在 `BackendMain.kt` 里逐个 `register(...)` |
| 协议文档 | `desktop/docs/protocol.md` + `desktop/docs/protocol-<domain>.md` |
| 核心 API 清单 | `desktop/docs/core-api-inventory.md`（**迁移必读**，含每个 UI 调用点） |
| 设计规格 | `desktop/qt/docs/design-spec.md`（颜色/字号/间距/组件尺寸，含 Compose 源码行号） |
| Compose 旧前端（参考实现） | `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/ui/`（**只读参考，不要修改**） |

## 2. 构建 / 运行 / 截图

统一用 `desktop/qt/tools/dev.sh`（测试数据隔离在 `$NERI_TEST_ROOT`，默认 `/tmp/neri-dev`，
**不会碰用户真实播放数据**）。多个 agent 并行时各自指定不同的 `NERI_TEST_ROOT`：

```bash
cd desktop/qt
export NERI_TEST_ROOT=/tmp/neri-dev-<你的名字>

tools/dev.sh backend                      # 构建并启动测试后端（已运行则跳过）
tools/dev.sh screenshot /tmp/shot.png --page home --wait 2500
tools/dev.sh build                        # 只有改了 C++ 才需要
tools/dev.sh stop
```

**演示数据**（10 首带标签/封面的 demo 音乐，已扫描进媒体库）在 `/tmp/neri-demo`。
复制一份到自己的测试根再用（不要共用同一个后端）：

```bash
export NERI_TEST_ROOT=/tmp/neri-dev-<你的名字>
rm -rf "$NERI_TEST_ROOT"
rsync -a --exclude runtime /tmp/neri-demo/ "$NERI_TEST_ROOT/"
tools/dev.sh backend     # 在自己的副本上启动后端（会快速重扫一遍）
tools/dev.sh screenshot /tmp/xx.png --page library --wait 2500
```

- 只改 QML：不用编译，直接 `screenshot` 即可（QML 从源码目录加载）。
- 改 C++（`src/*.cpp|h`）：先 `tools/dev.sh build`。
- 改后端 Kotlin：`tools/dev.sh stop` 后直接 `tools/dev.sh backend`（它会串行构建 jar 并启动私有副本）。
  多个 agent 并行时 gradle 构建已用 flock 串行化；运行中的后端使用 `$NERI_TEST_ROOT/neriplayer-core.jar` 副本，不受他人重建影响。
- 截图是 offscreen（`QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software`），不会弹窗打扰用户。
- `--page` 可取值：`home/explore/library/settings/nowplaying/recent/stats/downloads/localPlaylist/localArtist/onlineCollection/remoteArtist`。
- 也可以用 `read_image` 工具直接查看截图确认视觉效果。

环境变量：`NERIPLAYER_SOCKET` 指定 socket；`NERIPLAYER_QML_DIR` 指定 QML 目录；`NERIPLAYER_BACKEND_JAR` 让 Qt 自动拉起后端。

## 3. IPC 协议（按行分隔的 JSON，Unix socket）

```
请求  {"id":1,"method":"player.play","params":{...}}
响应  {"id":1,"result":{...}}        或 {"id":1,"error":{"code":"...","message":"..."}}
事件  {"event":"player","data":{...}}
```

socket 路径：`$XDG_RUNTIME_DIR/neriplayer/core.sock`。
已有命令：`app.ping`、`app.bootstrap`、`app.setSystemTheme`、`app.refreshTheme`、`app.theme`、
`settings.get`、`settings.set`（`{patch:{...}}` 局部更新）、
`player.*`（togglePlayPause/play/pause/next/previous/seekTo/setVolume/setShuffle/toggleShuffle/
setRepeatMode/cycleRepeatMode/setSpeed/setPitch/setLoudness/setEqualizer*/resetEffects/
setQueue/playSongNow/playAt/enqueue/enqueueNext/removeFromQueue/moveInQueue/clearQueue/
startSleepTimer/stopAfterCurrentSong/cancelSleepTimer/reloadLyrics）。

事件：`player`（播放状态快照，约 4Hz）、`player.queue`、`player.lyrics`、`player.lyricsLoading`、
`player.coverSeed`、`theme`、`settings`、`toast`、`library.changed`、`library.scan`、
`collections.changed`、`history.changed`、`stats.changed`、`accounts.changed`、
`downloads.changed`、`sync.state`。

数据形状以 `desktop/docs/protocol*.md` 为准（每个 domain 有一份）。Kotlin 模型见
`desktop/src/main/kotlin/.../core/Models.kt`；能直接 `@Serializable` 编码的模型优先直接用。

## 4. QML 全局对象（context property，任何 QML 文件都能直接用）

### `Api` — 与后端通信
```qml
Api.get("library.songs", {}, function(result, error) { ... })   // 回调风格
Api.call("library.songs").then(r => ...).catch(e => ...)         // Promise 风格
const off = Api.on("library.changed", function(data) { ... })    // 订阅事件
Api.off("library.changed", handler)
```

### `Player` — 播放器状态 + 命令
只读状态：`song`（Song 对象）、`state`（IDLE/PREPARING/PLAYING/PAUSED/ERROR）、`positionMs`、`durationMs`、
`volume`、`shuffle`、`repeatMode`、`speed`、`pitch`、`loudness`、`equalizerEnabled`、`equalizerBands`、
`buffering`、`sleepTimer`、`supportsEffects`、`queue`、`queueIndex`、`lyrics`（`{lines:[{timeMs,text,translation}], translated, source, raw}`）、
`lyricsLoading`、`coverSeed`、`coverPath`；派生：`playing`、`hasSong`、`title`、`artist`、`album`、`progress`。
命令：`Player.togglePlayPause() / play() / pause() / next() / previous() / seekTo(ms) / seekBy(ms) /
setVolume(v) / setShuffle(b) / toggleShuffle() / setRepeatMode(m) / cycleRepeatMode() / setSpeed(v) /
setPitch(v) / setLoudness(b) / setEqualizer… / setQueue(songs, index, autoPlay) / playSongNow(song, queue) /
playQueueIndex(i) / enqueue(songs) / enqueueNext(songs) / removeFromQueue(i) / moveInQueue(from,to) /
clearQueue() / startSleepTimer(min) / stopAfterCurrentSong() / cancelSleepTimer() / reloadLyrics()`。

### `Settings` — 应用设置
`Settings.values`（完整 AppSettings 对象，字段见 `core/Storage.kt` 的 `AppSettings`）、
`Settings.set(key, value)`、`Settings.patch({..})`、`Settings.get(key, fallback)`。
后端变更后会通过 `settings` 事件自动刷新 `values`。

### `Theme` — 颜色与设计 token
颜色角色（Material3，来自后端 material-kolor，**跟随系统强调色**）：
- 普通角色直接是属性：`Theme.primary`、`Theme.surface`、`Theme.surfaceContainerLow`、`Theme.surfaceContainerHigh`、
  `Theme.surfaceContainerHighest`、`Theme.surfaceVariant`、`Theme.outlineVariant`、`Theme.error`、`Theme.primaryContainer` 等。
- **`onXxx` 角色带 `Color` 后缀**（QML 里 `onXxx` 会与既有属性冲突）：`Theme.onSurfaceColor`、
  `Theme.onSurfaceVariantColor`、`Theme.onPrimaryColor`、`Theme.onPrimaryContainerColor`、`Theme.onSecondaryColor`、
  `Theme.onSecondaryContainerColor`、`Theme.onErrorColor`、`Theme.onTertiaryColor`、`Theme.onBackgroundColor`。
- 兜底：`Theme.c("角色名")` 返回任意角色色。
- 其它 token：`Theme.fontFamily`、`Theme.iconFontFamily`、`Theme.textStyle("titleMedium")`、`Theme.fontFor(role)`、
  `Theme.spacingXs/Sm/Md/Lg/Xl/Xxl/Huge`、`Theme.radiusXs/Sm/Md/Lg/Xl/Xxl/Full`、
  `Theme.motionShort/Medium/Long`、`Theme.easingStandard`、`Theme.withAlpha(color, alpha)`、`Theme.isDark`。

### `Nav` — 路由与浮层
`Nav.tab`（当前 tab 名）、`Nav.current`/`Nav.currentName`、`Nav.openTab(name)`、`Nav.push(name, arg)`、
`Nav.back()`、`Nav.canGoBack`、`Nav.openByName(name, arg)`；
浮层：`Nav.activePanel`（`""/queue/volume/effects/sleep/downloads/login`）、`Nav.openPanel(name)`、`Nav.closePanel()`、`Nav.openQueuePanel()` 等。

### `Toast` — 轻提示
`Toast.show(text, kind)`（kind: `"info"|"error"`）。

### `Backend` / `SystemTheme`（C++，一般页面不用直接碰）
`Backend.connected`、`Backend.call(method, params, cb)`、`Backend.event(name, data)`；
`SystemTheme.payload()` 返回 `{isDark, accent, ...}`。

## 5. 组件与约定

已有可复用组件（`qml/components/`）：
- `NeriIcon { name; filled; iconSize; color }` —— Material Symbols 图标（名字见 `components/IconMap.js`，常用：`home/explore/library_music/settings/play_arrow/pause/skip_next/skip_previous/volume_up/graphic_eq/queue_music/bedtime/lyrics/search/album/person/download/history/bar_chart/media/circle/chevron_left/chevron_right/more_vert/favorite/favorite_border/error/music_note`）
- `IconButton { icon; iconFilled; iconSize; size; iconColor; tooltip; onClicked }`
- `CoverArt { path; url; radius; placeholderIconSize }` —— 本地路径或 URL 封面，自动占位
- `NavigationBar`、`MiniPlayerBar`、`ToastOverlay`、`PagePlaceholder { title; subtitle; icon }`

视觉约定（详见 design-spec.md）：
- 页面底色 `Theme.surface`；卡片 `Theme.surfaceContainerLow`（圆角 20）；正文 `Theme.onSurfaceColor`；
  次级文字/图标 `Theme.onSurfaceVariantColor`；强调 `Theme.primary`。
- **不要**给普通卡片加阴影/边框（层级靠 surfaceContainer 色阶），只有迷你播放条与托盘面板例外。
- 字体统一 `font.family: Theme.fontFamily`，字号用 `Theme.textStyle(...)` 或 design-spec 里的 sp 值；
  **不要**写死字体族。
- 图标**不要**用 Unicode 表情或 SVG 文件，统一 `NeriIcon`。
- 页面里不写魔法数字，用 `Theme.spacing*` / `Theme.radius*`；确需精确尺寸时按 design-spec 表。
- 每个页面根节点是 `Item`，从 `AppShell.qml` 的 `sourceForRoute()` 加载，锚点铺满即可（内容区已让出底部栏）。

## 6. 文件所有权

并行开发时**只改分配给你的文件**，不要动别人的文件，不要改 `globals/`（需要新全局能力先报告）、
不要改 `Main.qml`/`AppShell.qml`/`CMakeLists.txt`（除非任务明确要求）。新增 QML 文件放在自己的 `pages/` 或
`components/` 命名空间下（例如 `components/player/`）。**不要提交 git**，提交由主 agent 统一做。

## 7. 验收要求

- 用 `tools/dev.sh screenshot` 截图确认页面能渲染、没有 QML 报错（stderr 里不能有 `Unable to assign`、
  `is not a type`、`Cannot read property` 之类）。
- 交互尽量通过后端真实数据驱动；后端还没实现的命令先按协议文档调用，失败时页面要有空状态而不是崩。
- 完成后报告：改了哪些文件、截图路径、遗留问题。
