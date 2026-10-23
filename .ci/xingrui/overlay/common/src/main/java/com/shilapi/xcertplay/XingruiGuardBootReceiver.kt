package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Registered only in the platform build: hands the vehicle navigation service back after boot or an app update. */
class XingruiGuardBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finishOnce() { if (finished.compareAndSet(false, true)) pending.finish() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finishOnce() }, 8000)
        Thread({
            try {
                runCatching { XingruiClusterGuard.release(context, "开机或应用更新后恢复") }
                    .onFailure { XingruiRuntimeStatus.record("退出恢复", "启动恢复失败：${it.message}") }
            } finally { finishOnce() }
        }, "xingrui-boot-recovery").apply { isDaemon = true; start() }
    }
}
