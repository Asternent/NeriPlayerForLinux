package moe.ouom.neriplayer.desktop.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.desktop.net.HttpService
import java.io.File

@Serializable
data class AccountInfo(
    val source: String,
    val nickname: String = "",
    val userId: String = "",
    val avatarUrl: String? = null,
    val vip: Boolean = false,
    val loginAt: Long = 0L,
)

@Serializable
data class AccountStoreData(
    val accounts: List<AccountInfo> = emptyList(),
    val cookies: Map<String, Map<String, String>> = emptyMap(),
)

/** 账号仓库：保存各平台登录态（Cookie）与账号信息，文件权限收紧到 600。 */
class AccountRepository(private val http: HttpService) {

    private val store = JsonFileStore(AppDirs.accountFile, AccountStoreData.serializer()) { AccountStoreData() }

    private val _state = MutableStateFlow(AccountStoreData())
    val state: StateFlow<AccountStoreData> = _state.asStateFlow()

    fun load() {
        val data = store.load()
        _state.value = data
        data.cookies.forEach { (key, cookies) -> http.importCookies(key, cookies) }
        restrictPermissions()
    }

    fun accountOf(source: MediaSource): AccountInfo? =
        _state.value.accounts.firstOrNull { it.source == source.name }

    fun isLoggedIn(source: MediaSource): Boolean =
        accountOf(source)?.let { it.userId.isNotBlank() || it.nickname.isNotBlank() } == true

    fun save(source: MediaSource, info: AccountInfo, jarKey: String) {
        val cookies = http.cookieSnapshot(jarKey)
        val next = _state.value.let { current ->
            current.copy(
                accounts = current.accounts.filterNot { it.source == source.name } + info.copy(source = source.name),
                cookies = current.cookies + (jarKey to cookies),
            )
        }
        _state.value = next
        store.save(next)
        restrictPermissions()
    }

    fun logout(source: MediaSource, jarKey: String) {
        http.clearCookies(jarKey)
        val next = _state.value.copy(
            accounts = _state.value.accounts.filterNot { it.source == source.name },
            cookies = _state.value.cookies - jarKey,
        )
        _state.value = next
        store.save(next)
    }

    private fun restrictPermissions() {
        runCatching {
            val file = AppDirs.accountFile
            if (file.isFile) {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }
}
