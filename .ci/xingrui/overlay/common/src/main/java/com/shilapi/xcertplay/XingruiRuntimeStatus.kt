package com.shilapi.xcertplay

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

internal object XingruiRuntimeStatus {
    private var journal: java.io.File? = null
    @Synchronized fun attach(context: android.content.Context) {
        if (journal == null) journal = java.io.File(context.filesDir, "xingrui-runtime.log")
    }
    @Synchronized fun journalText(): String = runCatching {
        val file = journal ?: return@runCatching "not initialized"
        val previous = java.io.File(file.parentFile, "xingrui-runtime.previous.log")
        (if (previous.exists()) previous.readText() else "") + (if (file.exists()) file.readText() else "")
    }.getOrElse { "journal read failed: $it" }
    private val current = linkedMapOf<String, String>()
    private val events = ArrayDeque<String>()
    @Synchronized fun record(area: String, message: String) {
        current[area] = message
        events.addLast(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()) + " $area: $message")
        while (events.size > 120) events.removeFirst()
        Log.i("XingruiRuntime", "$area: $message")
        runCatching { journal?.let { file ->
            if (file.length() > 256 * 1024) {
                val previous = java.io.File(file.parentFile, "xingrui-runtime.previous.log")
                previous.delete(); file.renameTo(previous)
            }
            file.appendText(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date()) + " $area: $message\n")
        } }
    }
    @Synchronized fun summary(): String = if (current.isEmpty()) "尚未运行适配接口。请先连接 CarPlay。" else
        current.entries.joinToString("\n\n") { "${it.key}：${it.value}" }
    @Synchronized fun report(): String = summary() + "\n\n" + events.joinToString("\n")
}
