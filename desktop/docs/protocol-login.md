# 扫码登录 · IPC 协议（protocol-login）

领域：网易云 / 哔哩哔哩扫码登录与账号态。实现：`backend/api/LoginApi.kt`。
所用核心 API：`AppContainer.neteaseLogin / biliLogin / accounts`（`net/LoginApi.kt`、`core/Account.kt`）。

设计要点：**轮询在后台完成**，前端只需要「开始二维码 → 每 2 秒调一次 `login.qrPoll`」，
二维码图片由后端生成后以 base64 PNG 下发（旧 Compose 前端是 UI 侧 zxing 画 Canvas，QML 不再需要 zxing/QR 组件）。

## 1. 命令

| method | params | result | 说明 |
| --- | --- | --- | --- |
| `login.accounts` | — | `{accounts:[AccountInfo]}` | 当前已登录账号（不含 Cookie） |
| `login.qrStart` | `{source}` | `{source,key,qrText,qrImageDataUrl}` | 生成登录二维码；失败返回 `internal_error` |
| `login.qrPoll` | `{source,key}` | `{status,message?,account?}` | 轮询扫码状态；`SUCCESS` 时后端已保存登录态 |
| `login.logout` | `{source}` | `{ok:true}` | 清 Cookie + 删除账号（广播 `accounts.changed`） |
| `login.refreshProfile` | `{source}` | `{account:AccountInfo}` | 重新拉昵称/UID/头像并保存 |

`source` 只接受 `"NETEASE"` / `"BILIBILI"`（大小写不敏感，非法值 → `bad_request`）。

`AccountInfo`（`core/Account.kt`，`@Serializable`）：
`{source:"NETEASE"|"BILIBILI", nickname, userId, avatarUrl?, vip:Boolean, loginAt:Long}`。
`nickname` 可能为空（账号信息接口偶发失败，只有 Cookie 的兜底账号），此时界面应显示 `userId` 或“已登录”。

### `login.qrStart` 返回字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `source` | string | 平台名，与参数一致 |
| `key` | string | 轮询用的 key（网易云 `unikey` / B 站 `qrcode_key`），原样传回 `login.qrPoll` |
| `qrText` | string | 二维码内容原文（`https://music.163.com/login?codekey=...` 或 B 站 passport url），可用于“复制链接” |
| `qrImageDataUrl` | string | `data:image/png;base64,...`（512×512 黑白 PNG），QML 直接 `Image.source` 显示 |

二维码有效期约 2 分钟；`EXPIRED` 后需重新 `login.qrStart`。

### `login.qrPoll` 状态

| status | 含义 | 前端动作 |
| --- | --- | --- |
| `WAITING` | 未扫码 | 继续轮询 |
| `SCANNED` | 已扫码，等待手机确认 | 继续轮询，文案改“已扫码，请在手机上确认” |
| `SUCCESS` | 登录成功 | 停止轮询，读 `account` 更新界面 |
| `EXPIRED` | 二维码过期 | 停止轮询，提示刷新 |
| `FAILED` | 失败（网络异常/未知状态码） | 停止轮询，展示 `message` |

- `SUCCESS` 时后端已调用 `AccountRepository.save()` 持久化 Cookie + 账号，并自动广播 `accounts.changed`；
  前端不要自己再写账号。
- `FAILED` 带 `message`（中文，可直接展示）。

## 2. 事件

| event | data | 触发时机 |
| --- | --- | --- |
| `accounts.changed` | `[AccountInfo, ...]` | 登录成功、退出登录、补拉账号信息后（由 `StatePublisher` 推送） |

事件 payload 是账号数组（不是对象），与 `login.accounts` 的 `accounts` 字段形状一致。

## 3. 错误码

| code | 场景 |
| --- | --- |
| `bad_request` | 缺少 `source` / `key`，或 source 不是 NETEASE/BILIBILI |
| `internal_error` | 二维码接口不可用、账号信息接口不可用（网络受限/风控） |
| `unknown_method` | 方法名拼错 |

## 4. QML 使用示例

```qml
// 开始扫码
Api.call("login.qrStart", { source: "NETEASE" }).then(function(r) {
    qrImage.source = r.qrImageDataUrl          // 直接显示 base64 PNG
    currentKey = r.key
    pollTimer.start()
}).catch(function(e) { Toast.show(e.message, "error") })

// 每 2 秒轮询
Timer {
    id: pollTimer
    interval: 2000; repeat: true
    onTriggered: Api.call("login.qrPoll", { source: "NETEASE", key: qrImage.source ? currentKey : "" })
        .then(function(r) {
            if (r.status === "SUCCESS") { pollTimer.stop(); Nav.closePanel() }
            else if (r.status === "EXPIRED" || r.status === "FAILED") { pollTimer.stop(); statusText.text = r.message || "二维码已过期" }
            else statusText.text = r.status === "SCANNED" ? "已扫码，请在手机上确认" : "请使用手机客户端扫码"
        })
}

// 账号列表（也可订阅 accounts.changed 保持同步）
Api.call("login.accounts").then(function(r) { accountsModel = r.accounts })

// 退出登录
Api.call("login.logout", { source: "BILIBILI" })
```
