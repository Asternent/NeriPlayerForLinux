@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package moe.ouom.neriplayer.desktop.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * 与手机端完全一致的三通道序列化：
 * - 普通通道：UTF-8 JSON 文本（backup.json）
 * - 省流通道：原始 GZIP(ProtoBuf) 字节（backup-raw.bin，以 0x1F 0x8B 开头）
 * - 兼容旧版：Base64(GZIP(ProtoBuf)) 文本（backup.bin）
 */
object SyncDataSerializer {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    private val protoBuf = ProtoBuf

    private const val MAX_DECOMPRESSED_BYTES = 32 * 1024 * 1024
    private const val MAX_UPLOAD_BYTES = 12 * 1024 * 1024

    fun fileNameFor(useDataSaver: Boolean): String =
        if (useDataSaver) SYNC_RAW_BINARY_FILE_NAME else SYNC_JSON_FILE_NAME

    /** 只读兼容：找不到当前通道文件时按顺序回退（与手机端一致）。 */
    fun readFallbackFileNames(useDataSaver: Boolean): List<String> =
        if (useDataSaver) {
            listOf(SYNC_LEGACY_BINARY_FILE_NAME, SYNC_JSON_FILE_NAME)
        } else {
            listOf(SYNC_RAW_BINARY_FILE_NAME, SYNC_LEGACY_BINARY_FILE_NAME)
        }

    fun serialize(data: SyncData, useDataSaver: Boolean): ByteArray {
        val content = if (useDataSaver) {
            gzip(protoBuf.encodeToByteArray(SyncData.serializer(), data))
        } else {
            json.encodeToString(SyncData.serializer(), data).toByteArray(Charsets.UTF_8)
        }
        require(content.size <= MAX_UPLOAD_BYTES) { "同步数据过大，已超过 12MB 上限" }
        return content
    }

    fun deserialize(content: ByteArray): SyncData {
        if (content.isEmpty()) return SyncData()
        // 1. 原始 GZIP(ProtoBuf)
        if (content.size > 2 && content[0] == 0x1F.toByte() && content[1] == 0x8B.toByte()) {
            return protoBuf.decodeFromByteArray(SyncData.serializer(), gunzip(content))
        }
        val text = content.decodeToString().trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        // 2. JSON 文本
        if (text.startsWith("{")) {
            return json.decodeFromString(SyncData.serializer(), text)
        }
        // 3. 旧版 Base64(GZIP(ProtoBuf))
        return runCatching {
            protoBuf.decodeFromByteArray(SyncData.serializer(), gunzip(Base64.getDecoder().decode(text)))
        }.getOrElse { SyncData() }
    }

    fun isBinaryFileName(fileName: String): Boolean = fileName.endsWith(".bin")

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPInputStream(bytes.inputStream()).use { stream ->
            val buffer = ByteArray(1 shl 16)
            var total = 0
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_DECOMPRESSED_BYTES) { "同步数据解压后过大" }
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }
}
