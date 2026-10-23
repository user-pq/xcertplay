package com.shilapi.xcertplay

internal data class XingruiExitResult(val sdkReleased: Boolean, val nativeRestored: Boolean, val timedOut: Boolean)

internal fun interface XingruiReleaseHandle { fun release(completion: (Boolean) -> Unit) }

/** Includes releases already started by Presentation.onStop; they remain registered until complete. */
internal class XingruiReleaseRegistry {
    private val handles = linkedSetOf<XingruiReleaseHandle>()
    @Synchronized fun add(handle: XingruiReleaseHandle) { handles.add(handle) }
    @Synchronized fun remove(handle: XingruiReleaseHandle) { handles.remove(handle) }
    @Synchronized fun pending(): List<((Boolean) -> Unit) -> Unit> =
        handles.map { handle -> { done -> handle.release(done) } }
}

/** Actual exit barrier. Keeps teardown behind all display releases and legacy recovery, with a deadline. */
internal class XingruiExitCoordinator(
    private val scheduler: XingruiScheduler,
    private val releases: List<((Boolean) -> Unit) -> Unit>,
    private val recoverNative: ((Boolean) -> Unit) -> Unit,
    private val complete: (XingruiExitResult) -> Unit,
) {
    private val results = arrayOfNulls<Boolean>(releases.size)
    private var native: Boolean? = null
    private var started = false
    private var finished = false
    private var recoveryAttempt = 0

    fun start() {
        if (started) return
        started = true
        scheduler.post(DEADLINE_MILLIS) { finish(true) }
        releases.forEachIndexed { index, release ->
            try { release { success -> scheduler.post(0) { display(index, success) } } }
            catch (_: Exception) { display(index, false) }
        }
        recover()
    }

    private fun display(index: Int, success: Boolean) {
        if (finished || results[index] != null) return
        results[index] = success
        checkFinished()
    }

    private fun recover() {
        if (finished || native != null) return
        val attempt = ++recoveryAttempt
        try { recoverNative { success -> scheduler.post(0) { recovered(attempt, success) } } }
        catch (_: Exception) { recovered(attempt, false) }
    }

    private var answeredAttempt = 0
    private fun recovered(attempt: Int, success: Boolean) {
        if (finished || native != null || attempt != recoveryAttempt || answeredAttempt == attempt) return
        answeredAttempt = attempt
        if (success) native = true
        else if (attempt < RECOVERY_ATTEMPTS) scheduler.post(attempt * 500L) { recover() }
        else native = false
        checkFinished()
    }

    private fun checkFinished() {
        if (native != null && results.all { it != null }) finish(false)
    }

    private fun finish(timedOut: Boolean) {
        if (finished) return
        finished = true
        complete(XingruiExitResult(results.all { it == true }, native == true, timedOut))
    }

    companion object {
        const val DEADLINE_MILLIS = 12_000L // DIM's bounded retries take at most 9 seconds of scheduled delay.
        const val RECOVERY_ATTEMPTS = 3
    }
}

/** Coalesces onStop/exit callers; Android delivery is supplied by the owner, outside the lock. */
internal class XingruiReleaseCompletion {
    private var result: Boolean? = null
    private val waiters = mutableListOf<(Boolean) -> Unit>()
    fun await(callback: (Boolean) -> Unit) {
        val ready = synchronized(this) {
            result.also { if (it == null) waiters.add(callback) }
        }
        if (ready != null) callback(ready)
    }
    fun finish(success: Boolean) {
        val callbacks = synchronized(this) {
            if (result != null) return
            result = success
            waiters.toList().also { waiters.clear() }
        }
        callbacks.forEach { runCatching { it(success) } }
    }
}
