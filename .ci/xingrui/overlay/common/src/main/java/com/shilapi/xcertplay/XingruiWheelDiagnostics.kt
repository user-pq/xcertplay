package com.shilapi.xcertplay

import android.util.Log
import android.view.KeyEvent

/** Logs unclaimed keys; standard MEDIA_NEXT/PREVIOUS remain on DiPlay's upstream path. */
internal object XingruiWheelDiagnostics {
    private const val TAG = "XingruiWheel"

    fun log(event: KeyEvent) {
        Log.i(
            TAG,
            "unclaimed key=${KeyEvent.keyCodeToString(event.keyCode)} code=${event.keyCode} " +
                "scan=${event.scanCode} device=${event.deviceId} source=0x${event.source.toString(16)} " +
                "meta=0x${event.metaState.toString(16)}",
        )
    }
}
