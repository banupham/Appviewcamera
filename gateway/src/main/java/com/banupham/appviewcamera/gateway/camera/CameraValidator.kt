package com.banupham.appviewcamera.gateway.camera

import com.banupham.appviewcamera.gateway.rtsp.RtspUrlFactory

object CameraValidator {
    private val relayPathPattern = Regex("^[a-zA-Z0-9_-]{1,64}$")

    fun validate(draft: CameraDraft): List<String> = buildList {
        if (draft.name.isBlank()) add("Tên camera không được để trống")
        if (draft.ip.isBlank()) add("Địa chỉ IP hoặc hostname không được để trống")
        val port = draft.port.toIntOrNull()
        if (port == null || port !in 1..65535) add("Cổng phải nằm trong khoảng 1–65535")
        if (!RtspUrlFactory.isValid(draft.mainRtspUrl)) add("Main RTSP URL phải có dạng rtsp://host/path")
        if (draft.subRtspUrl.isNotBlank() && !RtspUrlFactory.isValid(draft.subRtspUrl)) {
            add("Sub RTSP URL không hợp lệ")
        }
        if (!relayPathPattern.matches(draft.relayPath)) {
            add("Relay path chỉ gồm chữ, số, gạch ngang hoặc gạch dưới")
        }
    }
}
