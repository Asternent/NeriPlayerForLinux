# 封面解析与缓存 · IPC 协议（protocol-artwork）

领域：远程封面下载 → 本地缓存 → 返回绝对路径。实现：`backend/api/ArtworkApi.kt`。
所用核心 API：`AppDirs.coverDir`（`core/Storage.kt`）、`OnlineRepository.httpService`（`net/Http.kt`，
带 Cookie 与 gzip 处理）、`MediaSource` / `Song`（`core/Models.kt`）。

设计要点：

- 协议里的封面**永远是 URL 字符串**：`Song.artworkUrl`、`OnlineCollection.coverUrl`、`OnlineArtist.avatarUrl`。
  QML 的 `Image` 直接读网络图会被防盗链拦截，所以由后端下载到缓存目录，返回**本地绝对路径**。
- 缓存目录与旧 Compose 前端的封面缓存**是同一个目录**：`$XDG_CACHE_HOME/NeriPlayer/covers`
  （默认 `~/.cache/NeriPlayer/covers`）；旧缓存按 `url.hashCode()` 命名，新命令按 URL 的 MD5 命名，
  两者都会命中，不会重复下载旧缓存。
- 下载失败**不抛协议错误**（`artwork.resolve` / `artwork.forSong` 返回 `{path:null, error}`），
  前端应显示占位图而不是弹错误；只有参数本身非法（缺 `url`）才是 `bad_request`。
- 下载内容会做**图片嗅探**：HTML/JSON 错误页、风控页不会写进缓存（避免坏文件永久命中）。

## 1. 命令清单

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `artwork.resolve` | `{url, referer?}` | `{path:string, cached:bool}` 或 `{path:null, error}` | 通用 URL → 本地缓存路径 |
| `artwork.forSong` | `{song:Song, referer?}` | `{path:string, cached:bool}` 或 `{path:null, error}` | 歌曲封面优先本地、其次远程 |
| `artwork.clearCache` | — | `{removed:int}` | 清空远程封面缓存目录 |
| `artwork.saveAs` | `{url?, path?, target, referer?}` | `{ok:bool, path:string|null, error?}` | 另存封面到用户选择的路径（播放页封面右键菜单） |

## 2. `artwork.resolve`

```jsonc
// 请求
{"url": "https://p3.music.126.net/xxx.jpg"}
{"url": "https://i0.hdslb.com/bfs/xxx.jpg", "referer": "https://www.bilibili.com/"}
{"url": "/home/me/Music/cover.jpg"}          // 本地绝对路径：直接原样返回
// 成功
{"path": "/home/me/.cache/NeriPlayer/covers/2c225e0db385bd29d5e673bb502578bd.img", "cached": false}
{"path": "/home/me/.cache/NeriPlayer/covers/2c225e0db385bd29d5e673bb502578bd.img", "cached": true}  // 秒回
// 失败（命令本身仍成功返回，前端显示占位图）
{"path": null, "error": "封面下载失败（网络不可用或 URL 无效）: https://..."}
```

行为：

| 输入 | 行为 |
| --- | --- |
| `file://` URL 或本地绝对路径 | 不下载，原样返回绝对路径（文件不存在/为空 → `error`） |
| `http(s)` URL，缓存已存在 | 直接返回，`cached: true`（旧 `url.hashCode().img` 与新的 `md5(url).img` 都会命中） |
| `http(s)` URL，未缓存 | 下载 → 嗅探图片格式 → 原子写入缓存 → `cached: false` |
| 其它 scheme / 相对路径 | `{path:null, error:"不支持的 url..."}` |
| 同一 URL 并发请求 | 每个缓存文件一把锁，只下载一次，其余请求等待后命中缓存 |

`referer` 省略时按图片域名自动选择：

| 域名 | 默认 Referer |
| --- | --- |
| `*.163.com` / `*.126.net` / `*.127.net` | `https://music.163.com/` |
| `*.bilibili.com` / `*.hdslb.com` / `*.bilivideo.com` / `*.biliimg.com` | `https://www.bilibili.com/` |
| 其它 | 不带 Referer |

大多数网易云 / B 站图片必须带对应 Referer，否则返回 403 或占位图；显式传 `referer` 可覆盖默认值。

失败原因（`error` 文案可直接展示，也可忽略）：

- `封面下载失败（网络不可用或 URL 无效）` —— 网络异常 / 404
- `封面内容为空` —— 200 但 0 字节
- `返回内容不是图片（可能被防盗链拦截）` —— 收到 HTML/JSON 页面（如 403 风控页）
- `封面写入缓存失败` —— 磁盘不可写 / 空间不足
- `本地封面文件不存在或为空` —— 传入了本地路径但文件不存在

## 3. `artwork.forSong`

```jsonc
// 请求
{"song": { "key": "netease:509781655", "source": "NETEASE", "artworkUrl": "https://p3.music.126.net/xxx.jpg", ... }}
// 结果
{"path": "/home/me/.cache/NeriPlayer/covers/2c225e0d....img", "cached": false}
```

优先级：

1. `song.artworkPath` 非空且文件存在 → 直接返回（本地扫描 / 下载写标签后的封面，通常是
   `~/.cache/NeriPlayer/artwork/<md5(path)>.img`），`cached: true`；
2. 否则用 `song.artworkUrl` 下载缓存；Referer 默认按 `song.source`：
   `NETEASE` → `https://music.163.com/`，`BILIBILI` → `https://www.bilibili.com/`；
3. 两者都没有 → `{path:null, error:"歌曲没有封面（artworkPath 与 artworkUrl 均为空）"}`。

