package com.shilapi.xcertplay

internal enum class XingruiKeyAction { NEXT, PREVIOUS, PLAY_PAUSE, PLAY, PAUSE }

/** Pure key-code rules for the ECARX Input callback. No Android or SDK types, so JVM tests cover it. */
internal object XingruiKeyRouter {
    /** android.view.KeyEvent.KEYCODE_VOICE_ASSIST, inlined to stay free of Android classes. */
    const val KEYCODE_VOICE_ASSIST = 231
    /**
     * requestKeysInterception works on ECARX SDK action ids, NOT Android keycodes. Car logs (2026-10-23):
     * raw wheel key 304 -> id 200088 (ECarXRMediaPreviousAction), 305 -> 200087 (MediaNext), 302 -> 200025
     * (VolumeDown). InputImpl matches the requested list against these ids ("isValidKey keys[n]=200088").
     */
    const val SDK_MEDIA_NEXT = 200087
    const val SDK_MEDIA_PREVIOUS = 200088
    val DEFAULT_CODES: List<Int> = listOf(SDK_MEDIA_NEXT, SDK_MEDIA_PREVIOUS)
    /** Volume, home, back, power and phone-call keys stay with the vehicle (Android codes and SDK ids; 200024 is inferred). */
    val RESERVED: Set<Int> = setOf(0, 3, 4, 5, 6, 24, 25, 26, 164, 301, 302, 200024, 200025)
    private val SDK_NAME_HINTS = listOf("MEDIA", "SEEK", "PLAY", "PAUSE", "NEXT", "PREVIOUS", "VOICE", "SPEECH", "VR_")

    fun defaultAction(code: Int): XingruiKeyAction? = when (code) {
        SDK_MEDIA_NEXT, 305, 87 -> XingruiKeyAction.NEXT
        SDK_MEDIA_PREVIOUS, 304, 88 -> XingruiKeyAction.PREVIOUS
        85 -> XingruiKeyAction.PLAY_PAUSE
        126 -> XingruiKeyAction.PLAY
        127 -> XingruiKeyAction.PAUSE
        else -> null
    }

    fun isSdkKeyName(name: String): Boolean = SDK_NAME_HINTS.any { name.contains(it, ignoreCase = true) }

    /** Migrate known media mappings saved by older builds to the callback's SDK action ids. */
    fun sdkCode(code: Int): Int = when (code) {
        305, 87 -> SDK_MEDIA_NEXT
        304, 88 -> SDK_MEDIA_PREVIOUS
        else -> code
    }

    /** Codes to request. [discovered] and the voice key are only requested while learning. */
    fun candidates(learned: Collection<Int>, discovered: Collection<Int>, learning: Boolean): IntArray {
        val codes = linkedSetOf<Int>()
        codes.addAll(DEFAULT_CODES)
        codes.addAll(learned.map(::sdkCode))
        if (learning) {
            codes.addAll(discovered)
            codes.add(KEYCODE_VOICE_ASSIST)
        }
        codes.removeAll(RESERVED)
        return codes.sorted().toIntArray()
    }
}
