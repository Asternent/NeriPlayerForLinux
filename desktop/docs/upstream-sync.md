# 与上游 cwuom/NeriPlayer 同步

本仓库是 [cwuom/NeriPlayer](https://github.com/cwuom/NeriPlayer) 的 fork，**只额外维护 `desktop/` 目录**
（Linux 桌面版：Qt6/QML 前端 + Kotlin 无头后端）。上游的 Android 工程（`app/`、`build-logic/`、
`buildSrc/`、`modules/`、`tools_pub/`、`app/src/**` 等）保持原样。

## 日常同步

```bash
# 首次：添加上游 remote（remote 名为 upstream）
git remote add upstream https://github.com/cwuom/NeriPlayer.git

# 以后每次同步
git fetch upstream
git merge upstream/master        # 或 git rebase upstream/master
```

冲突面只有以下几处（因为桌面版代码全部位于 `desktop/`，与上游目录不重叠）：

| 文件 | 处理方式 |
| --- | --- |
| `README.md` / `README_EN.md` | 冲突通常只在 fork 新增的「Linux 原生桌面版」说明段；保留该段，其余采用上游版本 |
| `.gitmodules` / 子模块指针 | 直接采用上游版本（桌面版不引用子模块） |
| `.github/workflows/*` | 直接采用上游版本（桌面版目前不带 CI） |
| `.gitignore` | 采用上游版本；桌面版额外的忽略规则放在 `desktop/qt/.gitignore` |

`desktop/` 里的代码不会被上游改动碰到，所以合并后只需：

```bash
# 确认桌面版仍能构建（Kotlin 后端 + Qt 前端都依赖 JDK17 与 Qt6 开发包）
cd desktop && JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ./gradlew -q backendJar --offline
cd qt && tools/dev.sh backend && tools/dev.sh screenshot /tmp/home.png --page home
```

## 目录约定（保持与上游零重叠）

| 路径 | 归属 | 说明 |
| --- | --- | --- |
| `desktop/src/main/kotlin/.../core` `net` `sync` | fork | 桌面版核心逻辑（播放/网络/同步），与 Android 版独立实现 |
| `desktop/README.md` `desktop/docs/screenshots-qt` | fork | 桌面版文档与 Qt 截图 |
| `desktop/src/main/kotlin/.../backend` | fork | 无头后端 IPC 服务（Qt 前端通过 Unix socket 调用） |
| `desktop/qt` | fork | Qt6/QML 前端、打包脚本、开发文档 |
| `desktop/docs` | fork | 协议文档（`protocol*.md`）、截图 |
| 其余目录 | 上游 | 不在 fork 中修改，避免合并冲突 |

## 需要移植上游新能力时

上游新增的播放/同步/音源能力主要落在 Android 的 `modules/*` 和 `app/src/main/java/...` 里。
桌面版**不是**直接编译这些模块，而是按同样的接口协议在 `desktop/src/main/kotlin/.../net|sync|core`
里实现；移植时对照上游改动即可。桌面版的 IPC 协议文档在 `desktop/docs/protocol.md`，
Qt 侧命令对接见 `desktop/qt/docs/qt-dev-guide.md`。
