# YouTube Music 音源 · IPC 协议（protocol-youtube）

领域：YouTube Music 登录（Cookie 粘贴）/ 首页推荐 / 搜索 / 歌单·专辑·歌手详情 / 歌词 / 播放直链。
实现：`backend/api/YouTubeMusicApi.kt`（命令）、`net/YouTubeMusicApi.kt`（Innertube 客户端 + SAPISIDHASH）、
`net/YouTubeMusicParser.kt`（响应解析）、`core/YouTubeMusicModels.kt`（模型）。
公共播放/搜索复用 `net/OnlineRepository.kt` 的 `youtube` 分支。

设计要点：

- **桌面端没有 WebView**，登录只支持“从浏览器复制 Cookie 粘贴”。Cookie 通过
  `AccountRepository.save(MediaSource.YOUTUBE, info, "youtube")` 持久化（账号文件权限 600），
  与其它平台共用 `HttpService` 的 cookie 罐。
- 请求使用 **WEB_REMIX** 客户端；登录态按 `SAPISIDHASH <ts>_<sha1(ts + " " + SAPISID + " " + origin)>`
  签名（同时带 1P / 3P 变体）。未登录时用 visitorData 匿名访问公开内容。
- **匿名能力**：搜索、公开歌单/专辑/歌手页、公开首页推荐（取决于地区/账号状态）；
  **播放直链与个性化首页需要登录**。所有列表命令失败时返回空数组 + `message`，不抛错；
  只有参数非法 / id 完全取不到才返回协议错误。
- 封面一律 URL 字符串（`Song.artworkUrl` / `OnlineCollection.coverUrl` / `OnlineArtist.avatarUrl`），
  需要本地路径时调 `artwork.resolve` / `artwork.forSong`。
- `Song.key` 形如 `youtube:<videoId>`，`Song.remoteId` 存 videoId；
  `OnlineCollection.id` 存 browseId（专辑 `MPRE…` / 歌单 `VL…`）。

## 1. 通用约定

| 约定 | 说明 |
| --- | --- |
| `kind` | `SONG` / `ALBUM` / `ARTIST` / `PLAYLIST`，大小写不敏感；省略时一次返回四类结果 |
| 封面 | URL 字符串，前端用 `artwork.*` 换本地路径 |
| 匿名 | 未登录也能搜索 / 浏览公开内容；播放与“我的歌单”会返回明确错误或 `needLogin` |
| 编码 | 字段名 camelCase，与 `core/Models.kt` / `core/YouTubeMusicModels.kt` 一致 |

错误码：

| code | 场景 |
| --- | --- |
| `bad_request` | 缺参 / Cookie 文本解析不到 / Cookie 不含登录凭据 / 非法 `kind` |
| `not_found` | 歌单 / 专辑 / 歌手 id 无效，或接口返回空内容 |
| `internal_error` | 未预料的异常（前端按网络错误提示） |

典型错误信息：

```jsonc
{"code": "bad_request", "message": "缺少 query"}
{"code": "bad_request", "message": "Cookie 中未找到有效的 YouTube 登录凭据（需要 SAPISID / SID 等）；请确认复制的是已登录 youtube.com / music.youtube.com 的 Cookie"}
{"code": "not_found", "message": "YouTube 歌单为空或 id 非法: VLPLxxxx"}
```

## 2. 命令清单

| method | params | result |
| --- | --- | --- |
| `ytmusic.status` | — | `{status: YouTubeStatus}` |
| `ytmusic.setCookies` | `{cookies}` | `{ok, account:AccountInfo, loggedIn}` |
| `ytmusic.logout` | — | `{ok:true}` |
| `ytmusic.home` | `{limit?}` | `{sections:[YouTubeHomeSection], loggedIn, message?}` |
| `ytmusic.search` | `{query, kind?}` | `{query, songs:[Song], albums:[OnlineCollection], artists:[OnlineArtist], playlists:[OnlineCollection]}` |
| `ytmusic.playlist` | `{id}` | `{title, coverUrl, description, creator, trackCount, songs:[Song], collection}` |
| `ytmusic.album` | `{id}` | `{title, coverUrl, description, artist, creator, trackCount, songs:[Song], collection}` |
| `ytmusic.artist` | `{id}` | `{name, avatarUrl, description, songCount, songs:[Song], albums:[OnlineCollection], artist}` |
| `ytmusic.lyrics` | `{id}`（videoId 或 `MPLYt…`） | `{lyrics:Lyrics}` |
| `ytmusic.myPlaylists` | — | `{collections:[OnlineCollection], needLogin:Boolean, message?}` |
| `ytmusic.hotSearches` | — | `{keywords: []}`（YouTube 无公开热词接口，保留占位） |

> 集成方把 YOUTUBE 接进 `online.*` 时也可直接复用 `OnlineRepository` 的 `youtube` 分支
> （`search` / `songsForCollection` / `albumSongs` / `artistSongs(source,id)` / `lyrics` / `resolvePlayback`）。

### 2.1 `ytmusic.status`

