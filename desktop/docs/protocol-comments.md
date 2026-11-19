# 评论区 · IPC 协议（protocol-comments）

领域：歌曲评论（列表 / 楼中楼回复 / 点赞 / 发表 / 删除）。实现：
- 协议入口：`backend/api/CommentsApi.kt`（自带 `register(server, container)`，需在 `BackendMain.kt` 注册）
- 网络与平台解析：`net/CommentApis.kt`（`NeteaseCommentApi` / `BiliCommentApi`）
- 数据模型：`core/CommentModels.kt`（`CommentItem` / `CommentPage` / `CommentPlatform` / `CommentSort`）
- QML：`components/panels/CommentsPanel.qml` + `components/comments/`

设计要点：

- **平台由歌曲的「逻辑音源」决定**：`Song.source == NETEASE` 用网易云评论，`BILIBILI` 用 B 站评论
  （B 站 `bvid = Song.remoteId`），本地 / YouTube / 未知来源返回 `source="UNSUPPORTED"` 的空页而不是协议错误。
- **读评论不需要登录**：网易云评论列表 `/api/v2/resource/comments`、楼中楼 `/api/resource/comment/floor/get`
  都是公开 api 接口（明文表单，不需要 weapi 加密）；B 站用 legacy `/x/v2/reply`、`/x/v2/reply/reply`
  （首次请求先补 buvid 指纹 Cookie）。
- **网络/业务失败不抛协议错误**：列表失败返回带 `error` / `errorCode` 的空页，前端据此显示错误态；
  点赞 / 发表 / 删除这类「动作」返回 `{ok:false, requiresLogin?, error, errorCode}`，前端只弹 Toast。
   只有参数缺失/非法才抛 `bad_request`。
- **Cookie 复用 `container.online.httpService` 的共享 jar**（与「设置 → 账号」的扫码登录同一份登录态），
  未登录时 `comments.like` / `comments.post` / `comments.delete` 返回 `requiresLogin:true`。
- 命令处理函数在 `Dispatchers.IO` 上执行网络请求；行协议、模型编码约定见 `protocol.md`。

## 1. 命令清单

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `comments.list` | `{song, sort?, page?, pageSize?, cursor?}` | `CommentPage` | 一级评论分页；`sort` = `hot`/`new`（`time`/`newest` 是 `new` 的别名），默认 `hot` |
| `comments.replies` | `{song, commentId, page?, pageSize?, cursor?}` | `CommentPage` | 楼中楼回复分页；网易云翻页必须带上一页返回的 `cursor` |
| `comments.like` | `{song, commentId, like?}` | `{ok, liked, requiresLogin?, error?, errorCode?}` | 点赞/取消点赞；`like` 默认 `true` |
| `comments.post` | `{song, content}` | `{ok, comment?, requiresLogin?, error?, errorCode?}` | 发表一级评论（尽力实现，见 §5） |
| `comments.delete` | `{song, commentId}` | `{ok, error?, errorCode?}` | 删除评论（可选；B 站尽力实现，网易云不支持） |

参数说明：

| 参数 | 说明 |
| --- | --- |
| `song` | 完整 `Song` 对象（与 `player.playSongNow` 里的同名字段一致，见 `core/Models.kt`） |
| `sort` | `"hot"`（热门，默认）/ `"new"`（最新）。未知值按 `hot` 处理 |
| `page` | 从 1 开始，默认 1 |
| `pageSize` | `comments.list` 1..50（默认 20）；`comments.replies` 1..20（默认 20） |
| `cursor` | 上一次响应的 `cursor`。网易云「最新」排序和楼中楼翻页必须携带；B 站按页码翻页，忽略该参数 |

## 2. 数据模型

### `CommentPage`（`@Serializable`，`core/CommentModels.kt`）

```jsonc
{
  "comments": [CommentItem, ...],
  "page": 1,
  "pageSize": 20,
  "hasMore": true,
  "total": 7293,              // 平台没给总数时为 null
  "source": "NETEASE",        // NETEASE | BILIBILI | UNSUPPORTED
  "cursor": "normalHot#20",   // 下一页游标；无游标的平台为 null
  "loginRequired": false,     // 该页失败是否需要登录（如 B 站匿名读「最新」）
  "error": null,              // 中文错误文案；非空表示这一页失败
  "errorCode": null           // network|permission|not_found|closed|server|api|unsupported|login_required|cursor_required|internal_error
}
```

### `CommentItem`（`@Serializable`）

```jsonc
{
  "id": "4956438",             // 平台评论 id（网易云 commentId / B 站 rpid）
  "parentId": null,            // 直接父评论 id（楼中楼回复才有）
  "rootId": null,              // B 站：所属根评论；网易云为 null
  "userId": "548345",
  "nickname": "蛋蛋是圆D",
  "avatarUrl": "https://p4.music.126.net/...jpg",  // 已补全 https，可直接给 artwork.resolve / CoverArt
  "content": "评论正文",
  "timeMs": 1413530574252,     // 统一为毫秒时间戳
  "likedCount": 823208,
  "liked": false,              // 当前登录用户是否点过赞（未登录恒为 false）
  "replyCount": 14,            // 平台没给时为 null（UI 应隐藏而不是显示 0）
  "location": "广东",           // IP 属地，可能为 null
  "userLevel": 6,              // B 站用户等级；网易云为 null
  "replies": [CommentItem],    // 列表接口自带的楼中楼预览（可能为空）
  "quotes": [{"nickname": "...", "content": "..."}],  // 被回复的评论（content=null 表示原评论已删除）
  "images": [{"url": "https://...", "width": 0, "height": 0}]  // B 站评论配图
}
```

