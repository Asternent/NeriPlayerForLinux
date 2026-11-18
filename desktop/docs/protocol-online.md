# 在线音源 · IPC 协议（protocol-online）

领域：探索 / 搜索 / 详情 / 歌词 / 播放地址 / 音源状态。实现：`backend/api/OnlineApi.kt`。
所用核心 API：`AppContainer.online`（`net/OnlineRepository.kt`、`net/OnlineApis.kt`、`net/Http.kt`）、
`AppContainer.history`（首页「继续播放」）、`AppContainer.accounts`（登录态歌单）、
`AppContainer.lyrics`（歌词仓库）、`SettingsRepository`（音源开关 / 音质）。

设计要点：

- **只做聚合与裁剪，不重写网络逻辑**。搜索 / 详情 / 歌词 / 播放地址全部调用 `OnlineRepository` 与
  `NeteaseApi` / `BiliApi` 的现成方法；旧核心没有的能力才直接在 `OnlineApi` 里补（热门搜索参数、
  搜索建议、风格标签、标签歌单），且都是公开的 GET 接口，不涉及 WBI 签名。
- **错误分两类**：参数非法 / 音源不支持 → 协议错误（`bad_request` / `unsupported` / `not_found`）；
  网络接口失败通常是「返回空数组」而不是命令失败（首页聚合额外带 `errors`），前端按空状态渲染。
- **封面永远是 URL 字符串**（`Song.artworkUrl`、`OnlineCollection.coverUrl`、`OnlineArtist.avatarUrl`），
  要本地路径请调 `artwork.resolve` / `artwork.forSong`（见 `protocol-artwork.md`）。
- 一条请求一个 `suspend` 处理函数，内部阻塞网络调用都在 `Dispatchers.IO` 上执行。

## 1. 通用约定

| 约定 | 说明 |
| --- | --- |
| `source` | `"NETEASE"` / `"BILIBILI"`，大小写不敏感。`LOCAL` → `bad_request`（本地库用 `library.*`），`YOUTUBE` → `unsupported` |
| `page` | 从 1 开始，默认 1；网易云歌曲搜索每页 30，B 站视频搜索每页 30（由核心固定） |
| `kind` | `"SONG"` / `"PLAYLIST"` / `"ARTIST"` / `"ALBUM"`，大小写不敏感；省略时 `online.search` 一次返回 3 类结果 |
| 封面 | 统一用 URL 字符串，前端用 `artwork.*` 换本地路径 |
| 编码 | 枚举按名字（`"NETEASE"`），字段名 camelCase，与 `core/Models.kt` 一致 |

错误响应：

```jsonc
{"id": 7, "error": {"code": "bad_request", "message": "缺少 source"}}
```

常见错误码：`bad_request`（参数缺失/非法）、`unsupported`（音源不支持该功能）、
`not_found`（歌单/专辑/歌手 id 无效或接口不可用）、`internal_error`（未预料的异常，前端按网络错误提示）。

## 2. 命令清单

| method | params | result |
| --- | --- | --- |
| `online.home` | — | `{continuePlaying, guessYouLike, hotTracks, newSongs, radar, hotLists, sections, errors}` |
| `online.search` | `{source, keyword, page?, kind?}` | `{songs:[Song], collections:[OnlineCollection], artists:[OnlineArtist]}` |
| `online.hotSearches` | `{source}` | `{keywords:[string]}` |
| `online.searchSuggest` | `{source, keyword}` | `{keywords:[string]}` |
| `online.tags` | `{source?}` | `{tags:[{name, category?}]}` |
| `online.tagPlaylists` | `{tag, page?, source?}` | `{collections:[OnlineCollection]}` |
| `online.playlistDetail` | `{source, id}` | `{collection:OnlineCollection, songs:[Song]}` |
| `online.albumDetail` | `{source, id}` | `{collection:OnlineCollection, songs:[Song]}` |
| `online.artistDetail` | `{source, id}` | `{artist:OnlineArtist, songs:[Song], albums:[OnlineCollection]}` |
| `online.collectionDetail` | `{source, id, kind?}`，`kind` 默认 `"playlist"` | `{title, coverUrl, description, creator, songs, collection}` |
| `online.myPlaylists` | `{source}` | `{collections:[OnlineCollection], needLogin:Boolean}` |
| `online.biliFavorites` | `{url?}` | `{collections:[OnlineCollection], needLogin:Boolean}` |
| `online.lyrics` | `{song:Song, refresh?}` | `{lyrics:Lyrics}` |
| `online.playbackUrl` | `{song:Song}` | `{song:Song, playable:Boolean, url?, path?, headers?, error?}` |
| `online.sources` | — | `{sources:[{id,name,enabled}], quality, ffmpeg:Boolean}` |

## 3. 首页聚合 `online.home`

