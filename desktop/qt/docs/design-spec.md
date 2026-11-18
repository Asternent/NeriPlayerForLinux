# NeriPlayer Linux 桌面端 · 视觉与交互设计规格（Qt6 / QML 复刻基线）

> 目标读者：Qt6/QML 前端实现者、并行开发协调者、UI 走查者。
> 目的：把现有 **Kotlin + Compose Desktop** 前端（`desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/ui/`）的视觉与交互，逐项落成可执行的 QML 规格，做到"像素级可复刻、交互可对齐、动效时长一致"。

## 文档元信息

| 项 | 值 |
| --- | --- |
| 基线仓库 | `/home/yang/Git/neriplayer`，分支 `fix/window-restore-and-more`（HEAD `3a7f4ece`） |
| 基线版本 | NeriPlayer Desktop 1.4.7（`screens/SettingsScreen.kt:604`） |
| 目标端 | Qt 6.5+ / Qt Quick（`desktop/qt/CMakeLists.txt`，`QQuickStyle::setStyle("Basic")`） |
| 依据源码 | `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/` 下 `ui/` 全部文件 + `core/Shortcuts.kt` + `Main.kt` + `core/StatusNotifier.kt` |
| 依据截图 | `desktop/docs/screenshots/`（21 张，索引见附录 B） |
| 文档范围 | 只描述**前端视觉/交互/尺寸/动效/数据呈现**；后端协议、播放引擎、网络接口不在本文范围 |
| 行号约定 | 源码引用一律写作 `文件名:行号` 或 `文件名:起-止`，文件均位于 `desktop/src/main/kotlin/moe/ouom/neriplayer/desktop/ui/`，跨目录时写全相对路径 |
| 截图约定 | 截图引用写作 `docs/screenshots/xxx.png`，均位于 `desktop/` |

**单位约定**：Compose 的 `dp` 与 `sp` 在 100% 界面缩放下等价于 QML 的像素值（`Qt.application.font.pixelSize` 体系下 1dp = 1px）。本文所有数值默认 **dp**；字号写作 sp。所有尺寸在 QML 里都必须乘以全局 `Theme.uiScale / 100`（见 §3.7）。

---

## 0. 总览：界面骨架与本文档的切口

### 0.1 现有前端骨架（必须先对齐的 5 条结论）

1. **只有一个主窗口 + 两个独立小窗口**。
   - 主窗口：`Main.kt:66` 默认 `1180 × 820`，`WindowPosition(Alignment.Center)`，**带系统标题栏**（未设置 `undecorated`）。标题动态生成：`▶/⏸ 歌曲 - 歌手 · NeriPlayer`，无歌曲时 `音理音理!! · NeriPlayer`（`Main.kt:174-180`）。
   - 悬浮歌词窗口：无边框 / 透明 / 置顶 / 不可缩放 / 不抢焦点（`FloatingLyrics.kt:211-220`）。
   - 托盘控制面板：无边框 / 透明 / 置顶 / 可获焦点（`TrayControlPanel.kt:109-123`）。
2. **没有侧边导航栏**。全部导航由**底部 `NavigationBar`** 承担（`PlayerComponents.kt:93-133`），宽屏（≥900dp）下也仍然保持底部导航（见 `docs/screenshots/landscape-home.png` 底部）。所以 QML 侧**不需要 `NavigationRail`**；"宽屏适配"是靠内容区多栏 + 顶部栏动作实现的。
3. **页面栈是单一 `backStack`**（`NeriApp.kt:97`，`Navigation.kt:19-28`）。4 个 Tab（首页/探索/媒体库/设置）为根；7 个二级页（播放页/最近播放/统计/本地歌单/本地歌手/在线歌单/在线歌手）压栈；"返回"就是弹栈，根页按返回键回首页（`NeriApp.kt:128-160`）。
4. **深浅色 + 动态取色 + 9 种调色风格 + 2 套色彩规范**全部由 `material-kolor` 在运行时算出 **49 个 Material3 color role**（`theme/Theme.kt:125-174`），所有组件只引用角色名，无第二套配色。
5. **所有页面共享同一套内容容器**：宽屏铺满 + 左右 20dp，窄屏最大 1180dp 居中（`AppLayout.kt:68-83`）；底部固定"迷你播放条 + 底部导航"（`NeriApp.kt:287-313`），页面内容在其上方滚动。

### 0.2 与 Qt 侧已有骨架的衔接

`desktop/qt/` 已经存在（尚未提交）：`main.cpp` 用 `QQmlComponent` 顺序创建 `qml/globals/{Api,Player,Theme,Settings,Nav,Toast}.qml` 并注册为上下文对象，然后加载 `qml/Main.qml`；后端通过 `BackendClient` 以 **Unix domain socket + 行分隔 JSON** 通信（`desktop/qt/src/backendclient.h`）。本文第 9 章给出的文件清单、单例契约与数据下发格式，均按这套既有骨架设计，不另起炉灶。

### 0.3 页面/视图全景（本文第 5 章逐个展开）

| 层级 | 视图 | 载体 |
| --- | --- | --- |
| Tab 根页（4） | 首页 / 探索 / 媒体库 / 设置 | 主窗口内容区 |
| 二级页（7） | 播放页 / 最近播放 / 播放统计 / 本地歌单详情 / 本地歌手详情 / 在线歌单（含收藏夹）详情 / 在线歌手详情 | 主窗口内容区（压栈） |
| 浮层（8） | 全屏歌词 / 播放队列 / 音量 / 睡眠定时器 / 音效与倍速 / 下载管理 / 扫码登录 / 歌曲操作·新建歌单·更多选项（对话框组） | 主窗口内 Popup/Dialog |
| 独立窗口（2） | 悬浮歌词窗口 / 托盘控制面板 | 独立 Window |
| 系统级（3） | 使用须知（首次启动）/ Snackbar / 系统托盘菜单（SNI+DBusMenu 由桌面渲染） | Dialog / Scaffold / 系统托盘 |

---

## 1. 主题与颜色

### 1.1 取色管线（数据流，QML 侧只消费结果）

```
seedHex ──sanitize──> Hct.fromInt ──> DynamicScheme(variant, isDark, contrast=0.0, specVersion, Platform.PHONE)
    └─> MaterialDynamicColors.<role>() ──> ColorScheme(49 roles)
        └─> animateScheme() 逐角色 420ms 颜色补间 ──> MaterialTheme(colorScheme = animated)
```

| 步骤 | 位置 | 说明 |
| --- | --- | --- |
| 种子来源 | `NeriApp.kt:81-85` | `settings.dynamicColor && coverSeed 非空` → 用**当前歌曲封面提取的种子色**；否则用 `settings.themeSeedColor`（默认 `0061A4`） |
| 封面种子 | `NeriApp.kt:74` | `container.player.coverSeedColor`（StateFlow，随歌曲切换变化） |
| 种子清洗 | `theme/Theme.kt:70-77` | 去 `#`、转大写、必须 6 位 hex，否则回落 `DEFAULT_SEED_COLOR` |
| 默认种子 | `theme/Theme.kt:31` | `0061A4` |
| HCT 转换 | `theme/Theme.kt:91` | `Hct.fromInt(seedArgb)` |
| Scheme 构造 | `theme/Theme.kt:85-106` | 9 种 variant 各自一个 Scheme 类；`contrast = 0.0`；`Platform.PHONE` |
| 规范版本 | `theme/Theme.kt:120-121` | `SPEC_2021`（默认）/ `SPEC_2025` |
| 角色生成 | `theme/Theme.kt:123-174` | 逐个 `dynamicColors.<role>()` 取 ARGB，共 49 个 |
| 颜色补间 | `theme/Theme.kt:176-225` | `animateColorAsState(tween(420ms, FastOutSlowInEasing))`，**逐角色独立**动画 |
| Typography | `theme/Theme.kt:247` | `Typography()` —— **完全使用 M3 默认**，无任何覆写 |

> **给 QML 的关键决策**：不要在 QML 侧实现 material-color-utilities 的 HCT 算法。改为**后端算好 49 个 role 的十六进制值下发**，QML 只做"接收 + 420ms 补间 + 应用"。这样 9 种 variant × 2 套 spec 的差异全部留在后端（Kotlin 侧已经写好），QML 永远不会和手机端/后端色值不一致。

### 1.2 全部 color role（49 个）

`ColorScheme` 在 `theme/Theme.kt:125-174` 构造，字段顺序即下表顺序。**"动画"列**表示该角色是否参与 420ms 补间：`theme/Theme.kt:188-225` 只对前 37 个角色做了 `animateThemeColor`，`primaryFixed`…`onTertiaryFixedVariant` 这 12 个 fixed 角色**不参与动画**（切换主题时会瞬变）。