`CommentItem.replies` 只是预览；展开回复请调 `comments.replies`（完整分页）。

## 3. 错误码

列表类错误（`CommentPage.errorCode`）：`network`、`permission`（需要登录/登录态失效）、`not_found`、
`closed`（B 站评论区已关闭）、`server`、`api`（平台返回异常或数据无法解析）、`unsupported`
（该歌曲来源不支持评论）、`cursor_required`（网易云最新排序/楼中楼缺游标）、`internal_error`。

动作类错误（`errorCode` 字段）：`login_required`（附 `requiresLogin:true`）、`unsupported`、
`network`、`not_found`、`closed`、`api`、`internal_error`。

协议错误（抛给 `{"error":{code,message}}`）：只有参数缺失/非法时是 `bad_request`
（例如缺少 `song`、评论内容为空、正文超过平台长度上限 140/1000 字）。

## 4. QML 使用示例

```qml
// 列表：先读第一页，翻页时带回 cursor
Api.call("comments.list", {"song": Player.song, "sort": "hot", "page": 1})
   .then(function(page) {
       // page.comments / page.hasMore / page.total / page.source / page.error
   })

// 展开楼中楼：第一页不带 cursor，之后带上上一页的 cursor
Api.call("comments.replies", {"song": Player.song, "commentId": "4956438", "page": 1})
   .then(function(page) { /* page.cursor 用于下一页 */ })

// 点赞（未登录时 result.requiresLogin === true，应引导去登录）
Api.call("comments.like", {"song": Player.song, "commentId": "4956438", "like": true})
   .then(function(result) { if (!result.ok) Toast.show(result.error || "点赞失败", "error") })

// 发表评论（成功后面板应重新拉第一页）
Api.call("comments.post", {"song": Player.song, "content": "说点什么"}).then(...)
```

面板由播放页按钮 `Nav.openPanel("comments")` 打开（`components/PlayerOverlays.qml` 挂载），
面板自身读取 `Player.song`，切歌时自动重新加载。未登录时输入框替换为「去登录」引导，
点击调用 `Nav.openLoginPanel(source)`。

## 5. 已知限制（重要）

- **网易云发评论/点赞**：桌面版没有实现 weapi/eapi 加密，走的是明文 `api` 接口
  （`/api/resource/comments/add`、`/api/v1/comment/like`，带登录 Cookie 与 `csrf_token`）。
  平台可能追加 `checkToken` 风控校验，此时会返回平台错误码；需要用户重试或改用网页端。
  未登录时统一返回 `requiresLogin:true`，不会发请求。
- **网易云删除评论**：没有可用的公开 api 删除接口，`comments.delete` 固定返回
  `errorCode:"unsupported"`。
- **B 站匿名限制**：未登录时 legacy 评论接口只返回热门预览（约 3 条）且「最新」排序被截断
  （`page.num=0`）；面板会显示「仅显示部分评论，登录后可查看更多」。楼中楼未登录只能读第一页。
  登录后预期可正常翻页，但本机没有 B 站登录态可回归验证。
- **B 站回复定位**：`Song.remoteId` 必须是 bvid（B 站搜索/收藏夹生成的歌曲满足）；若 remoteId 是
  aid 数字也能解析。bvid → aid 需要额外请求 `x/web-interface/view`，结果在进程内缓存。
- 评论文本里的网易云 `[表情]` 标记与 B 站 `content.emote` 表情目前按纯文本显示（未内联渲染表情图）。
- 评论区没有推送事件：点赞/发评论后由前端重新拉列表（或本地覆盖表即时反馈）。

## 6. 集成方需要做的事

1. `backend/BackendMain.kt`：`import ...backend.api.CommentsApi`，在注册列表里加一行
   `CommentsApi.register(server, container)`。
2. `qt/qml/components/PlayerOverlays.qml`：`switch (host.panel)` 里加
   `case "comments": return "panels/CommentsPanel.qml"`。
3. 播放页 `components/player/PlayerCore.qml` 工具行加一个按钮：
   ```qml
   IconButton {
       icon: "comment"
       iconSize: 24
       size: 48
       iconColor: Theme.onSurfaceVariantColor
       tooltip: "评论"
       onClicked: Nav.openPanel("comments")
   }
   ```
   （`NavGlobal` 已有通用 `openPanel(name)`，不需要新增方法。）
4. 可选：设置页加「评论来源」开关时请自行在 `AppSettings` 里加字段并在 `CommentsApi` 里读取，
   本实现不做限制。
