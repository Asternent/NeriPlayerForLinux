# 协议 · 歌单 / 收藏 / 历史 / 统计（collections / history / stats）

无头后端 `collections.*`、`history.*`、`stats.*` 命令。
通用传输格式、错误码、模型编码约定见 [`protocol.md`](protocol.md)；本文只列本领域内容。

实现：`backend/api/CollectionsApi.kt`；核心仓库 `core/Collections.kt`
（`PlaylistRepository` / `HistoryRepository` / `StatsRepository`）。
所有写操作都会整表落盘（IO 线程执行，不阻塞事件循环），并由 `StatePublisher` 推送变化事件。

## 1. 模型

```ts
Playlist = {
  id: string,
  name: string,
  songs: Song[],
  system: boolean,        // 仅「我喜欢的音乐」为 true
  createdAt: number,      // ms
  updatedAt: number,      // ms
}

UsageEntry = { song: Song, playedAt: number, playCount: number }

StatsSummary = { plays: number, listenMs: number, trackCount: number }
HotSong      = { song: Song, playCount: number, listenMs: number, lastPlayed: number }

StatsPeriod = "DAY" | "WEEK" | "MONTH" | "YEAR" | "ALL"     // 天/近 7 天/近 30 天/近 365 天/全部
StatsSort   = "PLAY_COUNT" | "LISTEN_TIME" | "RECENT"
```

系统歌单 id 固定为 `"system:favorites"`（常量 `FAVORITES_PLAYLIST_ID`），显示名「我喜欢的音乐」。

## 2. 歌单 / 收藏

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `collections.list` | — | `{playlists:[Playlist], favorites:Playlist}` | `playlists` **只含用户歌单**（不含系统收藏），按创建时间升序；`favorites` 固定返回系统收藏歌单（没有也会返回空对象） |
| `collections.create` | `{name}` | `{playlist:Playlist}` | 新建用户歌单，`name` 去空格后不能为空 |
| `collections.rename` | `{id,name}` | `{ok:true}` | 系统歌单不可重命名 → `bad_request` |
| `collections.delete` | `{id}` | `{ok:true}` | 系统歌单不可删除 → `bad_request` |
| `collections.addSongs` | `{id,songs:[Song]}` | `{ok:true}` | 追加歌曲，已存在的 `song.key` 自动去重；空数组为合法 no-op |
| `collections.removeSong` | `{id,songKey}` | `{ok:true}` | 按 `song.key` 移除；不存在该曲不报错 |
| `collections.moveSong` | `{id,from,to}` | `{ok:true}` | 列表内拖动排序；索引越界 → `bad_request` |
| `collections.toggleFavorite` | `{song:Song}` | `{favorite:boolean}` | 切换收藏，返回切换后的状态 |
| `collections.isFavorite` | `{songKey}` | `{favorite:boolean}` | 按 `Song.key` 查询是否收藏 |
| `collections.replaceFavorites` | `{songs:[Song]}` | `{ok:true}` | 整体替换「我喜欢的音乐」内容（同步/批量导入用） |

约定：

- 除 `collections.create` 外，所有带 `id` 的命令在歌单不存在时返回 `not_found`。
- 「我喜欢的音乐」可以 `addSongs` / `removeSong` / `moveSong`（id 用 `system:favorites`），但不能重命名/删除。
- `Song` 由前端按 `block`/详情页拿到的完整对象原样回传（后端按对象保存，不重新解析文件）。

## 3. 播放历史（history）

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `history.list` | `{limit?}` | `{entries:[UsageEntry]}` | 按 `playedAt` 降序；`limit` 默认 600（历史最多保留 600 条） |
| `history.continuePlaying` | `{limit?}` | `{songs:[Song]}` | 「继续播放」卡片用，默认 12 |
| `history.recentlyPlayed` | `{limit?}` | `{songs:[Song]}` | 最近播放，默认 60 |
| `history.mostPlayed` | `{limit?}` | `{songs:[Song]}` | 按累计播放次数降序，默认 12 |
| `history.remove` | `{songKey}` | `{ok:true}` | 删除单条历史（不存在也返回 ok） |
| `history.clear` | — | `{ok:true}` | 清空全部历史（不会清空 statistics） |

`limit`：非负整数；负数 → `bad_request`。

