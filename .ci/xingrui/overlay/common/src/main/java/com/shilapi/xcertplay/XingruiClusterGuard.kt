package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import java.io.File

/**
 * Platform-build only. While CarPlay owns the cluster, the vehicle's own navigation service is disabled
 * so it cannot take the cluster back, and it is always handed back afterwards (session end, next boot,
 * package replacement). Opt out by creating /sdcard/Download/XingruiLogs/no-cluster-guard.
 * Manual recovery if the app is removed while held: adb shell pm default-state ecarx.naviservice
 */
internal object XingruiClusterGuard {
    private const val PREFS = "xingrui_cluster_guard"
    private const val KEY_HELD = "we_disabled_native"
    private const val KEY_PREVIOUS = "previous_enabled_state"
    private const val AREA = "仪表冲突"
    private val OPT_OUT_MARKER = File("/sdcard/Download/XingruiLogs/no-cluster-guard")
    private var notPlatformRecorded = false

    private fun prefs(app: Context) = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun isPlatform(app: Context) = app.applicationInfo.uid == Process.SYSTEM_UID
    private fun optedOut() = runCatching { OPT_OUT_MARKER.exists() }.getOrDefault(false)

    private fun nativeEnabled(pm: PackageManager): Boolean? = runCatching {
        when (pm.getApplicationEnabledSetting(XingruiClusterGuardPolicy.NATIVE_PACKAGE)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            else -> false
        }
    }.getOrNull()

    @Synchronized fun claim(context: Context) {
        val app = context.applicationContext
        val platform = isPlatform(app)
        if (!platform) {
            if (!notPlatformRecorded) {
                notPlatformRecorded = true
                XingruiRuntimeStatus.record(AREA, "非平台版，不处理车机导航服务（仍可能被原车导航抢占仪表）")
            }
            return
        }
        val pm = app.packageManager
        when (XingruiClusterGuardPolicy.onClaim(platform, optedOut(), prefs(app).getBoolean(KEY_HELD, false), nativeEnabled(pm))) {
            XingruiGuardAction.DISABLE_NATIVE -> disable(app, pm)
            XingruiGuardAction.RESTORE_NATIVE -> restore(app, pm, "已关闭仪表冲突处理")
            XingruiGuardAction.NONE -> Unit
        }
    }

    /** Session end. Also used for boot and package replacement recovery. */
    @Synchronized fun release(context: Context, reason: String = "投屏结束") {
        val app = context.applicationContext
        if (XingruiClusterGuardPolicy.onRelease(prefs(app).getBoolean(KEY_HELD, false)) == XingruiGuardAction.RESTORE_NATIVE) {
            restore(app, app.packageManager, reason)
        }
    }

    private fun disable(app: Context, pm: PackageManager) {
        // Record intent first: if the process dies mid-way, boot recovery still knows to restore.
        val previous = runCatching { pm.getApplicationEnabledSetting(XingruiClusterGuardPolicy.NATIVE_PACKAGE) }.getOrNull()
            ?: return
        if (!prefs(app).edit().putInt(KEY_PREVIOUS, previous).putBoolean(KEY_HELD, true).commit()) {
            XingruiRuntimeStatus.record(AREA, "无法保存恢复状态，保留原车导航服务")
            return
        }
        val result = runCatching {
            pm.setApplicationEnabledSetting(XingruiClusterGuardPolicy.NATIVE_PACKAGE,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER, 0)
        }
        if (result.isSuccess) {
            XingruiRuntimeStatus.record(AREA, "已临时禁用 ${XingruiClusterGuardPolicy.NATIVE_PACKAGE}，投屏结束后自动恢复")
        } else {
            XingruiRuntimeStatus.record(AREA, "禁用车机导航服务失败：${result.exceptionOrNull()?.message}")
            restore(app, pm, "禁用失败后核查恢复")
        }
    }

    private fun restore(app: Context, pm: PackageManager, reason: String) {
        val result = runCatching {
            pm.setApplicationEnabledSetting(XingruiClusterGuardPolicy.NATIVE_PACKAGE,
                prefs(app).getInt(KEY_PREVIOUS, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT), 0)
        }
        if (result.isSuccess) {
            prefs(app).edit().putBoolean(KEY_HELD, false).remove(KEY_PREVIOUS).commit()
            XingruiRuntimeStatus.record(AREA, "已恢复 ${XingruiClusterGuardPolicy.NATIVE_PACKAGE}：$reason")
        } else {
            // Keep the flag so the next release, boot or app start retries.
            XingruiRuntimeStatus.record(AREA, "恢复车机导航服务失败，将重试：${result.exceptionOrNull()?.message}")
        }
    }
}