`song.artworkPath` 指向的文件不存在时（例如缓存被手动清掉）会继续尝试 `artworkUrl` 兜底。

## 4. `artwork.clearCache`

```jsonc
{"removed": 137}   // 本次实际删除的文件数
```

- 只清 `AppDirs.coverDir`（远程封面缓存）里的普通文件，不递归删目录。
- **不会**删除 `AppDirs.artworkDir`（本地媒体库内嵌封面抽取文件，被 `Song.artworkPath` 引用），
  所以清理后本地歌曲封面仍在。
- 设置页「清除封面缓存」按钮调它即可；清理不会广播事件，前端自行刷新。
- 并发下载锁也会一并重置。

## 5. `artwork.saveAs`

把一张封面另存到用户指定的绝对路径（播放页封面右键「保存封面…」，前端用 `FileDialog` 选路径）。

```jsonc
// 请求：path（后端已缓存的本地封面）优先，url 作为兜底
{"path": "/home/me/.cache/NeriPlayer/covers/2c225e0d....img", "target": "/home/me/图片/封面 - 十年.jpg"}
// 只有远程地址时后端会先下载到缓存再复制
{"url": "https://p3.music.126.net/xxx.jpg", "target": "/home/me/图片/cover.jpg"}
// 成功
{"ok": true, "path": "/home/me/图片/封面 - 十年.jpg"}
// 失败（命令本身仍成功返回）
{"ok": false, "path": null, "error": "本地封面文件不存在或为空: ..."}
```

行为：

| 输入 | 行为 |
| --- | --- |
| `path` 是存在的本地文件 | 直接复制该文件到 `target` |
| `path` 不存在 / 为空，且给了 `url` | 按 `artwork.resolve` 的规则下载到缓存后复制 |
| `path` / `url` 都缺 | 返回 `{ok:false, error:"缺少 url 或 path"}` |
| `target` 缺失 | 协议错误 `bad_request`（缺少 target） |
| `target` 非绝对路径 / 是目录 | `{ok:false, error}` |
| `target` 后缀不是图片后缀 | 按内容嗅探补后缀（如 `.img` → `.jpg`），结果 `path` 是最终路径 |
| `target` 已存在 | 覆盖写入 |

- 目标目录不存在时会自动创建，父目录不可写时返回 `{ok:false, error}`。
- 与其它 artwork 命令一致：预期的失败不抛协议错误，前端用 Toast 展示 `error` 即可。
- 不做格式转换，直接按字节复制（缓存文件本身已是原始图片内容，只是没有扩展名）。

## 6. QML 使用示例

```qml
// 通用 URL 封面（歌单 / 歌手头像 / 搜索结果）
function loadCover(url) {
    if (!url) { return }
    Api.call("artwork.resolve", { url: url }).then(r => {
        if (r.path) coverPath = r.path
        else coverPath = ""            // 失败：显示占位图
    })
}

// 歌曲封面：本地优先、远程兜底，一次调用搞定
Api.call("artwork.forSong", { song: song }).then(r => { coverPath = r.path || "" })

// CoverArt 组件直接用本地路径
CoverArt { path: coverPath; radius: 12 }

// 清缓存（设置页）
Api.call("artwork.clearCache").then(r => Toast.show("已清理 " + r.removed + " 张封面"))

// 播放页封面右键「保存封面…」：FileDialog 选完路径后调用
function saveArtwork(targetPath) {
    Api.call("artwork.saveAs", {
        path: song.artworkPath || "",     // 本地内嵌封面（可能为空）
        url: song.artworkUrl || "",       // 远程封面兜底
        target: targetPath
    }).then(r => {
        if (r.ok) Toast.show("封面已保存：" + r.path)
        else Toast.show(r.error || "封面保存失败", "error")
    }).catch(e => Toast.show(e.message || "封面保存失败", "error"))
}
```

- 路径是**后端所在机器**的绝对路径；Qt 与后端同机时 QML `Image { source: "file://" + path }`
  （`CoverArt` 组件已封装，直接传 `path` 即可）。
- 建议前端做一层内存/`map<url,path>` 缓存，避免同一张图在列表滚动时反复发 IPC；
  后端虽然会命中磁盘缓存立即返回，但仍有一次 IPC 开销。

## 7. 缓存目录布局

```
$XDG_CACHE_HOME/NeriPlayer/
├── covers/                                  # 远程封面缓存（本领域负责）
│   ├── <md5(url)>.img                       # 新命名（ArtworkApi 写入）
│   ├── <url.hashCode()>.img                 # 旧 Compose 前端命名（只读复用）
│   └── <md5(url)>.img.tmp                   # 写入中间文件，正常不会残留
└── artwork/                                 # 本地媒体库内嵌封面（扫描时抽取，不由本领域清理）
    └── <md5(歌曲路径)>.img
```

- 文件名不含扩展名，图片格式由内容决定；QML `Image` 按内容识别，不需要扩展名。
- 缓存没有容量上限/过期策略（与原核心一致），目前靠设置页手动清理；后续如需自动淘汰，
  可在 `ArtworkApi` 内按文件 mtime 实现，不影响协议。

## 8. 已知限制

- 不支持需要登录 Cookie 才能访问的私密图片（如部分 B 站收藏夹封面）；这类 URL 下载会失败并返回 `error`。
- 一次调用只下载一张图；批量封面请由前端并发调用（`Api.call` 天然支持并发），后端会按 URL 去重。
- 缓存路径直接暴露给前端，属于本机绝对路径，不跨机器使用。
