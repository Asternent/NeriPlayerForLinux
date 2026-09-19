import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.0"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.0"
    id("org.jetbrains.compose") version "1.11.1"
}

group = "moe.ouom.neriplayer"
version = "1.4.3"

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // 图标：编译期用完整的 material-icons-extended（一万多个图标类，jar 36 MB），
    // 打包与运行时换成下方 trimMaterialIcons 裁剪出来的精简 jar（几十个类、约 120 KB）。
    // 图标是逐类定义 + 静态调用，裁剪只保留真正被引用到的类即可。
    compileOnly(compose.materialIconsExtended)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    // 打包/运行时用的精简图标 jar 在文件末尾加进来（需要先声明生成它的任务）
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
    // 系统媒体控制（MPRIS over D-Bus），对应手机端的通知栏/媒体键控制
    implementation("com.github.hypfvieh:dbus-java-core:5.2.1")
    implementation("com.github.hypfvieh:dbus-java-transport-native-unixsocket:5.2.1")
}

// ---------------------------------------------------------------------------
// 裁剪 material-icons-extended
//
// 该 jar 把每个图标都编成独立类，共 11105 个类 / 36 MB，而应用只用到几十个图标。
// 更关键的是 jar 里的 class 本身已是 deflate 过的，deb 再做 xz 几乎压不动，
// 相当于安装包里有 32 MB 是白带的。这里扫描常量池找出被引用到的图标类
// （含类之间的引用闭包），只保留这些类重新打包。
// ---------------------------------------------------------------------------

/** 读取 class 文件常量池中的全部 Utf8 字符串（超界/异常一律当作扫描不到）。 */
fun classPoolStrings(data: ByteArray): List<String> {
    if (data.size < 10 || data[0] != 0xCA.toByte() || data[1] != 0xFE.toByte() ||
        data[2] != 0xBA.toByte() || data[3] != 0xBE.toByte()
    ) {
        return emptyList()
    }
    fun u1(p: Int) = data[p].toInt() and 0xFF
    fun u2(p: Int) = (u1(p) shl 8) or u1(p + 1)

    val out = ArrayList<String>()
    val count = u2(8)
    var pos = 10
    var slot = 1
    while (slot < count && pos < data.size) {
        val tag = u1(pos)
        pos += 1
        when (tag) {
            1 -> {
                val len = u2(pos)
                out.add(String(data, pos + 2, len, Charsets.UTF_8))
                pos += 2 + len
            }
            3, 4, 9, 10, 11, 12, 17, 18 -> pos += 4
            5, 6 -> {
                pos += 8
                slot += 1 // long / double 占两个常量池槽位
            }
            7, 8, 16, 19, 20 -> pos += 2
            15 -> pos += 3
            else -> return out
        }
        slot += 1
    }
    return out
}

val iconClassPattern = Regex("androidx/compose/material/icons/[A-Za-z0-9_$/]+")

/** 收集一段字节码里引用到的所有图标类名（不含 .class 后缀）。 */
fun collectIconNames(data: ByteArray, into: MutableSet<String>) {
    for (text in classPoolStrings(data)) {
        if (!text.contains("androidx/compose/material/icons")) continue
        for (match in iconClassPattern.findAll(text)) into.add(match.value)
    }
}

val trimmedIconsJar = layout.buildDirectory.file("trimmed-icons/material-icons-extended-trimmed.jar")

val trimMaterialIcons by tasks.registering {
    group = "build"
    description = "裁剪 material-icons-extended，只保留被引用到的图标类（安装包约小 32 MB）"
    val iconJar = configurations.compileClasspath.map { classpath ->
        classpath.firstOrNull { it.name.startsWith("material-icons-extended") }
            ?: error("compileClasspath 中找不到 material-icons-extended")
    }
    val scanJars = configurations.compileClasspath.map { classpath ->
        classpath.filterNot { it.name.startsWith("material-icons-extended") }
    }
    val appClasses = sourceSets["main"].output.classesDirs
    val output = trimmedIconsJar

    inputs.files(iconJar, scanJars, appClasses).withPropertyName("iconScanSources")
    outputs.file(output)

    doLast {
        val referenced = HashSet<String>()

        fun scanJar(file: File) {
            ZipFile(file).use { zip ->
                for (entry in zip.entries().toList()) {
                    if (!entry.name.endsWith(".class")) continue
                    collectIconNames(zip.getInputStream(entry).readBytes(), referenced)
                }
            }
        }

        scanJars.get().forEach { scanJar(it) }
        appClasses.forEach { dir ->
            dir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { collectIconNames(it.readBytes(), referenced) }
        }

        val source = iconJar.get()
        val target = output.get().asFile
        var kept = 0
        ZipFile(source).use { zip ->
            val entries = zip.entries().toList()
            val byName = entries.filter { it.name.endsWith(".class") }
                .associateBy { it.name.removeSuffix(".class") }
            val keep = HashSet<String>()
            referenced.forEach { if (byName.containsKey(it)) keep.add(it) }

            // 被保留的图标类之间可能还有引用，补齐闭包
            val queue = ArrayDeque(keep.toList())
            while (queue.isNotEmpty()) {
                val entry = byName[queue.removeFirst()] ?: continue
                val found = HashSet<String>()
                collectIconNames(zip.getInputStream(entry).readBytes(), found)
                for (name in found) {
                    if (name in keep || !byName.containsKey(name)) continue
                    keep.add(name)
                    queue.add(name)
                }
            }
            kept = keep.size

            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream().buffered()).use { out ->
                out.setLevel(9)
                for (entry in entries) {
                    if (entry.isDirectory) continue
                    if (entry.name.endsWith(".class") &&
                        entry.name.removeSuffix(".class") !in keep
                    ) {
                        continue
                    }
                    out.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).copyTo(out)
                    out.closeEntry()
                }
            }
        }
        logger.lifecycle(
            "[icons] material-icons-extended 裁剪：11105 -> $kept 个类，" +
                "${source.length() / 1048576} MB -> ${target.length() / 1024} KB"
        )
    }
}

dependencies {
    // 打包与运行时使用裁剪后的图标 jar（生成它的 trimMaterialIcons 会先跑）
    runtimeOnly(files(trimmedIconsJar).builtBy(trimMaterialIcons))
}

compose.desktop {
    application {
        mainClass = "moe.ouom.neriplayer.desktop.MainKt"
        jvmArgs += listOf("-Dfile.encoding=UTF-8")
        nativeDistributions {
            targetFormats(TargetFormat.Deb)
            packageName = "NeriPlayer"
            packageVersion = "1.4.3"
            description = "NeriPlayer 音理音理 — Linux 原生 Compose Desktop 音乐播放器"
            vendor = "NeriPlayer Desktop"
            copyright = "GPL-3.0-only"
            licenseFile.set(project.file("packaging/LICENSE"))
            // java.net.http 用于在线音源请求，jdk.unsupported / java.instrument 供解码与音频库使用，
            // jdk.security.auth 供 MPRIS（dbus-java 读取 uid）使用，缺失会导致安装版媒体控制不可用
            modules(
                "java.instrument",
                "java.net.http",
                "jdk.unsupported",
                "java.logging",
                "jdk.crypto.ec",
                "jdk.security.auth",
            )
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
