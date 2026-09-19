package moe.ouom.neriplayer.desktop.sync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import moe.ouom.neriplayer.desktop.core.AppDirs
import moe.ouom.neriplayer.desktop.core.JsonFileStore

@Serializable
data class SyncConfig(
    val deviceId: String = "",
    val token: String = "",
    val owner: String = "",
    val repo: String = "",
    val useDataSaver: Boolean = false,
    val autoSync: Boolean = true,
    /** 支持 GitHub Enterprise / 自建地址；默认 github.com。 */
    val apiBase: String = "https://api.github.com",
    val lastSyncAt: Long = 0L,
    val lastStatus: String = "",
) {
    val configured: Boolean get() = token.isNotBlank() && owner.isNotBlank() && repo.isNotBlank()

    val repoFullName: String get() = if (owner.isBlank() || repo.isBlank()) "" else "$owner/$repo"
}

/** 同步配置（含 GitHub Token）单独存放，文件权限收紧到 600。 */
class SyncConfigStore {

    private val store = JsonFileStore(AppDirs.syncConfigFile, SyncConfig.serializer()) { SyncConfig() }

    private val _state = MutableStateFlow(store.load())
    val state: StateFlow<SyncConfig> = _state.asStateFlow()

    val current: SyncConfig get() = _state.value

    fun update(transform: (SyncConfig) -> SyncConfig) {
        val next = transform(_state.value)
        _state.value = next
        store.save(next)
        restrictPermissions()
    }

    fun clear() {
        update { SyncConfig(autoSync = it.autoSync) }
    }

    private fun restrictPermissions() {
        runCatching {
            val file = AppDirs.syncConfigFile
            if (file.isFile) {
                file.setReadable(false, false)
                file.setReadable(true, true)
                file.setWritable(true, true)
            }
        }
    }
}
