package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager

/** Recovery for R6 only. New sessions leave the vehicle navigation package enabled. */
internal object XingruiClusterGuard {
    private const val PREFS = "xingrui_cluster_guard"
    private const val KEY_HELD = "we_disabled_native"
    private const val KEY_PREVIOUS = "previous_enabled_state"
    private var recorded = false

    @Synchronized fun claim(context: Context) {
        release(context, "恢复旧版本遗留状态")
        if (!recorded) {
            recorded = true
            XingruiRuntimeStatus.record("仪表冲突", "原车导航服务保持运行；通过 DIM 申请和交还仪表显示")
        }
    }

    @Synchronized fun release(context: Context, reason: String = "投屏结束"): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val held = prefs.getBoolean(KEY_HELD, false)
        if (!held) return true
        val result = XingruiLegacyNavigationRecovery.recover(object : XingruiLegacyNavigationState {
            override fun heldByOldBuild() = prefs.getBoolean(KEY_HELD, false)
            override fun previousState() = prefs.getInt(KEY_PREVIOUS, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            override fun currentState() = app.packageManager.getApplicationEnabledSetting(XingruiClusterGuardPolicy.NATIVE_PACKAGE)
            override fun restore(state: Int) {
                app.packageManager.setApplicationEnabledSetting(XingruiClusterGuardPolicy.NATIVE_PACKAGE, state, 0)
            }
            override fun clearRecoveryRecord() = prefs.edit().remove(KEY_HELD).remove(KEY_PREVIOUS).commit()
        })
        XingruiRuntimeStatus.record("仪表冲突", if (result) "旧版本原车导航状态已恢复：$reason" else
            "旧版本原车导航恢复未确认；保留恢复记录，将在下次启动或退出时重试")
        return result
    }
}