"用量"列是 Kotlin UI 代码里 `MaterialTheme.colorScheme.<role>` 的出现次数（用于判断迁移优先级与回归关注点）：

| # | role（Kotlin / QML 属性名） | 中文语义 | 动画 | 用量 | 主要用途（源码位置） |
| --- | --- | --- | --- | --- | --- |
| 1 | `primary` | 主色 | ✓ | 28 | 主按钮/播放按钮底色、选中态图标文字、进度条激活段、强调文字（`Components.kt:75,222,240`、`PlayerComponents.kt:235`、`NowPlayingScreen.kt:546,592,618,674`） |
| 2 | `onPrimary` | 主色上的内容 | ✓ | 2 | FilledIconButton 图标、封面加载遮罩里的转圈（`NowPlayingScreen.kt:503`、`HomeScreen.kt:377`） |
| 3 | `primaryContainer` | 主色容器 | ✓ | 4 | 当前歌曲行高亮（α0.3）、定位闪烁（α0.55）、快捷键录制中底色（`Components.kt:209-210`、`PlayerComponents.kt:700`、`ShortcutSettings.kt:263`） |
| 4 | `onPrimaryContainer` | 主色容器上的内容 | ✓ | 1 | 快捷键录制文字（`ShortcutSettings.kt:307`） |
| 5 | `inversePrimary` | 反色主色 | ✓ | 0 | 保留（Snackbar 动作色等在 M3 内部会用，代码未显式引用） |
| 6 | `secondary` | 次色 | ✓ | 0 | 保留 |
| 7 | `onSecondary` | 次色上的内容 | ✓ | 0 | 保留 |
| 8 | `secondaryContainer` | 次色容器 | ✓ | 3 | 队列当前项高亮（α0.5）、托盘面板动作 hover（α0.6）、收藏页头卡（`PlayerComponents.kt:702`、`TrayControlPanel.kt:461`、`LibraryScreen.kt:754`） |
| 9 | `onSecondaryContainer` | 次色容器上的内容 | ✓ | 2 | 收藏页头卡图标与计数（`LibraryScreen.kt:761,773`） |
| 10 | `tertiary` | 第三色 | ✓ | 0 | 保留 |
| 11 | `onTertiary` | 第三色上的内容 | ✓ | 0 | 保留 |
| 12 | `tertiaryContainer` | 第三色容器 | ✓ | 0 | 保留 |
| 13 | `onTertiaryContainer` | 第三色容器上的内容 | ✓ | 0 | 保留 |
| 14 | `background` | 页面背景 | ✓ | 1 | 全屏歌词页底色（`NowPlayingScreen.kt:286`） |
| 15 | `onBackground` | 背景上的内容 | ✓ | 0 | 保留（未显式使用，正文都用 `onSurface`） |
| 16 | `surface` | 表面 | ✓ | 0 | 保留（Scaffold 默认底色） |
| 17 | `onSurface` | 表面上的内容 | ✓ | 9 | 正文、图标默认色、未激活的播放工具栏图标（`Components.kt:240`、`PlayerComponents.kt:351`、`NowPlayingScreen.kt:592,616`） |
| 18 | `surfaceVariant` | 表面变体 | ✓ | 2 | 迷你播放条进度轨道、托盘面板进度轨道（`PlayerComponents.kt:234`、`TrayControlPanel.kt:255`） |
| 19 | `onSurfaceVariant` | 表面变体上的内容 | ✓ | **87** | **全局次级文字/图标色**（副标题、说明、时长、图标默认 tint）。最高频角色，QML 必须优先接通 |
| 20 | `surfaceTint` | 表面着色（高度叠加） | ✓ | 0 | 由 M3 elevation 内部使用；QML 若手写阴影需自行模拟（见 §3.4） |
| 21 | `inverseSurface` | 反色表面 | ✓ | 0 | Snackbar 底色（M3 内部） |
| 22 | `inverseOnSurface` | 反色表面上的内容 | ✓ | 0 | Snackbar 文字（M3 内部） |
| 23 | `error` | 错误 | ✓ | 4 | 下载失败状态文字、错误相关图标、托盘"退出"危险色（`DownloadPanel.kt:133`、`SyncSettings.kt:92`、`TrayControlPanel.kt:457`） |
| 24 | `onError` | 错误上的内容 | ✓ | 0 | 保留 |
| 25 | `errorContainer` | 错误容器 | ✓ | 2 | `ErrorCard` 底色、托盘危险项 hover 底色（α0.55）（`Components.kt:137`、`TrayControlPanel.kt:460`） |
| 26 | `onErrorContainer` | 错误容器上的内容 | ✓ | 1 | `ErrorCard` 前景（`Components.kt:138`） |
| 27 | `outline` | 描边 | ✓ | 1 | 托盘面板"暂停中"状态圆点（`TrayControlPanel.kt:407`） |
| 28 | `outlineVariant` | 细分隔线/描边 | ✓ | 6 | 分隔线、色块未选中描边、快捷键键位框描边、托盘面板外框（α0.7）/分隔线（α0.55）（`TrayControlPanel.kt:231,443`、`ShortcutSettings.kt:207,272`、`SettingsScreen.kt:237`） |
| 29 | `scrim` | 遮罩黑 | ✓ | 1 | 封面缓冲遮罩（α0.4）（`NowPlayingScreen.kt:499`） |
| 30 | `surfaceBright` | 亮表面 | ✓ | 0 | 保留 |
| 31 | `surfaceDim` | 暗表面 | ✓ | 0 | 保留 |
| 32 | `surfaceContainer` | 容器层 | ✓ | 4 | 底部导航栏底色、快捷键全局键位框（`PlayerComponents.kt:120,128,204`、`ShortcutSettings.kt:205`） |
| 33 | `surfaceContainerHigh` | 高容器层 | ✓ | 3 | 迷你播放条常态底色、行 hover 底色（α0.5）、快捷键键位框常态（`PlayerComponents.kt:227`、`Components.kt:178`） |
| 34 | `surfaceContainerHighest` | 最高容器层 | ✓ | 3 | 迷你播放条 hover 底色、封面占位底色、空歌单封面占位（`PlayerComponents.kt:225`、`Artwork.kt:226`、`Components.kt:355`） |
| 35 | `surfaceContainerLow` | 低容器层 | ✓ | 13 | **卡片标准底色**：所有区块卡片/设置卡片/歌曲列表卡/歌词面板/统计卡（`Components.kt:501`、`PlayerComponents.kt:581`、`NowPlayingScreen.kt:241`、`SettingsScreen.kt:719`、`DetailScreens.kt:837`） |
| 36 | `surfaceContainerLowest` | 最低容器层 | ✓ | 0 | 保留 |
| 37 | `primaryFixed` | 主色固定 | ✗ | 0 | 保留 |
| 38 | `primaryFixedDim` | 主色固定暗 | ✗ | 0 | 保留 |
| 39 | `onPrimaryFixed` | — | ✗ | 0 | 保留 |
| 40 | `onPrimaryFixedVariant` | — | ✗ | 0 | 保留 |
| 41 | `secondaryFixed` | 次色固定 | ✗ | 0 | 保留 |
| 42 | `secondaryFixedDim` | — | ✗ | 0 | 保留 |
| 43 | `onSecondaryFixed` | — | ✗ | 0 | 保留 |
| 44 | `onSecondaryFixedVariant` | — | ✗ | 0 | 保留 |
| 45 | `tertiaryFixed` | 第三色固定 | ✗ | 0 | 保留 |
| 46 | `tertiaryFixedDim` | — | ✗ | 0 | 保留 |
| 47 | `onTertiaryFixed` | — | ✗ | 0 | 保留 |
| 48 | `onTertiaryFixedVariant` | — | ✗ | 0 | 保留 |
| 49 | `（surfaceContainerLowest 之后无其它）` | — | — | — | `ColorScheme` 构造到此结束（`theme/Theme.kt:174`） |

> 备注：Compose M3 的 `ColorScheme` 构造器还有 `surfaceVariant` 之外的若干新增角色（如 `surfaceContainer*`）已在表中；`background/onBackground` 在新版 M3 中与 `surface` 同值，但代码仍显式传入（`Theme.kt:139-140`）。

