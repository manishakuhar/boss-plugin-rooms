package ai.rever.boss.plugin.dynamic.rooms

import kotlinx.serialization.Serializable

/** Implemented by an authenticated host storage broker; never expose service credentials. */
interface RoomsAttachmentProvider {
    suspend fun upload(org: String, room: String, id: String, bytes: ByteArray, progress: (Int) -> Unit)
    suspend fun download(org: String, room: String, id: String): ByteArray
}

@Serializable data class Attachment(
    val id: String, val room_id: String, val owner_id: String, val message_id: String? = null,
    val name: String, val size: Long, val mime: String, val status: String = "uploading",
)
data class UploadDraft(val attachment: Attachment, val path: String, val parent: String? = null, val progress: Int = 0, val ready: Boolean = false, val error: String? = null)

fun attachmentMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "pdf" -> "application/pdf"
    "txt", "md", "csv", "log" -> "text/plain"
    else -> error("Choose a PNG, JPEG, PDF or text file, up to 10 MB.")
}
