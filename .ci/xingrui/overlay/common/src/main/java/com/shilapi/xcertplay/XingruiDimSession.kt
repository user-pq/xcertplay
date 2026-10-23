package com.shilapi.xcertplay

/** Production lifecycle; transitions run on the scheduler's owner thread. */
internal class XingruiDimSession(private val port: XingruiDimPort, private val scheduler: XingruiScheduler,
    private val allowed: () -> Boolean, private val report: (String) -> Unit, private val finished: (Boolean) -> Unit,
    private val claimNative: () -> Unit = {}, private val releaseNative: () -> Unit = {}) {
    private val gate = XingruiProjectionGate()
    private var connected = false
    private var started = false
    private var closing = false
    private var disposed = false
    private var enableAttempted = false
    private var releaseConfirmed = false
    private var leaseAttempted = false
    private var epoch = 0
    fun start() {
        if (started || closing || disposed) return
        started = true
        try {
            port.watch({ scheduler.post(0) { connection(true) } }, { scheduler.post(0) { connection(false) } })
            report("DIM 连接请求已发送，等待真实服务回调")
            port.connect()
            scheduler.post(15000) {
                if (!disposed && !closing && !connected) report("DIM 15 秒未连接；未执行仪表显示写入")
            }
        } catch (error: Exception) { report("连接失败：${error.cause ?: error}"); finish() }
    }
    private fun connection(value: Boolean) {
        if (disposed) return
        connected = value
        if (!value) {
            epoch++; gate.connection(false)
            releaseLease()
            report("DIM 服务断开；软件投屏状态未知")
            if (closing) finish()
            return
        }
        if (closing) { begin(false); return }
        report("DIM 已连接，等待仪表视频首帧")
        gate.connection(true)?.let(::begin)
    }
    fun video(ready: Boolean) {
        if (!closing && !disposed) gate.video(ready && allowed())?.let(::begin)
    }
    private fun begin(show: Boolean) { epoch++; attempt(show, epoch, 0) }
    private fun attempt(show: Boolean, generation: Int, retries: Int) {
        if (disposed || generation != epoch || !connected || (show && (closing || !allowed()))) return
        if (show) { enableAttempted = true; releaseConfirmed = false }
        var state = XingruiDimState(null, null)
        var failed = false
        try {
            if (show) { leaseAttempted = true; claimNative() }
            port.request(show); state = port.read()
        }
        catch (error: Exception) { failed = true; report("请求=$show 调用失败：${error.cause ?: error}") }
        val verdict = XingruiSwitchPolicy.verdict(show, state.enabled, state.internal, failed, retries)
        report("请求=$show switch=${state.enabled} internal=${state.internal} ${state.note}；软件判定=$verdict；实屏待确认")
        when (verdict) {
            XingruiSwitchVerdict.RETRY -> scheduler.post(XingruiSwitchPolicy.delayMillis(retries + 1)) {
                attempt(show, generation, retries + 1)
            }
            XingruiSwitchVerdict.GAVE_UP -> {
                report("请求=$show 已重试 ${XingruiSwitchPolicy.MAX_RETRIES} 次，仍无法确认软件状态；请导出诊断")
                releaseLease()
                if (closing) finish()
            }
            XingruiSwitchVerdict.CONFIRMED -> {
                if (!show) { releaseConfirmed = true; releaseLease() }
                if (closing) finish()
            }
        }
    }
    fun close() {
        if (closing || disposed) return
        closing = true
        epoch++
        gate.close()
        if (enableAttempted && connected) begin(false) else {
            if (enableAttempted) report("关闭时 DIM 未连接，无法确认仪表释放；请检查原厂画面")
            finish()
        }
    }
    private fun finish() {
        if (disposed) return
        disposed = true; epoch++
        var success = !enableAttempted || releaseConfirmed
        try { port.dispose() } catch (error: Exception) {
            success = false
            report("释放 DIM 服务失败：${error.cause ?: error}")
        }
        finally { releaseLease(); finished(success) }
    }
    private fun releaseLease() {
        if (!leaseAttempted) return
        leaseAttempted = false
        try { releaseNative() } catch (error: Exception) { report("恢复原车导航失败：${error.cause ?: error}") }
    }
}