**语义约定（迁移时不要自创角色）**：
- **页面底色** = `surface`（Scaffold 默认），**卡片** = `surfaceContainerLow`，**底部导航** = `surfaceContainer`，**迷你播放条** = `surfaceContainerHigh`（hover → `surfaceContainerHighest`）。
- **正文** = `onSurface`，**次级文字/图标** = `onSurfaceVariant`（87 处，占绝对多数），**强调/选中** = `primary`，**当前项背景** = `primaryContainer` 低透明度，**队列当前项** = `secondaryContainer`。
- 分隔线 = `outlineVariant`（α0.55 / α0.7 按场景），色块未选中描边 = `outlineVariant`，选中描边 = `onSurface`（主题色块，`SettingsScreen.kt:237`）或 `primary`（悬浮歌词色块，`FloatingLyricsSettings.kt:265`）。

### 1.3 深浅色

- 三档：`DarkModeSetting.LIGHT / DARK / AUTO`（`core/Storage.kt:86`，默认 `AUTO`），AUTO 用系统 `isSystemInDarkTheme()`（`NeriApp.kt:75-80`）。
- 深色并非简单反色，而是**同一 seed 在深色 tone 上重新生成 palette**（Primary P80 而非 P40）。浅色截图见 `docs/screenshots/home.png`，深色截图见 `docs/screenshots/tray-panel-dark.png`。
- 切换深浅色时**所有 37 个可动画角色 420ms 过渡**；`background` 与 `surface` 会一起慢慢变化（截图对比可见导航栏底色渐变，不是瞬切）。
- QML 契约：`Theme.dark` 布尔 + 49 个颜色属性必须**成组同时更新**，否则会出现"半深半浅"的中间帧。

### 1.4 动态取色（封面种子）

| 项 | 规格 |
| --- | --- |
| 开关 | 设置 → 主题设置 → "动态取色"（`SettingsScreen.kt:219-224`） |
| 默认值 | `false`（`core/Storage.kt:85`） |
| 种子来源 | 当前歌曲封面主色，由播放器侧提取并写入 `player.coverSeedColor`（`NeriApp.kt:74,81-85`） |
| 无封面时 | 回落 `settings.themeSeedColor` |
| 切歌行为 | 种子变化 → `remember(seedColorHex, ...)` 重建 scheme → 全部角色 420ms 补间。**切歌时整窗颜色缓慢漂移**是产品特性，不要去掉 |
| 用户预设种子 | 8 个：`0061A4`（默认蓝）、`6750A4`（紫）、`B3261E`（红）、`C425A8`（品红）、`00897B`（青绿）、`388E3C`（绿）、`FBC02D`（黄）、`E65100`（橙）（`theme/Theme.kt:33-35`） |
| 自定义种子 | 6 位 HEX 输入 + "应用"；非法输入回退默认色（`SettingsScreen.kt:247-266`、`theme/Theme.kt:70-77`） |
| 选色即关闭动态 | 点预设色块或应用自定义色时，同时置 `dynamicColor = false`（`SettingsScreen.kt:241,262`） |

### 1.5 九种调色风格（palette style）

`theme/Theme.kt:37-39` 定义顺序即 UI 顺序；中文标签在 `:43-54`，描述在 `:56-67`；映射到 `Variant` 在 `:108-118`。

| 顺序 | style | 中文标签 | UI 描述文案 | material Variant | 视觉取向 |
| --- | --- | --- | --- | --- | --- |
| 1 | `TonalSpot` | 柔和色点 | 接近 Android 默认动态色，平衡耐看。 | `TONAL_SPOT` | **默认**。中低饱和、色相集中 |
| 2 | `Neutral` | 低饱和 | 减少彩度，页面更克制。 | `NEUTRAL` | 极低彩度，几乎灰阶带一点色相 |
| 3 | `Vibrant` | 鲜明 | 主色更亮更突出。 | `VIBRANT` | 高彩度 |
| 4 | `Expressive` | 表现力 | 色相更跳跃，适合个性化主题。 | `EXPRESSIVE` | 色相偏移大 |
| 5 | `Rainbow` | 彩虹 | 色相分布更宽，色彩更丰富。 | `RAINBOW` | 多色相互补 |
| 6 | `FruitSalad` | 果味 | 互补色更强，风格活泼。 | `FRUIT_SALAD` | 明显互补 |
| 7 | `Monochrome` | 单色 | 接近黑白灰，弱化主题色。 | `MONOCHROME` | 零彩度 |
| 8 | `Fidelity` | 忠实取色 | 更贴近你选择的种子色。 | `FIDELITY` | 种子色几乎原样成为主色 |
| 9 | `Content` | 内容色 | 沿用内容本身的取色。 | `CONTENT` | 同 Fidelity，偏内容驱动 |

UI 呈现（`SettingsScreen.kt:268-286`）：`FilterChip` 每行 3 个（`chunked(3)`），共 3 行；选中项显示，下方一行 `labelSmall` 显示当前风格描述。**QML 侧只要把 9 个 key + 中文标签 + 描述 + Variant 枚举做成常量表即可，不需要理解算法。**

### 1.6 2021 / 2025 色彩规范差异

| 项 | SPEC_2021（默认） | SPEC_2025 |
| --- | --- | --- |
| 入口 | 设置 →"色彩空间"→ `2021`（`SettingsScreen.kt:288-297`） | 同处 `2025` |
| 默认 | ✓（`core/Storage.kt:88` `colorSpec = "SPEC_2021"`） | — |
| 实现 | `ColorSpec.SpecVersion.SPEC_2021`（`theme/Theme.kt:120-121`） | `SPEC_2025` |
| 视觉差异 | 经典 M3 baseline：容器层 tone 差 2–4 级，outline 系列偏低对比 | 2025 规范重算 tone/chroma 分配：**surfaceContainer* 层级更分明、容器与背景对比略强、primary/secondary 的色度分配更"分层"**；fixed 角色取值也不同 |
| 影响范围 | 影响**全部 49 个角色**（不是局部开关）；同一 seed 下 2025 的主色通常比 2021 更"沉"、容器分层更清晰 | 同 |
| QML 处理 | **零特殊处理**：后端下发什么就用什么；切换时 420ms 补间 | 同 |

> 实现提示：`material-kolor` 的 `ColorSpec.SpecVersion` 只影响 `DynamicScheme` 生成，QML 侧不要试图用 `Material` 主题近似 —— Qt Quick Controls 的 `Material` style 是 **M2**（Material 2），与本项目的 M3 视觉（圆角、容器色、导航条指示器）差异明显，因此 `main.cpp` 已指定 `Basic` style，M3 外观需全部自绘。

### 1.7 主题色动画（强调色动画时长）

| 项 | 值 | 依据 |
| --- | --- | --- |
| 颜色补间时长 | **420 ms** | `theme/Theme.kt:181` |
| 缓动 | `FastOutSlowInEasing`（M3 标准曲线）≈ `cubic-bezier(0.4, 0.0, 0.2, 1.0)` | `theme/Theme.kt:181` |
| 作用对象 | 37 个非 fixed 角色，各自独立 `animateColorAsState` | `theme/Theme.kt:188-225` |
| 触发场景 | 切深浅色、切动态取色开关、切种子色、切调色风格、切色彩规范、**切歌（动态取色开启时）** | `NeriApp.kt:86-92` |
| QML 实现建议 | 颜色属性统一包一层 `Behavior on color { ColorAnimation { duration: 420; easing.type: Easing.Bezier; easing.bezierCurve: [0.4,0,0.2,1] } }` 的派生属性（`qml/globals/Theme.qml` 提供 `primary` 原始值 + `primaryAnimated` 或直接在 `Theme` 内置 420ms 的 ColorAnimation） | — |

**注意**：这套 420ms 是**全局唯一**的主题色动画。其它交互动效另有自己的时长（220/180/260/900ms 等，见 §4、§5），不要混用。

### 1.8 QML 侧如何接收颜色（契约）

后端（Kotlin）算好 49 个 role 后，以事件 + 快照两种方式下发；`Theme.qml` 只做"存 + 动画 + 暴露"。建议协议（与既有 `BackendClient` 的 `event(name, data)` 对齐）：

