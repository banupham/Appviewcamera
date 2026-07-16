package com.banupham.appviewcamera.gateway.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.banupham.appviewcamera.gateway.GatewayApplication

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val settings = (context.applicationContext as GatewayApplication).container.gatewaySettings.settings.value
        if (settings.autoStart) runCatching { GatewayServerService.start(context) }
    }
}