一次请求拿齐首页所有在线区块；**不会整体失败**：失败/禁用的区块返回空数组并写进 `errors`。

```jsonc
{
  "continuePlaying": {"songs": [Song, ...]},        // 本地播放历史最近 12 首（非在线数据）
  "guessYouLike":    {"songs": [Song, ...]},        // 为你推荐（网易云「热门歌曲」搜索，18 首）
  "hotTracks":       {"songs": [Song, ...]},        // 热歌榜（榜单详情前 18 首）
  "newSongs":        {"songs": [Song, ...]},        // 推荐新歌（18 首）
  "radar":           {"collections": [...]},        // 私人雷达（推荐歌单 12 个）
  "hotLists":        {"collections": [...]},        // 热门榜单（topplist 前 12 个）
  "sections": [                                      // 有序渲染列表，避免前端硬编码标题/顺序
    {"id": "continuePlaying", "title": "继续播放",  "kind": "songs",       "songs": [...]},
    {"id": "guessYouLike",    "title": "为你推荐",  "kind": "songs",       "songs": [...]},
    {"id": "hotTracks",       "title": "热歌榜",    "kind": "songs",       "songs": [...]},
    {"id": "newSongs",        "title": "推荐新歌",  "kind": "songs",       "songs": [...]},
    {"id": "radar",           "title": "私人雷达",  "kind": "collections", "collections": [...]},
    {"id": "hotLists",        "title": "热门榜单",  "kind": "collections", "collections": [...]}
  ],
  "errors": []                                       // 失败说明（中文），空数组表示全部正常
}
```

- 每个区块固定是 `{songs:[...]}` 或 `{collections:[...]}`；`kind` 告诉你该读哪一个字段
  （另一个字段可能是 `null` 或缺省）。
- `sections` 里顶部区块**总是存在**（字段为空数组时也保留），QML 可自行跳过空区块；
  `continuePlaying` 只有本地有播放历史时才会出现在 `sections` 里（字段本身始终存在）。
- `errors` 典型内容：`"网易云音源已在设置中禁用"`、`"首页推荐加载失败：<原因>"`。
- 耗时：网易云首页内部是 6+ 次串行请求，**正常网络约 1~3 秒**，前端应显示加载态；
  建议加载一次后缓存，下拉刷新再重新调用。

## 4. 搜索

### `online.search`

```jsonc
// 请求
{"source": "NETEASE", "keyword": "周杰伦", "page": 1}
{"source": "NETEASE", "keyword": "周杰伦", "kind": "PLAYLIST"}
{"source": "BILIBILI", "keyword": "周杰伦"}
```

```jsonc
// 结果（未指定 kind 时三类都有；指定 kind 时只有对应一类非空）
{
  "songs": [Song, ...],
  "collections": [OnlineCollection, ...],
  "artists": [OnlineArtist, ...]
}
```

- `keyword` 为空字符串/空白：直接返回全空结果，不发网络请求（不会报错）。
- 未指定 `kind`：网易云会发 3 次请求（歌曲 / 歌单 / 歌手）再合并；`page` 只影响歌曲分页。
- B 站只支持视频搜索：忽略 `kind`，统一返回 `songs`（视频当作歌曲，`key = "bili:<bvid>"`）。
- 接口失败（风控、断网）时对应数组为空，不抛错；参数非法才抛 `bad_request`。

### `online.hotSearches`

| source | 来源 | 说明 |
| --- | --- | --- |
| `NETEASE` | `/api/search/hot?type=1111` 的 `result.hots[].first` | 返回约 10 个热词 |
| `BILIBILI` | `/x/web-interface/search/square` 的 `data.trending.list[].keyword` | 返回约 20 个热词 |

失败时 `keywords: []`。

### `online.searchSuggest`

输入框联想词（防抖 200~300ms 后调用即可）：

```jsonc
// NETEASE：歌曲「歌名 歌手」+ 歌手 + 专辑 + 歌单，最多 10 条
{"keywords": ["布拉格广场 蔡依林", "屋顶 周杰伦", "周杰伦", "看我72变", "J-Top冠军精选"]}
// BILIBILI：视频搜索联想词
{"keywords": ["周杰伦歌曲全集", "周杰伦", "周杰伦演唱会"]}
```

`keyword` 为空时返回空数组。

## 5. 风格标签与标签歌单

### `online.tags`

网易云歌单分类接口（真实数据，约 70 个标签），`category` 是分类编号：

| category | 含义 |
| --- | --- |
| 0 | 语种（华语 / 欧美 / 日语 …） |
| 1 | 风格（流行 / 摇滚 / 民谣 …） |
| 2 | 场景（清晨 / 夜晚 / 学习 / 工作 …） |
| 3 | 情感（怀旧 / 浪漫 / 伤感 …） |
| 4 | 主题（综艺 / 影视原声 / ACG …） |

