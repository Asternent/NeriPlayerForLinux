package moe.ouom.neriplayer.desktop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.ouom.neriplayer.desktop.core.AppContainer
import moe.ouom.neriplayer.desktop.core.MediaSource
import moe.ouom.neriplayer.desktop.core.displayName
import moe.ouom.neriplayer.desktop.net.LoginPollResult

/** 用 Compose Canvas 绘制二维码，避免额外的图片编码依赖。 */
@Composable
fun QrCodeView(
    content: String,
    size: Dp = 240.dp,
    modifier: Modifier = Modifier,
) {
    val matrix by produceState<BitMatrix?>(initialValue = null, content) {
        value = withContext(Dispatchers.Default) {
            runCatching {
                QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 512, 512)
            }.getOrNull()
        }
    }
    Surface(
        modifier = modifier.size(size),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
    ) {
        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val current = matrix
            if (current == null) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val cell = this.size.width / current.width
                    for (y in 0 until current.height) {
                        for (x in 0 until current.width) {
                            if (current.get(x, y)) {
                                drawRect(
                                    color = Color.Black,
                                    topLeft = Offset(x * cell, y * cell),
                                    size = Size(cell + 0.6f, cell + 0.6f),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private data class LoginSession(
    val key: String,
    val qrContent: String,
)

/**
 * 扫码登录弹窗：网易云与哔哩哔哩共用，区别只在二维码内容与轮询接口。
 */
@Composable
fun LoginDialog(
    container: AppContainer,
    source: MediaSource,
    onDismiss: () -> Unit,
    showMessage: (String) -> Unit,
) {
    var session by remember(source) { mutableStateOf<LoginSession?>(null) }
    var status by remember(source) { mutableStateOf("正在生成二维码…") }
    var refreshing by remember(source) { mutableStateOf(false) }
    var finished by remember(source) { mutableStateOf(false) }

    suspend fun startSession() {
        refreshing = true
        status = "正在生成二维码…"
        val created = withContext(Dispatchers.IO) {
            when (source) {
                MediaSource.NETEASE -> container.neteaseLogin.createQrCode()
                MediaSource.BILIBILI -> container.biliLogin.createQrCode()
                else -> null
            }
        }
        if (created == null) {
            session = null
            status = "二维码获取失败，请检查网络后重试"
        } else {
            session = LoginSession(created.first, created.second)
            status = "请使用${source.displayName}手机客户端扫码登录"
        }
        refreshing = false
    }

    LaunchedEffect(source) { startSession() }

    LaunchedEffect(session?.key) {
        val key = session?.key ?: return@LaunchedEffect
        while (true) {
            delay(2_000)
            val result = withContext(Dispatchers.IO) {
                when (source) {
                    MediaSource.NETEASE -> container.neteaseLogin.poll(key)
                    MediaSource.BILIBILI -> container.biliLogin.poll(key)
                    else -> LoginPollResult.Failed("不支持的平台")
                }
            }
            when (result) {
                LoginPollResult.Expired -> {
                    status = "二维码已过期，请点击刷新"
                    break
                }

                is LoginPollResult.Waiting -> {
                    status = if (result.scanned) {
                        "已扫码，请在手机上确认登录"
                    } else {
                        "请使用${source.displayName}手机客户端扫码登录"
                    }
                }

                is LoginPollResult.Success -> {
                    val jarKey = if (source == MediaSource.NETEASE) "netease" else "bilibili"
                    container.accounts.save(source, result.account, jarKey)
                    status = "登录成功：${result.account.displayName()}"
                    finished = true
                    showMessage("已登录${source.displayName}：${result.account.displayName()}")
                    delay(900)
                    onDismiss()
                    break
                }

                is LoginPollResult.Failed -> {
                    status = result.message
                    break
                }
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${source.displayName}扫码登录") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val current = session
                if (current != null) {
                    QrCodeView(content = current.qrContent, size = 240.dp)
                } else {
                    Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        if (refreshing) {
                            CircularProgressIndicator()
                        } else {
                            Icon(
                                imageVector = Icons.Outlined.QrCode2,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (finished) {
                        Icon(
                            imageVector = Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "登录后可访问你的歌单 / 收藏夹，并使用更高音质；账号凭据仅保存在本机配置目录。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        },
        dismissButton = {
            val scope = rememberCoroutineScope()
            TextButton(onClick = { scope.launch { startSession() } }) {
                Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("刷新二维码")
            }
        },
    )
}

/** 设置页「账号」分组使用的一行账号信息。 */
@Composable
fun AccountRow(
    source: MediaSource,
    nickname: String?,
    detail: String,
    isLoggedIn: Boolean,
    onLogin: () -> Unit,
    onLogout: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = source.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = if (isLoggedIn) "已登录：${nickname.orEmpty()} · $detail" else detail,
                style = MaterialTheme.typography.labelSmall,
                color = if (isLoggedIn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (isLoggedIn) {
            TextButton(onClick = onLogout) { Text("退出登录") }
        } else {
            TextButton(onClick = onLogin) { Text("扫码登录") }
        }
    }
}
