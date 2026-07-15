package com.banupham.appviewcamera.viewer.playback

import com.banupham.appviewcamera.viewer.api.PlaybackItem

object PlaybackSourceSelector {
    fun select(item: PlaybackItem, primaryFailed: Boolean = false): String? {
        if (item.status != "READY") return null
        if (primaryFailed && item.youtubeAvailable) return "YOUTUBE_READY"
        return when {
            item.localAvailable -> "LOCAL_CACHE"
            item.driveAvailable -> "DRIVE_READY"
            item.youtubeAvailable -> "YOUTUBE_READY"
            else -> null
        }
    }
}