```jsonc
{
  "status": {
    "loggedIn": false,
    "hasCookies": false,
    "anonymous": true,
    "visitorDataAvailable": true,
    "playbackAvailable": false,
    "account": null,
    "message": "未登录：可匿名搜索与浏览公开内容；播放直连与个性化首页需要粘贴浏览器 Cookie 登录"
  }
}
```

- `loggedIn` 同时要求“账号仓库有 YouTube 账号”+“cookie 罐里有登录 Cookie”。
- `playbackAvailable` 只是乐观估计（需要有效登录 Cookie），实际播放失败仍按 `online.playbackUrl` 的 `playable:false` 处理。
- `message` 在未登录 / Cookie 失效 / 无法连接 YouTube 时给出可读提示；正常时为 `null`。

### 2.2 `ytmusic.setCookies`

```jsonc
// 请求：直接粘贴浏览器 devtools 的 Cookie 行或 Cookie 请求头
{"cookies": "SAPISID=…; SID=…; HSID=…; SSID=…; LOGIN_INFO=…"}
// 成功
{"ok": true, "loggedIn": true, "account": {"source": "YOUTUBE", "nickname": "…", "userId": "", "vip": false, "loginAt": 1763…}}
```

- 解析支持：单行 `Cookie: a=b; c=d`、多行 devtools 头文本（只取 cookie 行）、纯 `a=b; c=d`。
- 校验：重新拉 music.youtube.com 首页的 `LOGGED_IN` → `account_menu` 取昵称/头像；
  两者都失败时返回 `bad_request`，不会保存无效 Cookie。
- 成功后写入 `AccountRepository`，`accounts.changed` 事件由 `StatePublisher` 自动广播；
  `login.accounts` 会包含 `source:"YOUTUBE"` 的账号。

### 2.3 `ytmusic.home`

```jsonc
{
  "sections": [
    {
      "id": "yt-0",
      "title": "歌曲合辑",
      "items": [
        {"kind": "song", "song": Song},
        {"kind": "album", "collection": OnlineCollection},
        {"kind": "playlist", "collection": OnlineCollection},
        {"kind": "artist", "artist": OnlineArtist}
      ]
    }
  ],
  "loggedIn": true,
  "message": null
}
```

- 未登录 / 地区限制 / 无个性化推荐时 `sections: []`，并带 `message`；前端应整块隐藏。
- 收藏逻辑：`YouTubeHomeSection.qml` 在 `Settings.get("youtubeEnabled", false)` 为真时由首页
  Loader 加载，`sections` 为空时自身 `height = 0`。

## 3. 搜索

```jsonc
// 全部（四类并行请求）
{"query": "周杰伦"}
// 指定分类
{"query": "周杰伦", "kind": "SONG"}
```

结果：

```jsonc
{
  "query": "周杰伦",
  "songs":     [Song, …],              // 每类最多 30 条
  "albums":    [OnlineCollection, …],  // id = MPRE…
  "artists":   [OnlineArtist, …],      // id = UC…
  "playlists": [OnlineCollection, …]   // id = VL…
}
```

- `query` 为空 → `bad_request`。
- 过滤参数（YouTube 内部常量）可能随版本变化：某类结果为空时会自动回退一次「全部」搜索再按类型筛选。
- 搜索结果里的歌手 / 专辑 / 歌单 id 可直接用于 `ytmusic.artist` / `ytmusic.album` / `ytmusic.playlist`。

## 4. 歌单 / 专辑 / 歌手详情

```jsonc
// 歌单：id 支持 VL… / PL… / 裸 playlistId
{"id": "VLPLH7hGda8pqVd_G9cqDPBc8e60KZjMsEnj"}
// 专辑：id 支持 MPRE… 或裸 albumId
{"id": "MPREb_yBTLlVG2D4V"}
// 歌手：频道 / browseId
{"id": "UCL2MDNdwEtV6aYUgNjFQGZA"}
```

- 详情命令返回的 `songs` 每首都是完整 `Song`（`source:"YOUTUBE"`、`remoteId=videoId`），
  可直接交给 `Player.setQueue` / `player.playSongNow`。
- 歌单 / 专辑的 `collection.id` 是 browseId，可继续传给 `online.collectionDetail` 的 YOUTUBE 分支
  （若集成方未接 `online.*`，则页面直接调 `ytmusic.*`，见 §7）。
- `ytmusic.album` 传入的 id 若是音频播放列表（`OLAK5uy…` / `VL…`），会自动回退按歌单解析。

## 5. 歌词

```jsonc
// videoId
{"id": "Hgnpi3eBQ9o"}
// 结果
{"lyrics": {"raw": "……", "lines": [], "translated": [], "source": "YouTube Music"}}
```

- 流程：`next` 找到歌词页 browseId（`MPLYt…`）→ `browse` 取纯文本歌词。
- YouTube 歌词一般**没有时间轴**（纯文本），`lines` 可能为空，`raw` 为原文；
  播放器会按“无同步歌词”显示。取不到时返回空 `Lyrics`（不报错）。

## 6. 播放直链