```jsonc
// 事件名： "theme"
{
  "event": "theme",
  "data": {
    "dark": false,
    "seed": "A8475F",            // 当前实际使用的种子（动态取色时为封面种子）
    "seedSource": "cover",       // "cover" | "settings"
    "paletteStyle": "TonalSpot",
    "colorSpec": "SPEC_2021",
    "roles": {
      "primary": "#3D628C",
      "onPrimary": "#FFFFFF",
      "primaryContainer": "#D3E4FF",
      "onPrimaryContainer": "#001D33",
      "inversePrimary": "#A5C9F5",
      "secondary": "#52606F",
      "onSecondary": "#FFFFFF",
      "secondaryContainer": "#D5E4F6",
      "onSecondaryContainer": "#0F1D2A",
      "tertiary": "#685779",
      "onTertiary": "#FFFFFF",
      "tertiaryContainer": "#EFDBFF",
      "onTertiaryContainer": "#231532",
      "background": "#FCFCFF",
      "onBackground": "#1A1C1E",
      "surface": "#FCFCFF",
      "onSurface": "#1A1C1E",
      "surfaceVariant": "#DFE2EA",
      "onSurfaceVariant": "#42474E",
      "surfaceTint": "#3D628C",
      "inverseSurface": "#2F3033",
      "inverseOnSurface": "#F1F0F4",
      "error": "#BA1A1A",
      "onError": "#FFFFFF",
      "errorContainer": "#FFDAD6",
      "onErrorContainer": "#410002",
      "outline": "#73777F",
      "outlineVariant": "#C3C7CF",
      "scrim": "#000000",
      "surfaceBright": "#FCFCFF",
      "surfaceDim": "#DCD9DE",
      "surfaceContainer": "#F0F0F4",
      "surfaceContainerHigh": "#EAEAEE",
      "surfaceContainerHighest": "#E4E4E8",
      "surfaceContainerLow": "#F6F6FA",
      "surfaceContainerLowest": "#FFFFFF",
      "primaryFixed": "#D3E4FF",
      "primaryFixedDim": "#A5C9F5",
      "onPrimaryFixed": "#001D33",
      "onPrimaryFixedVariant": "#23486B",
      "secondaryFixed": "#D5E4F6",
      "secondaryFixedDim": "#B9C8DA",
      "onSecondaryFixed": "#0F1D2A",
      "onSecondaryFixedVariant": "#3B4857",
      "tertiaryFixed": "#EFDBFF",
      "tertiaryFixedDim": "#D3BFE4",
      "onTertiaryFixed": "#231532",
      "onTertiaryFixedVariant": "#4F3F60"
    }
  }
}
```

> 上表的十六进制是 **seed=0061A4 / TonalSpot / SPEC_2021 / 浅色** 的**参考近似值**，仅用于 QML 首批自检与对比截图；**正式实现必须以后端 material-kolor 输出为准**（HCT 取整在两端实现里可能差 1–2 个色阶）。深色对应 P80/S80 序列。

`Theme.qml` 契约（与 `main.cpp` 的 `globals/Theme.qml` 对应）：

```qml
// qml/globals/Theme.qml —— 只做三件事：接收、动画、暴露常量
QtObject {
    id: theme
    property bool dark: false
    // 49 个颜色属性（原始值，来自后端事件）
    property color primary: "#3D628C"
    // …（onPrimary、primaryContainer、…、onTertiaryFixedVariant）
    // 动画版本：QML 里对每个 color 属性加 Behavior，或在 apply() 中统一赋值
    function apply(data) {
        dark = data.dark
        const roles = data.roles
        for (const key in roles) if (theme.hasOwnProperty(key)) theme[key] = roles[key]
    }
}
```

实现建议：
1. **属性名与 Kotlin 完全同名**（`surfaceContainerLow` 而不是 `surface_container_low`），方便 `grep` 对照与自动核对；
2. 每个颜色属性挂 `Behavior on primary { ColorAnimation { duration: 420; easing.type: Easing.Bezier; easing.bezierCurve: [0.4, 0, 0.2, 1] } }`（其余 36 个同理；12 个 fixed 角色**不挂** Behavior，保持瞬变）；
3. `Theme` 同时导出**非颜色 token**：`uiScale`、`radius*`、`spacing*`、`fontSize*`、`elevation*`、`breakpointWide`（见 §2/§3），避免散落在组件里；
4. 未收到事件前用上表参考值兜底，保证首帧不黑屏；收到后立即 `apply()`。

### 1.9 代码里出现的非角色硬编码颜色（必须原样复刻）

| 颜色 | 位置 | 用途 |
| --- | --- | --- |
| `#000000` α0.40 | `NeriApp.kt:410` | 迷你播放条功能面板展开时的全屏遮罩（点击关闭） |
| `#000000` α0.45 | `PlayerComponents.kt:617` | `OverlayPanel`（队列/音量/睡眠/音效/下载）全屏遮罩 |
| `#000000` α0.40 | `NowPlayingScreen.kt:499` | 封面缓冲时叠加的 scrim（`scrim` 角色 α0.4） |
| `#000000` α0.55 | `FloatingLyrics.kt:386` | 悬浮歌词 hover 时控制条的黑色胶囊底 |
| `#FFFFFF` | `LoginFlow.kt:75` | 二维码卡片底色（**固定白色**，不随主题） |
| `#000000` | `LoginFlow.kt:88` | 二维码码点颜色 |
| `#FF8A80` | `FloatingLyrics.kt:400` | 悬浮歌词控制条"关闭"图标的粉红警示色 |
| `#4F8EF7` | `DesktopLookAndFeel.kt:38` | Swing 菜单（文件选择器等系统对话框）选中项底色，**跟随不了主题**，仅作系统对话框的近似 |
| 悬浮歌词 10 色 | `FloatingLyricsStyle.kt:9-28` | 白/黑/红 `F44336`/橙 `FF9800`/黄 `FFEB3B`/绿 `4CAF50`/青 `00BCD4`/蓝 `2196F3`/紫 `9C27B0`/粉 `E91E63` |

---
## 2. 字体与排版

### 2.1 文本角色：Material3 默认 Typography（代码零覆写）

`theme/Theme.kt:247` 传的是 `typography = Typography()`，**没有任何自定义字体、字号、行高、字距**。因此下表就是全应用的字号基线（Compose Material3 1.x / M3 baseline tokens）：

| # | 角色 | 字号 | 行高 | 字重 | 字距 | 项目中的实际用途（源码位置） |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | `displayLarge` | 57sp | 64sp | W400 | -0.25sp | 未使用 |
| 2 | `displayMedium` | 45sp | 52sp | W400 | 0 | 未使用 |
| 3 | `displaySmall` | 36sp | 44sp | W400 | 0 | 未使用 |
| 4 | `headlineLarge` | 32sp | 40sp | W400 | 0 | 未使用 |
| 5 | `headlineMedium` | 28sp | 36sp | W400 | 0 | 未使用 |
| 6 | `headlineSmall` | 24sp | 32sp | W400 | 0 | 播放页歌名（`NowPlayingScreen.kt:513`，另加 SemiBold）、歌单/歌手/收藏夹详情大标题（`DetailScreens.kt:130`，另加 SemiBold）、睡眠定时器剩余时间（`PlayerComponents.kt:811`） |
| 7 | `titleLarge` | 22sp | 28sp | W400 | 0 | 播放统计数值（`DetailScreens.kt:848`，另加 SemiBold） |
| 8 | `titleMedium` | 16sp | 24sp | W500 | 0.15sp | 区块标题（`Components.kt:82`，另加 SemiBold）、面板标题（`PlayerComponents.kt:589`）、设置卡片标题（`SettingsScreen.kt:737`）、播放页顶部标题（`NowPlayingScreen.kt:184`）、封面下方单行歌词（`NowPlayingScreen.kt:674`）、睡眠"播完停止"提示（`PlayerComponents.kt:817`） |
| 9 | `titleSmall` | 14sp | 20sp | W500 | 0.1sp | 空状态标题（`Components.kt:112`）、音效/播放速度等小节标题（`PlayerComponents.kt:868,877,888,902`）、同步步骤标题（`SyncSettings.kt:112,149`） |
| 10 | `bodyLarge` | 16sp | 24sp | W400 | 0.5sp | 歌曲行标题（`Components.kt:239`）、歌曲操作项（`DetailScreens.kt:406`、`NowPlayingScreen.kt:751`）、设置开关标题（`SettingsScreen.kt:775`）、账号名（`LoginFlow.kt:269`）、歌手搜索结果名（`ExploreScreen.kt:396`）、托盘面板歌曲名（`TrayControlPanel.kt:393`） |
| 11 | `bodyMedium` | 14sp | 20sp | W400 | 0.25sp | 次级正文/说明（`Components.kt:145,248`）、卡片标题（`Components.kt:370,404,450,480`）、面板动作文字（`TrayControlPanel.kt:482`）、错误卡正文（`Components.kt:145`）、统计行（`DetailScreens.kt` 多处） |
| 12 | `bodySmall` | 12sp | 16sp | W400 | 0.4sp | 歌曲行副标题（`Components.kt:247`）、空状态 hint（`Components.kt:119`）、文件夹路径（`SettingsScreen.kt:402`）、下载目录（`DownloadSettings.kt:54`） |
| 13 | `labelLarge` | 14sp | 20sp | W500 | 0.1sp | 按钮文字（M3 内部，`TextButton`/`Button` 默认）、错误卡"重试"（`Components.kt:151`）、音量百分比（`PlayerComponents.kt:789`）、设置项标签（`SettingsScreen.kt:757`）、快捷键键位文字（`ShortcutSettings.kt:305`）、全局键位（`ShortcutSettings.kt:219`） |
| 14 | `labelMedium` | 12sp | 16sp | W500 | 0.5sp | 歌曲时长（`Components.kt:267`）、统计行（`DetailScreens.kt:688`）、"收起/展开"（`SettingsScreen.kt:744`）、快捷键表头（`ShortcutSettings.kt:81`）、队列提示（`PlayerComponents.kt:681`）、列表计数（`LibraryScreen.kt:600`） |
| 15 | `labelSmall` | 11sp | 16sp | W500 | 0.5sp | 卡片副标题（`Components.kt:376,418,456,485`）、说明文字（大量，如 `SettingsScreen.kt:175,283`）、面板 hint（`TrayControlPanel.kt:493`）、悬浮歌词设置提示（`FloatingLyricsSettings.kt:210`） |

