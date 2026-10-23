package com.shilapi.xcertplay

internal enum class XingruiWheelRole { NEXT, PREVIOUS, PLAY_PAUSE, SIRI }

/** Production callback/learning lifecycle, with SDK and Android scheduling supplied at the boundary. */
internal class XingruiWheelSession(private val port: XingruiInputPort, private val scheduler: XingruiScheduler,
    private val learned: () -> Map<XingruiWheelRole, Int>,
    private val save: (XingruiWheelRole, Int) -> Unit,
    private val send: (XingruiWheelRole?, Int) -> Boolean,
    private val inCall: () -> Boolean, private val report: (String, String) -> Unit) {
    private data class Learning(val role: XingruiWheelRole, val done: (Int) -> Unit,
        val cancel: () -> Unit, val refused: (XingruiWheelRole) -> Unit, val token: Int)
    private var learning: Learning? = null
    private var generation = 0
    private var playback = false
    private val presses = XingruiWheelPresses()
    private val learningHolds = mutableSetOf<Int>()
    private val rawHolds = mutableSetOf<Int>()
    private var requested: List<Int>? = null
    var observedCallback: Boolean = false
        private set

    private fun assignments() = learned().mapValues { XingruiKeyRouter.sdkCode(it.value) }

    @Synchronized fun playback(enabled: Boolean) {
        playback = enabled
        if (!enabled) presses.clear()
        register()
    }
    @Synchronized fun settingsChanged() { register() }
    @Synchronized fun learn(role: XingruiWheelRole, timeout: Long, done: (Int) -> Unit,
        cancel: () -> Unit, refused: (XingruiWheelRole) -> Unit): Boolean {
        cancelLearning()
        if (inCall()) return false
        val token = ++generation
        learning = Learning(role, done, cancel, refused, token)
        if (!register()) { learning = null; return false }
        scheduler.post(timeout) { synchronized(this) { if (generation == token && learning != null) cancelLearning() } }
        report("方控学习", "等待真实按键：$role")
        return true
    }
    @Synchronized fun cancelLearning() {
        generation++ // Also invalidate a captured-but-not-yet-delivered learning result.
        val old = learning
        learning = null
        if (old != null) { old.cancel(); register() }
    }
    @Synchronized private fun down(code: Int): Boolean {
        observedCallback = true
        if (rawHolds.add(code)) report("方控回调", "按下 code=$code")
        if (code in XingruiKeyRouter.RESERVED) return false
        if (code in learningHolds) return true
        if (inCall()) return false
        val pending = learning
        if (pending != null) {
            learning = null
            learningHolds.add(code)
            scheduler.post(0) {
                synchronized(this) {
                    if (generation != pending.token) return@synchronized
                    // SDK names are not enough to authorize a vehicle-reserved key for learning.
                    if (code in XingruiKeyRouter.RESERVED) { pending.cancel(); register(); return@synchronized }
                    val conflict = assignments().entries.firstOrNull { it.key != pending.role && it.value == code }?.key
                    if (conflict != null) pending.refused(conflict) else {
                        save(pending.role, code)
                        report("方控学习", "已保存 ${pending.role}=$code")
                        pending.done(code)
                    }
                    register()
                }
            }
            return true
        }
        if (!playback || code in XingruiKeyRouter.RESERVED) return false
        return presses.down(code) dispatch@ {
            val role = assignments().entries.firstOrNull { it.value == code }?.key
            if (role == null && XingruiKeyRouter.defaultAction(code) == null) return@dispatch false
            val sent = send(role, code)
            report("方控", "收到按键=$code role=$role CarPlay发送=$sent")
            sent
        }
    }
    @Synchronized private fun up(code: Int): Boolean {
        observedCallback = true
        if (rawHolds.remove(code)) report("方控回调", "松开 code=$code")
        val consumed = learningHolds.remove(code) || presses.up(code)
        // Keep one callback registered throughout an owned press, including learning.
        if (learningHolds.isEmpty()) scheduler.post(0) { synchronized(this) { register() } }
        return consumed
    }
    private fun register(): Boolean {
        if (learningHolds.isNotEmpty()) return true
        return runCatching {
            val discovering = learning != null
            val extra = if (discovering) runCatching { port.discover() }.getOrElse {
                report("方控学习", "SDK 常量读取失败：${it.cause ?: it}"); emptyList()
            } else emptyList()
            val codes = if (playback || discovering)
                XingruiKeyRouter.candidates(assignments().values, extra, discovering) else intArrayOf()
            val next = codes.toList()
            if (requested == next) return@runCatching true
            if (codes.isEmpty()) port.abandon() else {
                val result = port.request(codes, ::down, ::up)
                report("方控", "请求=${codes.joinToString()} 返回=${result?.joinToString()}；等待实际回调")
            }
            requested = next
            true
        }.getOrElse {
            requested = null
            report("方控", "注册失败：${it.cause ?: it}"); false
        }
    }
}