由 `OnlineRepository.resolvePlayback(song, quality)` 的 `MediaSource.YOUTUBE` 分支解析，
`online.playbackUrl` 命令可直接验证：

```jsonc
{"song": Song}   // Song.remoteId = videoId
// 成功
{"song": Song, "playable": true, "url": "https://rr…googlevideo.com/videoplayback?…", "headers": {"User-Agent": "…", "Referer": "https://music.youtube.com/", "Origin": "https://music.youtube.com"}}
// 失败（未登录 / 机器人校验 / 地区限制）
{"song": Song, "playable": false, "error": "无法解析播放地址（音源可能被限制或需要登录）"}
```

实现细节与限制：

- 只用**不需要 PoToken** 的客户端求直链：`VISIONOS`、`ANDROID_VR`（登录后额外尝试 `WEB_REMIX`）。
  不移植 Android 端的 player.js 签名 / n 参数求解，`signatureCipher` 候选直接跳过。
- 优先 `adaptiveFormats` 里带普通 `url` 的音频格式（itag 140/141/251/250…），按设置的音质挑接近的码率。
- 直链是**临时地址**（带过期时间），不要持久化/下载；缓存 5 分钟。
- **未登录时 YouTube 通常返回 `LOGIN_REQUIRED`（请登录以确认不是聊天机器人），此时 `playable:false`**；
  需要在设置页完成 YouTube Cookie 登录后才能播放。

## 7. QML 集成

### 7.1 登录面板

- `components/panels/YouTubeLoginPanel.qml`（`PanelShell` 包裹 `components/youtube/YouTubeLoginCard.qml`）。
- 集成方在 `PlayerOverlays.qml` 挂载：`Nav.activePanel === "youtubeLogin"` → 加载该面板；
  入口 `Nav.openPanel("youtubeLogin")`（`NavGlobal.openYouTubeLoginPanel()` 可选）。
- 面板内 `YouTubeLoginCard` 自己调用 `ytmusic.status / setCookies / logout`，成功后 `loginStateChanged()`
  关闭面板；`accounts.changed` 由后端广播，各页面监听刷新。

### 7.2 首页区块

- `components/youtube/YouTubeHomeSection.qml`：`ytmusic.status` + `ytmusic.home`，
  渲染横向 shelf（`YouTubeShelfRow`，卡片复用 `TrackCard` / `CollectionCard` / `ArtistCard`）。
- 首页用 Loader 挂载（`Settings.get("youtubeEnabled", false)` 时 active）。未登录 / 无数据 / 出错时
  `height: 0`、`visible: false`，不占位。

### 7.3 探索页

- `pages/ExplorePage.qml` 在 `youtubeEnabled` 为真时显示 YouTube 音源 tab；
  分类 chips：全部 / 单曲 / 专辑 / 歌手 / 歌单（分别对应 `ytmusic.search` 的 `kind`）。
- 歌曲点击直接播放（队列 = 当前结果）；专辑卡片跳 `albumDetail`，歌单卡片跳 `onlineCollection`，
  歌手卡片跳 `remoteArtist`，参数带 `source: "YOUTUBE"`。
- 未登录时默认内容区内嵌 `YouTubeLoginCard`（粘贴 Cookie）；登录成功后自动重载推荐与搜索结果。

### 7.4 在线音源页

- `components/library/OnlineSourcesTab.qml` 在 `youtubeEnabled` 为真时显示 YouTube 入口：
  未登录内嵌登录卡，已登录时调 `ytmusic.myPlaylists` 展示账号歌单。

```qml
// 搜索（防抖后）
Api.call("ytmusic.search", { query: kw, kind: "SONG" })
   .then(r => { songs = r.songs; collections = (r.albums || []).concat(r.playlists || []) })

// 首页
Api.call("ytmusic.home", { limit: 8 }).then(r => sections = r.sections)

// 详情（协议返回 songs 可直接播放）
Api.call("ytmusic.playlist", { id: id }).then(r => songs = r.songs)
```

## 8. 已知限制

1. **播放直链需要登录 Cookie**：匿名请求会被 YouTube 判为机器人（`LOGIN_REQUIRED`），
   这也是桌面版必须提供 Cookie 登录入口的原因。
2. **地区限制**：出口 IP 所在地区不支持 YouTube Music 时，`music.youtube.com` 会返回
   “YouTube Music is not available in your area”，此时歌曲/专辑搜索、首页推荐、专辑详情可能为空，
   但社区歌单（`VL…`）仍可读取；命令按空结果 + `message` 降级，不会崩溃。
3. 搜索过滤参数是 YouTube 内部常量，可能随版本失效；已加“全部搜索 + 本地分类”兜底，
   若 YouTube 同时限制目录内容，则只能返回社区歌单 / 歌手。
4. 未实现：点赞/收藏写入 YouTube 账号、播放列表分页（continuation）、MV 视频类型区分、
   个性化推荐排序。下载/播放地址依旧是临时直链，播放器/下载器内部处理。
5. `ytmusic.status` 的 `playbackAvailable` 是乐观值，真正可播放性以 `online.playbackUrl` 为准。
