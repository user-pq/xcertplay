package com.shilapi.xcertplay

/** Runs tasks on one owner thread. Delayed tasks are invalidated by session generations. */
internal fun interface XingruiScheduler {
    fun post(delayMillis: Long, task: () -> Unit)
}