**使用分布**：全代码库 `MaterialTheme.typography.*` 引用约 110 处，其中 `labelSmall`（说明性文字）与 `bodySmall/labelMedium`（列表副行）占比最高；`display*`、`headlineLarge/Medium` **完全未使用**。QML 侧可只实现 11 个实际用到的角色，但建议 15 个全量定义以免后续页面用到时临时写死。

**M3 默认字重语义**：`titleMedium/titleSmall/labelLarge/labelMedium/labelSmall` 默认 W500（Medium）；`body*` 与 `headline*` 默认 W400（Normal）。代码里额外加的 SemiBold/Bold 见 §2.3。

### 2.2 代码中硬编码的字号 / 行高 / 字距（全部清单）

除 `LyricsPane`、悬浮歌词外，没有任何 `fontSize=` 覆盖；`letterSpacing` 全代码 0 处覆写。

| 位置 | 文本 | 字号 | 行高 | 字重 | 颜色 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| `PlayerComponents.kt:527-538` | 歌词正文（`LyricsPane`） | `20sp × lyricsFontScale` | `28sp × lyricsFontScale` | 当前行 **Bold**，其余 Normal | 当前 `primary`，其余 `onSurfaceVariant` | 用户可调 0.7–1.8（默认 1.0，`core/Storage.kt:97`） |
| `PlayerComponents.kt:540-549` | 歌词翻译行 | `15sp × lyricsFontScale` | `21sp × lyricsFontScale` | Normal（默认） | 当前 `primary` α0.8，其余 `onSurfaceVariant` α0.7 | 仅在 `showLyricTranslation` 时渲染 |
| `FloatingLyrics.kt:444-448` | 悬浮歌词主行 | `settings.floatingLyricsFontSize` sp（默认 **30**） | 未设置（用字体自身行高，约 1.2×） | 主行 **Bold**，翻译行 Normal | 用户选色 + α | 见 §7 |
| `FloatingLyrics.kt:371` | 悬浮歌词翻译行 | 主字号 × **0.78** | 同上 | Normal | 用户选色 + `translationAlpha`（默认 0.75） | — |
| `FloatingLyrics.kt:526` | 设置页悬浮歌词预览主行 | 主字号 × **0.7** | — | Bold | — | 仅预览（`FloatingLyricsPreview`） |
| `FloatingLyrics.kt:535` | 设置页预览翻译行 | 主字号 × **0.55** | — | Normal | — | 仅预览 |
| `FloatingLyricsStyle.kt:127-134` | 悬浮窗高度推算 | 行高取 **字号 × 1.6**；翻译占 **字号 × 1.35** | — | — | — | 见 §7.1 高度公式 |
| `NowPlayingScreen.kt:303-307` | 全屏歌词顶部"字号 1.00x" | `labelSmall`（非硬编码） | — | — | `onSurfaceVariant` | 纯文本标签 |
| `DesktopLookAndFeel.kt:61` | Swing 系统对话框字体 | **14pt** / PLAIN | — | — | — | 仅影响文件选择器等 AWT 对话框 |

**`sp` 与缩放的关系**（QML 必须理解，否则字号会放大两次）：Compose 里 `2.4 sp 的物理像素 = sp × fontScale × density`。项目把**界面缩放**（100%–250%）注入到 `LocalDensity.density`（`AppLayout.kt:186-201`），而 `fontScale` 保持原值 —— 因此**字号会随界面缩放一起变大，但不会二次放大**。QML 侧对应做法：所有字号与尺寸统一乘 `Theme.uiScale`，**不要**再额外乘 `font.pointSize` 的系统缩放。

### 2.3 代码中的字重覆盖（全部清单）

| 位置 | 文本/元素 | 字重 |
| --- | --- | --- |
| `HomeScreen.kt:149` | 首页大标题（LargeTopAppBar title） | **Bold(700)** |
| `Components.kt:83` | `SectionHeader` 标题 | SemiBold(600) |
| `DetailScreens.kt:131` | 详情页 `CollectionHeader` 大标题 | SemiBold |
| `DetailScreens.kt:849` | 统计数值 `StatCard` | SemiBold |
| `NowPlayingScreen.kt:184` | 播放页顶部"正在播放" | SemiBold |
| `NowPlayingScreen.kt:514` | 播放页歌名 | SemiBold |
| `PlayerComponents.kt:531` | 歌词当前行 | Bold |
| `PlayerComponents.kt:592` | `OverlayPanel` 标题 | SemiBold |
| `SettingsScreen.kt:737` | 设置分组标题 | SemiBold |
| `LibraryScreen.kt:767` | 收藏页头卡"我喜欢的音乐" | SemiBold |
| `SyncSettings.kt:84` | 同步状态"已配置/未配置" | Medium(500) |
| `SyncSettings.kt:346` | 同步卡片标题 | SemiBold |
| `ShortcutSettings.kt:68` | 快捷键卡片标题 | SemiBold |
| `BackgroundSettings.kt:47` | 后台与系统控制标题 | SemiBold |
| `DownloadSettings.kt:43` | 下载卡片标题 | SemiBold |
| `FloatingLyricsSettings.kt:53` | 悬浮歌词卡片标题 | SemiBold |
| `TrayControlPanel.kt:393` | 托盘面板歌曲名 | SemiBold |
| `FloatingLyrics.kt:446` | 浮窗主歌词 | Bold |
| `LoginFlow.kt:270` | 账号平台名 | Medium |

> QML 实现：Qt 的 `font.weight` 取值 100–900。Compose `FontWeight.SemiBold` = 600、`Medium` = 500、`Bold` = 700。**必须确认实际加载的中文字体是否有 500/600 字重**：Noto Sans CJK SC 只有 Regular/Bold 两档，W500/W600 会被合成或用 Bold 顶替，最终观感会偏粗。建议在 `Theme.qml` 里做一次字重映射（例如 600 → Bold、500 → Medium 或 Regular），并在真机上比对 `docs/screenshots/settings.png` 的"标题 SemiBold + 说明 labelSmall"层级关系。

### 2.4 字体族与中文字体选择

现状：
- Compose Desktop 使用 `FontFamily.Default`（Skia 平台默认；Linux 上通常落到 fontconfig 的 `sans-serif`，中文由 fontconfig 做字形回退）。
- 原生系统对话框（JFileChooser / Alert 等 Swing 组件）在 `DesktopLookAndFeel.kt:47-62` 里按优先级挑字体：

| 顺序 | 候选字体 | 备注 |
| --- | --- | --- |
| 1 | `Noto Sans CJK SC` | Debian/Ubuntu 上最常见（`fonts-noto-cjk`） |
| 2 | `Source Han Sans SC` | 思源黑体 |
| 3 | `Microsoft YaHei` | 双系统用户 |
| 4 | `WenQuanYi Micro Hei` | 文泉驿 |
| 5 | `Sarasa Gothic SC` | 更纱黑体（等宽/比例均有） |
| 6 | `PingFang SC` | macOS 迁移 |
| 7 | `DejaVu Sans` | 兜底西文 |
| 8 | `Font.SANS_SERIF` | 逻辑字体，交由 fontconfig 回退 |

