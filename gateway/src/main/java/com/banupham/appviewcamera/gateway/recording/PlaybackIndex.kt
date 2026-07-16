package com.banupham.appviewcamera.gateway.recording

import com.banupham.appviewcamera.gateway.database.RecordingClipEntity
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

class PlaybackIndex(
    private val recordings: RecordingRepository,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {
    suspend fun days(cameraId: String, limit: Int): JSONObject {
        require(cameraId.isNotBlank()) { "camera_id là bắt buộc" }
        val grouped = recordings.recentForDays(cameraId)
            .groupBy { Instant.ofEpochMilli(it.startedAtMs).atZone(zoneId).toLocalDate() }
            .toSortedMap(compareByDescending { it })
            .entries.take(limit.coerceIn(1, 365))
        return JSONObject().put("camera_id", cameraId).put("days", JSONArray().apply {
            grouped.forEach { (day, clips) ->
                put(JSONObject()
                    .put("day", day.toString())
                    .put("item_count", clips.size)
                    .put("first_start_time", clips.minOfOrNull(RecordingClipEntity::startedAtMs))
                    .put("last_end_time", clips.maxOfOrNull { it.startedAtMs + (it.durationMs ?: 0) }))
            }
        })
    }

    suspend fun timeline(cameraId: String, fromMs: Long?, toMs: Long?, day: String?, limit: Int): JSONObject {
        require(cameraId.isNotBlank()) { "camera_id là bắt buộc" }
        val range = resolveRange(fromMs, toMs, day)
        val clips = recordings.timeline(cameraId, range.first, range.second, limit)
        return JSONObject()
            .put("camera_id", cameraId)
            .put("day", Instant.ofEpochMilli(range.first).atZone(zoneId).toLocalDate().toString())
            .put("from_ms", range.first)
            .put("to_ms", range.second)
            .put("count", clips.size)
            .put("items", JSONArray().apply { clips.forEach { put(itemJson(it)) } })
    }

    suspend fun item(id: String): JSONObject? = recordings.get(id)
        ?.takeUnless { it.clipState == "RECORDING" }
        ?.let(::itemJson)

    suspend fun sources(id: String): JSONObject? {
        val clip = recordings.get(id)?.takeUnless { it.clipState == "RECORDING" } ?: return null
        val localReady = recordings.localFile(clip) != null
        val driveReady = clip.remoteCopyVerified()
        val youtubeReady = clip.youtubeCopyVerified()
        val preferred = when { localReady -> "LOCAL_CACHE"; driveReady -> "DRIVE_READY"; youtubeReady -> "YOUTUBE_READY"; else -> null }
        val encoded = URLEncoder.encode(id, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return JSONObject().put("item_id", id).put("preferred_source", preferred).put("sources", JSONArray().apply {
            put(JSONObject().put("type", "LOCAL_CACHE").put("state", if (localReady) "READY" else "UNAVAILABLE")
                .put("stream_url", if (localReady) "/api/playback/items/$encoded/stream?source=local" else JSONObject.NULL))
            put(JSONObject().put("type", "DRIVE_READY").put("state", if (driveReady) "READY" else "UNAVAILABLE")
                .put("stream_url", JSONObject.NULL))
            put(JSONObject().put("type", "YOUTUBE_READY").put("state", if (youtubeReady) "READY" else clip.youtubeStatus)
                .put("video_id", clip.youtubeVideoId ?: JSONObject.NULL)
                .put("start_offset_seconds", clip.youtubeStartOffsetSeconds)
                .put("watch_url", if (youtubeReady) "https://www.youtube.com/watch?v=${clip.youtubeVideoId}&t=${clip.youtubeStartOffsetSeconds}s" else JSONObject.NULL)
                .put("requires_google_sign_in", youtubeReady))
        })
    }

    fun itemJson(clip: RecordingClipEntity): JSONObject {
        val local = clip.localState == "AVAILABLE"
        val drive = clip.remoteCopyVerified()
        val youtube = clip.youtubeCopyVerified()
        val preferred = when { local -> "LOCAL_CACHE"; drive -> "DRIVE_READY"; youtube -> "YOUTUBE_READY"; else -> null }
        val status = when {
            preferred != null -> "READY"
            clip.clipState == "FAILED" || clip.uploadState == "FAILED" || clip.youtubeStatus == "YOUTUBE_FAILED" -> "FAILED"
            clip.clipState in setOf("LOCAL_PENDING", "DRIVE_UPLOADING", "UPLOAD_RETRY") -> "PROCESSING"
            else -> "UNAVAILABLE"
        }
        return JSONObject()
            .put("id", clip.id).put("camera_id", clip.cameraId)
            .put("start_time", clip.startedAtMs).put("end_time", clip.startedAtMs + (clip.durationMs ?: 0))
            .put("duration", clip.durationMs ?: JSONObject.NULL).put("size_bytes", clip.sizeBytes)
            .put("motion", clip.motion).put("protected", clip.isProtected)
            .put("local_available", local).put("drive_available", drive).put("youtube_available", youtube)
            .put("youtube_video_id", if (youtube) clip.youtubeVideoId else JSONObject.NULL)
            .put("youtube_start_offset_seconds", clip.youtubeStartOffsetSeconds)
            .put("status", status).put("preferred_source", preferred ?: JSONObject.NULL)
            .put("last_error", clip.lastError ?: clip.youtubeLastError ?: JSONObject.NULL)
    }

    private fun resolveRange(fromMs: Long?, toMs: Long?, day: String?): Pair<Long, Long> {
        if (fromMs != null || toMs != null) {
            require(fromMs != null && toMs != null && fromMs < toMs) { "from_ms và to_ms phải tạo thành khoảng hợp lệ" }
            return fromMs to toMs
        }
        val selected = runCatching { LocalDate.parse(day.orEmpty()) }
            .getOrElse { throw IllegalArgumentException("day phải theo định dạng YYYY-MM-DD") }
        return selected.atStartOfDay(zoneId).toInstant().toEpochMilli() to
            selected.plusDays(1).atStartOfDay(zoneId).toInstant().toEpochMilli()
    }
}
