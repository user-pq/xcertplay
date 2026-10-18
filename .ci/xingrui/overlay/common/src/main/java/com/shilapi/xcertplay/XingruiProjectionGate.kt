package com.shilapi.xcertplay

/** A decoder-released frame and a live SDK connection must belong to this presentation. */
internal class XingruiProjectionGate {
    private var connected = false
    private var ready = false
    private var closed = false
    private var requested = false
    fun connection(value: Boolean): Boolean? {
        connected = value
        if (!value) { requested = false; return null }
        return transition()
    }
    fun video(value: Boolean): Boolean? { ready = value; return transition() }
    fun close(): Boolean? { closed = true; return transition() }
    private fun transition(): Boolean? {
        if (!connected) return null
        val desired = ready && !closed
        if (desired == requested) return null
        requested = desired
        return desired
    }
}