**QML 建议**（与上表保持一致的优先级，用逗号分隔的 fallback 列表）：

```qml
font.family: "Noto Sans CJK SC, Source Han Sans SC, Sarasa Gothic SC, WenQuanYi Micro Hei, DejaVu Sans, sans-serif"
```

实现要点：
1. 在 Qt 里 `font.family` 只接受**单个**家族名，多值需要用 `Qt.fontFamilies()` 或 `FontLoader`/`QFont::setFamilies`；QML 侧可在 `Theme.qml` 里用 `Qt.application.font.family` 检测，或在 C++ 启动时用 `QFontDatabase::families()` 按上表顺序挑一个写入 `Theme.fontFamily`。
2. **不要**在 QML 里直接写 `"Noto Sans CJK SC"` —— 缺失该字体的机器会整体退回 `DejaVu Sans` 且中文变方框。必须走候选检测。
3. 数字/拉丁文字建议保持同一 CJK 字体（Compose 现状就是同一 family），不要单独给西文开 Inter/Roboto，否则"3:06""192"等数字宽度和截图不一致。
4. 等宽场景：当前项目没有等宽字体需求（无代码块/对齐数字），可忽略。
5. 打包建议：若目标环境不保证 `fonts-noto-cjk`，可在 `qml/resources/fonts/` 内嵌 `NotoSansSC-Regular.otf`/`Bold.otf` 并用 `FontLoader`，代价是包体 +10MB 左右；图标字体已经内嵌（见 §6）。

### 2.5 文本截断、换行与跑马灯规则

| 场景 | 规则 | 源码 |
| --- | --- | --- |
| 歌曲行标题 | 1 行 + `Ellipsis` | `Components.kt:241-242` |
| 歌曲行副标题 | 1 行 + `Ellipsis` | `Components.kt:249-251` |
| 迷你播放条歌名/歌手 | 1 行 + `Ellipsis` + **跑马灯**（`basicMarquee`） | `PlayerComponents.kt:288-299` |
| 详情页大标题 | 最多 2 行 + `Ellipsis` | `DetailScreens.kt:132-133` |
| 详情页副标题 | 最多 2 行 + `Ellipsis` | `DetailScreens.kt:140-141` |
| 卡片标题（歌单/专辑/歌手） | 1 行 + `Ellipsis` | `Components.kt:371-372` 等 |
| 顶部栏标题 | 1 行 + `Ellipsis` | `DetailScreens.kt:85` |
| 二维码状态文字 | 无限制 + 居中 | `LoginFlow.kt:223-227` |
| 全屏歌词行 | 单行（`softWrap=false`，超出可见，可跑马灯） | `FloatingLyrics.kt:462-465`（浮窗） |
| 歌词页行 | 自动换行，无 maxLines | `PlayerComponents.kt:527-538` |
| 设置项说明 | 自动换行（无 maxLines） | `SettingsScreen.kt:777-782` |
| 文件路径 | 1 行 + `Ellipsis` | `SettingsScreen.kt:404`、`DownloadPanel.kt:73` |
| 信息行 value | 最多 2 行 + `Ellipsis` | `SettingsScreen.kt:799-800` |

**跑马灯规格**（`PlayerComponents.kt:437-438` → `basicMarquee()` 默认参数；浮窗另指定）：连续循环、初始延迟 1500ms（浮窗）/ 默认 0（迷你播放条），仅在文本超出宽度时滚动。QML 侧建议自写 `MarqueeText` 组件（`Text` + `NumberAnimation on x` 或用 `TextMetrics` 判断是否需要跑），因为 Qt 没有内置 marquee。

### 2.6 文本行高层级速查（判断行高时用）

| 层级 | 行高 | 典型用途 |
| --- | --- | --- |
| 24sp 行高 | `titleMedium`/`bodyLarge`/`bodyMedium` | 列表主副行、面板标题 |
| 20sp 行高 | `titleSmall`/`labelLarge`/`bodyMedium` | 按钮、键位框、说明 |
| 16sp 行高 | `labelSmall`/`labelMedium`/`bodySmall` | 卡片副标题、说明文字 |
| 32/36/40/44sp 行高 | `headlineSmall`/`headlineMedium`/`headlineLarge`/`displaySmall` | 播放页歌名、详情页标题 |

---

## 3. 间距 / 圆角 / 阴影 / 尺寸

### 3.1 窗口、断点与内容容器

| 常量/规格 | 值 | 依据 |
| --- | --- | --- |
| 主窗口默认尺寸 | **1180 × 820** | `Main.kt:66` |
| 窗口尺寸夹取 | 不超过屏幕可视区 **94% 宽 / 92% 高**（物理像素换算后） | `AppLayout.kt:169-173` |
| 窗口初始位置 | 屏幕居中 | `Main.kt:69` |
| 窗口标题 | `▶/⏸ 歌曲 - 歌手 · NeriPlayer`；无歌时 `音理音理!! · NeriPlayer` | `Main.kt:174-180` |
| 宽屏断点 | 内容宽度 **≥ 900dp** → WIDE 布局 | `AppLayout.kt:47`、`NeriApp.kt:55` |
| 宽屏内容左右留白 | **20dp** | `AppLayout.kt:50` |
| 窄屏内容最大宽度 | **1180dp**，水平居中 | `AppLayout.kt:41,68-83` |
| 窄屏底部导航收拢宽度 | **720dp**（窗口比内容区还宽时，导航收成居中浮动条） | `AppLayout.kt:44`、`PlayerComponents.kt:101,113-124` |
| 全屏歌词正文最大宽度（宽屏） | **960dp** 居中 | `AppLayout.kt:53`、`NowPlayingScreen.kt:328-332` |
| 播放页自身再判宽 | `maxWidth >= 900.dp`（**硬编码**，与 `AppWideBreakpoint` 重复） | `NowPlayingScreen.kt:211` |
| 界面缩放档位 | 跟随系统 / 100% / 125% / 150% / 175% / 200% / 250% | `core/UiScale.kt:23-32` |
| 缩放范围 | 1.0 – 3.0 | `core/UiScale.kt:18-19` |

### 3.2 全局骨架尺寸（含 M3 默认值）

| 区域 | 高度/尺寸 | 依据 | 说明 |
| --- | --- | --- | --- |
| `TopAppBar` | **64dp**（M3 默认，未覆写） | `DetailScreens.kt:84`、`ExploreScreen.kt:147`、`LibraryScreen.kt:222`、`SettingsScreen.kt:120` | 标题 `titleLarge` 22sp，返回图标 `IconButton` 48dp |
| `LargeTopAppBar`（首页） | 展开 **152dp** / 收起 **64dp**（M3 默认，`exitUntilCollapsedScrollBehavior`） | `HomeScreen.kt:148-159`、`:89` | 标题 22sp Bold；滚动折叠 |
| `NavigationBar` | **80dp**（M3 默认） | `PlayerComponents.kt:93-133` | 底色 `surfaceContainer`，`tonalElevation=0` |
| `NavigationBarItem` | 图标 24dp + 文字 `labelMedium`；选中指示器 64×32dp 胶囊 | M3 默认 | 4 项：首页/探索/媒体库/设置（`Navigation.kt:12-17`）；`alwaysShowLabel = true` |
| 迷你播放条卡片 | `heightIn(min = 70dp)`；外形高度约 70–78dp | `PlayerComponents.kt:233` | 外边距 `horizontal 8 / vertical 4` |
| 底部栏总占用 | ≈ 70（播放条）+ 80（导航条）+ 8（外边距）≈ **158dp** | 推算 | 内容区滚动时需预留，否则最后一行会被遮住（截图 `home.png` 底部） |
| 内容区底部内边距 | 列表 `contentPadding bottom = 24dp`（多数页面） | `HomeScreen.kt:145`、`LibraryScreen.kt:630` 等 | 部分页面 32dp（设置 `SettingsScreen.kt:116`） |
| 页面外边距 | 卡片 `horizontal 12dp`，区块标题 `horizontal 16dp` | `Components.kt:68,499`、`SettingsScreen.kt:717` | — |

### 3.3 圆角（radius）全表

