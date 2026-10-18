package com.shilapi.xcertplay

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object XingruiRuntimeStatus {
    private val current = linkedMapOf<String, String>()
    private val events = ArrayDeque<String>()
    @Synchronized fun record(area: String, message: String) {
        current[area] = message
        events.addLast(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " $area: $message")
        while (events.size > 120) events.removeFirst()
        Log.i("XingruiRuntime", "$area: $message")
    }
    @Synchronized fun summary(): String = if (current.isEmpty()) "尚未运行适配接口。请先连接 CarPlay。" else
        current.entries.joinToString("\n\n") { "${it.key}：${it.value}" }
    @Synchronized fun report(): String = summary() + "\n\n" + events.joinToString("\n")
}