```jsonc
{"tags": [{"name": "综艺", "category": 4}, {"name": "流行", "category": 1}, ...]}
```

- `source` 可省略，默认 `NETEASE`；`BILIBILI` 没有标签体系，返回 `{"tags": []}`。
- 网络失败/接口变更时返回空数组（不要在前端写死标签列表）。

### `online.tagPlaylists`

```jsonc
// 请求
{"tag": "流行", "page": 1}
// 结果（每页 30 个）
{"collections": [OnlineCollection, ...]}
```

- 用网易云歌单列表接口（`/api/playlist/list?cat=<tag>`）取真实标签歌单；失败时回退到
  `searchPlaylists(tag)`。只有 `NETEASE` 支持，其它 source → `unsupported`。
- `tag` 建议来自 `online.tags` 的 `name`。

## 6. 歌单 / 专辑 / 歌手详情

### `online.playlistDetail`

| source | `id` 含义 | 行为 |
| --- | --- | --- |
| `NETEASE` | 歌单 id 或专辑 id | 先按歌单取；歌单为空时**回退专辑接口**（两类 id 会撞车，与旧 UI 一致） |
| `BILIBILI` | 收藏夹 media_id | 读取收藏夹内全部视频（20/页翻页，最多 2000 条），可能耗时数秒 |

```jsonc
{"collection": OnlineCollection, "songs": [Song, ...]}
```

### `online.albumDetail`

仅 `NETEASE`（`source=BILIBILI` → `unsupported`），返回 `{collection, songs}`。

### `online.artistDetail`

仅 `NETEASE`：

```jsonc
{
  "artist": {"id": "6452", "name": "周杰伦", "avatarUrl": "https://...", "songCount": 591, "source": "NETEASE"},
  "songs": [Song, ...],       // 歌手热门 50 首
  "albums": [OnlineCollection, ...]  // 热门专辑 20 张
}
```

头像 `avatarUrl` 是 URL；`songCount` 是网易云统计的作品数（可能大于返回的 50 首）。

### `online.collectionDetail`（统一入口）

参数 `{source, id, kind}`，`kind` 省略时按 `"playlist"`：

| kind | 网易云 | B 站 |
| --- | --- | --- |
| `playlist` | 同 `online.playlistDetail`（含专辑回退） | 收藏夹 |
| `album` | 同 `online.albumDetail` | `unsupported` |
| `favorites` | 同 `playlist`（「我喜欢的音乐」本身就是歌单 id） | 收藏夹 |

```jsonc
{
  "title": "经典老歌专属",
  "coverUrl": "https://...",        // 可能为 null（收藏夹会尽量用首曲封面兜底）
  "description": "...",
  "creator": "云村专属推荐",
  "songs": [Song, ...],
  "collection": OnlineCollection    // 一定非空；详情页可继续用来跳转/播放
}
```

- `collection.coverUrl` 与顶层 `coverUrl` 一致；`collection.trackCount` 为 0 时后端会用实际歌曲数补上。
- id 无效/接口取不到 → `not_found`。

## 7. 登录态歌单

### `online.myPlaylists`

```jsonc
// 已登录：NETEASE 用账号 userId，BILIBILI 用账号 mid
{"collections": [OnlineCollection, ...], "needLogin": false}
// 未登录 / 缺 UID
{"collections": [], "needLogin": true}
```

- 网易云：`/api/user/playlist`（用户创建+收藏的歌单，含「我喜欢的音乐」）。
- B 站：`/x/v3/fav/folder/created/list-all`（用户创建的收藏夹）。
- 前端看到 `needLogin:true` 应显示「登录后查看」引导（登录命令见 `protocol-login.md`），
  并在收到 `accounts.changed` 事件后重试。

### `online.biliFavorites`

B 站收藏夹列表。`url` 可省略：

| 调用 | 行为 |
| --- | --- |
| `{}` | 用当前登录账号的 mid 列全部收藏夹；未登录 `/ 无 mid` → `{collections: [], needLogin: true}` |
| `{url: "https://space.bilibili.com/123/favlist?fid=456&ftype=create"}` | 解析 `fid=456`，返回该收藏夹（在账号收藏夹列表里匹配不到时返回一个 `{id:"456", name:"B站收藏夹", source:"BILIBILI"}` 占位） |
| `{url: "https://www.bilibili.com/medialist/detail/ml456"}` | 解析 `ml456` → 同上 |

- 也能从 `url` 里解析 `space.bilibili.com/<mid>` 作为 mid（用于查看**别人**的公开收藏夹列表）。
- 收藏夹没有封面时，`OnlineCollection.coverUrl` 可能为 null；打开详情后
  `online.playlistDetail` 返回的 `collection.coverUrl` 会用首曲封面兜底。

## 8. 歌词与播放地址

### `online.lyrics`

