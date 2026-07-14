package com.banupham.appviewcamera.gateway.rtsp

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryScheduleTest {
    @Test
    fun followsRequiredBackoff() {
        assertEquals(5_000L, RetrySchedule.delayAfterFailure(1))
        assertEquals(10_000L, RetrySchedule.delayAfterFailure(2))
        assertEquals(30_000L, RetrySchedule.delayAfterFailure(3))
        assertEquals(60_000L, RetrySchedule.delayAfterFailure(4))
        assertEquals(60_000L, RetrySchedule.delayAfterFailure(20))
    }
}