| 元素 | 圆角 | 源码 |
| --- | --- | --- |
| 迷你播放条卡片 | **18dp** | `PlayerComponents.kt:223` |
| 迷你播放条封面 | 10dp | `PlayerComponents.kt:283` |
| 迷你播放条进度条 | 2dp（clip；实际绘制为全圆角条） | `PlayerComponents.kt:243-257` |
| 底部导航浮动条（窄屏收拢时） | **24dp** | `PlayerComponents.kt:119` |
| 歌曲列表卡（首页区块） | **20dp** | `HomeScreen.kt:442` |
| 通用区块卡片 `SectionSurface` | **20dp** | `Components.kt:500` |
| 设置卡片 `SettingsSection` / 同步 / 下载 / 后台 / 悬浮歌词 / 快捷键 | **20dp** | `SettingsScreen.kt:718`、`SyncSettings.kt:341`、`DownloadSettings.kt:39`、`BackgroundSettings.kt:43`、`FloatingLyricsSettings.kt:47`、`ShortcutSettings.kt:64` |
| 统计数值卡 `StatCard` | **18dp** | `DetailScreens.kt:836` |
| 收藏页头卡 | **20dp** | `LibraryScreen.kt:753` |
| 首页"继续播放"卡 | **18dp** | `HomeScreen.kt:359` |
| 首页继续播放卡封面 | 12dp | `HomeScreen.kt:367` |
| 首页继续播放卡中心播放徽标 | 13dp（26dp 圆形） | `HomeScreen.kt:370` |
| 歌单/专辑/歌手/最近添加 卡片外框 | **16dp** | `Components.kt:342,391,435,471`、`HomeScreen.kt:411` |
| 卡片内封面 | **14dp** | `Components.kt:349,398,444(圆形62),475`、`HomeScreen.kt:415` |
| 歌手头像（列表卡） | 62dp（124dp 直径圆形） | `Components.kt:444` |
| 详情页头图封面 | **20dp**（歌单/收藏夹） | `DetailScreens.kt:225,552` |
| 详情页歌手头像 | 66dp（132dp 直径圆形） | `DetailScreens.kt:431,626` |
| 播放页封面 | **24dp**（220/300dp） | `NowPlayingScreen.kt:495,498` |
| 播放页歌词侧栏 | **24dp** | `NowPlayingScreen.kt:240` |
| 覆盖面板 `OverlayPanel` | **24dp** | `PlayerComponents.kt:580` |
| 覆盖面板内歌词行 / 可点击行 | 10dp | `PlayerComponents.kt:523` |
| 错误卡 `ErrorCard` | **16dp** | `Components.kt:136` |
| 错误卡"重试"点击区 | 8dp | `Components.kt:154` |
| 歌曲行封面 | **9dp**（46dp） | `Components.kt:234` |
| 悬浮歌词窗口 | **14dp** | `FloatingLyrics.kt:339` |
| 悬浮歌词 hover 控制条 | 10dp | `FloatingLyrics.kt:385` |
| 悬浮歌词设置预览 | 12dp | `FloatingLyrics.kt:507` |
| 托盘控制面板 | **20dp** | `TrayControlPanel.kt:232,234` |
| 托盘面板动作行 | 10dp | `TrayControlPanel.kt:471` |
| 托盘面板封面 | 12dp（46dp） | `TrayControlPanel.kt:387` |
| 二维码卡片 | 16dp | `LoginFlow.kt:74` |
| 快捷键键位框 | 10dp（132×36dp） | `ShortcutSettings.kt:204,209,260,275` |
| 主题色/悬浮歌词色块 | 圆形（`CircleShape`） | `SettingsScreen.kt:233`、`FloatingLyricsSettings.kt:260` |
| M3 对话框（AlertDialog） | **28dp**（M3 默认） | 全局 `AlertDialog` |
| M3 按钮 / Chip / Snackbar | 按钮 20dp 全圆角、FilterChip 8dp、Snackbar 4dp（M3 默认） | 全局 |

### 3.4 阴影 / 高度（elevation）

M3 的"阴影"由 `tonalElevation`（叠一层 `surfaceTint`）与 `shadowElevation`（真实投影）两个参数表达：

| 元素 | tonalElevation | shadowElevation | 源码 | QML 实现 |
| --- | --- | --- | --- | --- |
| 迷你播放条 | **2dp** | 默认 | `PlayerComponents.kt:229` | 叠一层 `primary` α≈0.05 或轻微 DropShadow（blur 4, y 2） |
| 底部导航 | 0dp（禁用） | 0 | `PlayerComponents.kt:121,129` | 纯色，不加阴影 |
| 覆盖面板 `OverlayPanel` | **6dp** | 默认 | `PlayerComponents.kt:582` | 叠 `primary` α≈0.11 + 投影 |
| 托盘控制面板 | 0dp | **16dp** | `TrayControlPanel.kt:236-237` | 明显投影，`MultiEffect`/`DropShadow` blur≈24、offset 0,8、α≈0.25 |
| 卡片（`Surface` 无 elevation 参数） | 0 | 0 | 各处 | 纯色块，**无阴影** |
| 对话框 / 菜单 | M3 内部 6dp | — | — | 投影 + 28dp 圆角 |

> 结论：整套 UI **几乎不用阴影**，层级靠"表面容器色阶"（surface → surfaceContainerLow → surfaceContainer → High → Highest）表达。QML 里不要给卡片加 `DropShadow`，否则和截图不符（对比 `docs/screenshots/home.png`：卡片是纯色块 + 20dp 圆角，无边框无阴影）。仅两个例外：迷你播放条（轻微）、托盘面板（明显）。

### 3.5 组件尺寸全表（按钮、图标、封面、行高）