```jsonc
// 请求
{"song": Song}
{"song": Song, "refresh": true}   // 手动刷新：先清缓存再加载
// 结果
{"lyrics": Lyrics}
```

`Lyrics`（`core/Models.kt`）：

```jsonc
{
  "raw": "[00:00.00]...",
  "lines":   [{"timeMs": 0, "text": "第一行", "translation": "..."}],  // 已合并翻译
  "translated": [{"timeMs": 0, "text": "..."}],
  "source": "网易云"   // "本地歌词" / "内嵌歌词" / "网易云"
}
```

- 走 `container.lyrics` 仓库：**本地 `.lrc` → 音频内嵌歌词 → 在线歌词** 的完整回退链，
  不是只查在线接口；结果带 LRU 缓存。
- QML 播放器页面已有 `player.lyrics` 事件推送，本命令用于**详情页/手动刷新**。
- 本地歌曲没有歌词文件时，在线回退会用「标题 + 歌手」搜网易云最匹配的一首。
- 无歌词时返回 `lines: []`、`raw: ""`，不报错。

### `online.playbackUrl`

补齐/调试用命令，主要用于确认某首歌是否有可播放地址（播放器内部走同一套逻辑）：

```jsonc
// 成功
{
  "song": Song,
  "playable": true,
  "path": null,                       // 本地歌曲时有值
  "url": "http://m801.music.126.net/.../jdymus",   // 远程音频地址
  "headers": {"Referer": "https://music.163.com/", "User-Agent": "..."}
}
// 不可播放（VIP / 地区限制 / 接口异常）：命令本身不失败
{"song": Song, "playable": false, "error": "无法解析播放地址（音源可能被限制或需要登录）"}
```

- 音质取 `settings.qualityPreference`（`standard`/`higher`/`exhigh`/`lossless`…，网易云映射为码率）。
- 网易云返回 `url` 为 `null` 的歌曲（VIP/付费）是**接口本身的限制**，不是后端 bug；
  `playable:false` + `error` 如实反映。
- B 站会先取分 P 列表得到 cid 再解析 DASH 最高码率音频。
- 注意：这里的 `url` 是**临时地址**（带过期时间），不要持久化/下载，播放请交给 `player.*`。

## 9. 音源状态 `online.sources`

```jsonc
{
  "sources": [
    {"id": "NETEASE",  "name": "网易云",   "enabled": true},
    {"id": "BILIBILI", "name": "哔哩哔哩", "enabled": true},
    {"id": "YOUTUBE",  "name": "YouTube",  "enabled": false}
  ],
  "quality": "exhigh",
  "ffmpeg": true
}
```

- `enabled` 来自 `settings.neteaseEnabled / bilibiliEnabled / youtubeEnabled`（设置变化时读最新值）。
- `quality` 是设置里的 `qualityPreference`；`ffmpeg` 是 `FfmpegSupport.available`（影响播放器音效能力）。
- 前端可用它渲染探索页的音源筛选、隐藏被禁用的平台。

## 10. QML 使用示例

```qml
// 探索页：热门搜索 + 推荐歌单 + 风格标签
Api.call("online.hotSearches", { source: "NETEASE" }).then(r => hotKeywords = r.keywords)
Api.call("online.tags", {}).then(r => tags = r.tags)
Api.call("online.tagPlaylists", { tag: "流行", page: 1 }).then(r => playlists = r.collections)

// 搜索（防抖后）
Api.call("online.search", { source: "NETEASE", keyword: kw, page: 1 })
   .then(r => { songs = r.songs; collections = r.collections; artists = r.artists })

// 首页
Api.call("online.home").then(r => {
    sections = r.sections                     // 每项按 kind 取 songs 或 collections
    if (r.errors.length > 0) Toast.show(r.errors.join("；"), "error")
})

// 详情页
Api.call("online.collectionDetail", { source: src, id: id, kind: "playlist" })
   .then(r => {
       songs = r.songs
       coverSource = r.coverUrl                 // 传给 CoverArt { url: r.coverUrl }
   }).catch(e => Toast.show(e.message, "error"))

// 播放
Player.playSongNow(song, songs)                 // 播放地址由后端播放器内部解析
```

## 11. 遗留 / 限制

- B 站搜索走 WBI 签名并有风控，偶发返回空数组（核心内部已重试 3 次）；此时前端应提示
  「结果为空，可稍后重试」而不是报错。
- 网易云首页聚合是串行多次请求，没有做服务端缓存；前端不要在多个页面同时反复调用。
- `online.tags` / `online.tagPlaylists` / `online.searchSuggest` 依赖网易云未公开的公开接口，
  接口变更时只会退化为空数组，不影响其它命令。
- 未登录时 `online.myPlaylists` / `online.biliFavorites` 只返回 `needLogin:true`，不做匿名兜底。
