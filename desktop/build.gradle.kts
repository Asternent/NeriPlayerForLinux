// NeriPlayer 桌面版 · Kotlin 无头后端
//
// Qt6/QML 前端（desktop/qt）通过 Unix socket 上的 JSON 协议调用这里的命令；
// 这个工程只负责「引擎 + 服务」：播放引擎、媒体库、在线音源、下载、GitHub 同步、
// MPRIS、系统托盘、全局快捷键。前端界面完全由 Qt 实现，不再依赖 Compose Desktop。
plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
}

group = "moe.ouom.neriplayer"
version = "2.0.0"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
    // Material3 调色板（主题颜色由后端算好下发给 Qt，前端不自带取色算法）
    implementation("com.materialkolor:material-color-utilities-jvm:3.0.1")
    // 本地媒体库标签/封面读取
    implementation("net.jthink:jaudiotagger:3.0.1")
    // Java Sound 回退解码器（ffmpeg 不可用时）
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("org.jflac:jflac-codec:1.5.2")
    // 扫码登录二维码（后端直接生成 base64 PNG 下发给前端）
    implementation("com.google.zxing:core:3.5.3")
    // MPRIS / 托盘（StatusNotifierItem + DBusMenu）/ 全局快捷键（xdg portal）
    implementation("com.github.hypfvieh:dbus-java-core:5.2.1")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.1")
}

/** 开发期直接启动后端（阻塞）。 */
tasks.register<JavaExec>("runBackend") {
    group = "application"
    description = "启动 Qt 前端的无头后端"
    mainClass.set("moe.ouom.neriplayer.desktop.backend.BackendMainKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** 无头后端的可执行 fat jar（Qt 前端用 `java -jar` 拉起）。 */
val backendJar by tasks.registering(Jar::class) {
    group = "build"
    description = "打包无头后端 neriplayer-core.jar"
    archiveFileName.set("neriplayer-core.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes["Main-Class"] = "moe.ouom.neriplayer.desktop.backend.BackendMainKt"
    }
    from(sourceSets["main"].output)
    dependsOn(configurations.runtimeClasspath)
    from({
        configurations.runtimeClasspath.get()
            .filter { it.exists() }
            .map { if (it.isDirectory) it else zipTree(it) }
    })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    exclude("module-info.class", "META-INF/versions/*/module-info.class")
}

tasks.register<JavaExec>("selfTest") {
    group = "verification"
    description = "运行核心功能自检（网络 / 解码 / 播放 / 歌词）"
    mainClass.set("moe.ouom.neriplayer.desktop.tools.SelfTestKt")
    classpath = sourceSets["main"].runtimeClasspath
}

/** 真实的 GitHub 同步端到端测试（需要 GH_TOKEN，会创建并删除一个临时私有仓库）。 */
tasks.register<JavaExec>("syncE2E") {
    group = "verification"
    description = "GitHub 同步端到端测试"
    mainClass.set("moe.ouom.neriplayer.desktop.tools.SyncE2ETestKt")
    classpath = sourceSets["main"].runtimeClasspath
    environment("GH_TOKEN", System.getProperty("GH_TOKEN") ?: System.getenv("GH_TOKEN") ?: "")
    environment("GH_MOCK_BASE", System.getProperty("GH_MOCK_BASE") ?: System.getenv("GH_MOCK_BASE") ?: "")
}