## 4. 播放统计（stats）

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `stats.summary` | `{period}` | `{summary:StatsSummary}` | 指定周期的播放次数/累计时长/去重曲目数 |
| `stats.top` | `{period,sort,limit?}` | `{songs:[HotSong]}` | 排行；`limit` 默认 50 |
| `stats.clear` | — | `{ok:true}` | 清空全部统计（不会清空 history） |
| `stats.periods` | — | `{periods:[{value,label}], sorts:[{value,label}]}` | 给前端下拉框用的枚举 + 中文标签 |

`stats.periods` 返回示例：

```json
{"periods":[{"value":"DAY","label":"日"},{"value":"WEEK","label":"周"},{"value":"MONTH","label":"月"},
            {"value":"YEAR","label":"年"},{"value":"ALL","label":"总"}],
 "sorts":[{"value":"PLAY_COUNT","label":"按播放次数"},{"value":"LISTEN_TIME","label":"按累计时长"},
          {"value":"RECENT","label":"按最近播放"}]}
```

说明：

- `period` / `sort` 为必填，大小写不敏感（后端 `uppercase` 后解析）；非法值 → `bad_request`。
- 统计按「歌曲 × 天」分桶累计；`StatsSummary.trackCount` 是去重歌曲数，`plays` 是总播放次数，
  `listenMs` 是累计收听毫秒。
- `stats.top` 只返回**能在 history 中找到歌曲对象**的歌单（历史的歌词/封面等对象快照）；
  如果 history 被清空，排行榜会变空（统计数据仍保留）——这是核心限制，前端应显示空状态。
- 无数据时：`summary` 返回 `{plays:0,listenMs:0,trackCount:0}`，`top` 返回 `{songs:[]}`。

## 5. 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `collections.changed` | `{count:number}` | 歌单/收藏内容变化（含 `Playlist.songs` 变化） |
| `history.changed` | `{count:number}` | 历史增加/删除/清空 |
| `stats.changed` | `{count:number}` | 统计变化（播放中每 10 秒会写 stats） |

事件只带计数，内容变化后前端需要重新调用对应查询命令（或本地已持有数据时直接更新）。

## 6. 错误码

| code | 场景 |
| --- | --- |
| `bad_request` | 缺少/非法 `id`、`name`、`songKey`、`songs`、`period`、`sort`、`limit`；索引越界；操作系统歌单 |
| `not_found` | `id` 指向的歌单不存在 |
| `internal_error` | 落盘等意外异常 |

## 7. QML 示例

```qml
// 歌单列表（playlists 不含系统收藏，favorites 单独返回）
Api.get("collections.list", {}, function(r) {
    playlistModel.playlists = r.playlists
    favoritesModel.playlist = r.favorites
})

// 新建歌单
Api.call("collections.create", { name: "通勤" })
   .then(r => Toast.show(qsTr("已创建 %1").arg(r.playlist.name)))
   .catch(e => Toast.show(e.message, "error"))

// 收藏切换（song 用当前播放/列表项里的完整 Song 对象）
Api.call("collections.toggleFavorite", { song: Player.song })
   .then(r => { favorite = r.favorite })

// 统计页
Api.get("stats.summary", { period: "WEEK" }, function(r) { summary = r.summary })
Api.get("stats.top", { period: "WEEK", sort: "PLAY_COUNT", limit: 50 },
        function(r) { topModel.songs = r.songs })

// 周期/排序下拉框直接吃 stats.periods
Api.get("stats.periods", {}, function(r) {
    periodCombo.model = r.periods       // textRole: "label", valueRole: "value"
    sortCombo.model = r.sorts
})
```

## 8. 备注

- 写命令（create/rename/delete/addSongs/removeSong/moveSong/toggleFavorite/replaceFavorites、
  history.remove/clear、stats.clear）都在 `Dispatchers.IO` 上执行并同步落盘；前端连点不会阻塞 IPC 事件循环，
  但会连续触发多次 `collections.changed` / `history.changed`。
- 拖动排序建议本地先更新 UI，调用 `collections.moveSong` 后再以事件/重新查询校正。
- 历史是**播放开始时**记录（核心 `HistoryRepository.record`），不是播放结束；统计的收听时长在播放中累计。
- 本领域命令不包含播放控制；开始播放用 `player.*`（见 [`protocol.md`](protocol.md) 3.2）。
