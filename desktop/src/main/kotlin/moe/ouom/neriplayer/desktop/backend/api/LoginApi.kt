package moe.ouom.neriplayer.desktop.backend.api

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import moe.ouom.neriplayer.desktop.backend.CoreServer
import moe.ouom.neriplayer.desktop.backend.ProtocolException
import moe.ouom.neriplayer.desktop.backend.badRequest
import moe.ouom.neriplayer.desktop.backend.encodeJson
import moe.ouom.neriplayer.desktop.backend.stringOrNull
import moe.ouom.neriplayer.desktop.core.AccountInfo
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.net.LoginPollResult
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * 网易云 / 哔哩哔哩扫码登录。
 *
 * 二维码由后端生成（旧 Compose 前端是 UI 侧用 zxing 画 Canvas），这里直接下发
 * `data:image/png;base64,...`，QML 可以把 `qrImageDataUrl` 直接喂给 `Image.source`。
 * 登录成功 / 登出后账号写入 AccountRepository，`accounts.changed` 事件由 StatePublisher 推送。
 * 命令清单见 desktop/docs/protocol-login.md。
 */
object LoginApi {

    private const val NETEASE_JAR_KEY = "netease"
    private const val BILIBILI_JAR_KEY = "bilibili"

    fun register(server: CoreServer, container: AppContainer) {
        server.on("login.accounts") {
            buildJsonObject {
                put("accounts", encodeJson(container.accounts.state.value.accounts))
            }
        }

        server.on("login.qrStart") { params ->
            val source = supportedSource(params.stringOrNull("source"))
            val created = withContext(Dispatchers.IO) {
                runCatching {
                    when (source) {
                        MediaSource.NETEASE -> container.neteaseLogin.createQrCode()
                        else -> container.biliLogin.createQrCode()
                    }
                }.getOrNull()
            } ?: throw ProtocolException("internal_error", "二维码获取失败，请检查网络后重试")
            val (key, qrText) = created
            buildJsonObject {
                put("source", source.name)
                put("key", key)
                put("qrText", qrText)
                put("qrImageDataUrl", qrImageDataUrl(qrText))
            }
        }

        server.on("login.qrPoll") { params ->
            val source = supportedSource(params.stringOrNull("source"))
            val key = params.stringOrNull("key")?.takeIf { it.isNotBlank() } ?: badRequest("缺少 key")
            val result = withContext(Dispatchers.IO) {
                when (source) {
                    MediaSource.NETEASE -> container.neteaseLogin.poll(key)
                    else -> container.biliLogin.poll(key)
                }
            }
            when (result) {
                LoginPollResult.Expired -> pollPayload("EXPIRED")
                is LoginPollResult.Waiting -> pollPayload(if (result.scanned) "SCANNED" else "WAITING")
                is LoginPollResult.Success -> {
                    // 登录成功必须立刻快照 Cookie，否则进程退出后登录态丢失
                    container.accounts.save(source, result.account, jarKeyOf(source))
                    pollPayload("SUCCESS", account = result.account)
                }
                is LoginPollResult.Failed -> pollPayload("FAILED", message = result.message)
            }
        }

        server.on("login.logout") { params ->
            val source = supportedSource(params.stringOrNull("source"))
            withContext(Dispatchers.IO) { container.accounts.logout(source, jarKeyOf(source)) }
            buildJsonObject { put("ok", true) }
        }

        server.on("login.refreshProfile") { params ->
            val source = supportedSource(params.stringOrNull("source"))
            val info = withContext(Dispatchers.IO) {
                when (source) {
                    MediaSource.NETEASE -> container.neteaseLogin.fetchProfile()
                    else -> container.biliLogin.fetchProfile()
                }
            } ?: throw ProtocolException("internal_error", "无法获取账号信息，请检查登录状态或网络")
            container.accounts.save(source, info, jarKeyOf(source))
            buildJsonObject { put("account", encodeJson(info)) }
        }
    }

    private fun pollPayload(
        status: String,
        message: String? = null,
        account: AccountInfo? = null,
    ): JsonObject = buildJsonObject {
        put("status", status)
        if (message != null) put("message", message)
        if (account != null) put("account", encodeJson(account))
    }

    private fun supportedSource(raw: String?): MediaSource {
        val source = raw?.trim()?.takeIf { it.isNotBlank() }
            ?.let { name -> runCatching { MediaSource.valueOf(name.uppercase()) }.getOrNull() }
            ?: badRequest("缺少或非法的 source")
        if (source != MediaSource.NETEASE && source != MediaSource.BILIBILI) {
            badRequest("不支持的登录来源: ${source.name}")
        }
        return source
    }

    private fun jarKeyOf(source: MediaSource): String =
        if (source == MediaSource.NETEASE) NETEASE_JAR_KEY else BILIBILI_JAR_KEY

    /**
     * 用 zxing 编码二维码并转成 base64 PNG data URL。
     * 后端无图形界面，AWT 仅用于内存位图，显式 headless 以避免尝试连接显示服务。
     */
    private fun qrImageDataUrl(content: String, size: Int = 512): String {
        if (System.getProperty("java.awt.headless").isNullOrBlank()) {
            System.setProperty("java.awt.headless", "true")
        }
        val matrix = runCatching {
            QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        }.getOrElse { throw ProtocolException("internal_error", "二维码生成失败：${it.message}") }
        val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                image.setRGB(x, y, if (matrix.get(x, y)) 0x000000 else 0xFFFFFF)
            }
        }
        val output = ByteArrayOutputStream()
        if (!ImageIO.write(image, "png", output)) {
            throw ProtocolException("internal_error", "当前运行环境缺少 PNG 编码器")
        }
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(output.toByteArray())
    }
}
