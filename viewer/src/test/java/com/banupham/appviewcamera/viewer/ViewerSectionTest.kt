package com.banupham.appviewcamera.viewer

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerSectionTest {
    @Test
    fun containsTheFiveRequiredTopLevelSectionsInOrder() {
        assertEquals(
            listOf("Trực tiếp", "Xem lại", "Thiết bị", "Lưu trữ", "Cài đặt"),
            ViewerSection.entries.map { it.label }
        )
    }
}
