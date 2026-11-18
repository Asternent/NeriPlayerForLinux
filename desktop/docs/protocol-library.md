# 协议 · 本地媒体库（library）

无头后端 `library.*` 命令：本地媒体库查询、扫描、音乐文件夹管理。
通用传输格式、错误码、模型编码约定见 [`protocol.md`](protocol.md)；本文只列本领域内容。

实现：`backend/api/LibraryApi.kt`（命令）、`core/Library.kt`（`LibraryRepository`）。
对应事件由 `StatePublisher` 统一推送，前端**不需要轮询**。

## 1. 模型

```ts
ScanState = {
  running: boolean,      // 是否正在扫描
  total: number,         // 本次扫描待处理文件数
  processed: number,     // 已处理数
  found: number,         // 已识别歌曲数
  finishedAt: number,    // 最近一次扫描结束时间（ms）
  error?: string,        // 失败原因；为 null 时字段被省略
}

ArtistGroup = { name: string, songs: Song[] }
AlbumGroup  = { name: string, artist: string, songs: Song[] }
```

`Song` 字段见 [`protocol.md`](protocol.md) 第 2 节（`@Serializable`，camelCase，null 字段省略）。
本地歌曲 `source = "LOCAL"`，`filePath` 为绝对路径，`artworkPath` 为缓存后的封面文件路径（可能没有）。

## 2. 命令

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `library.songs` | — | `{songs:[Song]}` | 全库歌曲，按「艺术家→专辑→音轨号→标题」排序 |
| `library.artists` | — | `{artists:[ArtistGroup]}` | 按艺术家分组，组内歌曲同排序；组按名字排序 |
| `library.albums` | — | `{albums:[AlbumGroup]}` | 按「专辑+艺术家」分组 |
| `library.byKey` | `{key}` | `{song:Song\|null}` | 按 `Song.key` 精确查找；找不到返回 `{"song":null}` |
| `library.scan` | — | `{count:number}` | **suspend**：立即扫描一次并等待完成，返回扫到的歌曲数 |
| `library.scanState` | — | `{scan:ScanState}` | 当前扫描状态快照 |
| `library.indexLoaded` | — | `{loaded:boolean}` | 启动时是否已完成索引加载 |
| `library.addFolder` | `{path}` | `{settings:AppSettings}` | 校验目录存在且是目录，加入音乐文件夹（去重）并**异步**触发一次扫描 |
| `library.removeFolder` | `{path}` | `{settings:AppSettings}` | 从音乐文件夹移除（按绝对路径匹配） |
| `library.setFolders` | `{folders:[string]}` | `{settings:AppSettings}` | 整体替换音乐文件夹列表；去重，不校验目录是否存在 |

参数细节：

- `path`：字符串，必填。支持 `~` / `~/xxx`，返回与保存的是展开后的**绝对路径**。
- `folders`：字符串数组，必填；含空路径或非字符串元素会报 `bad_request`。
- `library.scan` 是唯一会真正等待扫描结束的命令（大库可能耗时数十秒）；前端应显示进度并允许超时。
  命令触发的扫描会**串行执行**，不会并发读写同一份索引。
- `library.addFolder` 返回时扫描可能尚未开始（响应后事件会陆续推送）；不要用返回值判断扫描结果，
  以 `library.scan` 事件 + `library.scanState` / `library.songs` 为准。
- 修改音乐文件夹后设置会自动落盘，并广播 `settings` 事件。

## 3. 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `library.changed` | `{songCount:number}` | 歌曲列表变化（扫描结束、启动加载） |
| `library.scan` | `{running,total,processed,found,error?}` | 扫描开始、每处理约 64 个文件、扫描结束 |
| `settings` | `<AppSettings>` | 音乐文件夹等设置变化后自动广播（`AppSettings.musicFolders`） |

`library.addFolder` 典型事件顺序：`settings` → `library.scan`（running=true）→ 若干 `library.scan`
→ `library.changed`（新 songCount）→ `library.scan`（running=false）。
`library.removeFolder` / `library.setFolders` 只推 `settings`；如需让新设置生效请再调 `library.scan`。

## 4. 错误码

| code | 场景 |
| --- | --- |
| `bad_request` | 缺少 `path`/`folders`、`path` 不存在或不是目录、`folders` 元素非法 |
| `internal_error` | 扫描/IO 意外异常（同时 `library.scan` 事件的 `error` 可能带原因） |

## 5. QML 示例

```qml
// 加载歌曲列表
Api.get("library.songs", {}, function(r) {
    if (r) songsModel.songs = r.songs
})

// 只在歌曲数变化时刷新（避免每次广播都重排列表）
Api.on("library.changed", function(data) { refreshLibrary() })

// 扫描进度
Api.on("library.scan", function(s) {
    scanning = s.running
    progress = s.total > 0 ? s.processed / s.total : 0
    scanText = qsTr("正在扫描 %1/%2，已找到 %3 首").arg(s.processed).arg(s.total).arg(s.found)
})

// 添加文件夹后重新扫描（addFolder 已异步触发，这里演示手动刷新）
Api.call("library.addFolder", { path: "/home/user/Music" }).then(r => {
    Settings.values = r.settings
}).catch(e => Toast.show(e.message, "error"))
```

## 6. 备注 / 已知限制

- `library.scan` 的扫描进度依赖 `library.scan` 事件；若只想等结果，轮询 `library.scanState` 亦可。
- 核心 `LibraryRepository.scan()` 在音乐文件夹列表**为空**时提前返回 `0`，不会清空旧索引：
  移除最后一个文件夹后再扫描，`library.songs` 仍返回旧歌曲（重新添加文件夹并扫描可恢复）。
- 扫描会读取标签与内嵌封面并写缓存目录，首次大库扫描较慢；缩略图/封面展示走 `artwork.resolve`（见 protocol-artwork.md）。
