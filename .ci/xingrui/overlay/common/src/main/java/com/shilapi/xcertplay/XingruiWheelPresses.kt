package com.shilapi.xcertplay

/** Pair consumed OEM presses with their releases; a held key sends one CarPlay command. */
internal class XingruiWheelPresses {
    private val held = mutableSetOf<Int>()
    @Synchronized fun down(code: Int, send: () -> Boolean): Boolean {
        if (code in held) return true
        if (!send()) return false
        held.add(code)
        return true
    }
    @Synchronized fun up(code: Int): Boolean = held.remove(code)
    @Synchronized fun clear() { held.clear() }
}
