package com.banupham.appviewcamera.gateway

enum class GatewayServiceState(val displayName: String) {
    NOT_CONFIGURED("Chưa cấu hình"),
    STARTING("Đang khởi động"),
    RUNNING("Đang chạy"),
    DEGRADED("Cần kiểm tra"),
    STOPPED("Đã dừng")
}

data class GatewayState(
    val serviceState: GatewayServiceState = GatewayServiceState.NOT_CONFIGURED,
    val configuredCameraCount: Int = 0,
    val recordingCameraCount: Int = 0
) {
    init {
        require(configuredCameraCount >= 0) { "Số camera không được âm" }
        require(recordingCameraCount in 0..configuredCameraCount) {
            "Số camera đang ghi phải nằm trong số camera đã cấu hình"
        }
    }
}