| 元素 | 尺寸 | 源码 |
| --- | --- | --- |
| 迷你播放条左侧封面 | **42dp**（圆角 10） | `PlayerComponents.kt:283` |
| 迷你播放条 上一/下一首按钮 | 36dp 点击区 / 22dp 图标 | `PlayerComponents.kt:305,315` |
| 迷你播放条 播放/暂停（FilledIconButton） | **38dp** / 22dp 图标 | `PlayerComponents.kt:308-313` |
| 迷你播放条 右侧功能按钮 | 34dp / 19dp 图标（音量、音效、队列、睡眠、悬浮歌词） | `PlayerComponents.kt:325-354` |
| 迷你播放条 左右留白 | `horizontal 10 / vertical 12` | `PlayerComponents.kt:278` |
| 迷你播放条 进度条 | 高 8dp 容器，**常态 2dp**、hover/拖拽时 **8dp** | `PlayerComponents.kt:241-245` |
| 歌曲行封面 | **46dp**（圆角 9） | `Components.kt:234` |
| 歌曲行序号列宽 | **30dp** | `Components.kt:215` |
| 歌曲行 hover 播放图标 | 17dp | `Components.kt:223` |
| 歌曲行 more 按钮 | 34dp / 18dp 图标 | `Components.kt:272-276` |
| 歌曲行已下载图标 | 15dp | `Components.kt:261` |
| 歌曲行内边距 | `horizontal 12 / vertical 6` | `Components.kt:184` |
| 歌曲行高度（推算） | ≈ 46（封面）+ 12（上下 padding）= **58dp** | — |
| 空状态图标 | **54dp**；标题 `titleSmall`；hint `bodySmall` | `Components.kt:103-121` |
| 空状态内边距 | `vertical 40 / horizontal 24`；图标与标题间 12，标题与 hint 间 6 | `Components.kt:97-121` |
| 错误卡 | 图标 20dp，内边距 14dp，上下外边距 8dp | `Components.kt:133-147` |
| 区块标题 | 图标 20dp + 间距 8dp；内边距 `h16 v10` | `Components.kt:66-79` |
| 列表卡封面（歌单/专辑/收藏/最近添加） | **138dp**（圆角 14），卡片外宽 **150dp** | `Components.kt:340,348`、`HomeScreen.kt:409,415` |
| 歌手卡 | 外宽 **136dp**，头像 **124dp** 圆形 | `Components.kt:433,443` |
| 继续播放卡 | 宽 **220dp**，封面 56dp，内边距 8dp | `HomeScreen.kt:358,363,367` |
| 详情页封面 | **132dp**（歌单/收藏夹圆角 20；歌手圆形 66） | `DetailScreens.kt:225,431,552,626` |
| 播放页封面（窄/宽） | **220dp / 300dp**，圆角 24 | `NowPlayingScreen.kt:269,229,495` |
| 播放页 上一/下一首 | 52dp / 34dp 图标 | `NowPlayingScreen.kt:595,608` |
| 播放页 播放/暂停 | **68dp** / 36dp 图标 | `NowPlayingScreen.kt:600-606` |
| 播放页 随机/循环 | 48dp（IconButton 默认） | `NowPlayingScreen.kt:588,611` |
| 播放页 工具行按钮 | 48dp，行间距 6dp | `NowPlayingScreen.kt:624-657` |
| 播放页 传输控制行间距 | **18dp** | `NowPlayingScreen.kt:586` |
| 播放页 歌词侧栏宽 | **420dp**，内边距 `end16/top8/bottom16` | `NowPlayingScreen.kt:239` |
| 播放页 进度条 | M3 Slider 默认（高 48dp 交互区） + 时间标签 `labelMedium` | `PlayerComponents.kt:453-479` |
| 覆盖面板（非紧凑） | 宽 **420dp**、全高、外边距 12dp | `PlayerComponents.kt:573` |
| 覆盖面板（紧凑 Popup） | 宽 **380dp**、最大高 **560dp** | `PlayerComponents.kt:570` |
| 覆盖面板标题栏 | 内边距 `h18 v14`，右侧关闭 `IconButton` 48dp | `PlayerComponents.kt:586-598` |
| 队列行 | 内边距 `h14 v6`；序号列 26dp；上下移/删除按钮 30dp / 16dp 图标 | `PlayerComponents.kt:708-756` |
| 队列当前播放指示器 | 14dp | `PlayerComponents.kt:713` |
| 音量面板 | 内容内边距 20dp | `PlayerComponents.kt:779` |
| 睡眠定时器 | 4 个 15/30/60/90 按钮；剩余时间 `headlineSmall` 主色 | `PlayerComponents.kt:808-827` |
| 音效面板 | 内边距 `h20 v8`；频段标签宽 58dp、数值宽 46dp | `PlayerComponents.kt:862,936,946` |
| 下载任务行 | 内边距 `h14 v6`；封面 38dp；按钮 32dp/17dp；进度条高 **4dp** | `DownloadPanel.kt:101-177` |
| 二维码 | **240dp** 卡片，内边距 12dp | `LoginFlow.kt:72-77,197` |
| 设置卡片 | 外边距 `h12 v6`；内边距 `v8`；标题栏 `h16 v10`；内容 `h16 v4` | `SettingsScreen.kt:717-747` |
| 设置卡片图标 | 22dp（主色） | `SettingsScreen.kt:733` |
| 设置项标签 | `labelLarge`，下边距 6dp | `SettingsScreen.kt:758` |
| 开关行 | 上下内边距 6dp | `SettingsScreen.kt:771` |
| 信息行 | 标签列宽 **96dp**；上下 4dp | `SettingsScreen.kt:790-795` |
| 主题色块 | **34dp** 圆；选中描边 3dp，未选中 1dp | `SettingsScreen.kt:231-239` |
| 悬浮歌词色块 | **28dp** 圆；选中描边 3dp | `FloatingLyricsSettings.kt:257-270` |
| 自定义种子输入框 | 宽 **240dp** | `SettingsScreen.kt:257` |
| 快捷键键位框 | **132 × 36dp**，圆角 10dp；两列间距 8dp；表头列宽 132dp | `ShortcutSettings.kt:89,96,203,228,257` |
| 同步 API 输入框 | Token 自适应宽、账号框 180dp | `SyncSettings.kt:157` |
| 托盘控制面板 | **340 × 436dp**（`PANEL_WIDTH/HEIGHT`），内容内边距 `h14 v12` | `TrayControlPanel.kt:76-77,239` |
| 托盘面板封面 | 46dp（圆角 12） | `TrayControlPanel.kt:387` |
| 托盘面板播放按钮 | 52dp；上一/下一 40dp / 26dp 图标 | `TrayControlPanel.kt:278-299` |
| 托盘面板状态圆点 | **7dp** 圆 | `TrayControlPanel.kt:399-410` |
| 托盘面板进度条 | 高 **4dp** | `TrayControlPanel.kt:252-256` |
| 托盘面板音量滑杆 | 高 24dp（压缩） | `TrayControlPanel.kt:368` |
| 托盘面板动作行 | 内边距 `h8 v7`；图标 18dp；间距 10dp | `TrayControlPanel.kt:467-498` |
| 悬浮歌词控制按钮 | 30dp / 17dp 图标 | `FloatingLyrics.kt:417-419` |
| 悬浮歌词内容内边距 | `h14 v8`，底部额外预留 30dp | `FloatingLyrics.kt:346,350` |
| 统计数值卡 | 内边距 14dp；数值 `titleLarge` SemiBold；三列等宽间距 10dp | `DetailScreens.kt:833-853,748-756` |

### 3.6 间距体系（spacing tokens）

代码中出现的间距值（`Arrangement.spacedBy` / `Spacer` / `padding`）分布：

| 值 | 语义 | 典型位置 |
| --- | --- | --- |
| 2dp | 紧贴（文字副行、`Row` 行内） | `Components.kt:244`、`PlayerComponents.kt:525` |
| 4dp | 极小间隔（时间标签、图标旁） | `PlayerComponents.kt:466`、`SettingsScreen.kt:248` |
| 6dp | 小间隔（行内元素、卡片内小节） | `Components.kt:116`、`PlayerComponents.kt:624`、`LocalLibraryContent` |
| 8dp | **常用小间距**（图标-文字、区块外边距、chips 行距） | `Components.kt:78`、`HomeScreen.kt:268`、`SettingsScreen.kt:142` |
| 10dp | 中等（卡片内主间隔、按钮行） | `Components.kt:367`、`HomeScreen.kt:383`、`TrayControlPanel.kt:479` |
| 12dp | **标准外边距/卡片间距** | `Components.kt:499`、`NowPlayingScreen.kt:585`(行列) |
| 14dp | 面板行内边距 | `PlayerComponents.kt:676,708`、`DownloadPanel.kt:60` |
| 16dp | **页面/区块内边距** | `Components.kt:68`、`DetailScreens.kt:122`、`SettingsScreen.kt:726` |
| 18dp | 面板标题栏、播放页传输行间距 | `PlayerComponents.kt:586`、`NowPlayingScreen.kt:586` |
| 20dp | 卡片内边距、页面级 padding | `SettingsScreen.kt:120`(contentPadding)、`PlayerComponents.kt:779`、`NowPlayingScreen.kt:256` |
| 24dp | 页面留白（顶部栏/歌词列表） | `PlayerComponents.kt:515`、`NowPlayingScreen.kt:215` |
| 30dp | 悬浮歌词底部控制条预留 | `FloatingLyrics.kt:350` |
| 40dp | 空状态上下留白 | `Components.kt:100` |
| 48dp | 列表底部 contentPadding（部分页面 24/32） | `HomeScreen.kt:145`（24）、`SettingsScreen.kt:116`（32） |

**QML token 建议**：`Theme.spacing2/4/6/8/10/12/14/16/18/20/24/30/40`；`Theme.radiusSmall(10)/radiusCard(20)/radiusDialog(28)/radiusPanel(24)/radiusChip(8)`；`Theme.iconSmall(16)/icon(20)/iconLarge(24)`；`Theme.rowHeight(58)`；`Theme.miniPlayerHeight(70)`；`Theme.bottomBarHeight(80)`；`Theme.breakpointWide(900)`；`Theme.contentMaxWidth(1180)`。**禁止**在页面里直接写魔法数字；走查时按本节表核对。

### 3.7 界面缩放（UiScale）与 QML 的对应

| 项 | Kotlin 行为 | QML 建议 |
| --- | --- | --- |
| 语义 | 0 = 跟随系统；1.0–3.0 = 手动 | 同 |
| 生效方式 | 注入 `LocalDensity.density`，字号/控件/间距一起放大；窗口尺寸 = `base × scale / platformScale`（`AppLayout.kt:161-175,186-201`） | `Theme.uiScale`（浮点，如 2.0）作为全局因子；所有 dp 常量写成 `value * Theme.uiScale`（或封装 `Theme.dp(n)`）；窗口初始尺寸 `1180 × uiScale / devicePixelRatio` 并夹取屏幕 94%/92% |
| 与系统缩放叠加 | 不叠加（先减去平台自身 `platformUiScale()`） | Qt6 默认按 `QScreen::devicePixelRatio` 自动缩放；若也要手动档位，建议 `Theme.uiScale / Screen.devicePixelRatio` 作为内容因子，避免双重放大 |
| 立即生效 | 界面立即缩放（`SettingsScreen.kt:172-177` 文案），窗口尺寸下次启动生效 | 同：`Theme.uiScale` 变化立即重排；窗口尺寸变化提示"下次启动生效" |
| 悬浮歌词窗口 | 宽高都乘 `uiScale / platformUiScale()`（`FloatingLyrics.kt:116-119`） | 同 |
| 托盘面板 | 同上（`TrayControlPanel.kt:98-101`） | 同 |

---
<!-- CHUNK3 -->

