package com.shilapi.xcertplay

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/** Android owner for the same session and reflection adapter exercised by contract tests. */
internal class XingruiDimProjection(context: Context, private val displayId: Int) : XingruiReleaseHandle {
    private val app = context.applicationContext
    private val thread = HandlerThread("xingrui-dim").apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val released = XingruiReleaseCompletion()
    @Volatile private var closing = false
    private var session: XingruiDimSession? = null
    /**
     * The alternate CarPlay decoder can release its first frame before the DIM worker has finished
     * constructing its session. Keep the latest state so that one early callback cannot permanently
     * suppress request(true).
     */
    private var latestVideoReady = false
    init { active.add(this) }
    fun start() { handler.post {
        if (closing || session != null) return@post
        try {
            XingruiClusterGuard.release(app, "新会话开始前恢复遗留状态")
            val port = XingruiReflectiveDim(app, Context::class.java, app.packageName, CarPlayHostActivity::class.java.name,
                report = { XingruiRuntimeStatus.record("DIM调用", it) })
            session = XingruiDimSession(port, XingruiScheduler { delay, task -> handler.postDelayed({ task() }, delay) },
                // Xingrui's physical DashboardMapDisplay is auto-enabled by the host even when the
                // legacy map preference is off. DIM authorization must follow that same decision.
                allowed = { AirPlayPersistence.loadClusterMapEnabled(app) || XingruiClusterProbe.find(app) != null },
                report = { XingruiRuntimeStatus.record("仪表", it) },
                finished = { finish(it) },
                claimNative = { XingruiClusterGuard.claim(app) },
                releaseNative = { XingruiClusterGuard.release(app) }).also {
                    it.start()
                    // Re-play a first-frame event that may have beaten session construction.
                    it.video(latestVideoReady)
                }
        } catch (error: Exception) {
            XingruiRuntimeStatus.record("仪表", "DIM 初始化失败：${error.cause ?: error}")
            finish(false)
        }
    } }
    fun videoReady(ready: Boolean) { handler.post {
        if (closing) return@post
        latestVideoReady = ready
        XingruiRuntimeStatus.record("仪表视频", if (ready) "首帧已提交到 Display $displayId" else "视频流已停止")
        session?.video(ready)
    } }
    @Synchronized fun close(completion: (Boolean) -> Unit = {}) {
        released.await { result -> main.post { completion(result) } }
        if (closing) return
        closing = true
        handler.post {
            session?.close() ?: finish(true)
        }
    }
    override fun release(completion: (Boolean) -> Unit) { close(completion) }
    private fun finish(success: Boolean) {
        released.finish(success)
        active.remove(this)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }
    companion object {
        private val active = XingruiReleaseRegistry()
        fun pendingReleases(): List<((Boolean) -> Unit) -> Unit> = active.pending()
    }
}
