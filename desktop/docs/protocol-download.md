# 下载管理 · IPC 协议（protocol-download）

领域：下载任务队列 + 已下载索引（离线播放）。实现：`backend/api/DownloadApi.kt`。
所用核心 API：`AppContainer.downloads`（`core/Download.kt` 的 `DownloadManager`）、
`AppContainer.downloadCatalog`（`DownloadCatalog`，跨启动持久化到 `downloads.json`）。

设计要点：
- **任务队列只在内存**（core 现状），进程重启后 `download.tasks` 为空，`download.items` 仍在；
- 任务进度回调非常频繁（每 64 KB），前端靠 `downloads.changed` 事件刷新角标、用低频（500ms 左右）
  `download.tasks` 拉进度，**不要**每次事件都全量重绘；
- 下载目录/音质/并发数属于设置项，用已有的 `settings.get` / `settings.set`
  （`downloadDirectory`、`downloadQuality`、`downloadConcurrency`），不在本领域另设命令。

## 1. 命令

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `download.tasks` | — | `{tasks:[DownloadTask], activeCount}` | 当前任务（含进度） |
| `download.items` | — | `{items:[DownloadedSong]}` | 已下载记录（离线索引） |
| `download.enqueue` | `{songs:[Song]}` | `{queued, message?}` | 入队；跳过 LOCAL / 已下载 / 已在队列的歌曲 |
| `download.cancel` | `{id}` | `{ok:true}` | 取消 QUEUED/RUNNING 任务（`id` = task.id） |
| `download.retry` | `{id}` | `{ok:true}` | 重试 FAILED/CANCELED 任务（`id` = task.id） |
| `download.remove` | `{id}` | `{ok, removed, fileDeleted, message?}` | 删除**已下载文件**；`id` 可以是 `songKey` 或已完成任务的 `task.id` |
| `download.clearFinished` | — | `{ok:true}` | 清空 DONE/FAILED/CANCELED 任务（不动文件） |
| `download.openDirectory` | `{path?}` | `{ok, path, message?}` | `xdg-open` 打开目录，失败返回 `ok:false` 不抛错 |

### 扩展命令（旧 UI 用到，非必需清单）

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `download.clearAll` | — | `{ok:true}` | 取消进行中任务并清空整个任务列表（不删文件） |
| `download.repairMetadata` | — | `{repaired:int}` | 为旧下载文件补齐封面/标签/歌词（会重写文件，较慢） |

注意 `download.remove` 的 `id` 优先按 `DownloadedSong.songKey`（已下载记录）查找，
找不到再按「已完成任务的 `task.id`」换算成对应 `song.key` 删除；两者都找不到时返回
`ok:false` + `message`。任务列表里的**失败/取消任务条目**没有单条移除接口，
请用 `download.clearFinished`（或 `download.clearAll`）清理。

### `download.enqueue` 细节

- 参数 `songs` 是完整 `Song` 数组（形状见 `core/Models.kt`）。
- 返回 `queued` 为**实际新增**的任务数；被跳过的歌曲不计数。
- `message` 是 core 生成的中文提示（“已加入下载队列：N 首” / “这些歌曲都已在本地或下载队列中”），
  可直接用 Toast 展示。
- `source=LOCAL` 的歌曲会被 core 直接跳过（已经是本地文件），不会产生任务。

### `download.openDirectory`

- 传 `path` 时打开该目录（不存在会先创建）；不传时打开当前设置的下载目录
  （`downloadDirectory` 为空时是 `~/Music/NeriPlayer`）。
- 内部用 `xdg-open`；失败（无 `xdg-open`、非图形环境、退出码非 0）返回 `ok:false` + `message`，**不抛异常**。

## 2. 数据模型

### `DownloadTask`（`@Serializable`）

```jsonc
{
  "id": "uuid",                       // 取消/重试用它
  "song": { /* Song */ },
  "state": "QUEUED",                  // QUEUED|RUNNING|DONE|FAILED|CANCELED
  "downloadedBytes": 0,
  "totalBytes": -1,                   // -1 表示未知大小
  "error": null,                      // 失败原因（中文一句话）
  "filePath": null                    // DONE 后的本地绝对路径
}
```
派生值（前端自行计算）：`progress`（DONE=1；totalBytes>0 时 downloaded/total）、
`hasKnownSize`（totalBytes>0，用于决定是进度条还是不确定进度条）。

### `DownloadedSong`（`@Serializable`）

```jsonc
{
  "songKey": "netease:123", "filePath": "/home/u/Music/NeriPlayer/歌手 - 标题.mp3",
  "title": "...", "artist": "...", "album": "...", "durationMs": 0,
  "artworkUrl": "...", "artworkPath": "...", "lyricsPath": "...",
  "source": "NETEASE",                // 原始音源（不是 LOCAL）
  "sizeBytes": 1234567, "downloadedAt": 1700000000000
}
```
`DownloadedSong.toSong()` 可转成 `source=LOCAL`、`filePath` 指向本地文件的 `Song`，但**保留原 songKey**，
所以播放/去重仍按原在线 key 匹配。

## 3. 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `downloads.changed` | `{activeCount, taskCount}` | 任务列表/进度变化（由 `StatePublisher` 推送） |

`download.items` 变化（下载完成、删除）**没有独立事件**；完成时 `downloads.changed` 的
`taskCount/activeCount` 会变，前端可在收到后刷新 `download.items`。

## 4. 错误码

| code | 场景 |
| --- | --- |
| `bad_request` | `songs` 缺失/格式非法、`id` 缺失 |
| `internal_error` | 处理异常（一般不会发生，下载失败只体现在任务 `FAILED` 状态） |
| `unknown_method` | 方法名拼错 |

## 5. QML 使用示例

```qml
// 列表初始加载 + 事件刷新
Api.call("download.tasks").then(function(r) { taskModel = r.tasks; activeCount = r.activeCount })
Api.on("downloads.changed", function(d) { activeCount = d.activeCount; refreshTasks() })

// 进度用低频轮询（不要用事件频率刷新列表）
Timer { interval: 700; running: activeCount > 0; repeat: true; onTriggered: refreshTasks() }

function refreshTasks() {
    Api.call("download.tasks").then(function(r) { taskModel = r.tasks })
}

// 下载歌曲 / 取消 / 重试 / 清除已完成
Api.call("download.enqueue", { songs: [song] }).then(function(r) { Toast.show(r.message || ("已入队 " + r.queued + " 首"), "info") })
Api.call("download.cancel", { id: task.id })
Api.call("download.retry", { id: task.id })
Api.call("download.clearFinished")

// 删除已下载文件（id 可用 songKey 或已完成任务的 task.id）
Api.call("download.remove", { id: item.songKey }).then(function(r) { if (!r.ok) Toast.show(r.message || "未找到下载记录", "error") })

// 打开目录
Api.call("download.openDirectory", { path: "" }).then(function(r) {
    if (!r.ok) Toast.show(r.message || "无法打开下载目录", "error")
})

// 已下载分栏
Api.call("download.items").then(function(r) { downloadedModel = r.items })
```
