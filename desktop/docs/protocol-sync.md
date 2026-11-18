# GitHub 同步 · IPC 协议（protocol-sync）

领域：GitHub 跨设备同步（歌单/收藏/最近播放/播放统计）。实现：`backend/api/SyncApi.kt`。
所用核心 API：`AppContainer.syncConfig`（`sync/SyncConfig.kt` 的 `SyncConfigStore`）、
`AppContainer.sync`（`sync/GitHubSyncManager.kt` 的 `GitHubSyncManager`）。

设计要点：
- **Token 绝不回传明文**：所有回包里的 `token` 只给前 4 位 + `****`（未配置时为空串），
  前端不要试图回显/复制完整 Token；配置时由用户重新输入。
- 配置写入 `$XDG_CONFIG_HOME/NeriPlayer/sync.json`（权限 600），与手机端共用同一 GitHub 仓库格式。
- `sync.perform` 是**重操作**（读远端 → 合并 → 本地落盘 → 上传），可能耗时数十秒；
  必须用异步调用（QML `Api.call(...).then(...)`），不要在 UI 线程同步等待。执行期间 `sync.state`
  会推送 `running:true`，完成后推送最终状态。
- 自动同步由后端 `AppContainer.observeLocalChangesForAutoSync()` 维持（每 15s 检查本地歌单/历史/统计
  条目数变化，`autoSync && configured` 时静默同步），前端只需维护 `autoSync` 开关。

## 1. 命令

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `sync.config` | — | `{config}` | 读取配置（token 已打码） |
| `sync.configure` | `{token?,owner?,repo?,useDataSaver?,autoSync?,apiBase?}` | `{config}` | 局部保存，未提供字段保留旧值 |
| `sync.perform` | — | `{status,success,message,lastSyncAt,configured}` | 立即同步一次（suspend，可能几十秒） |
| `sync.disable` | `{clearRepo?,clearOwnerAndRepo?}` | `{config}` | 清空 token；owner/repo 默认保留 |
| `sync.status` | — | `{configured,running,lastSyncAt,message,success}` | 同步状态快照 |

`config` 对象字段：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `token` | string | **打码后的 token**：`"ghp_****"` 或 `""` |
| `tokenSet` | bool | 是否已配置 token（判断“已配置”请用 `configured`） |
| `owner` | string | 仓库所有者（GitHub 用户名/组织） |
| `repo` | string | 仓库名 |
| `repoFullName` | string | `owner/repo`，未齐时为空串 |
| `useDataSaver` | bool | 省流通道（`backup-raw.bin`），**两端必须一致**才能读到最新数据 |
| `autoSync` | bool | 本地数据变化后自动同步 |
| `apiBase` | string | GitHub API 地址，默认 `https://api.github.com`（支持 GHE） |
| `lastSyncAt` | long | 上次成功同步时间（毫秒时间戳；0 表示从未成功） |
| `lastStatus` | string | 上次同步结果文案（中文，可直接展示） |
| `configured` | bool | `token && owner && repo` 都非空 |

### `sync.configure` 语义

- **只覆盖 params 里出现的字段**，其余保留；不含 `token` 的调用不会清掉 token。
- `token` 传 `""`（空字符串）表示清除 token；`owner`/`repo` 传 `""` 表示清除。
- `apiBase` 传 `""` 时恢复默认 `https://api.github.com`；末尾 `/` 会被去掉。
- 保存后立即刷新 `sync.state` 的 `configured` 并广播事件。
- **不做网络校验**（Token 是否有效由 `sync.verifyToken` / 实际 `sync.perform` 反馈）。

### `sync.disable` 语义

清空 token（= 退出同步），默认保留 owner/repo，方便下次只重填 token。
传 `clearRepo:true` 或 `clearOwnerAndRepo:true` 时一并清空 owner/repo。返回打码后的新配置。

### `sync.perform` 的 `status`

| status | 含义 |
| --- | --- |
| `success` | 同步成功（已上传或“已是最新”） |
| `failed` | 已配置但同步失败（token 无效/仓库不存在/网络/冲突重试失败），`message` 为中文原因 |
| `not_configured` | 尚未配置 token/owner/repo |

`message` 直接来自 core（如“初始数据已上传到 owner/repo”“无法读取仓库默认分支”），可直接展示。

### 扩展命令（旧 UI 同步设置页用到，非必需清单）

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `sync.verifyToken` | `{token?}` | `{ok:true,login}` 或 `{ok:false,message}` | 校验 Token，返回 GitHub 登录名（可作 owner）；缺 `repo` 权限也会 `ok:false` |
| `sync.listRepos` | `{token?}` | `{repos:[string]}` | 列出可写仓库的 `owner/repo`（网络失败返回空数组） |
| `sync.createRepo` | `{token?,name}` | `{ok:true,fullName}` 或 `{ok:false,message}` | 创建私有仓库（auto_init），成功后可再 `sync.configure` 保存 owner/repo |

`token` 参数缺省时用已保存的 token。网络/校验失败走 `ok:false` + `message`，不抛协议错误。

## 2. 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `sync.state` | `{configured,running,lastSyncAt,message,success}` | 同步开始/结束、配置变化（由 `StatePublisher` 推送） |

`sync.state` 的 `configured` 来自 core 状态；判断是否已配置建议同时用 `sync.status`/`sync.config`
的 `configured`（以配置为准）。同步进行中 `running:true`、`message:"正在同步…"`。

## 3. 错误码

| code | 场景 |
| --- | --- |
| `bad_request` | `sync.createRepo` 缺 `name`；扩展命令无 token 且未配置 |
| `internal_error` | 处理异常（同步/网络失败一般返回在 result 里，不抛错误） |
| `unknown_method` | 方法名拼错 |

## 4. QML 使用示例

```qml
// 初始加载
Api.call("sync.config").then(function(r) { cfg = r })
Api.call("sync.status").then(function(r) { syncState = r })
Api.on("sync.state", function(d) { syncState = d })

// 保存配置（token 只在用户输入时提供；留空不回传）
Api.call("sync.configure", {
    token: tokenField.text.length > 0 ? tokenField.text : undefined,
    owner: ownerField.text, repo: repoField.text
}).then(function(r) { cfg = r; Toast.show("同步配置已保存", "info") })

// 校验 Token（可选）
Api.call("sync.verifyToken", { token: tokenField.text }).then(function(r) {
    if (r.ok) { ownerField.text = r.login; Toast.show("Token 有效：" + r.login, "info") }
    else Toast.show(r.message, "error")
})

// 立即同步（异步！可能几十秒）
Api.call("sync.perform").then(function(r) {
    Toast.show(r.message, r.status === "success" ? "info" : "error")
})

// 关闭同步
Api.call("sync.disable").then(function(r) { cfg = r })
```
