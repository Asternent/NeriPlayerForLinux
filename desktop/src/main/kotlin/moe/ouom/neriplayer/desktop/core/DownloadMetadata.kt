package moe.ouom.neriplayer.desktop.core

import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.StandardArtwork
import org.jaudiotagger.tag.reference.PictureTypes
import java.io.File

/**
 * 下载完成后的元数据写入：
 * 把标题 / 艺术家 / 专辑 / 歌词写进音频内嵌标签，并把封面作为内嵌图片写进文件，
 * 这样其它播放器也能看到完整信息（对应手机端的 DownloadedAudioTagWriter）。
 */
object DownloadMetadataWriter {

    /** jaudiotagger 能写标签的容器（也是手机端支持写入内嵌标签的格式）。 */
    private val SUPPORTED_EXTENSIONS = setOf("mp3", "m4a", "mp4", "flac", "ogg", "oga")

    data class Result(
        val propertyWritten: Boolean,
        val coverWritten: Boolean,
        val lyricWritten: Boolean,
    )

    fun supports(file: File): Boolean = file.extension.lowercase() in SUPPORTED_EXTENSIONS

    fun write(
        file: File,
        title: String,
        artist: String,
        album: String,
        lyrics: String?,
        coverBytes: ByteArray?,
        coverMimeType: String?,
    ): Result {
        if (!supports(file) || !file.isFile) return Result(false, false, false)
        return runCatching {
            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.getTagOrCreateAndSetDefault()
            if (title.isNotBlank()) tag.setField(FieldKey.TITLE, title)
            if (artist.isNotBlank()) tag.setField(FieldKey.ARTIST, artist)
            if (album.isNotBlank()) tag.setField(FieldKey.ALBUM, album)
            var lyricWritten = false
            if (!lyrics.isNullOrBlank()) {
                tag.setField(FieldKey.LYRICS, lyrics)
                lyricWritten = true
            }
            var coverWritten = false
            if (coverBytes != null && coverBytes.isNotEmpty()) {
                runCatching {
                    tag.deleteArtworkField()
                    val artwork = StandardArtwork()
                    artwork.binaryData = coverBytes
                    artwork.mimeType = coverMimeType ?: "image/jpeg"
                    artwork.pictureType = PictureTypes.DEFAULT_ID
                    tag.setField(artwork)
                    coverWritten = true
                }
            }
            audioFile.commit()
            Result(
                propertyWritten = title.isNotBlank() || artist.isNotBlank() || album.isNotBlank(),
                coverWritten = coverWritten,
                lyricWritten = lyricWritten,
            )
        }.getOrElse { error ->
            println("[download] 写入元数据失败：${file.name} → ${error.message}")
            Result(false, false, false)
        }
    }
}

/** 根据图片字节判断扩展名与 MIME 类型。 */
fun detectImageType(bytes: ByteArray): Pair<String, String> = when {
    bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "jpg" to "image/jpeg"
    bytes.size > 8 && bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() -> "png" to "image/png"
    bytes.size > 12 && bytes[8] == 'W'.code.toByte() && bytes[9] == 'E'.code.toByte() ->
        "webp" to "image/webp"

    else -> "jpg" to "image/jpeg"
}

/** 图片 MIME（用于写内嵌封面）。 */
fun imageMimeType(bytes: ByteArray): String = detectImageType(bytes).second
