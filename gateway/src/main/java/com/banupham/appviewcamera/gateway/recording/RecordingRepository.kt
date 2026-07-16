package com.banupham.appviewcamera.gateway.recording

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.StatFs
import com.banupham.appviewcamera.gateway.database.RecordingClipDao
import com.banupham.appviewcamera.gateway.database.RecordingClipEntity
import java.io.File
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class RecordingStorageStatus(
    val clipCount: Int,
    val diskFreeBytes: Long,
    val diskTotalBytes: Long,
    val localBytes: Long,
    val cacheBudgetBytes: Long,
    val storagePressure: Boolean,
    val pendingUploads: Int,
    val failedUploads: Int,
    val uploadedClips: Int
)

class RecordingRepository(
    context: Context,
    private val dao: RecordingClipDao,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    val root: File = File(context.filesDir, "recordings")
    private val storageRoot = context.filesDir

    suspend fun scan(cameraIds: Set<String>, localRetentionMinutes: Int = 60): RecordingStorageStatus {
        root.mkdirs()
        val seen = mutableSetOf<String>()
        root.walkTopDown().filter { it.isFile && it.extension.equals("mp4", true) }.forEach { file ->
            val relative = file.relativeTo(root).invariantSeparatorsPath
            val cameraId = relative.substringBefore('/')
            if (cameraId !in cameraIds) return@forEach
            seen += relative
            val modifiedMs = file.lastModified()
            val sizeBytes = file.length()
            val existing = dao.getByPath(relative)
            val recording = nowMs() - modifiedMs < STABLE_AFTER_MS
            if (existing != null && existing.modifiedMs == modifiedMs && existing.sizeBytes == sizeBytes &&
                ((recording && existing.clipState == "RECORDING") || (!recording && existing.clipState != "RECORDING"))) {
                return@forEach
            }
            val clipState = if (recording) "RECORDING" else when {
                existing?.remoteCopyVerified() == true -> "LOCAL_CACHE"
                else -> "LOCAL_PENDING"
            }
            dao.upsert(
                (existing ?: RecordingClipEntity(
                    id = stableId(relative),
                    cameraId = cameraId,
                    relativePath = relative,
                    startedAtMs = parseStartedAt(file.nameWithoutExtension) ?: modifiedMs,
                    durationMs = null,
                    sizeBytes = sizeBytes,
                    modifiedMs = modifiedMs
                )).copy(
                    cameraId = cameraId,
                    startedAtMs = existing?.startedAtMs ?: parseStartedAt(file.nameWithoutExtension) ?: modifiedMs,
                    durationMs = if (recording) null else probeDurationMs(file),
                    sizeBytes = sizeBytes,
                    modifiedMs = modifiedMs,
                    clipState = clipState,
                    localState = "AVAILABLE",
                    localDeletedAtMs = null,
                    stateUpdatedAtMs = nowMs()
                )
            )
        }
        dao.localClipsOldestFirst().filter { it.relativePath !in seen }.forEach { dao.markMissing(it.id, nowMs()) }
        enforceLocalBudget(localRetentionMinutes)
        return status()
    }

    suspend fun status(): RecordingStorageStatus {
        val stat = StatFs(storageRoot.absolutePath)
        val total = stat.totalBytes
        val free = stat.availableBytes
        val localBytes = dao.localClipsOldestFirst().sumOf(RecordingClipEntity::sizeBytes)
        val budget = cacheBudget(total)
        return RecordingStorageStatus(
            clipCount = dao.clipCount(),
            diskFreeBytes = free,
            diskTotalBytes = total,
            localBytes = localBytes,
            cacheBudgetBytes = budget,
            storagePressure = localBytes > budget || free < MIN_FREE_BYTES,
            pendingUploads = dao.uploadCount("PENDING"),
            failedUploads = dao.uploadCount("FAILED"),
            uploadedClips = dao.uploadCount("UPLOADED")
        )
    }

    suspend fun list(cameraId: String?, fromMs: Long?, toMs: Long?, limit: Int): List<RecordingClipEntity> =
        dao.list(cameraId, fromMs, toMs, limit.coerceIn(1, 1_000))

    suspend fun timeline(cameraId: String, fromMs: Long, toMs: Long, limit: Int): List<RecordingClipEntity> =
        dao.timeline(cameraId, fromMs, toMs, limit.coerceIn(1, 2_000))

    suspend fun recentForDays(cameraId: String): List<RecordingClipEntity> = dao.recentForDays(cameraId, 20_000)

    suspend fun get(id: String): RecordingClipEntity? = dao.get(id)

    suspend fun setProtected(id: String, protected: Boolean): RecordingClipEntity? {
        if (dao.setProtected(id, protected, nowMs()) == 0) return null
        return dao.get(id)
    }

    suspend fun localFile(clip: RecordingClipEntity): File? {
        if (clip.localState != "AVAILABLE") return null
        val file = File(root, clip.relativePath).canonicalFile
        val canonicalRoot = root.canonicalFile
        if (!file.path.startsWith(canonicalRoot.path + File.separator) || !file.isFile) return null
        return file
    }

    private suspend fun enforceLocalBudget(localRetentionMinutes: Int) {
        val budget = cacheBudget(StatFs(storageRoot.absolutePath).totalBytes)
        val clips = dao.localClipsOldestFirst()
        var total = clips.sumOf(RecordingClipEntity::sizeBytes)
        val retentionCutoff = nowMs() - localRetentionMinutes.coerceAtLeast(5) * 60_000L
        for (clip in clips) {
            if (total <= budget && clip.startedAtMs >= retentionCutoff) continue
            if (!RetentionPolicy.canDeleteLocal(clip)) continue
            val file = localFile(clip) ?: continue
            if (file.delete()) {
                total -= clip.sizeBytes
                dao.markLocalDeleted(clip.id, nowMs())
            }
        }
    }

    private fun probeDurationMs(file: File): Long? = runCatching {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } finally {
            retriever.release()
        }
    }.getOrNull()

    companion object {
        private const val STABLE_AFTER_MS = 2_000L
        private const val MIN_FREE_BYTES = 512L * 1024 * 1024
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSSSSS")

        fun stableId(relativePath: String): String = MessageDigest.getInstance("SHA-256")
            .digest(relativePath.toByteArray())
            .take(12)
            .joinToString("") { "%02x".format(it) }

        fun parseStartedAt(stem: String, zoneId: ZoneId = ZoneId.systemDefault()): Long? = try {
            LocalDateTime.parse(stem, FILE_TIME).atZone(zoneId).toInstant().toEpochMilli()
        } catch (_: DateTimeParseException) {
            null
        }

        fun cacheBudget(totalBytes: Long): Long = totalBytes / 100L
    }
}

object RetentionPolicy {
    fun canDeleteLocal(clip: RecordingClipEntity): Boolean = !clip.isProtected &&
        clip.clipState != "RECORDING" && (clip.remoteCopyVerified() || clip.youtubeCopyVerified())
}

fun RecordingClipEntity.remoteCopyVerified(): Boolean = uploadState == "UPLOADED" &&
    !remoteId.isNullOrBlank() && !remoteFileId.isNullOrBlank() && remoteVerifiedAtMs != null &&
    remoteSizeBytes == sizeBytes

fun RecordingClipEntity.youtubeCopyVerified(): Boolean = youtubeStatus == "YOUTUBE_READY" &&
    !youtubeVideoId.isNullOrBlank()
