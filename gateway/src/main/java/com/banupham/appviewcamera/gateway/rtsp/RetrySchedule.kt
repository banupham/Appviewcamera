package com.banupham.appviewcamera.gateway.rtsp

object RetrySchedule {
    private val delaysMillis = longArrayOf(5_000, 10_000, 30_000)

    const val MAX_ATTEMPTS = 4

    fun delayAfterFailure(failedAttempt: Int): Long =
        delaysMillis.getOrElse(failedAttempt - 1) { 60_000 }
}
