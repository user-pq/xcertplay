package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread

/** Android owner for the same session and reflection adapter exercised by contract tests. */
internal class XingruiDimProjection(context: Context, private val displayId: Int) {
    private val app = context.applicationContext
    private val thread = HandlerThread("xingrui-dim").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closing = false
    private var session: XingruiDimSession? = null
    fun start() { handler.post {
        if (closing || session != null) return@post
        try {
            XingruiClusterGuard.release(app, "新会话开始前恢复遗留状态")
            val port = XingruiReflectiveDim(app, Context::class.java, app.packageName, CarPlayHostActivity::class.java.name)
            session = XingruiDimSession(port, XingruiScheduler { delay, task -> handler.postDelayed({ task() }, delay) },
                allowed = { AirPlayPersistence.loadClusterMapEnabled(app) },
                report = { XingruiRuntimeStatus.record("仪表", it) },
                finished = { handler.removeCallbacksAndMessages(null); thread.quitSafely() },
                claimNative = { XingruiClusterGuard.claim(app) },
                releaseNative = { XingruiClusterGuard.release(app) }).also { it.start() }
        } catch (error: Exception) {
            XingruiRuntimeStatus.record("仪表", "DIM 初始化失败：${error.cause ?: error}")
            handler.removeCallbacksAndMessages(null); thread.quitSafely()
        }
    } }
    fun videoReady(ready: Boolean) { handler.post {
        if (closing) return@post
        XingruiRuntimeStatus.record("仪表视频", if (ready) "首帧已提交到 Display $displayId" else "视频流已停止")
        session?.video(ready)
    } }
    fun close() {
        if (closing) return
        closing = true
        handler.post {
            session?.close() ?: run { handler.removeCallbacksAndMessages(null); thread.quitSafely() }
        }
    }
}
