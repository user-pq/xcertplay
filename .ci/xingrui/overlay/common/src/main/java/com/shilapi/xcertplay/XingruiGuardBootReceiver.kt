package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Registered only in the platform build: hands the vehicle navigation service back after boot or an app update. */
class XingruiGuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { XingruiClusterGuard.release(context, "开机或应用更新后恢复") }
    }
}
