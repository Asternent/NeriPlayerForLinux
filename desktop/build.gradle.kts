import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "moe.ouom.neriplayer"
version = "1.2.2"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.9.0")
    implementation("com.materialkolor:material-color-utilities-jvm:3.0.1")
    implementation("net.jthink:jaudiotagger:3.0.1")
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("org.jflac:jflac-codec:1.5.2")
    // 扫码登录需要在应用内渲染二维码
    implementation("com.google.zxing:core:3.5.3")
}

compose.desktop {
    application {
        mainClass = "moe.ouom.neriplayer.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "NeriPlayer"
            packageVersion = "1.2.2"
            description = "NeriPlayer 音理音理 — Linux 原生 Compose Desktop 音乐播放器"
            vendor = "NeriPlayer Desktop"
            copyright = "GPL-3.0-only"
            licenseFile.set(project.file("packaging/LICENSE"))
            // java.net.http 用于在线音源请求，jdk.unsupported / java.instrument 供解码与音频库使用
            modules("java.instrument", "java.net.http", "jdk.unsupported", "java.logging", "jdk.crypto.ec")
            linux {
                packageName = "neriplayer"
                debMaintainer = "neriplayer@localhost"
                appCategory = "Audio"
                menuGroup = "Audio"
                iconFile.set(project.file("packaging/neriplayer.png"))
            }
        }
    }
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
    // 支持 -DGH_TOKEN=xxx（推荐，避免 Gradle 守护进程环境变量过期）
    environment("GH_TOKEN", System.getProperty("GH_TOKEN") ?: System.getenv("GH_TOKEN") ?: "")
    environment("GH_MOCK_BASE", System.getProperty("GH_MOCK_BASE") ?: System.getenv("GH_MOCK_BASE") ?: "")
}
